package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeDataConversionJvmTest {
    @Test
    fun toNativeDataReturnsReadOnlyByteBufferAtPositionZero() {
        val buffer = PlatformBuffer.allocate(8)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()

        val result = buffer.toNativeData()
        assertTrue(result.isReadOnly)
        assertEquals(0, result.position())
        assertEquals(8, result.remaining())
    }

    @Test
    fun toNativeDataReturnsRemainingBytesFromPosition() {
        val buffer = PlatformBuffer.allocate(8)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()
        buffer.readByte() // position = 1
        buffer.readByte() // position = 2

        val result = buffer.toNativeData()
        assertEquals(2, result.position())
        assertEquals(6, result.remaining())

        val bytes = ByteArray(6)
        result.get(bytes)
        assertContentEquals(byteArrayOf(3, 4, 5, 6, 7, 8), bytes)
    }

    @Test
    fun toNativeDataRespectsLimit() {
        val buffer = PlatformBuffer.allocate(8)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()
        buffer.setLimit(5)

        val result = buffer.toNativeData()
        assertEquals(5, result.remaining())

        val bytes = ByteArray(5)
        result.get(bytes)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), bytes)
    }

    @Test
    fun toMutableNativeDataReturnsMutableByteBuffer() {
        val buffer = PlatformBuffer.allocate(8)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()

        val result = buffer.toMutableNativeData()
        assertEquals(0, result.position())
        assertEquals(8, result.remaining())

        // Verify it's mutable by writing
        result.put(0, 99.toByte())
        assertEquals(99.toByte(), result.get(0))
    }

    @Test
    fun toMutableNativeDataRespectsPositionAndLimit() {
        val buffer = PlatformBuffer.allocate(8)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()
        buffer.readByte() // position = 1
        buffer.setLimit(6)

        val result = buffer.toMutableNativeData()
        assertEquals(1, result.position())
        assertEquals(5, result.remaining())
    }

    @Test
    fun toNativeDataWorksWithDirectBuffer() {
        val buffer = PlatformBuffer.allocate(8, AllocationZone.Direct)
        buffer.writeBytes(byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80))
        buffer.resetForRead()
        buffer.readByte() // position = 1

        val result = buffer.toNativeData()
        assertTrue(result.isDirect)
        assertEquals(7, result.remaining())
    }

    @Test
    fun toNativeDataWorksWithHeapBuffer() {
        val buffer = PlatformBuffer.allocate(8, AllocationZone.Heap)
        buffer.writeBytes(byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80))
        buffer.resetForRead()
        buffer.readByte() // position = 1

        val result = buffer.toNativeData()
        assertEquals(7, result.remaining())
    }

    @Test
    fun toNativeDataWrappedByteArray() {
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val buffer = PlatformBuffer.wrap(original)
        buffer.readByte() // position = 1

        val result = buffer.toNativeData()
        assertEquals(4, result.remaining())

        val bytes = ByteArray(4)
        result.get(bytes)
        assertContentEquals(byteArrayOf(2, 3, 4, 5), bytes)
    }
}
