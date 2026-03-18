package com.ditchoom.buffer

import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.pool.BufferPool
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression tests for writing zero-length buffers into all buffer types.
 *
 * Validates that write(EMPTY_BUFFER) is a safe no-op on every platform.
 * On Apple (MutableDataBuffer), pinned.addressOf(0) on ByteArray(0) previously
 * threw ArrayIndexOutOfBoundsException (see autobahn case 6.1.3).
 */
class WriteEmptyBufferTests {
    @Test
    fun writeEmptyBufferIntoManagedBuffer() {
        val target = BufferFactory.managed().allocate(16)
        target.write(EMPTY_BUFFER)
        assertEquals(0, target.position())
    }

    @Test
    fun writeEmptyBufferIntoDefaultBuffer() {
        val target = BufferFactory.Default.allocate(16)
        target.write(EMPTY_BUFFER)
        assertEquals(0, target.position())
    }

    @Test
    fun writeEmptyBufferIntoDeterministicBuffer() {
        val target = BufferFactory.deterministic().allocate(16)
        target.write(EMPTY_BUFFER)
        assertEquals(0, target.position())
    }

    @Test
    fun writeEmptyManagedBufferIntoDefaultBuffer() {
        val empty = BufferFactory.managed().allocate(0)
        empty.resetForRead()
        val target = BufferFactory.Default.allocate(16)
        target.write(empty)
        assertEquals(0, target.position())
    }

    @Test
    fun writeEmptyDefaultBufferIntoDefaultBuffer() {
        val empty = BufferFactory.Default.allocate(0)
        empty.resetForRead()
        val target = BufferFactory.Default.allocate(16)
        target.write(empty)
        assertEquals(0, target.position())
    }

    /** Regression: WebSocket fragment assembly combines EMPTY_BUFFER + data + EMPTY_BUFFER into a pooled buffer. */
    @Test
    fun combineEmptyAndNonEmptyFragments() {
        val pool = BufferPool()
        val combined = pool.acquire(20)

        // Simulate [EMPTY_BUFFER, 20-byte payload, EMPTY_BUFFER]
        combined.write(EMPTY_BUFFER)
        val payload = BufferFactory.managed().allocate(20)
        repeat(20) { payload.writeByte((it + 1).toByte()) }
        payload.resetForRead()
        combined.write(payload)
        combined.write(EMPTY_BUFFER)

        combined.resetForRead()
        assertEquals(20, combined.remaining())
        assertEquals(1, combined.readByte())
        pool.clear()
    }

    /** Same test with Default (native) factory to ensure cross-platform consistency. */
    @Test
    fun combineEmptyAndNonEmptyFragmentsDefaultFactory() {
        val pool = BufferPool(factory = BufferFactory.Default)
        val combined = pool.acquire(20)

        combined.write(EMPTY_BUFFER)
        val payload = BufferFactory.Default.allocate(20)
        repeat(20) { payload.writeByte((it + 1).toByte()) }
        payload.resetForRead()
        combined.write(payload)
        combined.write(EMPTY_BUFFER)

        combined.resetForRead()
        assertEquals(20, combined.remaining())
        assertEquals(1, combined.readByte())
        pool.clear()
    }

    /** Same test with deterministic factory. */
    @Test
    fun combineEmptyAndNonEmptyFragmentsDeterministicFactory() {
        val factory = BufferFactory.deterministic()
        val pool = BufferPool(factory = factory)
        val combined = pool.acquire(20)

        combined.write(EMPTY_BUFFER)
        val payload = factory.allocate(20)
        repeat(20) { payload.writeByte((it + 1).toByte()) }
        payload.resetForRead()
        combined.write(payload)
        combined.write(EMPTY_BUFFER)

        combined.resetForRead()
        assertEquals(20, combined.remaining())
        assertEquals(1, combined.readByte())
        pool.clear()
    }

    /** Write an empty default-allocated buffer (not EMPTY_BUFFER singleton) into each factory type. */
    @Test
    fun writeZeroCapacityAllocatedBuffers() {
        for (factory in listOf(BufferFactory.managed(), BufferFactory.Default, BufferFactory.deterministic())) {
            val empty = factory.allocate(0)
            empty.resetForRead()
            assertEquals(0, empty.remaining())

            for (targetFactory in listOf(BufferFactory.managed(), BufferFactory.Default, BufferFactory.deterministic())) {
                val target = targetFactory.allocate(8)
                target.write(empty)
                assertEquals(0, target.position(), "Writing empty ${empty::class.simpleName} into ${target::class.simpleName} should be a no-op")
            }
        }
    }
}
