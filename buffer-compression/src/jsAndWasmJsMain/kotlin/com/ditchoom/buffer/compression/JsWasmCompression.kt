package com.ditchoom.buffer.compression

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

/**
 * Shared JS/wasmJs compression implementation.
 * Dispatches to Node.js sync APIs or returns browser-unsupported errors.
 */

actual val supportsSyncCompression: Boolean by lazy(LazyThreadSafetyMode.NONE) { isNodeJs }

actual val supportsRawDeflate: Boolean by lazy(LazyThreadSafetyMode.NONE) { isNodeJs }

actual val supportsStatefulFlush: Boolean by lazy(LazyThreadSafetyMode.NONE) { isNodeJs }

// Node sync zlib options accept windowBits and JsNodeStreamingCompressor now threads it.
// Browser CompressionStream has no windowBits knob, so this stays false in the browser.
// The Node Transform suspending path doesn't yet forward windowBits — its expect signature
// (SuspendingStreamingCompressor.Companion.create) doesn't take it; future API expansion.
actual val supportsCustomWindowBits: Boolean by lazy(LazyThreadSafetyMode.NONE) { isNodeJs }

// JsInterop. Node's synchronous zlib throws an opaque JS `Error` on malformed
// input; across the Kotlin/JS FFI boundary that surfaces only as a broad type,
// so the catch is intentionally wide. The cause is preserved in the Failure.
@Suppress("TooGenericExceptionCaught")
actual fun compress(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): CompressionResult =
    if (isNodeJs) {
        try {
            val input = buffer.toJsByteArrayView() // zero-copy: sync zlib consumes immediately
            val compressed = nodeZlibSync(input, algorithm, level)
            CompressionResult.Success(compressed.toPlatformBuffer() as PlatformBuffer)
        } catch (e: Exception) {
            CompressionResult.Failure("Compression failed: ${e.message}", e)
        }
    } else {
        CompressionResult.Failure(
            "Synchronous compression not supported in browser. Use StreamingCompressor instead.",
            UnsupportedOperationException("Browser CompressionStream API is async-only"),
        )
    }

@Suppress("TooGenericExceptionCaught") // JS FFI: Node zlib throws opaque JS Error; cause preserved.
actual fun decompress(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm,
): CompressionResult =
    if (isNodeJs) {
        try {
            val input = buffer.toJsByteArrayView() // zero-copy: sync zlib consumes immediately
            val decompressed = nodeZlibDecompressSync(input, algorithm)
            CompressionResult.Success(decompressed.toPlatformBuffer() as PlatformBuffer)
        } catch (e: Exception) {
            CompressionResult.Failure("Decompression failed: ${e.message}", e)
        }
    } else {
        CompressionResult.Failure(
            "Synchronous decompression not supported in browser. Use StreamingDecompressor instead.",
            UnsupportedOperationException("Browser CompressionStream API is async-only"),
        )
    }

/**
 * Compress with Z_SYNC_FLUSH. Used by streaming compressor flush().
 */
internal fun compressWithSyncFlushShared(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): JsByteArray {
    val input = buffer.toJsByteArrayView() // zero-copy: sync zlib consumes immediately
    return nodeZlibSyncFlush(input, algorithm, level)
}

/**
 * Get the browser compression format string for an algorithm.
 */
internal fun CompressionAlgorithm.toBrowserFormat(): String =
    when (this) {
        CompressionAlgorithm.Gzip -> "gzip"
        CompressionAlgorithm.Deflate -> "deflate"
        CompressionAlgorithm.Raw -> throw UnsupportedOperationException(
            "Raw deflate not supported in browser. Use Gzip or Deflate.",
        )
    }

/**
 * Get the algorithm string for Node.js Transform stream APIs.
 */
internal fun CompressionAlgorithm.toNodeString(): String =
    when (this) {
        CompressionAlgorithm.Gzip -> "gzip"
        CompressionAlgorithm.Deflate -> "deflate"
        CompressionAlgorithm.Raw -> "raw"
    }
