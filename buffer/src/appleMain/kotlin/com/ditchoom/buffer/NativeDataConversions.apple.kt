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
import platform.Foundation.NSMutableData
import platform.Foundation.create

/**
 * Converts this buffer to NSData.
 *
 * - If the buffer is an [NSDataBuffer], returns the underlying NSData (zero-copy)
 * - If the buffer is a [MutableDataBuffer], returns the underlying NSMutableData as NSData (zero-copy)
 * - Otherwise, copies the remaining bytes to a new NSData
 */
fun ReadBuffer.toNativeData(): NSData =
    when (this) {
        is NSDataBuffer -> data
        is MutableDataBuffer -> data
        else -> toByteArray().toNSData()
    }

/**
 * Converts this buffer to NSMutableData.
 *
 * - If the buffer is a [MutableDataBuffer], returns the underlying NSMutableData (zero-copy)
 * - Otherwise, copies the remaining bytes to a new NSMutableData
 */
fun PlatformBuffer.toMutableNativeData(): NSMutableData =
    when (this) {
        is MutableDataBuffer -> data
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
            NSMutableData.create(bytes = pinned.addressOf(0), length = size.convert())!!
        }
    }
