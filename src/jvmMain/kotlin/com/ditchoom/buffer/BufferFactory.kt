@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.buffer

import java.nio.ByteBuffer

actual fun allocateNewBuffer(
    size: UInt,
    byteOrder: ByteOrder
): ParcelablePlatformBuffer {
    val nativeOrder = when (byteOrder) {
        ByteOrder.BIG_ENDIAN -> java.nio.ByteOrder.BIG_ENDIAN
        ByteOrder.LITTLE_ENDIAN -> java.nio.ByteOrder.LITTLE_ENDIAN
    }
    return JvmBuffer(ByteBuffer.allocateDirect(size.toInt()).order(nativeOrder))
}


actual fun String.toBuffer(): ParcelablePlatformBuffer = JvmBuffer(ByteBuffer.wrap(encodeToByteArray()))

actual fun String.utf8Length(): UInt = encodeToByteArray().size.toUInt()
