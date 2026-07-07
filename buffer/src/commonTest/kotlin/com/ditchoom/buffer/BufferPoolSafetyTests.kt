package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.LockFreeBufferPool
import com.ditchoom.buffer.pool.withBuffer
import com.ditchoom.buffer.pool.withPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Safety tests for BufferPool.
 * Validates pool clear ArrayDeque corruption fix (a4cf457),
 * cross-pool release rejection, double-release detection, and stats accuracy.
 */
class BufferPoolSafetyTests {
    // ============================================================================
    // Double-Release Detection
    // ============================================================================

    @Test
    fun doubleReleaseViaPlatformBufferFreeIsIdempotent() {
        withPool(defaultBufferSize = 64) { pool ->
            val buffer = pool.acquire(8) as PlatformBuffer
            buffer.writeInt(42)

            // First free returns to pool
            buffer.freeNativeMemory()

            // Second free should be a no-op (PooledBuffer.freed flag prevents double-return)
            buffer.freeNativeMemory()

            // Pool should have exactly 1 buffer, not 2
            assertTrue(pool.stats().currentPoolSize <= 1)
        }
    }

    @Test
    fun useAfterFreeThrows() {
        withPool(defaultBufferSize = 64) { pool ->
            val buffer = pool.acquire(8)
            buffer.writeInt(42)
            // freeNativeMemory() sets the freed flag on PooledBuffer
            (buffer as PlatformBuffer).freeNativeMemory()

            // Attempting to use buffer after free should throw
            assertFailsWith<IllegalStateException> {
                buffer.readInt()
            }
        }
    }

    @Test
    fun useAfterFreeOnWriteThrows() {
        withPool(defaultBufferSize = 64) { pool ->
            val buffer = pool.acquire(8)
            // freeNativeMemory() sets the freed flag on PooledBuffer
            (buffer as PlatformBuffer).freeNativeMemory()

            assertFailsWith<IllegalStateException> {
                buffer.writeInt(42)
            }
        }
    }

    // ============================================================================
    // Cross-Pool Release Rejection
    // ============================================================================

    @Test
    fun crossPoolReleaseThrows() {
        val pool1 = BufferPool(defaultBufferSize = 64)
        val pool2 = BufferPool(defaultBufferSize = 64)

        val buffer = pool1.acquire(8)

        assertFailsWith<IllegalArgumentException> {
            pool2.release(buffer)
        }

        pool1.release(buffer)
        pool1.clear()
        pool2.clear()
    }

    /**
     * Regression for the websocket #19 / buffer 6.8.1 cross-pool crash.
     *
     * A **nested** pool — one whose backing [factory] is itself a [BufferPool] — is a legal
     * construction today (`BufferPool : BufferFactory`, so a pool is accepted anywhere a factory
     * is), and it is exactly the topology the socket transport's `ReadBufferSource` builds when a
     * consumer passes a shared [BufferPool] as its `bufferFactory`:
     *
     * ```
     * outer.acquire() == PooledBuffer(inner = PooledBuffer(pool = inner), pool = outer)
     * ```
     *
     * Freeing that buffer the normal way runs `PooledBuffer.releaseRef → outer.release(inner)`,
     * where `inner.pool === inner ≠ outer`, tripping the cross-pool `require` in
     * `LockFreeBufferPool.release` / `SingleThreadedBufferPool.release` — an
     * `IllegalArgumentException` thrown from an ordinary free of a legitimately-acquired buffer.
     *
     * The cure: `BufferPool(factory = x)` collapses to reuse `x` when `x is BufferPool`, so the
     * nested structure never forms. This test asserts both the collapse (same-instance) and that a
     * normal free never raises a cross-pool error.
     */
    @Test
    fun nestedPoolCollapsesAndFreesWithoutCrossPoolError() {
        val inner = BufferPool(defaultBufferSize = 64)
        val outer = BufferPool(defaultBufferSize = 64, factory = inner)

        // Collapse: wrapping a pool in a pool must reuse the existing pool, not nest it.
        assertSame(inner, outer, "BufferPool(factory = aPool) must reuse aPool, not nest it")

        val buffer = outer.acquire(8) as PlatformBuffer
        buffer.writeInt(42)

        // A normal free of a buffer acquired from `outer` must not raise a cross-pool error.
        buffer.freeNativeMemory()

        outer.clear()
        inner.clear()
    }

    /**
     * Defensive backstop: even if a nested pool is built *directly* via the internal constructor
     * (bypassing the `BufferPool.invoke` collapse), a normal free must route the buffer back to its
     * owner pool instead of throwing the cross-pool error. Genuine misuse (an unrelated pool) still
     * throws — see [crossPoolReleaseThrows].
     */
    @Test
    fun directlyNestedPoolRoutesReleaseToOwnerInsteadOfThrowing() {
        val inner = BufferPool(defaultBufferSize = 64)
        // Force the nested structure the invoke-guard would otherwise collapse.
        val outer = LockFreeBufferPool(maxPoolSize = 64, defaultBufferSize = 64, factory = inner)

        val buffer = outer.acquire(8) as PlatformBuffer
        buffer.writeInt(42)

        // Must not throw: the buffer belongs to `inner` (outer's backing factory), so it is routed
        // there rather than rejected.
        buffer.freeNativeMemory()

        // The buffer went back to the inner pool, so inner can hand it out again.
        assertTrue(inner.stats().currentPoolSize >= 1)

        outer.clear()
        inner.clear()
    }

    // ============================================================================
    // Clear During Active Usage
    // ============================================================================

    @Test
    fun clearPoolWithNoBuffersDoesNotCrash() {
        val pool = BufferPool(defaultBufferSize = 64)
        pool.clear() // Should be safe on empty pool
        pool.clear() // Double clear should also be safe
    }

    @Test
    fun clearPoolWithReturnedBuffers() {
        val pool = BufferPool(defaultBufferSize = 64, maxPoolSize = 10)

        // Acquire and release several buffers
        for (i in 0 until 5) {
            val buf = pool.acquire(32)
            buf.writeInt(i)
            pool.release(buf)
        }

        assertTrue(pool.stats().currentPoolSize > 0)

        // Clear should drain all without corruption
        pool.clear()
        assertEquals(0, pool.stats().currentPoolSize)
    }

    @Test
    fun poolFunctionsAfterClear() {
        val pool = BufferPool(defaultBufferSize = 64)

        val buf1 = pool.acquire(8)
        buf1.writeInt(42)
        pool.release(buf1)
        pool.clear()

        // Pool should still work after clearing
        val buf2 = pool.acquire(8)
        buf2.writeInt(99)
        buf2.resetForRead()
        assertEquals(99, buf2.readInt())
        pool.release(buf2)
        pool.clear()
    }

    // ============================================================================
    // Stats Accuracy
    // ============================================================================

    @Test
    fun statsTrackAllocationsCorrectly() {
        val pool = BufferPool(defaultBufferSize = 64, maxPoolSize = 10)

        assertEquals(0, pool.stats().totalAllocations)
        assertEquals(0, pool.stats().poolHits)
        assertEquals(0, pool.stats().poolMisses)

        // First acquire is always a miss
        val buf1 = pool.acquire(8)
        assertEquals(1, pool.stats().totalAllocations)
        assertEquals(0, pool.stats().poolHits)
        assertEquals(1, pool.stats().poolMisses)

        // Return and re-acquire should hit
        pool.release(buf1)
        val buf2 = pool.acquire(8)
        assertEquals(2, pool.stats().totalAllocations)
        assertEquals(1, pool.stats().poolHits)
        assertEquals(1, pool.stats().poolMisses)

        pool.release(buf2)
        pool.clear()
    }

    @Test
    fun hitRateCalculation() {
        val pool = BufferPool(defaultBufferSize = 64, maxPoolSize = 10)

        // Empty pool has 0 hit rate
        assertEquals(0.0, pool.stats().hitRate)

        // 1 miss → 0% hit rate
        val buf = pool.acquire(8)
        assertEquals(0.0, pool.stats().hitRate)

        // Release and re-acquire → 50% hit rate (1 hit, 1 miss, 2 total)
        pool.release(buf)
        val buf2 = pool.acquire(8)
        assertEquals(0.5, pool.stats().hitRate)

        pool.release(buf2)
        pool.clear()
    }

    @Test
    fun currentPoolSizeTracksCorrectly() {
        val pool = BufferPool(defaultBufferSize = 64, maxPoolSize = 5)

        assertEquals(0, pool.stats().currentPoolSize)

        val buffers = (1..3).map { pool.acquire(8) }
        assertEquals(0, pool.stats().currentPoolSize) // All acquired, none in pool

        // Release all
        buffers.forEach { pool.release(it) }
        assertEquals(3, pool.stats().currentPoolSize)

        pool.clear()
        assertEquals(0, pool.stats().currentPoolSize)
    }

    @Test
    fun maxPoolSizeEnforced() {
        val maxSize = 3
        val pool = BufferPool(defaultBufferSize = 64, maxPoolSize = maxSize)

        // Acquire 5 buffers
        val buffers = (1..5).map { pool.acquire(8) }

        // Release all 5 — only 3 should be kept
        buffers.forEach { pool.release(it) }
        assertTrue(pool.stats().currentPoolSize <= maxSize)

        pool.clear()
    }

    @Test
    fun peakPoolSizeTracked() {
        val pool = BufferPool(defaultBufferSize = 64, maxPoolSize = 10)

        val buffers = (1..4).map { pool.acquire(8) }
        buffers.forEach { pool.release(it) }

        assertTrue(pool.stats().peakPoolSize >= 4)

        pool.clear()
        // Peak should be preserved after clear
        assertTrue(pool.stats().peakPoolSize >= 4)
    }

    // ============================================================================
    // withBuffer Auto-Release
    // ============================================================================

    @Test
    fun withBufferReleasesOnNormalReturn() {
        val pool = BufferPool(defaultBufferSize = 64, maxPoolSize = 10)

        pool.withBuffer(8) { buffer ->
            buffer.writeInt(42)
        }

        // Buffer should have been returned to pool
        assertEquals(1, pool.stats().currentPoolSize)
        pool.clear()
    }

    @Test
    fun withBufferReleasesOnException() {
        val pool = BufferPool(defaultBufferSize = 64, maxPoolSize = 10)

        assertFailsWith<IllegalStateException> {
            pool.withBuffer(8) { buffer ->
                buffer.writeInt(42)
                error("test")
            }
        }

        // Buffer should still have been returned to pool
        assertEquals(1, pool.stats().currentPoolSize)
        pool.clear()
    }

    // ============================================================================
    // Pool Acquires Buffer of Sufficient Size
    // ============================================================================

    @Test
    fun acquireReturnsSufficientCapacity() {
        withPool(defaultBufferSize = 64) { pool ->
            val buf = pool.acquire(128)
            assertTrue(buf.capacity >= 128)
            pool.release(buf)
        }
    }

    @Test
    fun acquireWithZeroSizeUsesDefault() {
        withPool(defaultBufferSize = 256) { pool ->
            val buf = pool.acquire(0)
            assertTrue(buf.capacity >= 256)
            pool.release(buf)
        }
    }
}
