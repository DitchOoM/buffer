package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.test.protocols.ConnectFlags
import com.ditchoom.buffer.codec.test.protocols.MqttConnect
import com.ditchoom.buffer.codec.test.protocols.MqttConnectCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MqttConnectPayloadRoundTripTest {
    @Test
    fun `round trip with will payload and password`() {
        val flags = ConnectFlags(0b11000100u) // willFlag=true, passwordFlag=true, usernameFlag=true
        val original =
            MqttConnect<String, String>(
                protocolName = "MQTT",
                protocolLevel = 4u,
                flags = flags,
                keepAlive = 60u,
                clientId = "client1",
                willTopic = "last/will",
                willPayload = "goodbye",
                username = "user",
                password = "pass",
            )
        val buffer = BufferFactory.Default.allocate(512)
        MqttConnectCodec.encode(
            buffer,
            original,
            encodeWillPayload = { buf, s -> buf.writeString(s) },
            encodePassword = { buf, s -> buf.writeString(s) },
        )
        buffer.resetForRead()
        val decoded =
            MqttConnectCodec.decode<String, String>(
                buffer,
                decodeWillPayload = { pr -> pr.readString(pr.remaining()) },
                decodePassword = { pr -> pr.readString(pr.remaining()) },
            )
        assertEquals(original.protocolName, decoded.protocolName)
        assertEquals(original.protocolLevel, decoded.protocolLevel)
        assertEquals(original.keepAlive, decoded.keepAlive)
        assertEquals(original.clientId, decoded.clientId)
        assertEquals(original.willTopic, decoded.willTopic)
        assertEquals(original.willPayload, decoded.willPayload)
        assertEquals(original.username, decoded.username)
        assertEquals(original.password, decoded.password)
    }

    @Test
    fun `round trip without optional fields`() {
        val flags = ConnectFlags(0u) // no will, no username, no password
        val original =
            MqttConnect<String, String>(
                protocolName = "MQTT",
                protocolLevel = 4u,
                flags = flags,
                keepAlive = 30u,
                clientId = "minimal",
            )
        val buffer = BufferFactory.Default.allocate(256)
        MqttConnectCodec.encode(
            buffer,
            original,
            encodeWillPayload = { buf, s -> buf.writeString(s) },
            encodePassword = { buf, s -> buf.writeString(s) },
        )
        buffer.resetForRead()
        val decoded =
            MqttConnectCodec.decode<String, String>(
                buffer,
                decodeWillPayload = { pr -> pr.readString(pr.remaining()) },
                decodePassword = { pr -> pr.readString(pr.remaining()) },
            )
        assertEquals(original.protocolName, decoded.protocolName)
        assertEquals(original.protocolLevel, decoded.protocolLevel)
        assertEquals(original.keepAlive, decoded.keepAlive)
        assertEquals(original.clientId, decoded.clientId)
        assertNull(decoded.willTopic)
        assertNull(decoded.willPayload)
        assertNull(decoded.username)
        assertNull(decoded.password)
    }

    @Test
    fun `round trip with different payload types`() {
        val flags = ConnectFlags(0b01000100u) // willFlag=true, passwordFlag=true
        val original =
            MqttConnect<Long, Int>(
                protocolName = "MQTT",
                protocolLevel = 5u,
                flags = flags,
                keepAlive = 120u,
                clientId = "typed-client",
                willTopic = "will/topic",
                willPayload = 0x0102030405060708L,
                password = 42,
            )
        val buffer = BufferFactory.Default.allocate(512)
        MqttConnectCodec.encode(
            buffer,
            original,
            encodeWillPayload = { buf, v -> buf.writeLong(v) },
            encodePassword = { buf, v -> buf.writeInt(v) },
        )
        buffer.resetForRead()
        val decoded =
            MqttConnectCodec.decode<Long, Int>(
                buffer,
                decodeWillPayload = { pr -> pr.readLong() },
                decodePassword = { pr -> pr.readInt() },
            )
        assertEquals(original.protocolName, decoded.protocolName)
        assertEquals(original.protocolLevel, decoded.protocolLevel)
        assertEquals(original.keepAlive, decoded.keepAlive)
        assertEquals(original.clientId, decoded.clientId)
        assertEquals(original.willTopic, decoded.willTopic)
        assertEquals(original.willPayload, decoded.willPayload)
        assertNull(decoded.username)
        assertEquals(original.password, decoded.password)
    }
}
