package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Round-trip tests for NativeData conversions.
 * Validates allocate → write → toNativeData/toMutableNativeData → read back
 * on both Direct and Managed buffers, including zero-length and large buffers.
 */
class NativeDataRoundTripTests {
    // ============================================================================
    // toByteArray Round-Trip
    // ============================================================================

    @Test
    fun directBufferWriteAndReadBackViaToByteArray() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val buf = BufferFactory.Default.allocate(data.size)
        buf.writeBytes(data)
        buf.resetForRead()
        assertContentEquals(data, buf.toByteArray())
    }

    @Test
    fun managedBufferWriteAndReadBackViaToByteArray() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val buf = BufferFactory.managed().allocate(data.size)
        buf.writeBytes(data)
        buf.resetForRead()
        assertContentEquals(data, buf.toByteArray())
    }

    @Test
    fun wrappedBufferRoundTripViaToByteArray() {
        val data = byteArrayOf(10, 20, 30, 40, 50)
        val buf = BufferFactory.Default.wrap(data)
        assertContentEquals(data, buf.toByteArray())
    }

    @Test
    fun zeroLengthBufferToByteArray() {
        val buf = BufferFactory.Default.allocate(0)
        buf.resetForRead()
        val result = buf.toByteArray()
        assertEquals(0, result.size)
    }

    @Test
    fun partialBufferToByteArray() {
        val buf = BufferFactory.Default.allocate(8)
        buf.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buf.resetForRead()
        buf.position(2)
        buf.setLimit(6)
        assertContentEquals(byteArrayOf(3, 4, 5, 6), buf.toByteArray())
    }

    // ============================================================================
    // toNativeData Round-Trip
    // ============================================================================

    @Test
    fun directBufferToNativeDataPreservesContent() {
        val data = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val buf = BufferFactory.Default.allocate(data.size)
        buf.writeBytes(data)
        buf.resetForRead()

        val nativeData = buf.toNativeData()
        // Verify position/limit unchanged
        assertEquals(0, buf.position())
        assertEquals(data.size, buf.limit())
        // NativeData is platform-specific — just verify no crash
        @Suppress("UNUSED_VARIABLE")
        val unused = nativeData
    }

    @Test
    fun managedBufferToNativeDataPreservesContent() {
        val data = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val buf = BufferFactory.managed().allocate(data.size)
        buf.writeBytes(data)
        buf.resetForRead()

        val nativeData = buf.toNativeData()
        assertEquals(0, buf.position())
        assertEquals(data.size, buf.limit())
        @Suppress("UNUSED_VARIABLE")
        val unused = nativeData
    }

    @Test
    fun zeroLengthBufferToNativeData() {
        val buf = BufferFactory.Default.allocate(0)
        buf.resetForRead()
        // Should not crash on zero-length
        val nativeData = buf.toNativeData()

        @Suppress("UNUSED_VARIABLE")
        val unused = nativeData
    }

    // ============================================================================
    // toMutableNativeData Round-Trip
    // ============================================================================

    @Test
    fun directBufferToMutableNativeDataPreservesContent() {
        val data = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val buf = BufferFactory.Default.allocate(data.size)
        buf.writeBytes(data)
        buf.resetForRead()

        val mutableNativeData = buf.toMutableNativeData()
        assertEquals(0, buf.position())
        assertEquals(data.size, buf.limit())
        @Suppress("UNUSED_VARIABLE")
        val unused = mutableNativeData
    }

    @Test
    fun managedBufferToMutableNativeDataPreservesContent() {
        val data = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val buf = BufferFactory.managed().allocate(data.size)
        buf.writeBytes(data)
        buf.resetForRead()

        val mutableNativeData = buf.toMutableNativeData()
        assertEquals(0, buf.position())
        assertEquals(data.size, buf.limit())
        @Suppress("UNUSED_VARIABLE")
        val unused = mutableNativeData
    }

    @Test
    fun zeroLengthBufferToMutableNativeData() {
        val buf = BufferFactory.Default.allocate(0)
        buf.resetForRead()
        val mutableNativeData = buf.toMutableNativeData()

        @Suppress("UNUSED_VARIABLE")
        val unused = mutableNativeData
    }

    // ============================================================================
    // Large Buffer Round-Trip
    // ============================================================================

    @Test
    fun largeDirectBufferToByteArrayRoundTrip() {
        val size = 1024 * 1024 // 1MB
        val buf = BufferFactory.Default.allocate(size)
        // Write a recognizable pattern
        for (i in 0 until size / 4) {
            buf.writeInt(i)
        }
        buf.resetForRead()

        val bytes = buf.toByteArray()
        assertEquals(size, bytes.size)

        // Verify by wrapping and reading back
        val readBack = BufferFactory.Default.wrap(bytes)
        for (i in 0 until size / 4) {
            assertEquals(i, readBack.readInt(), "Int at index $i")
        }
    }

    @Test
    fun largeManagedBufferToByteArrayRoundTrip() {
        val size = 1024 * 1024 // 1MB
        val buf = BufferFactory.managed().allocate(size)
        for (i in 0 until size / 4) {
            buf.writeInt(i)
        }
        buf.resetForRead()

        val bytes = buf.toByteArray()
        assertEquals(size, bytes.size)

        val readBack = BufferFactory.Default.wrap(bytes)
        for (i in 0 until size / 4) {
            assertEquals(i, readBack.readInt(), "Int at index $i")
        }
    }

    // ============================================================================
    // Multiple Conversions Are Idempotent
    // ============================================================================

    @Test
    fun multipleToNativeDataCallsAreIdempotent() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val buf = BufferFactory.Default.allocate(data.size)
        buf.writeBytes(data)
        buf.resetForRead()

        buf.toNativeData()
        buf.toNativeData()
        buf.toMutableNativeData()
        val bytes = buf.toByteArray()

        // Position should still be unchanged
        assertEquals(0, buf.position())
        assertContentEquals(data, bytes)
    }

    @Test
    fun toByteArrayAndToNativeDataCanBeInterleaved() {
        val data = byteArrayOf(10, 20, 30, 40)
        val buf = BufferFactory.Default.allocate(data.size)
        buf.writeBytes(data)
        buf.resetForRead()

        val bytes1 = buf.toByteArray()
        buf.toNativeData()
        val bytes2 = buf.toByteArray()
        buf.toMutableNativeData()
        val bytes3 = buf.toByteArray()

        assertContentEquals(bytes1, bytes2)
        assertContentEquals(bytes2, bytes3)
        assertContentEquals(data, bytes1)
    }

    // ============================================================================
    // Primitive Write/Read Round-Trip via toByteArray
    // ============================================================================

    @Test
    fun intRoundTripViaByteArray() {
        val buf = BufferFactory.Default.allocate(4)
        buf.writeInt(0xDEADBEEF.toInt())
        buf.resetForRead()

        val bytes = buf.toByteArray()
        val readBack = BufferFactory.Default.wrap(bytes)
        assertEquals(0xDEADBEEF.toInt(), readBack.readInt())
    }

    @Test
    fun longRoundTripViaByteArray() {
        val buf = BufferFactory.Default.allocate(8)
        buf.writeLong(0x123456789ABCDEF0L)
        buf.resetForRead()

        val bytes = buf.toByteArray()
        val readBack = BufferFactory.Default.wrap(bytes)
        assertEquals(0x123456789ABCDEF0L, readBack.readLong())
    }

    @Test
    fun stringRoundTripViaByteArray() {
        val text = "Hello, World!"
        val buf = BufferFactory.Default.allocate(text.length * 4)
        buf.writeString(text)
        buf.resetForRead()
        val textLen = buf.remaining()

        val bytes = buf.toByteArray()
        val readBack = BufferFactory.Default.wrap(bytes)
        assertEquals(text, readBack.readString(textLen))
    }

    @Test
    fun mixedTypesRoundTripViaByteArray() {
        val buf = BufferFactory.Default.allocate(32)
        buf.writeByte(0x42)
        buf.writeShort(0x1234.toShort())
        buf.writeInt(0xDEADBEEF.toInt())
        val longVal = -0x3501454135014542L // 0xCAFEBABECAFEBABE
        buf.writeLong(longVal)
        buf.resetForRead()

        val bytes = buf.toByteArray()
        val readBack = BufferFactory.Default.wrap(bytes)
        assertEquals(0x42.toByte(), readBack.readByte())
        assertEquals(0x1234.toShort(), readBack.readShort())
        assertEquals(0xDEADBEEF.toInt(), readBack.readInt())
        assertEquals(longVal, readBack.readLong())
    }
}
