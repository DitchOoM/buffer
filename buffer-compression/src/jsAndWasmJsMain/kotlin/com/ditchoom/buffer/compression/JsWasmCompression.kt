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

// Node's zlib bindings accept a `dictionary` option; the browser CompressionStream/
// DecompressionStream Web API has no dictionary parameter at all.
actual val supportsPresetDictionary: Boolean by lazy(LazyThreadSafetyMode.NONE) { isNodeJs }

// JsInterop. Node's synchronous zlib throws an opaque native JS `Error` on malformed
// input or a missing/incorrect preset dictionary (Z_NEED_DICT / Z_DATA_ERROR). A raw
// JS Error thrown across the Kotlin/JS FFI boundary is only guaranteed catchable by
// `Throwable`, not narrower Kotlin exception types, so the catch is intentionally the
// broadest type. The cause is preserved in the Failure.
@Suppress("TooGenericExceptionCaught")
actual fun compress(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    dictionary: ReadBuffer?,
): CompressionResult =
    if (isNodeJs) {
        try {
            requireDictionarySupport(algorithm, dictionary)
            val input = buffer.toJsByteArrayView() // zero-copy: sync zlib consumes immediately
            val dict = dictionary?.toJsByteArrayView()
            val compressed = nodeZlibSync(input, algorithm, level, dictionary = dict)
            CompressionResult.Success(compressed.toPlatformBuffer() as PlatformBuffer)
        } catch (e: Throwable) {
            CompressionResult.Failure("Compression failed: ${e.message}", e)
        }
    } else {
        CompressionResult.Failure(
            "Synchronous compression not supported in browser. Use StreamingCompressor instead.",
            UnsupportedOperationException("Browser CompressionStream API is async-only"),
        )
    }

// See the comment on compress() above: a raw JS Error is only guaranteed catchable by
// Throwable across the Kotlin/JS FFI boundary (e.g. inflateSync with a missing or
// incorrect preset dictionary throws this way).
@Suppress("TooGenericExceptionCaught")
actual fun decompress(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm,
    dictionary: ReadBuffer?,
): CompressionResult =
    if (isNodeJs) {
        try {
            requireDictionarySupport(algorithm, dictionary)
            val input = buffer.toJsByteArrayView() // zero-copy: sync zlib consumes immediately
            val dict = dictionary?.toJsByteArrayView()
            val decompressed = nodeZlibDecompressSync(input, algorithm, dict)
            CompressionResult.Success(decompressed.toPlatformBuffer() as PlatformBuffer)
        } catch (e: Throwable) {
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
