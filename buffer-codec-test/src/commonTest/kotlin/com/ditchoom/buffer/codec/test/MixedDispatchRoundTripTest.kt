package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.FixedHeaderByte
import com.ditchoom.buffer.codec.test.protocols.MixedDispatchPacket
import com.ditchoom.buffer.codec.test.protocols.MixedDispatchPacketCodec
import com.ditchoom.buffer.codec.testRoundTrip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies per-variant discriminator handling: [MixedDispatchPacket.MixedConnect] has no
 * FixedHeaderByte field so the dispatcher must emit the literal wire byte; MixedPublish
 * carries the header as a field so it self-encodes, and the dispatcher must NOT also emit
 * a literal (which would corrupt the stream).
 */
class MixedDispatchRoundTripTest {
    @Test
    fun connectWithoutDiscriminatorFieldRoundTrip() {
        val original: MixedDispatchPacket = MixedDispatchPacket.MixedConnect(protocolLevel = 4u, keepAlive = 60u)
        val decoded = MixedDispatchPacketCodec.testRoundTrip(original)
        assertTrue(decoded is MixedDispatchPacket.MixedConnect)
        assertEquals(original, decoded)
    }

    @Test
    fun connectEncodeWritesLiteralWireByte() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        MixedDispatchPacketCodec.encode(buffer, MixedDispatchPacket.MixedConnect(protocolLevel = 4u, keepAlive = 60u))
        assertEquals(0x10.toByte(), buffer[0])
    }

    @Test
    fun publishWithDiscriminatorFieldQos0RoundTrip() {
        val original: MixedDispatchPacket =
            MixedDispatchPacket.MixedPublish(
                header = FixedHeaderByte(0x30u), // type=3, qos=0, no packetId
                topicName = "topic/a",
                packetId = null,
                payload = "hello",
            )
        val decoded = MixedDispatchPacketCodec.testRoundTrip(original)
        assertTrue(decoded is MixedDispatchPacket.MixedPublish)
        assertEquals(original, decoded)
    }

    @Test
    fun publishWithDiscriminatorFieldQos1PreservesFlags() {
        val original: MixedDispatchPacket =
            MixedDispatchPacket.MixedPublish(
                header = FixedHeaderByte(0x3Bu), // type=3, dup=1, qos=1, retain=1
                topicName = "t",
                packetId = 42u,
                payload = "x",
            )
        val decoded = MixedDispatchPacketCodec.testRoundTrip(original)
        assertTrue(decoded is MixedDispatchPacket.MixedPublish)
        assertEquals(original, decoded)
        assertEquals(0x3Bu.toUByte(), decoded.header.raw)
        assertEquals(42u.toUShort(), decoded.packetId)
    }

    @Test
    fun publishEncodeDoesNotDoubleWriteHeader() {
        // The dispatcher MUST skip its literal wire write because MixedPublish self-encodes.
        // If it double-wrote, the buffer would start with two header bytes and decode would
        // interpret the second as the topic-length prefix, corrupting the round-trip.
        val buffer = BufferFactory.Default.allocate(32, ByteOrder.BIG_ENDIAN)
        val packet =
            MixedDispatchPacket.MixedPublish(
                header = FixedHeaderByte(0x30u),
                topicName = "t",
                packetId = null,
                payload = "x",
            )
        MixedDispatchPacketCodec.encode(buffer, packet)
        val written = buffer.position()
        // Expected bytes: header(1) + topicLen(2) + "t"(1) + payload(1) = 5
        assertEquals(5, written)
        assertEquals(0x30.toByte(), buffer[0])
    }
}
