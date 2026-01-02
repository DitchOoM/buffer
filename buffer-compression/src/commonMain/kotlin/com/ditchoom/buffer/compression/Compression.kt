package com.ditchoom.buffer.compression

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate

/**
 * Compression algorithm types.
 */
sealed interface CompressionAlgorithm {
    data object Deflate : CompressionAlgorithm

    data object Gzip : CompressionAlgorithm

    data object Raw : CompressionAlgorithm // Raw deflate without headers
}

/**
 * Compression level.
 */
sealed interface CompressionLevel {
    val value: Int

    data object NoCompression : CompressionLevel {
        override val value = 0
    }

    data object BestSpeed : CompressionLevel {
        override val value = 1
    }

    data object Default : CompressionLevel {
        override val value = 6
    }

    data object BestCompression : CompressionLevel {
        override val value = 9
    }

    data class Custom(
        override val value: Int,
    ) : CompressionLevel {
        init {
            require(value in 0..9) { "Compression level must be between 0 and 9" }
        }
    }
}

/**
 * Result of compression/decompression operations.
 */
sealed interface CompressionResult {
    data class Success(
        val buffer: PlatformBuffer,
    ) : CompressionResult

    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : CompressionResult
}

/**
 * Compresses data from a ReadBuffer using the specified algorithm.
 * Reads from current position to limit.
 */
expect fun compress(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm = CompressionAlgorithm.Deflate,
    level: CompressionLevel = CompressionLevel.Default,
): CompressionResult

/**
 * Decompresses data from a ReadBuffer using the specified algorithm.
 * Reads from current position to limit.
 */
expect fun decompress(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm = CompressionAlgorithm.Deflate,
): CompressionResult

/**
 * Extension function to get buffer from CompressionResult, throwing on failure.
 */
fun CompressionResult.getOrThrow(): PlatformBuffer =
    when (this) {
        is CompressionResult.Success -> buffer
        is CompressionResult.Failure -> throw CompressionException(message, cause)
    }

/**
 * Extension function to get buffer from CompressionResult, returning null on failure.
 */
fun CompressionResult.getOrNull(): PlatformBuffer? =
    when (this) {
        is CompressionResult.Success -> buffer
        is CompressionResult.Failure -> null
    }

/**
 * Exception thrown when compression/decompression fails.
 */
class CompressionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Whether this platform supports the synchronous [compress] and [decompress] functions.
 *
 * - JVM, Android, Apple: `true` - native zlib available
 * - JS (Node.js): `true` - sync zlib module available
 * - JS (Browser): `false` - only async CompressionStream API, use [SuspendingStreamingCompressor]
 */
expect val supportsSyncCompression: Boolean

// =============================================================================
// Suspending One-Shot API (works on all platforms including browser JS)
// =============================================================================

/**
 * Compresses data using the specified algorithm. Works on all platforms.
 *
 * This is a convenience function that handles the streaming API internally.
 * For large data or when you need to process chunks incrementally, use
 * [SuspendingStreamingCompressor] directly.
 *
 * @param buffer The data to compress (reads from position to limit)
 * @param algorithm The compression algorithm to use
 * @param level The compression level
 * @param zone The allocation zone for the output buffer
 * @return The compressed data as a single buffer
 */
suspend fun compressAsync(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm = CompressionAlgorithm.Gzip,
    level: CompressionLevel = CompressionLevel.Default,
    zone: AllocationZone = AllocationZone.Direct,
): PlatformBuffer {
    val compressor = SuspendingStreamingCompressor.create(algorithm, level)
    return try {
        val output = mutableListOf<ReadBuffer>()
        output += compressor.compress(buffer)
        output += compressor.finish()
        combineBuffers(output, zone)
    } finally {
        compressor.close()
    }
}

/**
 * Decompresses data using the specified algorithm. Works on all platforms.
 *
 * This is a convenience function that handles the streaming API internally.
 * For large data or when you need to process chunks incrementally, use
 * [SuspendingStreamingDecompressor] directly.
 *
 * @param buffer The compressed data (reads from position to limit)
 * @param algorithm The compression algorithm to use
 * @param zone The allocation zone for the output buffer
 * @return The decompressed data as a single buffer
 */
suspend fun decompressAsync(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm = CompressionAlgorithm.Gzip,
    zone: AllocationZone = AllocationZone.Direct,
): PlatformBuffer {
    val decompressor = SuspendingStreamingDecompressor.create(algorithm)
    return try {
        val output = mutableListOf<ReadBuffer>()
        output += decompressor.decompress(buffer)
        output += decompressor.finish()
        combineBuffers(output, zone)
    } finally {
        decompressor.close()
    }
}

/**
 * Combines multiple buffers into a single buffer.
 */
private fun combineBuffers(
    buffers: List<ReadBuffer>,
    zone: AllocationZone,
): PlatformBuffer {
    val totalSize = buffers.sumOf { it.remaining() }
    val result = PlatformBuffer.allocate(totalSize, zone)
    for (buf in buffers) {
        result.write(buf)
    }
    result.resetForRead()
    return result
}
