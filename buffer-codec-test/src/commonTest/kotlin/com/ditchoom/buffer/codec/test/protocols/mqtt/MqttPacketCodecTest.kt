package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImage
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImageCodec
import com.ditchoom.buffer.codec.test.protocols.payload.PacketId
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * + doctrine vector. Validates
 * the bit-packed `@DispatchOn(MqttFixedHeader::class)` dispatcher,
 * lifted to a generic class `MqttPacketCodec<P : Payload>(payload
 * Codec: Codec<P>)` so the new `Publish<P : Payload>` variant
 *  can route through the typed payload codec.
 *
 * Payload-free variants (`Connect`, `PingReq`, `PingResp`,
 * `Disconnect`) are `: MqttPacket<Nothing>` — covariance makes
 * them assignable to any `MqttPacket<P>` instantiation, so tests
 * for those variants can pick any payload codec.
 */
class MqttPacketCodecTest {
    @Test
    fun fixedHeaderExtractsTypeAndFlags() {
        assertEquals(1, MqttFixedHeader(0x10u).packetType)
        assertEquals(0x0u.toUByte(), MqttFixedHeader(0x10u).flags)

        assertEquals(14, MqttFixedHeader(0xE0u).packetType)
        assertEquals(0x0u.toUByte(), MqttFixedHeader(0xE0u).flags)

        assertEquals(3, MqttFixedHeader(0x3Bu).packetType)
        assertEquals(0xBu.toUByte(), MqttFixedHeader(0x3Bu).flags)
    }

    @Test
    fun fixedHeaderQosGreaterThanZeroFlagsQosBits() {
        // Predicate for `Publish.packetId`. Bits
        // 1 and 2 of `flags` carry QoS per §3.3.1; the property
        // returns true iff either bit is set.
        assertEquals(false, MqttFixedHeader(0x30u).qosGreaterThanZero, "QoS=0 (flags=0x0)")
        assertEquals(true, MqttFixedHeader(0x32u).qosGreaterThanZero, "QoS=1 (flags=0x2)")
        assertEquals(true, MqttFixedHeader(0x34u).qosGreaterThanZero, "QoS=2 (flags=0x4)")
        // DUP bit (0x8) and RETAIN bit (0x1) do not affect the predicate.
        assertEquals(false, MqttFixedHeader(0x39u).qosGreaterThanZero, "DUP+RETAIN, QoS=0")
        assertEquals(true, MqttFixedHeader(0x3Bu).qosGreaterThanZero, "DUP+RETAIN, QoS=1")
    }

    @Test
    fun encodesConnectVariantByteExact() {
        // body = protoName "MQTT" LP (6) + level (1) + flags (1) +
        //        keepalive (2) + clientId LP (2 + 4) = 16 bytes
        val msg =
            MqttPacket.Connect(
                header = MqttFixedHeader(0x10u),
                protocolName = "MQTT",
                protocolLevel = 0x04u,
                connectFlags = MqttConnectFlags(0x02u),
                keepAliveSeconds = 60u,
                clientId = "abcd",
            )
        val expected =
            byteArrayOf(
                0x10, // fixed header (type=1, flags=0)
                0x10, // remaining length = 16 (1-byte var-int)
                0x00,
                0x04,
                'M'.code.toByte(),
                'Q'.code.toByte(),
                'T'.code.toByte(),
                'T'.code.toByte(),
                0x04, // protocol level (v3.1.1)
                0x02, // connect flags = cleanSession only
                0x00,
                0x3C, // keep-alive 60
                0x00,
                0x04,
                'a'.code.toByte(),
                'b'.code.toByte(),
                'c'.code.toByte(),
                'd'.code.toByte(),
            )
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun encodesDisconnectVariantAsTwoBytes() {
        // §3.14 — DISCONNECT is exactly two bytes on the wire: E0 00.
        val msg = MqttPacket.Disconnect(header = MqttFixedHeader(0xE0u))
        val expected = byteArrayOf(0xE0.toByte(), 0x00)
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun decodesConnectFromSpecBytes() {
        val wire =
            byteArrayOf(
                0x10,
                0x10, // remaining length = 16
                0x00,
                0x04,
                'M'.code.toByte(),
                'Q'.code.toByte(),
                'T'.code.toByte(),
                'T'.code.toByte(),
                0x04,
                0x02,
                0x00,
                0x3C,
                0x00,
                0x04,
                'a'.code.toByte(),
                'b'.code.toByte(),
                'c'.code.toByte(),
                'd'.code.toByte(),
            )
        val buf = BufferFactory.Default.allocate(wire.size).also { it.writeBytes(wire) }
        buf.resetForRead()
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        val connect = assertIs<MqttPacket.Connect>(decoded)
        assertEquals(MqttFixedHeader(0x10u), connect.header)
        assertEquals("MQTT", connect.protocolName)
        assertEquals(0x04u.toUByte(), connect.protocolLevel)
        assertEquals(MqttConnectFlags(0x02u), connect.connectFlags)
        assertEquals(60u.toUShort(), connect.keepAliveSeconds)
        assertEquals("abcd", connect.clientId)
    }

    @Test
    fun decodesDisconnectFromSpecBytes() {
        // E0 00 — the canonical MQTT DISCONNECT wire.
        val buf =
            BufferFactory.Default.allocate(2).also {
                it.writeByte(0xE0.toByte())
                it.writeByte(0x00)
            }
        buf.resetForRead()
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        val disconnect = assertIs<MqttPacket.Disconnect>(decoded)
        assertEquals(MqttFixedHeader(0xE0u), disconnect.header)
    }

    @Test
    fun decodePreservesNonZeroFixedHeaderFlagBitsOnConnect() {
        // Header byte 0x12: type=1 (CONNECT), flags=0x2 — the MQTT spec
        // reserves CONNECT's flag nibble as 0, but the dispatcher must
        // preserve the raw byte rather than overwriting it from the
        // @PacketType annotation default.
        // body = 6 (proto LP) + 1 (level) + 1 (flags) + 2 (keepalive) +
        //        3 (clientId "x" LP) = 13 bytes.
        val wire =
            byteArrayOf(
                0x12,
                0x0D, // remaining length = 13
                0x00,
                0x04,
                'M'.code.toByte(),
                'Q'.code.toByte(),
                'T'.code.toByte(),
                'T'.code.toByte(),
                0x04,
                0x02,
                0x00,
                0x3C,
                0x00,
                0x01,
                'x'.code.toByte(),
            )
        val buf = BufferFactory.Default.allocate(wire.size).also { it.writeBytes(wire) }
        buf.resetForRead()
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        val connect = assertIs<MqttPacket.Connect>(decoded)
        assertEquals(0x12u.toUByte(), connect.header.raw, "raw byte preserved")
        assertEquals(0x2u.toUByte(), connect.header.flags)
    }

    @Test
    fun decodeThrowsOnUnknownDispatchValue() {
        // Header byte 0xF0: type=15, reserved-and-forbidden per
        // MQTT v3.1.1 §2.2.1. After,
        // every spec-defined v3.1.1 packet type (1–14) is folded
        // into the dispatcher — type 15 is the only remaining
        // unknown vector that will never gain a sealed variant.
        val buf = BufferFactory.Default.allocate(1).also { it.writeByte(0xF0.toByte()) }
        buf.resetForRead()
        val ex =
            assertFailsWith<DecodeException> {
                jpegDispatcher().decode(buf, DecodeContext.Empty)
            }
        assertEquals("MqttPacket.discriminator", ex.fieldPath)
    }

    @Test
    fun roundTripsConnect() {
        // body = 6 (proto) + 1 (level) + 1 (flags) + 2 (keepalive) +
        //        2 + 8 (clientId "client-1" with LP) = 20
        val original =
            MqttPacket.Connect(
                header = MqttFixedHeader(0x10u),
                protocolName = "MQTT",
                protocolLevel = 0x04u,
                connectFlags = MqttConnectFlags(0x02u),
                keepAliveSeconds = 30u,
                clientId = "client-1",
            )
        val buf = encode(original)
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripsDisconnect() {
        val original = MqttPacket.Disconnect(header = MqttFixedHeader(0xE0u))
        val buf = encode(original)
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun encodesPingReqAsTwoBytes() {
        // §3.12.1 — PINGREQ wire is exactly C0 00.
        val msg = MqttPacket.PingReq()
        encodeAndAssertBytes(msg, byteArrayOf(0xC0.toByte(), 0x00))
    }

    @Test
    fun encodesPingRespAsTwoBytes() {
        // §3.13.1 — PINGRESP wire is exactly D0 00.
        val msg = MqttPacket.PingResp()
        encodeAndAssertBytes(msg, byteArrayOf(0xD0.toByte(), 0x00))
    }

    @Test
    fun decodesPingReqFromSpecBytes() {
        val buf =
            BufferFactory.Default.allocate(2).also {
                it.writeByte(0xC0.toByte())
                it.writeByte(0x00)
            }
        buf.resetForRead()
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        val pingReq = assertIs<MqttPacket.PingReq>(decoded)
        assertEquals(MqttFixedHeader(0xC0u), pingReq.header)
    }

    @Test
    fun decodesPingRespFromSpecBytes() {
        val buf =
            BufferFactory.Default.allocate(2).also {
                it.writeByte(0xD0.toByte())
                it.writeByte(0x00)
            }
        buf.resetForRead()
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        val pingResp = assertIs<MqttPacket.PingResp>(decoded)
        assertEquals(MqttFixedHeader(0xD0u), pingResp.header)
    }

    @Test
    fun roundTripsPingReqAndPingResp() {
        for (msg in listOf(MqttPacket.PingReq(), MqttPacket.PingResp())) {
            val buf = encode(msg)
            val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
            assertEquals(msg, decoded, "round-trip $msg")
        }
    }

    @Test
    fun peekFrameSizeForPingReqCompletesAtTwoBytes() {
        // Same shape as Disconnect — header + var-int RL=0 → 2 bytes total.
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            val two = BufferFactory.Default.allocate(2)
            two.writeByte(0xC0.toByte())
            two.writeByte(0x00)
            two.resetForRead()
            stream.append(two)
            assertEquals(PeekResult.Complete(2), jpegDispatcher().peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeNeedsMoreDataWhenEmpty() {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, jpegDispatcher().peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeCompletesAtTwoBytesForDisconnect() {
        // Disconnect wire is `E0 00`. Peek needs both bytes (header + var-int)
        // to know the total length is 2.
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            val one = BufferFactory.Default.allocate(1)
            one.writeByte(0xE0.toByte())
            one.resetForRead()
            stream.append(one)
            assertEquals(
                PeekResult.NeedsMoreData,
                jpegDispatcher().peekFrameSize(stream),
                "header alone — peek still needs the var-int byte",
            )
            val two = BufferFactory.Default.allocate(1)
            two.writeByte(0x00)
            two.resetForRead()
            stream.append(two)
            assertEquals(PeekResult.Complete(2), jpegDispatcher().peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeWalksDripFedConnect() {
        val pool = BufferPool()
        // body = 6 (proto) + 1 (level) + 1 (flags) + 2 (keepalive) +
        //        2 + 3 (clientId "abc" with LP) = 15
        val original =
            MqttPacket.Connect(
                header = MqttFixedHeader(0x10u),
                protocolName = "MQTT",
                protocolLevel = 0x04u,
                connectFlags = MqttConnectFlags(0x02u),
                keepAliveSeconds = 60u,
                clientId = "abc",
            )
        val encoded = encode(original)
        val totalBytes = encoded.remaining()

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, jpegDispatcher().peekFrameSize(stream))

            for (i in 0 until totalBytes - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(encoded.readByte())
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    jpegDispatcher().peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), jpegDispatcher().peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun encodeAndAssertBytes(
        msg: MqttPacket<*>,
        expected: ByteArray,
    ) {
        val buf = encode(msg)
        assertEquals(expected.size, buf.remaining(), "encoded byte count matches spec layout")
        val actual = buf.readByteArray(expected.size)
        assertContentEquals(expected, actual, "encoded bytes match MQTT-3.1.1 §2.2 layout")
    }

    @Suppress("UNCHECKED_CAST")
    private fun encode(value: MqttPacket<*>): ReadBuffer =
        jpegDispatcher().encode(value as MqttPacket<JpegImage>, EncodeContext.Empty, BufferFactory.Default)

    // For payload-free variants the payload codec choice is irrelevant
    // — the dispatcher never invokes it. Use JpegImageCodec by
    // convention. Publish-variant tests instantiate the dispatcher
    // explicitly per payload type.
    private fun jpegDispatcher(): MqttPacketCodec<JpegImage> = MqttPacketCodec(JpegImageCodec)

    // — - Publish variant via the generic dispatcher — —

    @Test
    fun encodesPublishVariantByteExactAtQos1() {
        // Wire layout: 32 / RL / 00 03 't' '/' '1' / 00 2A / payload bytes
        // body = 2 (topic LP) + 3 (topic) + 2 (packetId) + payload (4 jpeg
        // header + 4 jpeg data) = 15 bytes. RL value = 15 (1-byte var-int).
        // Header byte 0x32 = type=3, flags=0x2 (QoS=1) — packetId is on wire.
        val codec = MqttPacketCodec(JpegImageCodec)
        val msg =
            MqttPacket.Publish<JpegImage>(
                header = MqttFixedHeader(0x32u),
                topic = "t/1",
                packetId = PacketId(0x002Au),
                payload =
                    JpegImage(
                        width = 4u,
                        height = 8u,
                        data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
                    ),
            )
        val buf = codec.encode(msg, EncodeContext.Empty, BufferFactory.Default)
        val actual = buf.readByteArray(buf.remaining())
        val expected =
            byteArrayOf(
                0x32, // fixed header (type=3, QoS=1)
                0x0F, // remaining length = 15 (1-byte var-int)
                0x00,
                0x03,
                't'.code.toByte(),
                '/'.code.toByte(),
                '1'.code.toByte(),
                0x00,
                0x2A, // packet id = 42
                // jpeg payload: width=4 (BE UShort), height=8 (BE UShort), 4 data bytes
                0x00,
                0x04,
                0x00,
                0x08,
                0xDE.toByte(),
                0xAD.toByte(),
                0xBE.toByte(),
                0xEF.toByte(),
            )
        assertContentEquals(expected, actual)
    }

    @Test
    fun encodesPublishVariantByteExactAtQos0OmitsPacketId() {
        // Header byte 0x30 = QoS=0, packetId field
        // is skipped on the wire per §3.3.2.2. body = 2 (topic LP) +
        // 3 (topic) + payload (4 jpeg header + 4 jpeg data) = 13 bytes.
        // Compare to the QoS=1 case which adds 2 bytes for packetId.
        val codec = MqttPacketCodec(JpegImageCodec)
        val msg =
            MqttPacket.Publish<JpegImage>(
                header = MqttFixedHeader(0x30u),
                topic = "t/1",
                packetId = null,
                payload =
                    JpegImage(
                        width = 4u,
                        height = 8u,
                        data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
                    ),
            )
        val buf = codec.encode(msg, EncodeContext.Empty, BufferFactory.Default)
        val actual = buf.readByteArray(buf.remaining())
        val expected =
            byteArrayOf(
                0x30, // fixed header (type=3, QoS=0)
                0x0D, // remaining length = 13 (1-byte var-int)
                0x00,
                0x03,
                't'.code.toByte(),
                '/'.code.toByte(),
                '1'.code.toByte(),
                // no packet id bytes — QoS=0 omits the slot
                0x00,
                0x04,
                0x00,
                0x08,
                0xDE.toByte(),
                0xAD.toByte(),
                0xBE.toByte(),
                0xEF.toByte(),
            )
        assertContentEquals(expected, actual)
    }

    @Test
    fun decodesPublishQos0YieldsNullPacketId() {
        val codec = MqttPacketCodec(JpegImageCodec)
        val wire =
            byteArrayOf(
                0x30,
                0x0D,
                0x00,
                0x03,
                't'.code.toByte(),
                '/'.code.toByte(),
                '1'.code.toByte(),
                0x00,
                0x04,
                0x00,
                0x08,
                0xDE.toByte(),
                0xAD.toByte(),
                0xBE.toByte(),
                0xEF.toByte(),
            )
        val buf = BufferFactory.Default.allocate(wire.size, ByteOrder.BIG_ENDIAN).also { it.writeBytes(wire) }
        buf.resetForRead()
        val decoded = assertIs<MqttPacket.Publish<JpegImage>>(codec.decode(buf, DecodeContext.Empty))
        assertEquals(null, decoded.packetId, "QoS=0 leaves packetId absent on wire and reads back null")
        assertEquals("t/1", decoded.topic)
    }

    @Test
    fun constructThrowsWhenQos1AndPacketIdIsNull() {
        // Closed the row-20 failure mode caller-side:
        // the §2.2.1 cross-bit invariant (header.qosGreaterThanZero ==
        // (packetId != null)) now fires from the data class init-block at
        // construction time, before the codec ever sees the message.
        // Previously this asserted EncodeException at encode time.
        assertFailsWith<IllegalArgumentException> {
            MqttPacket.Publish<JpegImage>(
                header = MqttFixedHeader(0x32u),
                topic = "t/1",
                packetId = null,
                payload = JpegImage(1u, 1u, byteArrayOf(0x00)),
            )
        }
    }

    @Test
    fun roundTripsPublishVariantViaDispatcherAtQos1() {
        // The integration test — Publish<P> routes through
        // the dispatcher's generic class with the supplied payload
        // codec. Confirms @RemainingLength + RemainingBytesPayload
        // composition lifts the carve-out correctly. Run
        // at QoS=1 (header byte 0x32) so the packetId slot is on the
        // wire and round-trips through the dispatcher.
        val codec = MqttPacketCodec(JpegImageCodec)
        val original =
            MqttPacket.Publish<JpegImage>(
                header = MqttFixedHeader(0x32u),
                // body = 2 (topic LP) + 12 (topic) + 2 (pid) + 4+8 (jpeg)
                topic = "sensors/jpeg",
                packetId = PacketId(0x0042u),
                payload = JpegImage(320u, 240u, ByteArray(8) { (it * 5).toByte() }),
            )
        val buf = codec.encode(original, EncodeContext.Empty, BufferFactory.Default)
        val decoded = codec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripsPublishVariantAtQos0WithNullPacketId() {
        // QoS=0 round-trip drops packetId from the
        // wire and reads back as null on the decoded side.
        val codec = MqttPacketCodec(JpegImageCodec)
        val original =
            MqttPacket.Publish<JpegImage>(
                header = MqttFixedHeader(0x30u),
                // body = 2 (topic LP) + 12 (topic) + 4+8 (jpeg) = 26 (no packetId)
                topic = "sensors/jpeg",
                packetId = null,
                payload = JpegImage(320u, 240u, ByteArray(8) { (it * 5).toByte() }),
            )
        val buf = codec.encode(original, EncodeContext.Empty, BufferFactory.Default)
        val decoded = codec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun publishCompleteRestoresOuterLimitFromPartialFlow() {
        // Partial+@RemainingLength composition: append trailing bytes that
        // the publish decode must NOT read. After Partial.complete() runs
        // (inside the var-int-narrowed bound), the outer limit must be
        // restored so the trailing bytes are visible to the next caller.
        val codec = MqttPacketCodec(JpegImageCodec)
        val original =
            MqttPacket.Publish<JpegImage>(
                header = MqttFixedHeader(0x32u),
                // 2 + 1 (topic "t") + 2 (pid) + 4+1 (jpeg)
                topic = "t",
                packetId = PacketId(0x0007u),
                payload = JpegImage(1u, 1u, byteArrayOf(0x42)),
            )
        val encoded = codec.encode(original, EncodeContext.Empty, BufferFactory.Default)
        val publishBytes = encoded.remaining()
        // Materialize publish bytes + trailing bytes the dispatcher must NOT consume.
        val buf = BufferFactory.Default.allocate(publishBytes + 2, ByteOrder.BIG_ENDIAN)
        buf.writeBytes(encoded.readByteArray(publishBytes))
        buf.writeByte(0xCA.toByte())
        buf.writeByte(0xFE.toByte())
        buf.resetForRead()
        val outerLimitBefore = buf.limit()
        val decoded = codec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
        assertEquals(publishBytes, buf.position(), "decode advanced exactly through the publish")
        assertEquals(outerLimitBefore, buf.limit(), "outer limit restored after RL-bounded decode")
    }

    @Test
    fun roundTripsPublishVariantThroughVariantPartial() {
        // Direct Partial flow for the variant codec: the consumer
        // decodes headers via MqttPacketPublishCodec.partial<P>(...), inspects
        // the topic, then completes with the payload codec selected
        // at the call site. Verifies the Partial machinery
        // composes with 's @RemainingLength outer-limit
        // capture.
        val codec = MqttPacketCodec(JpegImageCodec)
        val original =
            MqttPacket.Publish<JpegImage>(
                header = MqttFixedHeader(0x32u),
                // 2 + 3 (topic) + 2 (pid) + 4 + 1 (jpeg data)
                topic = "a/b",
                packetId = PacketId(0x0001u),
                payload = JpegImage(2u, 3u, byteArrayOf(0x55)),
            )
        val buf = codec.encode(original, EncodeContext.Empty, BufferFactory.Default)
        val outerLimitBefore = buf.limit()
        // Skip the discriminator route — exercise Partial directly on
        // the variant codec (the code path that emits the outer-limit
        // capture).
        val partial = MqttPacketPublishCodec.partial<JpegImage>(buf, DecodeContext.Empty)
        assertEquals("a/b", partial.topic)
        val full = partial.complete(JpegImageCodec)
        assertEquals(original, full)
        assertEquals(outerLimitBefore, buf.limit(), "outer limit restored after Partial.complete()")
    }

    @Test
    fun nothingVariantRoundTripsUnderArbitraryPayloadInstantiation() {
        // Connect/PingReq/etc. are : MqttPacket<Nothing>; covariance
        // makes them assignable through any `MqttPacket<P>` dispatcher.
        // Confirm the dispatcher doesn't try to invoke the payload
        // codec when routing payload-free variants.
        val codec = MqttPacketCodec(JpegImageCodec)
        val original =
            MqttPacket.Connect(
                header = MqttFixedHeader(0x10u),
                // body = 6 (proto) + 1 (level) + 1 (flags) + 2 (keepalive) + 6 (clientId LP "abcd") = 16
                protocolName = "MQTT",
                protocolLevel = 0x04u,
                connectFlags = MqttConnectFlags(0x02u),
                keepAliveSeconds = 60u,
                clientId = "abcd",
            )
        val buf = codec.encode(original, EncodeContext.Empty, BufferFactory.Default)
        assertEquals(original, codec.decode(buf, DecodeContext.Empty))
    }
}
