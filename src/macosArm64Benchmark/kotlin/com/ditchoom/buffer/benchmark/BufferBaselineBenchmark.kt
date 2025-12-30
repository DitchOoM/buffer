package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup

/**
 * Baseline benchmarks for buffer operations (JS platform).
 * These establish performance baselines before optimizations.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(kotlinx.benchmark.BenchmarkTimeUnit.SECONDS)
open class BufferBaselineBenchmark {
    private val smallBufferSize = 1024
    private val largeBufferSize = 64 * 1024

    private lateinit var heapBuffer: PlatformBuffer
    private lateinit var directBuffer: PlatformBuffer
    private lateinit var largeHeapBuffer: PlatformBuffer
    private lateinit var largeDirectBuffer: PlatformBuffer
    private lateinit var testData: ByteArray

    @Setup
    fun setup() {
        heapBuffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        directBuffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        largeHeapBuffer = PlatformBuffer.allocate(largeBufferSize, AllocationZone.Heap)
        largeDirectBuffer = PlatformBuffer.allocate(largeBufferSize, AllocationZone.Direct)
        testData = ByteArray(smallBufferSize) { it.toByte() }

        // Pre-fill buffers with data
        heapBuffer.writeBytes(testData)
        heapBuffer.resetForRead()
        directBuffer.writeBytes(testData)
        directBuffer.resetForRead()

        val largeData = ByteArray(largeBufferSize) { it.toByte() }
        largeHeapBuffer.writeBytes(largeData)
        largeHeapBuffer.resetForRead()
        largeDirectBuffer.writeBytes(largeData)
        largeDirectBuffer.resetForRead()
    }

    // --- Allocation Benchmarks ---

    @Benchmark
    fun allocateHeapSmall(): PlatformBuffer {
        return PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
    }

    @Benchmark
    fun allocateDirectSmall(): PlatformBuffer {
        return PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
    }

    // --- Read Benchmarks (Heap) ---

    @Benchmark
    fun readByteHeap(): Long {
        heapBuffer.position(0)
        var sum = 0L
        repeat(smallBufferSize) {
            sum += heapBuffer.readByte()
        }
        return sum
    }

    @Benchmark
    fun readShortHeap(): Long {
        heapBuffer.position(0)
        var sum = 0L
        repeat(smallBufferSize / 2) {
            sum += heapBuffer.readShort()
        }
        return sum
    }

    @Benchmark
    fun readIntHeap(): Long {
        heapBuffer.position(0)
        var sum = 0L
        repeat(smallBufferSize / 4) {
            sum += heapBuffer.readInt()
        }
        return sum
    }

    @Benchmark
    fun readLongHeap(): Long {
        heapBuffer.position(0)
        var sum = 0L
        repeat(smallBufferSize / 8) {
            sum += heapBuffer.readLong()
        }
        return sum
    }

    // --- Read Benchmarks (Direct) ---

    @Benchmark
    fun readByteDirect(): Long {
        directBuffer.position(0)
        var sum = 0L
        repeat(smallBufferSize) {
            sum += directBuffer.readByte()
        }
        return sum
    }

    @Benchmark
    fun readShortDirect(): Long {
        directBuffer.position(0)
        var sum = 0L
        repeat(smallBufferSize / 2) {
            sum += directBuffer.readShort()
        }
        return sum
    }

    @Benchmark
    fun readIntDirect(): Long {
        directBuffer.position(0)
        var sum = 0L
        repeat(smallBufferSize / 4) {
            sum += directBuffer.readInt()
        }
        return sum
    }

    @Benchmark
    fun readLongDirect(): Long {
        directBuffer.position(0)
        var sum = 0L
        repeat(smallBufferSize / 8) {
            sum += directBuffer.readLong()
        }
        return sum
    }

    // --- Write Benchmarks (Heap) ---

    @Benchmark
    fun writeByteHeap(): PlatformBuffer {
        heapBuffer.resetForWrite()
        repeat(smallBufferSize) {
            heapBuffer.writeByte(it.toByte())
        }
        return heapBuffer
    }

    @Benchmark
    fun writeIntHeap(): PlatformBuffer {
        heapBuffer.resetForWrite()
        repeat(smallBufferSize / 4) {
            heapBuffer.writeInt(it)
        }
        return heapBuffer
    }

    @Benchmark
    fun writeLongHeap(): PlatformBuffer {
        heapBuffer.resetForWrite()
        repeat(smallBufferSize / 8) {
            heapBuffer.writeLong(it.toLong())
        }
        return heapBuffer
    }

    // --- Write Benchmarks (Direct) ---

    @Benchmark
    fun writeByteDirect(): PlatformBuffer {
        directBuffer.resetForWrite()
        repeat(smallBufferSize) {
            directBuffer.writeByte(it.toByte())
        }
        return directBuffer
    }

    @Benchmark
    fun writeIntDirect(): PlatformBuffer {
        directBuffer.resetForWrite()
        repeat(smallBufferSize / 4) {
            directBuffer.writeInt(it)
        }
        return directBuffer
    }

    @Benchmark
    fun writeLongDirect(): PlatformBuffer {
        directBuffer.resetForWrite()
        repeat(smallBufferSize / 8) {
            directBuffer.writeLong(it.toLong())
        }
        return directBuffer
    }

    // --- Bulk Operations ---

    @Benchmark
    fun writeBytesHeap(): PlatformBuffer {
        heapBuffer.resetForWrite()
        heapBuffer.writeBytes(testData)
        return heapBuffer
    }

    @Benchmark
    fun writeBytesDirect(): PlatformBuffer {
        directBuffer.resetForWrite()
        directBuffer.writeBytes(testData)
        return directBuffer
    }

    @Benchmark
    fun readByteArrayHeap(): ByteArray {
        heapBuffer.position(0)
        return heapBuffer.readByteArray(smallBufferSize)
    }

    @Benchmark
    fun readByteArrayDirect(): ByteArray {
        directBuffer.position(0)
        return directBuffer.readByteArray(smallBufferSize)
    }

    // --- Large Buffer Operations (64KB) ---

    @Benchmark
    fun readLongLargeHeap(): Long {
        largeHeapBuffer.position(0)
        var sum = 0L
        repeat(largeBufferSize / 8) {
            sum += largeHeapBuffer.readLong()
        }
        return sum
    }

    @Benchmark
    fun readLongLargeDirect(): Long {
        largeDirectBuffer.position(0)
        var sum = 0L
        repeat(largeBufferSize / 8) {
            sum += largeDirectBuffer.readLong()
        }
        return sum
    }
}
