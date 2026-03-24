package com.ditchoom.buffer.compression

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

/**
 * Shared JS/wasmJs compression implementation.
 * Dispatches to Node.js sync APIs or returns browser-unsupported errors.
 */

actual val supportsSyncCompression: Boolean by lazy { isNodeJs }

actual val supportsRawDeflate: Boolean by lazy { isNodeJs }

actual val supportsStatefulFlush: Boolean = false

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
