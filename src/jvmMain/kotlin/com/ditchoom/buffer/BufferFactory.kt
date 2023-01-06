@file:JvmName("BufferFactoryJvm")

package com.ditchoom.buffer

import java.nio.ByteBuffer
import java.nio.CharBuffer

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
        AllocationZone.AndroidSharedMemory,
        AllocationZone.Direct -> JvmBuffer(ByteBuffer.allocateDirect(size).order(byteOrderNative))
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

@Throws(CharacterCodingException::class)
actual fun String.toReadBuffer(charset: Charset, zone: AllocationZone): ReadBuffer {
    val encoder = charset.toEncoder()
    encoder.reset()
    val out = PlatformBuffer.allocate(utf8Length(), zone = zone) as JvmBuffer
    encoder.encode(CharBuffer.wrap(this), out.byteBuffer, true)
    out.resetForRead()
    return out
}
