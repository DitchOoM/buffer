package com.ditchoom.buffer

// Zero-copy (buffer-to-buffer, no intermediate ByteArray/String) Base64 encode/decode, RFC 4648.
//
// Like hex (see HexCodec.kt), encoding expands the data (3 bytes -> 4 ASCII chars) so it cannot be done
// in place; "zero-copy" means the bytes flow straight from one buffer into another with no String /
// ByteArray staging. The common code here is the portable fallback (check the source range once, then
// loop over the unchecked source accessor); native NativeMemoryAccess buffers override the members to
// run the whole transform in C (buf_base64_encode / buf_base64_decode), the same shape as hashRange/hex.
//
// Both the standard ('+' '/') and URL-safe ('-' '_') alphabets are supported. Decode is lenient: it
// accepts either alphabet and tolerates missing padding, so it round-trips any encoder variant.

internal const val BASE64_STD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
internal const val BASE64_URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
private const val BASE64_PAD = '='.code

// RFC 4648 alphabet offsets: 'a'..'z' follow the 26 upper-case letters, '0'..'9' follow the 52 letters.
private const val BASE64_LOWERCASE_OFFSET = 26
private const val BASE64_DIGIT_OFFSET = 52

/** Sextet value of '+'/'-' (index 62) and '/'/'_' (index 63) in the Base64 alphabet. */
private const val BASE64_PLUS_VALUE = 62
private const val BASE64_SLASH_VALUE = 63

/** Base64 groups 3 input bytes into 4 output characters. */
private const val BYTES_PER_GROUP = 3
private const val CHARS_PER_GROUP = 4

/** Bits per Base64 sextet (input/output unit widths). */
private const val SEXTET_BITS = 6
private const val OCTET_BITS = 8

/** Mask isolating one Base64 sextet (6 bits). */
private const val SEXTET_MASK = 0x3F

/** Low-2-bit mask carried from the first byte into the second sextet. */
private const val LOW_TWO_BITS = 0x03

/** Low-4-bit mask carried from the second byte into the third sextet. */
private const val LOW_FOUR_BITS = 0x0F

/** Shift amounts that align the carried bits within a Base64 group. */
private const val SHIFT_TWO = 2
private const val SHIFT_FOUR = 4

/** Output-length remainder of 2 input bytes within a group decodes to 1 extra byte; 3 → 2. */
private const val GROUP_TAIL_TWO = 2
private const val GROUP_TAIL_THREE = 3

/**
 * Maps one ASCII Base64 character to its 0..63 sextet value, or -1 if it is not a Base64 digit.
 * Accepts both the standard ('+', '/') and URL-safe ('-', '_') alphabets. Table-free (branch arithmetic).
 */
internal fun base64DecodeChar(c: Int): Int =
    when (c) {
        in 'A'.code..'Z'.code -> c - 'A'.code
        in 'a'.code..'z'.code -> c - 'a'.code + BASE64_LOWERCASE_OFFSET
        in '0'.code..'9'.code -> c - '0'.code + BASE64_DIGIT_OFFSET
        '+'.code, '-'.code -> BASE64_PLUS_VALUE
        '/'.code, '_'.code -> BASE64_SLASH_VALUE
        else -> -1
    }

/**
 * Number of ASCII characters [encodeBase64Into] produces for [byteCount] input bytes.
 * Padded output is always a multiple of 4; unpadded output drops the trailing '=' fillers.
 */
fun base64EncodedLength(
    byteCount: Int,
    padded: Boolean = true,
): Int {
    if (padded) return (byteCount + GROUP_TAIL_TWO) / BYTES_PER_GROUP * CHARS_PER_GROUP
    val rem = byteCount % BYTES_PER_GROUP
    return byteCount / BYTES_PER_GROUP * CHARS_PER_GROUP + if (rem == 0) 0 else rem + 1
}

/**
 * Worst-case number of bytes [decodeBase64Into] can produce for [charCount] input characters — i.e.
 * assuming no padding. Use it to size the destination buffer; the actual count may be smaller when the
 * input carries '=' padding. Every 4 chars decode to 3 bytes.
 */
fun base64DecodedMaxLength(charCount: Int): Int =
    charCount / CHARS_PER_GROUP * BYTES_PER_GROUP +
        when (charCount % CHARS_PER_GROUP) {
            GROUP_TAIL_TWO -> 1
            GROUP_TAIL_THREE -> 2
            else -> 0
        }

/**
 * Portable encode path: reads `length` source bytes from [this] (via the unchecked accessor) and emits
 * Base64 ASCII into [dest]. The caller has already range-checked the source.
 *
 * Writes each output char with an individual [WriteBuffer.writeByte]. Packing a full group's 4 chars
 * into a single `writeInt` was measured neutral on the JVM and regressed hex's `writeShort` variant
 * (`putInt`/`putShort` on a direct ByteBuffer is no cheaper than the byte puts), so the simple per-byte
 * path stays. The native buffers bypass this via the C path (see NativeBase64.kt); this is the fallback
 * they share too.
 */
internal fun ReadBuffer.encodeBase64Common(
    srcOffset: Int,
    length: Int,
    urlSafe: Boolean,
    padded: Boolean,
    dest: WriteBuffer,
) {
    val alphabet = if (urlSafe) BASE64_URL_ALPHABET else BASE64_STD_ALPHABET
    val fullEnd = srcOffset + length / BYTES_PER_GROUP * BYTES_PER_GROUP
    var idx = srcOffset
    while (idx < fullEnd) {
        val b0 = getUnchecked(idx).toInt() and BufferConstants.BYTE_MASK
        val b1 = getUnchecked(idx + 1).toInt() and BufferConstants.BYTE_MASK
        val b2 = getUnchecked(idx + 2).toInt() and BufferConstants.BYTE_MASK
        dest.writeByte(alphabet[b0 ushr SHIFT_TWO].code.toByte())
        dest.writeByte(alphabet[((b0 and LOW_TWO_BITS) shl SHIFT_FOUR) or (b1 ushr SHIFT_FOUR)].code.toByte())
        dest.writeByte(alphabet[((b1 and LOW_FOUR_BITS) shl SHIFT_TWO) or (b2 ushr SEXTET_BITS)].code.toByte())
        dest.writeByte(alphabet[b2 and SEXTET_MASK].code.toByte())
        idx += BYTES_PER_GROUP
    }
    when ((srcOffset + length) - fullEnd) {
        1 -> {
            val b0 = getUnchecked(fullEnd).toInt() and BufferConstants.BYTE_MASK
            dest.writeByte(alphabet[b0 ushr SHIFT_TWO].code.toByte())
            dest.writeByte(alphabet[(b0 and LOW_TWO_BITS) shl SHIFT_FOUR].code.toByte())
            if (padded) {
                dest.writeByte(BASE64_PAD.toByte())
                dest.writeByte(BASE64_PAD.toByte())
            }
        }
        2 -> {
            val b0 = getUnchecked(fullEnd).toInt() and BufferConstants.BYTE_MASK
            val b1 = getUnchecked(fullEnd + 1).toInt() and BufferConstants.BYTE_MASK
            dest.writeByte(alphabet[b0 ushr SHIFT_TWO].code.toByte())
            dest.writeByte(alphabet[((b0 and LOW_TWO_BITS) shl SHIFT_FOUR) or (b1 ushr SHIFT_FOUR)].code.toByte())
            dest.writeByte(alphabet[(b1 and LOW_FOUR_BITS) shl SHIFT_TWO].code.toByte())
            if (padded) dest.writeByte(BASE64_PAD.toByte())
        }
    }
}

/**
 * Portable decode fallback: reads `length` Base64 ASCII source bytes via [getByte] and emits the decoded
 * bytes via [putByte]. Accepts both alphabets; stops at the first '=' padding. The caller has already
 * range-checked the source.
 *
 * @throws IllegalArgumentException if a source byte (before padding) is not a Base64 digit.
 */
internal inline fun decodeBase64Fallback(
    srcOffset: Int,
    length: Int,
    getByte: (Int) -> Byte,
    putByte: (Byte) -> Unit,
) {
    var acc = 0
    var bits = 0
    var i = 0
    while (i < length) {
        val c = getByte(srcOffset + i).toInt() and BufferConstants.BYTE_MASK
        i++
        if (c == BASE64_PAD) break
        val v = base64DecodeChar(c)
        if (v < 0) {
            throw IllegalArgumentException("invalid base64 character at index ${srcOffset + i - 1}")
        }
        acc = (acc shl SEXTET_BITS) or v
        bits += SEXTET_BITS
        if (bits >= OCTET_BITS) {
            bits -= OCTET_BITS
            putByte(((acc ushr bits) and BufferConstants.BYTE_MASK).toByte())
        }
    }
}

// region public relative convenience

/**
 * Relative: Base64-encodes this buffer's remaining bytes into [dest], advancing this buffer's [position]
 * to its [limit] and [dest]'s position by the encoded length.
 *
 * @param dest destination buffer that receives the ASCII Base64 bytes
 * @param urlSafe use the URL-safe alphabet ('-' '_') instead of the standard one ('+' '/')
 * @param padded append '=' padding so the output length is a multiple of 4
 */
fun ReadBuffer.encodeBase64Into(
    dest: WriteBuffer,
    urlSafe: Boolean = false,
    padded: Boolean = true,
): WriteBuffer {
    val start = position()
    val length = remaining()
    encodeBase64Into(dest, start, length, urlSafe, padded)
    position(start + length)
    return dest
}

/**
 * Relative: Base64-decodes this buffer's remaining ASCII bytes into [dest], advancing this buffer's
 * [position] to its [limit] and [dest]'s position by the decoded length.
 *
 * Accepts both alphabets and tolerates missing padding.
 *
 * @throws IllegalArgumentException if a byte (before padding) is not a Base64 digit.
 */
fun ReadBuffer.decodeBase64Into(dest: WriteBuffer): WriteBuffer {
    val start = position()
    val length = remaining()
    decodeBase64Into(dest, start, length)
    position(start + length)
    return dest
}

// endregion
