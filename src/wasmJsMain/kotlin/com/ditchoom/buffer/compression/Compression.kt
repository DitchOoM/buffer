package com.ditchoom.buffer.compression

/**
 * WASM JS implementation using a pure Kotlin fallback.
 *
 * For full compression support, consider using pako via JS interop.
 * This implementation provides basic store-only deflate support.
 */
actual fun compress(
    data: ByteArray,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): CompressionResult =
    try {
        val result =
            when (algorithm) {
                CompressionAlgorithm.Raw -> createStoreOnlyDeflate(data)
                CompressionAlgorithm.Deflate -> createDeflateWithHeader(data)
                CompressionAlgorithm.Gzip -> createGzipWrapper(data)
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
                CompressionAlgorithm.Raw -> parseStoreOnlyDeflate(data)
                CompressionAlgorithm.Deflate -> parseDeflateWithHeader(data)
                CompressionAlgorithm.Gzip -> parseGzipWrapper(data)
            }
        CompressionResult.Success(result)
    } catch (e: Exception) {
        CompressionResult.Failure("Decompression failed: ${e.message}", e)
    }

/**
 * Creates a store-only deflate block (no compression).
 */
private fun createStoreOnlyDeflate(data: ByteArray): ByteArray {
    if (data.isEmpty()) {
        return byteArrayOf(0x01, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte())
    }

    val result = mutableListOf<Byte>()
    var offset = 0

    while (offset < data.size) {
        val remaining = data.size - offset
        val blockSize = minOf(remaining, 65535)
        val isLast = offset + blockSize >= data.size

        result.add(if (isLast) 0x01 else 0x00)
        result.add((blockSize and 0xFF).toByte())
        result.add(((blockSize shr 8) and 0xFF).toByte())
        result.add((blockSize.inv() and 0xFF).toByte())
        result.add(((blockSize.inv() shr 8) and 0xFF).toByte())

        for (i in 0 until blockSize) {
            result.add(data[offset + i])
        }

        offset += blockSize
    }

    return result.toByteArray()
}

/**
 * Creates deflate data with zlib header.
 */
private fun createDeflateWithHeader(data: ByteArray): ByteArray {
    val compressed = createStoreOnlyDeflate(data)

    val cmf: Byte = 0x78
    val flg: Byte = 0x01

    val adler32 = computeAdler32(data)

    return byteArrayOf(cmf, flg) +
        compressed +
        byteArrayOf(
            ((adler32 shr 24) and 0xFF).toByte(),
            ((adler32 shr 16) and 0xFF).toByte(),
            ((adler32 shr 8) and 0xFF).toByte(),
            (adler32 and 0xFF).toByte(),
        )
}

/**
 * Creates gzip wrapper around deflate data.
 */
private fun createGzipWrapper(data: ByteArray): ByteArray {
    val compressed = createStoreOnlyDeflate(data)
    val crc32 = computeCrc32(data)
    val size = data.size

    val header =
        byteArrayOf(
            0x1F.toByte(),
            0x8B.toByte(),
            0x08,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0xFF.toByte(),
        )

    val trailer =
        byteArrayOf(
            (crc32 and 0xFF).toByte(),
            ((crc32 shr 8) and 0xFF).toByte(),
            ((crc32 shr 16) and 0xFF).toByte(),
            ((crc32 shr 24) and 0xFF).toByte(),
            (size and 0xFF).toByte(),
            ((size shr 8) and 0xFF).toByte(),
            ((size shr 16) and 0xFF).toByte(),
            ((size shr 24) and 0xFF).toByte(),
        )

    return header + compressed + trailer
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
            throw CompressionException(
                "Only stored blocks are supported in WASM fallback mode.",
            )
        }

        offset++
        if (offset + 4 > data.size) break

        val len = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        offset += 4

        if (offset + len > data.size) break

        for (i in 0 until len) {
            result.add(data[offset + i])
        }
        offset += len

        if (isFinal) break
    }

    return result.toByteArray()
}

/**
 * Parses deflate data with zlib header.
 */
private fun parseDeflateWithHeader(data: ByteArray): ByteArray {
    if (data.size < 6) throw CompressionException("Invalid zlib data: too short")
    val compressed = data.copyOfRange(2, data.size - 4)
    return parseStoreOnlyDeflate(compressed)
}

/**
 * Parses gzip wrapper.
 */
private fun parseGzipWrapper(data: ByteArray): ByteArray {
    if (data.size < 18) throw CompressionException("Invalid gzip data: too short")

    if (data[0] != 0x1F.toByte() || data[1] != 0x8B.toByte()) {
        throw CompressionException("Invalid gzip magic number")
    }

    val flags = data[3].toInt() and 0xFF
    var offset = 10

    if ((flags and 0x04) != 0) {
        if (offset + 2 > data.size) throw CompressionException("Invalid gzip extra field")
        val xlen = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        offset += 2 + xlen
    }

    if ((flags and 0x08) != 0) {
        while (offset < data.size && data[offset] != 0.toByte()) offset++
        offset++
    }

    if ((flags and 0x10) != 0) {
        while (offset < data.size && data[offset] != 0.toByte()) offset++
        offset++
    }

    if ((flags and 0x02) != 0) {
        offset += 2
    }

    if (offset >= data.size - 8) throw CompressionException("Invalid gzip data structure")
    val compressed = data.copyOfRange(offset, data.size - 8)

    return parseStoreOnlyDeflate(compressed)
}

/**
 * Computes Adler-32 checksum.
 */
private fun computeAdler32(data: ByteArray): Int {
    var s1 = 1
    var s2 = 0
    val mod = 65521

    for (byte in data) {
        s1 = (s1 + (byte.toInt() and 0xFF)) % mod
        s2 = (s2 + s1) % mod
    }

    return (s2 shl 16) or s1
}

/**
 * Computes CRC-32 checksum.
 */
private fun computeCrc32(data: ByteArray): Int {
    var crc = 0xFFFFFFFF.toInt()

    for (byte in data) {
        crc = crc xor (byte.toInt() and 0xFF)
        repeat(8) {
            crc =
                if ((crc and 1) != 0) {
                    (crc ushr 1) xor 0xEDB88320.toInt()
                } else {
                    crc ushr 1
                }
        }
    }

    return crc.inv()
}
