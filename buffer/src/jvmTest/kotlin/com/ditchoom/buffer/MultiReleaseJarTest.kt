package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests to verify multi-release JAR structure and mismatch correctness.
 */
class MultiReleaseJarTest {
    @Test
    fun `mismatch produces correct results`() {
        val buffer1 = PlatformBuffer.allocate(100)
        val buffer2 = PlatformBuffer.allocate(100)

        // Write same data to both
        repeat(100) {
            buffer1.writeByte(it.toByte())
            buffer2.writeByte(it.toByte())
        }
        buffer1.resetForRead()
        buffer2.resetForRead()

        // Should match
        assertTrue(buffer1.contentEquals(buffer2), "Identical buffers should match")
        assertEquals(-1, buffer1.mismatch(buffer2), "Identical buffers should have no mismatch")

        // Reset and modify one byte
        buffer1.position(0)
        buffer2.position(0)
        buffer2.position(50)
        buffer2.writeByte(99.toByte()) // Change byte at position 50
        buffer1.position(0)
        buffer2.position(0)

        // Should find mismatch at position 50
        assertEquals(50, buffer1.mismatch(buffer2), "Should find mismatch at position 50")
    }

    @Test
    fun `mismatch handles edge cases`() {
        // Empty buffers
        val empty1 = PlatformBuffer.allocate(0)
        val empty2 = PlatformBuffer.allocate(0)
        assertEquals(-1, empty1.mismatch(empty2), "Empty buffers should match")

        // Different sizes
        val small = PlatformBuffer.allocate(10)
        val large = PlatformBuffer.allocate(20)
        repeat(10) {
            small.writeByte(it.toByte())
            large.writeByte(it.toByte())
        }
        repeat(10) { large.writeByte(it.toByte()) }
        small.resetForRead()
        large.resetForRead()

        // Should return index where they differ (at end of smaller buffer)
        assertEquals(10, small.mismatch(large), "Should find mismatch at end of smaller buffer")

        // First byte mismatch
        val a = PlatformBuffer.allocate(10)
        val b = PlatformBuffer.allocate(10)
        a.writeByte(1)
        b.writeByte(2)
        a.resetForRead()
        b.resetForRead()
        assertEquals(0, a.mismatch(b), "Should find mismatch at first byte")
    }
}
