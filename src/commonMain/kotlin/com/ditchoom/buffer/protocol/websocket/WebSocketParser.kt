package com.ditchoom.buffer.protocol.websocket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.AccumulatingBufferReader
import com.ditchoom.buffer.stream.BufferChunk
import com.ditchoom.buffer.stream.BufferStream

/**
 * High-performance WebSocket frame parser using zero-copy buffer operations.
 *
 * Implements RFC 6455 frame parsing with support for:
 * - Fragmented messages
 * - Masked payloads
 * - Per-message deflate compression (when decompressor is provided)
 */
class WebSocketParser(
    private val pool: BufferPool,
    private val decompressor: ((ReadBuffer) -> ReadBuffer)? = null,
) {
    /**
     * Parses WebSocket frames from a buffer stream.
     */
    fun parseFrames(
        stream: BufferStream,
        handler: (WebSocketFrame) -> Unit,
    ) {
        val reader = AccumulatingBufferReader(pool)

        try {
            stream.forEachChunk { chunk ->
                reader.append(chunk)

                while (reader.available() >= 2) {
                    val frame = tryParseFrame(reader)
                    if (frame != null) {
                        handler(frame)
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
     * Parses a single frame from a ReadBuffer.
     */
    fun parseFrame(buffer: ReadBuffer): WebSocketFrame {
        val reader = AccumulatingBufferReader(pool)
        try {
            reader.append(BufferChunk(buffer, true, 0))
            return tryParseFrame(reader)
                ?: throw WebSocketParseException("Incomplete WebSocket frame")
        } finally {
            reader.release()
        }
    }

    private fun tryParseFrame(reader: AccumulatingBufferReader): WebSocketFrame? {
        if (reader.available() < 2) return null

        // Peek at first two bytes without consuming
        val byte1 = reader.peekByte(0).toInt() and 0xFF
        val byte2 = reader.peekByte(1).toInt() and 0xFF

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
        payloadLen =
            when (payloadLen.toInt()) {
                126 -> {
                    (
                        ((reader.readByte().toInt() and 0xFF) shl 8) or
                            (reader.readByte().toInt() and 0xFF)
                    ).toLong()
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

        // Read masking key as buffer
        val maskingKey =
            if (masked) {
                val keyBuffer = reader.readBuffer(4)
                byteArrayOf(
                    keyBuffer.readByte(),
                    keyBuffer.readByte(),
                    keyBuffer.readByte(),
                    keyBuffer.readByte(),
                )
            } else {
                null
            }

        // Check if we have full payload
        if (reader.available() < payloadLen.toInt()) {
            return null
        }

        // Read payload as buffer
        val rawPayloadBuffer = reader.readBuffer(payloadLen.toInt())

        // Unmask if needed - this requires creating a new buffer
        val unmaskedPayload =
            if (masked && maskingKey != null) {
                unmaskToBuffer(rawPayloadBuffer, maskingKey)
            } else {
                rawPayloadBuffer
            }

        // Handle compression (rsv1 indicates per-message deflate)
        val payload =
            if (rsv1 && decompressor != null && unmaskedPayload.remaining() > 0) {
                WebSocketPayload.Compressed(unmaskedPayload, decompressor)
            } else {
                WebSocketPayload.Buffered(unmaskedPayload, payloadLen)
            }

        // Create frame based on opcode
        return when (opcode) {
            WebSocketOpcode.Continuation ->
                WebSocketFrame.Continuation(
                    fin,
                    rsv1,
                    rsv2,
                    rsv3,
                    masked,
                    maskingKey,
                    payloadLen,
                    payload,
                )

            WebSocketOpcode.Text ->
                WebSocketFrame.Text(
                    fin,
                    rsv1,
                    rsv2,
                    rsv3,
                    masked,
                    maskingKey,
                    payloadLen,
                    payload,
                )

            WebSocketOpcode.Binary ->
                WebSocketFrame.Binary(
                    fin,
                    rsv1,
                    rsv2,
                    rsv3,
                    masked,
                    maskingKey,
                    payloadLen,
                    payload,
                )

            WebSocketOpcode.Close -> {
                val (closeCode, reason) = parseClosePayload(unmaskedPayload)
                WebSocketFrame.Close(
                    fin,
                    rsv1,
                    rsv2,
                    rsv3,
                    masked,
                    maskingKey,
                    payloadLen,
                    payload,
                    closeCode,
                    reason,
                )
            }

            WebSocketOpcode.Ping ->
                WebSocketFrame.Ping(
                    fin,
                    rsv1,
                    rsv2,
                    rsv3,
                    masked,
                    maskingKey,
                    payloadLen,
                    payload,
                )

            WebSocketOpcode.Pong ->
                WebSocketFrame.Pong(
                    fin,
                    rsv1,
                    rsv2,
                    rsv3,
                    masked,
                    maskingKey,
                    payloadLen,
                    payload,
                )

            is WebSocketOpcode.Reserved -> throw WebSocketParseException(
                "Reserved opcode: ${opcode.value}",
            )
        }
    }

    private fun unmaskToBuffer(
        data: ReadBuffer,
        maskingKey: ByteArray,
    ): ReadBuffer {
        val size = data.remaining()
        val result = PlatformBuffer.allocate(size)
        for (i in 0 until size) {
            val maskedByte = (data.readByte().toInt() xor maskingKey[i % 4].toInt()).toByte()
            result.writeByte(maskedByte)
        }
        result.resetForRead()
        return result
    }

    private fun parseClosePayload(payload: ReadBuffer): Pair<WebSocketCloseCode, String> {
        if (payload.remaining() < 2) {
            return WebSocketCloseCode.NoStatusReceived to ""
        }

        val code =
            ((payload.readByte().toInt() and 0xFF) shl 8) or
                (payload.readByte().toInt() and 0xFF)
        val reason =
            if (payload.remaining() > 0) {
                payload.readString(payload.remaining())
            } else {
                ""
            }

        return WebSocketCloseCode.fromInt(code) to reason
    }
}

/**
 * Serializes WebSocket frames to buffers.
 */
class WebSocketSerializer(
    private val pool: BufferPool,
    private val compressor: ((ReadBuffer) -> ReadBuffer)? = null,
) {
    /**
     * Serializes a text frame to a WriteBuffer.
     * Returns the number of bytes written.
     */
    fun serializeTextFrame(
        text: String,
        buffer: WriteBuffer,
        masked: Boolean = false,
        compress: Boolean = false,
    ): Int {
        val textBuffer = PlatformBuffer.allocate(text.length * 4)
        textBuffer.writeString(text)
        textBuffer.resetForRead()
        return serializeFrame(WebSocketOpcode.Text, textBuffer, buffer, masked, compress)
    }

    /**
     * Serializes a binary frame to a WriteBuffer.
     * Returns the number of bytes written.
     */
    fun serializeBinaryFrame(
        data: ReadBuffer,
        buffer: WriteBuffer,
        masked: Boolean = false,
        compress: Boolean = false,
    ): Int = serializeFrame(WebSocketOpcode.Binary, data, buffer, masked, compress)

    /**
     * Serializes a close frame to a WriteBuffer.
     * Returns the number of bytes written.
     */
    fun serializeCloseFrame(
        code: WebSocketCloseCode,
        reason: String = "",
        buffer: WriteBuffer,
        masked: Boolean = false,
    ): Int {
        val payloadBuffer = PlatformBuffer.allocate(2 + reason.length * 4)
        payloadBuffer.writeByte(((code.code shr 8) and 0xFF).toByte())
        payloadBuffer.writeByte((code.code and 0xFF).toByte())
        payloadBuffer.writeString(reason)
        payloadBuffer.resetForRead()
        return serializeFrame(WebSocketOpcode.Close, payloadBuffer, buffer, masked, compress = false)
    }

    /**
     * Serializes a ping frame to a WriteBuffer.
     * Returns the number of bytes written.
     */
    fun serializePingFrame(
        data: ReadBuffer = ReadBuffer.EMPTY_BUFFER,
        buffer: WriteBuffer,
        masked: Boolean = false,
    ): Int = serializeFrame(WebSocketOpcode.Ping, data, buffer, masked, compress = false)

    /**
     * Serializes a pong frame to a WriteBuffer.
     * Returns the number of bytes written.
     */
    fun serializePongFrame(
        data: ReadBuffer = ReadBuffer.EMPTY_BUFFER,
        buffer: WriteBuffer,
        masked: Boolean = false,
    ): Int = serializeFrame(WebSocketOpcode.Pong, data, buffer, masked, compress = false)

    private fun serializeFrame(
        opcode: WebSocketOpcode,
        payload: ReadBuffer,
        buffer: WriteBuffer,
        masked: Boolean,
        compress: Boolean,
    ): Int {
        val startPos = buffer.position()

        val finalPayload =
            if (compress && compressor != null) {
                compressor.invoke(payload)
            } else {
                payload
            }

        val payloadLen = finalPayload.remaining()
        val rsv1 = compress && compressor != null

        // Byte 1: FIN, RSV1-3, opcode
        val byte1 =
            0x80 or // FIN
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
            for (i in 0 until payloadLen) {
                val byte = finalPayload.readByte()
                val maskedByte = (byte.toInt() xor maskingKey[i % 4].toInt()).toByte()
                buffer.writeByte(maskedByte)
            }
        } else {
            buffer.write(finalPayload)
        }

        return buffer.position() - startPos
    }

    private fun generateMaskingKey(): ByteArray =
        ByteArray(4) {
            kotlin.random.Random
                .nextInt()
                .toByte()
        }
}

/**
 * Exception thrown when WebSocket parsing fails.
 */
class WebSocketParseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
