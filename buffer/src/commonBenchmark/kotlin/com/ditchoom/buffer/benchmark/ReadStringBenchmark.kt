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
 * Benchmarks for [PlatformBuffer.readString] — the UTF-8 -> UTF-16 decode hot path on the
 * WebSocket receive side.
 *
 * This is the baseline for optimization #2 from the effort-vs-win plan: on the JVM the
 * pre-optimization path built a fresh `CharsetDecoder` per call and decoded into a freshly
 * allocated `HeapCharBuffer` sized to the whole payload — the single largest JVM allocation on the
 * receive path (~49% of allocations in the Autobahn cat-13 trace). The optimization reuses a
 * thread-local decoder and CharBuffer; the produced String is the only remaining allocation.
 * Run with `-prof gc` to see the alloc-rate delta, not just throughput.
 *
 * Scenarios (each at 64B / 1KB / 64KB of *chars*, matching [WriteStringBenchmark]):
 *  - ASCII        — single-byte input, the common WebSocket text-frame case.
 *  - Multi-byte   — mixed 2- and 3-byte code points (accented Latin + CJK).
 *  - Emoji        — 4-byte surrogate pairs, exercising the surrogate branch of the decoder.
 *
 * The source buffer is [BufferFactory.Default] (native memory on K/N, direct on JVM), the same
 * allocation type the WebSocket frame path reads from.
 *
 * Run with:
 *   ./gradlew :buffer:jvmBenchmark -PbenchmarkConfig=string
 *   ./gradlew :buffer:jvmBenchmark -PbenchmarkConfig=string -Pprof=gc
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class ReadStringBenchmark {
    // Char counts, not byte counts. Multi-byte/emoji strings decode from a larger byte payload.
    private val small = 64
    private val medium = 1024
    private val large = 64 * 1024

    private lateinit var asciiSmall: Source
    private lateinit var asciiMedium: Source
    private lateinit var asciiLarge: Source
    private lateinit var cjkSmall: Source
    private lateinit var cjkMedium: Source
    private lateinit var cjkLarge: Source
    private lateinit var emojiSmall: Source
    private lateinit var emojiMedium: Source
    private lateinit var emojiLarge: Source

    @Setup
    fun setup() {
        asciiSmall = encode(buildAscii(small))
        asciiMedium = encode(buildAscii(medium))
        asciiLarge = encode(buildAscii(large))
        cjkSmall = encode(repeatTo(CJK_PATTERN, small))
        cjkMedium = encode(repeatTo(CJK_PATTERN, medium))
        cjkLarge = encode(repeatTo(CJK_PATTERN, large))
        emojiSmall = encode(repeatSurrogatePairsTo(small))
        emojiMedium = encode(repeatSurrogatePairsTo(medium))
        emojiLarge = encode(repeatSurrogatePairsTo(large))
    }

    private fun read(source: Source): String {
        source.buffer.position(0)
        return source.buffer.readString(source.byteLength, Charset.UTF8)
    }

    @Benchmark fun asciiSmall(): String = read(asciiSmall)

    @Benchmark fun asciiMedium(): String = read(asciiMedium)

    @Benchmark fun asciiLarge(): String = read(asciiLarge)

    @Benchmark fun cjkSmall(): String = read(cjkSmall)

    @Benchmark fun cjkMedium(): String = read(cjkMedium)

    @Benchmark fun cjkLarge(): String = read(cjkLarge)

    @Benchmark fun emojiSmall(): String = read(emojiSmall)

    @Benchmark fun emojiMedium(): String = read(emojiMedium)

    @Benchmark fun emojiLarge(): String = read(emojiLarge)

    /** A source buffer pre-filled with one UTF-8 encoded string, plus its exact byte length. */
    private class Source(
        val buffer: PlatformBuffer,
        val byteLength: Int,
    )

    companion object {
        private const val PRINTABLE_ASCII_START = 32
        private const val PRINTABLE_ASCII_RANGE = 95

        // Worst-case UTF-8 expansion: a surrogate pair (2 chars) encodes to 4 bytes.
        private const val MAX_UTF8_BYTES_PER_CHAR = 4

        // Mixed 2- and 3-byte code points: accented Latin (é, ü) and CJK (世, 界).
        private const val CJK_PATTERN = "é世ü界"

        /** Encode [text] to UTF-8 into a fresh buffer and flip it for reading. */
        private fun encode(text: String): Source {
            val buffer = BufferFactory.Default.allocate(text.length * MAX_UTF8_BYTES_PER_CHAR)
            buffer.writeString(text, Charset.UTF8)
            val byteLength = buffer.position()
            buffer.resetForRead()
            return Source(buffer, byteLength)
        }

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
