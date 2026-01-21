package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * Converts the remaining bytes of this buffer to a direct read-only ByteBuffer.
 *
 * This guarantees the returned ByteBuffer has native memory access (isDirect = true),
 * suitable for efficient I/O operations.
 *
 * **Zero-copy path:**
 * - If the buffer is already backed by a direct ByteBuffer, returns a read-only
 *   duplicate that shares the underlying native memory.
 *
 * **Copy path:**
 * - If the buffer is heap-backed, copies remaining bytes to a new direct ByteBuffer.
 */
fun ReadBuffer.toNativeData(): ByteBuffer {
    if (this is BaseJvmBuffer && byteBuffer.isDirect) {
        val duplicate = byteBuffer.duplicate()
        duplicate.position(position())
        duplicate.limit(limit())
        return duplicate.asReadOnlyBuffer()
    }
    // Copy to direct buffer
    val bytes = toByteArray()
    val direct = ByteBuffer.allocateDirect(bytes.size)
    direct.put(bytes)
    direct.flip()
    return direct.asReadOnlyBuffer()
}

/**
 * Converts the remaining bytes of this buffer to a ByteArray.
 *
 * **Zero-copy path:**
 * - If the buffer is a heap-backed [BaseJvmBuffer] where hasArray() is true,
 *   position is 0, arrayOffset is 0, and remaining equals the full array size,
 *   returns the backing array directly.
 *
 * **Copy path:**
 * - Direct ByteBuffers (hasArray() returns false): copies to new array
 * - Heap buffers with non-zero position/offset or partial remaining: copies to new array
 * - Non-JVM buffer types: copies via readByteArray()
 */
actual fun ReadBuffer.toByteArray(): ByteArray =
    when (this) {
        is BaseJvmBuffer -> {
            if (byteBuffer.hasArray()) {
                val array = byteBuffer.array()
                val offset = byteBuffer.arrayOffset()
                val pos = position()
                val rem = remaining()
                if (offset == 0 && pos == 0 && rem == array.size) {
                    array
                } else {
                    byteBuffer.toArray(rem)
                }
            } else {
                byteBuffer.toArray(remaining())
            }
        }
        else -> readByteArray(remaining())
    }

/**
 * Converts the remaining bytes of this buffer to a mutable direct ByteBuffer.
 *
 * This guarantees the returned ByteBuffer has native memory access (isDirect = true),
 * suitable for efficient I/O operations.
 *
 * **Zero-copy path:**
 * - If the buffer is already backed by a direct ByteBuffer, returns a duplicate
 *   that shares the underlying native memory.
 *
 * **Copy path:**
 * - If the buffer is heap-backed, copies remaining bytes to a new direct ByteBuffer.
 */
fun PlatformBuffer.toMutableNativeData(): ByteBuffer {
    if (this is BaseJvmBuffer && byteBuffer.isDirect) {
        val duplicate = byteBuffer.duplicate()
        duplicate.position(position())
        duplicate.limit(limit())
        return duplicate
    }
    // Copy to direct buffer
    val bytes = toByteArray()
    val direct = ByteBuffer.allocateDirect(bytes.size)
    direct.put(bytes)
    direct.flip()
    return direct
}
