package com.ditchoom.buffer.protocol.http

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.AccumulatingBufferReader
import com.ditchoom.buffer.stream.BufferChunk
import com.ditchoom.buffer.stream.BufferStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * High-performance HTTP parser using zero-copy buffer operations.
 *
 * Parses HTTP messages from a stream of buffer chunks, minimizing memory copies
 * and allocations through buffer pooling.
 */
class HttpParser(
    private val pool: BufferPool,
    private val decompressor: (suspend (ByteArray, String) -> ByteArray)? = null,
) {
    /**
     * Parses HTTP requests from a buffer stream.
     */
    fun parseRequests(stream: BufferStream): Flow<HttpRequest> =
        flow {
            val reader = AccumulatingBufferReader(pool)

            try {
                stream.chunks.collect { chunk ->
                    reader.append(chunk)

                    while (reader.available() > 0) {
                        val request = tryParseRequest(reader)
                        if (request != null) {
                            emit(request)
                            reader.compact()
                        } else {
                            break // Need more data
                        }
                    }
                }
            } finally {
                reader.release()
            }
        }

    /**
     * Parses HTTP responses from a buffer stream.
     */
    fun parseResponses(stream: BufferStream): Flow<HttpResponse> =
        flow {
            val reader = AccumulatingBufferReader(pool)

            try {
                stream.chunks.collect { chunk ->
                    reader.append(chunk)

                    while (reader.available() > 0) {
                        val response = tryParseResponse(reader)
                        if (response != null) {
                            emit(response)
                            reader.compact()
                        } else {
                            break // Need more data
                        }
                    }
                }
            } finally {
                reader.release()
            }
        }

    /**
     * Parses a single request from a byte array (convenience method).
     */
    suspend fun parseRequest(data: ByteArray): HttpRequest {
        val reader = AccumulatingBufferReader(pool)
        try {
            val buffer = pool.acquire(data.size)
            buffer.writeBytes(data)
            buffer.resetForRead()
            reader.append(BufferChunk(buffer, true, 0))
            return tryParseRequest(reader)
                ?: throw HttpParseException("Incomplete HTTP request")
        } finally {
            reader.release()
        }
    }

    /**
     * Parses a single response from a byte array (convenience method).
     */
    suspend fun parseResponse(data: ByteArray): HttpResponse {
        val reader = AccumulatingBufferReader(pool)
        try {
            val buffer = pool.acquire(data.size)
            buffer.writeBytes(data)
            buffer.resetForRead()
            reader.append(BufferChunk(buffer, true, 0))
            return tryParseResponse(reader)
                ?: throw HttpParseException("Incomplete HTTP response")
        } finally {
            reader.release()
        }
    }

    private suspend fun tryParseRequest(reader: AccumulatingBufferReader): HttpRequest? {
        // Find end of headers (double CRLF)
        val headerEnd = findHeaderEnd(reader) ?: return null

        // Parse request line
        val requestLine = readLine(reader) ?: return null
        val parts = requestLine.split(" ", limit = 3)
        if (parts.size < 3) throw HttpParseException("Invalid request line: $requestLine")

        val method = HttpMethod.parse(parts[0])
        val path = parts[1]
        val version = HttpVersion.parse(parts[2])

        // Parse headers
        val headers = parseHeaders(reader)

        // Parse body based on Content-Length or Transfer-Encoding
        val body = parseBody(reader, headers)

        return HttpRequest(method, path, version, headers, body)
    }

    private suspend fun tryParseResponse(reader: AccumulatingBufferReader): HttpResponse? {
        // Find end of headers
        val headerEnd = findHeaderEnd(reader) ?: return null

        // Parse status line
        val statusLine = readLine(reader) ?: return null
        val parts = statusLine.split(" ", limit = 3)
        if (parts.size < 2) throw HttpParseException("Invalid status line: $statusLine")

        val version = HttpVersion.parse(parts[0])
        val statusCode =
            parts[1].toIntOrNull()
                ?: throw HttpParseException("Invalid status code: ${parts[1]}")
        val statusText = if (parts.size > 2) parts[2] else ""

        // Parse headers
        val headers = parseHeaders(reader)

        // Parse body
        val body = parseBody(reader, headers)

        return HttpResponse(statusCode, statusText, version, headers, body)
    }

    private fun findHeaderEnd(reader: AccumulatingBufferReader): Int? {
        val available = reader.available()
        if (available < 4) return null

        val data = reader.peek(available)
        for (i in 0 until data.size - 3) {
            if (data[i] == CR &&
                data[i + 1] == LF &&
                data[i + 2] == CR &&
                data[i + 3] == LF
            ) {
                return i + 4
            }
        }
        return null
    }

    private fun readLine(reader: AccumulatingBufferReader): String? {
        val builder = StringBuilder()
        while (reader.available() > 0) {
            val b = reader.readByte()
            if (b == CR) {
                if (reader.available() > 0 && reader.peek(1)[0] == LF) {
                    reader.readByte() // consume LF
                }
                return builder.toString()
            } else if (b == LF) {
                return builder.toString()
            } else {
                builder.append(b.toInt().toChar())
            }
        }
        return null
    }

    private fun parseHeaders(reader: AccumulatingBufferReader): HttpHeaders {
        val builder = HttpHeaders.builder()

        while (reader.available() > 0) {
            val line = readLine(reader) ?: break
            if (line.isEmpty()) break // End of headers

            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val name = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                builder.add(name, value)
            }
        }

        return builder.build()
    }

    private suspend fun parseBody(
        reader: AccumulatingBufferReader,
        headers: HttpHeaders,
    ): HttpBody {
        val transferEncoding = headers.transferEncoding
        val contentLength = headers.contentLength
        val contentEncoding = headers.contentEncoding

        val rawBody =
            when {
                transferEncoding?.contains("chunked", ignoreCase = true) == true -> {
                    parseChunkedBody(reader)
                }

                contentLength != null && contentLength > 0 -> {
                    if (reader.available() < contentLength.toInt()) {
                        // Not enough data yet
                        HttpBody.Empty
                    } else {
                        HttpBody.Bytes(reader.readBytes(contentLength.toInt()))
                    }
                }

                else -> HttpBody.Empty
            }

        // Handle compression
        return if (contentEncoding != null && rawBody != HttpBody.Empty && decompressor != null) {
            val data = rawBody.bytes()
            HttpBody.Compressed(data, contentEncoding, decompressor)
        } else {
            rawBody
        }
    }

    private fun parseChunkedBody(reader: AccumulatingBufferReader): HttpBody {
        val chunks = mutableListOf<ByteArray>()

        while (reader.available() > 0) {
            val sizeLine = readLine(reader) ?: break
            val chunkSize = sizeLine.trim().toIntOrNull(16) ?: break

            if (chunkSize == 0) {
                // End of chunks, skip trailing CRLF
                readLine(reader)
                break
            }

            if (reader.available() < chunkSize) {
                // Not enough data
                break
            }

            chunks.add(reader.readBytes(chunkSize))
            readLine(reader) // Skip trailing CRLF after chunk
        }

        return if (chunks.isEmpty()) HttpBody.Empty else HttpBody.Chunked(chunks)
    }

    companion object {
        private const val CR: Byte = '\r'.code.toByte()
        private const val LF: Byte = '\n'.code.toByte()
    }
}

/**
 * Exception thrown when HTTP parsing fails.
 */
class HttpParseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Serializes HTTP messages back to bytes.
 */
object HttpSerializer {
    fun serialize(request: HttpRequest): ByteArray {
        val builder = StringBuilder()

        // Request line
        builder.append(request.method.name)
        builder.append(' ')
        builder.append(request.path)
        builder.append(' ')
        builder.append(request.version)
        builder.append("\r\n")

        // Headers
        for ((name, value) in request.headers) {
            builder.append(name)
            builder.append(": ")
            builder.append(value)
            builder.append("\r\n")
        }
        builder.append("\r\n")

        val headerBytes = builder.toString().encodeToByteArray()

        // Body (blocking call - use for small bodies only)
        return if (request.body == HttpBody.Empty) {
            headerBytes
        } else {
            // For proper async, use serializeAsync
            headerBytes
        }
    }

    fun serialize(response: HttpResponse): ByteArray {
        val builder = StringBuilder()

        // Status line
        builder.append(response.version)
        builder.append(' ')
        builder.append(response.statusCode)
        builder.append(' ')
        builder.append(response.statusText)
        builder.append("\r\n")

        // Headers
        for ((name, value) in response.headers) {
            builder.append(name)
            builder.append(": ")
            builder.append(value)
            builder.append("\r\n")
        }
        builder.append("\r\n")

        return builder.toString().encodeToByteArray()
    }

    suspend fun serializeWithBody(request: HttpRequest): ByteArray {
        val headers = serialize(request)
        val body = request.body.bytes()
        return headers + body
    }

    suspend fun serializeWithBody(response: HttpResponse): ByteArray {
        val headers = serialize(response)
        val body = response.body.bytes()
        return headers + body
    }
}
