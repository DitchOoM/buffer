package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.NativeMemoryAccess
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
 * Baseline benchmarks for buffer operations.
 * These establish performance baselines before optimizations.
 *
 * Benchmarks are consistent with AndroidBufferBenchmark for cross-platform comparison.
 * All benchmarks return values to prevent dead code elimination.
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
    private lateinit var sourceBuffer: PlatformBuffer
    private lateinit var testData: ByteArray

    // For readLine benchmarks
    private lateinit var shortLinesBuffer: PlatformBuffer
    private lateinit var longLinesBuffer: PlatformBuffer
    private val shortLineText = "Hello\nWorld\nTest\n" // 3 short lines
    private val longLineText =
        buildString {
            // 5 lines of 200 chars each (1000+ bytes total)
            repeat(5) {
                append("A".repeat(200))
                append("\r\n")
            }
        }

    @Setup
    fun setup() {
        heapBuffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)
        // Use Heap for buffers that would be Direct - isolates WASM optimizer bug
        directBuffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap) // TODO: restore to Direct
        largeDirectBuffer = PlatformBuffer.allocate(largeBufferSize, AllocationZone.Heap) // TODO: restore to Direct
        sourceBuffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap) // TODO: restore to Direct
        testData = ByteArray(smallBufferSize) { it.toByte() }

        // Pre-fill source buffer for bulk write operations
        sourceBuffer.writeBytes(testData)
        sourceBuffer.resetForRead()

        // Setup readLine buffers
        shortLinesBuffer = PlatformBuffer.allocate(shortLineText.length * 2, AllocationZone.Heap)
        shortLinesBuffer.writeString(shortLineText)

        longLinesBuffer = PlatformBuffer.allocate(longLineText.length * 2, AllocationZone.Heap)
        longLinesBuffer.writeString(longLineText)
    }

    // --- Allocation Benchmarks ---

    @Benchmark
    fun allocateHeap(): PlatformBuffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Heap)

    // DISABLED: Causes stack overflow in WASM production builds due to optimizer bug
    // @Benchmark
    // fun allocateDirect(): PlatformBuffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)

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

    // --- Bulk Operations (buffer-to-buffer, no ByteArray allocation in read path) ---

    @Benchmark
    fun bulkOperationsHeap(): Int {
        sourceBuffer.position(0)
        heapBuffer.resetForWrite()
        heapBuffer.write(sourceBuffer)
        heapBuffer.resetForRead()
        // Return first int to prevent DCE without allocating new objects
        return heapBuffer.readInt()
    }

    @Benchmark
    fun bulkOperationsDirect(): Int {
        sourceBuffer.position(0)
        directBuffer.resetForWrite()
        directBuffer.write(sourceBuffer)
        directBuffer.resetForRead()
        // Return first int to prevent DCE without allocating new objects
        return directBuffer.readInt()
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
    // Note: This benchmark creates new buffer objects each iteration.
    // On native platforms, this may cause memory pressure at high iteration counts.

    @Benchmark
    fun sliceBuffer(): Int {
        directBuffer.resetForWrite()
        directBuffer.write(sourceBuffer.also { it.position(0) })
        directBuffer.resetForRead()
        val slice = directBuffer.slice()
        // Read first byte to prevent DCE and ensure slice was created
        return slice.readByte().toInt()
    }

    // --- readLine Benchmarks ---
    // These measure the performance of line parsing with bulk indexOf optimization

    @Benchmark
    fun readLineShort(): Int {
        shortLinesBuffer.resetForRead()
        var totalLength = 0
        while (shortLinesBuffer.hasRemaining()) {
            totalLength += shortLinesBuffer.readLine().length
        }
        return totalLength
    }

    @Benchmark
    fun readLineLong(): Int {
        longLinesBuffer.resetForRead()
        var totalLength = 0
        while (longLinesBuffer.hasRemaining()) {
            totalLength += longLinesBuffer.readLine().length
        }
        return totalLength
    }

    // --- Native Address Lookup (JVM/Android only) ---
    // Measures performance of getting native memory address from DirectByteBuffer
    // JVM: Uses cached reflection Field lookup
    // Android 33+: Uses MethodHandle optimization
    // Note: Only works on JVM/Android where NativeMemoryAccess is implemented

    @Benchmark
    fun nativeAddressLookupCached(): Long {
        // Access cached nativeAddress (lazy property already initialized)
        val access = directBuffer as? NativeMemoryAccess ?: return 0L
        return access.nativeAddress
    }

    @Benchmark
    fun nativeAddressLookupFresh(): Long {
        // Create new buffer and access nativeAddress (not cached)
        val buffer = PlatformBuffer.allocate(smallBufferSize, AllocationZone.Direct)
        val access = buffer as? NativeMemoryAccess ?: return 0L
        return access.nativeAddress
    }
}
