package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Linux-specific content validation tests for toNativeData/toMutableNativeData.
 * Reads actual bytes back from NativeBuffer to verify correctness.
 */
class LinuxNativeDataContentTests {
    @Test
    fun toNativeDataPreservesContent() {
        val data = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
        val buf = BufferFactory.Default.allocate(data.size)
        buf.writeBytes(data)
        buf.resetForRead()

        val nativeData = buf.toNativeData()
        val nativeBuffer = nativeData.nativeBuffer
        assertNotNull(nativeBuffer)
        assertEquals(data.size, nativeBuffer.remaining())

        val readBack = nativeBuffer.readByteArray(nativeBuffer.remaining())
        assertContentEquals(data, readBack)
    }

    @Test
    fun toMutableNativeDataPreservesContent() {
        val data = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val buf = BufferFactory.Default.allocate(data.size)
        buf.writeBytes(data)
        buf.resetForRead()

        val mutableData = buf.toMutableNativeData()
        val nativeBuffer = mutableData.nativeBuffer
        assertNotNull(nativeBuffer)
        assertEquals(data.size, nativeBuffer.remaining())

        val readBack = nativeBuffer.readByteArray(nativeBuffer.remaining())
        assertContentEquals(data, readBack)
    }

    @Test
    fun toNativeDataPartialBufferPreservesContent() {
        val buf = BufferFactory.Default.allocate(LEN_8)
        buf.writeInt(INT_FIRST)
        buf.writeInt(INT_SECOND)
        buf.resetForRead()
        buf.position(INT_SIZE) // Skip first int

        val nativeData = buf.toNativeData()
        val nativeBuffer = nativeData.nativeBuffer
        assertEquals(INT_SIZE, nativeBuffer.remaining())
        assertEquals(INT_SECOND, nativeBuffer.readInt())
    }

    @Test
    fun toNativeDataFromManagedBufferPreservesContent() {
        val data = byteArrayOf(10, 20, 30, 40, 50)
        val buf = BufferFactory.managed().allocate(data.size)
        buf.writeBytes(data)
        buf.resetForRead()

        val nativeData = buf.toNativeData()
        val readBack = nativeData.nativeBuffer.readByteArray(nativeData.nativeBuffer.remaining())
        assertContentEquals(data, readBack)
    }

    @Test
    fun toNativeDataHasValidNativeAddress() {
        val buf = BufferFactory.Default.allocate(LEN_16)
        buf.writeInt(INT_42)
        buf.resetForRead()

        val nativeData = buf.toNativeData()
        // NativeBuffer always has a valid native address (malloc'd)
        assertTrue(nativeData.nativeBuffer.nativeAddress != 0L)
    }

    @Test
    fun zeroLengthToNativeData() {
        val buf = BufferFactory.Default.allocate(0)
        buf.resetForRead()
        val nativeData = buf.toNativeData()
        assertEquals(0, nativeData.nativeBuffer.remaining())
    }

    @Test
    fun intRoundTripViaNativeData() {
        val buf = BufferFactory.Default.allocate(INT_SIZE)
        buf.writeInt(INT_DEAD_BEEF)
        buf.resetForRead()

        val nativeData = buf.toNativeData()
        assertEquals(INT_DEAD_BEEF, nativeData.nativeBuffer.readInt())
    }

    @Test
    fun longRoundTripViaNativeData() {
        val buf = BufferFactory.Default.allocate(LEN_8)
        buf.writeLong(LONG_SEQ)
        buf.resetForRead()

        val nativeData = buf.toNativeData()
        assertEquals(LONG_SEQ, nativeData.nativeBuffer.readLong())
    }

    @Test
    fun mixedTypesRoundTripViaNativeData() {
        val buf = BufferFactory.Default.allocate(MIXED_TYPES_SIZE)
        buf.writeByte(BYTE_42.toByte())
        buf.writeShort(SHORT_1234)
        buf.writeInt(INT_DEAD_BEEF)
        buf.writeLong(LONG_INCREMENTING)
        buf.resetForRead()

        val nativeData = buf.toNativeData()
        val nb = nativeData.nativeBuffer
        assertEquals(BYTE_42.toByte(), nb.readByte())
        assertEquals(SHORT_1234, nb.readShort())
        assertEquals(INT_DEAD_BEEF, nb.readInt())
        assertEquals(LONG_INCREMENTING, nb.readLong())
    }

    private companion object {
        private const val INT_SIZE = 4
        private const val LEN_8 = 8
        private const val LEN_16 = 16
        private const val MIXED_TYPES_SIZE = 15
        private const val INT_42 = 42
        private const val BYTE_42 = 0x42
        private const val SHORT_1234: Short = 0x1234
        private const val INT_FIRST = 0x01020304
        private const val INT_SECOND = 0x05060708
        private const val INT_DEAD_BEEF = 0xDEADBEEF.toInt()
        private const val LONG_SEQ = 0x123456789ABCDEF0L
        private const val LONG_INCREMENTING = 0x0102030405060708L
    }
}
