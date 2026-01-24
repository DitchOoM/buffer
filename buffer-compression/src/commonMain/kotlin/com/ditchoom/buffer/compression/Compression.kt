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

/**
 * Whether the current platform supports raw deflate (no zlib/gzip headers).
 *
 * - JVM, Android, Apple: `true`
 * - JS (Node.js): `true` - uses native zlib inflateRaw
 * - JS (Browser): `false` - CompressionStream API only supports "gzip" and "deflate"
 */
expect val supportsRawDeflate: Boolean

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
 * @param buffer The data to compress (reads from position to limit). **Fully consumed after call.**
 * @param algorithm The compression algorithm to use
 * @param level The compression level
 * @param zone The allocation zone for the output buffer
 * @return The compressed data as a single buffer (position=0, limit=compressed size)
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
 * @param buffer The compressed data (reads from position to limit). Position is advanced.
 * @param algorithm The compression algorithm to use
 * @param zone The allocation zone for the output buffer
 * @param expectedOutputSize Hint for pre-allocating the output buffer. If the actual
 *   decompressed size exceeds this, the buffer grows automatically. Use 0 (default)
 *   if unknown. Providing a good estimate reduces memory allocations.
 * @return The decompressed data as a single buffer (position=0, limit=decompressed size)
 */
suspend fun decompressAsync(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm = CompressionAlgorithm.Gzip,
    zone: AllocationZone = AllocationZone.Direct,
    expectedOutputSize: Int = 0,
): PlatformBuffer {
    val decompressor = SuspendingStreamingDecompressor.create(algorithm)
    return try {
        if (expectedOutputSize > 0) {
            // Optimized path: write directly to pre-allocated buffer
            decompressToBuffer(decompressor, buffer, zone, expectedOutputSize)
        } else {
            // Default path: collect chunks then combine
            val output = mutableListOf<ReadBuffer>()
            output += decompressor.decompress(buffer)
            output += decompressor.finish()
            combineBuffers(output, zone)
        }
    } finally {
        decompressor.close()
    }
}

/**
 * Decompresses directly to a pre-allocated buffer, growing if needed.
 * Returns a buffer sliced to exact size (no wasted capacity).
 */
private suspend fun decompressToBuffer(
    decompressor: SuspendingStreamingDecompressor,
    input: ReadBuffer,
    zone: AllocationZone,
    expectedSize: Int,
): PlatformBuffer {
    var output = PlatformBuffer.allocate(expectedSize, zone)
    var capacity = expectedSize // Track capacity since it's not exposed in API

    // Process decompression chunks
    for (chunk in decompressor.decompress(input)) {
        val result = ensureCapacityAndWrite(output, capacity, chunk, zone)
        output = result.first
        capacity = result.second
    }

    // Process finish chunks
    for (chunk in decompressor.finish()) {
        val result = ensureCapacityAndWrite(output, capacity, chunk, zone)
        output = result.first
        capacity = result.second
    }

    // Slice to exact size if we have excess capacity
    val actualSize = output.position()
    return if (actualSize == capacity) {
        output.resetForRead()
        output
    } else {
        // Return a right-sized buffer to avoid memory waste
        output.position(0)
        output.setLimit(actualSize)
        val result = PlatformBuffer.allocate(actualSize, zone)
        result.write(output)
        result.resetForRead()
        result
    }
}

/**
 * Ensures the output buffer has capacity for the chunk, growing if needed.
 * Returns the (possibly new) output buffer and its capacity.
 */
private fun ensureCapacityAndWrite(
    output: PlatformBuffer,
    capacity: Int,
    chunk: ReadBuffer,
    zone: AllocationZone,
): Pair<PlatformBuffer, Int> {
    val needed = chunk.remaining()
    val available = capacity - output.position()

    return if (needed <= available) {
        output.write(chunk)
        Pair(output, capacity)
    } else {
        // Grow buffer: double size or add needed space, whichever is larger
        val newCapacity = maxOf(capacity * 2, capacity + needed)
        val newOutput = PlatformBuffer.allocate(newCapacity, zone)

        // Copy existing data
        val written = output.position()
        output.position(0)
        output.setLimit(written)
        newOutput.write(output)

        // Write new chunk
        newOutput.write(chunk)
        Pair(newOutput, newCapacity)
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
