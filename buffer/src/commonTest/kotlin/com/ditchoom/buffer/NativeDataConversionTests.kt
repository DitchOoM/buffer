package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class NativeDataConversionTests {
    @Test
    fun toByteArrayReturnsFullBufferAtPositionZero() {
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val buffer = PlatformBuffer.wrap(original)
        val result = buffer.toByteArray()
        assertContentEquals(original, result)
    }

    @Test
    fun toByteArrayReturnsRemainingBytesFromPosition() {
        val buffer = PlatformBuffer.allocate(5)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        buffer.resetForRead()
        buffer.readByte() // position = 1
        buffer.readByte() // position = 2

        val result = buffer.toByteArray()
        assertContentEquals(byteArrayOf(3, 4, 5), result)
    }

    @Test
    fun toByteArrayRespectsLimit() {
        val buffer = PlatformBuffer.allocate(5)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        buffer.resetForRead()
        buffer.setLimit(3)

        val result = buffer.toByteArray()
        assertContentEquals(byteArrayOf(1, 2, 3), result)
    }

    @Test
    fun toByteArrayRespectsPositionAndLimit() {
        val buffer = PlatformBuffer.allocate(5)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        buffer.resetForRead()
        buffer.readByte() // position = 1
        buffer.setLimit(4) // limit = 4

        val result = buffer.toByteArray()
        assertContentEquals(byteArrayOf(2, 3, 4), result)
    }

    @Test
    fun toByteArrayReturnsEmptyForEmptyRemaining() {
        val buffer = PlatformBuffer.allocate(5)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        buffer.resetForRead()
        buffer.position(5) // position = limit

        val result = buffer.toByteArray()
        assertEquals(0, result.size)
    }

    @Test
    fun toByteArrayWorksWithDirectBuffer() {
        val buffer = PlatformBuffer.allocate(5, AllocationZone.Direct)
        buffer.writeBytes(byteArrayOf(10, 20, 30, 40, 50))
        buffer.resetForRead()
        buffer.readByte() // position = 1

        val result = buffer.toByteArray()
        assertContentEquals(byteArrayOf(20, 30, 40, 50), result)
    }

    @Test
    fun toByteArrayWorksWithHeapBuffer() {
        val buffer = PlatformBuffer.allocate(5, AllocationZone.Heap)
        buffer.writeBytes(byteArrayOf(10, 20, 30, 40, 50))
        buffer.resetForRead()
        buffer.readByte() // position = 1

        val result = buffer.toByteArray()
        assertContentEquals(byteArrayOf(20, 30, 40, 50), result)
    }
}
