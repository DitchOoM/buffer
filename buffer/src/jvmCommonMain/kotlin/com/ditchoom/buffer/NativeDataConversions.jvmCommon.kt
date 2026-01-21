package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * JVM/Android native data wrapper containing a direct ByteBuffer.
 *
 * Access the underlying ByteBuffer via [byteBuffer] property.
 */
actual class NativeData(
    val byteBuffer: ByteBuffer,
)

/**
 * JVM/Android mutable native data wrapper containing a direct ByteBuffer.
 *
 * Access the underlying ByteBuffer via [byteBuffer] property.
 */
actual class MutableNativeData(
    val byteBuffer: ByteBuffer,
)

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
actual fun ReadBuffer.toNativeData(): NativeData {
    if (this is BaseJvmBuffer && byteBuffer.isDirect) {
        val duplicate = byteBuffer.duplicate()
        duplicate.position(position())
        duplicate.limit(limit())
        return NativeData(duplicate.asReadOnlyBuffer())
    }
    // Copy to direct buffer
    val bytes = toByteArray()
    val direct = ByteBuffer.allocateDirect(bytes.size)
    direct.put(bytes)
    direct.flip()
    return NativeData(direct.asReadOnlyBuffer())
}

/**
 * Converts the remaining bytes of this buffer to a ByteArray.
 *
 * **Important:** This method does NOT modify the buffer's position.
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
                    // Copy without modifying position
                    val result = ByteArray(rem)
                    System.arraycopy(array, offset + pos, result, 0, rem)
                    result
                }
            } else {
                // Direct buffer - copy without modifying position
                val pos = position()
                val result = byteBuffer.toArray(remaining())
                (byteBuffer as java.nio.Buffer).position(pos)
                result
            }
        }
        else -> {
            val pos = position()
            val result = readByteArray(remaining())
            position(pos)
            result
        }
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
actual fun PlatformBuffer.toMutableNativeData(): MutableNativeData {
    if (this is BaseJvmBuffer && byteBuffer.isDirect) {
        val duplicate = byteBuffer.duplicate()
        duplicate.position(position())
        duplicate.limit(limit())
        return MutableNativeData(duplicate)
    }
    // Copy to direct buffer
    val bytes = toByteArray()
    val direct = ByteBuffer.allocateDirect(bytes.size)
    direct.put(bytes)
    direct.flip()
    return MutableNativeData(direct)
}
