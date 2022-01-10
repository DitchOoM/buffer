@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.buffer

import android.os.Build
import android.os.SharedMemory
import java.nio.ByteBuffer

actual fun allocateNewBuffer(
    size: UInt
): ParcelablePlatformBuffer {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        val sharedMemory = SharedMemory.create(null, size.toInt())
        val buffer = sharedMemory.mapReadWrite()
        ParcelableSharedMemoryBuffer(buffer, sharedMemory)
    } else {
        JvmBuffer(ByteBuffer.allocateDirect(size.toInt()))
    }
}


actual fun String.toBuffer(): ParcelablePlatformBuffer = JvmBuffer(ByteBuffer.wrap(encodeToByteArray()))

actual fun String.utf8Length(): UInt = encodeToByteArray().size.toUInt()
