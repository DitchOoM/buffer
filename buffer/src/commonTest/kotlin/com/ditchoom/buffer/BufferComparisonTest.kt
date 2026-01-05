package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the bulk comparison utility functions in BufferComparison.kt
 * These functions are used internally for optimized buffer operations.
 */
class BufferComparisonTest {

    // region bulkCompareEquals tests

    @Test
    fun bulkCompareEqualsEmptyBuffers() {
        val result = bulkCompareEquals(
            thisPos = 0,
            otherPos = 0,
            length = 0,
            getLong = { 0L },
            otherGetLong = { 0L },
            getByte = { 0 },
            otherGetByte = { 0 }
        )
        assertTrue(result, "Empty buffers should be equal")
    }

    @Test
    fun bulkCompareEqualsIdenticalSmallBuffers() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val result = bulkCompareEquals(
            thisPos = 0,
            otherPos = 0,
            length = 5,
            getLong = { pos -> data.sliceArray(pos until minOf(pos + 8, data.size)).toLong() },
            otherGetLong = { pos -> data.sliceArray(pos until minOf(pos + 8, data.size)).toLong() },
            getByte = { pos -> data[pos] },
            otherGetByte = { pos -> data[pos] }
        )
        assertTrue(result, "Identical small buffers should be equal")
    }

    @Test
    fun bulkCompareEqualsDifferentSmallBuffers() {
        val data1 = byteArrayOf(1, 2, 3, 4, 5)
        val data2 = byteArrayOf(1, 2, 9, 4, 5)
        val result = bulkCompareEquals(
            thisPos = 0,
            otherPos = 0,
            length = 5,
            getLong = { pos -> data1.sliceArray(pos until minOf(pos + 8, data1.size)).toLong() },
            otherGetLong = { pos -> data2.sliceArray(pos until minOf(pos + 8, data2.size)).toLong() },
            getByte = { pos -> data1[pos] },
            otherGetByte = { pos -> data2[pos] }
        )
        assertFalse(result, "Different buffers should not be equal")
    }

    @Test
    fun bulkCompareEqualsIdenticalLargeBuffers() {
        val data = ByteArray(24) { it.toByte() }
        val result = bulkCompareEquals(
            thisPos = 0,
            otherPos = 0,
            length = 24,
            getLong = { pos -> data.sliceArray(pos until pos + 8).toLong() },
            otherGetLong = { pos -> data.sliceArray(pos until pos + 8).toLong() },
            getByte = { pos -> data[pos] },
            otherGetByte = { pos -> data[pos] }
        )
        assertTrue(result, "Identical large buffers should be equal")
    }

    @Test
    fun bulkCompareEqualsDifferAtByte8() {
        val data1 = ByteArray(16) { it.toByte() }
        val data2 = ByteArray(16) { it.toByte() }
        data2[8] = 99 // Differ at byte 8 (start of second Long)
        val result = bulkCompareEquals(
            thisPos = 0,
            otherPos = 0,
            length = 16,
            getLong = { pos -> data1.sliceArray(pos until pos + 8).toLong() },
            otherGetLong = { pos -> data2.sliceArray(pos until pos + 8).toLong() },
            getByte = { pos -> data1[pos] },
            otherGetByte = { pos -> data2[pos] }
        )
        assertFalse(result, "Should detect difference at Long boundary")
    }

    @Test
    fun bulkCompareEqualsDifferAtLastByte() {
        val data1 = ByteArray(10) { it.toByte() }
        val data2 = ByteArray(10) { it.toByte() }
        data2[9] = 99 // Differ at last byte (in remainder section)
        val result = bulkCompareEquals(
            thisPos = 0,
            otherPos = 0,
            length = 10,
            getLong = { pos -> data1.sliceArray(pos until pos + 8).toLong() },
            otherGetLong = { pos -> data2.sliceArray(pos until pos + 8).toLong() },
            getByte = { pos -> data1[pos] },
            otherGetByte = { pos -> data2[pos] }
        )
        assertFalse(result, "Should detect difference in remainder bytes")
    }

    @Test
    fun bulkCompareEqualsWithOffset() {
        val data1 = byteArrayOf(99, 99, 1, 2, 3, 4, 5, 6, 7, 8)
        val data2 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val result = bulkCompareEquals(
            thisPos = 2,
            otherPos = 0,
            length = 8,
            getLong = { pos -> data1.sliceArray(pos until pos + 8).toLong() },
            otherGetLong = { pos -> data2.sliceArray(pos until pos + 8).toLong() },
            getByte = { pos -> data1[pos] },
            otherGetByte = { pos -> data2[pos] }
        )
        assertTrue(result, "Should compare equal with offset")
    }

    // endregion

    // region bulkMismatch tests

    @Test
    fun bulkMismatchEmptyBuffers() {
        val result = bulkMismatch(
            thisPos = 0,
            otherPos = 0,
            minLength = 0,
            thisRemaining = 0,
            otherRemaining = 0,
            getLong = { 0L },
            otherGetLong = { 0L },
            getByte = { 0 },
            otherGetByte = { 0 }
        )
        assertEquals(-1, result, "Empty buffers should have no mismatch")
    }

    @Test
    fun bulkMismatchIdenticalBuffers() {
        val data = ByteArray(16) { it.toByte() }
        val result = bulkMismatch(
            thisPos = 0,
            otherPos = 0,
            minLength = 16,
            thisRemaining = 16,
            otherRemaining = 16,
            getLong = { pos -> data.sliceArray(pos until pos + 8).toLong() },
            otherGetLong = { pos -> data.sliceArray(pos until pos + 8).toLong() },
            getByte = { pos -> data[pos] },
            otherGetByte = { pos -> data[pos] }
        )
        assertEquals(-1, result, "Identical buffers should have no mismatch")
    }

    @Test
    fun bulkMismatchAtStart() {
        val data1 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val data2 = byteArrayOf(9, 2, 3, 4, 5, 6, 7, 8)
        val result = bulkMismatch(
            thisPos = 0,
            otherPos = 0,
            minLength = 8,
            thisRemaining = 8,
            otherRemaining = 8,
            getLong = { pos -> data1.sliceArray(pos until pos + 8).toLong() },
            otherGetLong = { pos -> data2.sliceArray(pos until pos + 8).toLong() },
            getByte = { pos -> data1[pos] },
            otherGetByte = { pos -> data2[pos] }
        )
        assertEquals(0, result, "Mismatch should be at position 0")
    }

    @Test
    fun bulkMismatchAtMiddleOfLong() {
        val data1 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val data2 = byteArrayOf(1, 2, 3, 4, 9, 6, 7, 8)
        val result = bulkMismatch(
            thisPos = 0,
            otherPos = 0,
            minLength = 8,
            thisRemaining = 8,
            otherRemaining = 8,
            getLong = { pos -> data1.sliceArray(pos until pos + 8).toLong() },
            otherGetLong = { pos -> data2.sliceArray(pos until pos + 8).toLong() },
            getByte = { pos -> data1[pos] },
            otherGetByte = { pos -> data2[pos] }
        )
        assertEquals(4, result, "Mismatch should be at position 4")
    }

    @Test
    fun bulkMismatchInSecondLong() {
        val data1 = ByteArray(16) { it.toByte() }
        val data2 = ByteArray(16) { it.toByte() }
        data2[10] = 99
        val result = bulkMismatch(
            thisPos = 0,
            otherPos = 0,
            minLength = 16,
            thisRemaining = 16,
            otherRemaining = 16,
            getLong = { pos -> data1.sliceArray(pos until pos + 8).toLong() },
            otherGetLong = { pos -> data2.sliceArray(pos until pos + 8).toLong() },
            getByte = { pos -> data1[pos] },
            otherGetByte = { pos -> data2[pos] }
        )
        assertEquals(10, result, "Mismatch should be at position 10")
    }

    @Test
    fun bulkMismatchInRemainder() {
        val data1 = ByteArray(10) { it.toByte() }
        val data2 = ByteArray(10) { it.toByte() }
        data2[9] = 99
        val result = bulkMismatch(
            thisPos = 0,
            otherPos = 0,
            minLength = 10,
            thisRemaining = 10,
            otherRemaining = 10,
            getLong = { pos -> data1.sliceArray(pos until pos + 8).toLong() },
            otherGetLong = { pos -> data2.sliceArray(pos until pos + 8).toLong() },
            getByte = { pos -> data1[pos] },
            otherGetByte = { pos -> data2[pos] }
        )
        assertEquals(9, result, "Mismatch should be at position 9 (remainder)")
    }

    @Test
    fun bulkMismatchDifferentLengths() {
        val data1 = ByteArray(8) { it.toByte() }
        val data2 = ByteArray(10) { it.toByte() }
        val result = bulkMismatch(
            thisPos = 0,
            otherPos = 0,
            minLength = 8,
            thisRemaining = 8,
            otherRemaining = 10,
            getLong = { pos -> data1.sliceArray(pos until pos + 8).toLong() },
            otherGetLong = { pos -> data2.sliceArray(pos until pos + 8).toLong() },
            getByte = { pos -> data1[pos] },
            otherGetByte = { pos -> data2[pos] }
        )
        assertEquals(8, result, "Should return minLength when contents match but sizes differ")
    }

    // endregion

    // region bulkIndexOf tests

    @Test
    fun bulkIndexOfEmptyBuffer() {
        val result = bulkIndexOf(
            startPos = 0,
            length = 0,
            byte = 5,
            getLong = { 0L },
            getByte = { 0 }
        )
        assertEquals(-1, result, "Empty buffer should return -1")
    }

    @Test
    fun bulkIndexOfNotFound() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val result = bulkIndexOf(
            startPos = 0,
            length = 8,
            byte = 99,
            getLong = { pos -> data.sliceArray(pos until pos + 8).toLong() },
            getByte = { pos -> data[pos] }
        )
        assertEquals(-1, result, "Should return -1 when byte not found")
    }

    @Test
    fun bulkIndexOfAtStart() {
        val data = byteArrayOf(5, 2, 3, 4, 1, 6, 7, 8)
        val result = bulkIndexOf(
            startPos = 0,
            length = 8,
            byte = 5,
            getLong = { pos -> data.sliceArray(pos until pos + 8).toLong() },
            getByte = { pos -> data[pos] }
        )
        assertEquals(0, result, "Should find byte at position 0")
    }

    @Test
    fun bulkIndexOfAtEnd() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 99)
        val result = bulkIndexOf(
            startPos = 0,
            length = 8,
            byte = 99,
            getLong = { pos -> data.sliceArray(pos until pos + 8).toLong() },
            getByte = { pos -> data[pos] }
        )
        assertEquals(7, result, "Should find byte at last position")
    }

    @Test
    fun bulkIndexOfInMiddle() {
        val data = byteArrayOf(1, 2, 3, 99, 5, 6, 7, 8)
        val result = bulkIndexOf(
            startPos = 0,
            length = 8,
            byte = 99,
            getLong = { pos -> data.sliceArray(pos until pos + 8).toLong() },
            getByte = { pos -> data[pos] }
        )
        assertEquals(3, result, "Should find byte in middle")
    }

    @Test
    fun bulkIndexOfInSecondLong() {
        val data = ByteArray(16) { it.toByte() }
        data[10] = 99
        val result = bulkIndexOf(
            startPos = 0,
            length = 16,
            byte = 99,
            getLong = { pos -> data.sliceArray(pos until pos + 8).toLong() },
            getByte = { pos -> data[pos] }
        )
        assertEquals(10, result, "Should find byte in second Long block")
    }

    @Test
    fun bulkIndexOfInRemainder() {
        val data = ByteArray(10) { it.toByte() }
        data[9] = 99
        val result = bulkIndexOf(
            startPos = 0,
            length = 10,
            byte = 99,
            getLong = { pos -> data.sliceArray(pos until pos + 8).toLong() },
            getByte = { pos -> data[pos] }
        )
        assertEquals(9, result, "Should find byte in remainder section")
    }

    @Test
    fun bulkIndexOfSmallBuffer() {
        val data = byteArrayOf(1, 2, 99, 4, 5)
        val result = bulkIndexOf(
            startPos = 0,
            length = 5,
            byte = 99,
            getLong = { pos -> data.sliceArray(pos until minOf(pos + 8, data.size)).toLong() },
            getByte = { pos -> data[pos] }
        )
        assertEquals(2, result, "Should find byte in small buffer")
    }

    @Test
    fun bulkIndexOfWithOffset() {
        val data = byteArrayOf(99, 1, 2, 3, 99, 5, 6, 7, 8, 9)
        val result = bulkIndexOf(
            startPos = 2,
            length = 8,
            byte = 99,
            getLong = { pos -> data.sliceArray(pos until pos + 8).toLong() },
            getByte = { pos -> data[pos] }
        )
        assertEquals(2, result, "Should find byte relative to startPos")
    }

    @Test
    fun bulkIndexOfZeroByte() {
        val data = byteArrayOf(1, 2, 3, 0, 5, 6, 7, 8)
        val result = bulkIndexOf(
            startPos = 0,
            length = 8,
            byte = 0,
            getLong = { pos -> data.sliceArray(pos until pos + 8).toLong() },
            getByte = { pos -> data[pos] }
        )
        assertEquals(3, result, "Should find zero byte")
    }

    @Test
    fun bulkIndexOfNegativeByte() {
        val data = byteArrayOf(1, 2, 3, -1, 5, 6, 7, 8)
        val result = bulkIndexOf(
            startPos = 0,
            length = 8,
            byte = -1,
            getLong = { pos -> data.sliceArray(pos until pos + 8).toLong() },
            getByte = { pos -> data[pos] }
        )
        assertEquals(3, result, "Should find negative byte (0xFF)")
    }

    @Test
    fun bulkIndexOfLargeBuffer() {
        val data = ByteArray(100) { 0 }
        data[67] = 99
        val result = bulkIndexOf(
            startPos = 0,
            length = 100,
            byte = 99,
            getLong = { pos -> data.sliceArray(pos until minOf(pos + 8, data.size)).toLong() },
            getByte = { pos -> data[pos] }
        )
        assertEquals(67, result, "Should find byte in large buffer")
    }

    // endregion

    // region bulkIndexOfInt tests

    @Test
    fun bulkIndexOfIntEmptyBuffer() {
        val result = bulkIndexOfInt(
            startPos = 0,
            length = 0,
            byte = 5,
            getInt = { 0 },
            getByte = { 0 }
        )
        assertEquals(-1, result, "Empty buffer should return -1")
    }

    @Test
    fun bulkIndexOfIntNotFound() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val result = bulkIndexOfInt(
            startPos = 0,
            length = 8,
            byte = 99,
            getInt = { pos -> data.sliceArray(pos until pos + 4).toInt() },
            getByte = { pos -> data[pos] }
        )
        assertEquals(-1, result, "Should return -1 when byte not found")
    }

    @Test
    fun bulkIndexOfIntFound() {
        val data = byteArrayOf(1, 2, 99, 4, 5, 6, 7, 8)
        val result = bulkIndexOfInt(
            startPos = 0,
            length = 8,
            byte = 99,
            getInt = { pos -> data.sliceArray(pos until pos + 4).toInt() },
            getByte = { pos -> data[pos] }
        )
        assertEquals(2, result, "Should find byte at position 2")
    }

    @Test
    fun bulkIndexOfIntInSecondInt() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 99, 7, 8)
        val result = bulkIndexOfInt(
            startPos = 0,
            length = 8,
            byte = 99,
            getInt = { pos -> data.sliceArray(pos until pos + 4).toInt() },
            getByte = { pos -> data[pos] }
        )
        assertEquals(5, result, "Should find byte in second Int block")
    }

    @Test
    fun bulkIndexOfIntInRemainder() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 99)
        val result = bulkIndexOfInt(
            startPos = 0,
            length = 6,
            byte = 99,
            getInt = { pos -> data.sliceArray(pos until pos + 4).toInt() },
            getByte = { pos -> data[pos] }
        )
        assertEquals(5, result, "Should find byte in remainder section")
    }

    // endregion

    // region bulkCompareEqualsInt tests

    @Test
    fun bulkCompareEqualsIntEmptyBuffers() {
        val result = bulkCompareEqualsInt(
            thisPos = 0,
            otherPos = 0,
            length = 0,
            getInt = { 0 },
            otherGetInt = { 0 },
            getByte = { 0 },
            otherGetByte = { 0 }
        )
        assertTrue(result, "Empty buffers should be equal")
    }

    @Test
    fun bulkCompareEqualsIntIdentical() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val result = bulkCompareEqualsInt(
            thisPos = 0,
            otherPos = 0,
            length = 8,
            getInt = { pos -> data.sliceArray(pos until pos + 4).toInt() },
            otherGetInt = { pos -> data.sliceArray(pos until pos + 4).toInt() },
            getByte = { pos -> data[pos] },
            otherGetByte = { pos -> data[pos] }
        )
        assertTrue(result, "Identical buffers should be equal")
    }

    @Test
    fun bulkCompareEqualsIntDifferent() {
        val data1 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val data2 = byteArrayOf(1, 2, 99, 4, 5, 6, 7, 8)
        val result = bulkCompareEqualsInt(
            thisPos = 0,
            otherPos = 0,
            length = 8,
            getInt = { pos -> data1.sliceArray(pos until pos + 4).toInt() },
            otherGetInt = { pos -> data2.sliceArray(pos until pos + 4).toInt() },
            getByte = { pos -> data1[pos] },
            otherGetByte = { pos -> data2[pos] }
        )
        assertFalse(result, "Different buffers should not be equal")
    }

    @Test
    fun bulkCompareEqualsIntDifferInRemainder() {
        val data1 = byteArrayOf(1, 2, 3, 4, 5, 6)
        val data2 = byteArrayOf(1, 2, 3, 4, 5, 99)
        val result = bulkCompareEqualsInt(
            thisPos = 0,
            otherPos = 0,
            length = 6,
            getInt = { pos -> data1.sliceArray(pos until pos + 4).toInt() },
            otherGetInt = { pos -> data2.sliceArray(pos until pos + 4).toInt() },
            getByte = { pos -> data1[pos] },
            otherGetByte = { pos -> data2[pos] }
        )
        assertFalse(result, "Should detect difference in remainder")
    }

    // endregion

    // region bulkMismatchInt tests

    @Test
    fun bulkMismatchIntEmptyBuffers() {
        val result = bulkMismatchInt(
            thisPos = 0,
            otherPos = 0,
            minLength = 0,
            thisRemaining = 0,
            otherRemaining = 0,
            getInt = { 0 },
            otherGetInt = { 0 },
            getByte = { 0 },
            otherGetByte = { 0 }
        )
        assertEquals(-1, result, "Empty buffers should have no mismatch")
    }

    @Test
    fun bulkMismatchIntIdentical() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val result = bulkMismatchInt(
            thisPos = 0,
            otherPos = 0,
            minLength = 8,
            thisRemaining = 8,
            otherRemaining = 8,
            getInt = { pos -> data.sliceArray(pos until pos + 4).toInt() },
            otherGetInt = { pos -> data.sliceArray(pos until pos + 4).toInt() },
            getByte = { pos -> data[pos] },
            otherGetByte = { pos -> data[pos] }
        )
        assertEquals(-1, result, "Identical buffers should have no mismatch")
    }

    @Test
    fun bulkMismatchIntAtStart() {
        val data1 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val data2 = byteArrayOf(99, 2, 3, 4, 5, 6, 7, 8)
        val result = bulkMismatchInt(
            thisPos = 0,
            otherPos = 0,
            minLength = 8,
            thisRemaining = 8,
            otherRemaining = 8,
            getInt = { pos -> data1.sliceArray(pos until pos + 4).toInt() },
            otherGetInt = { pos -> data2.sliceArray(pos until pos + 4).toInt() },
            getByte = { pos -> data1[pos] },
            otherGetByte = { pos -> data2[pos] }
        )
        assertEquals(0, result, "Mismatch should be at position 0")
    }

    @Test
    fun bulkMismatchIntInMiddleOfInt() {
        val data1 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val data2 = byteArrayOf(1, 2, 99, 4, 5, 6, 7, 8)
        val result = bulkMismatchInt(
            thisPos = 0,
            otherPos = 0,
            minLength = 8,
            thisRemaining = 8,
            otherRemaining = 8,
            getInt = { pos -> data1.sliceArray(pos until pos + 4).toInt() },
            otherGetInt = { pos -> data2.sliceArray(pos until pos + 4).toInt() },
            getByte = { pos -> data1[pos] },
            otherGetByte = { pos -> data2[pos] }
        )
        assertEquals(2, result, "Mismatch should be at position 2")
    }

    @Test
    fun bulkMismatchIntInRemainder() {
        val data1 = byteArrayOf(1, 2, 3, 4, 5, 6)
        val data2 = byteArrayOf(1, 2, 3, 4, 5, 99)
        val result = bulkMismatchInt(
            thisPos = 0,
            otherPos = 0,
            minLength = 6,
            thisRemaining = 6,
            otherRemaining = 6,
            getInt = { pos -> data1.sliceArray(pos until pos + 4).toInt() },
            otherGetInt = { pos -> data2.sliceArray(pos until pos + 4).toInt() },
            getByte = { pos -> data1[pos] },
            otherGetByte = { pos -> data2[pos] }
        )
        assertEquals(5, result, "Mismatch should be at position 5 (remainder)")
    }

    @Test
    fun bulkMismatchIntDifferentLengths() {
        val data1 = byteArrayOf(1, 2, 3, 4)
        val data2 = byteArrayOf(1, 2, 3, 4, 5, 6)
        val result = bulkMismatchInt(
            thisPos = 0,
            otherPos = 0,
            minLength = 4,
            thisRemaining = 4,
            otherRemaining = 6,
            getInt = { pos -> data1.sliceArray(pos until pos + 4).toInt() },
            otherGetInt = { pos -> data2.sliceArray(pos until pos + 4).toInt() },
            getByte = { pos -> data1[pos] },
            otherGetByte = { pos -> data2[pos] }
        )
        assertEquals(4, result, "Should return minLength when contents match but sizes differ")
    }

    // endregion

    // Helper functions to convert ByteArray to Long/Int for testing
    private fun ByteArray.toLong(): Long {
        var result = 0L
        for (i in 0 until minOf(8, size)) {
            result = result or ((this[i].toLong() and 0xFF) shl (i * 8))
        }
        return result
    }

    private fun ByteArray.toInt(): Int {
        var result = 0
        for (i in 0 until minOf(4, size)) {
            result = result or ((this[i].toInt() and 0xFF) shl (i * 8))
        }
        return result
    }
}
