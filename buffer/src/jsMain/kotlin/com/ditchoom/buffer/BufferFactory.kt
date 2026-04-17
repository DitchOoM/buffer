package com.ditchoom.buffer

import js.buffer.SharedArrayBuffer
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array

// =============================================================================
// v2 BufferFactory implementations
// =============================================================================

internal actual val defaultBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer {
            require(size >= 0) { "Buffer size must be non-negative, got $size" }
            return JsBuffer(Int8Array(size), byteOrder)
        }

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer =
            JsBuffer(
                array.unsafeCast<Int8Array>(),
                byteOrder,
            )
    }

internal actual val managedBufferFactory: BufferFactory = defaultBufferFactory

// JS has no deterministic cleanup — GC-managed only
private val deterministicFactoryInstance: BufferFactory = defaultBufferFactory

internal actual fun deterministicBufferFactory(threadConfined: Boolean): BufferFactory = deterministicFactoryInstance

internal actual val sharedBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer {
            if (size == 0) return ReadBuffer.EMPTY_BUFFER
            val sharedArrayBuffer =
                try {
                    SharedArrayBuffer(size)
                } catch (_: Exception) {
                    null
                }
            return if (sharedArrayBuffer != null) {
                val arrayBuffer = sharedArrayBuffer.unsafeCast<ArrayBuffer>().slice(0, size)
                JsBuffer(Int8Array(arrayBuffer), byteOrder, sharedArrayBuffer = sharedArrayBuffer)
            } else {
                JsBuffer(Int8Array(size), byteOrder)
            }
        }

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer =
            JsBuffer(
                array.unsafeCast<Int8Array>(),
                byteOrder,
            )
    }

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
): PlatformBuffer = BufferFactory.shared().allocate(size, byteOrder)

actual fun PlatformBuffer.Companion.wrapNativeAddress(
    address: Long,
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer =
    throw UnsupportedOperationException(
        "wrapNativeAddress(Long) is not supported on JavaScript — JS has no global memory address space. " +
            "Use PlatformBuffer.wrap(Int8Array) or PlatformBuffer.wrap(ArrayBuffer) instead.",
    )

/**
 * Wraps an existing [Int8Array] as a [PlatformBuffer] (zero-copy).
 *
 * The buffer shares memory with the original typed array — modifications
 * to one are visible in the other. The buffer does not own the memory.
 *
 * This is the JS equivalent of [wrapNativeAddress] on other platforms.
 */
fun PlatformBuffer.Companion.wrap(
    int8Array: Int8Array,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): PlatformBuffer = JsBuffer(int8Array, byteOrder)

/**
 * Wraps an existing [ArrayBuffer] as a [PlatformBuffer] (zero-copy).
 *
 * Creates an [Int8Array] view over the entire ArrayBuffer. The buffer shares
 * memory with the original ArrayBuffer — modifications are visible in both.
 *
 * This is the JS equivalent of [wrapNativeAddress] on other platforms.
 */
fun PlatformBuffer.Companion.wrap(
    arrayBuffer: ArrayBuffer,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): PlatformBuffer = JsBuffer(Int8Array(arrayBuffer), byteOrder)
