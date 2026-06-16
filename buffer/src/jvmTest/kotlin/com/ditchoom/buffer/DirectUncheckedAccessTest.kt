package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies [DirectJvmBuffer]'s unchecked fast-path accessors ([ReadBuffer.getUnchecked] /
 * [ReadBuffer.getLongUnchecked]) — which read straight from the native address via the FFM/Unsafe
 * direct path — return bit-identical results to the checked accessors. Covers both byte orders and a
 * sliced buffer (whose index 0 is a non-zero offset into the parent, exercising the position-independent
 * base-address derivation that an earlier prototype got wrong).
 */
class DirectUncheckedAccessTest {
    private fun directBufferOf(
        size: Int,
        order: ByteOrder,
    ): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(size, order)
        for (i in 0 until size) buf.writeByte((i * 7 + 1).toByte())
        buf.resetForRead()
        return buf
    }

    private fun assertUncheckedMatchesChecked(
        buf: ReadBuffer,
        size: Int,
    ) {
        for (i in 0 until size) {
            assertEquals(buf.get(i), buf.getUnchecked(i), "byte at $i")
        }
        for (i in 0..size - 8) {
            assertEquals(buf.getLong(i), buf.getLongUnchecked(i), "long at $i")
        }
    }

    @Test
    fun matchesCheckedBigEndian() {
        val size = 40
        val buf = directBufferOf(size, ByteOrder.BIG_ENDIAN)
        // Ensure we are actually exercising the direct native path, not a heap fallback.
        assertTrue(buf.unwrapFully() is DirectJvmBuffer, "expected DirectJvmBuffer from Default.allocate")
        assertUncheckedMatchesChecked(buf, size)
    }

    @Test
    fun matchesCheckedLittleEndian() {
        val size = 40
        assertUncheckedMatchesChecked(directBufferOf(size, ByteOrder.LITTLE_ENDIAN), size)
    }

    @Test
    fun matchesCheckedOnSlice() {
        val parent = directBufferOf(40, ByteOrder.BIG_ENDIAN)
        parent.position(8)
        val slice = parent.slice(ByteOrder.BIG_ENDIAN)
        assertUncheckedMatchesChecked(slice, 32)
    }
}
