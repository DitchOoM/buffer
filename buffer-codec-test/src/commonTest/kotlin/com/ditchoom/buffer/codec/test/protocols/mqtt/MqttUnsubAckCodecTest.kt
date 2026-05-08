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
 * MQTT v3.1.1 §3.11 UNSUBACK
 * packet. Fixed-shape 4-byte ack: header `0xB0` + remainingLength=2
 * + packetIdentifier (UShort BE). Unlike SUBACK (which carries a
 * list of return codes), UNSUBACK in v3.1.1 has no body.
 */
class MqttUnsubAckCodecTest {
    @Test
    fun encodesByteExact() {
        val msg =
            MqttPacket.UnsubAck(
                header = MqttFixedHeader(0xB0u),
                packetIdentifier = 0x1234u,
            )
        val expected = byteArrayOf(0xB0.toByte(), 0x02, 0x12, 0x34)
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun decodesFromSpecBytes() {
        val wire = byteArrayOf(0xB0.toByte(), 0x02, 0x12, 0x34)
        val buf = bigEndianBufferOf(wire)
        val decoded = UnsubAckCodec.decode(buf, DecodeContext.Empty)
        assertEquals(MqttFixedHeader(0xB0u), decoded.header)
        assertEquals(0x1234u.toUShort(), decoded.packetIdentifier)
    }

    @Test
    fun decodeRespectsRemainingLengthBoundEvenWithTrailingBytes() {
        val wire =
            byteArrayOf(
                0xB0.toByte(),
                0x02,
                0x00,
                0x01,
                0xC0.toByte(),
                0x00,
                0xDE.toByte(),
                0xAD.toByte(),
            )
        val buf = bigEndianBufferOf(wire)
        UnsubAckCodec.decode(buf, DecodeContext.Empty)
        assertEquals(4, buf.position(), "decode advanced exactly through UNSUBACK")
        assertEquals(4, buf.remaining(), "trailing 4 bytes left in buffer for next packet")
    }

    @Test
    fun decodeRestoresBufferLimitAfterCompletion() {
        val buf = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0xB0.toByte())
        buf.writeByte(0x02)
        buf.writeShort(0x0001.toShort())
        buf.resetForRead()
        val originalLimit = buf.limit()
        UnsubAckCodec.decode(buf, DecodeContext.Empty)
        assertEquals(originalLimit, buf.limit(), "decode restored the outer limit")
    }

    @Test
    fun roundTripsSpecExample() {
        val original =
            MqttPacket.UnsubAck(
                header = MqttFixedHeader(0xB0u),
                packetIdentifier = 0xCAFEu,
            )
        val buf = encode(original)
        assertEquals(original, UnsubAckCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun decodeRejectsMalformedVarInt() {
        val wire =
            byteArrayOf(
                0xB0.toByte(),
                0x80.toByte(),
                0x80.toByte(),
                0x80.toByte(),
                0x80.toByte(),
                0x80.toByte(),
            )
        val buf = bigEndianBufferOf(wire)
        val ex =
            assertFailsWith<DecodeException> {
                UnsubAckCodec.decode(buf, DecodeContext.Empty)
            }
        assertEquals("MqttRemainingLength", ex.fieldPath)
    }

    @Test
    fun peekFrameSizeWalksDripFed() {
        val pool = BufferPool()
        val original = MqttPacket.UnsubAck(packetIdentifier = 0x0042u)
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
                    UnsubAckCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), UnsubAckCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun encodeAndAssertBytes(
        msg: MqttPacket.UnsubAck,
        expected: ByteArray,
    ) {
        val buf = encode(msg)
        assertEquals(expected.size, buf.remaining(), "encoded byte count matches MQTT-3.1.1 §3.11 layout")
        val actual = buf.readByteArray(expected.size)
        assertContentEquals(expected, actual, "encoded bytes match MQTT-3.1.1 §3.11")
    }

    private fun bigEndianBufferOf(wire: ByteArray) =
        BufferFactory.Default
            .allocate(wire.size, ByteOrder.BIG_ENDIAN)
            .also { it.writeBytes(wire) }
            .also { it.resetForRead() }

    private fun encode(value: MqttPacket.UnsubAck): ReadBuffer = UnsubAckCodec.encode(value, EncodeContext.Empty, BufferFactory.Default)
}
