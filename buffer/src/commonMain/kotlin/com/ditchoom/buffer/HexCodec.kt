package com.ditchoom.buffer

// Zero-copy (buffer-to-buffer, no intermediate ByteArray/String) hexadecimal encode/decode.
//
// Encoding doubles the data (N bytes -> 2N ASCII hex chars), so it cannot be done in place; "zero-copy"
// here means the bytes flow directly from one buffer into another with no String / ByteArray staging in
// between. Both directions are a textbook SIMD target: the common code below is the portable fallback
// (check the whole range once, then loop over the unchecked source accessor — see getUnchecked), and the
// native NativeMemoryAccess buffers override the members to run the whole transform in C over raw
// pointers (buf_hex_encode / buf_hex_decode), the same shape as hashRange.
//
// Hex is ASCII, so the output/input chars are single bytes regardless of the buffer's text encoding.

// '0'..'9' map to themselves (+0x30); nibbles 10..15 add 0x27 for lowercase 'a'..'f' or 0x07 for
// uppercase 'A'..'F'. Branchless: (9 - n) is negative exactly when n > 9, so its arithmetic shift is an
// all-ones mask there and zero otherwise.
internal const val HEX_LOWER_ALPHA_DELTA = 0x27
internal const val HEX_UPPER_ALPHA_DELTA = 0x07

internal fun hexEncodeNibble(
    nibble: Int,
    alphaDelta: Int,
): Byte {
    val mask = (9 - nibble) shr 31
    return (0x30 + nibble + (mask and alphaDelta)).toByte()
}

/**
 * Maps a single ASCII hex character byte to its 0..15 nibble value, or -1 if it is not a hex digit.
 * Accepts '0'-'9', 'a'-'f' and 'A'-'F'.
 */
internal fun hexDecodeNibble(c: Byte): Int {
    val v = c.toInt() and 0xFF
    return when (v) {
        in '0'.code..'9'.code -> v - '0'.code
        in 'a'.code..'f'.code -> v - 'a'.code + 10
        in 'A'.code..'F'.code -> v - 'A'.code + 10
        else -> -1
    }
}

/**
 * Portable encode fallback: reads `length` source bytes via [getByte] and emits `2 * length` ASCII hex
 * bytes via [putByte] (high nibble first). The caller has already range-checked the source.
 */
internal inline fun encodeHexFallback(
    srcOffset: Int,
    length: Int,
    upperCase: Boolean,
    getByte: (Int) -> Byte,
    putByte: (Byte) -> Unit,
) {
    val alphaDelta = if (upperCase) HEX_UPPER_ALPHA_DELTA else HEX_LOWER_ALPHA_DELTA
    var i = 0
    while (i < length) {
        val b = getByte(srcOffset + i).toInt() and 0xFF
        putByte(hexEncodeNibble(b ushr 4, alphaDelta))
        putByte(hexEncodeNibble(b and 0x0F, alphaDelta))
        i++
    }
}

/**
 * Portable decode fallback: reads `length` ASCII hex source bytes via [getByte] (length must be even)
 * and emits `length / 2` decoded bytes via [putByte]. The caller has already range-checked the source.
 *
 * @throws IllegalArgumentException if [length] is odd or a source byte is not a hex digit.
 */
internal inline fun decodeHexFallback(
    srcOffset: Int,
    length: Int,
    getByte: (Int) -> Byte,
    putByte: (Byte) -> Unit,
) {
    require(length and 1 == 0) { "hex input length ($length) must be even" }
    var i = 0
    while (i < length) {
        val hi = hexDecodeNibble(getByte(srcOffset + i))
        val lo = hexDecodeNibble(getByte(srcOffset + i + 1))
        if (hi < 0 || lo < 0) {
            val badIndex = if (hi < 0) srcOffset + i else srcOffset + i + 1
            throw IllegalArgumentException("invalid hex character at index $badIndex")
        }
        putByte(((hi shl 4) or lo).toByte())
        i += 2
    }
}

// region public relative convenience

/**
 * Relative: hex-encodes this buffer's remaining bytes (`position()` until `limit()`) into [dest],
 * advancing this buffer's [position] to its [limit] and [dest]'s position by `2 * remaining()`.
 *
 * @param dest destination buffer that receives the ASCII hex bytes
 * @param upperCase emit 'A'-'F' instead of 'a'-'f'
 */
fun ReadBuffer.encodeHexInto(
    dest: WriteBuffer,
    upperCase: Boolean = false,
): WriteBuffer {
    val start = position()
    val length = remaining()
    encodeHexInto(dest, start, length, upperCase)
    position(start + length)
    return dest
}

/**
 * Relative: hex-decodes this buffer's remaining ASCII hex bytes (must be an even count) into [dest],
 * advancing this buffer's [position] to its [limit] and [dest]'s position by `remaining() / 2`.
 *
 * @throws IllegalArgumentException if the remaining count is odd or a byte is not a hex digit.
 */
fun ReadBuffer.decodeHexInto(dest: WriteBuffer): WriteBuffer {
    val start = position()
    val length = remaining()
    decodeHexInto(dest, start, length)
    position(start + length)
    return dest
}

// endregion
