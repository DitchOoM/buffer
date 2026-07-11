// UTF-8 bit patterns (0x80/0xC0/0xE0/0xF0 leads, 0x3F continuation, 0xD800..0xDFFF surrogates) are
// clearer as literals than as named constants; this is a benchmark, not production.
@file:Suppress("MagicNumber")

package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * Char-access strategy comparison for the [encodeUtf8ToNative] hot path.
 *
 * The JVM re-profile of the Autobahn cat-13 run on buffer 6.9.4 (which shipped #285's direct-to-native
 * UTF-8 encode, eliminating `java.nio.Buffer.session()`) showed the new #2 JVM CPU frame is
 * `java.lang.String.charAt` (108 samples / 35% of execution). That is the per-char read in the encode
 * loop: `encodeUtf8ToNative` walks a `CharSequence`, so `text[i]` compiles to an **invokeinterface**
 * `CharSequence.get(int)` — a virtual dispatch the JIT struggles to inline across the three call sites.
 *
 * This isolates the char-access cost from everything else by running the identical UTF-8 transform into
 * an identical reused `ByteArray` scratch (byte-store side held constant), varying only how chars are
 * read. Because the production byte-write via `directPutByte` is likewise constant across strategies,
 * the throughput delta measured here is the delta the production change would capture.
 *
 *  - A `charSequence` — `text[i]` on a `CharSequence` (invokeinterface). Matches today's encoder.
 *  - B `getChars`     — one bulk `String.getChars` into a reused `char[]`, then `chars[i]` (caload).
 *                       One copy into thread-local-style scratch, no per-call allocation.
 *  - C `stringCharAt` — `(text as String)` then `text[i]` (invokevirtual on the final `String`,
 *                       inlinable). No copy — honours the "avoid copies" constraint.
 *
 * Content-shaped because the win scales with per-char branch count: ASCII (cheapest branch) shows the
 * most char-access-bound behaviour; 3-byte CJK and 4-byte emoji (surrogate branch) do more work per
 * char so the relative delta shrinks. CSV-friendly for diffing:
 *
 *   CHARACCESS content=ascii chars=65536 strategy=getChars ns_per_op=NNNN mb_per_s=NNNN speedup=N.NN
 *
 * Run with:
 *   ./gradlew :buffer:jvmTest --tests "*.WriteStringCharAccessBench"
 * (JDK 21 toolchain also exercises the same char-access code; the byte-store side is a plain array.)
 */
class WriteStringCharAccessBench {
    @Test
    fun benchCharAccess() {
        val charCounts = listOf(1024, 64 * 1024)
        val contents =
            listOf(
                "ascii" to ::buildAscii,
                "cjk" to { n: Int -> repeatTo("é世ü界", n) },
                "emoji" to ::buildEmoji,
            )

        val dst = ByteArray(64 * 1024 * MAX_UTF8_BYTES_PER_CHAR)
        val scratch = CharArray(64 * 1024)

        for ((name, build) in contents) {
            for (chars in charCounts) {
                val text = build(chars)
                val baseline = measure(text) { t -> encodeViaCharSequence(t, dst) }
                val strategies =
                    listOf(
                        "charSequence" to baseline,
                        "getChars" to measure(text) { t -> encodeViaGetChars(t, scratch, dst) },
                        "stringCharAt" to measure(text) { t -> encodeViaStringCharAt(t, dst) },
                    )
                for ((strategy, result) in strategies) {
                    val (nsPerOp, byteLen) = result
                    val mbPerSec = if (nsPerOp > 0.0) byteLen * 1000.0 / nsPerOp else 0.0
                    val speedup = if (nsPerOp > 0.0) baseline.first / nsPerOp else 0.0
                    println(
                        "CHARACCESS content=$name chars=$chars bytes=$byteLen strategy=$strategy " +
                            "ns_per_op=${nsPerOp.toLong()} mb_per_s=${mbPerSec.toLong()} " +
                            "speedup=${(speedup * 100).toLong() / 100.0}",
                    )
                }
            }
        }
    }

    /** Returns (ns/op, encoded byte count). Warms up, then runs for at least [MIN_RUN_MS]. */
    private fun measure(
        text: String,
        encode: (String) -> Int,
    ): Pair<Double, Int> {
        repeat(WARMUP_ITERS) { encode(text) }
        val byteLen = encode(text)

        var iters = 0L
        var sink = 0L
        val mark = TimeSource.Monotonic.markNow()
        while (mark.elapsedNow().inWholeMilliseconds < MIN_RUN_MS) {
            repeat(BATCH) { sink += encode(text) }
            iters += BATCH
        }
        val elapsedNs = mark.elapsedNow().inWholeNanoseconds
        blackhole = sink
        return (elapsedNs.toDouble() / iters.toDouble()) to byteLen
    }

    // --- Strategy A: CharSequence.get (invokeinterface) — mirrors the current encodeUtf8ToNative. ---
    @Suppress("CyclomaticComplexMethod")
    private fun encodeViaCharSequence(
        text: CharSequence,
        dst: ByteArray,
    ): Int {
        var j = 0
        val len = text.length
        var i = 0
        while (i < len) {
            val c = text[i].code
            when {
                c < 0x80 -> dst[j++] = c.toByte()
                c < 0x800 -> {
                    dst[j++] = (0xC0 or (c ushr 6)).toByte()
                    dst[j++] = (0x80 or (c and 0x3F)).toByte()
                }
                c < 0xD800 || c >= 0xE000 -> {
                    dst[j++] = (0xE0 or (c ushr 12)).toByte()
                    dst[j++] = (0x80 or ((c ushr 6) and 0x3F)).toByte()
                    dst[j++] = (0x80 or (c and 0x3F)).toByte()
                }
                else -> {
                    val low = text[i + 1].code
                    val cp = 0x10000 + ((c - 0xD800) shl 10) + (low - 0xDC00)
                    dst[j++] = (0xF0 or (cp ushr 18)).toByte()
                    dst[j++] = (0x80 or ((cp ushr 12) and 0x3F)).toByte()
                    dst[j++] = (0x80 or ((cp ushr 6) and 0x3F)).toByte()
                    dst[j++] = (0x80 or (cp and 0x3F)).toByte()
                    i += 1
                }
            }
            i += 1
        }
        return j
    }

    // --- Strategy B: bulk getChars into a reused char[], then plain array loads (caload). ---
    @Suppress("CyclomaticComplexMethod")
    private fun encodeViaGetChars(
        text: String,
        chars: CharArray,
        dst: ByteArray,
    ): Int {
        val len = text.length
        // Kotlin's String.toCharArray(destination, ...) delegates to the JVM String.getChars intrinsic.
        text.toCharArray(chars, 0, 0, len)
        var j = 0
        var i = 0
        while (i < len) {
            val c = chars[i].code
            when {
                c < 0x80 -> dst[j++] = c.toByte()
                c < 0x800 -> {
                    dst[j++] = (0xC0 or (c ushr 6)).toByte()
                    dst[j++] = (0x80 or (c and 0x3F)).toByte()
                }
                c < 0xD800 || c >= 0xE000 -> {
                    dst[j++] = (0xE0 or (c ushr 12)).toByte()
                    dst[j++] = (0x80 or ((c ushr 6) and 0x3F)).toByte()
                    dst[j++] = (0x80 or (c and 0x3F)).toByte()
                }
                else -> {
                    val low = chars[i + 1].code
                    val cp = 0x10000 + ((c - 0xD800) shl 10) + (low - 0xDC00)
                    dst[j++] = (0xF0 or (cp ushr 18)).toByte()
                    dst[j++] = (0x80 or ((cp ushr 12) and 0x3F)).toByte()
                    dst[j++] = (0x80 or ((cp ushr 6) and 0x3F)).toByte()
                    dst[j++] = (0x80 or (cp and 0x3F)).toByte()
                    i += 1
                }
            }
            i += 1
        }
        return j
    }

    // --- Strategy C: String smart-cast — invokevirtual charAt on the final String, no copy. ---
    @Suppress("CyclomaticComplexMethod")
    private fun encodeViaStringCharAt(
        text: String,
        dst: ByteArray,
    ): Int {
        var j = 0
        val len = text.length
        var i = 0
        while (i < len) {
            val c = text[i].code
            when {
                c < 0x80 -> dst[j++] = c.toByte()
                c < 0x800 -> {
                    dst[j++] = (0xC0 or (c ushr 6)).toByte()
                    dst[j++] = (0x80 or (c and 0x3F)).toByte()
                }
                c < 0xD800 || c >= 0xE000 -> {
                    dst[j++] = (0xE0 or (c ushr 12)).toByte()
                    dst[j++] = (0x80 or ((c ushr 6) and 0x3F)).toByte()
                    dst[j++] = (0x80 or (c and 0x3F)).toByte()
                }
                else -> {
                    val low = text[i + 1].code
                    val cp = 0x10000 + ((c - 0xD800) shl 10) + (low - 0xDC00)
                    dst[j++] = (0xF0 or (cp ushr 18)).toByte()
                    dst[j++] = (0x80 or ((cp ushr 12) and 0x3F)).toByte()
                    dst[j++] = (0x80 or ((cp ushr 6) and 0x3F)).toByte()
                    dst[j++] = (0x80 or (cp and 0x3F)).toByte()
                    i += 1
                }
            }
            i += 1
        }
        return j
    }

    private companion object {
        @Volatile
        private var blackhole: Long = 0

        private const val WARMUP_ITERS = 2000
        private const val BATCH = 128
        private const val MIN_RUN_MS = 400L
        private const val MAX_UTF8_BYTES_PER_CHAR = 4
        private const val PRINTABLE_ASCII_START = 32
        private const val PRINTABLE_ASCII_RANGE = 95

        private fun buildAscii(charCount: Int): String {
            val sb = StringBuilder(charCount)
            for (i in 0 until charCount) {
                sb.append((PRINTABLE_ASCII_START + (i % PRINTABLE_ASCII_RANGE)).toChar())
            }
            return sb.toString()
        }

        private fun repeatTo(
            pattern: String,
            charCount: Int,
        ): String {
            val sb = StringBuilder(charCount)
            while (sb.length < charCount) sb.append(pattern)
            return sb.substring(0, charCount)
        }

        private fun buildEmoji(charCount: Int): String {
            val sb = StringBuilder(charCount)
            while (sb.length + 2 <= charCount) sb.append('\uD83D').append('\uDE00')
            while (sb.length < charCount) sb.append(' ')
            return sb.toString()
        }
    }
}
