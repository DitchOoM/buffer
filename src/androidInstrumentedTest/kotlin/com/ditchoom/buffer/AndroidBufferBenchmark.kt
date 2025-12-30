package com.ditchoom.buffer

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.allocate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android-specific benchmarks using AndroidX Benchmark library.
 * Run with: ./gradlew connectedCheck -Pandroid.testInstrumentationRunnerArguments.class=com.ditchoom.buffer.AndroidBufferBenchmark
 */
@RunWith(AndroidJUnit4::class)
class AndroidBufferBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val smallBufferSize = 1024
    private val largeBufferSize = 64 * 1024

    // --- Allocation Benchmarks ---

    @Test
    fun allocateHeapSmall() {
        benchmarkRule.measureRepeated {
            PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        }
    }

    @Test
    fun allocateDirectSmall() {
        benchmarkRule.measureRepeated {
            PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        }
    }

    @Test
    fun allocateHeapLarge() {
        benchmarkRule.measureRepeated {
            PlatformBuffer.allocate(largeBufferSize, AllocationZone.Heap)
        }
    }

    @Test
    fun allocateDirectLarge() {
        benchmarkRule.measureRepeated {
            PlatformBuffer.allocate(largeBufferSize, AllocationZone.Direct)
        }
    }

    // --- Read Benchmarks ---

    @Test
    fun readByteHeap() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        buffer.writeBytes(ByteArray(smallBufferSize))
        buffer.resetForRead()
        benchmarkRule.measureRepeated {
            buffer.position(0)
            repeat(smallBufferSize) {
                buffer.readByte()
            }
        }
    }

    @Test
    fun readByteDirect() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        buffer.writeBytes(ByteArray(smallBufferSize))
        buffer.resetForRead()
        benchmarkRule.measureRepeated {
            buffer.position(0)
            repeat(smallBufferSize) {
                buffer.readByte()
            }
        }
    }

    @Test
    fun readIntHeap() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        repeat(smallBufferSize / 4) { buffer.writeInt(it) }
        buffer.resetForRead()
        benchmarkRule.measureRepeated {
            buffer.position(0)
            repeat(smallBufferSize / 4) {
                buffer.readInt()
            }
        }
    }

    @Test
    fun readIntDirect() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        repeat(smallBufferSize / 4) { buffer.writeInt(it) }
        buffer.resetForRead()
        benchmarkRule.measureRepeated {
            buffer.position(0)
            repeat(smallBufferSize / 4) {
                buffer.readInt()
            }
        }
    }

    @Test
    fun readLongHeap() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        repeat(smallBufferSize / 8) { buffer.writeLong(it.toLong()) }
        buffer.resetForRead()
        benchmarkRule.measureRepeated {
            buffer.position(0)
            repeat(smallBufferSize / 8) {
                buffer.readLong()
            }
        }
    }

    @Test
    fun readLongDirect() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        repeat(smallBufferSize / 8) { buffer.writeLong(it.toLong()) }
        buffer.resetForRead()
        benchmarkRule.measureRepeated {
            buffer.position(0)
            repeat(smallBufferSize / 8) {
                buffer.readLong()
            }
        }
    }

    // --- Write Benchmarks ---

    @Test
    fun writeByteHeap() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        benchmarkRule.measureRepeated {
            buffer.resetForWrite()
            repeat(smallBufferSize) {
                buffer.writeByte(it.toByte())
            }
        }
    }

    @Test
    fun writeByteDirect() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        benchmarkRule.measureRepeated {
            buffer.resetForWrite()
            repeat(smallBufferSize) {
                buffer.writeByte(it.toByte())
            }
        }
    }

    @Test
    fun writeIntHeap() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        benchmarkRule.measureRepeated {
            buffer.resetForWrite()
            repeat(smallBufferSize / 4) {
                buffer.writeInt(it)
            }
        }
    }

    @Test
    fun writeIntDirect() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        benchmarkRule.measureRepeated {
            buffer.resetForWrite()
            repeat(smallBufferSize / 4) {
                buffer.writeInt(it)
            }
        }
    }

    @Test
    fun writeLongHeap() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        benchmarkRule.measureRepeated {
            buffer.resetForWrite()
            repeat(smallBufferSize / 8) {
                buffer.writeLong(it.toLong())
            }
        }
    }

    @Test
    fun writeLongDirect() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        benchmarkRule.measureRepeated {
            buffer.resetForWrite()
            repeat(smallBufferSize / 8) {
                buffer.writeLong(it.toLong())
            }
        }
    }

    // --- Bulk Operations ---

    @Test
    fun writeBytesHeap() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        val data = ByteArray(smallBufferSize) { it.toByte() }
        benchmarkRule.measureRepeated {
            buffer.resetForWrite()
            buffer.writeBytes(data)
        }
    }

    @Test
    fun writeBytesDirect() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        val data = ByteArray(smallBufferSize) { it.toByte() }
        benchmarkRule.measureRepeated {
            buffer.resetForWrite()
            buffer.writeBytes(data)
        }
    }

    @Test
    fun readByteArrayHeap() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        buffer.writeBytes(ByteArray(smallBufferSize))
        buffer.resetForRead()
        benchmarkRule.measureRepeated {
            buffer.position(0)
            buffer.readByteArray(smallBufferSize)
        }
    }

    @Test
    fun readByteArrayDirect() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        buffer.writeBytes(ByteArray(smallBufferSize))
        buffer.resetForRead()
        benchmarkRule.measureRepeated {
            buffer.position(0)
            buffer.readByteArray(smallBufferSize)
        }
    }

    // --- Slice Benchmarks ---

    @Test
    fun sliceHeap() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        buffer.writeBytes(ByteArray(smallBufferSize))
        buffer.resetForRead()
        benchmarkRule.measureRepeated {
            buffer.position(0)
            buffer.slice()
        }
    }

    @Test
    fun sliceDirect() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        buffer.writeBytes(ByteArray(smallBufferSize))
        buffer.resetForRead()
        benchmarkRule.measureRepeated {
            buffer.position(0)
            buffer.slice()
        }
    }

    // --- Large Buffer Operations (64KB) ---

    @Test
    fun readLongLargeHeap() {
        val buffer = PlatformBuffer.allocate(largeBufferSize, AllocationZone.Heap)
        repeat(largeBufferSize / 8) { buffer.writeLong(it.toLong()) }
        buffer.resetForRead()
        benchmarkRule.measureRepeated {
            buffer.position(0)
            repeat(largeBufferSize / 8) {
                buffer.readLong()
            }
        }
    }

    @Test
    fun readLongLargeDirect() {
        val buffer = PlatformBuffer.allocate(largeBufferSize, AllocationZone.Direct)
        repeat(largeBufferSize / 8) { buffer.writeLong(it.toLong()) }
        buffer.resetForRead()
        benchmarkRule.measureRepeated {
            buffer.position(0)
            repeat(largeBufferSize / 8) {
                buffer.readLong()
            }
        }
    }

    // --- Mixed Operations ---

    @Test
    fun mixedOperationsHeap() {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
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

    @Test
    fun mixedOperationsDirect() {
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
}
