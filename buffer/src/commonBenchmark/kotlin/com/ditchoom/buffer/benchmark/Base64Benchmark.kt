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
        val full = size64k - size64k % BYTES_PER_GROUP
        while (i < full) {
            val n =
                ((raw.get(i).toInt() and BYTE_MASK) shl SHIFT_16) or
                    ((raw.get(i + 1).toInt() and BYTE_MASK) shl SHIFT_8) or
                    (raw.get(i + 2).toInt() and BYTE_MASK)
            encodeDest.writeByte(stdAlphabet[(n ushr SHIFT_18) and SEXTET_MASK].code.toByte())
            encodeDest.writeByte(stdAlphabet[(n ushr SHIFT_12) and SEXTET_MASK].code.toByte())
            encodeDest.writeByte(stdAlphabet[(n ushr SHIFT_6) and SEXTET_MASK].code.toByte())
            encodeDest.writeByte(stdAlphabet[n and SEXTET_MASK].code.toByte())
            i += BYTES_PER_GROUP
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
            val c = b64.get(i).toInt() and BYTE_MASK
            i++
            if (c == '='.code) break
            acc = (acc shl SEXTET_BITS) or sextet(c)
            bits += SEXTET_BITS
            if (bits >= BYTE_BITS) {
                bits -= BYTE_BITS
                decodeDest.writeByte(((acc ushr bits) and BYTE_MASK).toByte())
            }
        }
        return decodeDest.get(0).toInt()
    }

    private fun sextet(c: Int): Int =
        when (c) {
            in ASCII_A..ASCII_Z -> c - ASCII_A
            in ASCII_LOWER_A..ASCII_LOWER_Z -> c - ASCII_LOWER_A + UPPERCASE_COUNT
            in ASCII_ZERO..ASCII_NINE -> c - ASCII_ZERO + LETTER_COUNT
            ASCII_PLUS, ASCII_MINUS -> INDEX_PLUS
            else -> INDEX_SLASH
        }

    private companion object {
        private const val BYTES_PER_GROUP = 3
        private const val BYTE_MASK = 0xFF
        private const val SEXTET_MASK = 0x3F
        private const val SEXTET_BITS = 6
        private const val BYTE_BITS = 8
        private const val SHIFT_6 = 6
        private const val SHIFT_8 = 8
        private const val SHIFT_12 = 12
        private const val SHIFT_16 = 16
        private const val SHIFT_18 = 18
        private const val ASCII_A = 0x41
        private const val ASCII_Z = 0x5A
        private const val ASCII_LOWER_A = 0x61
        private const val ASCII_LOWER_Z = 0x7A
        private const val ASCII_ZERO = 0x30
        private const val ASCII_NINE = 0x39
        private const val ASCII_PLUS = 0x2B
        private const val ASCII_MINUS = 0x2D
        private const val UPPERCASE_COUNT = 26
        private const val LETTER_COUNT = 52
        private const val INDEX_PLUS = 62
        private const val INDEX_SLASH = 63
    }
}
