@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.ditchoom.buffer

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create

/**
 * Foundation `NSString` decoding for `watchosArm64` — the arm64_32 ABI, 64-bit registers with 32-bit
 * pointers, and the only Apple target where `NSUInteger` is 32 bits wide. Hence `toUInt()` where
 * every other Apple target uses `toULong()`; see `StringDecoding.appleLp64.kt` for that half.
 *
 * Not to be confused with `watchosDeviceArm64`, the 64-bit watchOS device target, which is LP64 and
 * therefore lives in `appleLp64Main`.
 */
internal actual fun decodeWithFoundation(
    data: ByteArray,
    offset: Int,
    length: Int,
    encoding: Long,
): String {
    val nsData =
        data.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(offset), length = length.toUInt())
        }

    return NSString.create(nsData, encoding.toUInt())?.toString()
        ?: throw IllegalArgumentException("Failed to decode bytes with encoding: $encoding")
}
