package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Edge case tests for indexOf operations on ReadBuffer.
 * Validates byte, Short, Int, Long, String, and ReadBuffer needle search
 * with aligned vs unaligned modes and cross-byte-order correctness.
 */
class IndexOfEdgeCaseTests {
    // ============================================================================
    // Byte indexOf
    // ============================================================================

    @Test
    fun indexOfByteAtPosition0() {
        val buf = BufferFactory.Default.allocate(8)
        buf.writeByte(0x42)
        for (i in 1 until 8) buf.writeByte(0)
        buf.resetForRead()
        assertEquals(0, buf.indexOf(0x42.toByte()))
    }

    @Test
    fun indexOfByteAtLastPosition() {
        val buf = BufferFactory.Default.allocate(8)
        for (i in 0 until 7) buf.writeByte(0)
        buf.writeByte(0x42)
        buf.resetForRead()
        assertEquals(7, buf.indexOf(0x42.toByte()))
    }

    @Test
    fun indexOfByteNotPresent() {
        val buf = BufferFactory.Default.allocate(8)
        for (i in 0 until 8) buf.writeByte(0)
        buf.resetForRead()
        assertEquals(-1, buf.indexOf(0x42.toByte()))
    }

    @Test
    fun indexOfByteInEmptyBuffer() {
        val buf = BufferFactory.Default.allocate(0)
        buf.resetForRead()
        assertEquals(-1, buf.indexOf(0x42.toByte()))
    }

    @Test
    fun indexOfByteReturnsFirstOccurrence() {
        val buf = BufferFactory.Default.allocate(8)
        buf.writeByte(0x42)
        buf.writeByte(0x00)
        buf.writeByte(0x42)
        buf.writeByte(0x00)
        buf.writeByte(0x42)
        for (i in 5 until 8) buf.writeByte(0)
        buf.resetForRead()
        assertEquals(0, buf.indexOf(0x42.toByte()))
    }

    @Test
    fun indexOfByteRelativeToPosition() {
        val buf = BufferFactory.Default.allocate(8)
        for (i in 0 until 4) buf.writeByte(0)
        buf.writeByte(0x42)
        for (i in 5 until 8) buf.writeByte(0)
        buf.resetForRead()
        buf.position(2) // Skip first 2 bytes
        assertEquals(2, buf.indexOf(0x42.toByte())) // Relative to position
    }

    @Test
    fun indexOfByteSingleElementBuffer() {
        val buf = BufferFactory.Default.allocate(1)
        buf.writeByte(0x42)
        buf.resetForRead()
        assertEquals(0, buf.indexOf(0x42.toByte()))
    }

    // ============================================================================
    // Short indexOf
    // ============================================================================

    @Test
    fun indexOfShortAtPosition0() {
        val buf = BufferFactory.Default.allocate(8)
        buf.writeShort(0x1234.toShort())
        for (i in 2 until 8) buf.writeByte(0)
        buf.resetForRead()
        assertEquals(0, buf.indexOf(0x1234.toShort()))
    }

    @Test
    fun indexOfShortAtLastValidPosition() {
        val buf = BufferFactory.Default.allocate(8)
        for (i in 0 until 6) buf.writeByte(0)
        buf.writeShort(0x1234.toShort())
        buf.resetForRead()
        assertEquals(6, buf.indexOf(0x1234.toShort()))
    }

    @Test
    fun indexOfShortNotPresent() {
        val buf = BufferFactory.Default.allocate(8)
        for (i in 0 until 8) buf.writeByte(0)
        buf.resetForRead()
        assertEquals(-1, buf.indexOf(0x1234.toShort()))
    }

    @Test
    fun indexOfShortBufferTooSmall() {
        val buf = BufferFactory.Default.allocate(1)
        buf.writeByte(0x12)
        buf.resetForRead()
        assertEquals(-1, buf.indexOf(0x1234.toShort()))
    }

    @Test
    fun indexOfShortAlignedSkipsOddPositions() {
        // Place the value at an odd offset — aligned search should miss it
        val buf = BufferFactory.Default.allocate(8)
        buf.writeByte(0x00)
        buf.writeShort(0x1234.toShort()) // at offset 1 (unaligned)
        for (i in 3 until 8) buf.writeByte(0)
        buf.resetForRead()
        assertEquals(1, buf.indexOf(0x1234.toShort(), aligned = false))
        assertEquals(-1, buf.indexOf(0x1234.toShort(), aligned = true))
    }

    @Test
    fun indexOfShortAlignedFindsAtEvenPosition() {
        val buf = BufferFactory.Default.allocate(8)
        buf.writeByte(0x00)
        buf.writeByte(0x00)
        buf.writeShort(0x1234.toShort()) // at offset 2 (aligned)
        for (i in 4 until 8) buf.writeByte(0)
        buf.resetForRead()
        assertEquals(2, buf.indexOf(0x1234.toShort(), aligned = true))
    }

    // ============================================================================
    // Int indexOf
    // ============================================================================

    @Test
    fun indexOfIntAtPosition0() {
        val buf = BufferFactory.Default.allocate(8)
        buf.writeInt(0x12345678)
        buf.writeInt(0)
        buf.resetForRead()
        assertEquals(0, buf.indexOf(0x12345678))
    }

    @Test
    fun indexOfIntAtLastValidPosition() {
        val buf = BufferFactory.Default.allocate(8)
        buf.writeInt(0)
        buf.writeInt(0x12345678)
        buf.resetForRead()
        assertEquals(4, buf.indexOf(0x12345678))
    }

    @Test
    fun indexOfIntNotPresent() {
        val buf = BufferFactory.Default.allocate(8)
        buf.writeLong(0)
        buf.resetForRead()
        assertEquals(-1, buf.indexOf(0x12345678))
    }

    @Test
    fun indexOfIntBufferTooSmall() {
        val buf = BufferFactory.Default.allocate(3)
        buf.writeByte(0x12)
        buf.writeByte(0x34)
        buf.writeByte(0x56)
        buf.resetForRead()
        assertEquals(-1, buf.indexOf(0x12345678))
    }

    @Test
    fun indexOfIntAlignedSkipsUnalignedPositions() {
        val buf = BufferFactory.Default.allocate(12)
        buf.writeByte(0x00)
        buf.writeInt(0x12345678) // at offset 1 (unaligned)
        for (i in 5 until 12) buf.writeByte(0)
        buf.resetForRead()
        assertEquals(1, buf.indexOf(0x12345678, aligned = false))
        assertEquals(-1, buf.indexOf(0x12345678, aligned = true))
    }

    // ============================================================================
    // Long indexOf
    // ============================================================================

    @Test
    fun indexOfLongAtPosition0() {
        val buf = BufferFactory.Default.allocate(16)
        buf.writeLong(0x123456789ABCDEF0L)
        buf.writeLong(0)
        buf.resetForRead()
        assertEquals(0, buf.indexOf(0x123456789ABCDEF0L))
    }

    @Test
    fun indexOfLongAtLastValidPosition() {
        val buf = BufferFactory.Default.allocate(16)
        buf.writeLong(0)
        buf.writeLong(0x123456789ABCDEF0L)
        buf.resetForRead()
        assertEquals(8, buf.indexOf(0x123456789ABCDEF0L))
    }

    @Test
    fun indexOfLongNotPresent() {
        val buf = BufferFactory.Default.allocate(16)
        buf.writeLong(0)
        buf.writeLong(0)
        buf.resetForRead()
        assertEquals(-1, buf.indexOf(0x123456789ABCDEF0L))
    }

    @Test
    fun indexOfLongBufferTooSmall() {
        val buf = BufferFactory.Default.allocate(7)
        for (i in 0 until 7) buf.writeByte(0)
        buf.resetForRead()
        assertEquals(-1, buf.indexOf(0x123456789ABCDEF0L))
    }

    @Test
    fun indexOfLongAlignedSkipsUnalignedPositions() {
        val buf = BufferFactory.Default.allocate(24)
        for (i in 0 until 3) buf.writeByte(0x00) // 3 bytes padding
        buf.writeLong(0x123456789ABCDEF0L) // at offset 3 (unaligned)
        for (i in 11 until 24) buf.writeByte(0)
        buf.resetForRead()
        assertEquals(3, buf.indexOf(0x123456789ABCDEF0L, aligned = false))
        assertEquals(-1, buf.indexOf(0x123456789ABCDEF0L, aligned = true))
    }

    // ============================================================================
    // String indexOf
    // ============================================================================

    @Test
    fun indexOfStringAtBeginning() {
        val buf = BufferFactory.Default.allocate(32)
        buf.writeString("Hello World")
        buf.resetForRead()
        assertEquals(0, buf.indexOf("Hello"))
    }

    @Test
    fun indexOfStringInMiddle() {
        val buf = BufferFactory.Default.allocate(32)
        buf.writeString("Hello World")
        buf.resetForRead()
        assertEquals(6, buf.indexOf("World"))
    }

    @Test
    fun indexOfStringNotPresent() {
        val buf = BufferFactory.Default.allocate(32)
        buf.writeString("Hello World")
        buf.resetForRead()
        assertEquals(-1, buf.indexOf("Goodbye"))
    }

    @Test
    fun indexOfEmptyStringReturnsZero() {
        val buf = BufferFactory.Default.allocate(8)
        buf.writeString("test")
        buf.resetForRead()
        assertEquals(0, buf.indexOf(""))
    }

    @Test
    fun indexOfStringInEmptyBuffer() {
        val buf = BufferFactory.Default.allocate(0)
        buf.resetForRead()
        assertEquals(-1, buf.indexOf("test"))
    }

    // ============================================================================
    // ReadBuffer needle indexOf
    // ============================================================================

    @Test
    fun indexOfBufferNeedleAtStart() {
        val haystack = BufferFactory.Default.allocate(16)
        haystack.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        haystack.resetForRead()

        val needle = BufferFactory.Default.allocate(3)
        needle.writeBytes(byteArrayOf(1, 2, 3))
        needle.resetForRead()

        assertEquals(0, haystack.indexOf(needle))
    }

    @Test
    fun indexOfBufferNeedleAtEnd() {
        val haystack = BufferFactory.Default.allocate(16)
        haystack.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        haystack.resetForRead()

        val needle = BufferFactory.Default.allocate(3)
        needle.writeBytes(byteArrayOf(6, 7, 8))
        needle.resetForRead()

        assertEquals(5, haystack.indexOf(needle))
    }

    @Test
    fun indexOfBufferNeedleNotFound() {
        val haystack = BufferFactory.Default.allocate(16)
        haystack.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        haystack.resetForRead()

        val needle = BufferFactory.Default.allocate(3)
        needle.writeBytes(byteArrayOf(9, 10, 11))
        needle.resetForRead()

        assertEquals(-1, haystack.indexOf(needle))
    }

    @Test
    fun indexOfEmptyNeedleReturnsZero() {
        val haystack = BufferFactory.Default.allocate(8)
        haystack.writeBytes(byteArrayOf(1, 2, 3))
        haystack.resetForRead()

        val needle = BufferFactory.Default.allocate(0)
        needle.resetForRead()

        assertEquals(0, haystack.indexOf(needle))
    }

    @Test
    fun indexOfNeedleLargerThanHaystackReturnsNegative() {
        val haystack = BufferFactory.Default.allocate(4)
        haystack.writeBytes(byteArrayOf(1, 2))
        haystack.resetForRead()

        val needle = BufferFactory.Default.allocate(8)
        needle.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        needle.resetForRead()

        assertEquals(-1, haystack.indexOf(needle))
    }

    @Test
    fun indexOfSingleByteNeedleDelegatesToByteSearch() {
        val haystack = BufferFactory.Default.allocate(8)
        haystack.writeBytes(byteArrayOf(0, 0, 0, 0x42, 0, 0))
        haystack.resetForRead()

        val needle = BufferFactory.Default.allocate(1)
        needle.writeByte(0x42)
        needle.resetForRead()

        assertEquals(3, haystack.indexOf(needle))
    }

    // ============================================================================
    // Cross Byte-Order
    // ============================================================================

    @Test
    fun indexOfShortRespectsBufferByteOrder() {
        // Write 0x1234 in big-endian → bytes are [0x12, 0x34]
        val be = BufferFactory.Default.allocate(4, byteOrder = ByteOrder.BIG_ENDIAN)
        be.writeShort(0x1234.toShort())
        be.writeShort(0)
        be.resetForRead()
        assertEquals(0, be.indexOf(0x1234.toShort()))

        // Write 0x1234 in little-endian → bytes are [0x34, 0x12]
        val le = BufferFactory.Default.allocate(4, byteOrder = ByteOrder.LITTLE_ENDIAN)
        le.writeShort(0x1234.toShort())
        le.writeShort(0)
        le.resetForRead()
        assertEquals(0, le.indexOf(0x1234.toShort()))
    }

    @Test
    fun indexOfIntRespectsBufferByteOrder() {
        val be = BufferFactory.Default.allocate(8, byteOrder = ByteOrder.BIG_ENDIAN)
        be.writeInt(0x12345678)
        be.writeInt(0)
        be.resetForRead()
        assertEquals(0, be.indexOf(0x12345678))

        val le = BufferFactory.Default.allocate(8, byteOrder = ByteOrder.LITTLE_ENDIAN)
        le.writeInt(0x12345678)
        le.writeInt(0)
        le.resetForRead()
        assertEquals(0, le.indexOf(0x12345678))
    }

    // ============================================================================
    // Managed vs Direct factory coverage
    // ============================================================================

    @Test
    fun indexOfByteWorksOnManagedBuffer() {
        val buf = BufferFactory.managed().allocate(8)
        buf.writeByte(0x00)
        buf.writeByte(0x00)
        buf.writeByte(0x42)
        for (i in 3 until 8) buf.writeByte(0)
        buf.resetForRead()
        assertEquals(2, buf.indexOf(0x42.toByte()))
    }

    @Test
    fun indexOfIntWorksOnManagedBuffer() {
        val buf = BufferFactory.managed().allocate(8)
        buf.writeInt(0)
        buf.writeInt(0xDEADBEEF.toInt())
        buf.resetForRead()
        assertEquals(4, buf.indexOf(0xDEADBEEF.toInt()))
    }
}
