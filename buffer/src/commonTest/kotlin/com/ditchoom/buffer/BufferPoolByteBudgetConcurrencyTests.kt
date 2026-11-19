package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Concurrent tests for the byte budget of a [ThreadingMode.MultiThreaded] pool. The budget is
 * reserved with CAS on a shared counter, so concurrent releasers must never retain more than the
 * budget allows, and the accounting must return to exactly zero once the pool is drained.
 *
 * Workers run on [Dispatchers.Default]: real threads on JVM, Android and Native; on JS and Wasm
 * that dispatcher is single-threaded, so the same accounting is checked without true overlap.
 */
@OptIn(ExperimentalAtomicApi::class)
class BufferPoolByteBudgetConcurrencyTests {
    private val workerCount = 16

    /** Start barrier: yields (never blocks a thread) until every worker has arrived, so they begin together. */
    private class StartBarrier(
        private val parties: Int,
    ) {
        private val arrived = AtomicInt(0)

        suspend fun await() {
            arrived.incrementAndFetch()
            while (arrived.load() < parties) yield()
        }
    }

    private fun runWorkers(body: suspend (worker: Int, barrier: StartBarrier) -> Unit) =
        runTest(timeout = 3.minutes) {
            withContext(Dispatchers.Default) {
                val barrier = StartBarrier(workerCount)
                coroutineScope {
                    List(workerCount) { worker -> async { body(worker, barrier) } }.awaitAll()
                }
            }
        }

    @Test
    fun simultaneousReleasesRetainExactlyTheBudget() =
        runTest(timeout = 3.minutes) {
            val bufferSize = 1024
            val budgetBuffers = 4
            withContext(Dispatchers.Default) {
                repeat(200) {
                    val pool =
                        BufferPool(
                            threadingMode = ThreadingMode.MultiThreaded,
                            maxPoolSize = 1_000,
                            defaultBufferSize = bufferSize,
                            factory = BufferFactory.managed(),
                            maxPooledBytes = budgetBuffers.toLong() * bufferSize,
                        )
                    val buffers = List(workerCount) { pool.acquire(bufferSize) }
                    val barrier = StartBarrier(workerCount)
                    coroutineScope {
                        buffers
                            .map { buffer ->
                                async {
                                    barrier.await()
                                    pool.release(buffer)
                                }
                            }.awaitAll()
                    }

                    // The count cap has ample room, so the only limit is the budget: exactly that many are kept.
                    assertEquals(budgetBuffers, pool.stats().currentPoolSize)
                    assertEquals(budgetBuffers.toLong() * bufferSize, pool.stats().currentPooledBytes)
                    pool.clear()
                }
            }
        }

    @Test
    fun mixedSizeChurnNeverExceedsEitherCapAndDrainsToZero() {
        val sizes = intArrayOf(1024, 2048, 4096, 16 * 1024)
        val maxBytes = 24L * 1024
        val maxCount = 6
        val pool =
            BufferPool(
                threadingMode = ThreadingMode.MultiThreaded,
                maxPoolSize = maxCount,
                defaultBufferSize = 1024,
                factory = BufferFactory.managed(),
                maxPooledBytes = maxBytes,
            )
        val violations = AtomicInt(0)
        runWorkers { worker, barrier ->
            barrier.await()
            repeat(20_000) { i ->
                val buffer = pool.acquire(sizes[(worker + i) % sizes.size])
                pool.release(buffer)
                val stats = pool.stats()
                if (stats.currentPooledBytes > maxBytes || stats.currentPoolSize > maxCount) {
                    violations.incrementAndFetch()
                }
                // Let other workers interleave on single-threaded targets.
                if (i % 256 == 0) yield()
            }
        }

        assertEquals(0, violations.load())
        assertTrue(pool.stats().currentPooledBytes <= maxBytes)
        pool.trim(0)
        assertEquals(0, pool.stats().currentPoolSize)
        assertEquals(0L, pool.stats().currentPooledBytes)
    }

    @Test
    fun trimRacingReleasesLeavesConsistentAccounting() {
        val pool =
            BufferPool(
                threadingMode = ThreadingMode.MultiThreaded,
                maxPoolSize = 64,
                defaultBufferSize = 1024,
                factory = BufferFactory.managed(),
                maxPooledBytes = 32L * 1024,
            )
        runWorkers { worker, barrier ->
            barrier.await()
            repeat(10_000) { i ->
                if (worker % 4 == 0) {
                    pool.trim(2L * 1024)
                } else {
                    pool.release(pool.acquire(1024))
                }
                if (i % 256 == 0) yield()
            }
        }

        pool.trim(0)
        assertEquals(0, pool.stats().currentPoolSize)
        assertEquals(0L, pool.stats().currentPooledBytes)
    }
}
