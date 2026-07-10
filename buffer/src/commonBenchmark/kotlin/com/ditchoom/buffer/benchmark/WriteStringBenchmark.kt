package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup

/**
 * Benchmarks for [PlatformBuffer.writeString] — the UTF-16 -> UTF-8 encode hot path.
 *
 * This is the baseline for optimizations #1 (kill the native `utf8Length` double-walk by
 * binding simdutf `utf8_length_from_utf16le`) and #3 (drop the redundant `toCharArray()` copy)
 * from the effort-vs-win plan. On Kotlin/Native the pre-optimization path walks the chars twice:
 * once in the `str.utf8Length()` Kotlin loop (CharSequence#get virtual dispatch) for the bounds
 * check, then again inside simdutf for the transcode. These benchmarks isolate that cost.
 *
 * Scenarios (each at 64B / 1KB / 64KB):
 *  - ASCII        — single-byte output, best case for the char loop.
 *  - Multi-byte   — 3-byte CJK, where every char expands and the length walk branches most.
 *  - Emoji        — 4-byte surrogate pairs, exercising the surrogate branch of the length walk.
 *
 * The destination buffer is [BufferFactory.Default] (native memory on K/N, direct on JVM), which
 * is the allocation type the WebSocket frame path actually uses.
 *
 * Run with:
 *   ./gradlew :buffer:jvmBenchmark -PbenchmarkConfig=string
 *   ./gradlew :buffer:linuxX64Benchmark -PbenchmarkConfig=string
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class WriteStringBenchmark {
    // Char counts, not byte counts. Multi-byte/emoji strings encode to a larger byte payload.
    private val small = 64
    private val medium = 1024
    private val large = 64 * 1024

    private lateinit var asciiSmall: String
    private lateinit var asciiMedium: String
    private lateinit var asciiLarge: String
    private lateinit var cjkSmall: String
    private lateinit var cjkMedium: String
    private lateinit var cjkLarge: String
    private lateinit var emojiSmall: String
    private lateinit var emojiMedium: String
    private lateinit var emojiLarge: String

    // One destination sized for the worst case (largest char count, 4 bytes/char).
    private lateinit var destination: PlatformBuffer

    @Setup
    fun setup() {
        asciiSmall = buildAscii(small)
        asciiMedium = buildAscii(medium)
        asciiLarge = buildAscii(large)
        cjkSmall = repeatTo(CJK_PATTERN, small)
        cjkMedium = repeatTo(CJK_PATTERN, medium)
        cjkLarge = repeatTo(CJK_PATTERN, large)
        emojiSmall = repeatSurrogatePairsTo(small)
        emojiMedium = repeatSurrogatePairsTo(medium)
        emojiLarge = repeatSurrogatePairsTo(large)

        destination = BufferFactory.Default.allocate(large * 4)
    }

    private fun write(text: String): Int {
        destination.position(0)
        destination.writeString(text, Charset.UTF8)
        return destination.position()
    }

    @Benchmark fun asciiSmall(): Int = write(asciiSmall)

    @Benchmark fun asciiMedium(): Int = write(asciiMedium)

    @Benchmark fun asciiLarge(): Int = write(asciiLarge)

    @Benchmark fun cjkSmall(): Int = write(cjkSmall)

    @Benchmark fun cjkMedium(): Int = write(cjkMedium)

    @Benchmark fun cjkLarge(): Int = write(cjkLarge)

    @Benchmark fun emojiSmall(): Int = write(emojiSmall)

    @Benchmark fun emojiMedium(): Int = write(emojiMedium)

    @Benchmark fun emojiLarge(): Int = write(emojiLarge)

    companion object {
        private const val PRINTABLE_ASCII_START = 32
        private const val PRINTABLE_ASCII_RANGE = 95

        // Mixed 2- and 3-byte code points: accented Latin (é, ü) and CJK (世, 界).
        private const val CJK_PATTERN = "é世ü界"

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
            while (sb.length < charCount) {
                sb.append(pattern)
            }
            return sb.substring(0, charCount)
        }

        // Each grinning-face emoji is a surrogate pair (2 chars, 4 UTF-8 bytes).
        private fun repeatSurrogatePairsTo(charCount: Int): String {
            val sb = StringBuilder(charCount)
            // Append complete surrogate pairs so we never split one at the boundary.
            while (sb.length + 2 <= charCount) {
                sb.append('\uD83D').append('\uDE00')
            }
            // Pad any odd trailing slot with ASCII to keep the exact char count valid.
            while (sb.length < charCount) {
                sb.append(' ')
            }
            return sb.toString()
        }
    }
}
