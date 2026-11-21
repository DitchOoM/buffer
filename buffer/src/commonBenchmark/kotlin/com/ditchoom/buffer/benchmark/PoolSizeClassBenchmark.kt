package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
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
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.benchmark.Warmup

/**
 * Cost of the pool's size-class lookup at request sizes around the 1 MiB class switch.
 *
 *  - [hit]: acquire + release of the same size in steady state.
 *  - [missEmptyPool]: acquire on a pool that holds nothing, with a one-byte stand-in allocation
 *    so the time is the bucket scan, not the allocator.
 *  - [missOtherClasses]: same, on a pool that holds buffers only in a smaller class.
 *  - [missEmptyPoolRealAllocation]: acquire on an empty pool including the real allocation and free.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
open class PoolSizeClassBenchmark {
    /** Allocates one byte whatever is asked, so a pool miss costs the scan and not the allocator. */
    private class OneByteFactory(
        private val delegate: BufferFactory = BufferFactory.Default,
    ) : BufferFactory by delegate {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = delegate.allocate(1, byteOrder)
    }

    @Param("SingleThreaded", "MultiThreaded")
    var mode: String = ""

    @Param("1024", "1048576", "2500000")
    var size: Int = 0

    private lateinit var hitPool: BufferPool
    private lateinit var emptyPool: BufferPool
    private lateinit var otherClassesPool: BufferPool
    private lateinit var realAllocationPool: BufferPool

    @Setup
    fun setup() {
        val threading = ThreadingMode.valueOf(mode)
        hitPool = BufferPool(threading, maxPoolSize = 4, defaultBufferSize = 1)
        emptyPool = BufferPool(threading, maxPoolSize = 0, defaultBufferSize = 1, factory = OneByteFactory())
        otherClassesPool = BufferPool(threading, maxPoolSize = 4, defaultBufferSize = 1, factory = OneByteFactory())
        realAllocationPool = BufferPool(threading, maxPoolSize = 0, defaultBufferSize = 1)
        hitPool.release(hitPool.acquire(size))
    }

    @TearDown
    fun tearDown() {
        val stats = hitPool.stats()
        // Allocation per hit() call: every miss allocates one buffer of this size.
        println(
            "POOLSTATS mode=$mode size=$size acquires=${stats.totalAllocations} misses=${stats.poolMisses} " +
                "hitRate=${stats.hitRate}",
        )
        hitPool.clear()
        otherClassesPool.clear()
    }

    @Benchmark
    fun hit(): Int {
        val buffer = hitPool.acquire(size)
        val capacity = buffer.capacity
        hitPool.release(buffer)
        return capacity
    }

    @Benchmark
    fun missEmptyPool(): Int {
        val buffer = emptyPool.acquire(size)
        val capacity = buffer.capacity
        emptyPool.release(buffer)
        return capacity
    }

    @Benchmark
    fun missOtherClasses(): Int {
        val buffer = otherClassesPool.acquire(size)
        val capacity = buffer.capacity
        otherClassesPool.release(buffer)
        return capacity
    }

    @Benchmark
    fun missEmptyPoolRealAllocation(): Int {
        val buffer = realAllocationPool.acquire(size)
        val capacity = buffer.capacity
        realAllocationPool.release(buffer)
        return capacity
    }
}
