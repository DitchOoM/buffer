package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for BufferFactory.deterministic(threadConfined = true) on JVM 21+.
 */
class DeterministicConfinedTest {
    @Test
    fun `deterministic threadConfined allocates working buffer`() {
        val factory = BufferFactory.deterministic(threadConfined = true)
        val buffer = factory.allocate(64)
        buffer.writeInt(0x12345678)
        buffer.resetForRead()
        assertEquals(0x12345678, buffer.readInt())
        buffer.freeNativeMemory()
    }

    @Test
    fun `deterministic threadConfined buffer implements CloseableBuffer`() {
        val factory = BufferFactory.deterministic(threadConfined = true)
        val buffer = factory.allocate(64)
        // On JVM 21+, this is a confined FfmBuffer (CloseableBuffer)
        // On JVM < 21, this falls back to the same deterministic impl
        assertIs<CloseableBuffer>(buffer)
        buffer.freeNativeMemory()
    }

    @Test
    fun `deterministic threadConfined use pattern works`() {
        val factory = BufferFactory.deterministic(threadConfined = true)
        factory.allocate(128).use { buffer ->
            buffer.writeLong(0x123456789ABCDEF0L)
            buffer.resetForRead()
            assertEquals(0x123456789ABCDEF0L, buffer.readLong())
        }
    }

    @Test
    fun `deterministic threadConfined has native memory access`() {
        val factory = BufferFactory.deterministic(threadConfined = true)
        factory.allocate(64).use { buffer ->
            assertIs<NativeMemoryAccess>(buffer)
            val nma = buffer as NativeMemoryAccess
            assertTrue(nma.nativeAddress != 0L)
            assertEquals(64L, nma.nativeSize)
        }
    }

    @Test
    fun `deterministic default is not thread confined`() {
        // default (threadConfined = false) should also work
        val factory = BufferFactory.deterministic()
        factory.allocate(64).use { buffer ->
            assertIs<CloseableBuffer>(buffer)
            buffer.writeInt(42)
            buffer.resetForRead()
            assertEquals(42, buffer.readInt())
        }
    }
}
