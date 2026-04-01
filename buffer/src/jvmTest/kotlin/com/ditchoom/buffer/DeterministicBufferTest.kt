package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertIsNot
import kotlin.test.assertTrue

/**
 * JVM-specific tests for deterministic buffer types.
 */
class DeterministicBufferTest {
    @Test
    fun deterministicFactoryAllocatesCloseableBuffer() {
        val buffer = BufferFactory.deterministic().allocate(64)
        assertIs<CloseableBuffer>(buffer)
        assertIs<NativeMemoryAccess>(buffer)
        buffer.freeNativeMemory()
    }

    @Test
    fun deterministicBufferUseLifecycle() {
        var freed = false
        BufferFactory.deterministic().allocate(128).use { buffer ->
            buffer.writeInt(42)
            buffer.resetForRead()
            assertEquals(42, buffer.readInt())
            freed = false
        }
        // After use{}, the buffer should be freed
        // (we can't easily check freed flag from outside, but we can verify no crash)
    }

    @Test
    fun deterministicBufferUseWithException() {
        assertFailsWith<RuntimeException> {
            BufferFactory.deterministic().allocate(64).use { _ ->
                throw RuntimeException("test")
            }
        }
        // Buffer should still be freed even on exception
    }

    @Test
    fun deterministicBufferRoundTripAllPrimitives() {
        BufferFactory.deterministic().allocate(64).use { buffer ->
            buffer.writeByte(0x42)
            buffer.writeShort(0x1234.toShort())
            buffer.writeInt(0x12345678)
            buffer.writeLong(0x123456789ABCDEF0L)

            buffer.resetForRead()

            assertEquals(0x42.toByte(), buffer.readByte())
            assertEquals(0x1234.toShort(), buffer.readShort())
            assertEquals(0x12345678, buffer.readInt())
            assertEquals(0x123456789ABCDEF0L, buffer.readLong())
        }
    }

    @Test
    fun deterministicBufferBulkOperations() {
        BufferFactory.deterministic().allocate(256).use { buffer ->
            val data = ByteArray(100) { it.toByte() }
            buffer.writeBytes(data)
            buffer.resetForRead()
            val result = buffer.readByteArray(100)
            assertTrue(data.contentEquals(result))
        }
    }

    @Test
    fun deterministicBufferStringOperations() {
        BufferFactory.deterministic().allocate(256).use { buffer ->
            buffer.writeString("Hello, World!", Charset.UTF8)
            buffer.resetForRead()
            assertEquals("Hello, World!", buffer.readString(13, Charset.UTF8))
        }
    }

    @Test
    fun deterministicBufferSlice() {
        BufferFactory.deterministic().allocate(64).use { buffer ->
            buffer.writeInt(0x11111111)
            buffer.writeInt(0x22222222)
            buffer.writeInt(0x33333333)

            buffer.resetForRead()
            buffer.readInt() // skip first int

            val slice = buffer.slice()
            assertEquals(0x22222222, slice.readInt())
            assertEquals(0x33333333, slice.readInt())
        }
    }

    @Test
    fun deterministicBufferNativeAddress() {
        BufferFactory.deterministic().allocate(64).use { buffer ->
            val nma = buffer as NativeMemoryAccess
            assertTrue(nma.nativeAddress != 0L, "nativeAddress should not be 0")
            assertEquals(64L, nma.nativeSize)
        }
    }

    @Test
    fun deterministicBufferDoubleFreeIsSafe() {
        val buffer = BufferFactory.deterministic().allocate(64)
        buffer.freeNativeMemory()
        // Second free should be a no-op, not crash
        buffer.freeNativeMemory()
    }

    @Test
    fun deterministicBufferThrowsAfterFree() {
        val buffer = BufferFactory.deterministic().allocate(64)
        buffer.freeNativeMemory()
        // After free: DeterministicUnsafeJvmBuffer throws IllegalStateException,
        // FfmBuffer throws BufferUnderflowException (ByteBuffer limit=0)
        val threw =
            try {
                buffer.readByte()
                false
            } catch (_: Exception) {
                true
            }
        assertTrue(threw, "readByte() should throw after freeNativeMemory()")
    }

    @Test
    fun deterministicBufferRoundTripBigEndian() {
        BufferFactory.deterministic().allocate(64, ByteOrder.BIG_ENDIAN).use { buffer ->
            buffer.writeByte(0x42)
            buffer.writeShort(0x1234.toShort())
            buffer.writeInt(0x12345678)
            buffer.writeLong(0x123456789ABCDEF0L)

            buffer.resetForRead()

            assertEquals(0x42.toByte(), buffer.readByte())
            assertEquals(0x1234.toShort(), buffer.readShort())
            assertEquals(0x12345678, buffer.readInt())
            assertEquals(0x123456789ABCDEF0L, buffer.readLong())
        }
    }

    @Test
    fun deterministicBufferLittleEndian() {
        BufferFactory.deterministic().allocate(64, ByteOrder.LITTLE_ENDIAN).use { buffer ->
            buffer.writeShort(0x1234.toShort())
            buffer.writeInt(0x12345678)

            buffer.resetForRead()

            assertEquals(0x1234.toShort(), buffer.readShort())
            assertEquals(0x12345678, buffer.readInt())
        }
    }

    @Test
    fun copyBetweenDeterministicAndHeap() {
        BufferFactory.deterministic().allocate(64, ByteOrder.BIG_ENDIAN).use { det ->
            det.writeInt(0xDEADBEEF.toInt())
            det.writeInt(0xCAFEBABE.toInt())
            det.resetForRead()

            val heap = BufferFactory.managed().allocate(64)
            heap.write(det)
            heap.resetForRead()

            assertEquals(0xDEADBEEF.toInt(), heap.readInt())
            assertEquals(0xCAFEBABE.toInt(), heap.readInt())
        }
    }

    @Test
    fun copyBetweenHeapAndDeterministic() {
        val heap = BufferFactory.managed().allocate(64)
        heap.writeInt(0xDEADBEEF.toInt())
        heap.writeInt(0xCAFEBABE.toInt())
        heap.resetForRead()

        BufferFactory.deterministic().allocate(64, ByteOrder.BIG_ENDIAN).use { det ->
            det.write(heap)
            det.resetForRead()

            assertEquals(0xDEADBEEF.toInt(), det.readInt())
            assertEquals(0xCAFEBABE.toInt(), det.readInt())
        }
    }

    @Test
    fun copyBetweenDeterministicAndDirect() {
        BufferFactory.deterministic().allocate(64, ByteOrder.BIG_ENDIAN).use { det ->
            det.writeInt(0xDEADBEEF.toInt())
            det.resetForRead()

            val direct = BufferFactory.Default.allocate(64)
            direct.write(det)
            direct.resetForRead()

            assertEquals(0xDEADBEEF.toInt(), direct.readInt())
        }
    }

    // ========== Slice Lifecycle Tests ==========

    @Test
    fun sliceDoesNotImplementCloseableBuffer() {
        BufferFactory.deterministic().allocate(64).use { buffer ->
            buffer.writeInt(42)
            buffer.resetForRead()
            val slice = buffer.slice()
            assertIsNot<CloseableBuffer>(slice)
            assertIs<NativeMemoryAccess>(slice)
        }
    }

    @Test
    fun sliceNativeAddressThrowsAfterParentFree() {
        val buffer = BufferFactory.deterministic().allocate(64)
        buffer.writeInt(42)
        buffer.resetForRead()
        val slice = buffer.slice()
        val nma = slice as NativeMemoryAccess

        // Before free: nativeAddress works
        assertTrue(nma.nativeAddress != 0L)

        buffer.freeNativeMemory()

        // After parent free: nativeAddress throws
        assertFailsWith<IllegalStateException> {
            nma.nativeAddress
        }
    }

    @Test
    fun sliceNativeSizeThrowsAfterParentFree() {
        val buffer = BufferFactory.deterministic().allocate(64)
        buffer.writeInt(42)
        buffer.resetForRead()
        val slice = buffer.slice()
        val nma = slice as NativeMemoryAccess

        buffer.freeNativeMemory()

        assertFailsWith<IllegalStateException> {
            nma.nativeSize
        }
    }

    @Test
    fun sliceOfSliceNativeAddressThrowsAfterParentFree() {
        val buffer = BufferFactory.deterministic().allocate(64)
        buffer.writeInt(11)
        buffer.writeInt(22)
        buffer.writeInt(33)
        buffer.resetForRead()
        buffer.readInt() // skip first int

        val slice1 = buffer.slice()
        slice1.readInt() // skip second int
        val slice2 = slice1.slice()

        // Both slices work before free
        assertTrue((slice1 as NativeMemoryAccess).nativeAddress != 0L)
        assertTrue((slice2 as NativeMemoryAccess).nativeAddress != 0L)

        buffer.freeNativeMemory()

        // Both slices invalidated by parent free
        assertFailsWith<IllegalStateException> { (slice1 as NativeMemoryAccess).nativeAddress }
        assertFailsWith<IllegalStateException> { (slice2 as NativeMemoryAccess).nativeAddress }
    }

    @Test
    fun sliceFreeNativeMemoryIsNoOp() {
        BufferFactory.deterministic().allocate(64).use { buffer ->
            buffer.writeInt(42)
            buffer.resetForRead()
            val slice = buffer.slice() as PlatformBuffer

            // freeNativeMemory on slice is no-op
            slice.freeNativeMemory()

            // Parent still works
            assertEquals(42, buffer.readInt())
        }
    }

    @Test
    fun multipleSlicesAllInvalidatedByParentFree() {
        val buffer = BufferFactory.deterministic().allocate(64)
        buffer.writeInt(11)
        buffer.writeInt(22)
        buffer.resetForRead()

        val slice1 = buffer.slice()
        buffer.readInt() // advance
        val slice2 = buffer.slice()

        buffer.freeNativeMemory()

        assertFailsWith<IllegalStateException> { (slice1 as NativeMemoryAccess).nativeAddress }
        assertFailsWith<IllegalStateException> { (slice2 as NativeMemoryAccess).nativeAddress }
    }

    @Test
    fun byteBufferGuardProtectsAllOperationsAfterFree() {
        val buffer = BufferFactory.deterministic().allocate(64)
        buffer.writeInt(42)
        buffer.resetForRead()
        buffer.freeNativeMemory()

        // All data operations should throw (guarded by byteBuffer override)
        assertFailsWith<IllegalStateException> { buffer.readByte() }
        assertFailsWith<IllegalStateException> { buffer.readShort() }
        assertFailsWith<IllegalStateException> { buffer.readInt() }
        assertFailsWith<IllegalStateException> { buffer.readLong() }
        assertFailsWith<IllegalStateException> { buffer.readByteArray(1) }
        assertFailsWith<IllegalStateException> { buffer.readString(1) }
        assertFailsWith<IllegalStateException> { buffer.writeByte(0) }
        assertFailsWith<IllegalStateException> { buffer.writeShort(0) }
        assertFailsWith<IllegalStateException> { buffer.writeInt(0) }
        assertFailsWith<IllegalStateException> { buffer.writeLong(0) }
        assertFailsWith<IllegalStateException> { buffer.writeBytes(byteArrayOf(1)) }
        assertFailsWith<IllegalStateException> { buffer.writeString("x") }

        // Metadata operations still work (for diagnostics)
        assertFalse(buffer.position() < 0) // doesn't throw
    }

    @Test
    fun nativeAddressAndSizeGuardedAfterFree() {
        val buffer = BufferFactory.deterministic().allocate(64)
        buffer.freeNativeMemory()

        assertFailsWith<IllegalStateException> { (buffer as NativeMemoryAccess).nativeAddress }
        assertFailsWith<IllegalStateException> { (buffer as NativeMemoryAccess).nativeSize }
    }

    // ========== Slice nativeAddress correctness ==========

    @Test
    fun sliceNativeAddressPointsToSliceNotParent() {
        BufferFactory.deterministic().allocate(64).use { buffer ->
            val parentNma = buffer as NativeMemoryAccess
            val parentAddr = parentNma.nativeAddress

            // Write data, advance position past 4 bytes, then slice
            buffer.writeInt(0x11111111)
            buffer.writeInt(0x22222222)
            buffer.resetForRead()
            buffer.readInt() // position = 4

            val slice = buffer.slice()
            val sliceNma = slice as NativeMemoryAccess

            // Slice address must be parent + 4, NOT parent base
            assertEquals(parentAddr + 4, sliceNma.nativeAddress)
        }
    }

    @Test
    fun sliceOfSliceNativeAddressIsCorrect() {
        BufferFactory.deterministic().allocate(64).use { buffer ->
            val parentAddr = (buffer as NativeMemoryAccess).nativeAddress

            buffer.writeInt(0x11111111) // bytes 0-3
            buffer.writeInt(0x22222222) // bytes 4-7
            buffer.writeInt(0x33333333) // bytes 8-11
            buffer.resetForRead()
            buffer.readInt() // position = 4

            val slice1 = buffer.slice() // starts at byte 4
            assertEquals(parentAddr + 4, (slice1 as NativeMemoryAccess).nativeAddress)

            slice1.readInt() // position = 4 within slice1 (byte 8 absolute)
            val slice2 = slice1.slice() // starts at byte 8

            assertEquals(parentAddr + 8, (slice2 as NativeMemoryAccess).nativeAddress)
        }
    }

    @Test
    fun sliceNativeAddressConsistentWithDataRead() {
        BufferFactory.deterministic().allocate(64).use { buffer ->
            // Write a known pattern at offset 16
            repeat(4) { buffer.writeInt(0) } // fill bytes 0-15 with zeros
            buffer.writeInt(0xDEADBEEF.toInt()) // bytes 16-19

            buffer.resetForRead()
            repeat(4) { buffer.readInt() } // advance to position 16

            val slice = buffer.slice()
            // Verify the slice reads the correct data (the data at its nativeAddress)
            assertEquals(0xDEADBEEF.toInt(), slice.readInt())

            // And the nativeAddress is offset correctly from parent
            val parentAddr = (buffer as NativeMemoryAccess).nativeAddress
            assertEquals(parentAddr + 16, (slice as NativeMemoryAccess).nativeAddress)
        }
    }

    // ========== Slice data operations guarded after parent free ==========

    @Test
    fun sliceDataOperationsThrowAfterParentFree() {
        val buffer = BufferFactory.deterministic().allocate(64)
        buffer.writeInt(42)
        buffer.resetForRead()
        val slice = buffer.slice()

        // Slice data operations work before parent free
        assertEquals(42, slice.readInt())

        buffer.freeNativeMemory()

        // All data operations on slice should throw after parent free
        assertFailsWith<IllegalStateException> { slice.readByte() }
        assertFailsWith<IllegalStateException> { slice.readShort() }
        assertFailsWith<IllegalStateException> { slice.readInt() }
        assertFailsWith<IllegalStateException> { slice.readLong() }
        val writeSlice = slice as PlatformBuffer
        assertFailsWith<IllegalStateException> { writeSlice.writeByte(0) }
        assertFailsWith<IllegalStateException> { writeSlice.writeInt(0) }
    }

    @Test
    fun sliceOfSliceDataOperationsThrowAfterParentFree() {
        val buffer = BufferFactory.deterministic().allocate(64)
        buffer.writeInt(11)
        buffer.writeInt(22)
        buffer.writeInt(33)
        buffer.resetForRead()
        buffer.readInt()

        val slice1 = buffer.slice()
        slice1.readInt()
        val slice2 = slice1.slice()

        buffer.freeNativeMemory()

        assertFailsWith<IllegalStateException> { slice1.readInt() }
        assertFailsWith<IllegalStateException> { slice2.readInt() }
    }
}
