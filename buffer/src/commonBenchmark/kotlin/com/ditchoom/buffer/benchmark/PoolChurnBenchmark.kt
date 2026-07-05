package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadWriteBuffer
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
 * Measures pool acquire/release throughput under the allocation patterns behind the
 * Autobahn 9.2.5 fragmentation OOM (see ANDROID_ART_ALLOCATOR.md), to quantify the
 * size-class pooling fix:
 *
 *  - [sameSizeHotLoop*]: fixed 8 KiB acquire/release — the pure pool-hit path. Guards
 *    against the size-class bucketing adding overhead to the case the old exact-size
 *    pool already handled well.
 *  - [mixedSizeChurn*]: four KiB-scale sizes with cycling odd offsets so no exact size
 *    repeats — the assembled-network-message shape. The old pool freed + reallocated on
 *    most of these acquires; size-class reuse turns them into hits.
 *  - [interleavedChopAndLarge*]: 4 held small chops + one odd-sized large acquire per
 *    op — the websocket read-path shape that crashed CI.
 *
 * Offsets cycle mod 64 so every odd size stays inside its power-of-two class and the
 * benchmark is steady-state (an unbounded offset would drift across size classes).
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
// Benchmark bodies are intentionally full of literal sizes/offsets (the churn shapes
// under test); extracting each to a named constant would obscure the pattern.
@Suppress("MagicNumber")
open class PoolChurnBenchmark {
    private val kib = 1024
    private val mixedSizesKib = intArrayOf(3, 5, 9, 17)

    private lateinit var singleThreaded: BufferPool
    private lateinit var multiThreaded: BufferPool
    private var round = 0

    @Setup
    fun setup() {
        singleThreaded = newPool(ThreadingMode.SingleThreaded)
        multiThreaded = newPool(ThreadingMode.MultiThreaded)
        round = 0
    }

    @TearDown
    fun tearDown() {
        singleThreaded.clear()
        multiThreaded.clear()
    }

    private fun newPool(threadingMode: ThreadingMode) =
        BufferPool(
            threadingMode = threadingMode,
            maxPoolSize = 16,
            defaultBufferSize = 1 * kib,
            factory = BufferFactory.Default,
        )

    @Benchmark
    fun sameSizeHotLoopSingleThreaded(): Int = sameSizeHotLoop(singleThreaded)

    @Benchmark
    fun sameSizeHotLoopMultiThreaded(): Int = sameSizeHotLoop(multiThreaded)

    @Benchmark
    fun mixedSizeChurnSingleThreaded(): Int = mixedSizeChurn(singleThreaded)

    @Benchmark
    fun mixedSizeChurnMultiThreaded(): Int = mixedSizeChurn(multiThreaded)

    @Benchmark
    fun interleavedChopAndLargeMultiThreaded(): Int = interleavedChopAndLarge(multiThreaded)

    private fun sameSizeHotLoop(pool: BufferPool): Int {
        val buffer = pool.acquire(8 * kib)
        buffer.writeByte(1)
        val capacity = buffer.capacity
        pool.release(buffer)
        return capacity
    }

    private fun mixedSizeChurn(pool: BufferPool): Int {
        round = (round + 1) and 63
        val held = ArrayList<ReadWriteBuffer>(mixedSizesKib.size)
        var total = 0
        for (sizeKib in mixedSizesKib) {
            val buffer = pool.acquire(sizeKib * kib + round * 7 + 1)
            buffer.writeByte(round.toByte())
            total += buffer.capacity
            held += buffer
        }
        held.forEach { pool.release(it) }
        return total
    }

    private fun interleavedChopAndLarge(pool: BufferPool): Int {
        round = (round + 1) and 63
        val chops = ArrayList<ReadWriteBuffer>(4)
        repeat(4) { chops += pool.acquire(1 * kib) }
        val big = pool.acquire(29 * kib + round * 13 + 29)
        big.writeByte(0)
        val capacity = big.capacity
        chops.forEach { pool.release(it) }
        pool.release(big)
        return capacity
    }
}
