package com.ditchoom.buffer.compression

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.zlib.Z_OK
import platform.zlib.compress2
import platform.zlib.compressBound
import platform.zlib.uLongVar
import platform.zlib.uncompress

/**
 * Apple implementation using system zlib.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun compress(
    data: ByteArray,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): CompressionResult =
    try {
        val result =
            when (algorithm) {
                CompressionAlgorithm.Raw -> compressRaw(data, level)
                CompressionAlgorithm.Deflate -> compressZlib(data, level)
                CompressionAlgorithm.Gzip -> compressGzip(data, level)
            }
        CompressionResult.Success(result)
    } catch (e: Exception) {
        CompressionResult.Failure("Compression failed: ${e.message}", e)
    }

@OptIn(ExperimentalForeignApi::class)
actual fun decompress(
    data: ByteArray,
    algorithm: CompressionAlgorithm,
): CompressionResult =
    try {
        val result =
            when (algorithm) {
                CompressionAlgorithm.Raw -> decompressRaw(data)
                CompressionAlgorithm.Deflate -> decompressZlib(data)
                CompressionAlgorithm.Gzip -> decompressGzip(data)
            }
        CompressionResult.Success(result)
    } catch (e: Exception) {
        CompressionResult.Failure("Decompression failed: ${e.message}", e)
    }

@OptIn(ExperimentalForeignApi::class)
private fun compressZlib(
    data: ByteArray,
    level: CompressionLevel,
): ByteArray =
    memScoped {
        val destLen = alloc<uLongVar>()
        destLen.value = compressBound(data.size.toULong())

        val dest = ByteArray(destLen.value.toInt())

        data.usePinned { srcPinned ->
            dest.usePinned { destPinned ->
                val result =
                    compress2(
                        destPinned.addressOf(0).reinterpret(),
                        destLen.ptr,
                        srcPinned.addressOf(0).reinterpret(),
                        data.size.toULong(),
                        level.value,
                    )

                if (result != Z_OK) {
                    throw CompressionException("zlib compress2 failed with code: $result")
                }
            }
        }

        dest.copyOfRange(0, destLen.value.toInt())
    }

@OptIn(ExperimentalForeignApi::class)
private fun decompressZlib(data: ByteArray): ByteArray =
    memScoped {
        // Start with a reasonable buffer size and grow if needed
        var destSize = data.size * 4
        var dest = ByteArray(destSize)
        val destLen = alloc<uLongVar>()

        data.usePinned { srcPinned ->
            while (true) {
                destLen.value = destSize.toULong()
                dest.usePinned { destPinned ->
                    val result =
                        uncompress(
                            destPinned.addressOf(0).reinterpret(),
                            destLen.ptr,
                            srcPinned.addressOf(0).reinterpret(),
                            data.size.toULong(),
                        )

                    when (result) {
                        Z_OK -> return@memScoped dest.copyOfRange(0, destLen.value.toInt())
                        -5 -> {
                            // Z_BUF_ERROR - need larger buffer
                            destSize *= 2
                            dest = ByteArray(destSize)
                        }
                        else -> throw CompressionException(
                            "zlib uncompress failed with code: $result",
                        )
                    }
                }
            }
        }

        @Suppress("UNREACHABLE_CODE")
        dest // Unreachable but needed for type inference
    }

/**
 * Raw deflate compression (without zlib header).
 * Uses store-only format as a simple fallback.
 */
private fun compressRaw(
    data: ByteArray,
    level: CompressionLevel,
): ByteArray = createStoreOnlyDeflate(data)

private fun decompressRaw(data: ByteArray): ByteArray = parseStoreOnlyDeflate(data)

/**
 * Gzip compression with wrapper.
 */
private fun compressGzip(
    data: ByteArray,
    level: CompressionLevel,
): ByteArray {
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

private fun decompressGzip(data: ByteArray): ByteArray {
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
