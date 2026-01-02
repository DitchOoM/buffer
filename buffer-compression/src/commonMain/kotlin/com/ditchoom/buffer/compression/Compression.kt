package com.ditchoom.buffer.compression

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

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
