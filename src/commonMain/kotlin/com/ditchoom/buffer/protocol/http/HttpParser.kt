package com.ditchoom.buffer.protocol.http

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.AccumulatingBufferReader
import com.ditchoom.buffer.stream.BufferChunk
import com.ditchoom.buffer.stream.BufferStream

/**
 * High-performance HTTP parser using zero-copy buffer operations.
 *
 * Parses HTTP messages from a stream of buffer chunks, minimizing memory copies
 * and allocations through buffer pooling.
 */
class HttpParser(
    private val pool: BufferPool,
    private val decompressor: ((ReadBuffer, String) -> ReadBuffer)? = null,
) {
    /**
     * Parses HTTP requests from a buffer stream.
     */
    fun parseRequests(
        stream: BufferStream,
        handler: (HttpRequest) -> Unit,
    ) {
        val reader = AccumulatingBufferReader(pool)

        try {
            stream.forEachChunk { chunk ->
                reader.append(chunk)

                while (reader.available() > 0) {
                    val request = tryParseRequest(reader)
                    if (request != null) {
                        handler(request)
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
    fun parseResponses(
        stream: BufferStream,
        handler: (HttpResponse) -> Unit,
    ) {
        val reader = AccumulatingBufferReader(pool)

        try {
            stream.forEachChunk { chunk ->
                reader.append(chunk)

                while (reader.available() > 0) {
                    val response = tryParseResponse(reader)
                    if (response != null) {
                        handler(response)
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
     * Parses a single request from a ReadBuffer.
     */
    fun parseRequest(buffer: ReadBuffer): HttpRequest {
        val reader = AccumulatingBufferReader(pool)
        try {
            reader.append(BufferChunk(buffer, true, 0))
            return tryParseRequest(reader)
                ?: throw HttpParseException("Incomplete HTTP request")
        } finally {
            reader.release()
        }
    }

    /**
     * Parses a single response from a ReadBuffer.
     */
    fun parseResponse(buffer: ReadBuffer): HttpResponse {
        val reader = AccumulatingBufferReader(pool)
        try {
            reader.append(BufferChunk(buffer, true, 0))
            return tryParseResponse(reader)
                ?: throw HttpParseException("Incomplete HTTP response")
        } finally {
            reader.release()
        }
    }

    private fun tryParseRequest(reader: AccumulatingBufferReader): HttpRequest? {
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

    private fun tryParseResponse(reader: AccumulatingBufferReader): HttpResponse? {
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

        // Scan for double CRLF pattern without creating ByteArray
        for (i in 0 until available - 3) {
            if (reader.peekByte(i) == CR &&
                reader.peekByte(i + 1) == LF &&
                reader.peekByte(i + 2) == CR &&
                reader.peekByte(i + 3) == LF
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
                if (reader.available() > 0 && reader.peekByte() == LF) {
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

    private fun parseBody(
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
                        // Use zero-copy slice for the body
                        HttpBody.Buffered(reader.readBuffer(contentLength.toInt()), contentLength)
                    }
                }

                else -> HttpBody.Empty
            }

        // Handle compression
        return if (contentEncoding != null && rawBody != HttpBody.Empty && decompressor != null) {
            HttpBody.Compressed(rawBody.asBuffer(), contentEncoding, decompressor)
        } else {
            rawBody
        }
    }

    private fun parseChunkedBody(reader: AccumulatingBufferReader): HttpBody {
        val chunks = mutableListOf<ReadBuffer>()

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

            // Use zero-copy slice for each chunk
            chunks.add(reader.readBuffer(chunkSize))
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
 * Serializes HTTP messages to buffers.
 */
object HttpSerializer {
    private val CRLF = "\r\n"
    private val HEADER_SEP = ": "

    /**
     * Serializes an HTTP request to a WriteBuffer.
     * Returns the number of bytes written.
     */
    fun serializeTo(
        request: HttpRequest,
        buffer: WriteBuffer,
    ): Int {
        val startPos = buffer.position()

        // Request line
        buffer.writeString(request.method.name)
        buffer.writeString(" ")
        buffer.writeString(request.path)
        buffer.writeString(" ")
        buffer.writeString(request.version.toString())
        buffer.writeString(CRLF)

        // Headers
        for ((name, value) in request.headers) {
            buffer.writeString(name)
            buffer.writeString(HEADER_SEP)
            buffer.writeString(value)
            buffer.writeString(CRLF)
        }
        buffer.writeString(CRLF)

        // Body
        if (request.body != HttpBody.Empty) {
            val bodyBuffer = request.body.asBuffer()
            buffer.write(bodyBuffer)
        }

        return buffer.position() - startPos
    }

    /**
     * Serializes an HTTP response to a WriteBuffer.
     * Returns the number of bytes written.
     */
    fun serializeTo(
        response: HttpResponse,
        buffer: WriteBuffer,
    ): Int {
        val startPos = buffer.position()

        // Status line
        buffer.writeString(response.version.toString())
        buffer.writeString(" ")
        buffer.writeString(response.statusCode.toString())
        buffer.writeString(" ")
        buffer.writeString(response.statusText)
        buffer.writeString(CRLF)

        // Headers
        for ((name, value) in response.headers) {
            buffer.writeString(name)
            buffer.writeString(HEADER_SEP)
            buffer.writeString(value)
            buffer.writeString(CRLF)
        }
        buffer.writeString(CRLF)

        // Body
        if (response.body != HttpBody.Empty) {
            val bodyBuffer = response.body.asBuffer()
            buffer.write(bodyBuffer)
        }

        return buffer.position() - startPos
    }

    /**
     * Calculates the size needed to serialize an HTTP request.
     */
    fun calculateSize(request: HttpRequest): Int {
        var size = 0
        size += request.method.name.length + 1 // method + space
        size += request.path.length + 1 // path + space
        size += request.version.toString().length + 2 // version + CRLF

        for ((name, value) in request.headers) {
            size += name.length + 2 + value.length + 2 // name: value\r\n
        }
        size += 2 // Final CRLF

        if (request.body != HttpBody.Empty) {
            size += request.body.length.toInt()
        }

        return size
    }

    /**
     * Calculates the size needed to serialize an HTTP response.
     */
    fun calculateSize(response: HttpResponse): Int {
        var size = 0
        size += response.version.toString().length + 1 // version + space
        size += response.statusCode.toString().length + 1 // code + space
        size += response.statusText.length + 2 // text + CRLF

        for ((name, value) in response.headers) {
            size += name.length + 2 + value.length + 2 // name: value\r\n
        }
        size += 2 // Final CRLF

        if (response.body != HttpBody.Empty) {
            size += response.body.length.toInt()
        }

        return size
    }
}
