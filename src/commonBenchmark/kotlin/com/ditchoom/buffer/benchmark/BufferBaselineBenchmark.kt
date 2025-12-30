package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
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
 * Baseline benchmarks for buffer operations.
 * These establish performance baselines before optimizations.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
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
    fun allocateHeapSmall(): PlatformBuffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)

    @Benchmark
    fun allocateDirectSmall(): PlatformBuffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)

    @Benchmark
    fun allocateHeapLarge(): PlatformBuffer = PlatformBuffer.allocate(largeBufferSize, AllocationZone.Heap)

    @Benchmark
    fun allocateDirectLarge(): PlatformBuffer = PlatformBuffer.allocate(largeBufferSize, AllocationZone.Direct)

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
    fun writeShortHeap(): PlatformBuffer {
        heapBuffer.resetForWrite()
        repeat(smallBufferSize / 2) {
            heapBuffer.writeShort(it.toShort())
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
    fun writeShortDirect(): PlatformBuffer {
        directBuffer.resetForWrite()
        repeat(smallBufferSize / 2) {
            directBuffer.writeShort(it.toShort())
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

    // --- Slice Benchmarks ---

    @Benchmark
    fun sliceHeap(): ReadBuffer {
        heapBuffer.position(0)
        return heapBuffer.slice()
    }

    @Benchmark
    fun sliceDirect(): ReadBuffer {
        directBuffer.position(0)
        return directBuffer.slice()
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

    @Benchmark
    fun writeLongLargeHeap(): PlatformBuffer {
        largeHeapBuffer.resetForWrite()
        repeat(largeBufferSize / 8) {
            largeHeapBuffer.writeLong(it.toLong())
        }
        return largeHeapBuffer
    }

    @Benchmark
    fun writeLongLargeDirect(): PlatformBuffer {
        largeDirectBuffer.resetForWrite()
        repeat(largeBufferSize / 8) {
            largeDirectBuffer.writeLong(it.toLong())
        }
        return largeDirectBuffer
    }

    // --- Mixed Operations ---

    @Benchmark
    fun mixedOperationsHeap(): PlatformBuffer {
        heapBuffer.resetForWrite()
        repeat(64) {
            heapBuffer.writeByte(1)
            heapBuffer.writeShort(2)
            heapBuffer.writeInt(3)
            heapBuffer.writeLong(4)
        }
        heapBuffer.resetForRead()
        var sum = 0L
        repeat(64) {
            sum += heapBuffer.readByte()
            sum += heapBuffer.readShort()
            sum += heapBuffer.readInt()
            sum += heapBuffer.readLong()
        }
        return heapBuffer
    }

    @Benchmark
    fun mixedOperationsDirect(): PlatformBuffer {
        directBuffer.resetForWrite()
        repeat(64) {
            directBuffer.writeByte(1)
            directBuffer.writeShort(2)
            directBuffer.writeInt(3)
            directBuffer.writeLong(4)
        }
        directBuffer.resetForRead()
        var sum = 0L
        repeat(64) {
            sum += directBuffer.readByte()
            sum += directBuffer.readShort()
            sum += directBuffer.readInt()
            sum += directBuffer.readLong()
        }
        return directBuffer
    }
}
