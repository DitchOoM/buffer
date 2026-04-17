package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the [PlatformBuffer.growBuffer] extension function.
 */
class GrowableBufferTests {
    private val factories =
        listOf(
            "Default" to BufferFactory.Default,
            "managed" to BufferFactory.managed(),
            "shared" to BufferFactory.shared(),
        )

    @Test
    fun growDoublesCapacityByDefault() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(32)
            buffer.writeInt(42)
            val grown = buffer.growBuffer(factory)
            assertTrue(grown.capacity >= 64, "$name: should at least double (was 32, got ${grown.capacity})")
            grown.freeNativeMemory()
        }
    }

    @Test
    fun growPreservesWrittenData() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(8)
            buffer.writeInt(0x12345678)
            buffer.writeShort(0x9ABC.toShort())
            val grown = buffer.growBuffer(factory)
            // Position should be preserved
            assertEquals(6, grown.position(), "$name: position preserved")
            // Verify data by reading
            grown.resetForRead()
            assertEquals(0x12345678, grown.readInt(), "$name: int data preserved")
            assertEquals(0x9ABC.toShort(), grown.readShort(), "$name: short data preserved")
            grown.freeNativeMemory()
        }
    }

    @Test
    fun growPreservesPositionForContinuedWriting() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(4)
            buffer.writeShort(0x1234.toShort())
            val grown = buffer.growBuffer(factory)
            // Should be able to continue writing from where we left off
            grown.writeShort(0x5678.toShort())
            grown.writeInt(0xDEADBEEF.toInt())
            grown.resetForRead()
            assertEquals(0x1234.toShort(), grown.readShort(), "$name: first short")
            assertEquals(0x5678.toShort(), grown.readShort(), "$name: second short (written after grow)")
            assertEquals(0xDEADBEEF.toInt(), grown.readInt(), "$name: int (written after grow)")
            grown.freeNativeMemory()
        }
    }

    @Test
    fun growRespectsMinCapacity() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(8)
            buffer.writeInt(42)
            val grown = buffer.growBuffer(factory, minCapacity = 1024)
            assertTrue(grown.capacity >= 1024, "$name: should respect minCapacity")
            grown.freeNativeMemory()
        }
    }

    @Test
    fun growThrowsIfMinCapacityNotGreaterThanCurrent() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(32)
            assertFailsWith<IllegalArgumentException>("$name: minCapacity == capacity") {
                buffer.growBuffer(factory, minCapacity = 32)
            }
            assertFailsWith<IllegalArgumentException>("$name: minCapacity < capacity") {
                buffer.growBuffer(factory, minCapacity = 16)
            }
            buffer.freeNativeMemory()
        }
    }

    @Test
    fun growWithPoolReturnOldBufferToPool() {
        val pool = BufferPool(maxPoolSize = 16, factory = BufferFactory.Default)
        val factory = BufferFactory.Default.withPooling(pool)

        val buffer = factory.allocate(32)
        buffer.writeInt(42)

        val statsBefore = pool.stats()
        val grown = buffer.growBuffer(factory)

        val statsAfter = pool.stats()
        // The old buffer should have been returned to the pool
        assertTrue(
            statsAfter.currentPoolSize > statsBefore.currentPoolSize,
            "old buffer should be returned to pool " +
                "(currentPoolSize: ${statsBefore.currentPoolSize} -> ${statsAfter.currentPoolSize})",
        )

        // Data should still be preserved
        grown.resetForRead()
        assertEquals(42, grown.readInt())

        grown.freeNativeMemory()
        pool.clear()
    }

    @Test
    fun growWithPoolAcquiresNewBufferFromPool() {
        val pool = BufferPool(maxPoolSize = 16, factory = BufferFactory.Default)
        val factory = BufferFactory.Default.withPooling(pool)

        // Pre-populate pool with a large buffer
        val preAllocated = factory.allocate(256)
        preAllocated.freeNativeMemory() // returns to pool

        val small = factory.allocate(16)
        small.writeInt(99)
        val grown = small.growBuffer(factory, minCapacity = small.capacity * 2)

        // Data preserved
        grown.resetForRead()
        assertEquals(99, grown.readInt())

        grown.freeNativeMemory()
        pool.clear()
    }

    @Test
    fun growEmptyBuffer() {
        for ((name, factory) in factories) {
            val buffer = factory.allocate(4)
            // Don't write anything
            val grown = buffer.growBuffer(factory)
            assertEquals(0, grown.position(), "$name: position should be 0 for empty grow")
            assertTrue(grown.capacity >= 8, "$name: capacity should at least double")
            grown.freeNativeMemory()
        }
    }

    @Test
    fun growMinimumCapacityFloor() {
        for ((name, factory) in factories) {
            // Very small buffer — default 2x would be < 16, so floor of 16 applies
            val buffer = factory.allocate(2)
            buffer.writeByte(0x42)
            val grown = buffer.growBuffer(factory)
            assertTrue(grown.capacity >= 16, "$name: minimum capacity floor should be 16 (got ${grown.capacity})")
            grown.resetForRead()
            assertEquals(0x42.toByte(), grown.readByte(), "$name: data preserved")
            grown.freeNativeMemory()
        }
    }
}
