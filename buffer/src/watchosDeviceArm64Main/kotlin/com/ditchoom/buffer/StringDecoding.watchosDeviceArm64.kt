@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.ditchoom.buffer

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create

/**
 * watchOS device (64-bit arm64) implementation using Foundation's NSString.
 *
 * Takes `ULong` like every other Apple target. Note this is *not* the same target as
 * `watchosArm64`, which is arm64_32 — the only 32-bit Apple target, and the sole reason this
 * function is an `expect` rather than one shared `appleMain` implementation. `NSUInteger` is 32-bit
 * there and 64-bit here, and a type whose width varies across targets cannot appear in `appleMain`'s
 * shared metadata compilation.
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
