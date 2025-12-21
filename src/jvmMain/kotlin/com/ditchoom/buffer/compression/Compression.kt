@file:JvmName("CompressionJvm")

package com.ditchoom.buffer.compression

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterOutputStream

/**
 * JVM implementation using java.util.zip.
 */
actual fun compress(
    data: ByteArray,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): CompressionResult =
    try {
        val result =
            when (algorithm) {
                CompressionAlgorithm.Gzip -> compressGzip(data, level)
                CompressionAlgorithm.Deflate -> compressDeflate(data, level, nowrap = false)
                CompressionAlgorithm.Raw -> compressDeflate(data, level, nowrap = true)
            }
        CompressionResult.Success(result)
    } catch (e: Exception) {
        CompressionResult.Failure("Compression failed: ${e.message}", e)
    }

actual fun decompress(
    data: ByteArray,
    algorithm: CompressionAlgorithm,
): CompressionResult =
    try {
        val result =
            when (algorithm) {
                CompressionAlgorithm.Gzip -> decompressGzip(data)
                CompressionAlgorithm.Deflate -> decompressInflate(data, nowrap = false)
                CompressionAlgorithm.Raw -> decompressInflate(data, nowrap = true)
            }
        CompressionResult.Success(result)
    } catch (e: Exception) {
        CompressionResult.Failure("Decompression failed: ${e.message}", e)
    }

private fun compressGzip(
    data: ByteArray,
    level: CompressionLevel,
): ByteArray {
    val bos = ByteArrayOutputStream()
    GZIPOutputStream(bos).use { gzip ->
        // Note: GZIPOutputStream doesn't expose setLevel directly
        // We use DeflaterOutputStream for level control
        gzip.write(data)
    }
    return bos.toByteArray()
}

private fun compressDeflate(
    data: ByteArray,
    level: CompressionLevel,
    nowrap: Boolean,
): ByteArray {
    val deflater = Deflater(level.value, nowrap)
    val bos = ByteArrayOutputStream()
    DeflaterOutputStream(bos, deflater).use { dos ->
        dos.write(data)
    }
    deflater.end()
    return bos.toByteArray()
}

private fun decompressGzip(data: ByteArray): ByteArray {
    val bis = ByteArrayInputStream(data)
    GZIPInputStream(bis).use { gzip ->
        return gzip.readBytes()
    }
}

private fun decompressInflate(
    data: ByteArray,
    nowrap: Boolean,
): ByteArray {
    val inflater = Inflater(nowrap)
    val bos = ByteArrayOutputStream()
    InflaterOutputStream(bos, inflater).use { ios ->
        ios.write(data)
    }
    inflater.end()
    return bos.toByteArray()
}
