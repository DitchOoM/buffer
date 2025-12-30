package com.ditchoom.buffer

import androidx.benchmark.BlackHole
import androidx.benchmark.ExperimentalBlackHoleApi
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android-specific benchmarks using AndroidX Benchmark library.
 *
 * Run benchmarks only:
 * ./gradlew connectedCheck -Pandroid.testInstrumentationRunnerArguments.class=com.ditchoom.buffer.AndroidBufferBenchmark
 *
 * Run all instrumented tests (includes benchmarks):
 * ./gradlew connectedCheck
 *
 * All benchmarks use BlackHole.consume() to prevent dead code elimination.
 * Benchmarks are consistent with BufferBaselineBenchmark for cross-platform comparison.
 */
@OptIn(ExperimentalBlackHoleApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidBufferBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val smallBufferSize = 1024
    private val largeBufferSize = 64 * 1024

    // --- Allocation Benchmarks ---

    @Test
    fun allocateHeap() {
        benchmarkRule.measureRepeated {
            BlackHole.consume(PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap))
        }
    }

    @Test
    fun allocateDirect() {
        benchmarkRule.measureRepeated {
            BlackHole.consume(PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct))
        }
    }

    // --- Read/Write Int (representative of primitive operations) ---

    @Test
    fun readWriteIntHeap() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        benchmarkRule.measureRepeated {
            buffer.resetForWrite()
            repeat(smallBufferSize / 4) { buffer.writeInt(it) }
            buffer.resetForRead()
            var sum = 0L
            repeat(smallBufferSize / 4) { sum += buffer.readInt() }
            BlackHole.consume(sum)
        }
    }

    @Test
    fun readWriteIntDirect() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        benchmarkRule.measureRepeated {
            buffer.resetForWrite()
            repeat(smallBufferSize / 4) { buffer.writeInt(it) }
            buffer.resetForRead()
            var sum = 0L
            repeat(smallBufferSize / 4) { sum += buffer.readInt() }
            BlackHole.consume(sum)
        }
    }

    // --- Bulk Operations (buffer-to-buffer, no ByteArray allocation in read path) ---

    @Test
    fun bulkOperationsHeap() {
        val sourceBuffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        sourceBuffer.writeBytes(ByteArray(smallBufferSize) { it.toByte() })
        sourceBuffer.resetForRead()
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        benchmarkRule.measureRepeated {
            sourceBuffer.position(0)
            buffer.resetForWrite()
            buffer.write(sourceBuffer)
            buffer.resetForRead()
            BlackHole.consume(buffer.slice())
        }
    }

    @Test
    fun bulkOperationsDirect() {
        val sourceBuffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        sourceBuffer.writeBytes(ByteArray(smallBufferSize) { it.toByte() })
        sourceBuffer.resetForRead()
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        benchmarkRule.measureRepeated {
            sourceBuffer.position(0)
            buffer.resetForWrite()
            buffer.write(sourceBuffer)
            buffer.resetForRead()
            BlackHole.consume(buffer.slice())
        }
    }

    // --- Large Buffer (64KB) ---

    @Test
    fun largeBufferOperations() {
        val buffer = PlatformBuffer.allocate(largeBufferSize, AllocationZone.Direct)
        benchmarkRule.measureRepeated {
            buffer.resetForWrite()
            repeat(largeBufferSize / 8) { buffer.writeLong(it.toLong()) }
            buffer.resetForRead()
            var sum = 0L
            repeat(largeBufferSize / 8) { sum += buffer.readLong() }
            BlackHole.consume(sum)
        }
    }

    // --- Mixed Operations (realistic usage pattern) ---

    @Test
    fun mixedOperations() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        benchmarkRule.measureRepeated {
            buffer.resetForWrite()
            repeat(64) {
                buffer.writeByte(1)
                buffer.writeShort(2)
                buffer.writeInt(3)
                buffer.writeLong(4)
            }
            buffer.resetForRead()
            var sum = 0L
            repeat(64) {
                sum += buffer.readByte()
                sum += buffer.readShort()
                sum += buffer.readInt()
                sum += buffer.readLong()
            }
            BlackHole.consume(sum)
        }
    }

    // --- Slice ---

    @Test
    fun sliceBuffer() {
        val sourceBuffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        sourceBuffer.writeBytes(ByteArray(smallBufferSize) { it.toByte() })
        sourceBuffer.resetForRead()
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        benchmarkRule.measureRepeated {
            sourceBuffer.position(0)
            buffer.resetForWrite()
            buffer.write(sourceBuffer)
            buffer.resetForRead()
            BlackHole.consume(buffer.slice())
        }
    }
}
