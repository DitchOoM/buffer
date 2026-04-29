package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Edge case tests for fill and xorMask operations.
 * Validates zero-length, single-byte, non-aligned fills, and mask edge cases.
 */
class FillMaskEdgeCaseTests {
    // ============================================================================
    // Byte Fill
    // ============================================================================

    @Test
    fun fillByteZeroLength() {
        val buf = BufferFactory.Default.allocate(8)
        buf.position(8) // position = limit, remaining = 0
        buf.fill(0xFF.toByte()) // Should be no-op
        assertEquals(8, buf.position())
    }

    @Test
    fun fillByteSingleByte() {
        val buf = BufferFactory.Default.allocate(1)
        buf.fill(0x42.toByte())
        buf.resetForRead()
        assertEquals(0x42.toByte(), buf.readByte())
    }

    @Test
    fun fillByteNonAlignedToLong() {
        // 5 bytes — not a multiple of 8, tests the tail-byte logic
        val buf = BufferFactory.Default.allocate(5)
        buf.fill(0xAB.toByte())
        buf.resetForRead()
        for (i in 0 until 5) {
            assertEquals(0xAB.toByte(), buf.readByte(), "Byte at index $i")
        }
    }

    @Test
    fun fillByteExactlyEightBytes() {
        val buf = BufferFactory.Default.allocate(8)
        buf.fill(0xCD.toByte())
        buf.resetForRead()
        for (i in 0 until 8) {
            assertEquals(0xCD.toByte(), buf.readByte(), "Byte at index $i")
        }
    }

    @Test
    fun fillByteAdvancesPositionToLimit() {
        val buf = BufferFactory.Default.allocate(16)
        buf.fill(0x00.toByte())
        assertEquals(16, buf.position())
        assertEquals(16, buf.limit())
    }

    @Test
    fun fillByteFromMidPosition() {
        val buf = BufferFactory.Default.allocate(8)
        buf.writeByte(0x11)
        buf.writeByte(0x22)
        // position = 2, remaining = 6
        buf.fill(0xFF.toByte())
        buf.resetForRead()
        assertEquals(0x11.toByte(), buf.readByte())
        assertEquals(0x22.toByte(), buf.readByte())
        for (i in 2 until 8) {
            assertEquals(0xFF.toByte(), buf.readByte(), "Byte at index $i")
        }
    }

    // ============================================================================
    // Short Fill
    // ============================================================================

    @Test
    fun fillShortZeroLength() {
        val buf = BufferFactory.Default.allocate(8)
        buf.position(8)
        buf.fill(0x1234.toShort()) // no-op
        assertEquals(8, buf.position())
    }

    @Test
    fun fillShortExactlyTwoBytes() {
        val buf = BufferFactory.Default.allocate(2)
        buf.fill(0x1234.toShort())
        buf.resetForRead()
        assertEquals(0x1234.toShort(), buf.readShort())
    }

    @Test
    fun fillShortOddRemainingThrows() {
        val buf = BufferFactory.Default.allocate(3)
        assertFailsWith<IllegalArgumentException> {
            buf.fill(0x1234.toShort())
        }
    }

    @Test
    fun fillShortMultipleValues() {
        val buf = BufferFactory.Default.allocate(8)
        buf.fill(0xABCD.toShort())
        buf.resetForRead()
        for (i in 0 until 4) {
            assertEquals(0xABCD.toShort(), buf.readShort(), "Short at index $i")
        }
    }

    // ============================================================================
    // Int Fill
    // ============================================================================

    @Test
    fun fillIntZeroLength() {
        val buf = BufferFactory.Default.allocate(8)
        buf.position(8)
        buf.fill(0x12345678) // no-op
        assertEquals(8, buf.position())
    }

    @Test
    fun fillIntExactlyFourBytes() {
        val buf = BufferFactory.Default.allocate(4)
        buf.fill(0xDEADBEEF.toInt())
        buf.resetForRead()
        assertEquals(0xDEADBEEF.toInt(), buf.readInt())
    }

    @Test
    fun fillIntNonMultipleOf4Throws() {
        val buf = BufferFactory.Default.allocate(5)
        assertFailsWith<IllegalArgumentException> {
            buf.fill(0x12345678)
        }
    }

    @Test
    fun fillIntEightBytes() {
        val buf = BufferFactory.Default.allocate(8)
        buf.fill(0xCAFEBABE.toInt())
        buf.resetForRead()
        assertEquals(0xCAFEBABE.toInt(), buf.readInt())
        assertEquals(0xCAFEBABE.toInt(), buf.readInt())
    }

    // ============================================================================
    // Long Fill
    // ============================================================================

    @Test
    fun fillLongZeroLength() {
        val buf = BufferFactory.Default.allocate(8)
        buf.position(8)
        buf.fill(0x123456789ABCDEF0L) // no-op
        assertEquals(8, buf.position())
    }

    @Test
    fun fillLongExactlyEightBytes() {
        val buf = BufferFactory.Default.allocate(8)
        buf.fill(0x123456789ABCDEF0L)
        buf.resetForRead()
        assertEquals(0x123456789ABCDEF0L, buf.readLong())
    }

    @Test
    fun fillLongNonMultipleOf8Throws() {
        val buf = BufferFactory.Default.allocate(12)
        assertFailsWith<IllegalArgumentException> {
            buf.fill(0x123456789ABCDEF0L)
        }
    }

    @Test
    fun fillLongMultipleValues() {
        val buf = BufferFactory.Default.allocate(24)
        val pattern = -0x123456789ABCDF0L // 0xFEDCBA9876543210
        buf.fill(pattern)
        buf.resetForRead()
        for (i in 0 until 3) {
            assertEquals(pattern, buf.readLong(), "Long at index $i")
        }
    }

    // ============================================================================
    // xorMask Edge Cases
    // ============================================================================

    @Test
    fun xorMaskSingleByte() {
        val buf = BufferFactory.Default.allocate(1) as ReadWriteBuffer
        buf.writeByte(0x00)
        buf.resetForRead()
        buf.xorMask(0x12345678)
        // Mask byte 0 (MSB) = 0x12
        assertEquals(0x12.toByte(), buf.get(0))
    }

    @Test
    fun xorMaskEmptyBuffer() {
        val buf = BufferFactory.Default.allocate(4) as ReadWriteBuffer
        buf.writeInt(0)
        buf.resetForRead()
        buf.position(buf.limit()) // remaining = 0
        // Should be no-op, no crash
        buf.xorMask(0x12345678)
    }

    @Test
    fun xorMaskNotDivisibleBy4() {
        // 5 bytes — mask cycles: 0,1,2,3,0
        val buf = BufferFactory.Default.allocate(5) as ReadWriteBuffer
        for (i in 0 until 5) buf.writeByte(0x00)
        buf.resetForRead()
        buf.xorMask(0x12345678)

        val maskBytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        for (i in 0 until 5) {
            assertEquals(maskBytes[i % 4], buf.get(i), "Byte at index $i")
        }
    }

    @Test
    fun xorMaskZeroIsNoOp() {
        val buf = BufferFactory.Default.allocate(4) as ReadWriteBuffer
        buf.writeBytes(byteArrayOf(1, 2, 3, 4))
        buf.resetForRead()
        buf.xorMask(0)
        assertEquals(1.toByte(), buf.get(0))
        assertEquals(2.toByte(), buf.get(1))
        assertEquals(3.toByte(), buf.get(2))
        assertEquals(4.toByte(), buf.get(3))
    }

    @Test
    fun xorMaskDoubleApplyRestoresOriginal() {
        val original = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
        val buf = BufferFactory.Default.allocate(5) as ReadWriteBuffer
        buf.writeBytes(original)
        buf.resetForRead()
        val mask = 0xAABBCCDD.toInt()
        buf.xorMask(mask)
        buf.xorMask(mask)
        for (i in original.indices) {
            assertEquals(original[i], buf.get(i), "Byte at index $i after double XOR")
        }
    }

    @Test
    fun xorMaskPreservesPositionAndLimit() {
        val buf = BufferFactory.Default.allocate(8) as ReadWriteBuffer
        for (i in 0 until 8) buf.writeByte(0)
        buf.resetForRead()
        buf.position(2)
        buf.setLimit(6)
        val posBefore = buf.position()
        val limBefore = buf.limit()
        buf.xorMask(0x12345678)
        assertEquals(posBefore, buf.position())
        assertEquals(limBefore, buf.limit())
    }

    // ============================================================================
    // xorMaskCopy Edge Cases
    // ============================================================================

    @Test
    fun xorMaskCopyEmptySource() {
        val src = BufferFactory.Default.allocate(0)
        src.resetForRead()
        val dst = BufferFactory.Default.allocate(4) as ReadWriteBuffer
        val posBefore = dst.position()
        dst.xorMaskCopy(src, 0x12345678)
        assertEquals(posBefore, dst.position()) // No change
    }

    @Test
    fun xorMaskCopyWithZeroMaskIsCopy() {
        val data = byteArrayOf(1, 2, 3, 4)
        val src = BufferFactory.Default.wrap(data)
        val dst = BufferFactory.Default.allocate(4) as ReadWriteBuffer
        dst.xorMaskCopy(src, 0)
        dst.resetForRead()
        for (i in data.indices) {
            assertEquals(data[i], dst.readByte(), "Byte at index $i")
        }
    }

    // ============================================================================
    // Managed vs Direct factory coverage
    // ============================================================================

    @Test
    fun fillByteWorksOnManagedBuffer() {
        val buf = BufferFactory.managed().allocate(7)
        buf.fill(0x42.toByte())
        buf.resetForRead()
        for (i in 0 until 7) {
            assertEquals(0x42.toByte(), buf.readByte())
        }
    }

    @Test
    fun xorMaskWorksOnManagedBuffer() {
        val mask = 0xAABBCCDD.toInt()
        val buf = BufferFactory.managed().allocate(4) as ReadWriteBuffer
        buf.writeBytes(byteArrayOf(0, 0, 0, 0))
        buf.resetForRead()
        buf.xorMask(mask)
        assertEquals((mask ushr 24).toByte(), buf.get(0))
        assertEquals(((mask ushr 16) and 0xFF).toByte(), buf.get(1))
        assertEquals(((mask ushr 8) and 0xFF).toByte(), buf.get(2))
        assertEquals((mask and 0xFF).toByte(), buf.get(3))
    }
}
