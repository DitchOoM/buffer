package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttConnectFlags
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImage
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImageCodec
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Phase J.M.5 slice 7 — CONNECT v5. Composes the always-present
 * property bag (slice 2 shape) and the conditional will-properties
 * (slice 5 shape, grammar 1 predicate `connectFlags.willPresent`)
 * with the existing v3-shape will/username/password fields
 * (`@LengthPrefixed @When val: String?` from slice 3.5).
 *
 * Will-message / password are modeled as `String` (per §3.1.3.3 they
 * are technically arbitrary bytes; binary modeling via `@Payload WP /
 * PP` slots needs the multi-generic dispatcher lift — deferred).
 */
class MqttV5ConnectCodecTest {
    @Test
    fun encodesMinimalConnectByteExact() {
        // body = 6 (proto LP) + 1 (level) + 1 (flags) + 2 (ka)
        //      + 1 (propLen=0) + 5 (clientId LP "abc": 2+3) = 16
        val msg =
            MqttV5Packet.Connect(
                remainingLength = 16u,
                protocolName = "MQTT",
                protocolLevel = 0x05u,
                connectFlags = MqttConnectFlags(0x02u), // clean start
                keepAliveSeconds = 60u,
                properties = emptyList(),
                clientId = "abc",
            )
        val buf = encode(msg)
        buf.resetForRead()
        val expected =
            byteArrayOf(
                0x10,
                0x10, // RL = 16
                0x00,
                0x04,
                'M'.code.toByte(),
                'Q'.code.toByte(),
                'T'.code.toByte(),
                'T'.code.toByte(),
                0x05, // protocol level 5
                0x02, // connect flags = clean-start
                0x00,
                0x3C, // keepalive = 60
                0x00, // properties length = 0
                0x00,
                0x03,
                'a'.code.toByte(),
                'b'.code.toByte(),
                'c'.code.toByte(),
            )
        assertContentEquals(expected, buf.readByteArray(buf.remaining()))
    }

    @Test
    fun roundTripsConnectWithProperties() {
        val original =
            MqttV5Packet.Connect(
                // body = 6 + 1 + 1 + 2 + 1 (propLen=5) + 5 (MessageExpiry)
                //      + 5 (clientId LP "abc": 2+3) = 21
                remainingLength = 21u,
                protocolName = "MQTT",
                protocolLevel = 0x05u,
                connectFlags = MqttConnectFlags(0x02u),
                keepAliveSeconds = 60u,
                properties = listOf(MqttV5Property.MessageExpiryInterval(seconds = 3_600u)),
                clientId = "abc",
            )
        val buf = encode(original)
        buf.resetForRead()
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripsConnectWithWillBlockAndCredentials() {
        // willPresent (0x04), usernamePresent (0x80), passwordPresent (0x40)
        // flags = 0xC4. willQoS=0 implies bits 3-4 = 0.
        val original =
            MqttV5Packet.Connect(
                // body = 6 + 1 + 1 + 2
                //      + 1 (propLen=0) + 5 (clientId LP)
                //      + 1 (willPropLen=0)
                //      + 5 (willTopic LP "w/1": 2+3) + 7 (willMsg LP "hello": 2+5)
                //      + 5 (username LP "abc": 2+3) + 5 (password LP "pwd": 2+3)
                //      = 39
                remainingLength = 39u,
                protocolName = "MQTT",
                protocolLevel = 0x05u,
                connectFlags = MqttConnectFlags(0xC4u),
                keepAliveSeconds = 30u,
                properties = emptyList(),
                clientId = "abc",
                willProperties = emptyList(),
                willTopic = "w/1",
                willMessage = "hello",
                username = "abc",
                password = "pwd",
            )
        val buf = encode(original)
        buf.resetForRead()
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripsConnectWithWillPropertiesPopulated() {
        val original =
            MqttV5Packet.Connect(
                // body = 6 + 1 + 1 + 2
                //      + 1 (propLen=0) + 5 (clientId)
                //      + 1 (willPropLen=5) + 5 (MessageExpiry)
                //      + 5 (willTopic LP "w/1") + 7 (willMsg LP "hello")
                //      = 34
                remainingLength = 34u,
                protocolName = "MQTT",
                protocolLevel = 0x05u,
                connectFlags = MqttConnectFlags(0x04u),
                keepAliveSeconds = 30u,
                properties = emptyList(),
                clientId = "abc",
                willProperties = listOf(MqttV5Property.MessageExpiryInterval(seconds = 600u)),
                willTopic = "w/1",
                willMessage = "hello",
            )
        val buf = encode(original)
        buf.resetForRead()
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun decodesMinimalConnectFromSpecBytes() {
        val wire =
            byteArrayOf(
                0x10,
                0x10,
                0x00,
                0x04,
                'M'.code.toByte(),
                'Q'.code.toByte(),
                'T'.code.toByte(),
                'T'.code.toByte(),
                0x05,
                0x02,
                0x00,
                0x3C,
                0x00,
                0x00,
                0x03,
                'a'.code.toByte(),
                'b'.code.toByte(),
                'c'.code.toByte(),
            )
        val buf =
            BufferFactory.Default
                .allocate(wire.size, ByteOrder.BIG_ENDIAN)
                .also {
                    it.writeBytes(wire)
                    it.resetForRead()
                }
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        val connect = assertIs<MqttV5Packet.Connect>(decoded)
        assertEquals(MqttFixedHeader(0x10u), connect.header)
        assertEquals("MQTT", connect.protocolName)
        assertEquals(0x05u.toUByte(), connect.protocolLevel)
        assertEquals(MqttConnectFlags(0x02u), connect.connectFlags)
        assertEquals(60u.toUShort(), connect.keepAliveSeconds)
        assertEquals(emptyList(), connect.properties)
        assertEquals("abc", connect.clientId)
        assertEquals(null, connect.willProperties)
        assertEquals(null, connect.willTopic)
        assertEquals(null, connect.username)
    }

    @Test
    fun peekFrameSizeForConnectCompletes() {
        val msg =
            MqttV5Packet.Connect(
                remainingLength = 16u,
                protocolName = "MQTT",
                protocolLevel = 0x05u,
                connectFlags = MqttConnectFlags(0x02u),
                keepAliveSeconds = 60u,
                properties = emptyList(),
                clientId = "abc",
            )
        val buf = encode(msg)
        buf.resetForRead()
        val totalBytes = buf.remaining()

        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            stream.append(buf)
            assertEquals(PeekResult.Complete(totalBytes), jpegDispatcher().peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun encode(value: MqttV5Packet<*>) =
        BufferFactory.Default
            .allocate(128, ByteOrder.BIG_ENDIAN)
            .also {
                (jpegDispatcher() as com.ditchoom.buffer.codec.Codec<MqttV5Packet<*>>)
                    .encode(it, value, EncodeContext.Empty)
            }

    private fun jpegDispatcher(): MqttV5PacketCodec<JpegImage> = MqttV5PacketCodec(JpegImageCodec)
}
