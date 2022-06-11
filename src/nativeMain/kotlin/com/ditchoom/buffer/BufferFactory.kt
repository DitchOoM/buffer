@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.buffer


actual fun PlatformBuffer.Companion.allocate(
    size: Int,
    byteOrder: ByteOrder,
    zone: AllocationZone
): PlatformBuffer {
    if (zone is AllocationZone.Custom) {
        return zone.allocator(size)
    }
    return NativeBuffer(ByteArray(size), byteOrder = byteOrder)
}

actual fun PlatformBuffer.Companion.wrap(array: ByteArray, byteOrder: ByteOrder): PlatformBuffer =
    NativeBuffer(array, byteOrder = byteOrder)

actual fun String.toBuffer(zone: AllocationZone): PlatformBuffer {
    val bytes = this.encodeToByteArray()
    return if (zone is AllocationZone.Custom) {
        val buffer = zone.allocator(bytes.size)
        buffer.write(bytes)
        buffer
    } else {
        NativeBuffer(bytes, byteOrder = ByteOrder.BIG_ENDIAN)
    }
}
