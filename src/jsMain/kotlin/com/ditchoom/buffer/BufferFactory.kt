@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.buffer

import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

actual fun allocateNewBuffer(
    size: UInt,
    byteOrder: ByteOrder
): ParcelablePlatformBuffer {
    return JsBuffer(Uint8Array(size.toInt()), littleEndian = byteOrder == ByteOrder.LITTLE_ENDIAN)
}

actual fun String.toBuffer(): ParcelablePlatformBuffer {
    val int8Array = encodeToByteArray().unsafeCast<Int8Array>()
    val uint8Array = Uint8Array(int8Array.buffer)
    return JsBuffer(uint8Array)
}

actual fun String.utf8Length(): UInt = encodeToByteArray().unsafeCast<Int8Array>().length.toUInt()