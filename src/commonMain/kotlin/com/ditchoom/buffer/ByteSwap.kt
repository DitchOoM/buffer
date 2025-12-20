package com.ditchoom.buffer

/**
 * Reverses the bytes of a Short value.
 */
internal fun Short.reverseBytes(): Short {
    val value = this.toInt() and 0xFFFF
    return ((value and 0xFF) shl 8 or (value shr 8 and 0xFF)).toShort()
}

/**
 * Reverses the bytes of an Int value.
 */
internal fun Int.reverseBytes(): Int =
    (this and 0xFF shl 24) or
        (this and 0xFF00 shl 8) or
        (this ushr 8 and 0xFF00) or
        (this ushr 24 and 0xFF)

/**
 * Reverses the bytes of a Long value.
 */
internal fun Long.reverseBytes(): Long {
    var v = this
    v = (v and 0x00FF00FF00FF00FFL shl 8) or (v ushr 8 and 0x00FF00FF00FF00FFL)
    v = (v and 0x0000FFFF0000FFFFL shl 16) or (v ushr 16 and 0x0000FFFF0000FFFFL)
    return (v shl 32) or (v ushr 32)
}
