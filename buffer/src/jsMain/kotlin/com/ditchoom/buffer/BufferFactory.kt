package com.ditchoom.buffer

import js.buffer.SharedArrayBuffer
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array

fun PlatformBuffer.Companion.allocate(
    size: Int,
    byteOrder: ByteOrder,
) = allocate(size, AllocationZone.SharedMemory, byteOrder)

actual fun PlatformBuffer.Companion.allocate(
    size: Int,
    zone: AllocationZone,
    byteOrder: ByteOrder,
): PlatformBuffer {
    if (zone is AllocationZone.Custom) {
        return zone.allocator(size)
    }
    val sharedArrayBuffer =
        try {
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
                "see: https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects" +
                "/SharedArrayBuffer#security_requirements",
        )
    }
    return if (sharedArrayBuffer != null) {
        val arrayBuffer = sharedArrayBuffer.unsafeCast<ArrayBuffer>().slice(0, size)
        JsBuffer(
            Int8Array(arrayBuffer),
            byteOrder,
            sharedArrayBuffer = sharedArrayBuffer,
        )
    } else {
        JsBuffer(Int8Array(size), byteOrder)
    }
}

actual fun PlatformBuffer.Companion.wrap(
    array: ByteArray,
    byteOrder: ByteOrder,
): PlatformBuffer =
    JsBuffer(
        array.unsafeCast<Int8Array>(),
        byteOrder,
    )

/**
 * Allocates a buffer with guaranteed native memory access (JsBuffer).
 * In JavaScript, all buffers have native access via ArrayBuffer.
 */
actual fun PlatformBuffer.Companion.allocateNative(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer = JsBuffer(Int8Array(size), byteOrder)

/**
 * Allocates a buffer with shared memory (SharedArrayBuffer) if available.
 * Falls back to regular ArrayBuffer if SharedArrayBuffer is not supported.
 *
 * Note: SharedArrayBuffer requires CORS headers:
 * - Cross-Origin-Opener-Policy: same-origin
 * - Cross-Origin-Embedder-Policy: require-corp
 */
actual fun PlatformBuffer.Companion.allocateShared(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer = allocate(size, AllocationZone.SharedMemory, byteOrder)
