@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.UnsafeNumber::class,
)

package com.ditchoom.buffer

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSMakeRange
import platform.Foundation.NSMutableData
import platform.Foundation.create
import platform.Foundation.subdataWithRange

/**
 * Apple native data wrapper containing NSData.
 *
 * Access the underlying NSData via [nsData] property.
 */
actual class NativeData(
    val nsData: NSData,
)

/**
 * Apple mutable native data wrapper containing NSMutableData.
 *
 * Access the underlying NSMutableData via [nsMutableData] property.
 */
actual class MutableNativeData(
    val nsMutableData: NSMutableData,
)

/**
 * Converts the remaining bytes of this buffer to NSData.
 *
 * **Scope**: Operates on remaining bytes (position to limit).
 *
 * **Position invariant**: Does NOT modify position or limit.
 *
 * **Zero-copy path:**
 * - If the buffer is an [NSDataBuffer] or [MutableDataBuffer], returns an NSData view
 *   of the remaining bytes using subdataWithRange (shares underlying memory).
 *
 * **Copy path:**
 * - Otherwise, copies the remaining bytes to a new NSData.
 */
actual fun ReadBuffer.toNativeData(): NativeData =
    NativeData(
        when (this) {
            is NSDataBuffer -> {
                val pos = position()
                val rem = remaining()
                if (pos == 0 && rem == data.length.toInt()) {
                    data
                } else {
                    data.subdataWithRange(NSMakeRange(pos.convert(), rem.convert()))
                }
            }
            is MutableDataBuffer -> {
                val pos = position()
                val rem = remaining()
                if (pos == 0 && rem == data.length.toInt()) {
                    data
                } else {
                    data.subdataWithRange(NSMakeRange(pos.convert(), rem.convert()))
                }
            }
            else -> toByteArray().toNSData()
        },
    )

/**
 * Converts the remaining bytes of this buffer to NSMutableData.
 *
 * **Scope**: Operates on remaining bytes (position to limit).
 *
 * **Position invariant**: Does NOT modify position or limit.
 *
 * **Zero-copy path:**
 * - If the buffer is a [MutableDataBuffer] at position 0 with full remaining,
 *   returns the underlying NSMutableData.
 *
 * **Copy path:**
 * - Otherwise, copies the remaining bytes to a new NSMutableData.
 *   Note: NSMutableData requires its own mutable memory, so partial views must copy.
 */
actual fun PlatformBuffer.toMutableNativeData(): MutableNativeData =
    MutableNativeData(
        when (this) {
            is MutableDataBuffer -> {
                val pos = position()
                val rem = remaining()
                if (pos == 0 && rem == data.length.toInt()) {
                    data
                } else {
                    // Note: NSMutableData may internally copy even with dataWithBytesNoCopy
                    // due to implementation details in NSConcreteMutableData.
                    // Using subdataWithRange + create for consistent behavior.
                    NSMutableData.create(data.subdataWithRange(NSMakeRange(pos.convert(), rem.convert())))
                }
            }
            else -> toByteArray().toNSMutableData()
        },
    )

/**
 * Converts a ByteArray to NSData.
 *
 * This creates a copy of the data since NSData.create(bytes:length:) copies the bytes.
 * The resulting NSData is independent of the original ByteArray.
 */
fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.convert())
        }
    }

/**
 * Converts a ByteArray to NSMutableData.
 *
 * This creates a copy of the data since NSMutableData.create(bytes:length:) copies the bytes.
 * The resulting NSMutableData is independent of the original ByteArray.
 */
fun ByteArray.toNSMutableData(): NSMutableData =
    if (isEmpty()) {
        NSMutableData()
    } else {
        usePinned { pinned ->
            NSMutableData.create(bytes = pinned.addressOf(0), length = size.convert())
        }
    }
