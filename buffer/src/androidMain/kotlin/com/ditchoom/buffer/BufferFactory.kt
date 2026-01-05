@file:JvmName("BufferFactoryAndroid")
@file:Suppress("DEPRECATION")

package com.ditchoom.buffer

import android.annotation.SuppressLint
import android.os.Build
import android.os.SharedMemory
import java.nio.ByteBuffer

@SuppressLint("NewApi") // SharedMemory API (API 27+) is guarded with Build.VERSION.SDK_INT check
actual fun PlatformBuffer.Companion.allocate(
    size: Int,
    zone: AllocationZone,
    byteOrder: ByteOrder,
): PlatformBuffer {
    val byteOrderNative =
        when (byteOrder) {
            ByteOrder.BIG_ENDIAN -> java.nio.ByteOrder.BIG_ENDIAN
            ByteOrder.LITTLE_ENDIAN -> java.nio.ByteOrder.LITTLE_ENDIAN
        }
    return when (zone) {
        AllocationZone.Heap -> HeapJvmBuffer(ByteBuffer.allocate(size).order(byteOrderNative))
        AllocationZone.Direct -> DirectJvmBuffer(ByteBuffer.allocateDirect(size).order(byteOrderNative))
        AllocationZone.SharedMemory ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && size > 0) {
                val sharedMemory = SharedMemory.create(null, size)
                val buffer = sharedMemory.mapReadWrite().order(byteOrderNative)
                ParcelableSharedMemoryBuffer(buffer, sharedMemory)
            } else {
                DirectJvmBuffer(ByteBuffer.allocateDirect(size).order(byteOrderNative))
            }

        is AllocationZone.Custom -> zone.allocator(size)
    }
}

actual fun PlatformBuffer.Companion.wrap(
    array: ByteArray,
    byteOrder: ByteOrder,
): PlatformBuffer {
    val byteOrderNative =
        when (byteOrder) {
            ByteOrder.BIG_ENDIAN -> java.nio.ByteOrder.BIG_ENDIAN
            ByteOrder.LITTLE_ENDIAN -> java.nio.ByteOrder.LITTLE_ENDIAN
        }
    return HeapJvmBuffer(ByteBuffer.wrap(array).order(byteOrderNative))
}

/**
 * Allocates a buffer with guaranteed native memory access (DirectJvmBuffer).
 * Uses a direct ByteBuffer with accessible native memory address.
 */
actual fun PlatformBuffer.Companion.allocateNative(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer {
    val byteOrderNative =
        when (byteOrder) {
            ByteOrder.BIG_ENDIAN -> java.nio.ByteOrder.BIG_ENDIAN
            ByteOrder.LITTLE_ENDIAN -> java.nio.ByteOrder.LITTLE_ENDIAN
        }
    return DirectJvmBuffer(ByteBuffer.allocateDirect(size).order(byteOrderNative))
}

/**
 * Allocates a buffer with shared memory (SharedMemory) if available.
 * Falls back to DirectJvmBuffer if SharedMemory is not supported (API < 27) or size is 0.
 *
 * SharedMemory enables zero-copy IPC via Parcelable on Android.
 */
@SuppressLint("NewApi")
actual fun PlatformBuffer.Companion.allocateShared(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer = allocate(size, AllocationZone.SharedMemory, byteOrder)
