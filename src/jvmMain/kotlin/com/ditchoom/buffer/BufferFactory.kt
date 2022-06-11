@file:JvmName("BufferFactoryJvm")
package com.ditchoom.buffer

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetEncoder


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
actual fun String.toBuffer(zone: AllocationZone): PlatformBuffer {
    val encoder = utf8Encoder.get()
    encoder.reset()
    val out = PlatformBuffer.allocate(utf8Length(), zone = zone) as JvmBuffer
    encoder.encode(CharBuffer.wrap(this), out.byteBuffer, true)
    out.byteBuffer.flip()
    return out
}

private val utf8Encoder = object : ThreadLocal<CharsetEncoder>() {
    override fun initialValue(): CharsetEncoder? = Charsets.UTF_8.newEncoder()
    override fun get(): CharsetEncoder = super.get()!!
}
