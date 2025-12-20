package com.ditchoom.buffer.protocol.websocket

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.PooledBuffer
import com.ditchoom.buffer.stream.AccumulatingBufferReader
import com.ditchoom.buffer.stream.BufferStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * High-performance WebSocket frame parser.
 *
 * Implements RFC 6455 frame parsing with support for:
 * - Fragmented messages
 * - Masked payloads
 * - Per-message deflate compression (when decompressor is provided)
 */
class WebSocketParser(
    private val pool: BufferPool,
    private val decompressor: ((ByteArray) -> ByteArray)? = null,
) {
    /**
     * Parses WebSocket frames from a buffer stream.
     */
    fun parseFrames(stream: BufferStream): Flow<WebSocketFrame> = flow {
        val reader = AccumulatingBufferReader(pool)

        try {
            stream.chunks.collect { chunk ->
                reader.append(chunk)

                while (reader.available() >= 2) {
                    val frame = tryParseFrame(reader)
                    if (frame != null) {
                        emit(frame)
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
     * Parses a single frame from a byte array.
     */
    fun parseFrame(data: ByteArray): WebSocketFrame {
        val reader = AccumulatingBufferReader(pool)
        try {
            val buffer = pool.acquire(data.size)
            buffer.writeBytes(data)
            buffer.resetForRead()
            reader.append(com.ditchoom.buffer.stream.BufferChunk(buffer, true, 0))
            return tryParseFrame(reader)
                ?: throw WebSocketParseException("Incomplete WebSocket frame")
        } finally {
            reader.release()
        }
    }

    private fun tryParseFrame(reader: AccumulatingBufferReader): WebSocketFrame? {
        if (reader.available() < 2) return null

        // Read first two bytes
        val byte1 = reader.peek(1)[0].toInt() and 0xFF
        val byte2 = reader.peek(2)[1].toInt() and 0xFF

        val fin = (byte1 and 0x80) != 0
        val rsv1 = (byte1 and 0x40) != 0
        val rsv2 = (byte1 and 0x20) != 0
        val rsv3 = (byte1 and 0x10) != 0
        val opcode = WebSocketOpcode.fromInt(byte1 and 0x0F)
        val masked = (byte2 and 0x80) != 0
        var payloadLen = (byte2 and 0x7F).toLong()

        var headerSize = 2

        // Extended payload length
        when (payloadLen.toInt()) {
            126 -> {
                if (reader.available() < 4) return null
                headerSize = 4
            }

            127 -> {
                if (reader.available() < 10) return null
                headerSize = 10
            }
        }

        if (masked) {
            headerSize += 4
        }

        if (reader.available() < headerSize) return null

        // Now actually read the header
        reader.readByte() // byte1
        reader.readByte() // byte2

        // Read extended payload length
        payloadLen = when (payloadLen.toInt()) {
            126 -> {
                ((reader.readByte().toInt() and 0xFF) shl 8) or
                    (reader.readByte().toInt() and 0xFF).toLong()
            }

            127 -> {
                var len = 0L
                repeat(8) {
                    len = (len shl 8) or (reader.readByte().toLong() and 0xFF)
                }
                len
            }

            else -> payloadLen
        }

        // Read masking key
        val maskingKey = if (masked) {
            reader.readBytes(4)
        } else {
            null
        }

        // Check if we have full payload
        if (reader.available() < payloadLen.toInt()) {
            // Can't rewind, so this is a problem - for now, return null
            // In a real implementation, we'd need to track partial read state
            return null
        }

        // Read payload
        val rawPayload = reader.readBytes(payloadLen.toInt())

        // Unmask if needed
        val unmaskedPayload = if (masked && maskingKey != null) {
            unmask(rawPayload, maskingKey)
        } else {
            rawPayload
        }

        // Handle compression (rsv1 indicates per-message deflate)
        val payload = if (rsv1 && decompressor != null && unmaskedPayload.isNotEmpty()) {
            WebSocketPayload.Compressed(unmaskedPayload, decompressor)
        } else {
            WebSocketPayload.Raw(unmaskedPayload)
        }

        // Create frame based on opcode
        return when (opcode) {
            WebSocketOpcode.Continuation -> WebSocketFrame.Continuation(
                fin, rsv1, rsv2, rsv3, masked, maskingKey, payloadLen, payload
            )

            WebSocketOpcode.Text -> WebSocketFrame.Text(
                fin, rsv1, rsv2, rsv3, masked, maskingKey, payloadLen, payload
            )

            WebSocketOpcode.Binary -> WebSocketFrame.Binary(
                fin, rsv1, rsv2, rsv3, masked, maskingKey, payloadLen, payload
            )

            WebSocketOpcode.Close -> {
                val (closeCode, reason) = parseClosePayload(unmaskedPayload)
                WebSocketFrame.Close(
                    fin, rsv1, rsv2, rsv3, masked, maskingKey, payloadLen, payload, closeCode, reason
                )
            }

            WebSocketOpcode.Ping -> WebSocketFrame.Ping(
                fin, rsv1, rsv2, rsv3, masked, maskingKey, payloadLen, payload
            )

            WebSocketOpcode.Pong -> WebSocketFrame.Pong(
                fin, rsv1, rsv2, rsv3, masked, maskingKey, payloadLen, payload
            )

            is WebSocketOpcode.Reserved -> throw WebSocketParseException(
                "Reserved opcode: ${opcode.value}"
            )
        }
    }

    private fun unmask(data: ByteArray, maskingKey: ByteArray): ByteArray {
        val result = ByteArray(data.size)
        for (i in data.indices) {
            result[i] = (data[i].toInt() xor maskingKey[i % 4].toInt()).toByte()
        }
        return result
    }

    private fun parseClosePayload(payload: ByteArray): Pair<WebSocketCloseCode, String> {
        if (payload.size < 2) {
            return WebSocketCloseCode.NoStatusReceived to ""
        }

        val code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val reason = if (payload.size > 2) {
            payload.copyOfRange(2, payload.size).decodeToString()
        } else {
            ""
        }

        return WebSocketCloseCode.fromInt(code) to reason
    }
}

/**
 * Serializes WebSocket frames.
 */
class WebSocketSerializer(
    private val pool: BufferPool,
    private val compressor: ((ByteArray) -> ByteArray)? = null,
) {
    /**
     * Serializes a text frame.
     */
    fun createTextFrame(
        text: String,
        masked: Boolean = false,
        compress: Boolean = false,
    ): ByteArray {
        val payload = text.encodeToByteArray()
        return createFrame(WebSocketOpcode.Text, payload, masked, compress)
    }

    /**
     * Serializes a binary frame.
     */
    fun createBinaryFrame(
        data: ByteArray,
        masked: Boolean = false,
        compress: Boolean = false,
    ): ByteArray {
        return createFrame(WebSocketOpcode.Binary, data, masked, compress)
    }

    /**
     * Serializes a close frame.
     */
    fun createCloseFrame(
        code: WebSocketCloseCode,
        reason: String = "",
        masked: Boolean = false,
    ): ByteArray {
        val payload = ByteArray(2 + reason.length)
        payload[0] = ((code.code shr 8) and 0xFF).toByte()
        payload[1] = (code.code and 0xFF).toByte()
        reason.encodeToByteArray().copyInto(payload, 2)
        return createFrame(WebSocketOpcode.Close, payload, masked, compress = false)
    }

    /**
     * Serializes a ping frame.
     */
    fun createPingFrame(data: ByteArray = ByteArray(0), masked: Boolean = false): ByteArray {
        return createFrame(WebSocketOpcode.Ping, data, masked, compress = false)
    }

    /**
     * Serializes a pong frame.
     */
    fun createPongFrame(data: ByteArray = ByteArray(0), masked: Boolean = false): ByteArray {
        return createFrame(WebSocketOpcode.Pong, data, masked, compress = false)
    }

    private fun createFrame(
        opcode: WebSocketOpcode,
        payload: ByteArray,
        masked: Boolean,
        compress: Boolean,
    ): ByteArray {
        val finalPayload = if (compress && compressor != null) {
            compressor.invoke(payload)
        } else {
            payload
        }

        val payloadLen = finalPayload.size
        val rsv1 = compress && compressor != null

        // Calculate frame size
        val headerSize = when {
            payloadLen <= 125 -> 2
            payloadLen <= 65535 -> 4
            else -> 10
        } + if (masked) 4 else 0

        val frameSize = headerSize + payloadLen
        val buffer = pool.acquire(frameSize)

        try {
            // Byte 1: FIN, RSV1-3, opcode
            val byte1 = 0x80 or // FIN
                (if (rsv1) 0x40 else 0) or
                (opcode.value and 0x0F)
            buffer.writeByte(byte1.toByte())

            // Byte 2: MASK, payload length
            val maskBit = if (masked) 0x80 else 0
            when {
                payloadLen <= 125 -> {
                    buffer.writeByte((maskBit or payloadLen).toByte())
                }

                payloadLen <= 65535 -> {
                    buffer.writeByte((maskBit or 126).toByte())
                    buffer.writeByte(((payloadLen shr 8) and 0xFF).toByte())
                    buffer.writeByte((payloadLen and 0xFF).toByte())
                }

                else -> {
                    buffer.writeByte((maskBit or 127).toByte())
                    for (i in 7 downTo 0) {
                        buffer.writeByte(((payloadLen shr (i * 8)) and 0xFF).toByte())
                    }
                }
            }

            // Masking key and payload
            if (masked) {
                val maskingKey = generateMaskingKey()
                buffer.writeBytes(maskingKey)

                // Mask and write payload
                for (i in finalPayload.indices) {
                    val maskedByte = (finalPayload[i].toInt() xor maskingKey[i % 4].toInt()).toByte()
                    buffer.writeByte(maskedByte)
                }
            } else {
                buffer.writeBytes(finalPayload)
            }

            buffer.resetForRead()
            return buffer.readByteArray(buffer.remaining())
        } finally {
            (buffer as PooledBuffer).release()
        }
    }

    private fun generateMaskingKey(): ByteArray {
        return ByteArray(4) { kotlin.random.Random.nextInt().toByte() }
    }
}

/**
 * Exception thrown when WebSocket parsing fails.
 */
class WebSocketParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
