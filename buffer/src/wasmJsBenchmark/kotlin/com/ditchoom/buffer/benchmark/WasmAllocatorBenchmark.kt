package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteArrayBuffer
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.LinearBuffer
import com.ditchoom.buffer.LinearMemoryAllocator
import com.ditchoom.buffer.PlatformBuffer
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
 * Cost of the wasmJs allocate/release cycle.
 *
 * [LinearMemoryAllocator] reclaims released blocks, which puts work on the allocation path that a
 * pure bump allocator did not have. These benchmarks measure both reclamation routes plus the
 * read/write hot path, which must be unaffected:
 *
 * - [allocateAndReleaseLifo] — the `use { }` shape. The block is the top allocation when released,
 *   so the bump pointer rewinds and the next allocation bumps straight back into it.
 * - [allocateAndReleaseViaFreeList] — a released block that is *not* at the top of the heap, so it
 *   goes onto the size-classed free list and is popped by the next same-size request.
 * - [allocateWithoutRelease] — the old bump-only path, for reference. Leaks, so it is bounded by
 *   [LEAK_BUDGET] and resets the allocator rather than running the pool dry.
 * - [linearBufferReadWrite] / [byteArrayBufferAllocate] — unchanged-hot-path and managed-heap
 *   reference points.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class WasmAllocatorBenchmark {
    private lateinit var linearBuffer: LinearBuffer
    private var leaked = 0

    @Setup
    fun setup() {
        val (offset, _) = LinearMemoryAllocator.allocate(BUFFER_SIZE)
        linearBuffer = LinearBuffer(offset, BUFFER_SIZE, ByteOrder.BIG_ENDIAN)
    }

    /** Allocate, touch, release — the shape `use { }` produces. Release rewinds the bump pointer. */
    @Benchmark
    fun allocateAndReleaseLifo(): Int {
        val buffer = BufferFactory.Default.allocate(BUFFER_SIZE)
        buffer.writeInt(SENTINEL_VALUE)
        buffer.freeNativeMemory()
        return buffer.capacity
    }

    /**
     * Two live buffers, released oldest-first, so the first release cannot rewind and has to go
     * through the free list. The next iteration's first allocation pops it back off.
     */
    @Benchmark
    fun allocateAndReleaseViaFreeList(): Int {
        val first = BufferFactory.Default.allocate(BUFFER_SIZE)
        val second = BufferFactory.Default.allocate(BUFFER_SIZE)
        first.writeInt(SENTINEL_VALUE)
        second.writeInt(SENTINEL_VALUE)
        first.freeNativeMemory() // pinned below `second` — parked on the free list
        second.freeNativeMemory() // top of heap — rewinds
        return first.capacity + second.capacity
    }

    /** Pure bump allocation, never released — the pre-reclamation cost baseline. */
    @Benchmark
    fun allocateWithoutRelease(): Int {
        if (leaked >= LEAK_BUDGET) {
            LinearMemoryAllocator.reset()
            leaked = 0
            setup()
        }
        leaked++
        val buffer: PlatformBuffer = BufferFactory.Default.allocate(BUFFER_SIZE)
        buffer.writeInt(SENTINEL_VALUE)
        return buffer.capacity
    }

    /** The read/write hot path — must not have moved. */
    @Benchmark
    fun linearBufferReadWrite(): Int {
        linearBuffer.resetForWrite()
        linearBuffer.writeInt(SENTINEL_VALUE)
        linearBuffer.resetForRead()
        return linearBuffer.readInt()
    }

    /** Managed-heap reference point: allocation served by the Wasm-GC heap instead. */
    @Benchmark
    fun byteArrayBufferAllocate(): ByteArrayBuffer = ByteArrayBuffer(ByteArray(BUFFER_SIZE), ByteOrder.BIG_ENDIAN)

    private companion object {
        private const val BUFFER_SIZE = 1024
        private const val SENTINEL_VALUE = 0x12345678

        /** 32 MB of leaked 1 KiB blocks, well inside the 256 MB pool, then reset. */
        private const val LEAK_BUDGET = 32 * 1024
    }
}
