package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.ControlPacketV4
import com.ditchoom.buffer.codec.test.protocols.ControlPacketV4Codec
import com.ditchoom.buffer.codec.test.protocols.MqttFixedHeader
import com.ditchoom.buffer.codec.test.protocols.V4ConnAckFlags
import com.ditchoom.buffer.codec.test.protocols.V4ConnectFlags
import com.ditchoom.buffer.codec.test.protocols.V4ConnectReturnCode
import com.ditchoom.buffer.codec.test.protocols.V4SubAckReturnCode
import com.ditchoom.buffer.codec.test.protocols.V4Subscription
import com.ditchoom.buffer.codec.test.protocols.V4SubscriptionOptions
import com.ditchoom.buffer.codec.test.protocols.V4TopicFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * End-to-end round-trip coverage for the full MQTT v3.1.1 control-packet sealed tree.
 *
 * Every variant goes through the dispatch codec to verify:
 * - The fixed-header byte (top nibble = type, low nibble = flags) reads back correctly.
 * - PUBLISH self-encodes its full header (preserving dup/qos/retain) — the dispatcher does
 *   NOT also write a literal byte for that variant.
 * - Reserved-flag packets (PUBREL 0x62, SUBSCRIBE 0x82, UNSUBSCRIBE 0xA2) preserve their
 *   low nibble through the dispatcher's literal `wire = …` path.
 * - Singletons (PingReq, PingResp, Disconnect, Reserved) decode back to the same instance.
 */
class ControlPacketV4RoundTripTest {
    private fun roundTrip(packet: ControlPacketV4): ControlPacketV4 {
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        ControlPacketV4Codec.encode<String>(
            buffer,
            packet,
            encodePublishPayload = { buf, p -> buf.writeString(p) },
        )
        buffer.resetForRead()
        return ControlPacketV4Codec.decode<String>(
            buffer,
            decodePublishPayload = { reader -> reader.readString(reader.remaining()) },
        )
    }

    @Test
    fun reservedRoundTrip() {
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        ControlPacketV4Codec.encode<String>(
            buffer,
            ControlPacketV4.Reserved,
            encodePublishPayload = { _, _ -> },
        )
        assertEquals(
            buffer.position(),
            ControlPacketV4Codec.wireSize(ControlPacketV4.Reserved),
            "wireSize must match encoded byte count",
        )
        val decoded = roundTrip(ControlPacketV4.Reserved)
        assertSame(ControlPacketV4.Reserved, decoded)
    }

    @Test
    fun connectRoundTripMinimal() {
        val original =
            ControlPacketV4.Connect(
                protocolName = "MQTT",
                protocolLevel = 4u,
                flags = V4ConnectFlags(0b0000_0010u), // cleanSession only
                keepAliveSeconds = 60u,
                clientId = "client-1",
            )
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun connectRoundTripWithCredentialsAndWill() {
        val original =
            ControlPacketV4.Connect(
                protocolName = "MQTT",
                protocolLevel = 4u,
                // willFlag + willQos=1 + willRetain + username + password + cleanSession
                flags = V4ConnectFlags(0b1110_1110u),
                keepAliveSeconds = 30u,
                clientId = "device-7",
                willTopic = "lwt/topic",
                willPayload = "offline",
                userName = "alice",
                password = "secret",
            )
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun connAckRoundTrip() {
        val original =
            ControlPacketV4.ConnAck(
                flags = V4ConnAckFlags(0b0000_0001u),
                returnCode = V4ConnectReturnCode.ACCEPTED,
            )
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun publishQos0RoundTripPreservesRetain() {
        val original =
            ControlPacketV4.Publish(
                header = MqttFixedHeader(0x31u), // type=3, retain=1, qos=0
                topicName = "sensors/temp",
                packetIdentifier = null,
                payload = "23.4",
            )
        val decoded = roundTrip(original)
        assertTrue(decoded is ControlPacketV4.Publish<*>)
        assertEquals(original, decoded)
        assertEquals(0x31u.toUByte(), decoded.header.raw)
        assertEquals(true, decoded.header.publishRetain)
    }

    @Test
    fun publishQos1RoundTripPreservesDupAndPacketId() {
        val original =
            ControlPacketV4.Publish(
                header = MqttFixedHeader(0x3Au), // type=3, dup=1, qos=1, retain=0
                topicName = "x",
                packetIdentifier = 42u,
                payload = "msg",
            )
        val decoded = roundTrip(original)
        assertTrue(decoded is ControlPacketV4.Publish<*>)
        assertEquals(original, decoded)
        assertEquals(true, decoded.header.publishDup)
        assertEquals(1, decoded.header.publishQos)
        assertEquals(42u.toUShort(), decoded.packetIdentifier)
    }

    @Test
    fun pubAckRoundTrip() {
        val original = ControlPacketV4.PubAck(packetIdentifier = 1234u)
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun pubRecPubRelPubCompChain() {
        val rec = ControlPacketV4.PubRec(packetIdentifier = 7u)
        val rel = ControlPacketV4.PubRel(packetIdentifier = 7u)
        val comp = ControlPacketV4.PubComp(packetIdentifier = 7u)
        assertEquals(rec, roundTrip(rec))
        assertEquals(rel, roundTrip(rel))
        assertEquals(comp, roundTrip(comp))
    }

    @Test
    fun pubRelPreservesReservedLowNibble() {
        val buffer = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        ControlPacketV4Codec.encode<String>(
            buffer,
            ControlPacketV4.PubRel(packetIdentifier = 99u),
            encodePublishPayload = { _, _ -> },
        )
        // First byte must be 0x62 — top nibble 6 = PUBREL, low nibble 2 = MQTT-mandated reserved bits.
        assertEquals(0x62.toByte(), buffer[0])
    }

    @Test
    fun subscribeRoundTrip() {
        val original =
            ControlPacketV4.Subscribe(
                packetIdentifier = 5u,
                subscriptions =
                    listOf(
                        V4Subscription("a/+", V4SubscriptionOptions(0u)),
                        V4Subscription("b/#", V4SubscriptionOptions(2u)),
                    ),
            )
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun subAckRoundTripWithMixedReturnCodes() {
        val original =
            ControlPacketV4.SubAck(
                packetIdentifier = 5u,
                returnCodes =
                    listOf(
                        V4SubAckReturnCode.MAX_QOS_0,
                        V4SubAckReturnCode.MAX_QOS_2,
                        V4SubAckReturnCode.FAILURE,
                    ),
            )
        val decoded = roundTrip(original)
        assertTrue(decoded is ControlPacketV4.SubAck)
        // Order must be preserved — SUBACK return codes are positionally matched to SUBSCRIBE filters.
        assertEquals(original.returnCodes, decoded.returnCodes)
    }

    @Test
    fun unsubscribeRoundTrip() {
        val original =
            ControlPacketV4.Unsubscribe(
                packetIdentifier = 8u,
                topicFilters = listOf(V4TopicFilter("a"), V4TopicFilter("b/c")),
            )
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun unsubAckRoundTrip() {
        val original = ControlPacketV4.UnsubAck(packetIdentifier = 8u)
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun pingReqRoundTrip() {
        val original = ControlPacketV4.PingReq()
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun pingRespRoundTrip() {
        val original = ControlPacketV4.PingResp()
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun disconnectRoundTrip() {
        val original = ControlPacketV4.Disconnect()
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun encodeWritesExactWireBytesForPubAck() {
        val buffer = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        ControlPacketV4Codec.encode<String>(
            buffer,
            ControlPacketV4.PubAck(packetIdentifier = 0x1234u),
            encodePublishPayload = { _, _ -> },
        )
        assertEquals(3, buffer.position())
        assertEquals(0x40.toByte(), buffer[0])
        assertEquals(0x12.toByte(), buffer[1])
        assertEquals(0x34.toByte(), buffer[2])
    }
}
