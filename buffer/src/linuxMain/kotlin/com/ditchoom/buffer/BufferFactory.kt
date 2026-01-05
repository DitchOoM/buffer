package com.ditchoom.buffer

actual fun PlatformBuffer.Companion.allocate(
    size: Int,
    zone: AllocationZone,
    byteOrder: ByteOrder,
): PlatformBuffer {
    if (zone is AllocationZone.Custom) {
        return zone.allocator(size)
    }
    return NativeBuffer(ByteArray(size), byteOrder = byteOrder)
}

actual fun PlatformBuffer.Companion.wrap(
    array: ByteArray,
    byteOrder: ByteOrder,
): PlatformBuffer = NativeBuffer(array, byteOrder = byteOrder)

/**
 * Allocates a buffer with guaranteed native memory access.
 *
 * @throws UnsupportedOperationException Linux/Native uses ByteArray which doesn't provide
 *         direct native memory access. Use JVM or Apple platforms for native memory support.
 */
actual fun PlatformBuffer.Companion.allocateNative(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer {
    throw UnsupportedOperationException(
        "Native memory access is not supported on Linux. " +
            "NativeBuffer uses Kotlin ByteArray which lives in managed memory.",
    )
}

/**
 * Allocates a buffer with shared memory support.
 * On Linux/Native, falls back to regular allocation (no cross-process shared memory).
 */
actual fun PlatformBuffer.Companion.allocateShared(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer = allocate(size, AllocationZone.Direct, byteOrder)
