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

/**
 * Maps one ASCII Base64 character to its 0..63 sextet value, or -1 if it is not a Base64 digit.
 * Accepts both the standard ('+', '/') and URL-safe ('-', '_') alphabets. Table-free (branch arithmetic).
 */
internal fun base64DecodeChar(c: Int): Int =
    when (c) {
        in 'A'.code..'Z'.code -> c - 'A'.code
        in 'a'.code..'z'.code -> c - 'a'.code + 26
        in '0'.code..'9'.code -> c - '0'.code + 52
        '+'.code, '-'.code -> 62
        '/'.code, '_'.code -> 63
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
    if (padded) return (byteCount + 2) / 3 * 4
    val rem = byteCount % 3
    return byteCount / 3 * 4 + if (rem == 0) 0 else rem + 1
}

/**
 * Worst-case number of bytes [decodeBase64Into] can produce for [charCount] input characters — i.e.
 * assuming no padding. Use it to size the destination buffer; the actual count may be smaller when the
 * input carries '=' padding. Every 4 chars decode to 3 bytes.
 */
fun base64DecodedMaxLength(charCount: Int): Int =
    charCount / 4 * 3 +
        when (charCount % 4) {
            2 -> 1
            3 -> 2
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
    val fullEnd = srcOffset + length / 3 * 3
    var idx = srcOffset
    while (idx < fullEnd) {
        val b0 = getUnchecked(idx).toInt() and 0xFF
        val b1 = getUnchecked(idx + 1).toInt() and 0xFF
        val b2 = getUnchecked(idx + 2).toInt() and 0xFF
        dest.writeByte(alphabet[b0 ushr 2].code.toByte())
        dest.writeByte(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)].code.toByte())
        dest.writeByte(alphabet[((b1 and 0x0F) shl 2) or (b2 ushr 6)].code.toByte())
        dest.writeByte(alphabet[b2 and 0x3F].code.toByte())
        idx += 3
    }
    when ((srcOffset + length) - fullEnd) {
        1 -> {
            val b0 = getUnchecked(fullEnd).toInt() and 0xFF
            dest.writeByte(alphabet[b0 ushr 2].code.toByte())
            dest.writeByte(alphabet[(b0 and 0x03) shl 4].code.toByte())
            if (padded) {
                dest.writeByte(BASE64_PAD.toByte())
                dest.writeByte(BASE64_PAD.toByte())
            }
        }
        2 -> {
            val b0 = getUnchecked(fullEnd).toInt() and 0xFF
            val b1 = getUnchecked(fullEnd + 1).toInt() and 0xFF
            dest.writeByte(alphabet[b0 ushr 2].code.toByte())
            dest.writeByte(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)].code.toByte())
            dest.writeByte(alphabet[(b1 and 0x0F) shl 2].code.toByte())
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
        val c = getByte(srcOffset + i).toInt() and 0xFF
        i++
        if (c == BASE64_PAD) break
        val v = base64DecodeChar(c)
        if (v < 0) {
            throw IllegalArgumentException("invalid base64 character at index ${srcOffset + i - 1}")
        }
        acc = (acc shl 6) or v
        bits += 6
        if (bits >= 8) {
            bits -= 8
            putByte(((acc ushr bits) and 0xFF).toByte())
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
