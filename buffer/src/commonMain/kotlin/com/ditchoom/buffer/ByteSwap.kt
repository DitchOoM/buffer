package com.ditchoom.buffer

/** Low 8 bits — isolates a single byte. */
private const val BYTE_MASK = 0xFF

/** Low 16 bits — isolates a single Short. */
private const val SHORT_MASK = 0xFFFF

/** Second byte of an Int (`0x0000FF00`). */
private const val SECOND_BYTE_MASK = 0xFF00

/** Shift to move a byte into the most-significant byte of an Int. */
private const val INT_TOP_BYTE_SHIFT = 24

/** Alternating byte mask (`0x00FF00FF...`) selecting the even bytes of a Long. */
private const val LONG_BYTE_SWAP_MASK = 0x00FF00FF00FF00FFL

/** Alternating 16-bit mask (`0x0000FFFF...`) selecting the even shorts of a Long. */
private const val LONG_SHORT_SWAP_MASK = 0x0000FFFF0000FFFFL

/**
 * Reverses the bytes of a Short value.
 */
fun Short.reverseBytes(): Short {
    val value = this.toInt() and SHORT_MASK
    return ((value and BYTE_MASK) shl Byte.SIZE_BITS or (value shr Byte.SIZE_BITS and BYTE_MASK)).toShort()
}

/**
 * Reverses the bytes of an Int value.
 */
fun Int.reverseBytes(): Int =
    (this and BYTE_MASK shl INT_TOP_BYTE_SHIFT) or
        (this and SECOND_BYTE_MASK shl Byte.SIZE_BITS) or
        (this ushr Byte.SIZE_BITS and SECOND_BYTE_MASK) or
        (this ushr INT_TOP_BYTE_SHIFT and BYTE_MASK)

/**
 * Reverses the bytes of a Long value.
 */
fun Long.reverseBytes(): Long {
    var v = this
    v = (v and LONG_BYTE_SWAP_MASK shl Byte.SIZE_BITS) or (v ushr Byte.SIZE_BITS and LONG_BYTE_SWAP_MASK)
    v = (v and LONG_SHORT_SWAP_MASK shl Short.SIZE_BITS) or (v ushr Short.SIZE_BITS and LONG_SHORT_SWAP_MASK)
    return (v shl Int.SIZE_BITS) or (v ushr Int.SIZE_BITS)
}
