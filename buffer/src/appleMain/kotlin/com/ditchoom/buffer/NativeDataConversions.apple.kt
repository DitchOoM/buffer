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
 * Converts the remaining bytes of this buffer to NSData.
 *
 * - If the buffer is an [NSDataBuffer] or [MutableDataBuffer], returns an NSData view
 *   of the remaining bytes using subdataWithRange (zero-copy, shares underlying memory)
 * - Otherwise, copies the remaining bytes to a new NSData
 */
fun ReadBuffer.toNativeData(): NSData =
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
    }

/**
 * Converts the remaining bytes of this buffer to NSMutableData.
 *
 * - If the buffer is a [MutableDataBuffer] at position 0 with full remaining,
 *   returns the underlying NSMutableData (zero-copy)
 * - Otherwise, copies the remaining bytes to a new NSMutableData
 */
fun PlatformBuffer.toMutableNativeData(): NSMutableData =
    when (this) {
        is MutableDataBuffer -> {
            val pos = position()
            val rem = remaining()
            if (pos == 0 && rem == data.length.toInt()) {
                data
            } else {
                NSMutableData.create(data.subdataWithRange(NSMakeRange(pos.convert(), rem.convert())))
            }
        }
        else -> toByteArray().toNSMutableData()
    }

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
