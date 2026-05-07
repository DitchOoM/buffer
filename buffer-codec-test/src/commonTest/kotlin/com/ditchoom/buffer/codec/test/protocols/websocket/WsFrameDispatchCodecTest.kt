package com.ditchoom.buffer.codec.test.protocols.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryData
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Full-frame round-trip vectors from RFC 6455 §5.7 ("Examples"), plus a few extras for
 * shapes the codec emitter cares about (reserved-opcode rejection via dispatch, ext-16 /
 * ext-64 binary dispatch, FIN=0 fragmentation marker survival, unmask-after-decode).
 *
 * Each vector pre-bounds the buffer to the frame's exact wire size before decoding —
 * mirroring how `WebSocketCodec.readNextFrame` in the websocket repo first peeks the
 * frame size via `WsFraming`, then hands `WsFrameCodec.decode` a buffer whose limit is
 * the frame end. The codec itself does not infer frame size from the variable-length
 * header; that's `WsFraming`'s handwritten job.
 */
class WsFrameDispatchCodecTest {
    /**
     * RFC 6455 §5.7 — "A single-frame unmasked text message" (the "Hello" vector).
     *
     * Wire: `0x81 0x05 0x48 0x65 0x6c 0x6c 0x6f`
     *
     * Pins down: end-to-end dispatch on opcode 0x1 → `Text` variant + `@RemainingBytes
     * String` payload that must be valid UTF-8.
     */
    @Test
    fun unmaskedTextHelloRoundTripsByteExact() {
        val wire = byteArrayOf(0x81.toByte(), 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f)
        val frame =
            WsFrame.Text(
                byte1 = FrameHeaderByte1.pack(true, rsv1 = false, rsv2 = false, rsv3 = false, opcode = 0x1),
                byte2 = WsHeaderByte2.pack(payloadSize = 5L, masked = false),
                payload = "Hello",
            )

        roundTripFrameByteExact(frame, wire)
        val decoded = decodeBounded(wire)
        assertTrue(decoded is WsFrame.Text, "dispatched to Text by opcode 0x1")
        assertEquals("Hello", decoded.payload)
        assertTrue(decoded.byte1.fin)
        assertEquals(5, decoded.byte2.lengthIndicator)
    }

    /**
     * RFC 6455 §5.7 — "A fragmented unmasked text message". Two frames:
     *   Frame 1: `0x01 0x03 0x48 0x65 0x6c`           (FIN=0, opcode=Text, len=3, "Hel")
     *   Frame 2: `0x80 0x02 0x6c 0x6f`                (FIN=1, opcode=Continuation, len=2, "lo")
     *
     * Pins down: FIN bit dispatch + Continuation variant. State-machine reassembly is NOT
     * in scope (that's `MessageAssembler` in the websocket repo, which is irreducibly
     * stateful and outside the codec layer).
     */
    @Test
    fun fragmentedTextHelloEachFrameRoundTrips() {
        val frame1Wire = byteArrayOf(0x01, 0x03, 0x48, 0x65, 0x6c)
        val frame1 =
            WsFrame.Text(
                byte1 = FrameHeaderByte1.pack(false, rsv1 = false, rsv2 = false, rsv3 = false, opcode = 0x1),
                byte2 = WsHeaderByte2.pack(payloadSize = 3L, masked = false),
                payload = "Hel",
            )
        val frame2Wire = byteArrayOf(0x80.toByte(), 0x02, 0x6c, 0x6f)
        val frame2 =
            WsFrame.Continuation(
                byte1 = FrameHeaderByte1.pack(true, rsv1 = false, rsv2 = false, rsv3 = false, opcode = 0x0),
                byte2 = WsHeaderByte2.pack(payloadSize = 2L, masked = false),
                payload = BinaryData(byteArrayOf(0x6c, 0x6f)),
            )

        roundTripFrameByteExact(frame1, frame1Wire)
        val decoded1 = decodeBounded(frame1Wire)
        assertTrue(decoded1 is WsFrame.Text)
        assertTrue(!decoded1.byte1.fin, "first fragment must have FIN=0")
        assertEquals("Hel", decoded1.payload)

        roundTripFrameByteExact(frame2, frame2Wire)
        val decoded2 = decodeBounded(frame2Wire)
        assertTrue(decoded2 is WsFrame.Continuation, "0x0 → Continuation")
        assertTrue(decoded2.byte1.fin, "final fragment must have FIN=1")
        assertContentEquals(byteArrayOf(0x6c, 0x6f), decoded2.payload.bytes)
    }

    /**
     * RFC 6455 §5.7 — "Unmasked Ping". Carries "Hello" as application data.
     *
     * Ping wire: `0x89 0x05 0x48 0x65 0x6c 0x6c 0x6f`
     */
    @Test
    fun unmaskedPingHelloDispatchesAndRoundTrips() {
        val wire = byteArrayOf(0x89.toByte(), 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f)
        val frame =
            WsFrame.Ping(
                byte1 = FrameHeaderByte1.pack(true, rsv1 = false, rsv2 = false, rsv3 = false, opcode = 0x9),
                byte2 = WsHeaderByte2.pack(payloadSize = 5L, masked = false),
                payload = BinaryData("Hello".encodeToByteArray()),
            )
        roundTripFrameByteExact(frame, wire)
        val decoded = decodeBounded(wire)
        assertTrue(decoded is WsFrame.Ping, "0x9 → Ping")
        assertContentEquals("Hello".encodeToByteArray(), decoded.payload.bytes)
    }

    /**
     * RFC 6455 §5.7 — "Masked Pong response". Same wire shape as the masked-text vector
     * but with opcode=0xA. Demonstrates that:
     *   1. The codec round-trips both the masking-key field and the masked payload bytes.
     *   2. Masking-XOR is a layer ABOVE the codec — the consumer applies `xorMask` after
     *      decode (or before encode) using the masking key. The codec itself never
     *      transforms payload bytes.
     *
     * Pong wire (RFC §5.7): `0x8a 0x85 0x37 0xfa 0x21 0x3d 0x7f 0x9f 0x4d 0x51 0x58`
     *   Header bytes 0..5: opcode=0xA, MASK=1, len=5, key=0x37fa213d
     *   Payload bytes 6..10: masked "Hello" → XOR with key cycle → 0x7f 0x9f 0x4d 0x51 0x58
     */
    @Test
    fun maskedPongHelloRoundTripsAndUnmasksToPlainHello() {
        val wire =
            byteArrayOf(
                0x8a.toByte(),
                0x85.toByte(),
                0x37.toByte(),
                0xfa.toByte(),
                0x21.toByte(),
                0x3d.toByte(),
                0x7f.toByte(),
                0x9f.toByte(),
                0x4d.toByte(),
                0x51.toByte(),
                0x58.toByte(),
            )
        val frame =
            WsFrame.Pong(
                byte1 = FrameHeaderByte1.pack(true, rsv1 = false, rsv2 = false, rsv3 = false, opcode = 0xA),
                byte2 = WsHeaderByte2.pack(payloadSize = 5L, masked = true),
                maskingKey = WsMaskingKey(0x37fa213du),
                payload = BinaryData(byteArrayOf(0x7f, 0x9f.toByte(), 0x4d, 0x51, 0x58)),
            )
        roundTripFrameByteExact(frame, wire)

        val decoded = decodeBounded(wire)
        assertTrue(decoded is WsFrame.Pong, "0xA → Pong")
        assertEquals(WsMaskingKey(0x37fa213du), decoded.maskingKey)

        // Apply xorMask off-codec — recovers "Hello".
        val maskedBuf = BufferFactory.Default.wrap(decoded.payload.bytes)
        maskedBuf.setLimit(5)
        maskedBuf.xorMask(0x37fa213d)
        maskedBuf.position(0)
        val unmasked = ByteArray(5) { maskedBuf.readByte() }
        assertContentEquals("Hello".encodeToByteArray(), unmasked, "xorMask off-codec recovers cleartext")
    }

    /**
     * RFC 6455 §5.7 — "A single-frame unmasked binary message of 256 bytes" (forces ext-16).
     *
     * Wire: `0x82 0x7E 0x01 0x00 + <256 bytes>`. Deterministic byte fill so the round-trip
     * can assert content.
     */
    @Test
    fun extended16BinaryRoundTrips() {
        val payloadBytes = ByteArray(256) { it.toByte() }
        val frame =
            WsFrame.Binary(
                byte1 = FrameHeaderByte1.pack(true, rsv1 = false, rsv2 = false, rsv3 = false, opcode = 0x2),
                byte2 = WsHeaderByte2.pack(payloadSize = 256L, masked = false),
                extendedLength16 = 256u.toUShort(),
                payload = BinaryData(payloadBytes),
            )

        val totalSize = 4 + 256
        val encoded = BufferFactory.Default.allocate(totalSize)
        WsFrameCodec.encode(encoded, frame, EncodeContext.Empty)
        assertEquals(totalSize, encoded.position())
        encoded.resetForRead()
        assertEquals(0x82.toByte(), encoded.readByte())
        assertEquals(0x7E.toByte(), encoded.readByte())
        assertEquals(0x01.toByte(), encoded.readByte(), "ext16 high byte")
        assertEquals(0x00.toByte(), encoded.readByte(), "ext16 low byte")
        for (i in 0 until 256) {
            assertEquals(i.toByte(), encoded.readByte(), "payload byte $i")
        }

        encoded.resetForRead()
        encoded.setLimit(totalSize)
        val decoded = WsFrameCodec.decode(encoded, DecodeContext.Empty)
        assertTrue(decoded is WsFrame.Binary)
        assertEquals(256u.toUShort(), decoded.extendedLength16)
        assertContentEquals(payloadBytes, decoded.payload.bytes)
    }

    /**
     * Reserved opcodes (0x3-0x7 non-control, 0xB-0xF control) have no `@PacketType`, so
     * dispatch must throw rather than silently route to an unrelated variant. RFC 6455
     * §5.2 mandates this is treated as a protocol error.
     *
     * Tests opcodes 0x3 (lowest reserved non-control) and 0xB (lowest reserved control).
     * This is "free policy" — the codec rejects reserved opcodes because no variant
     * matches; no annotation work needed on the consumer side.
     */
    @Test
    fun reservedOpcodesFailDispatch() {
        val wire3 = byteArrayOf(0x83.toByte(), 0x00)
        val buf3 = BufferFactory.Default.wrap(wire3)
        buf3.setLimit(2)
        assertFails("reserved opcode 0x3 must be rejected") {
            WsFrameCodec.decode(buf3, DecodeContext.Empty)
        }

        val wireB = byteArrayOf(0x8B.toByte(), 0x00)
        val bufB = BufferFactory.Default.wrap(wireB)
        bufB.setLimit(2)
        assertFails("reserved opcode 0xB must be rejected") {
            WsFrameCodec.decode(bufB, DecodeContext.Empty)
        }
    }

    /**
     * Empty-payload Close — no body present (status code absent ⇒ "1005 No Status Received"
     * by spec convention). Pins down `@When("remaining >= 2")` decoding to null when the
     * variant's preceding fields have consumed the entire bounded buffer.
     *
     * Wire: `0x88 0x00`
     */
    @Test
    fun emptyCloseHasNoBody() {
        val wire = byteArrayOf(0x88.toByte(), 0x00)
        val frame =
            WsFrame.Close(
                byte1 = FrameHeaderByte1.pack(true, rsv1 = false, rsv2 = false, rsv3 = false, opcode = 0x8),
                byte2 = WsHeaderByte2.pack(payloadSize = 0L, masked = false),
                body = null,
            )
        roundTripFrameByteExact(frame, wire)
        val decoded = decodeBounded(wire)
        assertTrue(decoded is WsFrame.Close)
        assertNull(decoded.body, "remaining < 2 → body absent")
    }

    /**
     * Close with status code 1000 ("normal closure") and reason "bye".
     *
     * Wire: `0x88 0x05 0x03 0xE8 0x62 0x79 0x65`
     *   header: FIN | Close, len=5
     *   body: status=0x03E8 (1000) BE, reason="bye"
     */
    @Test
    fun closeWithStatusAndReasonRoundTrips() {
        val wire = byteArrayOf(0x88.toByte(), 0x05, 0x03, 0xE8.toByte(), 0x62, 0x79, 0x65)
        val frame =
            WsFrame.Close(
                byte1 = FrameHeaderByte1.pack(true, rsv1 = false, rsv2 = false, rsv3 = false, opcode = 0x8),
                byte2 = WsHeaderByte2.pack(payloadSize = 5L, masked = false),
                body = WsCloseBody(statusCode = 1000u.toUShort(), reason = "bye"),
            )
        roundTripFrameByteExact(frame, wire)
        val decoded = decodeBounded(wire)
        assertTrue(decoded is WsFrame.Close)
        val body = decoded.body
        assertTrue(body != null, "remaining >= 2 → body present")
        assertEquals(1000u.toUShort(), body.statusCode)
        assertEquals("bye", body.reason)
    }

    /**
     * RFC 6455 §8.1 — A receiver of a Text frame MUST Fail the WebSocket Connection
     * if the payload contains malformed UTF-8. The codec layer enforces this via the
     * `String` decoder: feeding 0x80 (a lone continuation byte, never legal as a
     * start byte) into a Text frame must throw at decode.
     *
     * Pinning this down at the fixture level catches a regression in
     * `Charset.UTF8` decoding — the same gate guards the close-frame `reason` in
     * [closeReasonInvalidUtf8Rejected].
     */
    @Test
    fun invalidUtf8InTextPayloadIsRejected() {
        // 0x81 0x01 0x80  →  FIN | Text, len=1, payload = 0x80 (lone continuation byte)
        val wire = byteArrayOf(0x81.toByte(), 0x01, 0x80.toByte())
        val buf = BufferFactory.Default.wrap(wire)
        buf.setLimit(wire.size)
        assertFails("malformed UTF-8 in Text payload must reject") {
            WsFrameCodec.decode(buf, DecodeContext.Empty)
        }
    }

    /**
     * RFC 6455 §5.5.1 — Close payload MUST be 0 bytes OR ≥2 bytes. A 1-byte close
     * payload (impossible to interpret as a 2-byte status code) is a protocol error.
     *
     * Today's codec evaluates `@When("remaining >= 2")` and skips the body when
     * remaining is 0 or 1 — leaving the stray 1 byte unconsumed. The codec returns
     * `body=null` with success, but the buffer position will not equal limit on
     * exit. We assert the underconsumption explicitly so a future fix that
     * promotes this to a hard reject (or that adds an `@When("remaining == 0
     * || remaining >= 2")` shape) flips this test from "documents gap" to
     * "documents fix".
     *
     * Wire: `0x88 0x01 0x03`  →  FIN | Close, len=1, payload = 0x03
     */
    @Test
    fun oneBytePayloadCloseIsUnderConsumedKnownGap() {
        val wire = byteArrayOf(0x88.toByte(), 0x01, 0x03)
        val buf = BufferFactory.Default.wrap(wire)
        buf.setLimit(wire.size)
        val decoded = WsFrameCodec.decode(buf, DecodeContext.Empty)
        assertTrue(decoded is WsFrame.Close)
        assertNull(decoded.body, "remaining == 1 falls through `remaining >= 2`")
        // The 1 stray byte sits past the codec's reach. RFC mandates this be a
        // protocol error; the codec layer can't enforce it without a richer
        // `@When` predicate ("remaining == 0 || remaining >= 2") and an
        // EncodeException on the malformed case. Tracked as a known gap.
        val unread = buf.limit() - buf.position()
        assertEquals(1, unread, "1 byte of the close payload was not consumed by the codec")
    }

    // ──────────────────────── helpers ────────────────────────

    private fun roundTripFrameByteExact(
        frame: WsFrame,
        expectedWire: ByteArray,
    ) {
        val buf = BufferFactory.Default.allocate(expectedWire.size)
        WsFrameCodec.encode(buf, frame, EncodeContext.Empty)
        assertEquals(expectedWire.size, buf.position(), "encoded size")
        buf.resetForRead()
        for (i in expectedWire.indices) {
            assertEquals(expectedWire[i], buf.readByte(), "wire byte $i")
        }
    }

    private fun decodeBounded(wire: ByteArray): WsFrame {
        val buf = BufferFactory.Default.wrap(wire)
        buf.setLimit(wire.size)
        return WsFrameCodec.decode(buf, DecodeContext.Empty)
    }
}
