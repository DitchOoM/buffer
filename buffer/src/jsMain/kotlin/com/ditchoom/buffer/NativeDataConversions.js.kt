package com.ditchoom.buffer

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array

/**
 * Converts this buffer to an ArrayBuffer.
 *
 * - If the buffer is a [JsBuffer] and represents the full underlying ArrayBuffer,
 *   returns the underlying ArrayBuffer (zero-copy)
 * - Otherwise, copies the remaining bytes to a new ArrayBuffer
 */
fun ReadBuffer.toNativeData(): ArrayBuffer =
    when (this) {
        is JsBuffer -> {
            val pos = position()
            val rem = remaining()
            if (buffer.byteOffset == 0 && pos == 0 && rem == buffer.buffer.byteLength) {
                buffer.buffer
            } else {
                val slice = buffer.subarray(pos, pos + rem)
                Int8Array(slice.length).also { it.set(slice) }.buffer
            }
        }
        else -> {
            val bytes = toByteArray()
            bytes.unsafeCast<Int8Array>().buffer
        }
    }

/**
 * Converts the remaining bytes of this buffer to a ByteArray.
 *
 * This always creates a copy of the data.
 */
actual fun ReadBuffer.toByteArray(): ByteArray =
    when (this) {
        is JsBuffer -> {
            val pos = position()
            val rem = remaining()
            val subArray = buffer.subarray(pos, pos + rem)
            Int8Array(subArray.length).also { it.set(subArray) }.unsafeCast<ByteArray>()
        }
        else -> readByteArray(remaining())
    }

/**
 * Converts this buffer to an Int8Array.
 *
 * - If the buffer is a [JsBuffer], returns a view of the underlying buffer (zero-copy)
 * - Otherwise, copies the remaining bytes to a new Int8Array
 */
fun PlatformBuffer.toMutableNativeData(): Int8Array =
    when (this) {
        is JsBuffer -> buffer.subarray(position(), position() + remaining())
        else -> toByteArray().unsafeCast<Int8Array>()
    }
