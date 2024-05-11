package com.ditchoom.buffer

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
    return MutableDataBuffer(NSMutableData.create(length = size.convert())!!, byteOrder = byteOrder)
}

actual fun PlatformBuffer.Companion.wrap(
    array: ByteArray,
    byteOrder: ByteOrder,
): PlatformBuffer = MutableDataBuffer.wrap(array, byteOrder)
