package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.PoolReleasable
import com.ditchoom.buffer.pool.withPool
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests that buffer operations work correctly through PooledBuffer and TrackedSlice wrappers.
 *
 * PooledBuffer wraps a PlatformBuffer via delegation and is returned by BufferPool.acquire().
 * TrackedSlice wraps a ReadBuffer via delegation and is returned by PooledBuffer.slice().
 * These tests verify that operations like write(), contentEquals(), mismatch(), xorMaskCopy(),
 * toByteArray(), toNativeData(), and memory access extensions work transparently through wrappers.
 */
class WrapperTransparencyTests {
    private fun createPool(): BufferPool = BufferPool()

    // ============================================================================
    // write() from wrapper into platform buffer
    // ============================================================================

    @Test
    fun writeFromPooledBufferIntoPlatformBuffer() {
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeInt(0x12345678)
        pooled.writeInt(0xDEADBEEF.toInt())
        pooled.resetForRead()

        val dest = PlatformBuffer.allocate(8)
        dest.write(pooled)
        dest.resetForRead()

        assertEquals(0x12345678, dest.readInt(), "First int should match")
        assertEquals(0xDEADBEEF.toInt(), dest.readInt(), "Second int should match")
        pool.release(pooled)
        pool.clear()
    }

    @Test
    fun writeFromTrackedSliceIntoPlatformBuffer() {
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeInt(0xCAFEBABE.toInt())
        pooled.writeLong(0x0102030405060708L)
        pooled.resetForRead()

        val slice = pooled.slice()
        val dest = PlatformBuffer.allocate(12)
        dest.write(slice)
        dest.resetForRead()

        assertEquals(0xCAFEBABE.toInt(), dest.readInt(), "Int from slice should match")
        assertEquals(0x0102030405060708L, dest.readLong(), "Long from slice should match")

        (slice as? PoolReleasable)?.releaseToPool()
        pool.release(pooled)
        pool.clear()
    }

    // ============================================================================
    // contentEquals() between platform buffer and wrapper
    // ============================================================================

    @Test
    fun contentEqualsBetweenPlatformBufferAndPooledBuffer() {
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeInt(0x11223344)
        pooled.writeInt(0x55667788)
        pooled.resetForRead()

        val plain = PlatformBuffer.allocate(8)
        plain.writeInt(0x11223344)
        plain.writeInt(0x55667788)
        plain.resetForRead()

        assertTrue(plain.contentEquals(pooled), "plain.contentEquals(pooled) should be true")
        assertTrue(pooled.contentEquals(plain), "pooled.contentEquals(plain) should be true")

        pool.release(pooled)
        pool.clear()
    }

    @Test
    fun contentEqualsBetweenPlatformBufferAndTrackedSlice() {
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeShort(0x1234.toShort())
        pooled.writeShort(0x5678.toShort())
        pooled.resetForRead()

        val slice = pooled.slice()

        val plain = PlatformBuffer.allocate(4)
        plain.writeShort(0x1234.toShort())
        plain.writeShort(0x5678.toShort())
        plain.resetForRead()

        assertTrue(plain.contentEquals(slice), "plain.contentEquals(slice) should be true")
        assertTrue(slice.contentEquals(plain), "slice.contentEquals(plain) should be true")

        (slice as? PoolReleasable)?.releaseToPool()
        pool.release(pooled)
        pool.clear()
    }

    @Test
    fun contentEqualsReturnsFalseWhenDifferent() {
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeInt(0xAAAAAAAA.toInt())
        pooled.resetForRead()

        val plain = PlatformBuffer.allocate(4)
        plain.writeInt(0xBBBBBBBB.toInt())
        plain.resetForRead()

        assertFalse(plain.contentEquals(pooled), "Different buffers should not be equal")
        assertFalse(pooled.contentEquals(plain), "Different buffers should not be equal (reverse)")

        pool.release(pooled)
        pool.clear()
    }

    // ============================================================================
    // mismatch() between platform buffer and wrapper
    // ============================================================================

    @Test
    fun mismatchReturnsNegativeOneWhenPooledBufferMatchesPlatformBuffer() {
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        pooled.resetForRead()

        val plain = PlatformBuffer.allocate(8)
        plain.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        plain.resetForRead()

        assertEquals(-1, plain.mismatch(pooled), "Identical contents should return -1")
        assertEquals(-1, pooled.mismatch(plain), "Identical contents should return -1 (reverse)")

        pool.release(pooled)
        pool.clear()
    }

    @Test
    fun mismatchReturnsCorrectIndexWhenPooledBufferDiffers() {
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeBytes(byteArrayOf(1, 2, 3, 99, 5, 6, 7, 8))
        pooled.resetForRead()

        val plain = PlatformBuffer.allocate(8)
        plain.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        plain.resetForRead()

        assertEquals(3, plain.mismatch(pooled), "Mismatch should be at index 3")
        assertEquals(3, pooled.mismatch(plain), "Mismatch should be at index 3 (reverse)")

        pool.release(pooled)
        pool.clear()
    }

    @Test
    fun mismatchWithTrackedSliceMatchingContent() {
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeBytes(byteArrayOf(10, 20, 30, 40))
        pooled.resetForRead()

        val slice = pooled.slice()

        val plain = PlatformBuffer.allocate(4)
        plain.writeBytes(byteArrayOf(10, 20, 30, 40))
        plain.resetForRead()

        assertEquals(-1, plain.mismatch(slice), "Identical slice content should return -1")
        assertEquals(-1, slice.mismatch(plain), "Identical slice content should return -1 (reverse)")

        (slice as? PoolReleasable)?.releaseToPool()
        pool.release(pooled)
        pool.clear()
    }

    @Test
    fun mismatchWithTrackedSliceDifferingContent() {
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeBytes(byteArrayOf(10, 20, 99, 40))
        pooled.resetForRead()

        val slice = pooled.slice()

        val plain = PlatformBuffer.allocate(4)
        plain.writeBytes(byteArrayOf(10, 20, 30, 40))
        plain.resetForRead()

        assertEquals(2, plain.mismatch(slice), "Mismatch should be at index 2")
        assertEquals(2, slice.mismatch(plain), "Mismatch should be at index 2 (reverse)")

        (slice as? PoolReleasable)?.releaseToPool()
        pool.release(pooled)
        pool.clear()
    }

    // ============================================================================
    // xorMaskCopy() from wrapper source
    // ============================================================================

    @Test
    fun xorMaskCopyFromPooledBufferSource() {
        val mask = 0xCAFEBABE.toInt()
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeBytes(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
        pooled.resetForRead()

        val dest = PlatformBuffer.allocate(8)
        dest.xorMaskCopy(pooled, mask)
        dest.resetForRead()

        // XOR of 0x00 with mask bytes should produce the mask bytes themselves
        val expected = PlatformBuffer.allocate(8)
        expected.writeBytes(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
        expected.resetForRead()
        expected.xorMask(mask)

        val destBytes = ByteArray(8)
        for (i in 0 until 8) destBytes[i] = dest.readByte()
        val expectedBytes = ByteArray(8)
        for (i in 0 until 8) expectedBytes[i] = expected.readByte()

        assertContentEquals(expectedBytes, destBytes, "xorMaskCopy from PooledBuffer should match xorMask result")

        pool.release(pooled)
        pool.clear()
    }

    @Test
    fun xorMaskCopyFromTrackedSliceSource() {
        val mask = 0x12345678
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeBytes(byteArrayOf(0x11, 0x22, 0x33, 0x44))
        pooled.resetForRead()

        val slice = pooled.slice()

        val dest = PlatformBuffer.allocate(4)
        dest.xorMaskCopy(slice, mask)
        dest.resetForRead()

        // Manually compute expected XOR result
        val maskBytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val sourceBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val expectedBytes = ByteArray(4) { i -> (sourceBytes[i].toInt() xor maskBytes[i].toInt()).toByte() }

        val destBytes = ByteArray(4)
        for (i in 0 until 4) destBytes[i] = dest.readByte()

        assertContentEquals(expectedBytes, destBytes, "xorMaskCopy from TrackedSlice should produce correct XOR")

        (slice as? PoolReleasable)?.releaseToPool()
        pool.release(pooled)
        pool.clear()
    }

    // ============================================================================
    // toByteArray() on wrappers
    // ============================================================================

    @Test
    fun toByteArrayOnPooledBuffer() {
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        pooled.resetForRead()

        val result = pooled.toByteArray()
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), result, "toByteArray on PooledBuffer")

        pool.release(pooled)
        pool.clear()
    }

    @Test
    fun toByteArrayOnTrackedSlice() {
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeBytes(byteArrayOf(10, 20, 30, 40, 50))
        pooled.resetForRead()

        val slice = pooled.slice()
        val result = slice.toByteArray()
        assertContentEquals(byteArrayOf(10, 20, 30, 40, 50), result, "toByteArray on TrackedSlice")

        (slice as? PoolReleasable)?.releaseToPool()
        pool.release(pooled)
        pool.clear()
    }

    @Test
    fun toByteArrayOnPooledBufferWithAdvancedPosition() {
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        pooled.resetForRead()
        pooled.readByte() // advance position to 1
        pooled.readByte() // advance position to 2

        val result = pooled.toByteArray()
        assertContentEquals(byteArrayOf(3, 4, 5), result, "toByteArray should return remaining bytes")

        pool.release(pooled)
        pool.clear()
    }

    // ============================================================================
    // toNativeData() on wrappers
    // ============================================================================

    @Test
    fun toNativeDataOnPooledBuffer() {
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeBytes(byteArrayOf(1, 2, 3, 4))
        pooled.resetForRead()

        // toNativeData() should not throw and should not modify position
        val positionBefore = pooled.position()
        val nativeData = pooled.toNativeData()
        assertNotNull(nativeData, "toNativeData should return non-null")
        assertEquals(positionBefore, pooled.position(), "toNativeData should not modify position")

        pool.release(pooled)
        pool.clear()
    }

    @Test
    fun toNativeDataOnTrackedSlice() {
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeBytes(byteArrayOf(5, 6, 7, 8))
        pooled.resetForRead()

        val slice = pooled.slice()
        val positionBefore = slice.position()
        val nativeData = slice.toNativeData()
        assertNotNull(nativeData, "toNativeData on TrackedSlice should return non-null")
        assertEquals(positionBefore, slice.position(), "toNativeData should not modify slice position")

        (slice as? PoolReleasable)?.releaseToPool()
        pool.release(pooled)
        pool.clear()
    }

    // ============================================================================
    // nativeMemoryAccess / managedMemoryAccess through wrappers
    // ============================================================================

    @Test
    fun nativeMemoryAccessThroughPooledBufferDirect() {
        val pool = BufferPool(defaultBufferSize = 16, allocationZone = AllocationZone.Direct)
        val pooled = pool.acquire(16)
        pooled.writeInt(42)
        pooled.resetForRead()

        // Direct-allocated buffers should have NativeMemoryAccess
        val nma = (pooled as WriteBuffer).nativeMemoryAccess
        assertNotNull(nma, "Direct PooledBuffer should expose NativeMemoryAccess")
        assertTrue(nma.nativeSize > 0, "nativeSize should be positive")

        pool.release(pooled)
        pool.clear()
    }

    @Test
    fun nativeMemoryAccessThroughTrackedSliceDirect() {
        val pool = BufferPool(defaultBufferSize = 16, allocationZone = AllocationZone.Direct)
        val pooled = pool.acquire(16)
        pooled.writeInt(42)
        pooled.resetForRead()

        val slice = pooled.slice()
        val nma = slice.nativeMemoryAccess
        assertNotNull(nma, "TrackedSlice from Direct buffer should expose NativeMemoryAccess")
        assertTrue(nma.nativeSize > 0, "nativeSize should be positive")

        (slice as? PoolReleasable)?.releaseToPool()
        pool.release(pooled)
        pool.clear()
    }

    @Test
    fun managedMemoryAccessThroughPooledBufferHeap() {
        val pool = BufferPool(defaultBufferSize = 16, allocationZone = AllocationZone.Heap)
        val pooled = pool.acquire(16)
        pooled.writeBytes(byteArrayOf(1, 2, 3))
        pooled.resetForRead()

        // Heap-allocated buffers should have ManagedMemoryAccess on all platforms.
        val mma = (pooled as WriteBuffer).managedMemoryAccess
        assertNotNull(mma, "Heap PooledBuffer should expose ManagedMemoryAccess")

        pool.release(pooled)
        pool.clear()
    }

    @Test
    fun managedMemoryAccessThroughTrackedSliceHeap() {
        val pool = BufferPool(defaultBufferSize = 16, allocationZone = AllocationZone.Heap)
        val pooled = pool.acquire(16)
        pooled.writeBytes(byteArrayOf(1, 2, 3))
        pooled.resetForRead()

        val slice = pooled.slice()
        val mma = slice.managedMemoryAccess
        assertNotNull(mma, "TrackedSlice from Heap buffer should expose ManagedMemoryAccess")

        (slice as? PoolReleasable)?.releaseToPool()
        pool.release(pooled)
        pool.clear()
    }

    // ============================================================================
    // Partial slice (non-zero position) variants
    // ============================================================================

    @Test
    fun partialSliceContentEqualsWithOffset() {
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeBytes(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 1, 2, 3, 4))
        pooled.resetForRead()
        pooled.readByte() // position = 1
        pooled.readByte() // position = 2

        // slice() captures [position, limit) = bytes {1, 2, 3, 4}
        val slice = pooled.slice()

        val plain = PlatformBuffer.allocate(4)
        plain.writeBytes(byteArrayOf(1, 2, 3, 4))
        plain.resetForRead()

        assertTrue(plain.contentEquals(slice), "Partial slice should contentEquals matching buffer")
        assertTrue(slice.contentEquals(plain), "Partial slice contentEquals should be symmetric")

        (slice as? PoolReleasable)?.releaseToPool()
        pool.release(pooled)
        pool.clear()
    }

    @Test
    fun partialSliceMismatchWithOffset() {
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeBytes(byteArrayOf(0xFF.toByte(), 10, 20, 99, 40))
        pooled.resetForRead()
        pooled.readByte() // skip first byte, position = 1

        // slice() captures [position, limit) = bytes {10, 20, 99, 40}
        val slice = pooled.slice()

        val plain = PlatformBuffer.allocate(4)
        plain.writeBytes(byteArrayOf(10, 20, 30, 40))
        plain.resetForRead()

        assertEquals(2, plain.mismatch(slice), "Mismatch at relative index 2 in partial slice")
        assertEquals(2, slice.mismatch(plain), "Mismatch at relative index 2 (reverse)")

        (slice as? PoolReleasable)?.releaseToPool()
        pool.release(pooled)
        pool.clear()
    }

    @Test
    fun partialSliceWriteIntoDestBuffer() {
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeBytes(byteArrayOf(0x00, 0x00, 0x11, 0x22, 0x33))
        pooled.resetForRead()
        pooled.readByte() // position = 1
        pooled.readByte() // position = 2

        // slice() captures bytes {0x11, 0x22, 0x33}
        val slice = pooled.slice()

        val dest = PlatformBuffer.allocate(3)
        dest.write(slice)
        dest.resetForRead()

        assertEquals(0x11.toByte(), dest.readByte(), "First byte from partial slice")
        assertEquals(0x22.toByte(), dest.readByte(), "Second byte from partial slice")
        assertEquals(0x33.toByte(), dest.readByte(), "Third byte from partial slice")

        (slice as? PoolReleasable)?.releaseToPool()
        pool.release(pooled)
        pool.clear()
    }

    @Test
    fun partialSliceToByteArrayWithOffset() {
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeBytes(byteArrayOf(0, 0, 0, 7, 8, 9))
        pooled.resetForRead()
        pooled.readByte() // skip
        pooled.readByte() // skip
        pooled.readByte() // skip, position = 3

        val slice = pooled.slice()
        val result = slice.toByteArray()
        assertContentEquals(byteArrayOf(7, 8, 9), result, "toByteArray on partial slice")

        (slice as? PoolReleasable)?.releaseToPool()
        pool.release(pooled)
        pool.clear()
    }

    @Test
    fun partialSliceXorMaskCopyWithOffset() {
        val mask = 0xAABBCCDD.toInt()
        val pool = createPool()
        val pooled = pool.acquire(16)
        pooled.writeBytes(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x11, 0x22, 0x33, 0x44))
        pooled.resetForRead()
        pooled.readByte() // skip
        pooled.readByte() // skip, position = 2

        // slice() captures bytes {0x11, 0x22, 0x33, 0x44}
        val slice = pooled.slice()

        val dest = PlatformBuffer.allocate(4)
        dest.xorMaskCopy(slice, mask)
        dest.resetForRead()

        // Expected: each byte XORed with mask bytes
        val maskBytes =
            byteArrayOf(
                (mask ushr 24).toByte(),
                (mask ushr 16).toByte(),
                (mask ushr 8).toByte(),
                mask.toByte(),
            )
        val sourceBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val expected = ByteArray(4) { i -> (sourceBytes[i].toInt() xor maskBytes[i].toInt()).toByte() }

        val actual = ByteArray(4) { dest.readByte() }
        assertContentEquals(expected, actual, "xorMaskCopy from partial TrackedSlice")

        (slice as? PoolReleasable)?.releaseToPool()
        pool.release(pooled)
        pool.clear()
    }

    // ============================================================================
    // withPool convenience pattern
    // ============================================================================

    @Test
    fun writeFromPooledBufferUsingWithPool() =
        withPool(defaultBufferSize = 32) { pool ->
            val pooled = pool.acquire(8)
            pooled.writeLong(0x0102030405060708L)
            pooled.resetForRead()

            val dest = PlatformBuffer.allocate(8)
            dest.write(pooled)
            dest.resetForRead()

            assertEquals(0x0102030405060708L, dest.readLong(), "Long value through withPool should match")
            pool.release(pooled)
        }

    @Test
    fun contentEqualsBetweenTwoPooledBuffers() {
        val pool = createPool()
        val pooled1 = pool.acquire(16)
        pooled1.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        pooled1.resetForRead()

        val pooled2 = pool.acquire(16)
        pooled2.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        pooled2.resetForRead()

        assertTrue(pooled1.contentEquals(pooled2), "Two PooledBuffers with same data should be equal")

        pool.release(pooled1)
        pool.release(pooled2)
        pool.clear()
    }

    @Test
    fun mismatchBetweenTwoPooledBuffers() {
        val pool = createPool()
        val pooled1 = pool.acquire(16)
        pooled1.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        pooled1.resetForRead()

        val pooled2 = pool.acquire(16)
        pooled2.writeBytes(byteArrayOf(1, 2, 99, 4, 5))
        pooled2.resetForRead()

        assertEquals(2, pooled1.mismatch(pooled2), "Mismatch between two PooledBuffers at index 2")

        pool.release(pooled1)
        pool.release(pooled2)
        pool.clear()
    }

    @Test
    fun xorMaskCopyFromPooledBufferIntoPooledBuffer() {
        val mask = 0x11223344
        val pool = createPool()

        val src = pool.acquire(16)
        src.writeBytes(byteArrayOf(0, 0, 0, 0))
        src.resetForRead()

        val dst = pool.acquire(16)
        dst.xorMaskCopy(src, mask)
        dst.resetForRead()

        // XOR of zeros with mask yields mask bytes
        val maskBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val actual = ByteArray(4) { dst.readByte() }
        assertContentEquals(maskBytes, actual, "xorMaskCopy between PooledBuffers should work")

        pool.release(src)
        pool.release(dst)
        pool.clear()
    }
}
