package com.ditchoom.buffer

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
 */
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
            PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        }
    }

    @Test
    fun allocateDirect() {
        benchmarkRule.measureRepeated {
            PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
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
            repeat(smallBufferSize / 4) { buffer.readInt() }
        }
    }

    @Test
    fun readWriteIntDirect() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        benchmarkRule.measureRepeated {
            buffer.resetForWrite()
            repeat(smallBufferSize / 4) { buffer.writeInt(it) }
            buffer.resetForRead()
            repeat(smallBufferSize / 4) { buffer.readInt() }
        }
    }

    // --- Bulk Operations ---

    @Test
    fun bulkOperationsHeap() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        val data = ByteArray(smallBufferSize) { it.toByte() }
        benchmarkRule.measureRepeated {
            buffer.resetForWrite()
            buffer.writeBytes(data)
            buffer.resetForRead()
            buffer.readByteArray(smallBufferSize)
        }
    }

    @Test
    fun bulkOperationsDirect() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        val data = ByteArray(smallBufferSize) { it.toByte() }
        benchmarkRule.measureRepeated {
            buffer.resetForWrite()
            buffer.writeBytes(data)
            buffer.resetForRead()
            buffer.readByteArray(smallBufferSize)
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
            repeat(largeBufferSize / 8) { buffer.readLong() }
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
            repeat(64) {
                buffer.readByte()
                buffer.readShort()
                buffer.readInt()
                buffer.readLong()
            }
        }
    }

    // --- Slice ---

    @Test
    fun sliceBuffer() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        buffer.writeBytes(ByteArray(smallBufferSize))
        buffer.resetForRead()
        benchmarkRule.measureRepeated {
            buffer.position(0)
            buffer.slice()
        }
    }
}
