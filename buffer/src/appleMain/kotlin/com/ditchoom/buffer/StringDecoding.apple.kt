@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.ditchoom.buffer

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSASCIIStringEncoding
import platform.Foundation.NSData
import platform.Foundation.NSISOLatin1StringEncoding
import platform.Foundation.NSString
import platform.Foundation.NSUTF16BigEndianStringEncoding
import platform.Foundation.NSUTF16LittleEndianStringEncoding
import platform.Foundation.NSUTF16StringEncoding
import platform.Foundation.NSUTF32BigEndianStringEncoding
import platform.Foundation.NSUTF32LittleEndianStringEncoding
import platform.Foundation.NSUTF32StringEncoding
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create

/**
 * Apple implementation of string decoding using NSString.
 * Supports a wide range of charsets via Foundation framework.
 */
internal actual fun decodeByteArrayToString(
    data: ByteArray,
    startIndex: Int,
    endIndex: Int,
    charset: Charset,
): String {
    val encoding =
        when (charset) {
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

    val length = endIndex - startIndex

    // For UTF-8, use Kotlin's built-in decoder for better performance
    if (charset == Charset.UTF8) {
        return data.decodeToString(startIndex, endIndex, throwOnInvalidSequence = true)
    }

    // Create NSData from the byte range
    val nsData =
        data.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(startIndex), length = length.toULong())
        }

    // Decode using NSString
    val nsString =
        NSString.create(nsData, encoding)
            ?: throw IllegalArgumentException("Failed to decode bytes using charset: $charset")

    return nsString.toString()
}
