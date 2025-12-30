package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup
import java.util.concurrent.TimeUnit

/**
 * Baseline benchmarks for buffer operations.
 * These establish performance baselines before optimizations.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
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
    fun allocateHeapSmall(bh: Blackhole) {
        bh.consume(PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap))
    }

    @Benchmark
    fun allocateDirectSmall(bh: Blackhole) {
        bh.consume(PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct))
    }

    @Benchmark
    fun allocateHeapLarge(bh: Blackhole) {
        bh.consume(PlatformBuffer.allocate(largeBufferSize, AllocationZone.Heap))
    }

    @Benchmark
    fun allocateDirectLarge(bh: Blackhole) {
        bh.consume(PlatformBuffer.allocate(largeBufferSize, AllocationZone.Direct))
    }

    // --- Read Benchmarks (Heap) ---

    @Benchmark
    fun readByteHeap(bh: Blackhole) {
        heapBuffer.position(0)
        repeat(smallBufferSize) {
            bh.consume(heapBuffer.readByte())
        }
    }

    @Benchmark
    fun readShortHeap(bh: Blackhole) {
        heapBuffer.position(0)
        repeat(smallBufferSize / 2) {
            bh.consume(heapBuffer.readShort())
        }
    }

    @Benchmark
    fun readIntHeap(bh: Blackhole) {
        heapBuffer.position(0)
        repeat(smallBufferSize / 4) {
            bh.consume(heapBuffer.readInt())
        }
    }

    @Benchmark
    fun readLongHeap(bh: Blackhole) {
        heapBuffer.position(0)
        repeat(smallBufferSize / 8) {
            bh.consume(heapBuffer.readLong())
        }
    }

    // --- Read Benchmarks (Direct) ---

    @Benchmark
    fun readByteDirect(bh: Blackhole) {
        directBuffer.position(0)
        repeat(smallBufferSize) {
            bh.consume(directBuffer.readByte())
        }
    }

    @Benchmark
    fun readShortDirect(bh: Blackhole) {
        directBuffer.position(0)
        repeat(smallBufferSize / 2) {
            bh.consume(directBuffer.readShort())
        }
    }

    @Benchmark
    fun readIntDirect(bh: Blackhole) {
        directBuffer.position(0)
        repeat(smallBufferSize / 4) {
            bh.consume(directBuffer.readInt())
        }
    }

    @Benchmark
    fun readLongDirect(bh: Blackhole) {
        directBuffer.position(0)
        repeat(smallBufferSize / 8) {
            bh.consume(directBuffer.readLong())
        }
    }

    // --- Write Benchmarks (Heap) ---

    @Benchmark
    fun writeByteHeap(bh: Blackhole) {
        heapBuffer.resetForWrite()
        repeat(smallBufferSize) {
            heapBuffer.writeByte(it.toByte())
        }
        bh.consume(heapBuffer)
    }

    @Benchmark
    fun writeShortHeap(bh: Blackhole) {
        heapBuffer.resetForWrite()
        repeat(smallBufferSize / 2) {
            heapBuffer.writeShort(it.toShort())
        }
        bh.consume(heapBuffer)
    }

    @Benchmark
    fun writeIntHeap(bh: Blackhole) {
        heapBuffer.resetForWrite()
        repeat(smallBufferSize / 4) {
            heapBuffer.writeInt(it)
        }
        bh.consume(heapBuffer)
    }

    @Benchmark
    fun writeLongHeap(bh: Blackhole) {
        heapBuffer.resetForWrite()
        repeat(smallBufferSize / 8) {
            heapBuffer.writeLong(it.toLong())
        }
        bh.consume(heapBuffer)
    }

    // --- Write Benchmarks (Direct) ---

    @Benchmark
    fun writeByteDirect(bh: Blackhole) {
        directBuffer.resetForWrite()
        repeat(smallBufferSize) {
            directBuffer.writeByte(it.toByte())
        }
        bh.consume(directBuffer)
    }

    @Benchmark
    fun writeShortDirect(bh: Blackhole) {
        directBuffer.resetForWrite()
        repeat(smallBufferSize / 2) {
            directBuffer.writeShort(it.toShort())
        }
        bh.consume(directBuffer)
    }

    @Benchmark
    fun writeIntDirect(bh: Blackhole) {
        directBuffer.resetForWrite()
        repeat(smallBufferSize / 4) {
            directBuffer.writeInt(it)
        }
        bh.consume(directBuffer)
    }

    @Benchmark
    fun writeLongDirect(bh: Blackhole) {
        directBuffer.resetForWrite()
        repeat(smallBufferSize / 8) {
            directBuffer.writeLong(it.toLong())
        }
        bh.consume(directBuffer)
    }

    // --- Bulk Operations ---

    @Benchmark
    fun writeBytesHeap(bh: Blackhole) {
        heapBuffer.resetForWrite()
        heapBuffer.writeBytes(testData)
        bh.consume(heapBuffer)
    }

    @Benchmark
    fun writeBytesDirect(bh: Blackhole) {
        directBuffer.resetForWrite()
        directBuffer.writeBytes(testData)
        bh.consume(directBuffer)
    }

    @Benchmark
    fun readByteArrayHeap(bh: Blackhole) {
        heapBuffer.position(0)
        bh.consume(heapBuffer.readByteArray(smallBufferSize))
    }

    @Benchmark
    fun readByteArrayDirect(bh: Blackhole) {
        directBuffer.position(0)
        bh.consume(directBuffer.readByteArray(smallBufferSize))
    }

    // --- Slice Benchmarks ---

    @Benchmark
    fun sliceHeap(bh: Blackhole) {
        heapBuffer.position(0)
        bh.consume(heapBuffer.slice())
    }

    @Benchmark
    fun sliceDirect(bh: Blackhole) {
        directBuffer.position(0)
        bh.consume(directBuffer.slice())
    }

    // --- Large Buffer Operations (64KB) ---

    @Benchmark
    fun readLongLargeHeap(bh: Blackhole) {
        largeHeapBuffer.position(0)
        repeat(largeBufferSize / 8) {
            bh.consume(largeHeapBuffer.readLong())
        }
    }

    @Benchmark
    fun readLongLargeDirect(bh: Blackhole) {
        largeDirectBuffer.position(0)
        repeat(largeBufferSize / 8) {
            bh.consume(largeDirectBuffer.readLong())
        }
    }

    @Benchmark
    fun writeLongLargeHeap(bh: Blackhole) {
        largeHeapBuffer.resetForWrite()
        repeat(largeBufferSize / 8) {
            largeHeapBuffer.writeLong(it.toLong())
        }
        bh.consume(largeHeapBuffer)
    }

    @Benchmark
    fun writeLongLargeDirect(bh: Blackhole) {
        largeDirectBuffer.resetForWrite()
        repeat(largeBufferSize / 8) {
            largeDirectBuffer.writeLong(it.toLong())
        }
        bh.consume(largeDirectBuffer)
    }

    // --- Mixed Operations ---

    @Benchmark
    fun mixedOperationsHeap(bh: Blackhole) {
        heapBuffer.resetForWrite()
        repeat(64) {
            heapBuffer.writeByte(1)
            heapBuffer.writeShort(2)
            heapBuffer.writeInt(3)
            heapBuffer.writeLong(4)
        }
        heapBuffer.resetForRead()
        repeat(64) {
            bh.consume(heapBuffer.readByte())
            bh.consume(heapBuffer.readShort())
            bh.consume(heapBuffer.readInt())
            bh.consume(heapBuffer.readLong())
        }
    }

    @Benchmark
    fun mixedOperationsDirect(bh: Blackhole) {
        directBuffer.resetForWrite()
        repeat(64) {
            directBuffer.writeByte(1)
            directBuffer.writeShort(2)
            directBuffer.writeInt(3)
            directBuffer.writeLong(4)
        }
        directBuffer.resetForRead()
        repeat(64) {
            bh.consume(directBuffer.readByte())
            bh.consume(directBuffer.readShort())
            bh.consume(directBuffer.readInt())
            bh.consume(directBuffer.readLong())
        }
    }
}
