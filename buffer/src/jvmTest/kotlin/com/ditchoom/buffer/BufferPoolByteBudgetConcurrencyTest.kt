package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-thread tests for the byte budget of a [ThreadingMode.MultiThreaded] pool. The budget is
 * reserved with CAS on a shared counter, so concurrent releasers must never retain more than the
 * budget allows, and the accounting must return to exactly zero once the pool is drained.
 */
class BufferPoolByteBudgetConcurrencyTest {
    private val workerCount = 16
    private val executor = Executors.newFixedThreadPool(workerCount)

    @AfterTest
    fun shutdown() {
        executor.shutdownNow()
    }

    @Test
    fun simultaneousReleasesRetainExactlyTheBudget() {
        val bufferSize = 1024
        val budgetBuffers = 4
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
            val barrier = CyclicBarrier(workerCount)
            val done =
                buffers.map { buffer ->
                    executor.submit {
                        barrier.await()
                        pool.release(buffer)
                    }
                }
            done.forEach { it.get(30, TimeUnit.SECONDS) }

            // The count cap has ample room, so the only limit is the budget: exactly that many are kept.
            assertEquals(budgetBuffers, pool.stats().currentPoolSize)
            assertEquals(budgetBuffers.toLong() * bufferSize, pool.stats().currentPooledBytes)
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
        val barrier = CyclicBarrier(workerCount)
        val violations =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val done =
            List(workerCount) { worker ->
                executor.submit {
                    barrier.await()
                    repeat(20_000) { i ->
                        val buffer = pool.acquire(sizes[(worker + i) % sizes.size])
                        pool.release(buffer)
                        val stats = pool.stats()
                        if (stats.currentPooledBytes > maxBytes || stats.currentPoolSize > maxCount) {
                            violations.incrementAndGet()
                        }
                    }
                }
            }
        done.forEach { it.get(120, TimeUnit.SECONDS) }

        assertEquals(0, violations.get())
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
        val barrier = CyclicBarrier(workerCount)
        val done =
            List(workerCount) { worker ->
                executor.submit {
                    barrier.await()
                    repeat(10_000) {
                        if (worker % 4 == 0) {
                            pool.trim(2L * 1024)
                        } else {
                            pool.release(pool.acquire(1024))
                        }
                    }
                }
            }
        done.forEach { it.get(120, TimeUnit.SECONDS) }

        pool.trim(0)
        assertEquals(0, pool.stats().currentPoolSize)
        assertEquals(0L, pool.stats().currentPooledBytes)
    }
}
