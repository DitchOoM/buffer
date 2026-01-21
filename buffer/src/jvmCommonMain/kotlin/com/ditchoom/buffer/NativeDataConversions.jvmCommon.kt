package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * Converts the remaining bytes of this buffer to a read-only ByteBuffer.
 *
 * **Zero-copy path:**
 * - If the buffer is a [BaseJvmBuffer], returns a read-only duplicate that shares
 *   the underlying memory. The returned buffer preserves the direct/heap nature
 *   of the original (isDirect matches the source buffer).
 *
 * **Copy path:**
 * - Otherwise, copies remaining bytes to a new heap-backed ByteBuffer.
 */
fun ReadBuffer.toNativeData(): ByteBuffer =
    when (this) {
        is BaseJvmBuffer -> {
            val duplicate = byteBuffer.duplicate()
            duplicate.position(position())
            duplicate.limit(limit())
            duplicate.asReadOnlyBuffer()
        }
        else -> ByteBuffer.wrap(toByteArray()).asReadOnlyBuffer()
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
 * Converts the remaining bytes of this buffer to a mutable ByteBuffer.
 *
 * **Zero-copy path:**
 * - If the buffer is a [BaseJvmBuffer], returns a duplicate that shares the underlying
 *   memory. The returned buffer preserves the direct/heap nature of the original.
 *
 * **Copy path:**
 * - Otherwise, copies remaining bytes to a new heap-backed ByteBuffer.
 */
fun PlatformBuffer.toMutableNativeData(): ByteBuffer =
    when (this) {
        is BaseJvmBuffer -> {
            val duplicate = byteBuffer.duplicate()
            duplicate.position(position())
            duplicate.limit(limit())
            duplicate
        }
        else -> ByteBuffer.wrap(toByteArray())
    }
