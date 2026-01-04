package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ByteArrayBuffer
import com.ditchoom.buffer.LinearBuffer
import com.ditchoom.buffer.LinearMemoryAllocator
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
 * WASM-specific benchmark to isolate LinearBuffer performance.
 * Uses only Heap in setup to avoid triggering the optimizer bug.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 1)
@Measurement(iterations = 2)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class WasmLinearBufferBenchmark {
    private val bufferSize = 1024

    private lateinit var byteArrayBuffer: ByteArrayBuffer
    private lateinit var linearBuffer: LinearBuffer

    @Setup
    fun setup() {
        byteArrayBuffer = ByteArrayBuffer(ByteArray(bufferSize), ByteOrder.BIG_ENDIAN)
        // Allocate LinearBuffer once in setup - reuse for operations benchmarks
        val (offset, _) = LinearMemoryAllocator.allocate(bufferSize)
        linearBuffer = LinearBuffer(offset, bufferSize, ByteOrder.BIG_ENDIAN)
    }

    // Test 1: ByteArrayBuffer allocation (should work)
    @Benchmark
    fun byteArrayBufferAlloc(): ByteArrayBuffer {
        return ByteArrayBuffer(ByteArray(bufferSize), ByteOrder.BIG_ENDIAN)
    }

    // Test 2: ByteArrayBuffer operations (should work)
    @Benchmark
    fun byteArrayBufferOps(): Int {
        byteArrayBuffer.resetForWrite()
        byteArrayBuffer.writeInt(42)
        byteArrayBuffer.resetForRead()
        return byteArrayBuffer.readInt()
    }

    // Test 2b: LinearBuffer operations - compare with byteArrayBufferOps
    @Benchmark
    fun linearBufferOps(): Int {
        linearBuffer.resetForWrite()
        linearBuffer.writeInt(42)
        linearBuffer.resetForRead()
        return linearBuffer.readInt()
    }

    // Test 2c: LinearBuffer bulk write/read
    @Benchmark
    fun linearBufferBulkOps(): Int {
        linearBuffer.resetForWrite()
        repeat(bufferSize / 4) { linearBuffer.writeInt(it) }
        linearBuffer.resetForRead()
        var sum = 0
        repeat(bufferSize / 4) { sum += linearBuffer.readInt() }
        return sum
    }

    // Test 2d: ByteArrayBuffer bulk write/read (for comparison)
    @Benchmark
    fun byteArrayBufferBulkOps(): Int {
        byteArrayBuffer.resetForWrite()
        repeat(bufferSize / 4) { byteArrayBuffer.writeInt(it) }
        byteArrayBuffer.resetForRead()
        var sum = 0
        repeat(bufferSize / 4) { sum += byteArrayBuffer.readInt() }
        return sum
    }

    // Allocator stress tests disabled - they exhaust the bump allocator's memory
    // after millions of benchmark iterations. These tests proved the optimizer bug
    // workaround works. Use AllocationZone.Heap for high-frequency allocations.
    //
    // Results when enabled (all passing, ~95-103M ops/sec):
    // - z1AllocatorMinimal: 95.3M ops/sec
    // - z2AlignmentOnly: 103.5M ops/sec
    // - z3AlignmentWithAssignment: 101.7M ops/sec
    // - z4AllocatorWithAlignment: 98.6M ops/sec
    // - z5AllocatorWithBoundsCheck: 96.4M ops/sec
}
