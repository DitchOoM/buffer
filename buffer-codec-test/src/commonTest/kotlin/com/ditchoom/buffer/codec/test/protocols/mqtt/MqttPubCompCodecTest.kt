package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
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

/**
 * MQTT v3.1.1 §3.7 PUBCOMP packet.
 * Fixed-shape 4-byte ack: header `0x70` + remainingLength=2 +
 * packetIdentifier (UShort BE). Drives `MqttPacketPubCompCodec`.
 */
class MqttPubCompCodecTest {
    @Test
    fun encodesByteExact() {
        val msg =
            MqttPacket.PubComp(
                header = MqttFixedHeader(0x70u),
                packetIdentifier = 0x1234u,
            )
        val expected = byteArrayOf(0x70, 0x02, 0x12, 0x34)
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun decodesFromSpecBytes() {
        val wire = byteArrayOf(0x70, 0x02, 0x12, 0x34)
        val buf = bigEndianBufferOf(wire)
        val decoded = MqttPacketPubCompCodec.decode(buf, DecodeContext.Empty)
        assertEquals(MqttFixedHeader(0x70u), decoded.header)
        assertEquals(0x1234u.toUShort(), decoded.packetIdentifier)
    }

    @Test
    fun decodeRespectsRemainingLengthBoundEvenWithTrailingBytes() {
        val wire =
            byteArrayOf(
                0x70,
                0x02,
                0x00,
                0x01,
                0xC0.toByte(),
                0x00,
                0xDE.toByte(),
                0xAD.toByte(),
            )
        val buf = bigEndianBufferOf(wire)
        MqttPacketPubCompCodec.decode(buf, DecodeContext.Empty)
        assertEquals(4, buf.position(), "decode advanced exactly through PUBCOMP")
        assertEquals(4, buf.remaining(), "trailing 4 bytes left in buffer for next packet")
    }

    @Test
    fun decodeRestoresBufferLimitAfterCompletion() {
        val buf = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0x70)
        buf.writeByte(0x02)
        buf.writeShort(0x0001.toShort())
        buf.resetForRead()
        val originalLimit = buf.limit()
        MqttPacketPubCompCodec.decode(buf, DecodeContext.Empty)
        assertEquals(originalLimit, buf.limit(), "decode restored the outer limit")
    }

    @Test
    fun roundTripsSpecExample() {
        val original =
            MqttPacket.PubComp(
                header = MqttFixedHeader(0x70u),
                packetIdentifier = 0xCAFEu,
            )
        val buf = encode(original)
        assertEquals(original, MqttPacketPubCompCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun decodeRejectsMalformedVarInt() {
        val wire =
            byteArrayOf(
                0x70,
                0x80.toByte(),
                0x80.toByte(),
                0x80.toByte(),
                0x80.toByte(),
                0x80.toByte(),
            )
        val buf = bigEndianBufferOf(wire)
        val ex =
            assertFailsWith<DecodeException> {
                MqttPacketPubCompCodec.decode(buf, DecodeContext.Empty)
            }
        assertEquals("MqttRemainingLength", ex.fieldPath)
    }

    @Test
    fun peekFrameSizeWalksDripFed() {
        val pool = BufferPool()
        val original = MqttPacket.PubComp(packetIdentifier = 0x0042u)
        val encoded = encode(original)
        val totalBytes = encoded.remaining()
        assertEquals(4, totalBytes)

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            for (i in 0 until totalBytes - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(encoded.readByte())
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    MqttPacketPubCompCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), MqttPacketPubCompCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun encodeAndAssertBytes(
        msg: MqttPacket.PubComp,
        expected: ByteArray,
    ) {
        val buf = encode(msg)
        assertEquals(expected.size, buf.remaining(), "encoded byte count matches MQTT-3.1.1 §3.7 layout")
        val actual = buf.readByteArray(expected.size)
        assertContentEquals(expected, actual, "encoded bytes match MQTT-3.1.1 §3.7")
    }

    private fun bigEndianBufferOf(wire: ByteArray) =
        BufferFactory.Default
            .allocate(wire.size, ByteOrder.BIG_ENDIAN)
            .also { it.writeBytes(wire) }
            .also { it.resetForRead() }

    private fun encode(value: MqttPacket.PubComp): ReadBuffer =
        MqttPacketPubCompCodec.encode(value, EncodeContext.Empty, BufferFactory.Default)
}
