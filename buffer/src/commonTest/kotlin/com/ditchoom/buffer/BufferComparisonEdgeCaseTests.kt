package com.ditchoom.buffer

import com.ditchoom.buffer.pool.withPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Edge case tests for content-based buffer equality and comparison.
 * Validates the equals/hashCode unification (0352ee3) across buffer types.
 */
class BufferComparisonEdgeCaseTests {
    // ============================================================================
    // Empty Buffer Equality
    // ============================================================================

    @Test
    fun emptyBuffersAreEqual() {
        val a = BufferFactory.Default.allocate(0)
        a.resetForRead()
        val b = BufferFactory.Default.allocate(0)
        b.resetForRead()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun emptyBufferEqualsEmptyBuffer() {
        val a = ReadBuffer.EMPTY_BUFFER
        val b = BufferFactory.Default.allocate(0)
        b.resetForRead()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun emptyBufferEqualsItself() {
        val buf = ReadBuffer.EMPTY_BUFFER
        assertEquals(buf, buf)
    }

    @Test
    fun emptyBufferNotEqualToNonEmpty() {
        val empty = BufferFactory.Default.allocate(0)
        empty.resetForRead()
        val nonEmpty = BufferFactory.Default.allocate(1)
        nonEmpty.writeByte(0x42)
        nonEmpty.resetForRead()
        assertNotEquals(empty, nonEmpty)
    }

    // ============================================================================
    // Single-Byte Mismatch
    // ============================================================================

    @Test
    fun singleByteMismatchAtPosition0() {
        val a = BufferFactory.Default.allocate(8)
        val b = BufferFactory.Default.allocate(8)
        for (i in 0 until 8) {
            a.writeByte(i.toByte())
            b.writeByte(i.toByte())
        }
        a.resetForRead()
        b.resetForRead()

        // Modify first byte in b
        b.position(0)
        b.set(0, 0xFF.toByte())
        b.position(0)

        assertFalse(a.contentEquals(b))
        assertEquals(0, a.mismatch(b))
    }

    @Test
    fun singleByteMismatchAtLastPosition() {
        val size = 16
        val a = BufferFactory.Default.allocate(size)
        val b = BufferFactory.Default.allocate(size)
        for (i in 0 until size) {
            a.writeByte(i.toByte())
            b.writeByte(i.toByte())
        }
        a.resetForRead()
        b.resetForRead()

        // Modify last byte in b
        b.set(size - 1, 0xFF.toByte())

        assertFalse(a.contentEquals(b))
        assertEquals(size - 1, a.mismatch(b))
    }

    @Test
    fun identicalBuffersMismatchReturnsNegativeOne() {
        val a = BufferFactory.Default.allocate(32)
        val b = BufferFactory.Default.allocate(32)
        for (i in 0 until 32) {
            a.writeByte(i.toByte())
            b.writeByte(i.toByte())
        }
        a.resetForRead()
        b.resetForRead()
        assertTrue(a.contentEquals(b))
        assertEquals(-1, a.mismatch(b))
    }

    // ============================================================================
    // Slice Equality
    // ============================================================================

    @Test
    fun sliceEqualsOriginalContent() {
        val buf = BufferFactory.Default.allocate(10)
        for (i in 0 until 10) buf.writeByte((i + 1).toByte())
        buf.resetForRead()

        // Skip first 3 bytes, take 4 bytes
        buf.position(3)
        val oldLimit = buf.limit()
        buf.setLimit(7)
        val slice = buf.slice()
        buf.setLimit(oldLimit)

        // Create equivalent buffer
        val expected = BufferFactory.Default.allocate(4)
        expected.writeByte(4)
        expected.writeByte(5)
        expected.writeByte(6)
        expected.writeByte(7)
        expected.resetForRead()

        assertTrue(slice.contentEquals(expected))
    }

    @Test
    fun sliceEqualityAcrossDifferentBufferTypes() {
        // wrap() uses HeapJvmBuffer, allocate() uses DirectJvmBuffer (on JVM)
        val heapData = byteArrayOf(1, 2, 3, 4, 5)
        val heap = BufferFactory.Default.wrap(heapData)
        // wrap() already returns buffer in read mode (position=0, limit=size)

        val direct = BufferFactory.Default.allocate(5)
        direct.writeBytes(heapData)
        direct.resetForRead()

        assertTrue(heap.contentEquals(direct))
        assertEquals(heap, direct)
        assertEquals(heap.hashCode(), direct.hashCode())
    }

    // ============================================================================
    // Wrapper Equality (PooledBuffer / TrackedSlice)
    // ============================================================================

    @Test
    fun pooledBufferEqualsUnwrappedBuffer() {
        withPool(defaultBufferSize = 64) { pool ->
            val pooled = pool.acquire(8)
            // Write raw bytes to avoid byte order differences
            pooled.writeBytes(byteArrayOf(0x11, 0x22, 0x33, 0x44))
            pooled.resetForRead()

            val plain = BufferFactory.Default.wrap(byteArrayOf(0x11, 0x22, 0x33, 0x44))

            assertEquals(pooled, plain)
            assertEquals(plain, pooled)

            pool.release(pooled)
        }
    }

    @Test
    fun trackedSliceEqualsPlainBuffer() {
        withPool(defaultBufferSize = 64) { pool ->
            val pooled = pool.acquire(16)
            // Write raw bytes to avoid byte order differences
            pooled.writeBytes(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte()))
            pooled.resetForRead()

            // readBytes() on PooledBuffer returns TrackedSlice
            val slice = pooled.readBytes(4)

            val plain = BufferFactory.Default.wrap(byteArrayOf(0x11, 0x22, 0x33, 0x44))

            assertEquals(slice, plain)

            pool.release(pooled)
        }
    }

    // ============================================================================
    // Content-Based HashCode Consistency
    // ============================================================================

    @Test
    fun hashCodeConsistentForSameContent() {
        val a = BufferFactory.Default.allocate(8)
        val b = BufferFactory.Default.allocate(8)
        a.writeLong(0x0102030405060708L)
        b.writeLong(0x0102030405060708L)
        a.resetForRead()
        b.resetForRead()
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun hashCodeDiffersForDifferentContent() {
        val a = BufferFactory.Default.allocate(4)
        val b = BufferFactory.Default.allocate(4)
        a.writeInt(1)
        b.writeInt(2)
        a.resetForRead()
        b.resetForRead()
        // Not guaranteed but highly likely
        assertNotEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun hashCodeStableAcrossReads() {
        val buf = BufferFactory.Default.allocate(4)
        buf.writeInt(42)
        buf.resetForRead()
        val hash1 = buf.hashCode()
        val hash2 = buf.hashCode()
        assertEquals(hash1, hash2)
    }

    // ============================================================================
    // ContentEquals Edge Cases
    // ============================================================================

    @Test
    fun contentEqualsWithDifferentPositions() {
        // Buffer a: [1,2,3,4] at position 0
        // Buffer b: [0,0,1,2,3,4] at position 2
        // remaining bytes should be equal
        val a = BufferFactory.Default.allocate(4)
        a.writeByte(1)
        a.writeByte(2)
        a.writeByte(3)
        a.writeByte(4)
        a.resetForRead()

        val b = BufferFactory.Default.allocate(6)
        b.writeByte(0)
        b.writeByte(0)
        b.writeByte(1)
        b.writeByte(2)
        b.writeByte(3)
        b.writeByte(4)
        b.resetForRead()
        b.position(2)

        assertTrue(a.contentEquals(b))
    }

    @Test
    fun contentEqualsWithDifferentCapacitySameRemaining() {
        val small = BufferFactory.Default.allocate(4)
        small.writeInt(0xDEADBEEF.toInt())
        small.resetForRead()

        val large = BufferFactory.Default.allocate(1024)
        large.writeInt(0xDEADBEEF.toInt())
        large.resetForRead()
        large.setLimit(4)

        assertTrue(small.contentEquals(large))
    }

    @Test
    fun bufferNotEqualToNonBuffer() {
        val buf = BufferFactory.Default.allocate(4)
        buf.writeInt(42)
        buf.resetForRead()
        assertFalse(bufferEquals(buf, "not a buffer"))
        assertFalse(bufferEquals(buf, 42))
        assertFalse(bufferEquals(buf, null))
    }

    // ============================================================================
    // Mismatch Edge Cases
    // ============================================================================

    @Test
    fun mismatchBothEmpty() {
        val a = BufferFactory.Default.allocate(0)
        a.resetForRead()
        val b = BufferFactory.Default.allocate(0)
        b.resetForRead()
        assertEquals(-1, a.mismatch(b))
    }

    @Test
    fun mismatchOneEmptyOneNot() {
        val empty = BufferFactory.Default.allocate(0)
        empty.resetForRead()
        val nonEmpty = BufferFactory.Default.allocate(4)
        nonEmpty.writeInt(1)
        nonEmpty.resetForRead()
        assertEquals(0, empty.mismatch(nonEmpty))
        assertEquals(0, nonEmpty.mismatch(empty))
    }

    @Test
    fun mismatchDifferentLengthsSamePrefix() {
        val short = BufferFactory.Default.allocate(4)
        short.writeInt(0x01020304)
        short.resetForRead()

        val long = BufferFactory.Default.allocate(8)
        long.writeInt(0x01020304)
        long.writeInt(0x05060708)
        long.resetForRead()

        assertEquals(4, short.mismatch(long))
    }
}
