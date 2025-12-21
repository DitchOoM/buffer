package com.ditchoom.buffer.protocol.http

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate

/**
 * High-performance HTTP message model using sealed interfaces.
 *
 * Designed for zero-copy parsing where possible, with lazy body evaluation
 * to avoid unnecessary memory copies for large payloads.
 */
sealed interface HttpMessage {
    val headers: HttpHeaders
    val body: HttpBody
}

/**
 * HTTP Request message.
 */
data class HttpRequest(
    val method: HttpMethod,
    val path: String,
    val version: HttpVersion,
    override val headers: HttpHeaders,
    override val body: HttpBody,
) : HttpMessage

/**
 * HTTP Response message.
 */
data class HttpResponse(
    val statusCode: Int,
    val statusText: String,
    val version: HttpVersion,
    override val headers: HttpHeaders,
    override val body: HttpBody,
) : HttpMessage

/**
 * HTTP methods as sealed interface for exhaustive matching.
 */
sealed interface HttpMethod {
    val name: String

    data object GET : HttpMethod {
        override val name = "GET"
    }

    data object POST : HttpMethod {
        override val name = "POST"
    }

    data object PUT : HttpMethod {
        override val name = "PUT"
    }

    data object DELETE : HttpMethod {
        override val name = "DELETE"
    }

    data object HEAD : HttpMethod {
        override val name = "HEAD"
    }

    data object OPTIONS : HttpMethod {
        override val name = "OPTIONS"
    }

    data object PATCH : HttpMethod {
        override val name = "PATCH"
    }

    data object CONNECT : HttpMethod {
        override val name = "CONNECT"
    }

    data object TRACE : HttpMethod {
        override val name = "TRACE"
    }

    data class Custom(
        override val name: String,
    ) : HttpMethod

    companion object {
        fun parse(method: String): HttpMethod =
            when (method.uppercase()) {
                "GET" -> GET
                "POST" -> POST
                "PUT" -> PUT
                "DELETE" -> DELETE
                "HEAD" -> HEAD
                "OPTIONS" -> OPTIONS
                "PATCH" -> PATCH
                "CONNECT" -> CONNECT
                "TRACE" -> TRACE
                else -> Custom(method)
            }
    }
}

/**
 * HTTP version.
 */
sealed interface HttpVersion {
    val major: Int
    val minor: Int

    data object Http10 : HttpVersion {
        override val major = 1
        override val minor = 0

        override fun toString() = "HTTP/1.0"
    }

    data object Http11 : HttpVersion {
        override val major = 1
        override val minor = 1

        override fun toString() = "HTTP/1.1"
    }

    data object Http20 : HttpVersion {
        override val major = 2
        override val minor = 0

        override fun toString() = "HTTP/2.0"
    }

    data class Custom(
        override val major: Int,
        override val minor: Int,
    ) : HttpVersion {
        override fun toString() = "HTTP/$major.$minor"
    }

    companion object {
        fun parse(version: String): HttpVersion {
            val trimmed = version.trim()
            return when {
                trimmed.equals("HTTP/1.0", ignoreCase = true) -> Http10
                trimmed.equals("HTTP/1.1", ignoreCase = true) -> Http11
                trimmed.equals("HTTP/2.0", ignoreCase = true) ||
                    trimmed.equals("HTTP/2", ignoreCase = true) -> Http20
                trimmed.startsWith("HTTP/", ignoreCase = true) -> {
                    val parts = trimmed.substring(5).split(".")
                    Custom(parts[0].toInt(), parts.getOrElse(1) { "0" }.toInt())
                }
                else -> Http11 // Default
            }
        }
    }
}

/**
 * HTTP headers with efficient storage and lookup.
 */
class HttpHeaders private constructor(
    private val headers: List<Pair<String, String>>,
) : Iterable<Pair<String, String>> {
    /**
     * Gets the first value for a header (case-insensitive).
     */
    operator fun get(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /**
     * Gets all values for a header (case-insensitive).
     */
    fun getAll(name: String): List<String> = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    /**
     * Checks if header exists.
     */
    fun contains(name: String): Boolean = headers.any { it.first.equals(name, ignoreCase = true) }

    /**
     * Content-Length header value, or null if not present.
     */
    val contentLength: Long?
        get() = get("Content-Length")?.toLongOrNull()

    /**
     * Content-Type header value.
     */
    val contentType: String?
        get() = get("Content-Type")

    /**
     * Transfer-Encoding header.
     */
    val transferEncoding: String?
        get() = get("Transfer-Encoding")

    /**
     * Content-Encoding header (for compression).
     */
    val contentEncoding: String?
        get() = get("Content-Encoding")

    /**
     * Connection header.
     */
    val connection: String?
        get() = get("Connection")

    val size: Int get() = headers.size

    override fun iterator(): Iterator<Pair<String, String>> = headers.iterator()

    override fun toString(): String = headers.joinToString("\r\n") { "${it.first}: ${it.second}" }

    class Builder {
        private val headers = mutableListOf<Pair<String, String>>()

        fun add(
            name: String,
            value: String,
        ): Builder {
            headers.add(name to value)
            return this
        }

        fun set(
            name: String,
            value: String,
        ): Builder {
            headers.removeAll { it.first.equals(name, ignoreCase = true) }
            headers.add(name to value)
            return this
        }

        fun build(): HttpHeaders = HttpHeaders(headers.toList())
    }

    companion object {
        fun builder() = Builder()

        val EMPTY = HttpHeaders(emptyList())
    }
}

/**
 * HTTP body using zero-copy buffer operations.
 * All body types expose data via ReadBuffer to avoid ByteArray allocations.
 */
sealed interface HttpBody {
    /**
     * Returns the body as a ReadBuffer for zero-copy access.
     */
    fun asBuffer(): ReadBuffer

    /**
     * Returns the body as a string (UTF-8).
     */
    fun text(): String {
        val buffer = asBuffer()
        return buffer.readString(buffer.remaining())
    }

    /**
     * Body length if known, -1 otherwise.
     */
    val length: Long

    /**
     * Whether the body is compressed.
     */
    val isCompressed: Boolean

    /**
     * Empty body.
     */
    data object Empty : HttpBody {
        override fun asBuffer(): ReadBuffer = ReadBuffer.EMPTY_BUFFER

        override val length = 0L
        override val isCompressed = false
    }

    /**
     * Body backed by a buffer (zero-copy).
     */
    data class Buffered(
        private val buffer: ReadBuffer,
        override val length: Long,
        override val isCompressed: Boolean = false,
    ) : HttpBody {
        override fun asBuffer(): ReadBuffer = buffer
    }

    /**
     * Chunked transfer encoding body backed by buffers.
     */
    data class Chunked(
        private val chunks: List<ReadBuffer>,
        override val isCompressed: Boolean = false,
    ) : HttpBody {
        override val length = chunks.sumOf { it.remaining().toLong() }

        override fun asBuffer(): ReadBuffer {
            // If single chunk, return it directly
            if (chunks.size == 1) return chunks[0]
            // Combine multiple chunks into a single buffer
            val total = length.toInt()
            val combined = PlatformBuffer.allocate(total)
            for (chunk in chunks) {
                combined.write(chunk)
            }
            combined.resetForRead()
            return combined
        }
    }

    /**
     * Compressed body that decompresses on access.
     */
    data class Compressed(
        private val compressedBuffer: ReadBuffer,
        val encoding: String, // "gzip", "deflate", etc.
        private val decompressor: (ReadBuffer, String) -> ReadBuffer,
    ) : HttpBody {
        override val isCompressed = true
        override val length = compressedBuffer.remaining().toLong()

        private var decompressedCache: ReadBuffer? = null

        override fun asBuffer(): ReadBuffer =
            decompressedCache ?: decompressor(compressedBuffer, encoding).also {
                decompressedCache = it
            }
    }
}
