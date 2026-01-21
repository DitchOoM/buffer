package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
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
        // Result is a new direct buffer starting at position 0
        assertEquals(0, result.position())
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
        // Result is a new direct buffer starting at position 0
        assertEquals(0, result.position())
        assertEquals(5, result.remaining())

        val bytes = ByteArray(5)
        result.get(bytes)
        assertContentEquals(byteArrayOf(2, 3, 4, 5, 6), bytes)
    }

    // region toNativeData always returns direct ByteBuffer

    @Test
    fun toNativeDataFromDirectBufferReturnsDirect() {
        val buffer = PlatformBuffer.allocate(8, AllocationZone.Direct)
        buffer.writeBytes(byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80))
        buffer.resetForRead()
        buffer.readByte() // position = 1

        val result = buffer.toNativeData()
        assertTrue(result.isDirect, "toNativeData should always return direct ByteBuffer")
        assertEquals(7, result.remaining())
    }

    @Test
    fun toNativeDataFromHeapBufferReturnsDirect() {
        val buffer = PlatformBuffer.allocate(8, AllocationZone.Heap)
        buffer.writeBytes(byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80))
        buffer.resetForRead()
        buffer.readByte() // position = 1

        val result = buffer.toNativeData()
        assertTrue(result.isDirect, "toNativeData should always return direct ByteBuffer")
        assertEquals(7, result.remaining())

        val bytes = ByteArray(7)
        result.get(bytes)
        assertContentEquals(byteArrayOf(20, 30, 40, 50, 60, 70, 80), bytes)
    }

    @Test
    fun toNativeDataFromWrappedByteArrayReturnsDirect() {
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val buffer = PlatformBuffer.wrap(original)
        buffer.readByte() // position = 1

        val result = buffer.toNativeData()
        assertTrue(result.isDirect, "toNativeData should always return direct ByteBuffer")
        assertEquals(4, result.remaining())

        val bytes = ByteArray(4)
        result.get(bytes)
        assertContentEquals(byteArrayOf(2, 3, 4, 5), bytes)
    }

    @Test
    fun toMutableNativeDataFromDirectBufferReturnsDirect() {
        val buffer = PlatformBuffer.allocate(8, AllocationZone.Direct)
        buffer.writeBytes(byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80))
        buffer.resetForRead()

        val result = buffer.toMutableNativeData()
        assertTrue(result.isDirect, "toMutableNativeData should always return direct ByteBuffer")
    }

    @Test
    fun toMutableNativeDataFromHeapBufferReturnsDirect() {
        val buffer = PlatformBuffer.allocate(8, AllocationZone.Heap)
        buffer.writeBytes(byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80))
        buffer.resetForRead()

        val result = buffer.toMutableNativeData()
        assertTrue(result.isDirect, "toMutableNativeData should always return direct ByteBuffer")
    }

    // endregion

    // region toByteArray zero-copy vs copy behavior

    @Test
    fun toByteArrayZeroCopyForFullHeapBuffer() {
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val buffer = PlatformBuffer.wrap(original)

        val result = buffer.toByteArray()
        assertSame(original, result, "Full heap buffer at position 0 should return same array")
    }

    @Test
    fun toByteArrayCopiesWhenPositionNonZero() {
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val buffer = PlatformBuffer.wrap(original)
        buffer.readByte() // position = 1

        val result = buffer.toByteArray()
        assertNotSame(original, result, "Non-zero position should copy")
        assertContentEquals(byteArrayOf(2, 3, 4, 5), result)
    }

    @Test
    fun toByteArrayCopiesWhenLimitReduced() {
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val buffer = PlatformBuffer.wrap(original)
        buffer.setLimit(3)

        val result = buffer.toByteArray()
        assertNotSame(original, result, "Reduced limit should copy")
        assertContentEquals(byteArrayOf(1, 2, 3), result)
    }

    @Test
    fun toByteArrayAlwaysCopiesForDirectBuffer() {
        val buffer = PlatformBuffer.allocate(5, AllocationZone.Direct)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        buffer.resetForRead()

        val result1 = buffer.toByteArray()
        buffer.position(0)
        val result2 = buffer.toByteArray()

        assertNotSame(result1, result2, "Direct buffer should always copy")
        assertContentEquals(result1, result2)
    }

    // endregion

    // region Zero-copy verification via mutation

    @Test
    fun toMutableNativeDataSharesMemoryWithDirectBuffer() {
        val buffer = PlatformBuffer.allocate(8, AllocationZone.Direct)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()

        val nativeData = buffer.toMutableNativeData()
        nativeData.put(0, 99.toByte())

        // Original buffer should see the change (zero-copy for direct)
        assertEquals(99.toByte(), buffer.get(0), "Direct buffer toMutableNativeData should share memory")
    }

    @Test
    fun toMutableNativeDataCopiesFromHeapBuffer() {
        val buffer = PlatformBuffer.allocate(8, AllocationZone.Heap)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()

        val nativeData = buffer.toMutableNativeData()
        nativeData.put(0, 99.toByte())

        // Original buffer should NOT see the change (copy for heap -> direct)
        assertEquals(1.toByte(), buffer.get(0), "Heap buffer toMutableNativeData should copy to direct")
    }

    // endregion
}
