
package com.ditchoom.buffer


actual fun PlatformBuffer.Companion.allocate(
    size: Int,
<<<<<<< HEAD
    byteOrder: ByteOrder,
    zone: AllocationZone
): PlatformBuffer {
    if (zone is AllocationZone.Custom) {
        return zone.allocator(size)
    }
    return NativeBuffer(ByteArray(size), byteOrder = byteOrder)
}
=======
    byteOrder: ByteOrder
): PlatformBuffer = NativeBuffer(ByteArray(size), byteOrder = byteOrder)
>>>>>>> 58e06ae9adb974e31ac1221c7923565fe230da78

actual fun PlatformBuffer.Companion.wrap(array: ByteArray, byteOrder: ByteOrder): PlatformBuffer =
    NativeBuffer(array, byteOrder = byteOrder)

<<<<<<< HEAD
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
=======
actual fun String.toBuffer(): PlatformBuffer = NativeBuffer(this.encodeToByteArray(), byteOrder = ByteOrder.BIG_ENDIAN)
actual fun String.utf8Length(): Int = encodeToByteArray().size
>>>>>>> 58e06ae9adb974e31ac1221c7923565fe230da78
