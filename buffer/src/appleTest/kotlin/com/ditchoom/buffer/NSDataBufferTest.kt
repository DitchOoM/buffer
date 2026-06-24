package com.ditchoom.buffer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.Foundation.create
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.UnsafeNumber::class)
class NSDataBufferTest {
    @Test
    fun wrapReadOnlyDoesNotCopy() {
        // Create an NSMutableData with some content
        val mutableData = NSMutableData.create(length = LEN_16.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        writeBuffer.writeInt(INT_A)
        writeBuffer.writeInt(INT_DEAD_BEEF)
        writeBuffer.resetForRead()

        // Get immutable NSData reference
        val nsData: NSData = mutableData

        // Wrap as read-only
        val readOnlyBuffer = PlatformBuffer.wrapReadOnly(nsData)

        // Verify content is accessible
        assertEquals(INT_A, readOnlyBuffer.readInt())
        assertEquals(INT_DEAD_BEEF, readOnlyBuffer.readInt())

        // Verify it's the same underlying memory (nativeAddress matches)
        assertTrue(readOnlyBuffer is NativeMemoryAccess)
        assertEquals(writeBuffer.nativeAddress, (readOnlyBuffer as NativeMemoryAccess).nativeAddress)
    }

    @Test
    fun readOnlyBufferOperations() {
        val mutableData = NSMutableData.create(length = LEN_32.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        writeBuffer.writeByte(BYTE_ONE.toByte())
        writeBuffer.writeShort(SHORT_0203)
        writeBuffer.writeInt(INT_04050607)
        writeBuffer.writeLong(LONG_SEQ)
        writeBuffer.writeString("Hi")
        writeBuffer.resetForRead()

        val readBuffer = PlatformBuffer.wrapReadOnly(mutableData as NSData, ByteOrder.BIG_ENDIAN)

        // Relative reads
        assertEquals(BYTE_ONE.toByte(), readBuffer.readByte())
        assertEquals(SHORT_0203, readBuffer.readShort())
        assertEquals(INT_04050607, readBuffer.readInt())
        assertEquals(LONG_SEQ, readBuffer.readLong())
        assertEquals("Hi", readBuffer.readString(TWO))

        // Absolute reads (reset position first)
        readBuffer.position(0)
        assertEquals(BYTE_ONE.toByte(), readBuffer.get(0))
        assertEquals(SHORT_0203, readBuffer.getShort(1))
        assertEquals(INT_04050607, readBuffer.getInt(THREE))
        assertEquals(LONG_SEQ, readBuffer.getLong(SEVEN))
    }

    @Test
    fun sliceIsZeroCopy() {
        val mutableData = NSMutableData.create(length = LEN_16.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        for (i in 0 until LEN_16) {
            writeBuffer.writeByte(i.toByte())
        }

        val readBuffer = PlatformBuffer.wrapReadOnly(mutableData as NSData)

        // Position at byte 4, limit at byte 12
        readBuffer.position(FOUR)
        readBuffer.setLimit(TWELVE)

        // Slice should create a zero-copy view
        val slice = readBuffer.slice()

        // Slice starts at position 0, has remaining = 8
        assertEquals(0, slice.position())
        assertEquals(EIGHT, slice.remaining())

        // First byte in slice should be byte 4 from original
        assertEquals(FOUR.toByte(), slice.readByte())
        assertEquals(FIVE.toByte(), slice.readByte())
    }

    @Test
    fun indexOfWorksCorrectly() {
        val mutableData = NSMutableData.create(length = LEN_16.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        for (i in 0 until LEN_16) {
            writeBuffer.writeByte(i.toByte())
        }

        val readBuffer = PlatformBuffer.wrapReadOnly(mutableData as NSData)

        // Find existing byte
        assertEquals(FIVE, readBuffer.indexOf(FIVE.toByte()))

        // Find non-existing byte
        assertEquals(-1, readBuffer.indexOf(MISSING_BYTE.toByte()))

        // Find first byte
        assertEquals(0, readBuffer.indexOf(0.toByte()))
    }

    @Test
    fun contentEqualsWithDifferentBufferTypes() {
        val data1 = NSMutableData.create(length = LEN_8.convert())!!
        val writeBuffer1 = PlatformBuffer.wrap(data1)
        writeBuffer1.writeInt(INT_42)
        writeBuffer1.writeInt(INT_100)

        val data2 = NSMutableData.create(length = LEN_8.convert())!!
        val writeBuffer2 = PlatformBuffer.wrap(data2)
        writeBuffer2.writeInt(INT_42)
        writeBuffer2.writeInt(INT_100)

        val readOnly1 = PlatformBuffer.wrapReadOnly(data1 as NSData)
        val readOnly2 = PlatformBuffer.wrapReadOnly(data2 as NSData)

        // NSDataBuffer vs NSDataBuffer
        assertTrue(readOnly1.contentEquals(readOnly2))

        // NSDataBuffer vs MutableDataBuffer
        writeBuffer1.resetForRead()
        assertTrue(readOnly2.contentEquals(writeBuffer1))

        // Change one and verify not equal
        val mdBuffer = PlatformBuffer.wrap(data2)
        mdBuffer.writeInt(INT_999)
        val readOnly2Modified = PlatformBuffer.wrapReadOnly(data2)
        assertFalse(readOnly1.contentEquals(readOnly2Modified))
    }

    @Test
    fun positionAndLimitManagement() {
        val mutableData = NSMutableData.create(length = LEN_16.convert())!!
        val readBuffer = PlatformBuffer.wrapReadOnly(mutableData as NSData)

        // Initial state
        assertEquals(0, readBuffer.position())
        assertEquals(LEN_16, readBuffer.limit())
        assertEquals(LEN_16, readBuffer.remaining())
        assertTrue(readBuffer.hasRemaining())

        // Move position
        readBuffer.position(EIGHT)
        assertEquals(EIGHT, readBuffer.position())
        assertEquals(EIGHT, readBuffer.remaining())

        // Change limit
        readBuffer.setLimit(TWELVE)
        assertEquals(FOUR, readBuffer.remaining())

        // Move to end
        readBuffer.position(TWELVE)
        assertEquals(0, readBuffer.remaining())
        assertFalse(readBuffer.hasRemaining())
    }

    // region toNativeData tests

    @Test
    fun toNativeDataReturnsFullNSDataAtPositionZero() {
        val mutableData = NSMutableData.create(length = LEN_8.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        writeBuffer.writeBytes(sequentialBytes)
        writeBuffer.resetForRead()

        val result = writeBuffer.toNativeData().nsData
        assertSame(mutableData, result)
    }

    @Test
    fun toNativeDataReturnsRemainingBytesFromPosition() {
        val mutableData = NSMutableData.create(length = LEN_8.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        writeBuffer.writeBytes(sequentialBytes)
        writeBuffer.resetForRead()
        writeBuffer.readByte() // position = 1
        writeBuffer.readByte() // position = 2

        val result = writeBuffer.toNativeData().nsData
        assertEquals(SIX.convert(), result.length)

        val resultBuffer = PlatformBuffer.wrapReadOnly(result)
        val expected = byteArrayOf(3, 4, 5, 6, 7, 8)
        assertContentEquals(expected, resultBuffer.toByteArray())
    }

    @Test
    fun toNativeDataRespectsLimit() {
        val mutableData = NSMutableData.create(length = LEN_8.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        writeBuffer.writeBytes(sequentialBytes)
        writeBuffer.resetForRead()
        writeBuffer.setLimit(FIVE)

        val result = writeBuffer.toNativeData().nsData
        assertEquals(FIVE.convert(), result.length)

        val resultBuffer = PlatformBuffer.wrapReadOnly(result)
        val expected = byteArrayOf(1, 2, 3, 4, 5)
        assertContentEquals(expected, resultBuffer.toByteArray())
    }

    @Test
    fun toNativeDataRespectsPositionAndLimit() {
        val mutableData = NSMutableData.create(length = LEN_8.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        writeBuffer.writeBytes(sequentialBytes)
        writeBuffer.resetForRead()
        writeBuffer.readByte() // position = 1
        writeBuffer.setLimit(SIX)

        val result = writeBuffer.toNativeData().nsData
        assertEquals(FIVE.convert(), result.length)

        val resultBuffer = PlatformBuffer.wrapReadOnly(result)
        val expected = byteArrayOf(2, 3, 4, 5, 6)
        assertContentEquals(expected, resultBuffer.toByteArray())
    }

    @Test
    fun toMutableNativeDataReturnsFullNSMutableDataAtPositionZero() {
        val mutableData = NSMutableData.create(length = LEN_8.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        writeBuffer.writeBytes(sequentialBytes)
        writeBuffer.resetForRead()

        val result = writeBuffer.toMutableNativeData().nsMutableData
        assertSame(mutableData, result)
    }

    @Test
    fun toMutableNativeDataReturnsRemainingBytesFromPosition() {
        val mutableData = NSMutableData.create(length = LEN_8.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        writeBuffer.writeBytes(sequentialBytes)
        writeBuffer.resetForRead()
        writeBuffer.readByte() // position = 1
        writeBuffer.readByte() // position = 2

        val result = writeBuffer.toMutableNativeData().nsMutableData
        assertEquals(SIX.convert(), result.length)

        val resultBuffer = PlatformBuffer.wrap(result)
        val expected = byteArrayOf(3, 4, 5, 6, 7, 8)
        assertContentEquals(expected, resultBuffer.toByteArray())
    }

    @Test
    fun toNativeDataWorksWithNSDataBuffer() {
        val mutableData = NSMutableData.create(length = LEN_8.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        writeBuffer.writeBytes(sequentialBytes)

        val nsDataBuffer = PlatformBuffer.wrapReadOnly(mutableData as NSData)
        nsDataBuffer.readByte() // position = 1
        nsDataBuffer.readByte() // position = 2

        val result = nsDataBuffer.toNativeData().nsData
        assertEquals(SIX.convert(), result.length)
    }

    @Test
    fun toNativeDataFromByteArrayBuffer() {
        val buffer = BufferFactory.managed().allocate(LEN_8)
        buffer.writeBytes(sequentialBytes)
        buffer.resetForRead()
        buffer.readByte() // position = 1

        val result = buffer.toNativeData().nsData
        assertEquals(SEVEN.convert(), result.length)

        val resultBuffer = PlatformBuffer.wrapReadOnly(result)
        val expected = byteArrayOf(2, 3, 4, 5, 6, 7, 8)
        assertContentEquals(expected, resultBuffer.toByteArray())
    }

    // endregion

    // region slice NativeMemoryAccess preservation tests

    @Test
    fun nsDataBufferSlicePreservesNativeMemoryAccess() {
        val mutableData = NSMutableData.create(length = LEN_16.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        for (i in 0 until LEN_16) writeBuffer.writeByte(i.toByte())

        val readBuffer = PlatformBuffer.wrapReadOnly(mutableData as NSData)
        val nma = (readBuffer as ReadBuffer).nativeMemoryAccess
        assertNotNull(nma, "NSDataBuffer must have nativeMemoryAccess")

        readBuffer.position(FOUR)
        readBuffer.setLimit(TWELVE)
        val slice = readBuffer.slice()

        val sliceNma = slice.nativeMemoryAccess
        assertNotNull(sliceNma, "NSDataBuffer slice must preserve nativeMemoryAccess")
        assertTrue(sliceNma.nativeSize > 0, "Slice nativeSize must be > 0")
        assertEquals(EIGHT, sliceNma.nativeSize.toInt())
        assertEquals(FOUR.toByte(), slice.readByte())
    }

    @Test
    fun nsDataBufferDoubleSlicePreservesNativeMemoryAccess() {
        val mutableData = NSMutableData.create(length = LEN_32.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        for (i in 0 until LEN_32) writeBuffer.writeByte(i.toByte())

        val readBuffer = PlatformBuffer.wrapReadOnly(mutableData as NSData)
        readBuffer.position(FOUR)

        val slice1 = readBuffer.slice()
        val slice1Nma = slice1.nativeMemoryAccess
        assertNotNull(slice1Nma, "First slice must have nativeMemoryAccess")

        slice1.position(FOUR)
        val slice2 = slice1.slice()
        val slice2Nma = slice2.nativeMemoryAccess
        assertNotNull(slice2Nma, "Double-sliced NSDataBuffer must preserve nativeMemoryAccess")
        assertTrue(slice2Nma.nativeSize > 0)
        assertEquals(EIGHT.toByte(), slice2.readByte())
    }

    @Test
    fun mutableDataBufferSlicePreservesNativeMemoryAccess() {
        val buffer = BufferFactory.Default.allocate(LEN_64)
        buffer.writeInt(INT_11223344)
        buffer.writeInt(INT_55667788)
        buffer.resetForRead()
        buffer.readInt() // skip first

        val nma = (buffer as ReadBuffer).nativeMemoryAccess
        assertNotNull(nma, "MutableDataBuffer must have nativeMemoryAccess")

        val slice = buffer.slice()
        val sliceNma = slice.nativeMemoryAccess
        assertNotNull(sliceNma, "MutableDataBuffer slice must preserve nativeMemoryAccess")
        assertTrue(sliceNma.nativeSize > 0, "Slice nativeSize must be > 0")
        assertEquals(INT_55667788, slice.readInt())
    }

    @Test
    fun mutableDataBufferDoubleSlicePreservesNativeMemoryAccess() {
        val buffer = BufferFactory.Default.allocate(LEN_128)
        for (i in 0 until LEN_32) buffer.writeInt(i)
        buffer.resetForRead()
        buffer.readInt() // skip first

        val slice1 = buffer.slice()
        slice1.readInt() // skip one more
        val slice2 = slice1.slice()

        val sliceNma = slice2.nativeMemoryAccess
        assertNotNull(sliceNma, "Double-sliced MutableDataBuffer must preserve nativeMemoryAccess")
        assertTrue(sliceNma.nativeSize > 0)
        assertEquals(TWO, slice2.readInt())
    }

    @Test
    fun nsDataBufferSliceNativeAddressPointsToCorrectMemory() {
        val mutableData = NSMutableData.create(length = LEN_16.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        for (i in 0 until LEN_16) writeBuffer.writeByte(i.toByte())

        val readBuffer = PlatformBuffer.wrapReadOnly(mutableData as NSData)
        val parentNma = (readBuffer as NativeMemoryAccess)
        val parentAddr = parentNma.nativeAddress

        readBuffer.position(FOUR)
        val slice = readBuffer.slice()
        val sliceNma = slice.nativeMemoryAccess!!

        // Slice address should be parent address + offset
        assertEquals(parentAddr + FOUR, sliceNma.nativeAddress)
    }

    // endregion

    private companion object {
        private const val TWO = 2
        private const val THREE = 3
        private const val FOUR = 4
        private const val FIVE = 5
        private const val SIX = 6
        private const val SEVEN = 7
        private const val EIGHT = 8
        private const val TWELVE = 12
        private const val LEN_8 = 8
        private const val LEN_16 = 16
        private const val LEN_32 = 32
        private const val LEN_64 = 64
        private const val LEN_128 = 128
        private const val BYTE_ONE = 0x01
        private const val MISSING_BYTE = 99
        private const val SHORT_0203: Short = 0x0203
        private const val INT_A = 0x12345678
        private const val INT_DEAD_BEEF = 0xDEADBEEF.toInt()
        private const val INT_04050607 = 0x04050607
        private const val INT_11223344 = 0x11223344
        private const val INT_55667788 = 0x55667788
        private const val INT_42 = 42
        private const val INT_100 = 100
        private const val INT_999 = 999
        private const val LONG_SEQ = 0x08090A0B0C0D0E0FL

        private val sequentialBytes: ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    }
}
