@file:JvmName("BufferFactoryJvm")

package com.ditchoom.buffer

import java.nio.ByteBuffer

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
        AllocationZone.Heap -> JvmBuffer(ByteBuffer.allocate(size).order(byteOrderNative))
        AllocationZone.SharedMemory,
        AllocationZone.Direct,
        -> DirectJvmBuffer(ByteBuffer.allocateDirect(size).order(byteOrderNative))

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
    return JvmBuffer(ByteBuffer.wrap(array).order(byteOrderNative))
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
