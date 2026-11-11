package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.pool.LockFreeBufferPool
import com.ditchoom.buffer.pool.SingleThreadedBufferPool
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

/**
 * The bucket scan an acquire performs before it falls through to allocating: the pool's own
 * `popAtLeast`, called directly so no allocation is involved. `empty` holds nothing;
 * `otherClasses` holds buffers only in a smaller size class than the request.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
open class PoolMissScanBenchmark {
    @Param("SingleThreaded", "MultiThreaded")
    var mode: String = ""

    @Param("empty", "otherClasses")
    var contents: String = ""

    @Param("1024", "1048576", "2500000")
    var size: Int = 0

    private lateinit var singleThreaded: SingleThreadedBufferPool
    private lateinit var lockFree: LockFreeBufferPool

    @Setup
    fun setup() {
        singleThreaded = SingleThreadedBufferPool(maxPoolSize = 4, defaultBufferSize = 1, factory = BufferFactory.Default)
        lockFree = LockFreeBufferPool(maxPoolSize = 4, defaultBufferSize = 1, factory = BufferFactory.Default)
        if (contents == "otherClasses") {
            val smaller = size / 16
            singleThreaded.release(BufferFactory.Default.allocate(smaller))
            lockFree.release(BufferFactory.Default.allocate(smaller))
        }
    }

    @TearDown
    fun tearDown() {
        singleThreaded.clear()
        lockFree.clear()
    }

    @Benchmark
    fun scan(): Int {
        val buffer = if (mode == "SingleThreaded") singleThreaded.popAtLeast(size) else lockFree.popAtLeast(size)
        return if (buffer == null) 0 else buffer.capacity
    }
}
