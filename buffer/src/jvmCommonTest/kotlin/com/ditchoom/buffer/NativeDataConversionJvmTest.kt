package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeDataConversionJvmTest {
    @Test
    fun toNativeDataReturnsReadOnlyByteBufferAtPositionZero() {
        val source = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val buffer = BufferFactory.managed().allocate(BUFFER_SIZE)
        buffer.writeBytes(source)
        buffer.resetForRead()

        val result = buffer.toNativeData().byteBuffer
        assertTrue(result.isReadOnly)
        assertEquals(0, result.position())
        assertEquals(BUFFER_SIZE, result.remaining())
    }

    @Test
    fun toNativeDataReturnsRemainingBytesFromPosition() {
        val source = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val buffer = BufferFactory.managed().allocate(BUFFER_SIZE)
        buffer.writeBytes(source)
        buffer.resetForRead()
        buffer.readByte() // position = 1
        buffer.readByte() // position = 2

        val result = buffer.toNativeData().byteBuffer
        // Result is a new direct buffer starting at position 0
        assertEquals(0, result.position())
        assertEquals(REMAINING_AFTER_TWO, result.remaining())

        val bytes = ByteArray(REMAINING_AFTER_TWO)
        result.get(bytes)
        val expected = byteArrayOf(3, 4, 5, 6, 7, 8)
        assertContentEquals(expected, bytes)
    }

    @Test
    fun toNativeDataRespectsLimit() {
        val source = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val buffer = BufferFactory.managed().allocate(BUFFER_SIZE)
        buffer.writeBytes(source)
        buffer.resetForRead()
        buffer.setLimit(LIMIT_FIVE)

        val result = buffer.toNativeData().byteBuffer
        assertEquals(LIMIT_FIVE, result.remaining())

        val bytes = ByteArray(LIMIT_FIVE)
        result.get(bytes)
        val expected = byteArrayOf(1, 2, 3, 4, 5)
        assertContentEquals(expected, bytes)
    }

    @Test
    fun toMutableNativeDataReturnsMutableByteBuffer() {
        val source = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val buffer = BufferFactory.managed().allocate(BUFFER_SIZE)
        buffer.writeBytes(source)
        buffer.resetForRead()

        val result = buffer.toMutableNativeData().byteBuffer
        assertEquals(0, result.position())
        assertEquals(BUFFER_SIZE, result.remaining())

        // Verify it's mutable by writing
        result.put(0, MUTATED_BYTE)
        assertEquals(MUTATED_BYTE, result.get(0))
    }

    @Test
    fun toMutableNativeDataRespectsPositionAndLimit() {
        val source = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val buffer = BufferFactory.managed().allocate(BUFFER_SIZE)
        buffer.writeBytes(source)
        buffer.resetForRead()
        buffer.readByte() // position = 1
        buffer.setLimit(LIMIT_SIX)

        val result = buffer.toMutableNativeData().byteBuffer
        // Result is a new direct buffer starting at position 0
        assertEquals(0, result.position())
        assertEquals(LIMIT_FIVE, result.remaining())

        val bytes = ByteArray(LIMIT_FIVE)
        result.get(bytes)
        val expected = byteArrayOf(2, 3, 4, 5, 6)
        assertContentEquals(expected, bytes)
    }

    // region toNativeData always returns direct ByteBuffer

    @Test
    fun toNativeDataFromDirectBufferReturnsDirect() {
        val source = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        val buffer = BufferFactory.Default.allocate(BUFFER_SIZE)
        buffer.writeBytes(source)
        buffer.resetForRead()
        buffer.readByte() // position = 1

        val result = buffer.toNativeData().byteBuffer
        assertTrue(result.isDirect, "toNativeData should always return direct ByteBuffer")
        assertEquals(REMAINING_AFTER_ONE, result.remaining())
    }

    @Test
    fun toNativeDataFromHeapBufferReturnsDirect() {
        val source = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        val buffer = BufferFactory.managed().allocate(BUFFER_SIZE)
        buffer.writeBytes(source)
        buffer.resetForRead()
        buffer.readByte() // position = 1

        val result = buffer.toNativeData().byteBuffer
        assertTrue(result.isDirect, "toNativeData should always return direct ByteBuffer")
        assertEquals(REMAINING_AFTER_ONE, result.remaining())

        val bytes = ByteArray(REMAINING_AFTER_ONE)
        result.get(bytes)
        val expected = byteArrayOf(20, 30, 40, 50, 60, 70, 80)
        assertContentEquals(expected, bytes)
    }

    @Test
    fun toNativeDataFromWrappedByteArrayReturnsDirect() {
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val buffer = BufferFactory.Default.wrap(original)
        buffer.readByte() // position = 1

        val result = buffer.toNativeData().byteBuffer
        assertTrue(result.isDirect, "toNativeData should always return direct ByteBuffer")
        assertEquals(LIMIT_FOUR, result.remaining())

        val bytes = ByteArray(LIMIT_FOUR)
        result.get(bytes)
        val expected = byteArrayOf(2, 3, 4, 5)
        assertContentEquals(expected, bytes)
    }

    @Test
    fun toMutableNativeDataFromDirectBufferReturnsDirect() {
        val source = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        val buffer = BufferFactory.Default.allocate(BUFFER_SIZE)
        buffer.writeBytes(source)
        buffer.resetForRead()

        val result = buffer.toMutableNativeData().byteBuffer
        assertTrue(result.isDirect, "toMutableNativeData should always return direct ByteBuffer")
    }

    @Test
    fun toMutableNativeDataFromHeapBufferReturnsDirect() {
        val source = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        val buffer = BufferFactory.managed().allocate(BUFFER_SIZE)
        buffer.writeBytes(source)
        buffer.resetForRead()

        val result = buffer.toMutableNativeData().byteBuffer
        assertTrue(result.isDirect, "toMutableNativeData should always return direct ByteBuffer")
    }

    // endregion

    // region toByteArray zero-copy vs copy behavior

    @Test
    fun toByteArrayZeroCopyForFullHeapBuffer() {
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val buffer = BufferFactory.Default.wrap(original)

        val result = buffer.toByteArray()
        assertSame(original, result, "Full heap buffer at position 0 should return same array")
    }

    @Test
    fun toByteArrayCopiesWhenPositionNonZero() {
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val buffer = BufferFactory.Default.wrap(original)
        buffer.readByte() // position = 1

        val result = buffer.toByteArray()
        assertNotSame(original, result, "Non-zero position should copy")
        val expected = byteArrayOf(2, 3, 4, 5)
        assertContentEquals(expected, result)
    }

    @Test
    fun toByteArrayCopiesWhenLimitReduced() {
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val buffer = BufferFactory.Default.wrap(original)
        buffer.setLimit(LIMIT_THREE)

        val result = buffer.toByteArray()
        assertNotSame(original, result, "Reduced limit should copy")
        val expected = byteArrayOf(1, 2, 3)
        assertContentEquals(expected, result)
    }

    @Test
    fun toByteArrayAlwaysCopiesForDirectBuffer() {
        val source = byteArrayOf(1, 2, 3, 4, 5)
        val buffer = BufferFactory.Default.allocate(LIMIT_FIVE)
        buffer.writeBytes(source)
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
        val source = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val buffer = BufferFactory.Default.allocate(BUFFER_SIZE)
        buffer.writeBytes(source)
        buffer.resetForRead()

        val nativeData = buffer.toMutableNativeData().byteBuffer
        nativeData.put(0, MUTATED_BYTE)

        // Original buffer should see the change (zero-copy for direct)
        assertEquals(MUTATED_BYTE, buffer.get(0), "Direct buffer toMutableNativeData should share memory")
    }

    @Test
    fun toMutableNativeDataCopiesFromHeapBuffer() {
        val source = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val buffer = BufferFactory.managed().allocate(BUFFER_SIZE)
        buffer.writeBytes(source)
        buffer.resetForRead()

        val nativeData = buffer.toMutableNativeData().byteBuffer
        nativeData.put(0, MUTATED_BYTE)

        // Original buffer should NOT see the change (copy for heap -> direct)
        assertEquals(1.toByte(), buffer.get(0), "Heap buffer toMutableNativeData should copy to direct")
    }

    // endregion

    private companion object {
        private const val BUFFER_SIZE = 8
        private const val LIMIT_THREE = 3
        private const val LIMIT_FOUR = 4
        private const val LIMIT_FIVE = 5
        private const val LIMIT_SIX = 6
        private const val REMAINING_AFTER_ONE = 7
        private const val REMAINING_AFTER_TWO = 6
        private const val MUTATED_BYTE: Byte = 99
    }
}
