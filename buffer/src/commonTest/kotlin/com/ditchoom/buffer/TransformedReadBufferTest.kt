package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals

class TransformedReadBufferTest {
    // XOR transformer for testing - simulates simple encryption/decryption
    private val xorTransformer: (Int, Byte) -> Byte = { _, byte -> (byte.toInt() xor 0xFF).toByte() }

    @Test
    fun byte() {
        val buffer = PlatformBuffer.allocate(Byte.SIZE_BYTES)
        buffer.writeByte(10.toByte())
        buffer.resetForRead()
        val add1TransformedReadBuffer =
            TransformedReadBuffer(buffer) { _, byte ->
                (byte + 1).toByte()
            }
        assertEquals(11.toByte(), add1TransformedReadBuffer.readByte())
    }

    @Test
    fun getByte() {
        val buffer = PlatformBuffer.allocate(5)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        buffer.resetForRead()

        val transformed = TransformedReadBuffer(buffer, xorTransformer)
        assertEquals((3 xor 0xFF).toByte(), transformed[2])
        assertEquals(0, transformed.position()) // Position unchanged
    }

    @Test
    fun readShort() {
        val buffer = PlatformBuffer.allocate(Short.SIZE_BYTES)
        buffer.writeShort(0x1234)
        buffer.resetForRead()

        val transformed = TransformedReadBuffer(buffer, xorTransformer)
        val result = transformed.readShort()
        // Each byte is XORed with 0xFF
        assertEquals(0xEDCB.toShort(), result)
    }

    @Test
    fun getShort() {
        val buffer = PlatformBuffer.allocate(4)
        buffer.writeShort(0x0000)
        buffer.writeShort(0x1234)
        buffer.resetForRead()

        val transformed = TransformedReadBuffer(buffer, xorTransformer)
        val result = transformed.getShort(2)
        assertEquals(0xEDCB.toShort(), result)
    }

    @Test
    fun readInt() {
        val buffer = PlatformBuffer.allocate(Int.SIZE_BYTES)
        buffer.writeInt(0x12345678)
        buffer.resetForRead()

        val transformed = TransformedReadBuffer(buffer, xorTransformer)
        val result = transformed.readInt()
        // Each byte XORed with 0xFF: 0x12->0xED, 0x34->0xCB, 0x56->0xA9, 0x78->0x87
        assertEquals(0xEDCBA987.toInt(), result)
    }

    @Test
    fun getInt() {
        val buffer = PlatformBuffer.allocate(8)
        buffer.writeInt(0)
        buffer.writeInt(0x12345678)
        buffer.resetForRead()

        val transformed = TransformedReadBuffer(buffer, xorTransformer)
        val result = transformed.getInt(4)
        assertEquals(0xEDCBA987.toInt(), result)
    }

    @Test
    fun readLong() {
        val buffer = PlatformBuffer.allocate(Long.SIZE_BYTES)
        // Use a simple value where we can easily verify the XOR result
        buffer.writeLong(0x0000000000000000L)
        buffer.resetForRead()

        val transformed = TransformedReadBuffer(buffer, xorTransformer)
        val result = transformed.readLong()
        // Each byte 0x00 XORed with 0xFF becomes 0xFF
        assertEquals(-1L, result) // 0xFFFFFFFFFFFFFFFF
    }

    @Test
    fun getLong() {
        val buffer = PlatformBuffer.allocate(16)
        buffer.writeLong(0L)
        buffer.writeLong(0x0000000000000000L)
        buffer.resetForRead()

        val transformed = TransformedReadBuffer(buffer, xorTransformer)
        val result = transformed.getLong(8)
        assertEquals(-1L, result) // 0xFFFFFFFFFFFFFFFF
    }

    @Test
    fun readByteArray() {
        val buffer = PlatformBuffer.allocate(4)
        buffer.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        buffer.resetForRead()

        val transformed = TransformedReadBuffer(buffer, xorTransformer)
        val result = transformed.readByteArray(4)

        assertEquals(0xFE.toByte(), result[0])
        assertEquals(0xFD.toByte(), result[1])
        assertEquals(0xFC.toByte(), result[2])
        assertEquals(0xFB.toByte(), result[3])
    }

    @Test
    fun readString() {
        val buffer = PlatformBuffer.allocate(10)
        // Write "Hello" with identity transformer (no transformation)
        buffer.writeString("Hello")
        buffer.resetForRead()

        val identityTransformer: (Int, Byte) -> Byte = { _, byte -> byte }
        val transformed = TransformedReadBuffer(buffer, identityTransformer)
        assertEquals("Hello", transformed.readString(5))
    }

    @Test
    fun positionAndLimit() {
        val buffer = PlatformBuffer.allocate(10)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        buffer.resetForRead()

        val transformed = TransformedReadBuffer(buffer, xorTransformer)

        assertEquals(0, transformed.position())
        assertEquals(5, transformed.limit())

        transformed.position(2)
        assertEquals(2, transformed.position())

        transformed.setLimit(4)
        assertEquals(4, transformed.limit())
    }

    @Test
    fun resetForRead() {
        val buffer = PlatformBuffer.allocate(10)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        val transformed = TransformedReadBuffer(buffer, xorTransformer)
        transformed.resetForRead()

        assertEquals(0, transformed.position())
        assertEquals(5, transformed.limit())
    }

    @Test
    fun slice() {
        val buffer = PlatformBuffer.allocate(10)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        buffer.resetForRead()
        buffer.readByte() // advance to position 1

        val transformed = TransformedReadBuffer(buffer, xorTransformer)
        val sliced = transformed.slice()

        // slice() delegates to origin, not transformed
        assertEquals(4, sliced.remaining())
    }

    @Test
    fun byteOrderPreserved() {
        val buffer = PlatformBuffer.allocate(10, byteOrder = ByteOrder.LITTLE_ENDIAN)
        buffer.writeInt(0x12345678)
        buffer.resetForRead()

        val transformed = TransformedReadBuffer(buffer, xorTransformer)
        assertEquals(ByteOrder.LITTLE_ENDIAN, transformed.byteOrder)
    }
}
