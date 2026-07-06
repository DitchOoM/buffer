package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import java.io.IOException
import java.util.zip.DataFormatException

/**
 * JVM/Android supports synchronous compression via java.util.zip.
 */
actual val supportsSyncCompression: Boolean = true
actual val supportsRawDeflate: Boolean = true
actual val supportsStatefulFlush: Boolean = true

// java.util.zip.{Deflater,Inflater} expose only (level, nowrap) — no windowBits
// or memLevel surface. The bundled native zlib supports both via deflateInit2,
// but the JNI bridge hardcodes windowBits magnitude to 15 (nowrap toggles the
// sign: +15 zlib-format, -15 raw-deflate). No public JDK API reaches it.
actual val supportsCustomWindowBits: Boolean = false

// java.util.zip.{Deflater,Inflater} both expose setDictionary on every supported JDK/Android
// API level (the byte[] overload predates Java 1.2); the ByteBuffer overload used internally
// requires JDK 11 / Android API 35+, with an array-based fallback below that.
actual val supportsPresetDictionary: Boolean = true

/**
 * JVM/Android implementation delegating to streaming compression.
 */
actual fun compress(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    dictionary: ReadBuffer?,
): CompressionResult =
    try {
        requireDictionarySupport(algorithm, dictionary)
        val compressor = StreamingCompressor.create(algorithm, level, dictionary = dictionary)
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
        val result = BufferFactory.Default.allocate(totalSize)
        for (chunk in outputChunks) {
            result.write(chunk)
        }
        result.resetForRead()

        CompressionResult.Success(result)
    } catch (e: CompressionException) {
        CompressionResult.Failure("Compression failed: ${e.message}", e)
    } catch (e: IOException) {
        CompressionResult.Failure("Compression failed: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        CompressionResult.Failure("Compression failed: ${e.message}", e)
    }

actual fun decompress(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm,
    dictionary: ReadBuffer?,
): CompressionResult =
    try {
        requireDictionarySupport(algorithm, dictionary)
        val decompressor = StreamingDecompressor.create(algorithm, dictionary = dictionary)
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
        val result = BufferFactory.Default.allocate(totalSize)
        for (chunk in outputChunks) {
            result.write(chunk)
        }
        result.resetForRead()

        CompressionResult.Success(result)
    } catch (e: CompressionException) {
        CompressionResult.Failure("Decompression failed: ${e.message}", e)
    } catch (e: DataFormatException) {
        CompressionResult.Failure("Decompression failed: ${e.message}", e)
    } catch (e: IOException) {
        CompressionResult.Failure("Decompression failed: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        CompressionResult.Failure("Decompression failed: ${e.message}", e)
    }
