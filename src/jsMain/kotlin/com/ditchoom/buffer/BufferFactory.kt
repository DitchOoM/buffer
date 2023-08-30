package com.ditchoom.buffer

import js.buffer.SharedArrayBuffer
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array

fun PlatformBuffer.Companion.allocate(
    size: Int,
    byteOrder: ByteOrder
) = allocate(size, AllocationZone.SharedMemory, byteOrder)

actual fun PlatformBuffer.Companion.allocate(
    size: Int,
    zone: AllocationZone,
    byteOrder: ByteOrder
): PlatformBuffer {
    if (zone is AllocationZone.Custom) {
        return zone.allocator(size)
    }
    val sharedArrayBuffer = try {
        if (zone is AllocationZone.SharedMemory) {
            SharedArrayBuffer(size)
        } else {
            null
        }
    } catch (t: Throwable) {
        null
    }
    if (sharedArrayBuffer == null && zone is AllocationZone.SharedMemory) {
        console.warn(
            "Failed to allocate shared buffer in BufferFactory.kt. Please check and validate the " +
                "appropriate headers are set on the http request as defined in the SharedArrayBuffer MDN docs." +
                "see: https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/SharedArrayBuffer#security_requirements"
        )
    }
    return if (sharedArrayBuffer != null) {
        val arrayBuffer = sharedArrayBuffer.unsafeCast<ArrayBuffer>().slice(0, size)
        JsBuffer(
            Uint8Array(arrayBuffer),
            littleEndian = byteOrder == ByteOrder.LITTLE_ENDIAN,
            sharedArrayBuffer = sharedArrayBuffer
        )
    } else {
        JsBuffer(Uint8Array(size), littleEndian = byteOrder == ByteOrder.LITTLE_ENDIAN)
    }
}

actual fun PlatformBuffer.Companion.wrap(array: ByteArray, byteOrder: ByteOrder): PlatformBuffer =
    JsBuffer(
        array.unsafeCast<Uint8Array>(),
        littleEndian = byteOrder == ByteOrder.LITTLE_ENDIAN
    )
