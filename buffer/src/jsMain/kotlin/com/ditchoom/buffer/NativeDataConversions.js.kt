package com.ditchoom.buffer

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array

/**
 * JS native data wrapper containing ArrayBuffer.
 *
 * Access the underlying ArrayBuffer via [arrayBuffer] property.
 */
actual class NativeData(
    val arrayBuffer: ArrayBuffer,
)

/**
 * JS mutable native data wrapper containing Int8Array.
 *
 * Int8Array is used instead of ArrayBuffer because it can represent a view/slice
 * of the underlying memory, enabling zero-copy for partial buffers.
 *
 * Access the underlying Int8Array via [int8Array] property.
 */
actual class MutableNativeData(
    val int8Array: Int8Array,
)

/**
 * Converts the remaining bytes of this buffer to an ArrayBuffer.
 *
 * **Zero-copy path:**
 * - If the buffer is a [JsBuffer] at position 0 with no byte offset and remaining
 *   equals the full ArrayBuffer size, returns the underlying ArrayBuffer directly.
 *
 * **Copy path:**
 * - Otherwise, copies remaining bytes to a new ArrayBuffer. This is necessary because
 *   ArrayBuffer cannot represent a "view" of a portion - only TypedArrays can.
 *
 * For zero-copy access to a portion, use [toMutableNativeData] which returns Int8Array.
 */
actual fun ReadBuffer.toNativeData(): NativeData =
    NativeData(
        when (this) {
            is JsBuffer -> {
                val pos = position()
                val rem = remaining()
                if (buffer.byteOffset == 0 && pos == 0 && rem == buffer.buffer.byteLength) {
                    buffer.buffer
                } else {
                    // Must copy because ArrayBuffer can't represent a view/slice
                    val slice = buffer.subarray(pos, pos + rem)
                    Int8Array(slice.length).also { it.set(slice) }.buffer
                }
            }
            else -> {
                // toByteArray() is zero-copy for JsBuffer, so we just get the backing ArrayBuffer
                // For non-JsBuffer, readByteArray already copies
                toByteArray().unsafeCast<Int8Array>().buffer
            }
        },
    )

/**
 * Converts the remaining bytes of this buffer to a ByteArray.
 *
 * **Important:** This method does NOT modify the buffer's position.
 *
 * - If the buffer is a [JsBuffer], returns a view of the underlying memory (zero-copy).
 *   Note: The returned ByteArray shares memory with the original buffer.
 * - Otherwise, copies via readByteArray().
 */
actual fun ReadBuffer.toByteArray(): ByteArray =
    when (this) {
        is JsBuffer -> {
            // ByteArray IS Int8Array in Kotlin/JS, and subarray() creates a zero-copy view
            buffer.subarray(position(), position() + remaining()).unsafeCast<ByteArray>()
        }
        else -> {
            val pos = position()
            val result = readByteArray(remaining())
            position(pos)
            result
        }
    }

/**
 * Converts this buffer to an Int8Array.
 *
 * - If the buffer is a [JsBuffer], returns a view of the underlying buffer (zero-copy)
 * - Otherwise, copies the remaining bytes to a new Int8Array
 */
actual fun PlatformBuffer.toMutableNativeData(): MutableNativeData =
    MutableNativeData(
        when (this) {
            is JsBuffer -> buffer.subarray(position(), position() + remaining())
            else -> toByteArray().unsafeCast<Int8Array>()
        },
    )
