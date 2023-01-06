package com.ditchoom.buffer

import kotlinx.cinterop.convert
import platform.Foundation.NSMutableData
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding

actual fun PlatformBuffer.Companion.allocate(
    size: Int,
    zone: AllocationZone,
    byteOrder: ByteOrder
): PlatformBuffer {
    if (zone is AllocationZone.Custom) {
        return zone.allocator(size)
    }
    @Suppress("OPT_IN_USAGE")
    return MutableDataBuffer(NSMutableData.create(length = size.convert())!!, byteOrder = byteOrder)
}

actual fun PlatformBuffer.Companion.wrap(array: ByteArray, byteOrder: ByteOrder): PlatformBuffer =
    MutableDataBuffer.wrap(array, byteOrder)

@Throws(CharacterCodingException::class)
actual fun String.toReadBuffer(charset: Charset, zone: AllocationZone): ReadBuffer {
    return if (zone is AllocationZone.Custom) {
        @Suppress("OPT_IN_USAGE")
        val bytes = this.encodeToByteArray()
        val buffer = zone.allocator(bytes.size)
        buffer.writeBytes(bytes)
        buffer
    } else {
        @Suppress("OPT_IN_USAGE", "CAST_NEVER_SUCCEEDS")
        val data = (this as NSString).dataUsingEncoding(charset.toEncoding())!!
        DataBuffer(data, byteOrder = ByteOrder.BIG_ENDIAN)
    }
}
