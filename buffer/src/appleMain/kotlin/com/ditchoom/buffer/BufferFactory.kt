@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.ditchoom.buffer

import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.convert
import platform.Foundation.NSMutableData
import platform.Foundation.create

actual fun PlatformBuffer.Companion.allocate(
    size: Int,
    zone: AllocationZone,
    byteOrder: ByteOrder,
): PlatformBuffer {
    if (zone is AllocationZone.Custom) {
        return zone.allocator(size)
    }

    @OptIn(UnsafeNumber::class)
    return MutableDataBuffer(NSMutableData.create(length = size.convert())!!, byteOrder = byteOrder)
}

actual fun PlatformBuffer.Companion.wrap(
    array: ByteArray,
    byteOrder: ByteOrder,
): PlatformBuffer = MutableDataBuffer.wrap(array, byteOrder)

/**
 * Allocates a buffer with guaranteed native memory access (MutableDataBuffer).
 * All Apple buffers use NSMutableData which provides native pointer access.
 */
@OptIn(UnsafeNumber::class)
actual fun PlatformBuffer.Companion.allocateNative(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer = MutableDataBuffer(NSMutableData.create(length = size.convert())!!, byteOrder = byteOrder)
