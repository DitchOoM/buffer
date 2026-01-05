package com.ditchoom.buffer

/**
 * Utility functions for optimized buffer comparison operations.
 * Provides bulk comparison using Long (8-byte) and Int (4-byte) chunks.
 */

/**
 * Compare two buffers for equality starting at specified positions.
 * Uses bulk Long comparisons (8 bytes at a time) when possible.
 *
 * @param thisBuffer First buffer to compare
 * @param thisPos Position in first buffer
 * @param otherBuffer Second buffer to compare
 * @param otherPos Position in second buffer
 * @param length Number of bytes to compare
 * @param getLong Function to read a Long (8 bytes) from a buffer at given position
 * @param getByte Function to read a Byte from a buffer at given position
 * @return true if all bytes are equal
 */
internal inline fun bulkCompareEquals(
    thisPos: Int,
    otherPos: Int,
    length: Int,
    getLong: (Int) -> Long,
    otherGetLong: (Int) -> Long,
    getByte: (Int) -> Byte,
    otherGetByte: (Int) -> Byte,
): Boolean {
    var i = 0
    // Compare 8 bytes at a time
    while (i + 8 <= length) {
        if (getLong(thisPos + i) != otherGetLong(otherPos + i)) {
            return false
        }
        i += 8
    }
    // Compare remaining bytes
    while (i < length) {
        if (getByte(thisPos + i) != otherGetByte(otherPos + i)) {
            return false
        }
        i++
    }
    return true
}

/**
 * Find the first mismatch between two buffers starting at specified positions.
 * Uses bulk Long comparisons (8 bytes at a time) when possible.
 *
 * @param thisPos Position in first buffer
 * @param otherPos Position in second buffer
 * @param minLength Number of bytes to compare (minimum of both remaining sizes)
 * @param thisRemaining Remaining bytes in first buffer
 * @param otherRemaining Remaining bytes in second buffer
 * @param getLong Function to read a Long (8 bytes) from first buffer at given position
 * @param otherGetLong Function to read a Long (8 bytes) from second buffer at given position
 * @param getByte Function to read a Byte from first buffer at given position
 * @param otherGetByte Function to read a Byte from second buffer at given position
 * @return Index of first mismatch, or -1 if no mismatch within minLength.
 *         If buffers have different lengths but match up to shorter, returns minLength.
 */
internal inline fun bulkMismatch(
    thisPos: Int,
    otherPos: Int,
    minLength: Int,
    thisRemaining: Int,
    otherRemaining: Int,
    getLong: (Int) -> Long,
    otherGetLong: (Int) -> Long,
    getByte: (Int) -> Byte,
    otherGetByte: (Int) -> Byte,
): Int {
    var i = 0
    // Compare 8 bytes at a time
    while (i + 8 <= minLength) {
        if (getLong(thisPos + i) != otherGetLong(otherPos + i)) {
            // Found mismatch in this Long, find exact position
            for (j in 0 until 8) {
                if (getByte(thisPos + i + j) != otherGetByte(otherPos + i + j)) {
                    return i + j
                }
            }
        }
        i += 8
    }
    // Compare remaining bytes
    while (i < minLength) {
        if (getByte(thisPos + i) != otherGetByte(otherPos + i)) {
            return i
        }
        i++
    }
    return if (thisRemaining != otherRemaining) minLength else -1
}

/**
 * Find the first occurrence of a byte in a buffer using bulk Long searches.
 * Uses XOR trick to detect zero bytes for fast searching.
 *
 * @param startPos Position to start searching from
 * @param length Number of bytes to search
 * @param byte Byte to search for
 * @param getLong Function to read a Long (8 bytes) at given position
 * @param getByte Function to read a Byte at given position
 * @return Index of first occurrence (relative to startPos), or -1 if not found
 */
internal inline fun bulkIndexOf(
    startPos: Int,
    length: Int,
    byte: Byte,
    getLong: (Int) -> Long,
    getByte: (Int) -> Byte,
): Int {
    if (length == 0) return -1

    // Create a Long with the target byte repeated 8 times
    val targetLong = (byte.toLong() and 0xFFL) * 0x0101010101010101L
    var i = 0

    // Search 8 bytes at a time using XOR trick
    while (i + 8 <= length) {
        val value = getLong(startPos + i)
        val xor = value xor targetLong
        // Check if any byte is zero (meaning we found the target)
        // This uses the "determine if any byte is zero" trick
        val hasZero = (xor - 0x0101010101010101L) and 0x8080808080808080UL.toLong() and xor.inv()
        if (hasZero != 0L) {
            // Found a match in this Long, find exact position
            for (j in 0 until 8) {
                if (getByte(startPos + i + j) == byte) {
                    return i + j
                }
            }
        }
        i += 8
    }

    // Search remaining bytes
    while (i < length) {
        if (getByte(startPos + i) == byte) {
            return i
        }
        i++
    }
    return -1
}

/**
 * Variant using Int (4 bytes) instead of Long for platforms where Long access is slower.
 */
internal inline fun bulkIndexOfInt(
    startPos: Int,
    length: Int,
    byte: Byte,
    getInt: (Int) -> Int,
    getByte: (Int) -> Byte,
): Int {
    if (length == 0) return -1

    val targetInt = (byte.toInt() and 0xFF).let { b ->
        b or (b shl 8) or (b shl 16) or (b shl 24)
    }
    var i = 0

    while (i + 4 <= length) {
        val value = getInt(startPos + i)
        val xor = value xor targetInt
        val hasZero = (xor - 0x01010101) and xor.inv() and 0x80808080.toInt()
        if (hasZero != 0) {
            for (j in 0 until 4) {
                if (getByte(startPos + i + j) == byte) {
                    return i + j
                }
            }
        }
        i += 4
    }

    while (i < length) {
        if (getByte(startPos + i) == byte) {
            return i
        }
        i++
    }
    return -1
}

/**
 * Compare two buffers for equality using Int (4 bytes) chunks.
 * Variant for platforms where Long access is slower.
 */
internal inline fun bulkCompareEqualsInt(
    thisPos: Int,
    otherPos: Int,
    length: Int,
    getInt: (Int) -> Int,
    otherGetInt: (Int) -> Int,
    getByte: (Int) -> Byte,
    otherGetByte: (Int) -> Byte,
): Boolean {
    var i = 0
    // Compare 4 bytes at a time
    while (i + 4 <= length) {
        if (getInt(thisPos + i) != otherGetInt(otherPos + i)) {
            return false
        }
        i += 4
    }
    // Compare remaining bytes
    while (i < length) {
        if (getByte(thisPos + i) != otherGetByte(otherPos + i)) {
            return false
        }
        i++
    }
    return true
}

/**
 * Find the first mismatch using Int (4 bytes) chunks.
 * Variant for platforms where Long access is slower.
 */
internal inline fun bulkMismatchInt(
    thisPos: Int,
    otherPos: Int,
    minLength: Int,
    thisRemaining: Int,
    otherRemaining: Int,
    getInt: (Int) -> Int,
    otherGetInt: (Int) -> Int,
    getByte: (Int) -> Byte,
    otherGetByte: (Int) -> Byte,
): Int {
    var i = 0
    // Compare 4 bytes at a time
    while (i + 4 <= minLength) {
        if (getInt(thisPos + i) != otherGetInt(otherPos + i)) {
            // Found mismatch in this Int, find exact position
            for (j in 0 until 4) {
                if (getByte(thisPos + i + j) != otherGetByte(otherPos + i + j)) {
                    return i + j
                }
            }
        }
        i += 4
    }
    // Compare remaining bytes
    while (i < minLength) {
        if (getByte(thisPos + i) != otherGetByte(otherPos + i)) {
            return i
        }
        i++
    }
    return if (thisRemaining != otherRemaining) minLength else -1
}
