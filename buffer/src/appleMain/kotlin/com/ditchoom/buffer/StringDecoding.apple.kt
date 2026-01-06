@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.ditchoom.buffer

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create
import platform.darwin.NSUInteger

// Raw NSStringEncoding values (platform-agnostic, same numeric value everywhere)
// Using Long to avoid platform-specific type inference issues in metadata compilation
private const val ENCODING_ASCII: Long = 1
private const val ENCODING_ISO_LATIN1: Long = 2
private const val ENCODING_UTF8: Long = 4
private const val ENCODING_UTF16: Long = 10
private const val ENCODING_UTF16_BE: Long = 0x90000100
private const val ENCODING_UTF16_LE: Long = 0x94000100
private const val ENCODING_UTF32: Long = 0x8c000100
private const val ENCODING_UTF32_BE: Long = 0x98000100
private const val ENCODING_UTF32_LE: Long = 0x9c000100

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
    // For UTF-8, use Kotlin's built-in decoder for better performance
    if (charset == Charset.UTF8) {
        return data.decodeToString(startIndex, endIndex, throwOnInvalidSequence = true)
    }

    // Get the encoding value (platform-agnostic Long)
    val encodingValue: Long =
        when (charset) {
            Charset.UTF8 -> ENCODING_UTF8
            Charset.UTF16 -> ENCODING_UTF16
            Charset.UTF16BigEndian -> ENCODING_UTF16_BE
            Charset.UTF16LittleEndian -> ENCODING_UTF16_LE
            Charset.ASCII -> ENCODING_ASCII
            Charset.ISOLatin1 -> ENCODING_ISO_LATIN1
            Charset.UTF32 -> ENCODING_UTF32
            Charset.UTF32LittleEndian -> ENCODING_UTF32_LE
            Charset.UTF32BigEndian -> ENCODING_UTF32_BE
        }

    val length = endIndex - startIndex

    // Create NSData from the byte range
    val nsData =
        data.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(startIndex), length = length.convert<NSUInteger>())
        }

    // Decode using NSString - convert encoding to platform-specific NSUInteger
    val nsString =
        NSString.create(nsData, encodingValue.convert<NSUInteger>())
            ?: throw IllegalArgumentException("Failed to decode bytes using charset: $charset")

    return nsString.toString()
}
