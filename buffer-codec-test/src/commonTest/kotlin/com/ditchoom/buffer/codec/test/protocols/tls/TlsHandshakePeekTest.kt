package com.ditchoom.buffer.codec.test.protocols.tls

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.ownedBytesFrom
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryData
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TLS 1.3 handshake-message framing (RFC 8446 §4): `HandshakeType msg_type (1)`
 * + `uint24 length (3)` + `body[length]`. The handshake message is self-
 * delimiting via the `uint24` length, so `TlsHandshakeCodec.peekFrameSize`
 * frames the whole message — `Complete(1 + 3 + length)`.
 *
 * This pins the load-bearing invariant `peek.bytes == decode consumption` across
 * the `uint24` length range (small body → length < 256; large body → length
 * spilling into the upper length bytes), byte-at-a-time: `NeedsMoreData` for
 * every prefix shorter than the full message, then `Complete(total)`.
 */
class TlsHandshakePeekTest {
    private fun payload(size: Int): BinaryData {
        val buf = BufferFactory.Default.allocate(size, ByteOrder.BIG_ENDIAN)
        for (i in 0 until size) buf.writeByte((i and 0x7F).toByte())
        buf.resetForRead()
        return BinaryData(ownedBytesFrom(buf))
    }

    private fun handshake(randomSize: Int): TlsHandshake {
        val body = TlsHandshakeBody(legacyVersion = 0x0303u, random = payload(randomSize))
        val bodyBytes = 2 + randomSize // legacyVersion(2) + random[randomSize]
        return TlsHandshake(msgType = 0x01u, length = bodyBytes.toUInt(), body = body)
    }

    @Test
    fun peekFramesHandshakeAcrossUint24LengthRange() {
        // randomSize 4 → length 6 (single low byte); randomSize 300 → length 302
        // (0x00012E — exercises the middle uint24 byte). total = 1 + 3 + 2 + randomSize.
        for (randomSize in listOf(4, 300)) {
            assertDripPeek(handshake(randomSize), expectedTotal = 6 + randomSize)
        }
    }

    private fun assertDripPeek(
        frame: TlsHandshake,
        expectedTotal: Int,
    ) {
        val pool = BufferPool()
        val source = BufferFactory.Default.allocate(expectedTotal + 16, ByteOrder.BIG_ENDIAN)
        TlsHandshakeCodec.encode(source, frame, EncodeContext.Empty)
        source.resetForRead()
        val total = source.remaining()
        assertEquals(expectedTotal, total, "encoded total")
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            for (i in 0 until total - 1) {
                appendByte(stream, source.readByte())
                assertEquals(
                    PeekResult.NeedsMoreData,
                    TlsHandshakeCodec.peekFrameSize(stream),
                    "after ${i + 1}/$total bytes",
                )
            }
            appendByte(stream, source.readByte())
            assertEquals(
                PeekResult.Complete(total),
                TlsHandshakeCodec.peekFrameSize(stream),
                "fully buffered",
            )
            // The peeked frame size must equal what decode actually consumes.
            val decodeBuffer = BufferFactory.Default.allocate(total + 16, ByteOrder.BIG_ENDIAN)
            TlsHandshakeCodec.encode(decodeBuffer, frame, EncodeContext.Empty)
            decodeBuffer.resetForRead()
            TlsHandshakeCodec.decode(decodeBuffer, DecodeContext.Empty)
            assertEquals(total, decodeBuffer.position(), "decode consumed exactly the peeked frame size")
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun appendByte(
        stream: StreamProcessor,
        byte: Byte,
    ) {
        val one: PlatformBuffer = BufferFactory.Default.allocate(1)
        one.writeByte(byte)
        one.resetForRead()
        stream.append(one)
    }
}
