package com.ditchoom.buffer.compression

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate

/**
 * JVM/Android supports synchronous compression via java.util.zip.
 */
actual val supportsSyncCompression: Boolean = true

/**
 * JVM/Android implementation delegating to streaming compression.
 */
actual fun compress(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): CompressionResult =
    try {
        val compressor = StreamingCompressor.create(algorithm, level)
        val outputChunks = mutableListOf<ReadBuffer>()
        var totalSize = 0

        try {
            compressor.compress(buffer) { chunk ->
                totalSize += chunk.remaining()
                outputChunks.add(chunk)
            }
            compressor.finish { chunk ->
                totalSize += chunk.remaining()
                outputChunks.add(chunk)
            }
        } finally {
            compressor.close()
        }

        // Combine chunks into single buffer using buffer-to-buffer copy
        val result = PlatformBuffer.allocate(totalSize)
        for (chunk in outputChunks) {
            result.write(chunk)
        }
        result.resetForRead()

        CompressionResult.Success(result)
    } catch (e: Exception) {
        CompressionResult.Failure("Compression failed: ${e.message}", e)
    }

actual fun decompress(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm,
): CompressionResult =
    try {
        val decompressor = StreamingDecompressor.create(algorithm)
        val outputChunks = mutableListOf<ReadBuffer>()
        var totalSize = 0

        try {
            decompressor.decompress(buffer) { chunk ->
                totalSize += chunk.remaining()
                outputChunks.add(chunk)
            }
            decompressor.finish { chunk ->
                totalSize += chunk.remaining()
                outputChunks.add(chunk)
            }
        } finally {
            decompressor.close()
        }

        // Combine chunks into single buffer using buffer-to-buffer copy
        val result = PlatformBuffer.allocate(totalSize)
        for (chunk in outputChunks) {
            result.write(chunk)
        }
        result.resetForRead()

        CompressionResult.Success(result)
    } catch (e: Exception) {
        CompressionResult.Failure("Decompression failed: ${e.message}", e)
    }
