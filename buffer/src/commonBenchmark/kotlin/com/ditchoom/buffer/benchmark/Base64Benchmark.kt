package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.base64EncodedLength
import com.ditchoom.buffer.decodeBase64Into
import com.ditchoom.buffer.encodeBase64Into
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
 * Benchmarks for buffer-to-buffer Base64 encode/decode.
 *
 * Each direction has two variants on the SAME Direct buffer type:
 * - "Primitive" = current encodeBase64Into/decodeBase64Into (SIMD C cinterop on native, optimized JVM/JS)
 * - "Baseline" = naive per-byte loop via get()/writeByte()
 *
 * Both run native-memory source -> native-memory dest, so the primitive variants exercise the
 * pointer-to-pointer C fast path (buf_base64_encode / buf_base64_decode) on native targets.
 *
 * Run with: ./gradlew macosArm64BenchmarkBenchmark -Pbenchmark.filter=Base64
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class Base64Benchmark {
    private val size64k = 64 * 1024
    private val b64Size = base64EncodedLength(size64k)

    private val stdAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private lateinit var raw: PlatformBuffer
    private lateinit var b64: PlatformBuffer
    private lateinit var encodeDest: PlatformBuffer
    private lateinit var decodeDest: PlatformBuffer

    @Setup
    fun setup() {
        raw = BufferFactory.Default.allocate(size64k)
        encodeDest = BufferFactory.Default.allocate(b64Size)
        decodeDest = BufferFactory.Default.allocate(size64k)
        for (i in 0 until size64k) {
            raw.writeByte(i.toByte())
        }
        raw.resetForRead()

        b64 = BufferFactory.Default.allocate(b64Size)
        raw.encodeBase64Into(b64)
        b64.resetForRead()
        raw.resetForRead()
    }

    // ========================================================================= encode

    @Benchmark
    fun encodePrimitive(): Int {
        raw.position(0)
        raw.setLimit(size64k)
        encodeDest.resetForWrite()
        raw.encodeBase64Into(encodeDest)
        return encodeDest.get(0).toInt()
    }

    @Benchmark
    fun encodeBaseline(): Int {
        encodeDest.resetForWrite()
        var i = 0
        val full = size64k - size64k % 3
        while (i < full) {
            val n =
                ((raw.get(i).toInt() and 0xFF) shl 16) or
                    ((raw.get(i + 1).toInt() and 0xFF) shl 8) or
                    (raw.get(i + 2).toInt() and 0xFF)
            encodeDest.writeByte(stdAlphabet[(n ushr 18) and 0x3F].code.toByte())
            encodeDest.writeByte(stdAlphabet[(n ushr 12) and 0x3F].code.toByte())
            encodeDest.writeByte(stdAlphabet[(n ushr 6) and 0x3F].code.toByte())
            encodeDest.writeByte(stdAlphabet[n and 0x3F].code.toByte())
            i += 3
        }
        return encodeDest.get(0).toInt()
    }

    // ========================================================================= decode

    @Benchmark
    fun decodePrimitive(): Int {
        b64.position(0)
        b64.setLimit(b64Size)
        decodeDest.resetForWrite()
        b64.decodeBase64Into(decodeDest)
        return decodeDest.get(0).toInt()
    }

    @Benchmark
    fun decodeBaseline(): Int {
        decodeDest.resetForWrite()
        var acc = 0
        var bits = 0
        var i = 0
        while (i < b64Size) {
            val c = b64.get(i).toInt() and 0xFF
            i++
            if (c == '='.code) break
            acc = (acc shl 6) or sextet(c)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                decodeDest.writeByte(((acc ushr bits) and 0xFF).toByte())
            }
        }
        return decodeDest.get(0).toInt()
    }

    private fun sextet(c: Int): Int =
        when (c) {
            in 0x41..0x5A -> c - 0x41
            in 0x61..0x7A -> c - 0x61 + 26
            in 0x30..0x39 -> c - 0x30 + 52
            0x2B, 0x2D -> 62
            else -> 63
        }
}
