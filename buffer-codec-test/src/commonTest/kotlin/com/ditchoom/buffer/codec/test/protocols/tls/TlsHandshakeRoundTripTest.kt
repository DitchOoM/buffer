package com.ditchoom.buffer.codec.test.protocols.tls

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryData
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Issue #151 part 1 — `@LengthFrom("sibling") val: T`
 * round-trips for both nested data-class bodies and sealed-parent
 * bodies. The length sibling is a TLS uint24 (`@WireBytes(3) val
 * length: UInt`) which `@LengthPrefixed` cannot express, so
 * `@LengthFrom` is the only path even though the sibling is adjacent.
 */
class TlsHandshakeRoundTripTest {
    @Test
    fun handshakeWithDataClassBodyRoundTrips() {
        val body =
            TlsHandshakeBody(
                legacyVersion = 0x0303u,
                random = BinaryData(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())),
            )
        val bodyBytes = bodyWireBytes(body)
        val original =
            TlsHandshake(
                msgType = 0x01u,
                length = bodyBytes.toUInt(),
                body = body,
            )
        val decoded = roundTripHandshake(original, bodyBytes)
        assertEquals(original.msgType, decoded.msgType)
        assertEquals(original.length, decoded.length)
        assertEquals(original.body.legacyVersion, decoded.body.legacyVersion)
        assertContentEquals(original.body.random.bytes, decoded.body.random.bytes)
    }

    @Test
    fun handshakeBodyStopsAtLengthBoundary() {
        // Append junk after the framed body; the body decode must not
        // consume past the length-bounded region.
        val body = TlsHandshakeBody(legacyVersion = 0x0304u, random = BinaryData(byteArrayOf(0x01, 0x02)))
        val bodyBytes = bodyWireBytes(body)
        val original = TlsHandshake(msgType = 0x01u, length = bodyBytes.toUInt(), body = body)

        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        TlsHandshakeCodec.encode(buf, original, EncodeContext.Empty)
        // 3 junk bytes after the frame
        buf.writeByte(0xCA.toByte())
        buf.writeByte(0xFE.toByte())
        buf.writeByte(0xBA.toByte())
        val end = buf.position()
        buf.setLimit(end)
        buf.position(0)

        val decoded = TlsHandshakeCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original.msgType, decoded.msgType)
        assertEquals(original.length, decoded.length)
        assertContentEquals(original.body.random.bytes, decoded.body.random.bytes)
        assertEquals(3, buf.remaining(), "junk must remain after length-bounded body decode")
    }

    @Test
    fun handshakeWireSizeIsHeaderPlusLength() {
        val body = TlsHandshakeBody(legacyVersion = 0x0303u, random = BinaryData(byteArrayOf(0x10, 0x20, 0x30)))
        val bodyBytes = bodyWireBytes(body)
        val handshake =
            TlsHandshake(msgType = 0x01u, length = bodyBytes.toUInt(), body = body)
        // Header = 1 (msgType) + 3 (length) = 4; body = 2 (legacyVersion) + 3 (random)
        assertEquals(
            WireSize.Exact(4 + bodyBytes),
            TlsHandshakeCodec.wireSize(handshake, EncodeContext.Empty),
        )
    }

    @Test
    fun handshakeWithSealedClientHelloRoundTrips() {
        val body = TlsHandshakeSealedBody.ClientHello(legacyVersion = 0x0303u)
        // Sealed-body wire = 1 (PacketType discriminator) + 2 (legacyVersion) = 3
        val bodyBytes = 3
        val original =
            TlsHandshakeWithSealedBody(
                msgType = 0x01u,
                length = bodyBytes.toUInt(),
                body = body,
            )
        val expected = byteArrayOf(0x01, 0x00, 0x00, 0x03, 0x01, 0x03, 0x03)
        roundTripHandshakeWithSealed(original, expected)
    }

    @Test
    fun handshakeWithSealedHelloRequestDataObjectRoundTrips() {
        val body = TlsHandshakeSealedBody.HelloRequest
        // Sealed-body wire = 1 (PacketType discriminator) + 0 (singleton) = 1
        val bodyBytes = 1
        val original =
            TlsHandshakeWithSealedBody(
                msgType = 0x00u,
                length = bodyBytes.toUInt(),
                body = body,
            )
        val expected = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x02)
        val decoded = roundTripHandshakeWithSealed(original, expected)
        assertSame(TlsHandshakeSealedBody.HelloRequest, decoded.body)
    }

    private fun bodyWireBytes(body: TlsHandshakeBody): Int = 2 + body.random.bytes.size

    private fun roundTripHandshake(
        sample: TlsHandshake,
        expectedBodyBytes: Int,
    ): TlsHandshake {
        val buf = BufferFactory.Default.allocate(expectedBodyBytes + 16, ByteOrder.BIG_ENDIAN)
        TlsHandshakeCodec.encode(buf, sample, EncodeContext.Empty)
        val total = 1 + 3 + expectedBodyBytes
        assertEquals(total, buf.position(), "encoded byte count")
        buf.resetForRead()
        return TlsHandshakeCodec.decode(buf, DecodeContext.Empty)
    }

    private fun roundTripHandshakeWithSealed(
        sample: TlsHandshakeWithSealedBody,
        expectedBytes: ByteArray,
    ): TlsHandshakeWithSealedBody {
        val buf = BufferFactory.Default.allocate(expectedBytes.size + 16, ByteOrder.BIG_ENDIAN)
        TlsHandshakeWithSealedBodyCodec.encode(buf, sample, EncodeContext.Empty)
        assertEquals(expectedBytes.size, buf.position(), "encoded byte count")
        buf.resetForRead()
        val actual = ByteArray(expectedBytes.size)
        for (i in expectedBytes.indices) actual[i] = buf.readByte()
        if (!expectedBytes.contentEquals(actual)) {
            val expectedHex = expectedBytes.joinToString(" ") { hex(it) }
            val actualHex = actual.joinToString(" ") { hex(it) }
            kotlin.test.fail("wire bytes mismatch.\n  expected: $expectedHex\n    actual: $actualHex")
        }
        buf.resetForRead()
        return TlsHandshakeWithSealedBodyCodec.decode(buf, DecodeContext.Empty)
    }

    private fun hex(b: Byte): String =
        b
            .toUByte()
            .toString(16)
            .padStart(2, '0')
            .uppercase()
}
