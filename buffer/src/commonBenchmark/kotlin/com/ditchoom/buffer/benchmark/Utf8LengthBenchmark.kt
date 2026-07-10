package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.utf8Length
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
 * Isolates the [CharSequence.utf8Length] char-by-char loop (`BufferFactory.kt`).
 *
 * On Kotlin/Native this loop is ~30% self-CPU of the WebSocket cat-13 workload: `CharSequence#get`
 * virtual dispatch, `Kotlin_Any_getTypeInfo`, and `Int` boxing per char. It is the exact walk that
 * optimization #1 removes by computing the encoded byte length from the already-pinned CharArray via
 * simdutf `utf8_length_from_utf16le`. This benchmark measures that loop in isolation so the CPU delta
 * from #1 is attributable rather than buried inside the full `writeString` transcode.
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
open class Utf8LengthBenchmark {
    private val small = 64
    private val medium = 1024
    private val large = 64 * 1024

    private lateinit var asciiSmall: String
    private lateinit var asciiMedium: String
    private lateinit var asciiLarge: String
    private lateinit var cjkLarge: String
    private lateinit var emojiLarge: String

    @Setup
    fun setup() {
        asciiSmall = buildAscii(small)
        asciiMedium = buildAscii(medium)
        asciiLarge = buildAscii(large)
        cjkLarge = repeatTo("é世ü界", large)
        emojiLarge = repeatSurrogatePairsTo(large)
    }

    @Benchmark fun asciiSmall(): Int = asciiSmall.utf8Length()

    @Benchmark fun asciiMedium(): Int = asciiMedium.utf8Length()

    @Benchmark fun asciiLarge(): Int = asciiLarge.utf8Length()

    @Benchmark fun cjkLarge(): Int = cjkLarge.utf8Length()

    @Benchmark fun emojiLarge(): Int = emojiLarge.utf8Length()

    companion object {
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
            while (sb.length < charCount) {
                sb.append(pattern)
            }
            return sb.substring(0, charCount)
        }

        private fun repeatSurrogatePairsTo(charCount: Int): String {
            val sb = StringBuilder(charCount)
            while (sb.length + 2 <= charCount) {
                sb.append('\uD83D').append('\uDE00')
            }
            while (sb.length < charCount) {
                sb.append(' ')
            }
            return sb.toString()
        }
    }
}
