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
        val mutableData = NSMutableData.create(length = 16.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        writeBuffer.writeInt(0x12345678)
        writeBuffer.writeInt(0xDEADBEEF.toInt())
        writeBuffer.resetForRead()

        // Get immutable NSData reference
        val nsData: NSData = mutableData

        // Wrap as read-only
        val readOnlyBuffer = PlatformBuffer.wrapReadOnly(nsData)

        // Verify content is accessible
        assertEquals(0x12345678, readOnlyBuffer.readInt())
        assertEquals(0xDEADBEEF.toInt(), readOnlyBuffer.readInt())

        // Verify it's the same underlying memory (nativeAddress matches)
        assertTrue(readOnlyBuffer is NativeMemoryAccess)
        assertEquals(writeBuffer.nativeAddress, (readOnlyBuffer as NativeMemoryAccess).nativeAddress)
    }

    @Test
    fun readOnlyBufferOperations() {
        val mutableData = NSMutableData.create(length = 32.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        writeBuffer.writeByte(0x01)
        writeBuffer.writeShort(0x0203)
        writeBuffer.writeInt(0x04050607)
        writeBuffer.writeLong(0x08090A0B0C0D0E0FL)
        writeBuffer.writeString("Hi")
        writeBuffer.resetForRead()

        val readBuffer = PlatformBuffer.wrapReadOnly(mutableData as NSData, ByteOrder.BIG_ENDIAN)

        // Relative reads
        assertEquals(0x01.toByte(), readBuffer.readByte())
        assertEquals(0x0203.toShort(), readBuffer.readShort())
        assertEquals(0x04050607, readBuffer.readInt())
        assertEquals(0x08090A0B0C0D0E0FL, readBuffer.readLong())
        assertEquals("Hi", readBuffer.readString(2))

        // Absolute reads (reset position first)
        readBuffer.position(0)
        assertEquals(0x01.toByte(), readBuffer.get(0))
        assertEquals(0x0203.toShort(), readBuffer.getShort(1))
        assertEquals(0x04050607, readBuffer.getInt(3))
        assertEquals(0x08090A0B0C0D0E0FL, readBuffer.getLong(7))
    }

    @Test
    fun sliceIsZeroCopy() {
        val mutableData = NSMutableData.create(length = 16.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        for (i in 0 until 16) {
            writeBuffer.writeByte(i.toByte())
        }

        val readBuffer = PlatformBuffer.wrapReadOnly(mutableData as NSData)

        // Position at byte 4, limit at byte 12
        readBuffer.position(4)
        readBuffer.setLimit(12)

        // Slice should create a zero-copy view
        val slice = readBuffer.slice()

        // Slice starts at position 0, has remaining = 8
        assertEquals(0, slice.position())
        assertEquals(8, slice.remaining())

        // First byte in slice should be byte 4 from original
        assertEquals(4.toByte(), slice.readByte())
        assertEquals(5.toByte(), slice.readByte())
    }

    @Test
    fun indexOfWorksCorrectly() {
        val mutableData = NSMutableData.create(length = 16.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        for (i in 0 until 16) {
            writeBuffer.writeByte(i.toByte())
        }

        val readBuffer = PlatformBuffer.wrapReadOnly(mutableData as NSData)

        // Find existing byte
        assertEquals(5, readBuffer.indexOf(5.toByte()))

        // Find non-existing byte
        assertEquals(-1, readBuffer.indexOf(99.toByte()))

        // Find first byte
        assertEquals(0, readBuffer.indexOf(0.toByte()))
    }

    @Test
    fun contentEqualsWithDifferentBufferTypes() {
        val data1 = NSMutableData.create(length = 8.convert())!!
        val writeBuffer1 = PlatformBuffer.wrap(data1)
        writeBuffer1.writeInt(42)
        writeBuffer1.writeInt(100)

        val data2 = NSMutableData.create(length = 8.convert())!!
        val writeBuffer2 = PlatformBuffer.wrap(data2)
        writeBuffer2.writeInt(42)
        writeBuffer2.writeInt(100)

        val readOnly1 = PlatformBuffer.wrapReadOnly(data1 as NSData)
        val readOnly2 = PlatformBuffer.wrapReadOnly(data2 as NSData)

        // NSDataBuffer vs NSDataBuffer
        assertTrue(readOnly1.contentEquals(readOnly2))

        // NSDataBuffer vs MutableDataBuffer
        writeBuffer1.resetForRead()
        assertTrue(readOnly2.contentEquals(writeBuffer1))

        // Change one and verify not equal
        val mdBuffer = PlatformBuffer.wrap(data2)
        mdBuffer.writeInt(999)
        val readOnly2Modified = PlatformBuffer.wrapReadOnly(data2)
        assertFalse(readOnly1.contentEquals(readOnly2Modified))
    }

    @Test
    fun positionAndLimitManagement() {
        val mutableData = NSMutableData.create(length = 16.convert())!!
        val readBuffer = PlatformBuffer.wrapReadOnly(mutableData as NSData)

        // Initial state
        assertEquals(0, readBuffer.position())
        assertEquals(16, readBuffer.limit())
        assertEquals(16, readBuffer.remaining())
        assertTrue(readBuffer.hasRemaining())

        // Move position
        readBuffer.position(8)
        assertEquals(8, readBuffer.position())
        assertEquals(8, readBuffer.remaining())

        // Change limit
        readBuffer.setLimit(12)
        assertEquals(4, readBuffer.remaining())

        // Move to end
        readBuffer.position(12)
        assertEquals(0, readBuffer.remaining())
        assertFalse(readBuffer.hasRemaining())
    }

    // region toNativeData tests

    @Test
    fun toNativeDataReturnsFullNSDataAtPositionZero() {
        val mutableData = NSMutableData.create(length = 8.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        writeBuffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        writeBuffer.resetForRead()

        val result = writeBuffer.toNativeData().nsData
        assertSame(mutableData, result)
    }

    @Test
    fun toNativeDataReturnsRemainingBytesFromPosition() {
        val mutableData = NSMutableData.create(length = 8.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        writeBuffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        writeBuffer.resetForRead()
        writeBuffer.readByte() // position = 1
        writeBuffer.readByte() // position = 2

        val result = writeBuffer.toNativeData().nsData
        assertEquals(6.convert(), result.length)

        val resultBuffer = PlatformBuffer.wrapReadOnly(result)
        assertContentEquals(byteArrayOf(3, 4, 5, 6, 7, 8), resultBuffer.toByteArray())
    }

    @Test
    fun toNativeDataRespectsLimit() {
        val mutableData = NSMutableData.create(length = 8.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        writeBuffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        writeBuffer.resetForRead()
        writeBuffer.setLimit(5)

        val result = writeBuffer.toNativeData().nsData
        assertEquals(5.convert(), result.length)

        val resultBuffer = PlatformBuffer.wrapReadOnly(result)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), resultBuffer.toByteArray())
    }

    @Test
    fun toNativeDataRespectsPositionAndLimit() {
        val mutableData = NSMutableData.create(length = 8.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        writeBuffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        writeBuffer.resetForRead()
        writeBuffer.readByte() // position = 1
        writeBuffer.setLimit(6)

        val result = writeBuffer.toNativeData().nsData
        assertEquals(5.convert(), result.length)

        val resultBuffer = PlatformBuffer.wrapReadOnly(result)
        assertContentEquals(byteArrayOf(2, 3, 4, 5, 6), resultBuffer.toByteArray())
    }

    @Test
    fun toMutableNativeDataReturnsFullNSMutableDataAtPositionZero() {
        val mutableData = NSMutableData.create(length = 8.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        writeBuffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        writeBuffer.resetForRead()

        val result = writeBuffer.toMutableNativeData().nsMutableData
        assertSame(mutableData, result)
    }

    @Test
    fun toMutableNativeDataReturnsRemainingBytesFromPosition() {
        val mutableData = NSMutableData.create(length = 8.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        writeBuffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        writeBuffer.resetForRead()
        writeBuffer.readByte() // position = 1
        writeBuffer.readByte() // position = 2

        val result = writeBuffer.toMutableNativeData().nsMutableData
        assertEquals(6.convert(), result.length)

        val resultBuffer = PlatformBuffer.wrap(result)
        assertContentEquals(byteArrayOf(3, 4, 5, 6, 7, 8), resultBuffer.toByteArray())
    }

    @Test
    fun toNativeDataWorksWithNSDataBuffer() {
        val mutableData = NSMutableData.create(length = 8.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        writeBuffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

        val nsDataBuffer = PlatformBuffer.wrapReadOnly(mutableData as NSData)
        nsDataBuffer.readByte() // position = 1
        nsDataBuffer.readByte() // position = 2

        val result = nsDataBuffer.toNativeData().nsData
        assertEquals(6.convert(), result.length)
    }

    @Test
    fun toNativeDataFromByteArrayBuffer() {
        val buffer = PlatformBuffer.allocate(8, AllocationZone.Heap)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()
        buffer.readByte() // position = 1

        val result = buffer.toNativeData().nsData
        assertEquals(7.convert(), result.length)

        val resultBuffer = PlatformBuffer.wrapReadOnly(result)
        assertContentEquals(byteArrayOf(2, 3, 4, 5, 6, 7, 8), resultBuffer.toByteArray())
    }

    // endregion

    // region slice NativeMemoryAccess preservation tests

    @Test
    fun nsDataBufferSlicePreservesNativeMemoryAccess() {
        val mutableData = NSMutableData.create(length = 16.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        for (i in 0 until 16) writeBuffer.writeByte(i.toByte())

        val readBuffer = PlatformBuffer.wrapReadOnly(mutableData as NSData)
        val nma = (readBuffer as ReadBuffer).nativeMemoryAccess
        assertNotNull(nma, "NSDataBuffer must have nativeMemoryAccess")

        readBuffer.position(4)
        readBuffer.setLimit(12)
        val slice = readBuffer.slice()

        val sliceNma = slice.nativeMemoryAccess
        assertNotNull(sliceNma, "NSDataBuffer slice must preserve nativeMemoryAccess")
        assertTrue(sliceNma.nativeSize > 0, "Slice nativeSize must be > 0")
        assertEquals(8, sliceNma.nativeSize.toInt())
        assertEquals(4.toByte(), slice.readByte())
    }

    @Test
    fun nsDataBufferDoubleSlicePreservesNativeMemoryAccess() {
        val mutableData = NSMutableData.create(length = 32.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        for (i in 0 until 32) writeBuffer.writeByte(i.toByte())

        val readBuffer = PlatformBuffer.wrapReadOnly(mutableData as NSData)
        readBuffer.position(4)

        val slice1 = readBuffer.slice()
        val slice1Nma = slice1.nativeMemoryAccess
        assertNotNull(slice1Nma, "First slice must have nativeMemoryAccess")

        slice1.position(4)
        val slice2 = slice1.slice()
        val slice2Nma = slice2.nativeMemoryAccess
        assertNotNull(slice2Nma, "Double-sliced NSDataBuffer must preserve nativeMemoryAccess")
        assertTrue(slice2Nma.nativeSize > 0)
        assertEquals(8.toByte(), slice2.readByte())
    }

    @Test
    fun mutableDataBufferSlicePreservesNativeMemoryAccess() {
        val buffer = PlatformBuffer.allocate(64, AllocationZone.Direct)
        buffer.writeInt(0x11223344)
        buffer.writeInt(0x55667788)
        buffer.resetForRead()
        buffer.readInt() // skip first

        val nma = (buffer as ReadBuffer).nativeMemoryAccess
        assertNotNull(nma, "MutableDataBuffer must have nativeMemoryAccess")

        val slice = buffer.slice()
        val sliceNma = slice.nativeMemoryAccess
        assertNotNull(sliceNma, "MutableDataBuffer slice must preserve nativeMemoryAccess")
        assertTrue(sliceNma.nativeSize > 0, "Slice nativeSize must be > 0")
        assertEquals(0x55667788, slice.readInt())
    }

    @Test
    fun mutableDataBufferDoubleSlicePreservesNativeMemoryAccess() {
        val buffer = PlatformBuffer.allocate(128, AllocationZone.Direct)
        for (i in 0 until 32) buffer.writeInt(i)
        buffer.resetForRead()
        buffer.readInt() // skip first

        val slice1 = buffer.slice()
        slice1.readInt() // skip one more
        val slice2 = slice1.slice()

        val sliceNma = slice2.nativeMemoryAccess
        assertNotNull(sliceNma, "Double-sliced MutableDataBuffer must preserve nativeMemoryAccess")
        assertTrue(sliceNma.nativeSize > 0)
        assertEquals(2, slice2.readInt())
    }

    @Test
    fun nsDataBufferSliceNativeAddressPointsToCorrectMemory() {
        val mutableData = NSMutableData.create(length = 16.convert())!!
        val writeBuffer = PlatformBuffer.wrap(mutableData)
        for (i in 0 until 16) writeBuffer.writeByte(i.toByte())

        val readBuffer = PlatformBuffer.wrapReadOnly(mutableData as NSData)
        val parentNma = (readBuffer as NativeMemoryAccess)
        val parentAddr = parentNma.nativeAddress

        readBuffer.position(4)
        val slice = readBuffer.slice()
        val sliceNma = slice.nativeMemoryAccess!!

        // Slice address should be parent address + offset
        assertEquals(parentAddr + 4, sliceNma.nativeAddress)
    }

    // endregion
}
