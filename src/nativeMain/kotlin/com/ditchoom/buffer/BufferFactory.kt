@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.buffer


actual fun allocateNewBuffer(
    size: UInt,
    byteOrder: ByteOrder
): ParcelablePlatformBuffer = NativeBuffer(ByteArray(size.toInt()), byteOrder = byteOrder)

actual fun String.toBuffer(): ParcelablePlatformBuffer = NativeBuffer(this.encodeToByteArray(), byteOrder = ByteOrder.BIG_ENDIAN)
actual fun String.utf8Length(): UInt = encodeToByteArray().size.toUInt()
