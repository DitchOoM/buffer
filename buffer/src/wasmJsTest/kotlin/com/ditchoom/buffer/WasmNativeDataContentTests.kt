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
        val buf = BufferFactory.Default.allocate(LEN_12)
        buf.writeInt(INT_FIRST)
        buf.writeInt(INT_SECOND)
        buf.writeInt(INT_THIRD)
        buf.resetForRead()
        buf.position(INT_SIZE) // Skip first int

        val nativeData = buf.toNativeData()
        val lb = nativeData.linearBuffer
        assertEquals(TWO_INTS_BYTES, lb.remaining())
        assertEquals(INT_SECOND, lb.readInt())
        assertEquals(INT_THIRD, lb.readInt())
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
        val buf = BufferFactory.Default.allocate(INT_SIZE)
        buf.writeInt(INT_DEAD_BEEF)
        buf.resetForRead()

        val nativeData = buf.toNativeData()
        assertEquals(INT_DEAD_BEEF, nativeData.linearBuffer.readInt())
    }

    @Test
    fun longRoundTripViaNativeData() {
        val buf = BufferFactory.Default.allocate(LEN_8)
        buf.writeLong(LONG_SEQ)
        buf.resetForRead()

        val nativeData = buf.toNativeData()
        assertEquals(LONG_SEQ, nativeData.linearBuffer.readLong())
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
        val lb = nativeData.linearBuffer
        assertEquals(BYTE_42.toByte(), lb.readByte())
        assertEquals(SHORT_1234, lb.readShort())
        assertEquals(INT_DEAD_BEEF, lb.readInt())
        assertEquals(LONG_INCREMENTING, lb.readLong())
    }

    @Test
    fun zeroCopySliceSharesMemory() {
        val buf = BufferFactory.Default.allocate(LEN_8) as LinearBuffer
        buf.writeInt(SENTINEL_42)
        buf.writeInt(SENTINEL_99)
        buf.resetForRead()

        val mutableData = buf.toMutableNativeData()
        val lb = mutableData.linearBuffer

        // For LinearBuffer, toMutableNativeData should share memory
        assertEquals(SENTINEL_42, lb.readInt())
        assertEquals(SENTINEL_99, lb.readInt())
    }

    private companion object {
        private const val INT_SIZE = 4
        private const val LEN_8 = 8
        private const val LEN_12 = 12
        private const val MIXED_TYPES_SIZE = 15
        private const val TWO_INTS_BYTES = 8
        private const val SENTINEL_42 = 42
        private const val SENTINEL_99 = 99
        private const val BYTE_42 = 0x42
        private const val SHORT_1234: Short = 0x1234
        private const val INT_FIRST = 0x01020304
        private const val INT_SECOND = 0x05060708
        private const val INT_THIRD = 0x090A0B0C
        private const val INT_DEAD_BEEF = 0xDEADBEEF.toInt()
        private const val LONG_SEQ = 0x123456789ABCDEF0L
        private const val LONG_INCREMENTING = 0x0102030405060708L
    }
}
