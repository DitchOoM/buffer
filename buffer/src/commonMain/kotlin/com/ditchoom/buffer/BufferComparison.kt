package com.ditchoom.buffer

import com.ditchoom.buffer.BufferConstants.BYTE_1_SHIFT
import com.ditchoom.buffer.BufferConstants.BYTE_2_SHIFT
import com.ditchoom.buffer.BufferConstants.BYTE_3_SHIFT
import com.ditchoom.buffer.BufferConstants.BYTE_MASK

// Utility functions for optimized buffer comparison operations.
// Provides bulk comparison using Long (8-byte) and Int (4-byte) chunks.

/** SWAR (SIMD-within-a-register) low-bit-of-each-byte mask (`0x01010101`) for zero-byte detection. */
private const val SWAR_LOW_BITS = 0x01010101

/** SWAR high-bit-of-each-byte mask (`0x80808080`) marking which lanes held a zero byte. */
private const val SWAR_HIGH_BITS = 0x80808080.toInt()

/** 64-bit SWAR low-bit-of-each-byte mask (`0x0101...01`) for zero-byte detection across a Long. */
private const val SWAR_LOW_BITS_LONG = 0x0101010101010101L

/** 64-bit SWAR high-bit-of-each-byte mask (`0x8080...80`) marking zero lanes across a Long. */
private val SWAR_HIGH_BITS_LONG = 0x8080808080808080UL.toLong()

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
    // For length >= 8, compare whole 8-byte words, then handle any remainder with ONE final
    // overlapping word ([length-8, length)) instead of a per-byte tail. For equality the overlap
    // (re-comparing a few bytes) is harmless, and it keeps the whole path on 8-byte loads — fewer
    // memory accesses, and on non-JIT backends no per-byte pointer materialization.
    if (length >= Long.SIZE_BYTES) {
        var i = 0
        while (i + Long.SIZE_BYTES <= length) {
            if (getLong(thisPos + i) != otherGetLong(otherPos + i)) {
                return false
            }
            i += Long.SIZE_BYTES
        }
        if (i < length) {
            val tail = length - Long.SIZE_BYTES
            if (getLong(thisPos + tail) != otherGetLong(otherPos + tail)) {
                return false
            }
        }
        return true
    }
    // length < 8: byte-by-byte (too short for a word load)
    var i = 0
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
    while (i + Long.SIZE_BYTES <= minLength) {
        if (getLong(thisPos + i) != otherGetLong(otherPos + i)) {
            // Found mismatch in this Long, find exact position
            for (j in 0 until Long.SIZE_BYTES) {
                if (getByte(thisPos + i + j) != otherGetByte(otherPos + i + j)) {
                    return i + j
                }
            }
        }
        i += Long.SIZE_BYTES
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
    val targetLong = (byte.toLong() and BYTE_MASK.toLong()) * SWAR_LOW_BITS_LONG
    var i = 0

    // Search 8 bytes at a time using XOR trick
    while (i + Long.SIZE_BYTES <= length) {
        val value = getLong(startPos + i)
        val xor = value xor targetLong
        // Check if any byte is zero (meaning we found the target)
        // This uses the "determine if any byte is zero" trick
        val hasZero = (xor - SWAR_LOW_BITS_LONG) and SWAR_HIGH_BITS_LONG and xor.inv()
        if (hasZero != 0L) {
            // Found a match in this Long, find exact position
            for (j in 0 until Long.SIZE_BYTES) {
                if (getByte(startPos + i + j) == byte) {
                    return i + j
                }
            }
        }
        i += Long.SIZE_BYTES
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

    val targetInt =
        (byte.toInt() and BYTE_MASK).let { b ->
            b or (b shl BYTE_1_SHIFT) or (b shl BYTE_2_SHIFT) or (b shl BYTE_3_SHIFT)
        }
    var i = 0

    while (i + Int.SIZE_BYTES <= length) {
        val value = getInt(startPos + i)
        val xor = value xor targetInt
        val hasZero = (xor - SWAR_LOW_BITS) and xor.inv() and SWAR_HIGH_BITS
        if (hasZero != 0) {
            for (j in 0 until Int.SIZE_BYTES) {
                if (getByte(startPos + i + j) == byte) {
                    return i + j
                }
            }
        }
        i += Int.SIZE_BYTES
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
    while (i + Int.SIZE_BYTES <= length) {
        if (getInt(thisPos + i) != otherGetInt(otherPos + i)) {
            return false
        }
        i += Int.SIZE_BYTES
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
    while (i + Int.SIZE_BYTES <= minLength) {
        if (getInt(thisPos + i) != otherGetInt(otherPos + i)) {
            // Found mismatch in this Int, find exact position
            for (j in 0 until Int.SIZE_BYTES) {
                if (getByte(thisPos + i + j) != otherGetByte(otherPos + i + j)) {
                    return i + j
                }
            }
        }
        i += Int.SIZE_BYTES
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

// =========================================================================
// Bulk XOR Mask operations
// =========================================================================

/**
 * XOR mask a buffer in-place using Long (8-byte) chunks.
 *
 * @param pos Start position in the buffer
 * @param size Number of bytes to process
 * @param maskLong Pre-built 8-byte mask matching the buffer's byte order interpretation.
 *   Must be constructed with mask rotation (for maskOffset) and byte order already applied.
 * @param mask Original 4-byte XOR mask in big-endian order (for tail bytes)
 * @param maskOffset Byte offset into the mask cycle (0-3)
 */
internal inline fun bulkXorMask(
    pos: Int,
    size: Int,
    maskLong: Long,
    mask: Int,
    maskOffset: Int,
    getLong: (Int) -> Long,
    setLong: (Int, Long) -> Unit,
    getByte: (Int) -> Byte,
    setByte: (Int, Byte) -> Unit,
) {
    val maskByte0 = (mask ushr BYTE_3_SHIFT).toByte()
    val maskByte1 = (mask ushr BYTE_2_SHIFT).toByte()
    val maskByte2 = (mask ushr BYTE_1_SHIFT).toByte()
    val maskByte3 = mask.toByte()

    var i = 0
    while (i + Long.SIZE_BYTES <= size) {
        setLong(pos + i, getLong(pos + i) xor maskLong)
        i += Long.SIZE_BYTES
    }
    while (i < size) {
        val maskByte =
            when ((i + maskOffset) and 3) {
                0 -> maskByte0
                1 -> maskByte1
                2 -> maskByte2
                else -> maskByte3
            }
        setByte(pos + i, (getByte(pos + i).toInt() xor maskByte.toInt()).toByte())
        i++
    }
}

/**
 * Copy bytes from source to destination while XORing with a repeating mask, using Long (8-byte) chunks.
 *
 * @param srcPos Start position in the source
 * @param dstPos Start position in the destination
 * @param size Number of bytes to process
 * @param maskLong Pre-built 8-byte mask matching the buffers' byte order interpretation
 * @param mask Original 4-byte XOR mask in big-endian order (for tail bytes)
 * @param maskOffset Byte offset into the mask cycle (0-3)
 */
internal inline fun bulkXorMaskCopy(
    srcPos: Int,
    dstPos: Int,
    size: Int,
    maskLong: Long,
    mask: Int,
    maskOffset: Int,
    srcGetLong: (Int) -> Long,
    dstSetLong: (Int, Long) -> Unit,
    srcGetByte: (Int) -> Byte,
    dstSetByte: (Int, Byte) -> Unit,
) {
    val maskByte0 = (mask ushr BYTE_3_SHIFT).toByte()
    val maskByte1 = (mask ushr BYTE_2_SHIFT).toByte()
    val maskByte2 = (mask ushr BYTE_1_SHIFT).toByte()
    val maskByte3 = mask.toByte()

    var i = 0
    while (i + Long.SIZE_BYTES <= size) {
        dstSetLong(dstPos + i, srcGetLong(srcPos + i) xor maskLong)
        i += Long.SIZE_BYTES
    }
    while (i < size) {
        val maskByte =
            when ((i + maskOffset) and 3) {
                0 -> maskByte0
                1 -> maskByte1
                2 -> maskByte2
                else -> maskByte3
            }
        dstSetByte(dstPos + i, (srcGetByte(srcPos + i).toInt() xor maskByte.toInt()).toByte())
        i++
    }
}

/**
 * XOR mask a buffer in-place using Int (4-byte) chunks.
 * For platforms where Long access is slower (e.g., JS where Long is emulated).
 */
internal inline fun bulkXorMaskInt(
    pos: Int,
    size: Int,
    maskInt: Int,
    mask: Int,
    maskOffset: Int,
    getInt: (Int) -> Int,
    setInt: (Int, Int) -> Unit,
    getByte: (Int) -> Byte,
    setByte: (Int, Byte) -> Unit,
) {
    val maskByte0 = (mask ushr BYTE_3_SHIFT).toByte()
    val maskByte1 = (mask ushr BYTE_2_SHIFT).toByte()
    val maskByte2 = (mask ushr BYTE_1_SHIFT).toByte()
    val maskByte3 = mask.toByte()

    var i = 0
    while (i + Int.SIZE_BYTES <= size) {
        setInt(pos + i, getInt(pos + i) xor maskInt)
        i += Int.SIZE_BYTES
    }
    while (i < size) {
        val maskByte =
            when ((i + maskOffset) and 3) {
                0 -> maskByte0
                1 -> maskByte1
                2 -> maskByte2
                else -> maskByte3
            }
        setByte(pos + i, (getByte(pos + i).toInt() xor maskByte.toInt()).toByte())
        i++
    }
}

/**
 * Copy bytes from source to destination while XORing with a repeating mask, using Int (4-byte) chunks.
 * For platforms where Long access is slower (e.g., JS where Long is emulated).
 */
internal inline fun bulkXorMaskCopyInt(
    srcPos: Int,
    dstPos: Int,
    size: Int,
    maskInt: Int,
    mask: Int,
    maskOffset: Int,
    srcGetInt: (Int) -> Int,
    dstSetInt: (Int, Int) -> Unit,
    srcGetByte: (Int) -> Byte,
    dstSetByte: (Int, Byte) -> Unit,
) {
    val maskByte0 = (mask ushr BYTE_3_SHIFT).toByte()
    val maskByte1 = (mask ushr BYTE_2_SHIFT).toByte()
    val maskByte2 = (mask ushr BYTE_1_SHIFT).toByte()
    val maskByte3 = mask.toByte()

    var i = 0
    while (i + Int.SIZE_BYTES <= size) {
        dstSetInt(dstPos + i, srcGetInt(srcPos + i) xor maskInt)
        i += Int.SIZE_BYTES
    }
    while (i < size) {
        val maskByte =
            when ((i + maskOffset) and 3) {
                0 -> maskByte0
                1 -> maskByte1
                2 -> maskByte2
                else -> maskByte3
            }
        dstSetByte(dstPos + i, (srcGetByte(srcPos + i).toInt() xor maskByte.toInt()).toByte())
        i++
    }
}

/**
 * Build a Long mask for bulk XOR operations from a big-endian mask Int.
 * Handles mask rotation (for maskOffset) and byte order conversion.
 *
 * @param mask The 4-byte XOR mask in big-endian order
 * @param maskOffset Byte offset into the mask cycle (0-3)
 * @param littleEndian Whether the buffer uses little-endian byte order
 * @return Long mask suitable for XOR with getLong() results in the given byte order
 */
internal fun buildMaskLong(
    mask: Int,
    maskOffset: Int,
    littleEndian: Boolean,
): Long {
    val shift = (maskOffset and BufferConstants.WORD_BYTE_MASK) * Byte.SIZE_BITS
    val rotated = if (shift == 0) mask else (mask shl shift) or (mask ushr (Int.SIZE_BITS - shift))
    return if (littleEndian) {
        val le = rotated.reverseBytes()
        (le.toLong() and BufferConstants.INT_MASK) or (le.toLong() shl Int.SIZE_BITS)
    } else {
        (rotated.toLong() shl Int.SIZE_BITS) or (rotated.toLong() and BufferConstants.INT_MASK)
    }
}

/**
 * Build an Int mask for bulk XOR operations from a big-endian mask Int.
 * Handles mask rotation (for maskOffset) and byte order conversion.
 */
internal fun buildMaskInt(
    mask: Int,
    maskOffset: Int,
    littleEndian: Boolean,
): Int {
    val shift = (maskOffset and BufferConstants.WORD_BYTE_MASK) * Byte.SIZE_BITS
    val rotated = if (shift == 0) mask else (mask shl shift) or (mask ushr (Int.SIZE_BITS - shift))
    return if (littleEndian) rotated.reverseBytes() else rotated
}
