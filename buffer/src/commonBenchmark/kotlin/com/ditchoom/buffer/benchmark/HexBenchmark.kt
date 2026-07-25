package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.decodeHexInto
import com.ditchoom.buffer.encodeHexInto
import com.ditchoom.buffer.fromHexString
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.toHexString
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
 * Benchmarks for hex encode/decode — buffer-to-buffer, and the String conversions.
 *
 * The buffer-to-buffer pair has two variants on the SAME Direct buffer type:
 * - "Primitive" = current encodeHexInto/decodeHexInto (SIMD C cinterop on native, optimized on JVM/JS)
 * - "Baseline" = naive per-byte loop via get()/writeByte() (what a caller would hand-write)
 *
 * Both run native-memory source -> native-memory dest, so the primitive variants exercise the
 * pointer-to-pointer C fast path (buf_hex_encode / buf_hex_decode) on native targets.
 *
 * The String pair (toHexString / fromHexString) is measured against the shapes it replaced —
 * "Staged" allocates an intermediate buffer and reads it back, "SubstringParse" is the per-byte
 * `substring(i, i + 2).toInt(16)` loop hand-rolled decoders reach for.
 *
 * Run with: ./gradlew macosArm64BenchmarkBenchmark -Pbenchmark.filter=Hex
 */
@Suppress("TooManyFunctions") // one @Benchmark per variant under test, plus the two baseline helpers
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class HexBenchmark {
    private val size64k = 64 * 1024
    private val hexSize = size64k * 2

    /** Payload size for the String conversions — a realistic log line / crypto blob, not a 64K blast. */
    private val stringSize = 1024
    private lateinit var hexText: String

    /**
     * The String benchmarks allocate per invocation. On WasmJs `BufferFactory.Default` is a
     * non-reclaiming bump allocator over a 256MB arena, so a Default-backed loop OOMs long before the
     * measurement window closes — these variants are pinned to managed() so every target can run them.
     */
    private val managed = BufferFactory.managed()

    private lateinit var raw: PlatformBuffer
    private lateinit var hex: PlatformBuffer
    private lateinit var encodeDest: PlatformBuffer
    private lateinit var decodeDest: PlatformBuffer

    @Setup
    fun setup() {
        raw = BufferFactory.Default.allocate(size64k)
        encodeDest = BufferFactory.Default.allocate(hexSize)
        decodeDest = BufferFactory.Default.allocate(size64k)
        for (i in 0 until size64k) {
            raw.writeByte(i.toByte())
        }
        raw.resetForRead()

        // Pre-encode a hex blob to feed the decode benchmarks.
        hex = BufferFactory.Default.allocate(hexSize)
        raw.encodeHexInto(hex)
        hex.resetForRead()
        raw.resetForRead()

        hexText = raw.toHexString(0, stringSize)
    }

    // ========================================================================= encode

    @Benchmark
    fun encodePrimitive(): Int {
        raw.position(0)
        raw.setLimit(size64k)
        encodeDest.resetForWrite()
        raw.encodeHexInto(encodeDest)
        return encodeDest.get(0).toInt()
    }

    @Benchmark
    fun encodeBaseline(): Int {
        encodeDest.resetForWrite()
        var i = 0
        while (i < size64k) {
            val b = raw.get(i).toInt() and 0xFF
            encodeDest.writeByte(nibble(b ushr NIBBLE_BITS))
            encodeDest.writeByte(nibble(b and 0x0F))
            i++
        }
        return encodeDest.get(0).toInt()
    }

    // ========================================================================= decode

    @Benchmark
    fun decodePrimitive(): Int {
        hex.position(0)
        hex.setLimit(hexSize)
        decodeDest.resetForWrite()
        hex.decodeHexInto(decodeDest)
        return decodeDest.get(0).toInt()
    }

    @Benchmark
    fun decodeBaseline(): Int {
        decodeDest.resetForWrite()
        var i = 0
        while (i < hexSize) {
            val hi = hexVal(hex.get(i).toInt() and 0xFF)
            val lo = hexVal(hex.get(i + 1).toInt() and 0xFF)
            decodeDest.writeByte(((hi shl NIBBLE_BITS) or lo).toByte())
            i += 2
        }
        return decodeDest.get(0).toInt()
    }

    // ========================================================================= String conversions
    //
    // toHexString / fromHexString against the shapes they replaced. "Staged" is the
    // allocate-a-buffer-encode-read-it-back dance; "SubstringParse" is the per-byte
    // `substring(i, i+2).toInt(16)` loop that hand-rolled decoders reach for.

    @Benchmark
    fun toHexStringDirect(): Int = raw.toHexString(0, stringSize).length

    @Benchmark
    fun toHexStringStaged(): Int {
        val dest = managed.allocate(stringSize * 2)
        raw.encodeHexInto(dest, 0, stringSize)
        dest.resetForRead()
        return dest.readString(stringSize * 2).length
    }

    @Benchmark
    fun fromHexStringDirect(): Int = managed.fromHexString(hexText).remaining()

    @Benchmark
    fun fromHexStringStaged(): Int {
        val ascii = managed.allocate(hexText.length)
        ascii.writeString(hexText)
        ascii.resetForRead()
        val out = managed.allocate(hexText.length / 2)
        ascii.decodeHexInto(out, 0, hexText.length)
        out.resetForRead()
        return out.remaining()
    }

    @Benchmark
    fun fromHexStringSubstringParse(): Int {
        val n = hexText.length / 2
        val out = managed.allocate(n)
        for (i in 0 until n) out.writeByte(hexText.substring(i * 2, i * 2 + 2).toInt(HEX_RADIX).toByte())
        out.resetForRead()
        return out.remaining()
    }

    private fun nibble(n: Int): Byte {
        val ascii = if (n < DECIMAL_BASE) n + ASCII_ZERO else n - DECIMAL_BASE + ASCII_LOWER_A
        return ascii.toByte()
    }

    private fun hexVal(c: Int): Int =
        when (c) {
            in ASCII_ZERO..ASCII_NINE -> c - ASCII_ZERO
            in ASCII_LOWER_A..ASCII_LOWER_F -> c - ASCII_LOWER_A + DECIMAL_BASE
            in ASCII_UPPER_A..ASCII_UPPER_F -> c - ASCII_UPPER_A + DECIMAL_BASE
            else -> 0
        }

    private companion object {
        private const val NIBBLE_BITS = 4
        private const val DECIMAL_BASE = 10
        private const val ASCII_ZERO = 0x30
        private const val ASCII_NINE = 0x39
        private const val ASCII_LOWER_A = 0x61
        private const val ASCII_LOWER_F = 0x66
        private const val ASCII_UPPER_A = 0x41
        private const val ASCII_UPPER_F = 0x46
        private const val HEX_RADIX = 16
    }
}
