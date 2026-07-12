package com.ditchoom.buffer

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * Decode-path comparison for [decodeUtf8FromNative], the read-side counterpart to
 * [encodeUtf8ToNative].
 *
 * The JVM cat-13 Autobahn profile on buffer 6.9.4 showed the JDK `CharsetDecoder` read path —
 * `sun.nio.cs.UTF_8$Decoder.decodeBufferLoop` (+ its `decodeLoop` dispatcher) — as ~62% of client
 * JVM CPU. A *direct* ByteBuffer has no backing array, so `CharsetDecoder.decode` can only take
 * `decodeBufferLoop`, which does a `DirectByteBuffer.get()` — and thus a per-byte
 * `java.nio.Buffer.session()` scope check — for every byte. `decodeUtf8FromNative` reads each byte
 * through `directGetByte` (global-scope, no session check) instead, exactly as the write side does.
 *
 * This isolates that difference: identical native bytes, decoded to the identical String, once via
 * the old reused-`CharsetDecoder` path ([CharsetDecoder.decodeReusing], what `readString` used to
 * call) and once via the new [ReadBuffer.readString] fast path. CSV-friendly for diffing:
 *
 *   DECODE content=ascii chars=65536 strategy=native ns_per_op=NNNN mb_per_s=NNNN speedup=N.NN
 *
 * Run with:
 *   ./gradlew :buffer:jvmFfmTest --tests "*.ReadStringDecodeBench"
 * jvmFfmTest exercises the FFM buffers (FfmBuffer / FfmAutoBuffer) that take the native fast path —
 * measured 1.4-2.4x over the CharsetDecoder. On the plain :jvmTest (sun.misc.Unsafe / DirectJvmBuffer)
 * `readString` deliberately stays on the CharsetDecoder, so there the "native" row just re-measures
 * the baseline (~1.0x): per-byte Unsafe.getByte was 2-3x slower than decodeBufferLoop, so it is not
 * wired up. See jvm21Main/Utf8DirectDecode.kt for the gating rationale.
 */
class ReadStringDecodeBench {
    @Test
    fun benchDecode() {
        val charCounts = listOf(1024, 64 * 1024)
        val contents =
            listOf(
                "ascii" to ::buildAscii,
                "cjk" to { n: Int -> repeatTo("é世ü界", n) },
                "emoji" to ::buildEmoji,
            )

        for ((name, build) in contents) {
            for (chars in charCounts) {
                val text = build(chars)
                val bytes = text.encodeToByteArray()

                // Pre-build both inputs ONCE so the loop measures decode only, not allocation:
                // a direct ByteBuffer for the CharsetDecoder path, a native-backed buffer for readString.
                val direct = ByteBuffer.allocateDirect(bytes.size).put(bytes)
                val decoder = Charset.UTF8.toDecoder()
                val buffer = BufferFactory.Default.allocate(bytes.size).also { it.writeBytes(bytes) }

                val baseline =
                    measure {
                        (direct as java.nio.Buffer).position(0).limit(bytes.size)
                        decoder.decodeReusing(direct, bytes.size)
                    }
                val native =
                    measure {
                        buffer.position(0)
                        buffer.readString(bytes.size, Charset.UTF8)
                    }
                for ((strategy, nsPerOp) in listOf("charsetDecoder" to baseline, "native" to native)) {
                    val mbPerSec = if (nsPerOp > 0.0) bytes.size * 1000.0 / nsPerOp else 0.0
                    val speedup = if (nsPerOp > 0.0) baseline / nsPerOp else 0.0
                    println(
                        "DECODE content=$name chars=$chars bytes=${bytes.size} strategy=$strategy " +
                            "ns_per_op=${nsPerOp.toLong()} mb_per_s=${mbPerSec.toLong()} " +
                            "speedup=${(speedup * 100).toLong() / 100.0}",
                    )
                }
            }
        }
    }

    /** Returns ns/op. Warms up, then runs for at least [MIN_RUN_MS]. */
    private fun measure(decode: () -> String): Double {
        repeat(WARMUP_ITERS) { decode() }
        var iters = 0L
        var sink = 0
        val mark = TimeSource.Monotonic.markNow()
        while (mark.elapsedNow().inWholeMilliseconds < MIN_RUN_MS) {
            repeat(BATCH) { sink += decode().length }
            iters += BATCH
        }
        val elapsedNs = mark.elapsedNow().inWholeNanoseconds
        blackhole = sink
        return elapsedNs.toDouble() / iters.toDouble()
    }

    private companion object {
        @Volatile
        private var blackhole: Int = 0

        private const val WARMUP_ITERS = 2000
        private const val BATCH = 128
        private const val MIN_RUN_MS = 400L
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
