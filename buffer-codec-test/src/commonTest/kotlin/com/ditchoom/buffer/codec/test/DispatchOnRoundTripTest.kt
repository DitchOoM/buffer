package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.test.protocols.DispatchOnPacket
import com.ditchoom.buffer.codec.test.protocols.DispatchOnPacketCodec
import com.ditchoom.buffer.codec.test.protocols.DispatchOnPacketTypeConnAckCodec
import com.ditchoom.buffer.codec.test.protocols.DispatchOnPacketTypeConnectCodec
import com.ditchoom.buffer.codec.test.protocols.DispatchOnPacketTypePubAckCodec
import com.ditchoom.buffer.codec.test.protocols.FixedHeaderByte
import com.ditchoom.buffer.codec.test.protocols.FixedHeaderByteCodec
import com.ditchoom.buffer.codec.testRoundTrip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for @DispatchOn/@DispatchValue — MQTT-style bit-packed header dispatch.
 *
 * Validates that the KSP processor correctly generates decode that reads
 * the discriminator via its codec, extracts the @DispatchValue property,
 * and dispatches to the correct sub-codec.
 */
class DispatchOnRoundTripTest {
    // ========== FixedHeaderByte value class ==========

    @Test
    fun fixedHeaderByteExtractsPacketType() {
        val header = FixedHeaderByte(0x10u) // type=1, flags=0
        assertEquals(1, header.packetType)
        assertEquals(0, header.flags)
    }

    @Test
    fun fixedHeaderByteExtractsFlags() {
        val header = FixedHeaderByte(0x62u) // type=6, flags=2
        assertEquals(6, header.packetType)
        assertEquals(2, header.flags)
    }

    @Test
    fun fixedHeaderByteCodecRoundTrip() {
        val original = FixedHeaderByte(0x30u)
        val decoded = FixedHeaderByteCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    // ========== @DispatchOn decode from MQTT-spec bytes ==========

    @Test
    fun decodesTypeConnectFromSpecBytes() {
        // MQTT CONNECT: header byte 0x10 (type=1, flags=0)
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeByte(0x10.toByte()) // fixed header: type=1
        buffer.writeByte(4.toByte()) // protocolLevel
        buffer.writeShort(60.toShort()) // keepAlive
        buffer.resetForRead()

        val decoded = DispatchOnPacketCodec.decode(buffer)
        assertTrue(decoded is DispatchOnPacket.TypeConnect)
        assertEquals(4u.toUByte(), decoded.protocolLevel)
        assertEquals(60u.toUShort(), decoded.keepAlive)
    }

    @Test
    fun decodesTypeConnAckFromSpecBytes() {
        // MQTT CONNACK: header byte 0x20 (type=2, flags=0)
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeByte(0x20.toByte()) // fixed header: type=2
        buffer.writeByte(1.toByte()) // sessionPresent
        buffer.writeByte(0.toByte()) // returnCode
        buffer.resetForRead()

        val decoded = DispatchOnPacketCodec.decode(buffer)
        assertTrue(decoded is DispatchOnPacket.TypeConnAck)
        assertEquals(1u.toUByte(), decoded.sessionPresent)
        assertEquals(0u.toUByte(), decoded.returnCode)
    }

    @Test
    fun decodesTypePubAckFromSpecBytes() {
        // MQTT PUBACK: header byte 0x40 (type=4, flags=0)
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeByte(0x40.toByte()) // fixed header: type=4
        buffer.writeShort(42.toShort()) // packetId
        buffer.resetForRead()

        val decoded = DispatchOnPacketCodec.decode(buffer)
        assertTrue(decoded is DispatchOnPacket.TypePubAck)
        assertEquals(42u.toUShort(), decoded.packetId)
    }

    @Test
    fun dispatchIgnoresLowerNibbleFlags() {
        // Header byte 0x43 = type 4, flags 3 — should still dispatch as TypePubAck
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeByte(0x43.toByte()) // type=4, flags=0x03
        buffer.writeShort(99.toShort())
        buffer.resetForRead()

        val decoded = DispatchOnPacketCodec.decode(buffer)
        assertTrue(decoded is DispatchOnPacket.TypePubAck)
        assertEquals(99u.toUShort(), decoded.packetId)
    }

    @Test
    fun dispatchUnknownTypeThrows() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeByte(0xF0.toByte()) // type=15, not registered
        buffer.resetForRead()

        assertFailsWith<IllegalArgumentException> {
            DispatchOnPacketCodec.decode(buffer)
        }
    }

    // ========== Full round-trip through dispatch (encode writes wire byte) ==========

    @Test
    fun connectDispatchRoundTrip() {
        val original: DispatchOnPacket = DispatchOnPacket.TypeConnect(4u, 60u)
        val decoded = DispatchOnPacketCodec.testRoundTrip(original)
        assertTrue(decoded is DispatchOnPacket.TypeConnect)
        assertEquals(original, decoded)
    }

    @Test
    fun connAckDispatchRoundTrip() {
        val original: DispatchOnPacket = DispatchOnPacket.TypeConnAck(1u, 0u)
        val decoded = DispatchOnPacketCodec.testRoundTrip(original)
        assertTrue(decoded is DispatchOnPacket.TypeConnAck)
        assertEquals(original, decoded)
    }

    @Test
    fun pubAckDispatchRoundTrip() {
        val original: DispatchOnPacket = DispatchOnPacket.TypePubAck(1000u)
        val decoded = DispatchOnPacketCodec.testRoundTrip(original)
        assertTrue(decoded is DispatchOnPacket.TypePubAck)
        assertEquals(original, decoded)
    }

    @Test
    fun encodeWritesCorrectWireByte() {
        // Verify encode writes 0x10 (wire), not 0x01 (value)
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        DispatchOnPacketCodec.encode(buffer, DispatchOnPacket.TypeConnect(4u, 60u))
        assertEquals(0x10.toByte(), buffer[0]) // wire byte, not extracted value
    }

    // ========== Sub-codec round-trips (no dispatch) ==========

    @Test
    fun typeConnectSubCodecRoundTrip() {
        val original = DispatchOnPacket.TypeConnect(4u, 60u)
        val decoded = DispatchOnPacketTypeConnectCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun typeConnAckSubCodecRoundTrip() {
        val original = DispatchOnPacket.TypeConnAck(0u, 0u)
        val decoded = DispatchOnPacketTypeConnAckCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun typePubAckSubCodecRoundTrip() {
        val original = DispatchOnPacket.TypePubAck(1000u)
        val decoded = DispatchOnPacketTypePubAckCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    // ========== Context: discriminator forwarded via DiscriminatorKey ==========

    @Test
    fun discriminatorForwardedViaContext() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeByte(0x43.toByte()) // type=4, flags=3
        buffer.writeShort(7.toShort())
        buffer.resetForRead()

        val decoded = DispatchOnPacketCodec.decode(buffer, DecodeContext.Empty)
        assertTrue(decoded is DispatchOnPacket.TypePubAck)
        assertEquals(7u.toUShort(), decoded.packetId)
    }
}
