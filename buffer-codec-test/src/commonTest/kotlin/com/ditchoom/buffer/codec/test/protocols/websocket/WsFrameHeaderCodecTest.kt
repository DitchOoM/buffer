package com.ditchoom.buffer.codec.test.protocols.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RFC 6455 §5.2 frame-header round-trip vectors. These pin down the codec emitter for
 * the three encoded length forms (7-bit, 16-bit, 64-bit) and the optional 4-byte masking
 * key, all gated by `@When` predicates that read value-class properties of a sibling
 * field — the most demanding shape the websocket model puts on the codec processor.
 *
 * Header encode/decode is exercised in isolation here. Sealed-dispatch + variant
 * decoding is covered by [WsFrameDispatchCodecTest]; close-body shape is covered by
 * [WsCloseBodyCodecTest].
 */
class WsFrameHeaderCodecTest {
    /**
     * Vector: minimal unmasked text-frame header (FIN=1, opcode=Text, len=5).
     * RFC 6455 §5.7 example "A single-frame unmasked text message" — header bytes only.
     *
     * Wire: `0x81 0x05`
     */
    @Test
    fun unmaskedShortHeaderIsTwoBytes() {
        val header =
            WsFrameHeader.build(
                byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, opcode = 0x1),
                payloadSize = 5L,
                maskingKey = null,
            )
        assertEquals(2, header.wireSize, "no extended length, no masking key → 2 bytes")
        assertNull(header.extendedLength16)
        assertNull(header.extendedLength64)
        assertNull(header.maskingKey)

        val buf = BufferFactory.Default.allocate(2)
        WsFrameHeaderCodec.encode(buf, header, EncodeContext.Empty)
        assertEquals(2, buf.position(), "encode wrote exactly 2 bytes")

        buf.resetForRead()
        assertEquals(0x81.toByte(), buf.readByte(), "byte 1 = FIN | Text opcode")
        assertEquals(0x05.toByte(), buf.readByte(), "byte 2 = MASK=0 | len=5")

        buf.resetForRead()
        val decoded = WsFrameHeaderCodec.decode(buf, DecodeContext.Empty)
        assertEquals(header, decoded)
        assertEquals(5L, decoded.payloadLength)
        assertEquals(0x1, decoded.opcodeValue)
    }

    /**
     * Vector: masked text-frame header (FIN=1, opcode=Text, MASK=1, len=5, key=0x37fa213d).
     * RFC 6455 §5.7 example "A single-frame masked text message" — header bytes only.
     *
     * Wire: `0x81 0x85 0x37 0xfa 0x21 0x3d`
     */
    @Test
    fun maskedShortHeaderIsSixBytes() {
        val header =
            WsFrameHeader.build(
                byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, opcode = 0x1),
                payloadSize = 5L,
                maskingKey = WsMaskingKey(0x37fa213du),
            )
        assertEquals(6, header.wireSize)
        assertNotNull(header.maskingKey)
        assertTrue(header.byte2.masked)

        val buf = BufferFactory.Default.allocate(6)
        WsFrameHeaderCodec.encode(buf, header, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(0x81.toByte(), buf.readByte())
        assertEquals(0x85.toByte(), buf.readByte(), "byte 2 = MASK=1 | len=5")
        assertEquals(0x37.toByte(), buf.readByte(), "mask byte 0")
        assertEquals(0xfa.toByte(), buf.readByte(), "mask byte 1")
        assertEquals(0x21.toByte(), buf.readByte(), "mask byte 2")
        assertEquals(0x3d.toByte(), buf.readByte(), "mask byte 3")

        buf.resetForRead()
        val decoded = WsFrameHeaderCodec.decode(buf, DecodeContext.Empty)
        assertEquals(header, decoded)
        assertEquals(WsMaskingKey(0x37fa213du), decoded.maskingKey)
    }

    /**
     * Vector: 200-byte unmasked binary-frame header. Length 200 > 125 forces the 16-bit
     * extended-length form (lengthIndicator==126, extendedLength16=0x00C8).
     *
     * RFC 6455 §5.7 example "A single-frame unmasked binary message of 256 bytes" uses
     * 0x0100; 200 here is the smallest realistic value past the 7-bit threshold and keeps
     * the test buffer small. The 64-bit form is exercised by [extended64HeaderShape].
     *
     * Wire: `0x82 0x7E 0x00 0xC8`
     */
    @Test
    fun extended16HeaderShape() {
        val header =
            WsFrameHeader.build(
                byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, opcode = 0x2),
                payloadSize = 200L,
                maskingKey = null,
            )
        assertEquals(4, header.wireSize)
        assertEquals(126, header.byte2.lengthIndicator, "lengthIndicator==126 signals 16-bit ext")
        assertTrue(header.byte2.extended16)
        assertEquals(200u.toUShort(), header.extendedLength16)
        assertNull(header.extendedLength64)
        assertEquals(200L, header.payloadLength)

        val buf = BufferFactory.Default.allocate(4)
        WsFrameHeaderCodec.encode(buf, header, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(0x82.toByte(), buf.readByte(), "FIN | Binary")
        assertEquals(0x7E.toByte(), buf.readByte(), "MASK=0 | len=126")
        assertEquals(0x00.toByte(), buf.readByte(), "ext16 high byte (BE)")
        assertEquals(0xC8.toByte(), buf.readByte(), "ext16 low byte = 200")

        buf.resetForRead()
        assertEquals(header, WsFrameHeaderCodec.decode(buf, DecodeContext.Empty))
    }

    /**
     * Vector: 65536-byte (= 0x10000) unmasked binary-frame header. Length > 0xFFFF forces
     * the 64-bit extended-length form (lengthIndicator==127, extendedLength64=0x10000).
     *
     * Wire: `0x82 0x7F 0x00 0x00 0x00 0x00 0x00 0x01 0x00 0x00`
     */
    @Test
    fun extended64HeaderShape() {
        val header =
            WsFrameHeader.build(
                byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, opcode = 0x2),
                payloadSize = 0x10000L,
                maskingKey = null,
            )
        assertEquals(10, header.wireSize)
        assertEquals(127, header.byte2.lengthIndicator)
        assertTrue(header.byte2.extended64)
        assertNull(header.extendedLength16)
        assertEquals(0x10000L, header.extendedLength64)

        val buf = BufferFactory.Default.allocate(10)
        WsFrameHeaderCodec.encode(buf, header, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(0x82.toByte(), buf.readByte())
        assertEquals(0x7F.toByte(), buf.readByte(), "MASK=0 | len=127")
        // BE 8-byte length: 0x00_00_00_00_00_01_00_00 = 65536
        assertEquals(0x00.toByte(), buf.readByte())
        assertEquals(0x00.toByte(), buf.readByte())
        assertEquals(0x00.toByte(), buf.readByte())
        assertEquals(0x00.toByte(), buf.readByte())
        assertEquals(0x00.toByte(), buf.readByte())
        assertEquals(0x01.toByte(), buf.readByte(), "high byte of low 32 bits")
        assertEquals(0x00.toByte(), buf.readByte())
        assertEquals(0x00.toByte(), buf.readByte())

        buf.resetForRead()
        assertEquals(header, WsFrameHeaderCodec.decode(buf, DecodeContext.Empty))
    }

    /**
     * Vector: extended-16 + masked. Tests both `@When` predicates fire together.
     * Length 200, masked, key=0x12345678 → 8-byte header.
     *
     * Wire: `0x82 0xFE 0x00 0xC8 0x12 0x34 0x56 0x78`
     */
    @Test
    fun extended16AndMaskedHeaderShape() {
        val header =
            WsFrameHeader.build(
                byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, opcode = 0x2),
                payloadSize = 200L,
                maskingKey = WsMaskingKey(0x12345678u),
            )
        assertEquals(8, header.wireSize, "byte1 + byte2 + ext16 + mask = 1+1+2+4")

        val buf = BufferFactory.Default.allocate(8)
        WsFrameHeaderCodec.encode(buf, header, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(0x82.toByte(), buf.readByte())
        assertEquals(0xFE.toByte(), buf.readByte(), "MASK=1 | len=126")
        assertEquals(0x00.toByte(), buf.readByte())
        assertEquals(0xC8.toByte(), buf.readByte())
        assertEquals(0x12.toByte(), buf.readByte())
        assertEquals(0x34.toByte(), buf.readByte())
        assertEquals(0x56.toByte(), buf.readByte())
        assertEquals(0x78.toByte(), buf.readByte())

        buf.resetForRead()
        assertEquals(header, WsFrameHeaderCodec.decode(buf, DecodeContext.Empty))
    }

    /**
     * RSV1 carries permessage-deflate's compression flag (RFC 7692). The codec must
     * round-trip the bit transparently — interpretation is the consumer's job.
     */
    @Test
    fun rsv1BitRoundTrips() {
        val header =
            WsFrameHeader.build(
                byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = true, rsv2 = false, rsv3 = false, opcode = 0x1),
                payloadSize = 5L,
                maskingKey = null,
            )
        val buf = BufferFactory.Default.allocate(2)
        WsFrameHeaderCodec.encode(buf, header, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(0xC1.toByte(), buf.readByte(), "FIN | RSV1 | Text = 0x80 | 0x40 | 0x01 = 0xC1")
        buf.position(0)
        val decoded = WsFrameHeaderCodec.decode(buf, DecodeContext.Empty)
        assertTrue(decoded.byte1.rsv1)
        assertTrue(decoded.byte1.fin)
    }

    /**
     * Fragmentation marker: FIN=0 means "more fragments follow". The bit must round-trip
     * through `byte1.raw` regardless of the overall frame shape.
     */
    @Test
    fun finBitFalseRoundTrips() {
        val header =
            WsFrameHeader.build(
                byte1 = FrameHeaderByte1.pack(fin = false, rsv1 = false, rsv2 = false, rsv3 = false, opcode = 0x1),
                payloadSize = 3L,
                maskingKey = null,
            )
        val buf = BufferFactory.Default.allocate(2)
        WsFrameHeaderCodec.encode(buf, header, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(0x01.toByte(), buf.readByte(), "FIN=0 | Text = 0x01")
        buf.position(0)
        assertTrue(!WsFrameHeaderCodec.decode(buf, DecodeContext.Empty).byte1.fin)
    }

    /**
     * 7-bit length boundary (125 = max for 7-bit form). One byte higher (126) flips
     * to extended-16, so this confirms we don't slide off the boundary by one.
     */
    @Test
    fun length125StaysIn7BitForm() {
        val header =
            WsFrameHeader.build(
                byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, opcode = 0x2),
                payloadSize = 125L,
                maskingKey = null,
            )
        assertEquals(2, header.wireSize)
        assertEquals(125, header.byte2.lengthIndicator)
        assertTrue(!header.byte2.extended16 && !header.byte2.extended64)
    }

    /**
     * 16-bit length upper boundary (65535 = max for 16-bit form). One higher (65536)
     * flips to extended-64.
     */
    @Test
    fun length65535StaysIn16BitForm() {
        val header =
            WsFrameHeader.build(
                byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, opcode = 0x2),
                payloadSize = 0xFFFFL,
                maskingKey = null,
            )
        assertEquals(4, header.wireSize)
        assertTrue(header.byte2.extended16)
        assertTrue(!header.byte2.extended64)
        assertEquals(0xFFFFu.toUShort(), header.extendedLength16)
    }
}
