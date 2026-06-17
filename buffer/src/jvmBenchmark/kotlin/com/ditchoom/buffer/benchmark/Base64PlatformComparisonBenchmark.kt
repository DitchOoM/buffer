package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
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
import java.nio.ByteBuffer
import java.util.Base64

/**
 * JVM-only: empirically compares our zero-copy buffer-to-buffer Base64 encode against
 * java.util.Base64 at a small (handshake-sized) and a large input size.
 *
 * The hypothesis under test: java.util.Base64.encode(ByteBuffer) allocates a fresh output
 * ByteBuffer per call, so for SMALL inputs (the real WebSocket-handshake case, ~16-24 bytes)
 * that fixed allocation overhead makes the "platform" path slower than our in-place loop, and
 * the platform only wins on LARGE inputs where SIMD throughput amortizes the allocation.
 *
 * Run with: ./gradlew jvmBenchmarkBenchmark -Pbenchmark.filter=Base64PlatformComparison
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class Base64PlatformComparisonBenchmark {
    private val smallSize = 24 // multiple of 3 -> no padding noise; ~handshake key size
    private val largeSize = 48 * 1024

    private lateinit var smallSrc: PlatformBuffer
    private lateinit var largeSrc: PlatformBuffer
    private lateinit var smallDest: PlatformBuffer
    private lateinit var largeDest: PlatformBuffer

    private lateinit var smallSrcBB: ByteBuffer
    private lateinit var largeSrcBB: ByteBuffer
    private val encoder = Base64.getEncoder()

    @Setup
    fun setup() {
        smallSrc = BufferFactory.Default.allocate(smallSize)
        largeSrc = BufferFactory.Default.allocate(largeSize)
        smallDest = BufferFactory.Default.allocate(smallSize / 3 * 4)
        largeDest = BufferFactory.Default.allocate(largeSize / 3 * 4)
        smallSrcBB = ByteBuffer.allocateDirect(smallSize)
        largeSrcBB = ByteBuffer.allocateDirect(largeSize)
        for (i in 0 until smallSize) {
            smallSrc.writeByte(i.toByte())
            smallSrcBB.put(i.toByte())
        }
        for (i in 0 until largeSize) {
            largeSrc.writeByte(i.toByte())
            largeSrcBB.put(i.toByte())
        }
        smallSrc.resetForRead()
        largeSrc.resetForRead()
    }

    // ===== small (handshake-sized) =====

    @Benchmark
    fun oursSmall(): Int {
        smallSrc.position(0)
        smallSrc.setLimit(smallSize)
        smallDest.resetForWrite()
        smallSrc.encodeBase64Into(smallDest)
        return smallDest.get(0).toInt()
    }

    @Benchmark
    fun platformSmall(): Int {
        smallSrcBB.position(0)
        smallSrcBB.limit(smallSize)
        return encoder.encode(smallSrcBB).get(0).toInt()
    }

    // ===== large =====

    @Benchmark
    fun oursLarge(): Int {
        largeSrc.position(0)
        largeSrc.setLimit(largeSize)
        largeDest.resetForWrite()
        largeSrc.encodeBase64Into(largeDest)
        return largeDest.get(0).toInt()
    }

    @Benchmark
    fun platformLarge(): Int {
        largeSrcBB.position(0)
        largeSrcBB.limit(largeSize)
        return encoder.encode(largeSrcBB).get(0).toInt()
    }
}
