package com.ditchoom.buffer.compression

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

/**
 * JS implementation using pako library (if available) or pure Kotlin fallback.
 *
 * For production use, include pako in your project:
 * npm install pako
 * or use the CompressionStream API for modern browsers.
 */
actual fun compress(
    data: ByteArray,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): CompressionResult =
    try {
        val result = compressWithPakoOrFallback(data, algorithm, level)
        CompressionResult.Success(result)
    } catch (e: Exception) {
        CompressionResult.Failure("Compression failed: ${e.message}", e)
    }

actual fun decompress(
    data: ByteArray,
    algorithm: CompressionAlgorithm,
): CompressionResult =
    try {
        val result = decompressWithPakoOrFallback(data, algorithm)
        CompressionResult.Success(result)
    } catch (e: Exception) {
        CompressionResult.Failure("Decompression failed: ${e.message}", e)
    }

private fun compressWithPakoOrFallback(
    data: ByteArray,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): ByteArray {
    // Try to use pako if available
    val pako = tryGetPako()
    if (pako != null) {
        return compressWithPako(pako, data, algorithm, level)
    }

    // Fallback: return uncompressed data for Raw, throw for others
    return when (algorithm) {
        CompressionAlgorithm.Raw -> {
            // Simple store-only compression (no actual compression)
            createStoreOnlyDeflate(data)
        }
        else -> throw CompressionException(
            "Pako library not available. Include pako for compression support.",
        )
    }
}

private fun decompressWithPakoOrFallback(
    data: ByteArray,
    algorithm: CompressionAlgorithm,
): ByteArray {
    // Try to use pako if available
    val pako = tryGetPako()
    if (pako != null) {
        return decompressWithPako(pako, data, algorithm)
    }

    // Fallback: try to handle store-only deflate
    return when (algorithm) {
        CompressionAlgorithm.Raw -> {
            parseStoreOnlyDeflate(data)
        }
        else -> throw CompressionException(
            "Pako library not available. Include pako for decompression support.",
        )
    }
}

private fun tryGetPako(): dynamic =
    try {
        val pako = js("require('pako')")
        if (pako != undefined) pako else null
    } catch (e: Throwable) {
        try {
            val globalPako = js("typeof pako !== 'undefined' ? pako : null")
            globalPako
        } catch (e2: Throwable) {
            null
        }
    }

private fun compressWithPako(
    pako: dynamic,
    data: ByteArray,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): ByteArray {
    val uint8Array = data.toUint8Array()
    val options = js("{}")
    options["level"] = level.value

    val compressed: Uint8Array =
        when (algorithm) {
            CompressionAlgorithm.Gzip -> pako.gzip(uint8Array, options).unsafeCast<Uint8Array>()
            CompressionAlgorithm.Deflate -> pako.deflate(uint8Array, options).unsafeCast<Uint8Array>()
            CompressionAlgorithm.Raw -> pako.deflateRaw(uint8Array, options).unsafeCast<Uint8Array>()
        }

    return compressed.toByteArray()
}

private fun decompressWithPako(
    pako: dynamic,
    data: ByteArray,
    algorithm: CompressionAlgorithm,
): ByteArray {
    val uint8Array = data.toUint8Array()

    val decompressed: Uint8Array =
        when (algorithm) {
            CompressionAlgorithm.Gzip -> pako.ungzip(uint8Array).unsafeCast<Uint8Array>()
            CompressionAlgorithm.Deflate -> pako.inflate(uint8Array).unsafeCast<Uint8Array>()
            CompressionAlgorithm.Raw -> pako.inflateRaw(uint8Array).unsafeCast<Uint8Array>()
        }

    return decompressed.toByteArray()
}

private fun ByteArray.toUint8Array(): Uint8Array {
    val array = Uint8Array(this.size)
    for (i in this.indices) {
        array[i] = this[i]
    }
    return array
}

private fun Uint8Array.toByteArray(): ByteArray {
    val array = ByteArray(this.length)
    for (i in 0 until this.length) {
        array[i] = this[i]
    }
    return array
}

/**
 * Creates a store-only deflate block (no compression).
 * This is a fallback when pako is not available.
 */
private fun createStoreOnlyDeflate(data: ByteArray): ByteArray {
    if (data.isEmpty()) {
        // Empty stored block
        return byteArrayOf(0x01, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte())
    }

    val result = mutableListOf<Byte>()
    var offset = 0

    while (offset < data.size) {
        val remaining = data.size - offset
        val blockSize = minOf(remaining, 65535)
        val isLast = offset + blockSize >= data.size

        // Block header
        result.add(if (isLast) 0x01 else 0x00) // BFINAL + BTYPE=00 (stored)
        result.add((blockSize and 0xFF).toByte())
        result.add(((blockSize shr 8) and 0xFF).toByte())
        result.add((blockSize.inv() and 0xFF).toByte())
        result.add(((blockSize.inv() shr 8) and 0xFF).toByte())

        // Data
        for (i in 0 until blockSize) {
            result.add(data[offset + i])
        }

        offset += blockSize
    }

    return result.toByteArray()
}

/**
 * Parses a store-only deflate block.
 */
private fun parseStoreOnlyDeflate(data: ByteArray): ByteArray {
    val result = mutableListOf<Byte>()
    var offset = 0

    while (offset < data.size) {
        val header = data[offset].toInt() and 0xFF
        val isFinal = (header and 0x01) != 0
        val btype = (header shr 1) and 0x03

        if (btype != 0) {
            throw CompressionException("Only stored blocks are supported in fallback mode")
        }

        offset++
        if (offset + 4 > data.size) break

        val len = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        offset += 4 // Skip LEN and NLEN

        if (offset + len > data.size) break

        for (i in 0 until len) {
            result.add(data[offset + i])
        }
        offset += len

        if (isFinal) break
    }

    return result.toByteArray()
}
