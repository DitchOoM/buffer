package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.withScope
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
 * Benchmarks comparing ScopedBuffer vs PlatformBuffer performance.
 *
 * ScopedBuffer provides:
 * - Deterministic cleanup (no GC pressure)
 * - Platform-optimized operations (FFM on JVM 21+, Unsafe on older JVMs)
 * - Direct memory access for FFI/JNI interop
 *
 * These benchmarks measure the full lifecycle including scope creation/cleanup
 * to give realistic performance expectations.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class ScopedBufferBenchmark {
    private val smallBufferSize = 1024
    private val largeBufferSize = 64 * 1024

    // Pre-allocated PlatformBuffers for comparison
    private lateinit var platformBufferDirect: PlatformBuffer
    private lateinit var platformBufferHeap: PlatformBuffer
    private lateinit var largePlatformBuffer: PlatformBuffer

    @Setup
    fun setup() {
        // Use Heap to avoid exhausting WASM LinearMemoryAllocator
        // These are kept across iterations, so Direct would accumulate
        platformBufferDirect = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        platformBufferHeap = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        largePlatformBuffer = PlatformBuffer.allocate(largeBufferSize, AllocationZone.Heap)
    }

    // ===== Allocation Benchmarks =====
    // Measures allocation + deallocation overhead

    @Benchmark
    fun allocateScopedBuffer(): Int =
        withScope { scope ->
            val buffer = scope.allocate(smallBufferSize)
            buffer.capacity
        }

    @Benchmark
    fun allocatePlatformBufferDirect(): Int {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        return buffer.capacity
    }

    @Benchmark
    fun allocatePlatformBufferHeap(): Int {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        return buffer.capacity
    }

    // ===== Read/Write Int Benchmarks =====
    // Measures primitive operation performance

    @Benchmark
    fun readWriteIntScoped(): Long =
        withScope { scope ->
            val buffer = scope.allocate(smallBufferSize)
            repeat(smallBufferSize / 4) { buffer.writeInt(it) }
            buffer.resetForRead()
            var sum = 0L
            repeat(smallBufferSize / 4) { sum += buffer.readInt() }
            sum
        }

    @Benchmark
    fun readWriteIntPlatformDirect(): Long {
        platformBufferDirect.resetForWrite()
        repeat(smallBufferSize / 4) { platformBufferDirect.writeInt(it) }
        platformBufferDirect.resetForRead()
        var sum = 0L
        repeat(smallBufferSize / 4) { sum += platformBufferDirect.readInt() }
        return sum
    }

    @Benchmark
    fun readWriteIntPlatformHeap(): Long {
        platformBufferHeap.resetForWrite()
        repeat(smallBufferSize / 4) { platformBufferHeap.writeInt(it) }
        platformBufferHeap.resetForRead()
        var sum = 0L
        repeat(smallBufferSize / 4) { sum += platformBufferHeap.readInt() }
        return sum
    }

    // ===== Read/Write Long Benchmarks =====
    // Measures 8-byte primitive performance

    @Benchmark
    fun readWriteLongScoped(): Long =
        withScope { scope ->
            val buffer = scope.allocate(smallBufferSize)
            repeat(smallBufferSize / 8) { buffer.writeLong(it.toLong()) }
            buffer.resetForRead()
            var sum = 0L
            repeat(smallBufferSize / 8) { sum += buffer.readLong() }
            sum
        }

    @Benchmark
    fun readWriteLongPlatformDirect(): Long {
        platformBufferDirect.resetForWrite()
        repeat(smallBufferSize / 8) { platformBufferDirect.writeLong(it.toLong()) }
        platformBufferDirect.resetForRead()
        var sum = 0L
        repeat(smallBufferSize / 8) { sum += platformBufferDirect.readLong() }
        return sum
    }

    // ===== Large Buffer Benchmarks =====
    // Measures performance with 64KB buffers

    @Benchmark
    fun largeBufferScoped(): Long =
        withScope { scope ->
            val buffer = scope.allocate(largeBufferSize)
            repeat(largeBufferSize / 8) { buffer.writeLong(it.toLong()) }
            buffer.resetForRead()
            var sum = 0L
            repeat(largeBufferSize / 8) { sum += buffer.readLong() }
            sum
        }

    @Benchmark
    fun largeBufferPlatformDirect(): Long {
        largePlatformBuffer.resetForWrite()
        repeat(largeBufferSize / 8) { largePlatformBuffer.writeLong(it.toLong()) }
        largePlatformBuffer.resetForRead()
        var sum = 0L
        repeat(largeBufferSize / 8) { sum += largePlatformBuffer.readLong() }
        return sum
    }

    // ===== Mixed Operations Benchmarks =====
    // Measures realistic usage with various primitive types

    @Benchmark
    fun mixedOperationsScoped(): Long =
        withScope { scope ->
            val buffer = scope.allocate(smallBufferSize)
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
            sum
        }

    @Benchmark
    fun mixedOperationsPlatformDirect(): Long {
        platformBufferDirect.resetForWrite()
        repeat(64) {
            platformBufferDirect.writeByte(1)
            platformBufferDirect.writeShort(2)
            platformBufferDirect.writeInt(3)
            platformBufferDirect.writeLong(4)
        }
        platformBufferDirect.resetForRead()
        var sum = 0L
        repeat(64) {
            sum += platformBufferDirect.readByte()
            sum += platformBufferDirect.readShort()
            sum += platformBufferDirect.readInt()
            sum += platformBufferDirect.readLong()
        }
        return sum
    }

    // ===== Multiple Buffers in Single Scope =====
    // Measures overhead of allocating multiple buffers in one scope

    @Benchmark
    fun multipleBuffersScoped(): Long =
        withScope { scope ->
            val buffer1 = scope.allocate(256)
            val buffer2 = scope.allocate(256)
            val buffer3 = scope.allocate(256)
            val buffer4 = scope.allocate(256)

            buffer1.writeLong(1L)
            buffer2.writeLong(2L)
            buffer3.writeLong(3L)
            buffer4.writeLong(4L)

            buffer1.resetForRead()
            buffer2.resetForRead()
            buffer3.resetForRead()
            buffer4.resetForRead()

            buffer1.readLong() + buffer2.readLong() + buffer3.readLong() + buffer4.readLong()
        }

    @Benchmark
    fun multipleBuffersPlatform(): Long {
        val buffer1 = PlatformBuffer.allocate(256, AllocationZone.Heap)
        val buffer2 = PlatformBuffer.allocate(256, AllocationZone.Heap)
        val buffer3 = PlatformBuffer.allocate(256, AllocationZone.Heap)
        val buffer4 = PlatformBuffer.allocate(256, AllocationZone.Heap)

        buffer1.writeLong(1L)
        buffer2.writeLong(2L)
        buffer3.writeLong(3L)
        buffer4.writeLong(4L)

        buffer1.resetForRead()
        buffer2.resetForRead()
        buffer3.resetForRead()
        buffer4.resetForRead()

        return buffer1.readLong() + buffer2.readLong() + buffer3.readLong() + buffer4.readLong()
    }

    // ===== Byte Order Benchmarks =====
    // Measures any overhead from byte order handling

    @Benchmark
    fun bigEndianScoped(): Long =
        withScope { scope ->
            val buffer = scope.allocate(smallBufferSize, ByteOrder.BIG_ENDIAN)
            repeat(smallBufferSize / 4) { buffer.writeInt(it) }
            buffer.resetForRead()
            var sum = 0L
            repeat(smallBufferSize / 4) { sum += buffer.readInt() }
            sum
        }

    @Benchmark
    fun littleEndianScoped(): Long =
        withScope { scope ->
            val buffer = scope.allocate(smallBufferSize, ByteOrder.LITTLE_ENDIAN)
            repeat(smallBufferSize / 4) { buffer.writeInt(it) }
            buffer.resetForRead()
            var sum = 0L
            repeat(smallBufferSize / 4) { sum += buffer.readInt() }
            sum
        }

    // ===== Native Address Access =====
    // Measures the cost of accessing native address (useful for FFI/JNI)

    @Benchmark
    fun nativeAddressAccessScoped(): Long =
        withScope { scope ->
            val buffer = scope.allocate(smallBufferSize)
            buffer.nativeAddress
        }

    // ===== Aligned Allocation =====
    // Measures aligned allocation overhead (useful for SIMD operations)

    @Benchmark
    fun allocateAligned64Scoped(): Int =
        withScope { scope ->
            val buffer = scope.allocateAligned(smallBufferSize, alignment = 64)
            buffer.capacity
        }

    // ===== Buffer Copy Operations =====
    // Measures buffer-to-buffer copy performance

    @Benchmark
    fun bufferCopyScoped(): Long =
        withScope { scope ->
            val source = scope.allocate(smallBufferSize)
            val dest = scope.allocate(smallBufferSize)

            // Fill source
            repeat(smallBufferSize / 4) { source.writeInt(it) }
            source.resetForRead()

            // Copy to dest
            dest.write(source)
            dest.resetForRead()

            // Read first few values to verify
            dest.readLong()
        }

    @Benchmark
    fun bufferCopyPlatformDirect(): Long {
        val source = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        val dest = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)

        // Fill source
        repeat(smallBufferSize / 4) { source.writeInt(it) }
        source.resetForRead()

        // Copy to dest
        dest.write(source)
        dest.resetForRead()

        // Read first few values to verify
        return dest.readLong()
    }

    // ===== Bulk Primitive Operations =====
    // Measures the optimized bulk read/write operations that use long-pairs pattern

    private val bulkIntData = IntArray(smallBufferSize / 4) { it }
    private val bulkShortData = ShortArray(smallBufferSize / 2) { it.toShort() }
    private val bulkLongData = LongArray(smallBufferSize / 8) { it.toLong() }

    @Benchmark
    fun bulkWriteIntsScoped(): Int =
        withScope { scope ->
            val buffer = scope.allocate(smallBufferSize, ByteOrder.BIG_ENDIAN)
            buffer.writeInts(bulkIntData)
            buffer.position()
        }

    @Benchmark
    fun bulkReadIntsScoped(): Long =
        withScope { scope ->
            val buffer = scope.allocate(smallBufferSize, ByteOrder.BIG_ENDIAN)
            buffer.writeInts(bulkIntData)
            buffer.resetForRead()
            val result = buffer.readInts(bulkIntData.size)
            result.sum().toLong()
        }

    @Benchmark
    fun bulkWriteIntsPlatformDirect(): Int {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        buffer.writeInts(bulkIntData)
        return buffer.position()
    }

    @Benchmark
    fun bulkReadIntsPlatformDirect(): Long {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        buffer.writeInts(bulkIntData)
        buffer.resetForRead()
        val result = buffer.readInts(bulkIntData.size)
        return result.sum().toLong()
    }

    @Benchmark
    fun bulkWriteShortsScoped(): Int =
        withScope { scope ->
            val buffer = scope.allocate(smallBufferSize, ByteOrder.BIG_ENDIAN)
            buffer.writeShorts(bulkShortData)
            buffer.position()
        }

    @Benchmark
    fun bulkWriteShortsPlatformDirect(): Int {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        buffer.writeShorts(bulkShortData)
        return buffer.position()
    }

    @Benchmark
    fun bulkWriteLongsScoped(): Int =
        withScope { scope ->
            val buffer = scope.allocate(smallBufferSize, ByteOrder.BIG_ENDIAN)
            buffer.writeLongs(bulkLongData)
            buffer.position()
        }

    @Benchmark
    fun bulkWriteLongsPlatformDirect(): Int {
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        buffer.writeLongs(bulkLongData)
        return buffer.position()
    }

    // ===== Pre-allocated Buffer Operations =====
    // Measures read/write performance with pre-allocated buffers (no allocation overhead)
    // This isolates the pure read/write performance

    @Benchmark
    fun preallocatedPlatformDirectBigEndian(): Long {
        platformBufferDirect.resetForWrite()
        repeat(smallBufferSize / 4) { platformBufferDirect.writeInt(it) }
        platformBufferDirect.resetForRead()
        var sum = 0L
        repeat(smallBufferSize / 4) { sum += platformBufferDirect.readInt() }
        return sum
    }

    @Benchmark
    fun preallocatedPlatformHeap(): Long {
        platformBufferHeap.resetForWrite()
        repeat(smallBufferSize / 4) { platformBufferHeap.writeInt(it) }
        platformBufferHeap.resetForRead()
        var sum = 0L
        repeat(smallBufferSize / 4) { sum += platformBufferHeap.readInt() }
        return sum
    }
}
