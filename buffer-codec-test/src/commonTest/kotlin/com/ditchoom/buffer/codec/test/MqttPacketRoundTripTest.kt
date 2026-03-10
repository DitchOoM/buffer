package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.codec.test.protocols.ConnAckFlags
import com.ditchoom.buffer.codec.test.protocols.ConnectReturnCode
import com.ditchoom.buffer.codec.test.protocols.KeepAlive
import com.ditchoom.buffer.codec.test.protocols.MqttConnectFlags
import com.ditchoom.buffer.codec.test.protocols.MqttPacket
import com.ditchoom.buffer.codec.test.protocols.MqttPacketCodec
import com.ditchoom.buffer.codec.test.protocols.MqttPacketConnAck
import com.ditchoom.buffer.codec.test.protocols.MqttPacketConnAckCodec
import com.ditchoom.buffer.codec.test.protocols.MqttPacketConnect
import com.ditchoom.buffer.codec.test.protocols.MqttPacketConnectCodec
import com.ditchoom.buffer.codec.test.protocols.MqttPacketPubAck
import com.ditchoom.buffer.codec.test.protocols.MqttPacketPubAckCodec
import com.ditchoom.buffer.codec.test.protocols.MqttPacketPubComp
import com.ditchoom.buffer.codec.test.protocols.MqttPacketPubCompCodec
import com.ditchoom.buffer.codec.test.protocols.MqttPacketPubRec
import com.ditchoom.buffer.codec.test.protocols.MqttPacketPubRecCodec
import com.ditchoom.buffer.codec.test.protocols.MqttPacketPubRel
import com.ditchoom.buffer.codec.test.protocols.MqttPacketPubRelCodec
import com.ditchoom.buffer.codec.test.protocols.MqttPublish
import com.ditchoom.buffer.codec.test.protocols.MqttPublishCodec
import com.ditchoom.buffer.codec.test.protocols.MqttSubAckSingle
import com.ditchoom.buffer.codec.test.protocols.MqttSubAckSingleCodec
import com.ditchoom.buffer.codec.test.protocols.MqttSubscribeSingle
import com.ditchoom.buffer.codec.test.protocols.MqttSubscribeSingleCodec
import com.ditchoom.buffer.codec.test.protocols.MqttUnsubAck
import com.ditchoom.buffer.codec.test.protocols.MqttUnsubAckCodec
import com.ditchoom.buffer.codec.test.protocols.MqttUnsubscribeSingle
import com.ditchoom.buffer.codec.test.protocols.MqttUnsubscribeSingleCodec
import com.ditchoom.buffer.codec.test.protocols.PacketId
import com.ditchoom.buffer.codec.test.protocols.ProtocolLevel
import com.ditchoom.buffer.codec.test.protocols.PublishFlags
import com.ditchoom.buffer.codec.test.protocols.QosLevel
import com.ditchoom.buffer.codec.test.protocols.SubAckReturnCode
import com.ditchoom.buffer.codec.testRoundTrip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MqttPacketRoundTripTest {
    @Test
    fun `connect with all optional fields`() {
        val flags = MqttConnectFlags(0b11000100u) // willFlag, passwordFlag, usernameFlag
        val original =
            MqttPacketConnect(
                protocolName = "MQTT",
                protocolLevel = ProtocolLevel(4u),
                connectFlags = flags,
                keepAlive = KeepAlive(60u),
                clientId = "client1",
                willTopic = "last/will",
                willMessage = "goodbye",
                username = "user",
                password = "pass",
            )
        val buffer = PlatformBuffer.allocate(256)
        MqttPacketConnectCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = MqttPacketConnectCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun `connect minimal`() {
        val original =
            MqttPacketConnect(
                protocolName = "MQTT",
                protocolLevel = ProtocolLevel(4u),
                connectFlags = MqttConnectFlags(0u),
                keepAlive = KeepAlive(30u),
                clientId = "minimal",
            )
        val buffer = PlatformBuffer.allocate(256)
        MqttPacketConnectCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = MqttPacketConnectCodec.decode(buffer)
        assertEquals(original, decoded)
        assertNull(decoded.willTopic)
        assertNull(decoded.willMessage)
        assertNull(decoded.username)
        assertNull(decoded.password)
    }

    @Test
    fun `connect with empty client id`() {
        val original =
            MqttPacketConnect(
                protocolName = "MQTT",
                protocolLevel = ProtocolLevel(4u),
                connectFlags = MqttConnectFlags(0u),
                keepAlive = KeepAlive(0u),
                clientId = "",
            )
        val buffer = PlatformBuffer.allocate(256)
        MqttPacketConnectCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = MqttPacketConnectCodec.decode(buffer)
        assertEquals("", decoded.clientId)
    }

    @Test
    fun `connect flags bit extraction`() {
        val flags = MqttConnectFlags(0b11101110u)
        assertEquals(true, flags.cleanSession) // bit 1
        assertEquals(true, flags.willFlag) // bit 2
        assertEquals(1, flags.willQos) // bits 3-4 = 01
        assertEquals(true, flags.willRetain) // bit 5
        assertEquals(true, flags.passwordFlag) // bit 6
        assertEquals(true, flags.usernameFlag) // bit 7
    }

    @Test
    fun `connack accepted`() {
        val original = MqttPacketConnAck(ConnAckFlags(0u), ConnectReturnCode(0u))
        val decoded =
            MqttPacketConnAckCodec.testRoundTrip(
                original,
                expectedBytes = byteArrayOf(0x00, 0x00),
            )
        assertEquals(original, decoded)
    }

    @Test
    fun `connack rejected with session present`() {
        val original = MqttPacketConnAck(ConnAckFlags(1u), ConnectReturnCode(5u))
        val decoded =
            MqttPacketConnAckCodec.testRoundTrip(
                original,
                expectedBytes = byteArrayOf(0x01, 0x05),
            )
        assertEquals(original, decoded)
        assertTrue(decoded.acknowledgeFlags.sessionPresent)
    }

    @Test
    fun `puback round trip`() {
        val original = MqttPacketPubAck(PacketId(1u))
        val decoded =
            MqttPacketPubAckCodec.testRoundTrip(
                original,
                expectedBytes = byteArrayOf(0x00, 0x01),
            )
        assertEquals(original, decoded)
    }

    @Test
    fun `pubrec round trip`() {
        val original = MqttPacketPubRec(PacketId(100u))
        val decoded = MqttPacketPubRecCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun `pubrel round trip`() {
        val original = MqttPacketPubRel(PacketId(200u))
        val decoded = MqttPacketPubRelCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun `pubcomp round trip`() {
        val original = MqttPacketPubComp(PacketId(300u))
        val decoded = MqttPacketPubCompCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun `packet identifier boundary values`() {
        for (id in listOf(0u.toUShort(), 1u.toUShort(), UShort.MAX_VALUE)) {
            val original = MqttPacketPubAck(PacketId(id))
            val decoded = MqttPacketPubAckCodec.testRoundTrip(original)
            assertEquals(id, decoded.packetId.raw)
        }
    }

    @Test
    fun `sealed dispatch connect`() {
        val original: MqttPacket =
            MqttPacketConnect(
                protocolName = "MQTT",
                protocolLevel = ProtocolLevel(4u),
                connectFlags = MqttConnectFlags(0u),
                keepAlive = KeepAlive(60u),
                clientId = "test",
            )
        val decoded = MqttPacketCodec.testRoundTrip(original)
        assertTrue(decoded is MqttPacketConnect)
        assertEquals(original, decoded)
    }

    @Test
    fun `sealed dispatch connack`() {
        val original: MqttPacket = MqttPacketConnAck(ConnAckFlags(0u), ConnectReturnCode(0u))
        val decoded = MqttPacketCodec.testRoundTrip(original)
        assertTrue(decoded is MqttPacketConnAck)
        assertEquals(original, decoded)
    }

    @Test
    fun `sealed dispatch puback`() {
        val original: MqttPacket = MqttPacketPubAck(PacketId(42u))
        val decoded = MqttPacketCodec.testRoundTrip(original)
        assertTrue(decoded is MqttPacketPubAck)
        assertEquals(42u.toUShort(), decoded.packetId.raw)
    }

    @Test
    fun `sealed dispatch unknown type throws`() {
        val buffer = PlatformBuffer.allocate(16)
        buffer.writeByte(0xFF.toByte())
        buffer.resetForRead()
        assertFailsWith<IllegalArgumentException> {
            MqttPacketCodec.decode(buffer)
        }
    }

    @Test
    fun `publish qos0 no packet id`() {
        val original =
            MqttPublish<String>(
                flags = PublishFlags(0u), // qos=0, no packet id
                topicName = "test/topic",
                payload = "hello",
            )
        val buffer = PlatformBuffer.allocate(256)
        MqttPublishCodec.encode(buffer, original, encodePayload = { buf, s -> buf.writeString(s) })
        buffer.resetForRead()
        val decoded = MqttPublishCodec.decode<String>(buffer, decodePayload = { pr -> pr.readString(pr.remaining()) })
        assertEquals(original, decoded)
        assertNull(decoded.packetId)
    }

    @Test
    fun `publish qos1 with packet id`() {
        val original =
            MqttPublish<String>(
                flags = PublishFlags(0b00000010u), // qos=1
                topicName = "test/topic",
                packetId = PacketId(42u),
                payload = "hello world",
            )
        val buffer = PlatformBuffer.allocate(256)
        MqttPublishCodec.encode(buffer, original, encodePayload = { buf, s -> buf.writeString(s) })
        buffer.resetForRead()
        val decoded = MqttPublishCodec.decode<String>(buffer, decodePayload = { pr -> pr.readString(pr.remaining()) })
        assertEquals(original, decoded)
        assertEquals(PacketId(42u), decoded.packetId)
    }

    @Test
    fun `publish empty payload`() {
        val original =
            MqttPublish<String>(
                flags = PublishFlags(0u),
                topicName = "test",
                payload = "",
            )
        val buffer = PlatformBuffer.allocate(256)
        MqttPublishCodec.encode(buffer, original, encodePayload = { buf, s -> buf.writeString(s) })
        buffer.resetForRead()
        val decoded = MqttPublishCodec.decode<String>(buffer, decodePayload = { pr -> pr.readString(pr.remaining()) })
        assertEquals("", decoded.payload)
    }

    @Test
    fun `subscribe single round trip`() {
        val original =
            MqttSubscribeSingle(
                packetId = PacketId(1u),
                topicFilter = "sensors/+/temperature",
                requestedQos = QosLevel.AT_LEAST_ONCE,
            )
        val decoded = MqttSubscribeSingleCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun `suback single round trip`() {
        val original = MqttSubAckSingle(PacketId(1u), SubAckReturnCode(0u))
        val decoded = MqttSubAckSingleCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun `unsubscribe single round trip`() {
        val original = MqttUnsubscribeSingle(PacketId(2u), "sensors/#")
        val decoded = MqttUnsubscribeSingleCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun `unsuback round trip`() {
        val original = MqttUnsubAck(PacketId(2u))
        val decoded =
            MqttUnsubAckCodec.testRoundTrip(
                original,
                expectedBytes = byteArrayOf(0x00, 0x02),
            )
        assertEquals(original, decoded)
    }

    @Test
    fun `keep alive duration conversion`() {
        val keepAlive = KeepAlive(60u)
        assertEquals(60.seconds, keepAlive.duration)

        val zero = KeepAlive(0u)
        assertEquals(0.seconds, zero.duration)

        val max = KeepAlive(UShort.MAX_VALUE)
        assertEquals(65535.seconds, max.duration)
    }

    @Test
    fun `qos level constants`() {
        assertEquals(0u.toUByte(), QosLevel.AT_MOST_ONCE.raw)
        assertEquals(1u.toUByte(), QosLevel.AT_LEAST_ONCE.raw)
        assertEquals(2u.toUByte(), QosLevel.EXACTLY_ONCE.raw)
    }

    @Test
    fun `protocol level value class`() {
        val level = ProtocolLevel(4u)
        assertEquals(4u.toUByte(), level.raw)
    }
}
