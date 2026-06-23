package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.withPooling
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SecurePoolTest {
    // A managed (heap) delegate keeps the raw backing readable AFTER it returns to the pool
    // (managed free is a GC no-op), so a test can inspect what the wipe left behind. The factory
    // also records every raw buffer it hands the pool, so a test can read the exact backing the
    // PooledBuffer wraps — the SecureBuffer/PooledBuffer wrappers throw after free, the raw does not.
    private class CapturingFactory(
        private val delegate: BufferFactory = BufferFactory.managed(),
    ) : BufferFactory {
        val allocated = mutableListOf<PlatformBuffer>()

        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = delegate.allocate(size, byteOrder).also { allocated += it }

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = delegate.wrap(array, byteOrder).also { allocated += it }
    }

    private fun PlatformBuffer.writeAllFF(n: Int) = repeat(n) { writeByte(0xFF.toByte()) }

    private fun PlatformBuffer.assertAllZero(n: Int) {
        for (i in 0 until n) assertEquals(0, get(i).toInt(), "byte $i not wiped")
    }

    // --- The headline fix: both composition orders wipe-then-return-to-pool ---

    @Test
    fun secureInnermostPooled_wipesAndReturnsToPool() {
        val raws = CapturingFactory()
        val pool = BufferPool(defaultBufferSize = 16, factory = raws)
        // secure() is the delegate; withPooling borrows from the pool and decorates the borrow.
        val factory = BufferFactory.managed().secure().withPooling(pool)

        val buf = factory.allocate(16)
        assertTrue(buf is SecureBuffer, "borrowed buffer should be secure-wrapped")
        buf.writeAllFF(16)
        buf.freeNativeMemory()

        assertEquals(1, raws.allocated.size, "exactly one raw buffer should have been allocated")
        raws.allocated[0].assertAllZero(16)
        assertEquals(1, pool.stats().currentPoolSize, "wiped buffer should be back in the pool")
    }

    @Test
    fun secureOutermostPooled_wipesAndReturnsToPool() {
        val raws = CapturingFactory()
        val pool = BufferPool(defaultBufferSize = 16, factory = raws)
        // Opposite order: pool first, then secure on the outside.
        val factory = BufferFactory.managed().withPooling(pool).secure()

        val buf = factory.allocate(16)
        assertTrue(buf is SecureBuffer, "borrowed buffer should be secure-wrapped")
        buf.writeAllFF(16)
        buf.freeNativeMemory()

        assertEquals(1, raws.allocated.size)
        raws.allocated[0].assertAllZero(16)
        assertEquals(1, pool.stats().currentPoolSize)
    }

    @Test
    fun bothOrders_reuseTheSamePooledBacking() {
        // Prove the buffer actually returns to the pool and is reused (pool hit), not re-allocated.
        val raws = CapturingFactory()
        val pool = BufferPool(defaultBufferSize = 16, factory = raws)
        val factory = BufferFactory.managed().secure().withPooling(pool)

        factory.allocate(16).also { it.writeAllFF(16) }.freeNativeMemory()
        factory.allocate(16).freeNativeMemory()

        assertEquals(1, raws.allocated.size, "second allocation should reuse the pooled backing, not allocate")
        assertTrue(pool.stats().poolHits >= 1, "second acquire should be a pool hit")
    }

    // --- decorate() semantics ---

    @Test
    fun decorate_zeroInitsTheBorrowedBuffer() {
        val raw = BufferFactory.managed().allocate(16)
        raw.writeAllFF(16)
        val secure = BufferFactory.managed().secure().decorate(raw)
        assertTrue(secure is SecureBuffer)
        secure.assertAllZero(16) // zero-init cleared the prior contents
    }

    @Test
    fun decorate_defaultIsIdentityOnNonWrappingFactories() {
        val raw = BufferFactory.managed().allocate(8)
        assertSame(raw, BufferFactory.managed().decorate(raw))
        assertSame(raw, BufferFactory.Default.decorate(raw))
    }

    // --- non-secure pooling is unchanged (decorate == identity) ---

    @Test
    fun nonSecurePooling_isNotWrappedAndNotWiped() {
        val raws = CapturingFactory()
        val pool = BufferPool(defaultBufferSize = 16, factory = raws)
        val factory = BufferFactory.managed().withPooling(pool)

        val buf = factory.allocate(16)
        assertFalse(buf is SecureBuffer, "plain pooling must not secure-wrap")
        buf.writeAllFF(16)
        buf.freeNativeMemory()

        // No secure layer → no wipe; the bytes survive in the pooled backing.
        assertEquals(1, raws.allocated.size)
        assertEquals(0xFF, raws.allocated[0].get(0).toInt() and 0xFF)
        assertEquals(1, pool.stats().currentPoolSize, "plain pooling still returns to the pool")
    }

    // --- byte-order-mismatch fallback still decorates (allocates fresh secure, non-pooled) ---

    @Test
    fun byteOrderMismatch_fallsBackToFreshSecureBuffer() {
        val raws = CapturingFactory()
        // Pool hands out BIG_ENDIAN buffers; request LITTLE_ENDIAN to force the fallback path.
        val pool = BufferPool(defaultBufferSize = 16, factory = raws)
        val factory = BufferFactory.managed().secure().withPooling(pool)

        val buf = factory.allocate(16, ByteOrder.LITTLE_ENDIAN)
        assertTrue(buf is SecureBuffer, "fallback path must still be secure")
        assertEquals(ByteOrder.LITTLE_ENDIAN, buf.byteOrder)
    }

    // --- documented cap asymmetry between the two orders ---

    @Test
    fun capAsymmetry_secureOutermostEnforces_innermostDefers() {
        // secure() innermost: the requested size reaches the pool before the secure wrap, so the
        // cap is NOT applied to the pooled path (sizing is deferred to the pool).
        val innermost =
            BufferFactory
                .managed()
                .secure(maxAllocationBytes = 64)
                .withPooling(BufferPool(defaultBufferSize = 16, factory = BufferFactory.managed()))
        innermost.allocate(128).freeNativeMemory() // does not throw

        // secure() outermost: the cap is enforced before delegating to the pool.
        val outermost =
            BufferFactory
                .managed()
                .withPooling(BufferPool(defaultBufferSize = 16, factory = BufferFactory.managed()))
                .secure(maxAllocationBytes = 64)
        assertFailsWith<IllegalArgumentException> { outermost.allocate(128) }
    }

    // --- withSecureBuffer ---

    @Test
    fun withSecureBuffer_zeroInitsWipesAndReturns() {
        val raws = CapturingFactory()
        val pool = BufferPool(defaultBufferSize = 16, factory = raws)

        pool.withSecureBuffer(16) { buf ->
            buf.assertAllZero(16) // zero-init on borrow
            buf.writeAllFF(16)
        }

        assertEquals(1, raws.allocated.size)
        raws.allocated[0].assertAllZero(16) // wiped on the way back to the pool
        assertEquals(1, pool.stats().currentPoolSize)

        // Second borrow reuses the same wiped backing.
        pool.withSecureBuffer(16) { /* no-op */ }
        assertEquals(1, raws.allocated.size)
        assertTrue(pool.stats().poolHits >= 1)
    }

    @Test
    fun withSecureBuffer_wipesEvenWhenBlockThrows() {
        val raws = CapturingFactory()
        val pool = BufferPool(defaultBufferSize = 16, factory = raws)

        assertFailsWith<IllegalStateException> {
            pool.withSecureBuffer(16) { buf ->
                buf.writeAllFF(16)
                throw IllegalStateException("boom")
            }
        }
        raws.allocated[0].assertAllZero(16) // finally{} wiped despite the exception
    }

    // --- secureFixedPool ---

    @Test
    fun secureFixedPool_wipesReusesAndStaysFixedSize() {
        val raws = CapturingFactory()
        val factory = secureFixedPool(bufferSize = 16, base = raws)

        val first = factory.allocate(16)
        assertTrue(first is SecureBuffer)
        assertEquals(16, first.capacity)
        first.writeAllFF(16)
        first.freeNativeMemory()
        raws.allocated[0].assertAllZero(16)

        // Reuse: a second fixed-size allocation does not allocate a new backing.
        factory.allocate(16).freeNativeMemory()
        assertEquals(1, raws.allocated.size, "fixed pool should reuse the single backing")
    }

    @Test
    fun secureFixedPool_rejectsNonPositiveSize() {
        assertFailsWith<IllegalArgumentException> { secureFixedPool(bufferSize = 0) }
    }

    // --- toSecureBuffer extension ---

    @Test
    fun toSecureBuffer_preservesContentsByDefault_zeroFirstClears() {
        val keep = BufferFactory.managed().allocate(8)
        keep.writeAllFF(8)
        val preserved = keep.toSecureBuffer()
        assertEquals(0xFF, preserved.get(0).toInt() and 0xFF)

        val dirty = BufferFactory.managed().allocate(8)
        dirty.writeAllFF(8)
        val cleared = dirty.toSecureBuffer(zeroFirst = true)
        cleared.assertAllZero(8)
    }

    @Test
    fun toSecureBuffer_wipesOnFree() {
        val raw = BufferFactory.managed().allocate(8)
        val secure = raw.toSecureBuffer()
        repeat(8) { secure.writeByte(0xFF.toByte()) }
        secure.freeNativeMemory()
        raw.assertAllZero(8) // raw is the managed backing, still readable post-free
    }
}
