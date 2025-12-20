package com.ditchoom.buffer

actual fun PlatformBuffer.Companion.allocate(
    size: Int,
    zone: AllocationZone,
    byteOrder: ByteOrder,
): PlatformBuffer {
    if (zone is AllocationZone.Custom) {
        return zone.allocator(size)
    }
    // Unsafe not supported on Linux native, fall through to default allocation
    return NativeBuffer(ByteArray(size), byteOrder = byteOrder)
}

actual fun PlatformBuffer.Companion.wrap(
    array: ByteArray,
    byteOrder: ByteOrder,
): PlatformBuffer = NativeBuffer(array, byteOrder = byteOrder)
