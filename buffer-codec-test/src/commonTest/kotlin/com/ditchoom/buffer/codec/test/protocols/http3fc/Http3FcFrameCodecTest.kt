package com.ditchoom.buffer.codec.test.protocols.http3fc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.quic.QuicVarintCodec
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * The forward-compatible, **length-free** HTTP/3 frame fixture — the two
 * mechanisms `protocols/http3`'s stored-length fixture does not reach:
 *
 *  - **`@FramedBy` after a varint discriminator**: the Length varint is
 *    computed by [com.ditchoom.buffer.codec.FramedEncoder] on encode and
 *    bounds the body (strict consumption) on decode — no `length` field
 *    anywhere in the model.
 *  - **`@ForwardCompatible` over a varint union**: unknown/GREASE frame
 *    types (RFC 9114 §9) skip-and-preserve into [Http3FcFrame.Unknown]
 *    carrying the full 62-bit opcode, and re-encode byte-identically
 *    (minimal varint encoding) through the discriminator's codec.
 *
 * What this pins:
 *  - round-trip for every known variant across discriminator widths (1-byte
 *    types vs the 2-byte 0x40 Extension) and length widths (≤63 vs >63);
 *  - skip-and-preserve for unknown types at every varint width (1/2/4/8
 *    bytes), with byte-identical re-encode;
 *  - the oversized-type aliasing guard: a type whose low bits collide with
 *    a known type after `toInt()` truncation must decode as Unknown;
 *  - strict consumption: a GOAWAY body with trailing bytes throws (RFC 9114
 *    §7.1 H3_FRAME_ERROR semantics), and applyBound stops the payload at
 *    the frame end so back-to-back frames parse;
 *  - drip-fed `peekFrameSize` = `NeedsMoreData` until exactly
 *    `typeWidth + lengthWidth + length` bytes are buffered — including for
 *    unknown types;
 *  - encode is non-destructive for view payloads (position restored).
 */
class Http3FcFrameCodecTest {
    private fun buffer(bytes: ByteArray): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(bytes.size.coerceAtLeast(1))
        for (b in bytes) buf.writeByte(b)
        buf.resetForRead()
        return buf
    }

    private fun ReadBuffer.toBytes(): ByteArray {
        val saved = position()
        val out = ByteArray(remaining())
        for (i in out.indices) out[i] = readByte()
        position(saved)
        return out
    }

    private fun encodeToBytes(frame: Http3FcFrame): ByteArray =
        Http3FcFrameCodec.encode(frame, EncodeContext.Empty, BufferFactory.Default).toBytes()

    private fun decode(bytes: ByteArray): Http3FcFrame = Http3FcFrameCodec.decode(buffer(bytes), DecodeContext.Empty)

    private fun varint(value: ULong): ByteArray {
        val buf = BufferFactory.Default.allocate(8)
        QuicVarintCodec.encode(buf, value, EncodeContext.Empty)
        buf.resetForRead()
        return buf.toBytes()
    }

    private fun payloadBytes(size: Int): ByteArray = ByteArray(size) { (it and 0x7F).toByte() }

    // --- known variants, length-free round trip ---------------------------------------------

    @Test
    fun dataRoundTripsAcrossLengthWidths() {
        for (size in intArrayOf(0, 3, 200)) {
            val body = payloadBytes(size)
            val wire = encodeToBytes(Http3FcFrame.Data(payload = buffer(body)))
            // Envelope: 1-byte type 0x00 + computed varint length + body.
            assertContentEquals(byteArrayOf(0x00) + varint(size.toULong()) + body, wire, "wire bytes for DATA($size)")
            val decoded = assertIs<Http3FcFrame.Data>(decode(wire))
            assertContentEquals(body, decoded.payload.toBytes(), "payload for DATA($size)")
        }
    }

    @Test
    fun extensionUsesTwoByteTypeVarint() {
        val body = payloadBytes(5)
        val wire = encodeToBytes(Http3FcFrame.Extension(payload = buffer(body)))
        assertContentEquals(varint(0x40uL) + varint(5uL) + body, wire)
        assertEquals(2, varint(0x40uL).size, "0x40 needs the 2-byte varint class")
        val decoded = assertIs<Http3FcFrame.Extension>(decode(wire))
        assertContentEquals(body, decoded.payload.toBytes())
    }

    @Test
    fun settingsRoundTripsStructuredEntries() {
        val frame =
            Http3FcFrame.Settings(
                entries =
                    listOf(
                        Http3FcSetting(identifier = 0x01uL, value = 4096uL), // 1-byte id, 2-byte value
                        Http3FcSetting(identifier = 0xc671706auL, value = 1uL), // 8-byte GREASE-range id
                    ),
            )
        val wire = encodeToBytes(frame)
        val expectedBody = varint(0x01uL) + varint(4096uL) + varint(0xc671706auL) + varint(1uL)
        assertContentEquals(byteArrayOf(0x04) + varint(expectedBody.size.toULong()) + expectedBody, wire)
        val decoded = assertIs<Http3FcFrame.Settings>(decode(wire))
        assertEquals(frame.entries, decoded.entries)
    }

    @Test
    fun goAwayBodyIsASingleVarint() {
        val wire = encodeToBytes(Http3FcFrame.GoAway(id = 12345uL))
        assertContentEquals(byteArrayOf(0x07) + varint(varint(12345uL).size.toULong()) + varint(12345uL), wire)
        assertEquals(12345uL, assertIs<Http3FcFrame.GoAway>(decode(wire)).id)
    }

    @Test
    fun pushPromiseCarriesPushIdThenOpaqueRemainder() {
        val section = payloadBytes(9)
        val wire = encodeToBytes(Http3FcFrame.PushPromise(pushId = 7uL, encodedFieldSection = buffer(section)))
        val body = varint(7uL) + section
        assertContentEquals(byteArrayOf(0x05) + varint(body.size.toULong()) + body, wire)
        val decoded = assertIs<Http3FcFrame.PushPromise>(decode(wire))
        assertEquals(7uL, decoded.pushId)
        assertContentEquals(section, decoded.encodedFieldSection.toBytes())
    }

    // --- forward compatibility: skip-and-preserve --------------------------------------------

    @Test
    fun unknownTypesPreserveAcrossEveryVarintWidth() {
        // GREASE/reserved types needing 1-, 2-, 4-, and 8-byte type varints.
        for (type in ulongArrayOf(0x21uL, 0x1000uL, 0x123456uL, 0x123456789AuL)) {
            val body = payloadBytes(6)
            val wire = varint(type) + varint(6uL) + body
            val decoded = assertIs<Http3FcFrame.Unknown>(decode(wire), "type $type must be unknown")
            assertEquals(type, decoded.opcode, "preserved opcode for $type")
            assertContentEquals(body, decoded.raw.toBytes(), "preserved payload for $type")
            // Byte-identical re-encode (input used minimal varints).
            assertContentEquals(wire, encodeToBytes(decoded), "re-encoded wire for $type")
        }
    }

    @Test
    fun oversizedTypeMustNotAliasOntoAKnownType() {
        // 0x1_0000_0000.toInt() == 0 == DATA: without the dispatch-value
        // clamp this would decode as DATA. It must stay Unknown.
        val type = 0x1_0000_0000uL
        val wire = varint(type) + varint(2uL) + byteArrayOf(1, 2)
        val decoded = assertIs<Http3FcFrame.Unknown>(decode(wire))
        assertEquals(type, decoded.opcode)
        assertContentEquals(wire, encodeToBytes(decoded))
    }

    @Test
    fun unknownWithEmptyPayloadRoundTrips() {
        val wire = varint(0x21uL) + varint(0uL)
        val decoded = assertIs<Http3FcFrame.Unknown>(decode(wire))
        assertEquals(0, decoded.raw.remaining())
        assertContentEquals(wire, encodeToBytes(decoded))
    }

    // --- strict framing -----------------------------------------------------------------------

    @Test
    fun goAwayWithTrailingBytesInsideTheFrameThrows() {
        // Length says 3, but the id varint consumes 1 byte → 2 stray bytes
        // inside the frame body. RFC 9114 §7.1: payload bytes past the
        // identified fields are H3_FRAME_ERROR — strict consumption throws.
        val wire = byteArrayOf(0x07) + varint(3uL) + varint(5uL) + byteArrayOf(0x00, 0x00)
        assertFailsWith<DecodeException> { decode(wire) }
    }

    @Test
    fun frameLengthBoundsThePayloadAndPositionLandsAtFrameEnd() {
        val body = payloadBytes(4)
        val first = byteArrayOf(0x00) + varint(4uL) + body
        val second = byteArrayOf(0x07) + varint(1uL) + varint(9uL)
        val buf = buffer(first + second)
        val frame1 = assertIs<Http3FcFrame.Data>(Http3FcFrameCodec.decode(buf, DecodeContext.Empty))
        assertContentEquals(body, frame1.payload.toBytes(), "payload capped at the frame length")
        // Consume the view (it shares the source buffer's position space is
        // false — it's an independent slice; the source position must already
        // sit at the frame boundary so the next frame decodes).
        val frame2 = assertIs<Http3FcFrame.GoAway>(Http3FcFrameCodec.decode(buf, DecodeContext.Empty))
        assertEquals(9uL, frame2.id, "back-to-back frame after a bounded payload")
    }

    @Test
    fun encodeIsNonDestructiveForViewPayloads() {
        val payload = buffer(payloadBytes(8))
        val frame = Http3FcFrame.Data(payload = payload)
        val first = encodeToBytes(frame)
        assertEquals(8, payload.remaining(), "payload position restored after encode")
        assertContentEquals(first, encodeToBytes(frame), "second encode is identical")
    }

    // --- streaming peek ------------------------------------------------------------------------

    @Test
    fun peekFramesKnownAndUnknownTypesByteByByte() {
        val cases =
            listOf(
                encodeToBytes(Http3FcFrame.Data(payload = buffer(payloadBytes(3)))),
                encodeToBytes(Http3FcFrame.Data(payload = buffer(payloadBytes(200)))), // 2-byte length
                encodeToBytes(Http3FcFrame.Extension(payload = buffer(payloadBytes(3)))), // 2-byte type
                encodeToBytes(Http3FcFrame.GoAway(id = 5uL)),
                varint(0x1000uL) + varint(6uL) + payloadBytes(6), // unknown 2-byte type
            )
        for (wire in cases) {
            assertDripPeek(wire)
        }
    }

    private fun assertDripPeek(wire: ByteArray) {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            for (i in 0 until wire.size - 1) {
                appendByte(stream, wire[i])
                assertEquals(
                    PeekResult.NeedsMoreData,
                    Http3FcFrameCodec.peekFrameSize(stream),
                    "after ${i + 1}/${wire.size} bytes",
                )
            }
            appendByte(stream, wire.last())
            assertEquals(PeekResult.Complete(wire.size), Http3FcFrameCodec.peekFrameSize(stream), "fully buffered")
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
