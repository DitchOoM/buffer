package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * Converts this buffer to a read-only ByteBuffer.
 *
 * - If the buffer is a [BaseJvmBuffer], returns a read-only duplicate (zero-copy)
 * - Otherwise, wraps the byte array content in a read-only ByteBuffer
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
 * Converts this buffer to a ByteArray.
 *
 * - If the buffer is a heap-backed [BaseJvmBuffer] with hasArray() and the full remaining
 *   content matches the array bounds, returns the backing array (zero-copy)
 * - Otherwise, copies the remaining bytes to a new ByteArray
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
 * Converts this buffer to a mutable ByteBuffer.
 *
 * - If the buffer is a [BaseJvmBuffer], returns a duplicate that shares the underlying memory
 * - Otherwise, wraps the byte array content in a new ByteBuffer
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
