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
        val buf = BufferFactory.Default.allocate(8)
        buf.writeInt(0x01020304)
        buf.writeInt(0x05060708)
        buf.resetForRead()
        buf.position(4) // Skip first int

        val nativeData = buf.toNativeData()
        val nativeBuffer = nativeData.nativeBuffer
        assertEquals(4, nativeBuffer.remaining())
        assertEquals(0x05060708, nativeBuffer.readInt())
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
        val buf = BufferFactory.Default.allocate(16)
        buf.writeInt(42)
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
        val buf = BufferFactory.Default.allocate(4)
        buf.writeInt(0xDEADBEEF.toInt())
        buf.resetForRead()

        val nativeData = buf.toNativeData()
        assertEquals(0xDEADBEEF.toInt(), nativeData.nativeBuffer.readInt())
    }

    @Test
    fun longRoundTripViaNativeData() {
        val buf = BufferFactory.Default.allocate(8)
        buf.writeLong(0x123456789ABCDEF0L)
        buf.resetForRead()

        val nativeData = buf.toNativeData()
        assertEquals(0x123456789ABCDEF0L, nativeData.nativeBuffer.readLong())
    }

    @Test
    fun mixedTypesRoundTripViaNativeData() {
        val buf = BufferFactory.Default.allocate(15)
        buf.writeByte(0x42)
        buf.writeShort(0x1234.toShort())
        buf.writeInt(0xDEADBEEF.toInt())
        buf.writeLong(0x0102030405060708L)
        buf.resetForRead()

        val nativeData = buf.toNativeData()
        val nb = nativeData.nativeBuffer
        assertEquals(0x42.toByte(), nb.readByte())
        assertEquals(0x1234.toShort(), nb.readShort())
        assertEquals(0xDEADBEEF.toInt(), nb.readInt())
        assertEquals(0x0102030405060708L, nb.readLong())
    }
}
