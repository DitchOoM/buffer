package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.decodeHexInto
import com.ditchoom.buffer.encodeHexInto
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
 * Benchmarks for buffer-to-buffer hex encode/decode.
 *
 * Each direction has two variants on the SAME Direct buffer type:
 * - "Primitive" = current encodeHexInto/decodeHexInto (SIMD C cinterop on native, optimized on JVM/JS)
 * - "Baseline" = naive per-byte loop via get()/writeByte() (what a caller would hand-write)
 *
 * Both run native-memory source -> native-memory dest, so the primitive variants exercise the
 * pointer-to-pointer C fast path (buf_hex_encode / buf_hex_decode) on native targets.
 *
 * Run with: ./gradlew macosArm64BenchmarkBenchmark -Pbenchmark.filter=Hex
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class HexBenchmark {
    private val size64k = 64 * 1024
    private val hexSize = size64k * 2

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
            encodeDest.writeByte(nibble(b ushr 4))
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
            decodeDest.writeByte(((hi shl 4) or lo).toByte())
            i += 2
        }
        return decodeDest.get(0).toInt()
    }

    private fun nibble(n: Int): Byte = (if (n < 10) n + 0x30 else n - 10 + 0x61).toByte()

    private fun hexVal(c: Int): Int =
        when (c) {
            in 0x30..0x39 -> c - 0x30
            in 0x61..0x66 -> c - 0x61 + 10
            in 0x41..0x46 -> c - 0x41 + 10
            else -> 0
        }
}
