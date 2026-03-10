package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.codec.test.protocols.WsCloseBody
import com.ditchoom.buffer.codec.test.protocols.WsCloseBodyCodec
import com.ditchoom.buffer.codec.test.protocols.WsCloseCode
import com.ditchoom.buffer.codec.test.protocols.WsFrameHeader
import com.ditchoom.buffer.codec.test.protocols.WsFrameHeaderCodec
import com.ditchoom.buffer.codec.test.protocols.WsHeaderByte1
import com.ditchoom.buffer.codec.test.protocols.WsHeaderByte2
import com.ditchoom.buffer.codec.test.protocols.WsMaskedFrame
import com.ditchoom.buffer.codec.test.protocols.WsMaskedFrameCodec
import com.ditchoom.buffer.codec.test.protocols.WsMaskingKey
import com.ditchoom.buffer.codec.test.protocols.WsUnmaskedFrame
import com.ditchoom.buffer.codec.test.protocols.WsUnmaskedFrameCodec
import com.ditchoom.buffer.codec.testRoundTrip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebSocketFrameRoundTripTest {
    @Test
    fun `frame header text frame`() {
        // FIN=1, opcode=1 (text), mask=0, payloadLengthCode=5
        val original = WsFrameHeader(WsHeaderByte1(0x81u), WsHeaderByte2(0x05u))
        val decoded =
            WsFrameHeaderCodec.testRoundTrip(
                original,
                expectedBytes = byteArrayOf(0x81.toByte(), 0x05),
            )
        assertEquals(original, decoded)
    }

    @Test
    fun `frame header with RSV bits`() {
        // FIN=1, RSV1=1, RSV2=1, RSV3=1, opcode=1
        val original = WsFrameHeader(WsHeaderByte1(0xF1.toByte().toUByte()), WsHeaderByte2(0u))
        val decoded =
            WsFrameHeaderCodec.testRoundTrip(
                original,
                expectedBytes = byteArrayOf(0xF1.toByte(), 0x00),
            )
        assertTrue(decoded.byte1.fin)
        assertTrue(decoded.byte1.rsv1)
        assertTrue(decoded.byte1.rsv2)
        assertTrue(decoded.byte1.rsv3)
        assertEquals(1, decoded.byte1.opcode)
    }

    @Test
    fun `frame header sizeOf is 2`() {
        val header = WsFrameHeader(WsHeaderByte1(0u), WsHeaderByte2(0u))
        assertEquals(2, WsFrameHeaderCodec.sizeOf(header))
    }

    @Test
    fun `unmasked text frame Hello`() {
        val original =
            WsUnmaskedFrame(
                byte1 = WsHeaderByte1(0x81u), // FIN=1, text
                byte2 = WsHeaderByte2(5u), // length=5
                payload = "Hello",
            )
        val decoded = WsUnmaskedFrameCodec.testRoundTrip(original)
        assertEquals(original, decoded)
        assertEquals("Hello", decoded.payload)
    }

    @Test
    fun `unmasked empty payload`() {
        val original =
            WsUnmaskedFrame(
                byte1 = WsHeaderByte1(0x81u),
                byte2 = WsHeaderByte2(0u),
                payload = "",
            )
        val decoded = WsUnmaskedFrameCodec.testRoundTrip(original)
        assertEquals("", decoded.payload)
    }

    @Test
    fun `unmasked max small payload 125 bytes`() {
        val payload = "A".repeat(125)
        val original =
            WsUnmaskedFrame(
                byte1 = WsHeaderByte1(0x81u),
                byte2 = WsHeaderByte2(125u),
                payload = payload,
            )
        val decoded = WsUnmaskedFrameCodec.testRoundTrip(original)
        assertEquals(125, decoded.payload.length)
        assertEquals(payload, decoded.payload)
    }

    @Test
    fun `masked frame with masking key`() {
        val original =
            WsMaskedFrame(
                byte1 = WsHeaderByte1(0x81u),
                byte2 = WsHeaderByte2(0x85u), // mask=1, length=5
                maskingKey = WsMaskingKey(0x37FA213Du),
                payload = "Hello",
            )
        val buffer = PlatformBuffer.allocate(256)
        WsMaskedFrameCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = WsMaskedFrameCodec.decode(buffer)
        assertEquals(original, decoded)
        assertEquals(WsMaskingKey(0x37FA213Du), decoded.maskingKey)
    }

    @Test
    fun `masked frame without mask`() {
        val original =
            WsMaskedFrame(
                byte1 = WsHeaderByte1(0x81u),
                byte2 = WsHeaderByte2(0x05u), // mask=0, length=5
                payload = "Hello",
            )
        val buffer = PlatformBuffer.allocate(256)
        WsMaskedFrameCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = WsMaskedFrameCodec.decode(buffer)
        assertEquals(original, decoded)
        assertEquals(null, decoded.maskingKey)
    }

    @Test
    fun `close body with reason`() {
        val original = WsCloseBody(WsCloseCode.NORMAL, "Normal closure")
        val decoded = WsCloseBodyCodec.testRoundTrip(original)
        assertEquals(original, decoded)
        assertEquals(1000u.toUShort(), decoded.statusCode.raw)
    }

    @Test
    fun `close body no reason`() {
        val original = WsCloseBody(WsCloseCode.GOING_AWAY, "")
        val decoded = WsCloseBodyCodec.testRoundTrip(original)
        assertEquals("", decoded.reason)
        assertEquals(1001u.toUShort(), decoded.statusCode.raw)
    }

    @Test
    fun `close code well-known values`() {
        assertEquals(1000u.toUShort(), WsCloseCode.NORMAL.raw)
        assertEquals(1001u.toUShort(), WsCloseCode.GOING_AWAY.raw)
        assertEquals(1002u.toUShort(), WsCloseCode.PROTOCOL_ERROR.raw)
        assertEquals(1003u.toUShort(), WsCloseCode.UNSUPPORTED_DATA.raw)
        assertEquals(1005u.toUShort(), WsCloseCode.NO_STATUS.raw)
        assertEquals(1006u.toUShort(), WsCloseCode.ABNORMAL_CLOSURE.raw)
        assertEquals(1007u.toUShort(), WsCloseCode.INVALID_PAYLOAD.raw)
        assertEquals(1008u.toUShort(), WsCloseCode.POLICY_VIOLATION.raw)
        assertEquals(1009u.toUShort(), WsCloseCode.MESSAGE_TOO_BIG.raw)
        assertEquals(1010u.toUShort(), WsCloseCode.MANDATORY_EXTENSION.raw)
        assertEquals(1011u.toUShort(), WsCloseCode.INTERNAL_ERROR.raw)
    }

    @Test
    fun `header byte1 bit extraction`() {
        // 0b10010010 = FIN=1, RSV1=0, RSV2=0, RSV3=1, opcode=0010 (binary)
        val byte1 = WsHeaderByte1(0b10010010u)
        assertTrue(byte1.fin)
        assertFalse(byte1.rsv1)
        assertFalse(byte1.rsv2)
        assertTrue(byte1.rsv3)
        assertEquals(2, byte1.opcode) // binary frame
    }

    @Test
    fun `header byte2 bit extraction`() {
        // 0b11111101 = mask=1, payloadLengthCode=125
        val byte2 = WsHeaderByte2(0xFDu)
        assertTrue(byte2.mask)
        assertEquals(125, byte2.payloadLengthCode)

        // 0b01111110 = mask=0, payloadLengthCode=126 (extended 16-bit indicator)
        val byte2ext = WsHeaderByte2(0x7Eu)
        assertFalse(byte2ext.mask)
        assertEquals(126, byte2ext.payloadLengthCode)
    }

    @Test
    fun `header byte2 extended length indicators`() {
        // Small payload (≤125): no extended length, smallPayloadLength returns value
        val small = WsHeaderByte2(125u)
        assertFalse(small.hasExtendedLength16)
        assertFalse(small.hasExtendedLength64)
        assertEquals(125, small.smallPayloadLength)

        // 126 indicates 16-bit extended length follows
        val ext16 = WsHeaderByte2(126u)
        assertTrue(ext16.hasExtendedLength16)
        assertFalse(ext16.hasExtendedLength64)
        assertNull(ext16.smallPayloadLength)

        // 127 indicates 64-bit extended length follows
        val ext64 = WsHeaderByte2(127u)
        assertFalse(ext64.hasExtendedLength16)
        assertTrue(ext64.hasExtendedLength64)
        assertNull(ext64.smallPayloadLength)

        // With mask bit set + 126 indicator
        val maskedExt16 = WsHeaderByte2(0xFEu) // mask=1, payloadLengthCode=126
        assertTrue(maskedExt16.mask)
        assertTrue(maskedExt16.hasExtendedLength16)
        assertFalse(maskedExt16.hasExtendedLength64)
        assertNull(maskedExt16.smallPayloadLength)
    }
}
