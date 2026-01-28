package com.ditchoom.buffer.compression

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

/**
 * JS supports synchronous compression only in Node.js via native zlib module.
 * Browser only has async CompressionStream API - use [SuspendingStreamingCompressor] instead.
 */
actual val supportsSyncCompression: Boolean by lazy {
    isNodeJs
}

actual val supportsRawDeflate: Boolean by lazy {
    isNodeJs
}

/**
 * Whether the sync [StreamingCompressor] maintains dictionary across flush() calls.
 *
 * JS Node.js: `false` - sync API uses batch compression, flush clears state.
 * JS Browser: `false` - CompressionStream API doesn't support flush.
 *
 * Note: The async [SuspendingStreamingCompressor] DOES support stateful flush
 * on Node.js using the Transform stream API.
 */
actual val supportsStatefulFlush: Boolean = false

/**
 * Check if running in Node.js environment.
 */
internal val isNodeJs: Boolean by lazy {
    js("typeof process !== 'undefined' && process.versions != null && process.versions.node != null") as Boolean
}

/**
 * Get Node.js zlib module.
 * Uses dynamic module name to prevent webpack from bundling zlib for browser.
 */
internal fun getNodeZlib(): dynamic {
    // Construct module name dynamically to prevent webpack static analysis
    val moduleName = "zl" + "ib"

    @Suppress("UNUSED_VARIABLE")
    val m = moduleName
    return js("require(m)")
}

/**
 * JS implementation using native Node.js zlib.
 * Browser throws UnsupportedOperationException - use streaming API instead.
 */
actual fun compress(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): CompressionResult =
    if (isNodeJs) {
        try {
            CompressionResult.Success(compressWithNodeZlib(buffer, algorithm, level))
        } catch (e: Exception) {
            CompressionResult.Failure("Compression failed: ${e.message}", e)
        }
    } else {
        CompressionResult.Failure(
            "Synchronous compression not supported in browser. Use StreamingCompressor instead.",
            UnsupportedOperationException("Browser CompressionStream API is async-only"),
        )
    }

actual fun decompress(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm,
): CompressionResult =
    if (isNodeJs) {
        try {
            CompressionResult.Success(decompressWithNodeZlib(buffer, algorithm))
        } catch (e: Exception) {
            CompressionResult.Failure("Decompression failed: ${e.message}", e)
        }
    } else {
        CompressionResult.Failure(
            "Synchronous decompression not supported in browser. Use StreamingDecompressor instead.",
            UnsupportedOperationException("Browser CompressionStream API is async-only"),
        )
    }

// ============================================================================
// Node.js native zlib implementation - zero ByteArray conversion
// ============================================================================

private fun compressWithNodeZlib(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): PlatformBuffer {
    val zlib = getNodeZlib()
    val options = js("{}")
    options["level"] = level.value

    // Get input as Int8Array directly from JsBuffer, or convert
    val inputArray = buffer.toInt8Array()

    val compressed: Uint8Array =
        when (algorithm) {
            CompressionAlgorithm.Gzip -> zlib.gzipSync(inputArray, options).unsafeCast<Uint8Array>()
            CompressionAlgorithm.Deflate -> zlib.deflateSync(inputArray, options).unsafeCast<Uint8Array>()
            CompressionAlgorithm.Raw -> zlib.deflateRawSync(inputArray, options).unsafeCast<Uint8Array>()
        }

    return compressed.toJsBuffer()
}

/**
 * Compress with Z_SYNC_FLUSH to produce complete deflate blocks ending with sync marker.
 * The output can be immediately decompressed without waiting for more data.
 */
internal fun compressWithSyncFlush(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): PlatformBuffer {
    val zlib = getNodeZlib()
    val options = js("{}")
    options["level"] = level.value
    options["finishFlush"] = zlib.constants.Z_SYNC_FLUSH

    val inputArray = buffer.toInt8Array()

    val compressed: Uint8Array =
        when (algorithm) {
            CompressionAlgorithm.Gzip -> zlib.gzipSync(inputArray, options).unsafeCast<Uint8Array>()
            CompressionAlgorithm.Deflate -> zlib.deflateSync(inputArray, options).unsafeCast<Uint8Array>()
            CompressionAlgorithm.Raw -> zlib.deflateRawSync(inputArray, options).unsafeCast<Uint8Array>()
        }

    return compressed.toJsBuffer()
}

private fun decompressWithNodeZlib(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm,
): PlatformBuffer {
    val zlib = getNodeZlib()
    val inputArray = buffer.toInt8Array()

    val decompressed: Uint8Array =
        when (algorithm) {
            CompressionAlgorithm.Gzip -> zlib.gunzipSync(inputArray).unsafeCast<Uint8Array>()
            CompressionAlgorithm.Deflate -> zlib.inflateSync(inputArray).unsafeCast<Uint8Array>()
            CompressionAlgorithm.Raw -> {
                // Use Z_SYNC_FLUSH as finishFlush so inflateRawSync doesn't require
                // BFINAL=1. This is needed for WebSocket per-message-deflate (RFC 7692)
                // where the sync marker 00 00 ff ff has BFINAL=0.
                val options = js("{}")
                options["finishFlush"] = zlib.constants.Z_SYNC_FLUSH
                zlib.inflateRawSync(inputArray, options).unsafeCast<Uint8Array>()
            }
        }

    return decompressed.toJsBuffer()
}

// ============================================================================
// Conversion utilities - zero-copy where possible
// ============================================================================

/**
 * Get Int8Array from buffer. Zero-copy for JsBuffer.
 */
internal fun ReadBuffer.toInt8Array(): Int8Array {
    val remaining = remaining()
    return if (this is JsBuffer) {
        // Zero-copy: get subarray view
        buffer.subarray(position(), position() + remaining).also {
            position(position() + remaining)
        }
    } else {
        // Fallback: copy through ByteArray
        val bytes = readByteArray(remaining)
        bytes.unsafeCast<Int8Array>()
    }
}

/**
 * Get Uint8Array from buffer. Creates view for JsBuffer.
 */
internal fun ReadBuffer.toUint8Array(): Uint8Array {
    val remaining = remaining()
    return if (this is JsBuffer) {
        // Create Uint8Array view on same buffer
        val int8 = buffer.subarray(position(), position() + remaining)
        position(position() + remaining)
        Uint8Array(int8.buffer, int8.byteOffset, int8.length)
    } else {
        // Fallback: copy through ByteArray
        val bytes = readByteArray(remaining)
        val uint8 = Uint8Array(bytes.size)
        for (i in bytes.indices) {
            uint8[i] = bytes[i]
        }
        uint8
    }
}

/**
 * Wrap Uint8Array in JsBuffer. Zero-copy - creates Int8Array view on same memory.
 * Buffer is ready for reading: position=0, limit=length.
 */
internal fun Uint8Array.toJsBuffer(): JsBuffer {
    // Create Int8Array view on same ArrayBuffer
    val int8Array = Int8Array(buffer, byteOffset, length)
    // JsBuffer init sets limit = buffer.length, position = 0, which is read-ready
    return JsBuffer(int8Array)
}

/**
 * Wrap Int8Array in JsBuffer. Zero-copy.
 * Buffer is ready for reading: position=0, limit=length.
 */
internal fun Int8Array.toJsBuffer(): JsBuffer = JsBuffer(this)
