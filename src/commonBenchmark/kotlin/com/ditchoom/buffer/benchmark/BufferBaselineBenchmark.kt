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
 *
 * Benchmarks are consistent with AndroidBufferBenchmark for cross-platform comparison.
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
    private lateinit var largeDirectBuffer: PlatformBuffer
    private lateinit var testData: ByteArray

    @Setup
    fun setup() {
        heapBuffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        directBuffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        largeDirectBuffer = PlatformBuffer.allocate(largeBufferSize, AllocationZone.Direct)
        testData = ByteArray(smallBufferSize) { it.toByte() }

        // Pre-fill buffers for slice benchmark
        directBuffer.writeBytes(testData)
        directBuffer.resetForRead()
    }

    // --- Allocation Benchmarks ---

    @Benchmark
    fun allocateHeap(): PlatformBuffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)

    @Benchmark
    fun allocateDirect(): PlatformBuffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)

    // --- Read/Write Int (representative of primitive operations) ---

    @Benchmark
    fun readWriteIntHeap(): Long {
        heapBuffer.resetForWrite()
        repeat(smallBufferSize / 4) { heapBuffer.writeInt(it) }
        heapBuffer.resetForRead()
        var sum = 0L
        repeat(smallBufferSize / 4) { sum += heapBuffer.readInt() }
        return sum
    }

    @Benchmark
    fun readWriteIntDirect(): Long {
        directBuffer.resetForWrite()
        repeat(smallBufferSize / 4) { directBuffer.writeInt(it) }
        directBuffer.resetForRead()
        var sum = 0L
        repeat(smallBufferSize / 4) { sum += directBuffer.readInt() }
        return sum
    }

    // --- Bulk Operations ---

    @Benchmark
    fun bulkOperationsHeap(): ByteArray {
        heapBuffer.resetForWrite()
        heapBuffer.writeBytes(testData)
        heapBuffer.resetForRead()
        return heapBuffer.readByteArray(smallBufferSize)
    }

    @Benchmark
    fun bulkOperationsDirect(): ByteArray {
        directBuffer.resetForWrite()
        directBuffer.writeBytes(testData)
        directBuffer.resetForRead()
        return directBuffer.readByteArray(smallBufferSize)
    }

    // --- Large Buffer (64KB) ---

    @Benchmark
    fun largeBufferOperations(): Long {
        largeDirectBuffer.resetForWrite()
        repeat(largeBufferSize / 8) { largeDirectBuffer.writeLong(it.toLong()) }
        largeDirectBuffer.resetForRead()
        var sum = 0L
        repeat(largeBufferSize / 8) { sum += largeDirectBuffer.readLong() }
        return sum
    }

    // --- Mixed Operations (realistic usage pattern) ---

    @Benchmark
    fun mixedOperations(): Long {
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
        return sum
    }

    // --- Slice ---

    @Benchmark
    fun sliceBuffer(): ReadBuffer {
        directBuffer.position(0)
        return directBuffer.slice()
    }
}
