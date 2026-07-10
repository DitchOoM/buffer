@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * Nanosecond-resolution micro-benchmark for the production [NativeBuffer.writeString] hot path.
 *
 * Complements the kotlinx-benchmark [WriteStringBenchmark] / [Utf8LengthBenchmark], which cannot run
 * on Kotlin/Native here (the plugin's native source generator can't resolve the simdutf cinterop
 * klib). This test drives the *real* `writeString` so the effort-vs-win #1/#3 change — replacing the
 * `str.utf8Length()` scalar char loop with simdutf `utf8_length_from_utf16le` computed from the same
 * pinned CharArray — is measurable on the K/N target it actually changes.
 *
 * It is content-shaped on purpose: the removed loop's cost scales with the per-char branch count, so
 * ASCII (cheapest branch), 3-byte CJK, and 4-byte emoji (surrogate branch) are each measured at
 * 64 B / 1 KB / 64 KB. Output lines are CSV-friendly for before/after diffing:
 *
 *   MICROBENCH content=cjk chars=65536 ns_per_op=NNNN mb_per_s=NNNN
 *
 * Run with:
 *   ./gradlew :buffer:linuxX64Test --tests "*.WriteStringMicroBenchTest"
 */
class WriteStringMicroBenchTest {
    @Test
    fun microBenchWriteString() {
        val charCounts = listOf(64, 1024, 64 * 1024)
        val contents =
            listOf(
                "ascii" to ::buildAscii,
                "cjk" to { n: Int -> repeatTo("é世ü界", n) },
                "emoji" to ::buildEmoji,
            )

        // Destination sized for the worst case (largest char count, 4 bytes/char).
        val dst = NativeBuffer.allocate(64 * 1024 * 4)
        try {
            for ((name, build) in contents) {
                for (chars in charCounts) {
                    val text = build(chars)
                    val (nsPerOp, byteLen) = measure(dst, text)
                    val mbPerSec = if (nsPerOp > 0.0) byteLen * 1000.0 / nsPerOp else 0.0
                    println(
                        "MICROBENCH content=$name chars=$chars bytes=$byteLen " +
                            "ns_per_op=${nsPerOp.toLong()} mb_per_s=${mbPerSec.toLong()}",
                    )
                }
            }
        } finally {
            dst.freeNativeMemory()
        }
    }

    /** Returns (ns/op, encoded byte count). Warms up, then runs for at least [MIN_RUN_MS]. */
    private fun measure(
        dst: NativeBuffer,
        text: String,
    ): Pair<Double, Int> {
        // Warmup.
        repeat(WARMUP_ITERS) {
            dst.position(0)
            dst.writeString(text, Charset.UTF8)
        }
        dst.position(0)
        dst.writeString(text, Charset.UTF8)
        val byteLen = dst.position()

        var iters = 0L
        val mark = TimeSource.Monotonic.markNow()
        while (mark.elapsedNow().inWholeMilliseconds < MIN_RUN_MS) {
            // Run a batch between clock reads so the timer check isn't the bottleneck.
            repeat(BATCH) {
                dst.position(0)
                dst.writeString(text, Charset.UTF8)
            }
            iters += BATCH
        }
        val elapsedNs = mark.elapsedNow().inWholeNanoseconds
        return (elapsedNs.toDouble() / iters.toDouble()) to byteLen
    }

    private companion object {
        private const val WARMUP_ITERS = 200
        private const val BATCH = 64
        private const val MIN_RUN_MS = 300L
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
