package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.ControlPacketV5
import com.ditchoom.buffer.codec.test.protocols.ControlPacketV5Codec
import com.ditchoom.buffer.codec.test.protocols.MqttFixedHeader
import com.ditchoom.buffer.codec.test.protocols.V5ReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip coverage for the MQTT v5 sealed tree.
 *
 * Validates the v5-specific patterns:
 * - Cascading `@WhenRemaining(1)` for optional reason code + property bag.
 *   Wire-shape: PUBACK with packetId only (2 bytes) → reasonCode = null, properties = null.
 *   PUBACK with packetId + reasonCode (3 bytes) → properties = null.
 *   PUBACK with packetId + reasonCode + properties → all present.
 * - Reserved-flag preservation for PUBREL (0x62) — same as v4.
 * - PUBLISH self-encodes its full header byte (decision #2).
 * - DISCONNECT and AUTH have no required fields — both trailing fields can be absent.
 * - Singletons (PingReq / PingResp) round-trip to the same instance.
 */
class ControlPacketV5RoundTripTest {
    private fun roundTrip(packet: ControlPacketV5): ControlPacketV5 {
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        ControlPacketV5Codec.encode<String>(
            buffer,
            packet,
            encodePublishPayload = { buf, p -> buf.writeString(p) },
        )
        buffer.resetForRead()
        return ControlPacketV5Codec.decode<String>(
            buffer,
            decodePublishPayload = { reader -> reader.readString(reader.remaining()) },
        )
    }

    @Test
    fun pubAckMinimalRoundTrip() {
        val original = ControlPacketV5.PubAck(packetIdentifier = 7u)
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        ControlPacketV5Codec.encode<String>(
            buffer,
            original,
            encodePublishPayload = { _, _ -> },
        )
        assertEquals(
            buffer.position(),
            ControlPacketV5Codec.wireSize(original, EncodeContext.Empty),
            "wireSize must match encoded byte count",
        )
        val decoded = roundTrip(original)
        assertEquals(original, decoded)
        assertTrue(decoded is ControlPacketV5.PubAck)
        assertNull(decoded.reasonCode)
        assertNull(decoded.properties)
    }

    @Test
    fun pubAckWithReasonCodeOnlyRoundTrip() {
        val original =
            ControlPacketV5.PubAck(
                packetIdentifier = 7u,
                reasonCode = V5ReasonCode.NO_MATCHING_SUBSCRIBERS,
            )
        val decoded = roundTrip(original)
        assertTrue(decoded is ControlPacketV5.PubAck)
        assertEquals(original, decoded)
        assertEquals(V5ReasonCode.NO_MATCHING_SUBSCRIBERS, decoded.reasonCode)
        assertNull(decoded.properties)
    }

    @Test
    fun pubAckWithReasonCodeAndPropertiesRoundTrip() {
        val original =
            ControlPacketV5.PubAck(
                packetIdentifier = 7u,
                reasonCode = V5ReasonCode.NOT_AUTHORIZED,
                properties = mapOf(0x1F to 42, 0x21 to 99),
            )
        val decoded = roundTrip(original)
        assertTrue(decoded is ControlPacketV5.PubAck)
        assertEquals(original.packetIdentifier, decoded.packetIdentifier)
        assertEquals(original.reasonCode, decoded.reasonCode)
        assertEquals(original.properties, decoded.properties)
    }

    @Test
    fun pubAckWireSizeShrinksWithoutTrailers() {
        // No trailers: 0x40 (header) + 2 bytes packetId = 3 total.
        val buffer = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        ControlPacketV5Codec.encode<String>(
            buffer,
            ControlPacketV5.PubAck(packetIdentifier = 1u),
            encodePublishPayload = { _, _ -> },
        )
        assertEquals(3, buffer.position())
    }

    @Test
    fun pubRelPreservesReservedLowNibble() {
        val buffer = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        ControlPacketV5Codec.encode<String>(
            buffer,
            ControlPacketV5.PubRel(packetIdentifier = 2u),
            encodePublishPayload = { _, _ -> },
        )
        // First byte 0x62: type 6 = PUBREL, low nibble 2 = reserved bits
        assertEquals(0x62.toByte(), buffer[0])
    }

    @Test
    fun disconnectMinimalRoundTrip() {
        val decoded = roundTrip(ControlPacketV5.Disconnect())
        assertTrue(decoded is ControlPacketV5.Disconnect)
        assertNull(decoded.reasonCode)
        assertNull(decoded.properties)
    }

    @Test
    fun disconnectWithReasonRoundTrip() {
        val original =
            ControlPacketV5.Disconnect(
                reasonCode = V5ReasonCode.NORMAL_DISCONNECTION,
                properties = mapOf(0x1F to 1),
            )
        val decoded = roundTrip(original)
        assertTrue(decoded is ControlPacketV5.Disconnect)
        assertEquals(original.reasonCode, decoded.reasonCode)
        assertEquals(original.properties, decoded.properties)
    }

    @Test
    fun authRoundTrip() {
        val original =
            ControlPacketV5.Auth(
                reasonCode = V5ReasonCode.CONTINUE_AUTHENTICATION,
                properties = mapOf(0x15 to 7),
            )
        val decoded = roundTrip(original)
        assertTrue(decoded is ControlPacketV5.Auth)
        assertEquals(original.reasonCode, decoded.reasonCode)
        assertEquals(original.properties, decoded.properties)
    }

    @Test
    fun authMinimalRoundTrip() {
        val decoded = roundTrip(ControlPacketV5.Auth())
        assertTrue(decoded is ControlPacketV5.Auth)
        assertNull(decoded.reasonCode)
        assertNull(decoded.properties)
    }

    @Test
    fun pingReqRoundTripIsSingleton() {
        assertEquals(ControlPacketV5.PingReq(), roundTrip(ControlPacketV5.PingReq()))
    }

    @Test
    fun pingRespRoundTripIsSingleton() {
        assertEquals(ControlPacketV5.PingResp(), roundTrip(ControlPacketV5.PingResp()))
    }

    @Test
    fun publishV5WithPropertyBagRoundTrip() {
        val original =
            ControlPacketV5.Publish(
                header = MqttFixedHeader(0x32u), // type=3, qos=1
                topicName = "v5/topic",
                packetIdentifier = 5u,
                properties = mapOf(0x01 to 1, 0x02 to 60),
                payload = "v5-payload",
            )
        val decoded = roundTrip(original)
        assertTrue(decoded is ControlPacketV5.Publish<*>)
        assertEquals(original, decoded)
        assertEquals(0x32u.toUByte(), decoded.header.raw)
    }
}
