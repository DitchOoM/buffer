@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.buffer

import android.os.Build
import android.os.SharedMemory
import java.nio.ByteBuffer

actual fun allocateNewBuffer(
    size: UInt,
    byteOrder: ByteOrder
): ParcelablePlatformBuffer {
    val byteOrderNative = when (byteOrder) {
        ByteOrder.BIG_ENDIAN -> java.nio.ByteOrder.BIG_ENDIAN
        ByteOrder.LITTLE_ENDIAN -> java.nio.ByteOrder.LITTLE_ENDIAN
    }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && size > 0u) {
        val sharedMemory = SharedMemory.create(null, size.toInt())
        val buffer = sharedMemory.mapReadWrite().order(byteOrderNative)
        ParcelableSharedMemoryBuffer(buffer, sharedMemory)
    } else {
        JvmBuffer(ByteBuffer.allocateDirect(size.toInt()).order(byteOrderNative))
    }
}


actual fun String.toBuffer(): ParcelablePlatformBuffer = JvmBuffer(ByteBuffer.wrap(encodeToByteArray()))

actual fun String.utf8Length(): UInt = encodeToByteArray().size.toUInt()
