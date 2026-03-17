package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * WASM-specific content validation tests for toNativeData/toMutableNativeData.
 * Reads actual bytes back from LinearBuffer to verify correctness.
 * Complements WasmPlatformInteropDocTests which only checks remaining().
 */
class WasmNativeDataContentTests {
    @Test
    fun toNativeDataPreservesContent() {
        val data = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
        val buf = BufferFactory.Default.allocate(data.size)
        buf.writeBytes(data)
        buf.resetForRead()

        val nativeData = buf.toNativeData()
        val linearBuffer = nativeData.linearBuffer
        assertNotNull(linearBuffer)

        val readBack = linearBuffer.readByteArray(linearBuffer.remaining())
        assertContentEquals(data, readBack)
    }

    @Test
    fun toMutableNativeDataPreservesContent() {
        val data = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val buf = BufferFactory.Default.allocate(data.size)
        buf.writeBytes(data)
        buf.resetForRead()

        val mutableData = buf.toMutableNativeData()
        val linearBuffer = mutableData.linearBuffer
        assertNotNull(linearBuffer)

        val readBack = linearBuffer.readByteArray(linearBuffer.remaining())
        assertContentEquals(data, readBack)
    }

    @Test
    fun toNativeDataPartialBufferPreservesContent() {
        val buf = BufferFactory.Default.allocate(12)
        buf.writeInt(0x01020304)
        buf.writeInt(0x05060708)
        buf.writeInt(0x090A0B0C)
        buf.resetForRead()
        buf.position(4) // Skip first int

        val nativeData = buf.toNativeData()
        val lb = nativeData.linearBuffer
        assertEquals(8, lb.remaining())
        assertEquals(0x05060708, lb.readInt())
        assertEquals(0x090A0B0C, lb.readInt())
    }

    @Test
    fun toNativeDataFromManagedBufferPreservesContent() {
        val data = byteArrayOf(10, 20, 30, 40, 50)
        val buf = BufferFactory.managed().allocate(data.size)
        buf.writeBytes(data)
        buf.resetForRead()

        val nativeData = buf.toNativeData()
        val readBack = nativeData.linearBuffer.readByteArray(nativeData.linearBuffer.remaining())
        assertContentEquals(data, readBack)
    }

    @Test
    fun zeroLengthToNativeData() {
        val buf = BufferFactory.Default.allocate(0)
        buf.resetForRead()
        val nativeData = buf.toNativeData()
        assertEquals(0, nativeData.linearBuffer.remaining())
    }

    @Test
    fun intRoundTripViaNativeData() {
        val buf = BufferFactory.Default.allocate(4)
        buf.writeInt(0xDEADBEEF.toInt())
        buf.resetForRead()

        val nativeData = buf.toNativeData()
        assertEquals(0xDEADBEEF.toInt(), nativeData.linearBuffer.readInt())
    }

    @Test
    fun longRoundTripViaNativeData() {
        val buf = BufferFactory.Default.allocate(8)
        buf.writeLong(0x123456789ABCDEF0L)
        buf.resetForRead()

        val nativeData = buf.toNativeData()
        assertEquals(0x123456789ABCDEF0L, nativeData.linearBuffer.readLong())
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
        val lb = nativeData.linearBuffer
        assertEquals(0x42.toByte(), lb.readByte())
        assertEquals(0x1234.toShort(), lb.readShort())
        assertEquals(0xDEADBEEF.toInt(), lb.readInt())
        assertEquals(0x0102030405060708L, lb.readLong())
    }

    @Test
    fun zeroCopySliceSharesMemory() {
        val buf = BufferFactory.Default.allocate(8) as LinearBuffer
        buf.writeInt(42)
        buf.writeInt(99)
        buf.resetForRead()

        val mutableData = buf.toMutableNativeData()
        val lb = mutableData.linearBuffer

        // For LinearBuffer, toMutableNativeData should share memory
        assertEquals(42, lb.readInt())
        assertEquals(99, lb.readInt())
    }
}
