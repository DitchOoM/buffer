package com.ditchoom.buffer

import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

fun PlatformBuffer.Companion.allocate(
    size: Int,
    byteOrder: ByteOrder
) = allocate(size, AllocationZone.Heap, byteOrder)

actual fun PlatformBuffer.Companion.allocate(
    size: Int,
    zone: AllocationZone,
    byteOrder: ByteOrder
): PlatformBuffer {
    if (zone is AllocationZone.Custom) {
        return zone.allocator(size)
    }
    return JsBuffer(Uint8Array(size), littleEndian = byteOrder == ByteOrder.LITTLE_ENDIAN)
}

// TODO: Wrap shouldn't duplicate data. Look into direct case to Uint8Array to wrap with the JsBuffer or use NativeBuffer
actual fun PlatformBuffer.Companion.wrap(array: ByteArray, byteOrder: ByteOrder): PlatformBuffer =
    //NativeBuffer(array, byteOrder = byteOrder)
    JsBuffer(Uint8Array(array.toTypedArray()), littleEndian = byteOrder == ByteOrder.LITTLE_ENDIAN)


fun String.toBuffer(): PlatformBuffer = toBuffer(AllocationZone.Heap)
actual fun String.toBuffer(zone: AllocationZone): PlatformBuffer {
    val bytes = encodeToByteArray()
    return if (zone is AllocationZone.Custom) {
        val buffer = zone.allocator(bytes.size)
        buffer.write(bytes)
        buffer
    } else {
        val int8Array = bytes.unsafeCast<Int8Array>()
        val uint8Array = Uint8Array(int8Array.buffer)
        JsBuffer(uint8Array)
    }
}
