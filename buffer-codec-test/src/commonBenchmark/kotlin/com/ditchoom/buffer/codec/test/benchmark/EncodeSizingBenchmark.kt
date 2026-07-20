package com.ditchoom.buffer.codec.test.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.AsciiStringCodec
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Encoder
import com.ditchoom.buffer.codec.encodeToPlatformBuffer
import com.ditchoom.buffer.codec.test.protocols.asciistring.AsciiGreeting
import com.ditchoom.buffer.codec.test.protocols.asciistring.AsciiGreetingCodec
import com.ditchoom.buffer.codec.test.protocols.count.CountFixedList
import com.ditchoom.buffer.codec.test.protocols.count.CountFixedListCodec
import com.ditchoom.buffer.codec.test.protocols.count.CountPoint
import com.ditchoom.buffer.codec.test.protocols.simple.TwoStrings
import com.ditchoom.buffer.codec.test.protocols.simple.TwoStringsCodec
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
 * Measures `encodeToPlatformBuffer` sizing strategies across the three
 * wireSize regimes:
 *
 * - **BackPatch via `@UseCodec`** ([AsciiGreeting]) — the user codec reports
 *   `Exact(value.length)` but message-level codegen currently collapses to
 *   BackPatch, so encode pays the 64-byte-doubling grow-and-retry loop. The
 *   `@UseCodec` Exact promotion should move these rows to single-allocation;
 *   the before/after delta on this fixture is the promotion's whole case.
 * - **BackPatch via bare UTF-8 strings** ([TwoStrings]) — stays BackPatch
 *   until/unless UTF-8 pre-measuring lands; baseline for that decision.
 * - **Exact control** ([CountFixedList]) — already single-allocation; the
 *   floor the BackPatch rows should approach.
 *
 * Plus a micro pair isolating [AsciiStringCodec]'s validation scan from the
 * underlying bulk `writeString`, into a pre-allocated buffer (no sizing loop).
 *
 * Sizes: SMALL fits the 64-byte initial estimate (zero retries — BackPatch's
 * best case), MEDIUM forces ~3 doublings, LARGE ~7.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class EncodeSizingBenchmark {
    private lateinit var asciiSmall: AsciiGreeting
    private lateinit var asciiMedium: AsciiGreeting
    private lateinit var asciiLarge: AsciiGreeting
    private lateinit var utf8Small: TwoStrings
    private lateinit var utf8Medium: TwoStrings
    private lateinit var utf8Large: TwoStrings
    private lateinit var fixedControl: CountFixedList
    private lateinit var largeAsciiText: String
    private lateinit var scratch: PlatformBuffer

    @Setup
    fun setup() {
        asciiSmall = AsciiGreeting("x".repeat(SMALL_CHARS))
        asciiMedium = AsciiGreeting("x".repeat(MEDIUM_CHARS))
        asciiLarge = AsciiGreeting("x".repeat(LARGE_CHARS))
        // Split across the two fields so both prefixes participate.
        utf8Small = TwoStrings("x".repeat(SMALL_CHARS / 2), "y".repeat(SMALL_CHARS / 2))
        utf8Medium = TwoStrings("x".repeat(MEDIUM_CHARS / 2), "y".repeat(MEDIUM_CHARS / 2))
        utf8Large = TwoStrings("x".repeat(LARGE_CHARS / 2), "y".repeat(LARGE_CHARS / 2))
        fixedControl = CountFixedList(id = 1u, points = List(MEDIUM_CHARS / 3) { CountPoint(it.toUShort(), 0u) })
        largeAsciiText = "x".repeat(LARGE_CHARS)
        scratch = BufferFactory.Default.allocate(LARGE_CHARS + SCRATCH_SLACK)
    }

    // ---- encodeToPlatformBuffer: @UseCodec ASCII (promotion target) -------

    @Benchmark
    fun asciiGreetingSmall(): Int = encodeAndFree(AsciiGreetingCodec, asciiSmall)

    @Benchmark
    fun asciiGreetingMedium(): Int = encodeAndFree(AsciiGreetingCodec, asciiMedium)

    @Benchmark
    fun asciiGreetingLarge(): Int = encodeAndFree(AsciiGreetingCodec, asciiLarge)

    // ---- encodeToPlatformBuffer: bare UTF-8 strings (stays BackPatch) -----

    @Benchmark
    fun utf8TwoStringsSmall(): Int = encodeAndFree(TwoStringsCodec, utf8Small)

    @Benchmark
    fun utf8TwoStringsMedium(): Int = encodeAndFree(TwoStringsCodec, utf8Medium)

    @Benchmark
    fun utf8TwoStringsLarge(): Int = encodeAndFree(TwoStringsCodec, utf8Large)

    // ---- encodeToPlatformBuffer: Exact control -----------------------------

    @Benchmark
    fun fixedListControl(): Int = encodeAndFree(CountFixedListCodec, fixedControl)

    // ---- micro: validation scan vs bare bulk write (no sizing loop) --------

    @Benchmark
    fun asciiCodecEncodeIntoPreallocated(): Int {
        scratch.resetForWrite()
        AsciiStringCodec.encode(scratch, largeAsciiText, EncodeContext.Empty)
        return scratch.position()
    }

    @Benchmark
    fun bareWriteStringIntoPreallocated(): Int {
        scratch.resetForWrite()
        scratch.writeString(largeAsciiText, Charset.UTF8)
        return scratch.position()
    }

    private fun <T> encodeAndFree(
        codec: Encoder<T>,
        value: T,
    ): Int {
        val buffer = codec.encodeToPlatformBuffer(value)
        val size = buffer.limit()
        buffer.freeNativeMemory()
        return size
    }

    private companion object {
        const val SMALL_CHARS = 24 // body fits the 64-byte initial estimate
        const val MEDIUM_CHARS = 400 // ~3 doublings from 64
        const val LARGE_CHARS = 8_000 // ~7 doublings from 64
        const val SCRATCH_SLACK = 64
    }
}
