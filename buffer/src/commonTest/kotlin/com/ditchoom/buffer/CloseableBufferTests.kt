package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Edge case tests for CloseableBuffer lifecycle and the use {} pattern.
 * Validates isFreed state, use {} cleanup, and double-close safety.
 */
class CloseableBufferTests {
    // ============================================================================
    // use {} Pattern
    // ============================================================================

    @Test
    fun useBlockReturnsValue() {
        val result =
            BufferFactory.Default.allocate(8).use { buf ->
                buf.writeInt(42)
                buf.resetForRead()
                buf.readInt()
            }
        assertEquals(42, result)
    }

    @Test
    fun useBlockCallsFreeOnCloseableBuffer() {
        val buf = BufferFactory.Default.allocate(8)
        buf.use { it.writeInt(42) }
        if (buf is CloseableBuffer) {
            assertTrue(buf.isFreed, "CloseableBuffer should be freed after use {}")
        }
        // Non-closeable buffers just return normally — no assertion needed
    }

    @Test
    fun useBlockSafeOnNonCloseableBuffer() {
        // wrap() typically returns GC-managed buffers (not CloseableBuffer)
        val buf = BufferFactory.Default.wrap(byteArrayOf(1, 2, 3, 4))
        buf.use { b ->
            assertEquals(1.toByte(), b.readByte())
        }
        // Should not throw — use {} is a no-op on non-closeable
    }

    @Test
    fun useBlockFreesEvenOnException() {
        val buf = BufferFactory.Default.allocate(8)
        try {
            buf.use { throw RuntimeException("test") }
        } catch (_: RuntimeException) {
            // expected
        }
        if (buf is CloseableBuffer) {
            assertTrue(buf.isFreed, "Buffer should be freed even when block throws")
        }
    }

    // ============================================================================
    // isFreed State
    // ============================================================================

    @Test
    fun newBufferIsNotFreed() {
        val buf = BufferFactory.Default.allocate(8)
        if (buf is CloseableBuffer) {
            assertFalse(buf.isFreed)
        }
    }

    @Test
    fun bufferIsFreedAfterFreeNativeMemory() {
        val buf = BufferFactory.Default.allocate(8)
        buf.freeNativeMemory()
        if (buf is CloseableBuffer) {
            assertTrue(buf.isFreed)
        }
    }

    @Test
    fun doubleFreeDoesNotThrow() {
        val buf = BufferFactory.Default.allocate(8)
        buf.freeNativeMemory()
        // Second free should be safe (no-op or idempotent)
        buf.freeNativeMemory()
        if (buf is CloseableBuffer) {
            assertTrue(buf.isFreed)
        }
    }

    // ============================================================================
    // Nested use {} Scopes
    // ============================================================================

    @Test
    fun nestedUseBlocksCleanUpInOrder() {
        val outer = BufferFactory.Default.allocate(8)
        val inner = BufferFactory.Default.allocate(8)

        outer.use { o ->
            o.writeInt(1)
            inner.use { i ->
                i.writeInt(2)
                i.resetForRead()
                assertEquals(2, i.readInt())
            }
            // inner should be freed here (if closeable)
            if (inner is CloseableBuffer) {
                assertTrue(inner.isFreed)
            }
            o.resetForRead()
            assertEquals(1, o.readInt())
        }
        // outer should be freed here (if closeable)
        if (outer is CloseableBuffer) {
            assertTrue(outer.isFreed)
        }
    }

    // ============================================================================
    // Zero-size Buffer
    // ============================================================================

    @Test
    fun useWithZeroSizeBuffer() {
        val buf = BufferFactory.Default.allocate(0)
        buf.use { b ->
            assertEquals(0, b.remaining())
        }
        if (buf is CloseableBuffer) {
            assertTrue(buf.isFreed)
        }
    }

    // ============================================================================
    // Managed Buffer Cleanup
    // ============================================================================

    @Test
    fun managedBufferUseIsSafe() {
        // managed() returns heap/GC-managed buffers — use {} should work fine
        val buf = BufferFactory.managed().allocate(8)
        val result =
            buf.use { b ->
                b.writeInt(99)
                b.resetForRead()
                b.readInt()
            }
        assertEquals(99, result)
    }

    @Test
    fun wrappedBufferUseIsSafe() {
        val data = byteArrayOf(10, 20, 30, 40)
        val result =
            BufferFactory.Default.wrap(data).use { b ->
                b.readByte().toInt()
            }
        assertEquals(10, result)
    }
}
