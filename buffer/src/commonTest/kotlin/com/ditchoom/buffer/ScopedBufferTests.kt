package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScopedBufferTests {
    // ===== Scope Lifecycle Tests =====

    @Test
    fun scopeIsOpenInitially() {
        withScope { scope ->
            assertTrue(scope.isOpen, "Scope should be open initially")
        }
    }

    @Test
    fun scopeIsClosedAfterBlock() {
        var capturedScope: BufferScope? = null
        withScope { scope ->
            capturedScope = scope
            assertTrue(scope.isOpen)
        }
        assertFalse(capturedScope!!.isOpen, "Scope should be closed after withScope block")
    }

    @Test
    fun allocatedBufferHasCorrectCapacity() {
        withScope { scope ->
            val buffer = scope.allocate(1024)
            assertEquals(1024, buffer.capacity)
        }
    }

    @Test
    fun multipleAllocationsFromSameScope() {
        withScope { scope ->
            val buffer1 = scope.allocate(100)
            val buffer2 = scope.allocate(200)
            val buffer3 = scope.allocate(300)

            assertEquals(100, buffer1.capacity)
            assertEquals(200, buffer2.capacity)
            assertEquals(300, buffer3.capacity)

            // All buffers should be usable independently
            buffer1.writeInt(1)
            buffer2.writeInt(2)
            buffer3.writeInt(3)

            buffer1.resetForRead()
            buffer2.resetForRead()
            buffer3.resetForRead()

            assertEquals(1, buffer1.readInt())
            assertEquals(2, buffer2.readInt())
            assertEquals(3, buffer3.readInt())
        }
    }

    @Test
    fun bufferHasValidNativeAddress() {
        withScope { scope ->
            val buffer = scope.allocate(64)
            // Native address should be non-zero (except possibly on JS where it's the byte offset)
            // At minimum, it should be a valid value
            assertTrue(buffer.nativeAddress >= 0, "Native address should be >= 0")
        }
    }

    // ===== Position and Limit Tests =====

    @Test
    fun initialPositionIsZero() {
        withScope { scope ->
            val buffer = scope.allocate(64)
            assertEquals(0, buffer.position())
        }
    }

    @Test
    fun initialLimitEqualsCapacity() {
        withScope { scope ->
            val buffer = scope.allocate(64)
            assertEquals(64, buffer.limit())
        }
    }

    @Test
    fun setPosition() {
        withScope { scope ->
            val buffer = scope.allocate(64)
            buffer.position(32)
            assertEquals(32, buffer.position())
        }
    }

    @Test
    fun setLimit() {
        withScope { scope ->
            val buffer = scope.allocate(64)
            buffer.setLimit(32)
            assertEquals(32, buffer.limit())
        }
    }

    @Test
    fun remaining() {
        withScope { scope ->
            val buffer = scope.allocate(64)
            assertEquals(64, buffer.remaining())

            buffer.position(24)
            assertEquals(40, buffer.remaining())

            buffer.setLimit(32)
            assertEquals(8, buffer.remaining())
        }
    }

    @Test
    fun resetForRead() {
        withScope { scope ->
            val buffer = scope.allocate(64)
            buffer.writeInt(42)
            buffer.writeInt(123)
            assertEquals(8, buffer.position())

            buffer.resetForRead()
            assertEquals(0, buffer.position())
            assertEquals(8, buffer.limit())
        }
    }

    @Test
    fun resetForWrite() {
        withScope { scope ->
            val buffer = scope.allocate(64)
            buffer.writeInt(42)
            buffer.setLimit(32)
            buffer.position(16)

            buffer.resetForWrite()
            assertEquals(0, buffer.position())
            assertEquals(64, buffer.limit())
        }
    }

    // ===== Primitive Read/Write Tests (Relative) =====

    @Test
    fun readWriteByte() {
        withScope { scope ->
            val buffer = scope.allocate(16)

            buffer.writeByte(0x42)
            buffer.writeByte((-1).toByte())
            buffer.writeByte(Byte.MIN_VALUE)
            buffer.writeByte(Byte.MAX_VALUE)

            buffer.resetForRead()

            assertEquals(0x42.toByte(), buffer.readByte())
            assertEquals((-1).toByte(), buffer.readByte())
            assertEquals(Byte.MIN_VALUE, buffer.readByte())
            assertEquals(Byte.MAX_VALUE, buffer.readByte())
        }
    }

    @Test
    fun readWriteShortBigEndian() {
        withScope { scope ->
            val buffer = scope.allocate(16, ByteOrder.BIG_ENDIAN)

            buffer.writeShort(0x1234)
            buffer.writeShort(Short.MIN_VALUE)
            buffer.writeShort(Short.MAX_VALUE)

            buffer.resetForRead()

            assertEquals(0x1234.toShort(), buffer.readShort())
            assertEquals(Short.MIN_VALUE, buffer.readShort())
            assertEquals(Short.MAX_VALUE, buffer.readShort())
        }
    }

    @Test
    fun readWriteShortLittleEndian() {
        withScope { scope ->
            val buffer = scope.allocate(16, ByteOrder.LITTLE_ENDIAN)

            buffer.writeShort(0x1234)
            buffer.writeShort(Short.MIN_VALUE)
            buffer.writeShort(Short.MAX_VALUE)

            buffer.resetForRead()

            assertEquals(0x1234.toShort(), buffer.readShort())
            assertEquals(Short.MIN_VALUE, buffer.readShort())
            assertEquals(Short.MAX_VALUE, buffer.readShort())
        }
    }

    @Test
    fun readWriteIntBigEndian() {
        withScope { scope ->
            val buffer = scope.allocate(32, ByteOrder.BIG_ENDIAN)

            buffer.writeInt(0x12345678)
            buffer.writeInt(Int.MIN_VALUE)
            buffer.writeInt(Int.MAX_VALUE)
            buffer.writeInt(-1)

            buffer.resetForRead()

            assertEquals(0x12345678, buffer.readInt())
            assertEquals(Int.MIN_VALUE, buffer.readInt())
            assertEquals(Int.MAX_VALUE, buffer.readInt())
            assertEquals(-1, buffer.readInt())
        }
    }

    @Test
    fun readWriteIntLittleEndian() {
        withScope { scope ->
            val buffer = scope.allocate(32, ByteOrder.LITTLE_ENDIAN)

            buffer.writeInt(0x12345678)
            buffer.writeInt(Int.MIN_VALUE)
            buffer.writeInt(Int.MAX_VALUE)
            buffer.writeInt(-1)

            buffer.resetForRead()

            assertEquals(0x12345678, buffer.readInt())
            assertEquals(Int.MIN_VALUE, buffer.readInt())
            assertEquals(Int.MAX_VALUE, buffer.readInt())
            assertEquals(-1, buffer.readInt())
        }
    }

    @Test
    fun readWriteLongBigEndian() {
        withScope { scope ->
            val buffer = scope.allocate(64, ByteOrder.BIG_ENDIAN)

            buffer.writeLong(0x123456789ABCDEF0L)
            buffer.writeLong(Long.MIN_VALUE)
            buffer.writeLong(Long.MAX_VALUE)
            buffer.writeLong(-1L)

            buffer.resetForRead()

            assertEquals(0x123456789ABCDEF0L, buffer.readLong())
            assertEquals(Long.MIN_VALUE, buffer.readLong())
            assertEquals(Long.MAX_VALUE, buffer.readLong())
            assertEquals(-1L, buffer.readLong())
        }
    }

    @Test
    fun readWriteLongLittleEndian() {
        withScope { scope ->
            val buffer = scope.allocate(64, ByteOrder.LITTLE_ENDIAN)

            buffer.writeLong(0x123456789ABCDEF0L)
            buffer.writeLong(Long.MIN_VALUE)
            buffer.writeLong(Long.MAX_VALUE)
            buffer.writeLong(-1L)

            buffer.resetForRead()

            assertEquals(0x123456789ABCDEF0L, buffer.readLong())
            assertEquals(Long.MIN_VALUE, buffer.readLong())
            assertEquals(Long.MAX_VALUE, buffer.readLong())
            assertEquals(-1L, buffer.readLong())
        }
    }

    // ===== Primitive Read/Write Tests (Absolute) =====

    @Test
    fun getSetByteAbsolute() {
        withScope { scope ->
            val buffer = scope.allocate(16)

            buffer[0] = 0x11.toByte()
            buffer[5] = 0x22.toByte()
            buffer[10] = 0x33.toByte()

            assertEquals(0x11.toByte(), buffer[0])
            assertEquals(0x22.toByte(), buffer[5])
            assertEquals(0x33.toByte(), buffer[10])

            // Position should not change with absolute operations
            assertEquals(0, buffer.position())
        }
    }

    @Test
    fun getSetShortAbsolute() {
        withScope { scope ->
            val buffer = scope.allocate(16, ByteOrder.BIG_ENDIAN)

            buffer[0] = 0x1234.toShort()
            buffer[4] = 0x5678.toShort()

            assertEquals(0x1234.toShort(), buffer.getShort(0))
            assertEquals(0x5678.toShort(), buffer.getShort(4))
            assertEquals(0, buffer.position())
        }
    }

    @Test
    fun getSetIntAbsolute() {
        withScope { scope ->
            val buffer = scope.allocate(16, ByteOrder.BIG_ENDIAN)

            buffer[0] = 0x12345678
            buffer[8] = 0xDEADBEEF.toInt()

            assertEquals(0x12345678, buffer.getInt(0))
            assertEquals(0xDEADBEEF.toInt(), buffer.getInt(8))
            assertEquals(0, buffer.position())
        }
    }

    @Test
    fun getSetLongAbsolute() {
        withScope { scope ->
            val buffer = scope.allocate(24, ByteOrder.BIG_ENDIAN)

            buffer[0] = 0x123456789ABCDEF0L
            buffer[16] = 0xFEDCBA9876543210UL.toLong()

            assertEquals(0x123456789ABCDEF0L, buffer.getLong(0))
            assertEquals(0xFEDCBA9876543210UL.toLong(), buffer.getLong(16))
            assertEquals(0, buffer.position())
        }
    }

    // ===== Bulk Operations Tests =====

    @Test
    fun writeAndReadByteArray() {
        withScope { scope ->
            val buffer = scope.allocate(64)
            val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

            buffer.writeBytes(data)
            buffer.resetForRead()

            val result = buffer.readByteArray(10)
            assertContentEquals(data, result)
        }
    }

    @Test
    fun writeByteArrayWithOffsetAndLength() {
        withScope { scope ->
            val buffer = scope.allocate(64)
            val data = byteArrayOf(0, 0, 1, 2, 3, 4, 5, 0, 0, 0)

            buffer.writeBytes(data, offset = 2, length = 5)
            buffer.resetForRead()

            val result = buffer.readByteArray(5)
            assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), result)
        }
    }

    @Test
    fun writeBufferToBuffer() {
        withScope { scope ->
            val source = scope.allocate(32)
            val dest = scope.allocate(32)

            source.writeInt(0x12345678)
            source.writeInt(0xDEADBEEF.toInt())
            source.resetForRead()

            dest.write(source)
            dest.resetForRead()

            assertEquals(0x12345678, dest.readInt())
            assertEquals(0xDEADBEEF.toInt(), dest.readInt())
        }
    }

    // ===== String Operations Tests =====

    @Test
    fun writeAndReadStringUtf8() {
        withScope { scope ->
            val buffer = scope.allocate(64)
            val text = "Hello, World!"

            buffer.writeString(text, Charset.UTF8)
            buffer.resetForRead()

            val result = buffer.readString(text.encodeToByteArray().size, Charset.UTF8)
            assertEquals(text, result)
        }
    }

    @Test
    fun writeAndReadStringWithUnicode() {
        withScope { scope ->
            val buffer = scope.allocate(128)
            val text = "Hello, 世界! 🌍"

            buffer.writeString(text, Charset.UTF8)
            buffer.resetForRead()

            val result = buffer.readString(text.encodeToByteArray().size, Charset.UTF8)
            assertEquals(text, result)
        }
    }

    // ===== Slice Tests =====

    @Test
    fun sliceCreatesViewOfRemainingBytes() {
        withScope { scope ->
            val buffer = scope.allocate(32)

            buffer.writeInt(0x11111111)
            buffer.writeInt(0x22222222)
            buffer.writeInt(0x33333333)
            buffer.writeInt(0x44444444)

            buffer.resetForRead()
            buffer.readInt() // Skip first int
            buffer.readInt() // Skip second int

            val slice = buffer.slice()
            assertEquals(0, slice.position())
            assertEquals(8, slice.remaining()) // 2 ints remaining

            assertEquals(0x33333333, slice.readInt())
            assertEquals(0x44444444, slice.readInt())
        }
    }

    // ===== Byte Order Tests =====

    @Test
    fun byteOrderAffectsMultiByteWrites() {
        withScope { scope ->
            val bigEndian = scope.allocate(4, ByteOrder.BIG_ENDIAN)
            val littleEndian = scope.allocate(4, ByteOrder.LITTLE_ENDIAN)

            bigEndian.writeInt(0x01020304)
            littleEndian.writeInt(0x01020304)

            bigEndian.resetForRead()
            littleEndian.resetForRead()

            // Big endian: 01 02 03 04
            assertEquals(0x01.toByte(), bigEndian.readByte())
            assertEquals(0x02.toByte(), bigEndian.readByte())
            assertEquals(0x03.toByte(), bigEndian.readByte())
            assertEquals(0x04.toByte(), bigEndian.readByte())

            // Little endian: 04 03 02 01
            assertEquals(0x04.toByte(), littleEndian.readByte())
            assertEquals(0x03.toByte(), littleEndian.readByte())
            assertEquals(0x02.toByte(), littleEndian.readByte())
            assertEquals(0x01.toByte(), littleEndian.readByte())
        }
    }

    // ===== Aligned Allocation Tests =====

    @Test
    fun alignedAllocationReturnsBuffer() {
        withScope { scope ->
            val buffer = scope.allocateAligned(1024, alignment = 64)
            assertEquals(1024, buffer.capacity)
            assertTrue(buffer.nativeAddress >= 0)
        }
    }

    @Test
    fun alignedAllocationWithDifferentAlignments() {
        withScope { scope ->
            val buffer16 = scope.allocateAligned(256, alignment = 16)
            val buffer32 = scope.allocateAligned(256, alignment = 32)
            val buffer64 = scope.allocateAligned(256, alignment = 64)

            // All should have the requested capacity
            assertEquals(256, buffer16.capacity)
            assertEquals(256, buffer32.capacity)
            assertEquals(256, buffer64.capacity)

            // All should be usable
            buffer16.writeInt(1)
            buffer32.writeInt(2)
            buffer64.writeInt(3)

            buffer16.resetForRead()
            buffer32.resetForRead()
            buffer64.resetForRead()

            assertEquals(1, buffer16.readInt())
            assertEquals(2, buffer32.readInt())
            assertEquals(3, buffer64.readInt())
        }
    }

    // ===== Mixed Operations Test =====

    @Test
    fun mixedReadWriteOperations() {
        withScope { scope ->
            val buffer = scope.allocate(128, ByteOrder.BIG_ENDIAN)

            // Write mixed data
            buffer.writeByte(0x01)
            buffer.writeShort(0x0203)
            buffer.writeInt(0x04050607)
            buffer.writeLong(0x08090A0B0C0D0E0FL)
            buffer.writeString("Test", Charset.UTF8)
            buffer.writeBytes(byteArrayOf(0x10, 0x11, 0x12))

            buffer.resetForRead()

            // Read it back
            assertEquals(0x01.toByte(), buffer.readByte())
            assertEquals(0x0203.toShort(), buffer.readShort())
            assertEquals(0x04050607, buffer.readInt())
            assertEquals(0x08090A0B0C0D0E0FL, buffer.readLong())
            assertEquals("Test", buffer.readString(4, Charset.UTF8))
            assertContentEquals(byteArrayOf(0x10, 0x11, 0x12), buffer.readByteArray(3))
        }
    }

    // ===== Scope Reference Test =====

    @Test
    fun bufferReferencesItsScope() {
        withScope { scope ->
            val buffer = scope.allocate(64)
            assertEquals(scope, buffer.scope)
            assertTrue(buffer.scope.isOpen)
        }
    }

    // ===== Large Buffer Test =====

    @Test
    fun largeBufferAllocation() {
        withScope { scope ->
            val size = 1024 * 1024 // 1 MB
            val buffer = scope.allocate(size)

            assertEquals(size, buffer.capacity)

            // Write at beginning and end
            buffer.writeInt(0xDEADBEEF.toInt())
            buffer.position(size - 4)
            buffer.writeInt(0xCAFEBABE.toInt())

            // Read back
            buffer.position(0)
            assertEquals(0xDEADBEEF.toInt(), buffer.readInt())
            buffer.position(size - 4)
            assertEquals(0xCAFEBABE.toInt(), buffer.readInt())
        }
    }

    // ===== Return Value from withScope =====

    @Test
    fun withScopeReturnsBlockResult() {
        val result =
            withScope { scope ->
                val buffer = scope.allocate(16)
                buffer.writeInt(42)
                buffer.resetForRead()
                buffer.readInt()
            }
        assertEquals(42, result)
    }

    @Test
    fun withScopeReturnsComplexResult() {
        data class Result(
            val sum: Int,
            val count: Int,
        )

        val result =
            withScope { scope ->
                val buffer = scope.allocate(32)
                buffer.writeInt(10)
                buffer.writeInt(20)
                buffer.writeInt(30)

                buffer.resetForRead()
                var sum = 0
                var count = 0
                while (buffer.remaining() >= 4) {
                    sum += buffer.readInt()
                    count++
                }
                Result(sum, count)
            }

        assertEquals(60, result.sum)
        assertEquals(3, result.count)
    }

    // ===== Bulk Primitive Operations Tests =====

    @Test
    fun writeAndReadIntArray() {
        withScope { scope ->
            val buffer = scope.allocate(64, ByteOrder.BIG_ENDIAN)
            val data = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

            buffer.writeInts(data)
            buffer.resetForRead()

            val result = buffer.readInts(8)
            assertContentEquals(data, result)
        }
    }

    @Test
    fun writeAndReadIntArrayBigEndian() {
        // Test with big-endian to trigger byte swapping on little-endian CPUs (x86/ARM)
        withScope { scope ->
            val buffer = scope.allocate(64, ByteOrder.BIG_ENDIAN)
            val data = intArrayOf(0x12345678, 0xDEADBEEF.toInt(), 0x11223344, 0x55667788.toInt())

            buffer.writeInts(data)
            buffer.resetForRead()

            val result = buffer.readInts(4)
            assertContentEquals(data, result)
        }
    }

    @Test
    fun writeAndReadIntArrayLittleEndian() {
        // Test with little-endian to trigger byte swapping on big-endian CPUs
        withScope { scope ->
            val buffer = scope.allocate(64, ByteOrder.LITTLE_ENDIAN)
            val data = intArrayOf(0x12345678, 0xDEADBEEF.toInt(), 0x11223344, 0x55667788.toInt())

            buffer.writeInts(data)
            buffer.resetForRead()

            val result = buffer.readInts(4)
            assertContentEquals(data, result)
        }
    }

    @Test
    fun writeAndReadIntArrayOddCount() {
        // Test with odd number of ints to verify handling of remainders
        withScope { scope ->
            val buffer = scope.allocate(64, ByteOrder.BIG_ENDIAN)
            val data = intArrayOf(1, 2, 3, 4, 5) // Odd count

            buffer.writeInts(data)
            buffer.resetForRead()

            val result = buffer.readInts(5)
            assertContentEquals(data, result)
        }
    }

    @Test
    fun writeAndReadShortArray() {
        withScope { scope ->
            val buffer = scope.allocate(64, ByteOrder.BIG_ENDIAN)
            val data = shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

            buffer.writeShorts(data)
            buffer.resetForRead()

            val result = buffer.readShorts(8)
            assertContentEquals(data, result)
        }
    }

    @Test
    fun writeAndReadShortArrayBigEndian() {
        withScope { scope ->
            val buffer = scope.allocate(64, ByteOrder.BIG_ENDIAN)
            val data = shortArrayOf(0x1234, 0x5678, 0x1111, 0x2222, 0x3333)

            buffer.writeShorts(data)
            buffer.resetForRead()

            val result = buffer.readShorts(5)
            assertContentEquals(data, result)
        }
    }

    @Test
    fun writeAndReadShortArrayLittleEndian() {
        withScope { scope ->
            val buffer = scope.allocate(64, ByteOrder.LITTLE_ENDIAN)
            val data = shortArrayOf(0x1234, 0x5678, 0x1111, 0x2222, 0x3333)

            buffer.writeShorts(data)
            buffer.resetForRead()

            val result = buffer.readShorts(5)
            assertContentEquals(data, result)
        }
    }

    @Test
    fun writeAndReadLongArray() {
        withScope { scope ->
            val buffer = scope.allocate(128, ByteOrder.BIG_ENDIAN)
            val data = longArrayOf(0x123456789ABCDEF0L, 0xFEDCBA9876543210UL.toLong(), 1L, -1L)

            buffer.writeLongs(data)
            buffer.resetForRead()

            val result = buffer.readLongs(4)
            assertContentEquals(data, result)
        }
    }

    @Test
    fun writeAndReadFloatArray() {
        withScope { scope ->
            val buffer = scope.allocate(64, ByteOrder.BIG_ENDIAN)
            val data = floatArrayOf(1.0f, 2.5f, 3.14159f, -100.0f)

            buffer.writeFloats(data)
            buffer.resetForRead()

            val result = buffer.readFloats(4)
            assertContentEquals(data, result)
        }
    }

    @Test
    fun writeAndReadDoubleArray() {
        withScope { scope ->
            val buffer = scope.allocate(128, ByteOrder.BIG_ENDIAN)
            val data = doubleArrayOf(1.0, 2.5, 3.14159265358979, -100.0)

            buffer.writeDoubles(data)
            buffer.resetForRead()

            val result = buffer.readDoubles(4)
            assertContentEquals(data, result)
        }
    }

    @Test
    fun bulkIntArrayWithOffsetAndLength() {
        withScope { scope ->
            val buffer = scope.allocate(64, ByteOrder.BIG_ENDIAN)
            val data = intArrayOf(0, 0, 1, 2, 3, 4, 5, 0, 0, 0)

            buffer.writeInts(data, offset = 2, length = 5)
            buffer.resetForRead()

            val result = buffer.readInts(5)
            assertContentEquals(intArrayOf(1, 2, 3, 4, 5), result)
        }
    }

    @Test
    fun bulkOperationsAdvancePosition() {
        withScope { scope ->
            val buffer = scope.allocate(64, ByteOrder.BIG_ENDIAN)
            val ints = intArrayOf(1, 2, 3, 4)
            val shorts = shortArrayOf(5, 6, 7, 8)

            assertEquals(0, buffer.position())

            buffer.writeInts(ints)
            assertEquals(16, buffer.position()) // 4 ints * 4 bytes

            buffer.writeShorts(shorts)
            assertEquals(24, buffer.position()) // + 4 shorts * 2 bytes

            buffer.resetForRead()
            assertEquals(0, buffer.position())

            val readInts = buffer.readInts(4)
            assertEquals(16, buffer.position())

            val readShorts = buffer.readShorts(4)
            assertEquals(24, buffer.position())

            assertContentEquals(ints, readInts)
            assertContentEquals(shorts, readShorts)
        }
    }

    @Test
    fun largeBulkIntOperation() {
        // Test with a larger array to ensure the optimization works correctly
        withScope { scope ->
            val buffer = scope.allocate(4096, ByteOrder.BIG_ENDIAN)
            val size = 1000
            val data = IntArray(size) { it * 2 + 1 }

            buffer.writeInts(data)
            buffer.resetForRead()

            val result = buffer.readInts(size)
            assertContentEquals(data, result)
        }
    }

    // ===== Slice Memory Access Preservation Tests =====

    @Test
    fun scopedBufferSlicePreservesNativeMemoryAccess() {
        withScope { scope ->
            val buffer = scope.allocate(64)
            buffer.writeInt(0x11223344)
            buffer.writeInt(0x55667788)
            buffer.resetForRead()
            buffer.readInt() // skip first

            val nma = (buffer as ReadBuffer).nativeMemoryAccess
            assertNotNull(nma, "ScopedBuffer must have nativeMemoryAccess")

            val slice = buffer.slice()
            val sliceNma = slice.nativeMemoryAccess
            assertNotNull(sliceNma, "Slice of ScopedBuffer must preserve nativeMemoryAccess")
            assertTrue(sliceNma.nativeSize > 0, "Slice nativeSize must be > 0")
            assertEquals(0x55667788, slice.readInt())
        }
    }

    @Test
    fun scopedBufferDoubleSlicePreservesNativeMemoryAccess() {
        withScope { scope ->
            val buffer = scope.allocate(128)
            for (i in 0 until 32) buffer.writeInt(i)
            buffer.resetForRead()
            buffer.readInt() // skip first

            val slice1 = buffer.slice()
            val slice2 = slice1.slice()
            val sliceNma = slice2.nativeMemoryAccess
            assertNotNull(sliceNma, "Double-sliced ScopedBuffer must preserve nativeMemoryAccess")
            assertTrue(sliceNma.nativeSize > 0)
        }
    }
}
