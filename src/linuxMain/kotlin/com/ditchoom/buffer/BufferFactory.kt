package com.ditchoom.buffer

actual fun PlatformBuffer.Companion.allocate(
    size: Int,
    zone: AllocationZone,
    byteOrder: ByteOrder,
): PlatformBuffer {
    if (zone is AllocationZone.Custom) {
        return zone.allocator(size)
    }
    if (zone is AllocationZone.Unsafe) {
        throw UnsupportedOperationException(
            "UnsafeBuffer cannot be returned as PlatformBuffer. " +
                "Use UnsafeBuffer.allocate() or UnsafeBuffer.withBuffer() directly.",
        )
    }
    return NativeBuffer(ByteArray(size), byteOrder = byteOrder)
}

actual fun PlatformBuffer.Companion.wrap(
    array: ByteArray,
    byteOrder: ByteOrder,
): PlatformBuffer = NativeBuffer(array, byteOrder = byteOrder)
