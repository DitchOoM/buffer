package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.DEFAULT_MAX_POOLED_BYTES
import com.ditchoom.buffer.pool.ThreadingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The pool is capped by the bytes it retains as well as by the number of buffers. */
class BufferPoolByteBudgetTests {
    private val modes = ThreadingMode.entries
    private val bufferSize = 1024

    private fun pool(
        mode: ThreadingMode,
        maxPooledBytes: Long,
        maxPoolSize: Int = 64,
    ) = BufferPool(
        threadingMode = mode,
        maxPoolSize = maxPoolSize,
        defaultBufferSize = bufferSize,
        maxPooledBytes = maxPooledBytes,
    )

    @Test
    fun releasingPastTheByteBudgetDoesNotRetainTheExcess() {
        for (mode in modes) {
            val pool = pool(mode, maxPooledBytes = 3L * bufferSize)
            val buffers = List(8) { pool.acquire(bufferSize) }
            buffers.forEach { pool.release(it) }

            val stats = pool.stats()
            assertEquals(3, stats.currentPoolSize, "$mode")
            assertEquals(3L * bufferSize, stats.currentPooledBytes, "$mode")
            pool.clear()
        }
    }

    @Test
    fun aBufferLargerThanTheWholeBudgetIsNeverRetained() {
        for (mode in modes) {
            val pool = pool(mode, maxPooledBytes = bufferSize.toLong())
            pool.release(pool.acquire(4 * bufferSize))

            assertEquals(0, pool.stats().currentPoolSize, "$mode")
            assertEquals(0L, pool.stats().currentPooledBytes, "$mode")
        }
    }

    @Test
    fun theCountCapStillAppliesWhenTheByteBudgetHasRoom() {
        for (mode in modes) {
            val pool = pool(mode, maxPooledBytes = 100L * bufferSize, maxPoolSize = 2)
            List(5) { pool.acquire(bufferSize) }.forEach { pool.release(it) }

            assertEquals(2, pool.stats().currentPoolSize, "$mode")
            assertEquals(2L * bufferSize, pool.stats().currentPooledBytes, "$mode")
            pool.clear()
        }
    }

    @Test
    fun acquiringReturnsTheBytesToTheBudget() {
        for (mode in modes) {
            val pool = pool(mode, maxPooledBytes = 2L * bufferSize)
            pool.release(pool.acquire(bufferSize))
            assertEquals(bufferSize.toLong(), pool.stats().currentPooledBytes, "$mode")

            val held = pool.acquire(bufferSize)
            assertEquals(0L, pool.stats().currentPooledBytes, "$mode")
            pool.release(held)
            pool.clear()
            assertEquals(0L, pool.stats().currentPooledBytes, "$mode")
        }
    }

    @Test
    fun trimFreesPooledBuffersDownToTheTargetAndReportsWhatItFreed() {
        for (mode in modes) {
            val pool = pool(mode, maxPooledBytes = 16L * bufferSize)
            List(6) { pool.acquire(bufferSize) }.forEach { pool.release(it) }

            val freed = pool.trim(2L * bufferSize)

            assertEquals(4L * bufferSize, freed, "$mode")
            assertEquals(2, pool.stats().currentPoolSize, "$mode")
            assertEquals(2L * bufferSize, pool.stats().currentPooledBytes, "$mode")
            pool.clear()
        }
    }

    @Test
    fun trimFreesTheLargestBuffersFirst() {
        for (mode in modes) {
            val pool = pool(mode, maxPooledBytes = 64L * bufferSize)
            val small = pool.acquire(bufferSize)
            val large = pool.acquire(8 * bufferSize)
            pool.release(small)
            pool.release(large)

            val freed = pool.trim(bufferSize.toLong())

            assertEquals(8L * bufferSize, freed, "$mode")
            assertEquals(1, pool.stats().currentPoolSize, "$mode")
            assertEquals(bufferSize.toLong(), pool.stats().currentPooledBytes, "$mode")
            pool.clear()
        }
    }

    @Test
    fun trimToZeroEmptiesThePoolAndTrimAboveTheHeldBytesFreesNothing() {
        for (mode in modes) {
            val pool = pool(mode, maxPooledBytes = 16L * bufferSize)
            List(3) { pool.acquire(bufferSize) }.forEach { pool.release(it) }

            assertEquals(0L, pool.trim(10L * bufferSize), "$mode")
            assertEquals(3, pool.stats().currentPoolSize, "$mode")

            assertEquals(3L * bufferSize, pool.trim(0), "$mode")
            assertEquals(0, pool.stats().currentPoolSize, "$mode")
            assertEquals(0L, pool.stats().currentPooledBytes, "$mode")
        }
    }

    @Test
    fun trimLeavesAcquiredBuffersUsable() {
        for (mode in modes) {
            val pool = pool(mode, maxPooledBytes = 16L * bufferSize)
            val held = pool.acquire(bufferSize)
            pool.trim(0)
            held.writeInt(7)
            held.resetForRead()
            assertEquals(7, held.readInt(), "$mode")
            pool.release(held)
            pool.clear()
        }
    }

    @Test
    fun trimRejectsANegativeTarget() {
        for (mode in modes) {
            assertFailsWith<IllegalArgumentException> { pool(mode, maxPooledBytes = 0).trim(-1) }
        }
    }

    @Test
    fun theDefaultBudgetHoldsTheDefaultWorkingSetAndStaysBounded() {
        assertEquals(16L * 1024 * 1024, DEFAULT_MAX_POOLED_BYTES)
        for (mode in modes) {
            val pool = BufferPool(threadingMode = mode)
            // 64 default (64 KiB) buffers = 4 MiB, well inside the budget.
            List(64) { pool.acquire() }.forEach { pool.release(it) }
            assertEquals(64, pool.stats().currentPoolSize, "$mode")

            // Large buffers are cut off by the byte budget long before the count cap.
            pool.clear()
            List(64) { pool.acquire(4 * 1024 * 1024) }.forEach { pool.release(it) }
            assertTrue(pool.stats().currentPooledBytes <= DEFAULT_MAX_POOLED_BYTES, "$mode")
            assertTrue(pool.stats().currentPoolSize < 64, "$mode")
            pool.clear()
        }
    }
}
