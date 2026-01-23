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

    // region Position/Limit Consistency Tests

    @Test
    fun toByteArrayDoesNotModifyPositionForHeapBuffer() {
        val buffer = PlatformBuffer.allocate(8, AllocationZone.Heap)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()
        buffer.readByte() // position = 1
        buffer.readByte() // position = 2
        buffer.setLimit(6) // limit = 6

        val positionBefore = buffer.position()
        val limitBefore = buffer.limit()

        buffer.toByteArray()

        assertEquals(positionBefore, buffer.position(), "toByteArray should not modify position")
        assertEquals(limitBefore, buffer.limit(), "toByteArray should not modify limit")
    }

    @Test
    fun toByteArrayDoesNotModifyPositionForDirectBuffer() {
        val buffer = PlatformBuffer.allocate(8, AllocationZone.Direct)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()
        buffer.readByte() // position = 1
        buffer.readByte() // position = 2
        buffer.setLimit(6) // limit = 6

        val positionBefore = buffer.position()
        val limitBefore = buffer.limit()

        buffer.toByteArray()

        assertEquals(positionBefore, buffer.position(), "toByteArray should not modify position")
        assertEquals(limitBefore, buffer.limit(), "toByteArray should not modify limit")
    }

    @Test
    fun toByteArrayDoesNotModifyPositionForWrappedBuffer() {
        val buffer = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.readByte() // position = 1
        buffer.readByte() // position = 2
        buffer.setLimit(6) // limit = 6

        val positionBefore = buffer.position()
        val limitBefore = buffer.limit()

        buffer.toByteArray()

        assertEquals(positionBefore, buffer.position(), "toByteArray should not modify position")
        assertEquals(limitBefore, buffer.limit(), "toByteArray should not modify limit")
    }

    @Test
    fun toNativeDataDoesNotModifyPositionForHeapBuffer() {
        val buffer = PlatformBuffer.allocate(8, AllocationZone.Heap)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()
        buffer.readByte() // position = 1
        buffer.readByte() // position = 2
        buffer.setLimit(6) // limit = 6

        val positionBefore = buffer.position()
        val limitBefore = buffer.limit()

        buffer.toNativeData()

        assertEquals(positionBefore, buffer.position(), "toNativeData should not modify position")
        assertEquals(limitBefore, buffer.limit(), "toNativeData should not modify limit")
    }

    @Test
    fun toNativeDataDoesNotModifyPositionForDirectBuffer() {
        val buffer = PlatformBuffer.allocate(8, AllocationZone.Direct)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()
        buffer.readByte() // position = 1
        buffer.readByte() // position = 2
        buffer.setLimit(6) // limit = 6

        val positionBefore = buffer.position()
        val limitBefore = buffer.limit()

        buffer.toNativeData()

        assertEquals(positionBefore, buffer.position(), "toNativeData should not modify position")
        assertEquals(limitBefore, buffer.limit(), "toNativeData should not modify limit")
    }

    @Test
    fun toNativeDataDoesNotModifyPositionForWrappedBuffer() {
        val buffer = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.readByte() // position = 1
        buffer.readByte() // position = 2
        buffer.setLimit(6) // limit = 6

        val positionBefore = buffer.position()
        val limitBefore = buffer.limit()

        buffer.toNativeData()

        assertEquals(positionBefore, buffer.position(), "toNativeData should not modify position")
        assertEquals(limitBefore, buffer.limit(), "toNativeData should not modify limit")
    }

    @Test
    fun toMutableNativeDataDoesNotModifyPositionForHeapBuffer() {
        val buffer = PlatformBuffer.allocate(8, AllocationZone.Heap)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()
        buffer.readByte() // position = 1
        buffer.readByte() // position = 2
        buffer.setLimit(6) // limit = 6

        val positionBefore = buffer.position()
        val limitBefore = buffer.limit()

        buffer.toMutableNativeData()

        assertEquals(positionBefore, buffer.position(), "toMutableNativeData should not modify position")
        assertEquals(limitBefore, buffer.limit(), "toMutableNativeData should not modify limit")
    }

    @Test
    fun toMutableNativeDataDoesNotModifyPositionForDirectBuffer() {
        val buffer = PlatformBuffer.allocate(8, AllocationZone.Direct)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()
        buffer.readByte() // position = 1
        buffer.readByte() // position = 2
        buffer.setLimit(6) // limit = 6

        val positionBefore = buffer.position()
        val limitBefore = buffer.limit()

        buffer.toMutableNativeData()

        assertEquals(positionBefore, buffer.position(), "toMutableNativeData should not modify position")
        assertEquals(limitBefore, buffer.limit(), "toMutableNativeData should not modify limit")
    }

    @Test
    fun toMutableNativeDataDoesNotModifyPositionForWrappedBuffer() {
        val buffer = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.readByte() // position = 1
        buffer.readByte() // position = 2
        buffer.setLimit(6) // limit = 6

        val positionBefore = buffer.position()
        val limitBefore = buffer.limit()

        buffer.toMutableNativeData()

        assertEquals(positionBefore, buffer.position(), "toMutableNativeData should not modify position")
        assertEquals(limitBefore, buffer.limit(), "toMutableNativeData should not modify limit")
    }

    @Test
    fun multipleCallsToByteArrayReturnSameContent() {
        val buffer = PlatformBuffer.allocate(8, AllocationZone.Direct)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()
        buffer.readByte() // position = 1
        buffer.readByte() // position = 2

        val result1 = buffer.toByteArray()
        val result2 = buffer.toByteArray()
        val result3 = buffer.toByteArray()

        assertContentEquals(result1, result2, "Multiple toByteArray calls should return same content")
        assertContentEquals(result2, result3, "Multiple toByteArray calls should return same content")
        assertContentEquals(byteArrayOf(3, 4, 5, 6, 7, 8), result1)
    }

    @Test
    fun multipleCallsToNativeDataReturnSameContent() {
        val buffer = PlatformBuffer.allocate(8, AllocationZone.Direct)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()
        buffer.readByte() // position = 1
        buffer.readByte() // position = 2

        // Call multiple times - position should remain unchanged
        buffer.toNativeData()
        buffer.toNativeData()
        val positionAfter = buffer.position()

        assertEquals(2, positionAfter, "Multiple toNativeData calls should not modify position")
    }

    // endregion
}
