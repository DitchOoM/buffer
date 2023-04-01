@file:JvmName("BufferFactoryAndroid")

package com.ditchoom.buffer

import android.os.Build
import android.os.SharedMemory
import java.nio.ByteBuffer

actual fun PlatformBuffer.Companion.allocate(
    size: Int,
    zone: AllocationZone,
    byteOrder: ByteOrder
): PlatformBuffer {
    val byteOrderNative = when (byteOrder) {
        ByteOrder.BIG_ENDIAN -> java.nio.ByteOrder.BIG_ENDIAN
        ByteOrder.LITTLE_ENDIAN -> java.nio.ByteOrder.LITTLE_ENDIAN
    }
    return when (zone) {
        AllocationZone.Heap -> JvmBuffer(ByteBuffer.allocate(size).order(byteOrderNative))
        AllocationZone.Direct -> JvmBuffer(ByteBuffer.allocateDirect(size).order(byteOrderNative))
        AllocationZone.SharedMemory ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && size > 0) {
                val sharedMemory = SharedMemory.create(null, size)
                val buffer = sharedMemory.mapReadWrite().order(byteOrderNative)
                ParcelableSharedMemoryBuffer(buffer, sharedMemory)
            } else {
                JvmBuffer(ByteBuffer.allocateDirect(size).order(byteOrderNative))
            }

        is AllocationZone.Custom -> zone.allocator(size)
    }
}

actual fun PlatformBuffer.Companion.wrap(array: ByteArray, byteOrder: ByteOrder): PlatformBuffer {
    val byteOrderNative = when (byteOrder) {
        ByteOrder.BIG_ENDIAN -> java.nio.ByteOrder.BIG_ENDIAN
        ByteOrder.LITTLE_ENDIAN -> java.nio.ByteOrder.LITTLE_ENDIAN
    }
    return JvmBuffer(ByteBuffer.wrap(array).order(byteOrderNative))
}
