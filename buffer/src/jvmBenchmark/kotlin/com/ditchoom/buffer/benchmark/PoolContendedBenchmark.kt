package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.benchmark.Warmup
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Lock-free pool hit path under contention: [threads] threads each run [OPS_PER_THREAD]
 * acquire + release pairs per invocation. Divide the reported time by `threads * OPS_PER_THREAD`
 * for the cost of one pair.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 300, timeUnit = BenchmarkTimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 300, timeUnit = BenchmarkTimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class PoolContendedBenchmark {
    private companion object {
        const val OPS_PER_THREAD = 1000
    }

    @Param("4", "8")
    var threads: Int = 0

    @Param("1024", "65536", "1048576", "2500000")
    var size: Int = 0

    private lateinit var pool: BufferPool
    private val executor by lazy { Executors.newFixedThreadPool(threads) }

    @Setup
    fun setup() {
        pool = BufferPool(ThreadingMode.MultiThreaded, maxPoolSize = 16, defaultBufferSize = 1)
        val warm = List(threads) { pool.acquire(size) }
        warm.forEach { pool.release(it) }
    }

    @TearDown
    fun tearDown() {
        executor.shutdownNow()
        pool.clear()
    }

    @Benchmark
    fun contendedHit(): Int {
        val tasks =
            List(threads) {
                Callable {
                    var total = 0
                    repeat(OPS_PER_THREAD) {
                        val buffer = pool.acquire(size)
                        total += buffer.capacity
                        pool.release(buffer)
                    }
                    total
                }
            }
        return executor.invokeAll(tasks).sumOf { it.get() }
    }
}
