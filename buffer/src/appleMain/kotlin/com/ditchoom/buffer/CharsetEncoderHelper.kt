package com.ditchoom.buffer

import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSASCIIStringEncoding
import platform.Foundation.NSData
import platform.Foundation.NSISOLatin1StringEncoding
import platform.Foundation.NSString
import platform.Foundation.NSStringEncoding
import platform.Foundation.NSUTF16BigEndianStringEncoding
import platform.Foundation.NSUTF16LittleEndianStringEncoding
import platform.Foundation.NSUTF16StringEncoding
import platform.Foundation.NSUTF32BigEndianStringEncoding
import platform.Foundation.NSUTF32LittleEndianStringEncoding
import platform.Foundation.NSUTF32StringEncoding
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding

@OptIn(UnsafeNumber::class)
fun Charset.toEncoding(): NSStringEncoding =
    when (this) {
        Charset.UTF8 -> NSUTF8StringEncoding
        Charset.UTF16 -> NSUTF16StringEncoding
        Charset.UTF16BigEndian -> NSUTF16BigEndianStringEncoding
        Charset.UTF16LittleEndian -> NSUTF16LittleEndianStringEncoding
        Charset.ASCII -> NSASCIIStringEncoding
        Charset.ISOLatin1 -> NSISOLatin1StringEncoding
        Charset.UTF32 -> NSUTF32StringEncoding
        Charset.UTF32LittleEndian -> NSUTF32LittleEndianStringEncoding
        Charset.UTF32BigEndian -> NSUTF32BigEndianStringEncoding
    }

/**
 * Encodes [text] with [charset] via Foundation, without crashing on unrepresentable input.
 *
 * `NSString.dataUsingEncoding` returns `null` when the text cannot be represented in the
 * target encoding — most importantly for any unpaired UTF-16 surrogate (in every Unicode
 * encoding except UTF-16 itself; `allowLossyConversion` does not change this). For UTF-8 the
 * fallback substitutes U+FFFD via Kotlin's encoder, matching [ByteArrayBuffer], JS, and WASM —
 * and matching what [CharSequence.utf8Length] sizes. For other charsets unrepresentable text
 * throws [IllegalArgumentException] instead of a bare null-pointer crash.
 */
@OptIn(UnsafeNumber::class)
internal fun encodeToNSData(
    text: CharSequence,
    charset: Charset,
): NSData {
    val string =
        if (text is String) {
            @Suppress("CAST_NEVER_SUCCEEDS")
            text as NSString
        } else {
            @Suppress("CAST_NEVER_SUCCEEDS")
            text.toString() as NSString
        }
    val encoded = string.dataUsingEncoding(charset.toEncoding())
    if (encoded != null) return encoded
    require(charset == Charset.UTF8) { "Text is not representable in $charset" }
    return text.toString().encodeToByteArray().toNSData()
}
