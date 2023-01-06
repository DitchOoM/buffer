package com.ditchoom.buffer

actual fun PlatformBuffer.Companion.allocate(
    size: Int,
    zone: AllocationZone,
    byteOrder: ByteOrder
): PlatformBuffer {
    if (zone is AllocationZone.Custom) {
        return zone.allocator(size)
    }
    return NativeBuffer(ByteArray(size), byteOrder = byteOrder)
}

actual fun PlatformBuffer.Companion.wrap(array: ByteArray, byteOrder: ByteOrder): PlatformBuffer =
    NativeBuffer(array, byteOrder = byteOrder)

@Throws(CharacterCodingException::class)
actual fun String.toReadBuffer(charset: Charset, zone: AllocationZone): ReadBuffer {
    val bytes = this.encodeToByteArray()
    return if (charset != Charset.UTF8) {
        throw UnsupportedOperationException("Unsupported charset $charset")
    } else if (zone is AllocationZone.Custom) {
        val buffer = zone.allocator(bytes.size)
        buffer.writeBytes(bytes)
        buffer
    } else {
        NativeBuffer(bytes, byteOrder = ByteOrder.BIG_ENDIAN)
    }
}
