package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.benchmark.Warmup

/**
 * Cost of `PooledBuffer`'s reference count on the slice/release path.
 *
 * Exists to settle one question with a number instead of an argument: making `PooledBuffer`'s
 * refcount atomic buys thread safety for `MultiThreaded` pools, but only if the atomic is
 * affordable — an unconditional atomic would tax every `SingleThreaded` pool too, which is the
 * default and the single-consumer hot path.
 *
 * [sliceAndRelease] is the exact path that would change: `slice()` does `checkNotFreed()` +
 * `addRef()` + two allocations (`inner.slice()` and the `TrackedSlice` wrapper), and
 * `freeNativeMemory()` on the slice does the matching decrement. The parent chunk's own reference
 * is held for the whole run, so the count never reaches zero and the pool never reclaims — this
 * measures refcount traffic in steady state, not pool churn (that is [PoolChurnBenchmark]).
 *
 * Both threading modes are measured because the fix only needs to make `MultiThreaded` pools
 * atomic; `SingleThreaded` is the baseline that must not regress.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class PooledRefCountBenchmark {
    private lateinit var singleThreadedPool: BufferPool
    private lateinit var multiThreadedPool: BufferPool
    private lateinit var singleThreadedChunk: PlatformBuffer
    private lateinit var multiThreadedChunk: PlatformBuffer

    @Setup
    fun setup() {
        singleThreadedPool = newPool(ThreadingMode.SingleThreaded)
        multiThreadedPool = newPool(ThreadingMode.MultiThreaded)
        singleThreadedChunk = pinnedChunk(singleThreadedPool)
        multiThreadedChunk = pinnedChunk(multiThreadedPool)
    }

    @TearDown
    fun tearDown() {
        singleThreadedChunk.freeNativeMemory()
        multiThreadedChunk.freeNativeMemory()
        singleThreadedPool.clear()
        multiThreadedPool.clear()
    }

    /** Slice + release against a chunk whose own reference is held for the whole run. */
    @Benchmark
    fun sliceAndReleaseSingleThreaded(): Int = sliceAndRelease(singleThreadedChunk)

    @Benchmark
    fun sliceAndReleaseMultiThreaded(): Int = sliceAndRelease(multiThreadedChunk)

    /**
     * Same path, but the slice is actually read through before release.
     *
     * [sliceAndRelease] hands the JIT a slice whose only observed property is `capacity`, which
     * escape analysis can scalar-replace — making the measurement a property of the optimiser
     * rather than of the refcount. Reading a byte forces the wrapper to exist and go through
     * `TrackedSlice`'s liveness check, which is what a real consumer does.
     */
    @Benchmark
    fun sliceReadAndReleaseSingleThreaded(): Int = sliceReadAndRelease(singleThreadedChunk)

    @Benchmark
    fun sliceReadAndReleaseMultiThreaded(): Int = sliceReadAndRelease(multiThreadedChunk)

    private fun sliceReadAndRelease(chunk: PlatformBuffer): Int {
        val slice = chunk.slice()
        val first = slice.readByte().toInt()
        slice.freeNativeMemory()
        return first
    }

    private fun sliceAndRelease(chunk: PlatformBuffer): Int {
        val slice = chunk.slice()
        val capacity = slice.capacity
        slice.freeNativeMemory()
        return capacity
    }

    private fun newPool(threadingMode: ThreadingMode) =
        BufferPool(
            threadingMode = threadingMode,
            maxPoolSize = 16,
            defaultBufferSize = CHUNK_SIZE,
            factory = BufferFactory.Default,
        )

    private fun pinnedChunk(pool: BufferPool): PlatformBuffer {
        val chunk = pool.acquire(CHUNK_SIZE) as PlatformBuffer
        repeat(CHUNK_SIZE) { chunk.writeByte(1) }
        chunk.resetForRead()
        return chunk
    }

    private companion object {
        const val CHUNK_SIZE = 1024
    }
}
