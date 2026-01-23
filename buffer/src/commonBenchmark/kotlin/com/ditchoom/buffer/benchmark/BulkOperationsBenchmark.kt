package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
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
 * Benchmarks for bulk buffer operations to measure SIMD optimization impact.
 * Run baseline before optimizations, then again after to measure improvement.
 *
 * Run with: ./gradlew macosArm64BenchmarkBenchmark -Pbenchmark.filter=BulkOperations
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class BulkOperationsBenchmark {
    private val size64k = 64 * 1024

    private lateinit var directBuffer1: PlatformBuffer
    private lateinit var directBuffer2: PlatformBuffer
    private lateinit var heapBuffer1: PlatformBuffer
    private lateinit var heapBuffer2: PlatformBuffer
    private lateinit var intArray: IntArray
    private lateinit var shortArray: ShortArray

    @Setup
    fun setup() {
        directBuffer1 = PlatformBuffer.allocate(size64k, AllocationZone.Direct)
        directBuffer2 = PlatformBuffer.allocate(size64k, AllocationZone.Direct)
        heapBuffer1 = PlatformBuffer.allocate(size64k, AllocationZone.Heap)
        heapBuffer2 = PlatformBuffer.allocate(size64k, AllocationZone.Heap)

        // Fill buffers with test data
        for (i in 0 until size64k) {
            directBuffer1.writeByte(i.toByte())
            directBuffer2.writeByte(i.toByte())
            heapBuffer1.writeByte(i.toByte())
            heapBuffer2.writeByte(i.toByte())
        }
        directBuffer1.resetForRead()
        directBuffer2.resetForRead()
        heapBuffer1.resetForRead()
        heapBuffer2.resetForRead()

        intArray = IntArray(size64k / 4) { it }
        shortArray = ShortArray(size64k / 2) { it.toShort() }
    }

    // --- XOR Mask ---

    @Benchmark
    fun xorMask64kDirect(): Int {
        directBuffer1.position(0)
        directBuffer1.setLimit(size64k)
        directBuffer1.xorMask(0x12345678)
        // XOR again to restore original data
        directBuffer1.position(0)
        directBuffer1.setLimit(size64k)
        directBuffer1.xorMask(0x12345678)
        return directBuffer1.get(0).toInt()
    }

    @Benchmark
    fun xorMask64kHeap(): Int {
        heapBuffer1.position(0)
        heapBuffer1.setLimit(size64k)
        heapBuffer1.xorMask(0x12345678)
        // XOR again to restore original data
        heapBuffer1.position(0)
        heapBuffer1.setLimit(size64k)
        heapBuffer1.xorMask(0x12345678)
        return heapBuffer1.get(0).toInt()
    }

    // --- Fill ---

    @Benchmark
    fun fill64kDirect(): Int {
        directBuffer1.position(0)
        directBuffer1.setLimit(size64k)
        directBuffer1.fill(0x42.toByte())
        return directBuffer1.get(0).toInt()
    }

    @Benchmark
    fun fill64kHeap(): Int {
        heapBuffer1.position(0)
        heapBuffer1.setLimit(size64k)
        heapBuffer1.fill(0x42.toByte())
        return heapBuffer1.get(0).toInt()
    }

    // --- Content Equals ---

    @Benchmark
    fun contentEquals64k(): Boolean {
        directBuffer1.position(0)
        directBuffer2.position(0)
        return directBuffer1.contentEquals(directBuffer2)
    }

    // --- Mismatch ---

    @Benchmark
    fun mismatch64k(): Int {
        directBuffer1.position(0)
        directBuffer2.position(0)
        // Both buffers have same content, so mismatch returns -1
        return directBuffer1.mismatch(directBuffer2)
    }

    // --- indexOf(Byte) ---

    @Benchmark
    fun indexOfByte64k(): Int {
        directBuffer1.position(0)
        // Search for a byte that's near the end
        return directBuffer1.indexOf(0xFF.toByte())
    }

    // --- indexOf(Int) ---

    @Benchmark
    fun indexOfInt64k(): Int {
        directBuffer1.position(0)
        // Search for an int value
        return directBuffer1.indexOf(0x7C7D7E7F)
    }

    // --- indexOf(Long) ---

    @Benchmark
    fun indexOfLong64k(): Int {
        directBuffer1.position(0)
        // Search for a long value
        return directBuffer1.indexOf(0x78797A7B7C7D7E7FL)
    }

    // --- Write/Read Ints ---

    @Benchmark
    fun writeInts64k(): Int {
        directBuffer1.position(0)
        directBuffer1.setLimit(size64k)
        directBuffer1.writeInts(intArray)
        return directBuffer1.position()
    }

    @Benchmark
    fun readInts64k(): Int {
        directBuffer1.position(0)
        val result = directBuffer1.readInts(size64k / 4)
        return result[0]
    }

    // --- Write/Read Shorts ---

    @Benchmark
    fun writeShorts64k(): Int {
        directBuffer1.position(0)
        directBuffer1.setLimit(size64k)
        directBuffer1.writeShorts(shortArray)
        return directBuffer1.position()
    }

    @Benchmark
    fun readShorts64k(): Int {
        directBuffer1.position(0)
        val result = directBuffer1.readShorts(size64k / 2)
        return result[0].toInt()
    }

    // --- Buffer Copy ---

    @Benchmark
    fun bufferCopy64k(): Int {
        directBuffer2.position(0)
        directBuffer1.position(0)
        directBuffer1.setLimit(size64k)
        directBuffer1.write(directBuffer2)
        return directBuffer1.get(0).toInt()
    }
}
