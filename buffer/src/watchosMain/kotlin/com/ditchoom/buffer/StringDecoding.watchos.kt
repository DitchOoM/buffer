@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.ditchoom.buffer

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSStringEncoding
import platform.Foundation.create
import platform.darwin.NSUInteger

/**
 * watchOS implementation using Foundation's NSString.
 * Uses convert() to handle the type differences between watchOS device (32-bit) and simulator (64-bit).
 */
internal actual fun decodeWithFoundation(
    data: ByteArray,
    offset: Int,
    length: Int,
    encoding: Long,
): String {
    val nsData =
        data.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(offset), length = length.convert<NSUInteger>())
        }

    return NSString.create(nsData, encoding.convert<NSStringEncoding>())?.toString()
        ?: throw IllegalArgumentException("Failed to decode bytes with encoding: $encoding")
}
