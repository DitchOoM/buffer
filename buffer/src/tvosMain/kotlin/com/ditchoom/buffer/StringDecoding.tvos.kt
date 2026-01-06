@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.ditchoom.buffer

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create

/**
 * tvOS implementation using Foundation's NSString.
 * On 64-bit tvOS, NSString.create expects ULong for encoding.
 */
internal actual fun decodeWithFoundation(
    data: ByteArray,
    offset: Int,
    length: Int,
    encoding: Long,
): String {
    val nsData =
        data.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(offset), length = length.toULong())
        }

    return NSString.create(nsData, encoding.toULong())?.toString()
        ?: throw IllegalArgumentException("Failed to decode bytes with encoding: $encoding")
}
