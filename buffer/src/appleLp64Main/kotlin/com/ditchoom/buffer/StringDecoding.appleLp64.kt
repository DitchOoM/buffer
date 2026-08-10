@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.ditchoom.buffer

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create

/**
 * Foundation `NSString` decoding for every Apple target with 64-bit pointers — macOS, iOS, tvOS, and
 * the three 64-bit watchOS targets (`watchosDeviceArm64`, `watchosSimulatorArm64`, `watchosX64`).
 *
 * `NSUInteger` is 64 bits wide here, so the length and encoding arguments take `toULong()`. The one
 * Apple target this does not cover is `watchosArm64` (arm64_32), whose 32-bit `NSUInteger` needs
 * `toUInt()` — see `StringDecoding.watchosArm64.kt`. Membership of this source set is decided by
 * Konan target in `build.gradle.kts`, not by a list of names.
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
