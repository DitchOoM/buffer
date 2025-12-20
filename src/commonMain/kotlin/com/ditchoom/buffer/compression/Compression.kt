package com.ditchoom.buffer.compression

// Multiplatform compression interface using expect/actual pattern.
// Provides inflate/deflate operations for JVM/Android, JS, and Native/WASM.

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
        val data: ByteArray,
    ) : CompressionResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode() = data.contentHashCode()
    }

    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : CompressionResult
}

/**
 * Compresses data using the specified algorithm.
 */
expect fun compress(
    data: ByteArray,
    algorithm: CompressionAlgorithm = CompressionAlgorithm.Deflate,
    level: CompressionLevel = CompressionLevel.Default,
): CompressionResult

/**
 * Decompresses data using the specified algorithm.
 */
expect fun decompress(
    data: ByteArray,
    algorithm: CompressionAlgorithm = CompressionAlgorithm.Deflate,
): CompressionResult

/**
 * Extension function to get bytes from CompressionResult, throwing on failure.
 */
fun CompressionResult.getOrThrow(): ByteArray =
    when (this) {
        is CompressionResult.Success -> data
        is CompressionResult.Failure -> throw CompressionException(message, cause)
    }

/**
 * Extension function to get bytes from CompressionResult, returning null on failure.
 */
fun CompressionResult.getOrNull(): ByteArray? =
    when (this) {
        is CompressionResult.Success -> data
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
 * Helper object for creating decompressors for protocol parsers.
 */
object Decompressors {
    /**
     * Creates a decompressor function for HTTP Content-Encoding.
     */
    fun httpDecompressor(): suspend (ByteArray, String) -> ByteArray =
        { data, encoding ->
            val algorithm =
                when (encoding.lowercase()) {
                    "gzip" -> CompressionAlgorithm.Gzip
                    "deflate" -> CompressionAlgorithm.Deflate
                    else -> throw CompressionException("Unsupported encoding: $encoding")
                }
            decompress(data, algorithm).getOrThrow()
        }

    /**
     * Creates a decompressor function for WebSocket per-message deflate.
     */
    fun webSocketDecompressor(): (ByteArray) -> ByteArray =
        { data ->
            // WebSocket per-message deflate uses raw deflate without headers
            // and adds 0x00 0x00 0xFF 0xFF trailer
            val inflateData =
                if (data.size >= 4 &&
                    data[data.size - 4] == 0x00.toByte() &&
                    data[data.size - 3] == 0x00.toByte() &&
                    data[data.size - 2] == 0xFF.toByte() &&
                    data[data.size - 1] == 0xFF.toByte()
                ) {
                    data.copyOfRange(0, data.size - 4)
                } else {
                    data
                }
            decompress(inflateData, CompressionAlgorithm.Raw).getOrThrow()
        }

    /**
     * Creates a decompressor function for MQTT payloads.
     */
    fun mqttDecompressor(): (ByteArray) -> ByteArray =
        { data ->
            decompress(data, CompressionAlgorithm.Deflate).getOrThrow()
        }
}

/**
 * Helper object for creating compressors for protocol serializers.
 */
object Compressors {
    /**
     * Creates a compressor function for HTTP Content-Encoding.
     */
    fun httpCompressor(encoding: String = "gzip"): (ByteArray) -> ByteArray =
        { data ->
            val algorithm =
                when (encoding.lowercase()) {
                    "gzip" -> CompressionAlgorithm.Gzip
                    "deflate" -> CompressionAlgorithm.Deflate
                    else -> throw CompressionException("Unsupported encoding: $encoding")
                }
            compress(data, algorithm).getOrThrow()
        }

    /**
     * Creates a compressor function for WebSocket per-message deflate.
     */
    fun webSocketCompressor(): (ByteArray) -> ByteArray =
        { data ->
            // WebSocket per-message deflate uses raw deflate
            val compressed = compress(data, CompressionAlgorithm.Raw).getOrThrow()
            // Add the 0x00 0x00 0xFF 0xFF trailer
            compressed + byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte())
        }

    /**
     * Creates a compressor function for MQTT payloads.
     */
    fun mqttCompressor(level: CompressionLevel = CompressionLevel.Default): (ByteArray) -> ByteArray =
        { data ->
            compress(data, CompressionAlgorithm.Deflate, level).getOrThrow()
        }
}
