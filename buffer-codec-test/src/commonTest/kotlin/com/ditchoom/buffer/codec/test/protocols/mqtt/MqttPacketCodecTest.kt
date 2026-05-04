package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Stage F slice 6 doctrine vector. Validates the bit-packed
 * `@DispatchOn(MqttFixedHeader::class)` dispatcher: peek-without-
 * consume decode, naturally-encoded variant header byte, dispatcher
 * peek that delegates to variant peek at `baseOffset` (variant
 * accounts for its own header bytes), and the unknown-discriminator
 * `DecodeException` field-path attribution.
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
    fun encodesConnectVariantByteExact() {
        // body = keepalive (2) + clientId LP (2 + 4) = 8 bytes
        val msg =
            MqttPacket.Connect(
                header = MqttFixedHeader(0x10u),
                remainingLength = 8u,
                keepAliveSeconds = 60u,
                clientId = "abcd",
            )
        val expected =
            byteArrayOf(
                0x10, // fixed header (type=1, flags=0)
                0x08, // remaining length = 8 (1-byte var-int)
                0x00, 0x3C, // keep-alive 60
                0x00, 0x04, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 'd'.code.toByte(),
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
                0x08,
                0x00, 0x3C,
                0x00, 0x04, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 'd'.code.toByte(),
            )
        val buf = BufferFactory.Default.allocate(wire.size).also { it.writeBytes(wire) }
        buf.resetForRead()
        val decoded = MqttPacketCodec.decode(buf, DecodeContext.Empty)
        val connect = assertIs<MqttPacket.Connect>(decoded)
        assertEquals(MqttFixedHeader(0x10u), connect.header)
        assertEquals(8u, connect.remainingLength)
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
        val decoded = MqttPacketCodec.decode(buf, DecodeContext.Empty)
        val disconnect = assertIs<MqttPacket.Disconnect>(decoded)
        assertEquals(MqttFixedHeader(0xE0u), disconnect.header)
        assertEquals(0u, disconnect.remainingLength)
    }

    @Test
    fun decodePreservesNonZeroFixedHeaderFlagBitsOnConnect() {
        // Header byte 0x12: type=1 (CONNECT), flags=0x2 — the MQTT spec
        // reserves CONNECT's flag nibble as 0, but the dispatcher must
        // preserve the raw byte rather than overwriting it from the
        // @PacketType annotation default.
        val wire =
            byteArrayOf(
                0x12,
                0x05,
                0x00, 0x3C,
                0x00, 0x01, 'x'.code.toByte(),
            )
        val buf = BufferFactory.Default.allocate(wire.size).also { it.writeBytes(wire) }
        buf.resetForRead()
        val decoded = MqttPacketCodec.decode(buf, DecodeContext.Empty)
        val connect = assertIs<MqttPacket.Connect>(decoded)
        assertEquals(0x12u.toUByte(), connect.header.raw, "raw byte preserved")
        assertEquals(0x2u.toUByte(), connect.header.flags)
    }

    @Test
    fun decodeThrowsOnUnknownDispatchValue() {
        // Header byte 0x50: type=5 (PUBACK in MQTT, not in our sealed set).
        val buf = BufferFactory.Default.allocate(1).also { it.writeByte(0x50.toByte()) }
        buf.resetForRead()
        val ex =
            assertFailsWith<DecodeException> {
                MqttPacketCodec.decode(buf, DecodeContext.Empty)
            }
        assertEquals("MqttPacket.discriminator", ex.fieldPath)
    }

    @Test
    fun roundTripsConnect() {
        // body = 2 (keepalive) + 2 + 8 (clientId "client-1" with LP) = 12
        val original =
            MqttPacket.Connect(
                header = MqttFixedHeader(0x10u),
                remainingLength = 12u,
                keepAliveSeconds = 30u,
                clientId = "client-1",
            )
        val buf = encode(original)
        buf.resetForRead()
        val decoded = MqttPacketCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripsDisconnect() {
        val original = MqttPacket.Disconnect(header = MqttFixedHeader(0xE0u))
        val buf = encode(original)
        buf.resetForRead()
        val decoded = MqttPacketCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun peekFrameSizeNeedsMoreDataWhenEmpty() {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, MqttPacketCodec.peekFrameSize(stream))
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
                MqttPacketCodec.peekFrameSize(stream),
                "header alone — peek still needs the var-int byte",
            )
            val two = BufferFactory.Default.allocate(1)
            two.writeByte(0x00)
            two.resetForRead()
            stream.append(two)
            assertEquals(PeekResult.Complete(2), MqttPacketCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeWalksDripFedConnect() {
        val pool = BufferPool()
        val original =
            MqttPacket.Connect(
                header = MqttFixedHeader(0x10u),
                remainingLength = 7u, // 2 (keepalive) + 2 + 3 (clientId "abc") = 7
                keepAliveSeconds = 60u,
                clientId = "abc",
            )
        val encoded = encode(original)
        encoded.resetForRead()
        val totalBytes = encoded.remaining()

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, MqttPacketCodec.peekFrameSize(stream))

            for (i in 0 until totalBytes - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(encoded.readByte())
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    MqttPacketCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), MqttPacketCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun encodeAndAssertBytes(
        msg: MqttPacket,
        expected: ByteArray,
    ) {
        val buf = encode(msg)
        assertEquals(expected.size, buf.position(), "encoded byte count matches spec layout")
        buf.resetForRead()
        val actual = buf.readByteArray(expected.size)
        assertContentEquals(expected, actual, "encoded bytes match MQTT-3.1.1 §2.2 layout")
    }

    private fun encode(value: MqttPacket) =
        BufferFactory.Default
            .allocate(256)
            .also { MqttPacketCodec.encode(it, value, EncodeContext.Empty) }
}
