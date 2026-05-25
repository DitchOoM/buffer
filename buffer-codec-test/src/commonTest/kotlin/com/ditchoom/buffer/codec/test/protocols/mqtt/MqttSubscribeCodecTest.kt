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
 * MQTT v3.1.1 §3.8 SUBSCRIBE
 * packet. Variable-shape body: header `0x82` + remainingLength +
 * packetIdentifier + a non-empty list of `MqttTopicFilter`
 * elements (`<2-byte LP><filter><qos>` per element). Drives
 * `MqttPacketSubscribeCodec` over the emitter slice
 * (`@RemainingBytes List<@ProtocolMessage T>`).
 */
class MqttSubscribeCodecTest {
    @Test
    fun encodesSingleFilterByteExact() {
        // body = 2 (pid) + 2 (LP) + 3 (topic "t/1") + 1 (qos) = 8
        val msg =
            MqttPacket.Subscribe(
                header = MqttFixedHeader(0x82u),
                packetIdentifier = 0x000Au,
                topicFilters = listOf(MqttTopicFilter(filter = "t/1", qos = 0x01u)),
            )
        val expected =
            byteArrayOf(
                0x82.toByte(), // fixed header (type=8, flags=0x2 reserved)
                0x08, // remaining length = 8 (1-byte var-int)
                0x00,
                0x0A, // packet id = 10
                0x00,
                0x03,
                't'.code.toByte(),
                '/'.code.toByte(),
                '1'.code.toByte(),
                0x01, // qos
            )
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun encodesMultipleFiltersByteExact() {
        // Two filters: ("a", qos=0) and ("b/c", qos=2).
        // body = 2 (pid) + 2 + 1 + 1 (filter1) + 2 + 3 + 1 (filter2) = 12
        val msg =
            MqttPacket.Subscribe(
                header = MqttFixedHeader(0x82u),
                packetIdentifier = 0x0001u,
                topicFilters =
                    listOf(
                        MqttTopicFilter(filter = "a", qos = 0x00u),
                        MqttTopicFilter(filter = "b/c", qos = 0x02u),
                    ),
            )
        val expected =
            byteArrayOf(
                0x82.toByte(),
                0x0C,
                0x00,
                0x01,
                0x00,
                0x01,
                'a'.code.toByte(),
                0x00,
                0x00,
                0x03,
                'b'.code.toByte(),
                '/'.code.toByte(),
                'c'.code.toByte(),
                0x02,
            )
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun defaultHeaderEncodesSpecMandatedFlagBit() {
        // Default constructor must emit 0x82 — bottom-bit-2 set per §3.8.1.
        val msg =
            MqttPacket.Subscribe(
                packetIdentifier = 1u,
                topicFilters = listOf(MqttTopicFilter("t/1", 0u)),
            )
        assertEquals(MqttFixedHeader(0x82u), msg.header, "default header byte = 0x82")
        assertEquals(0x2u.toUByte(), msg.header.flags, "flags = 0x2 per §3.8.1")
    }

    @Test
    fun decodesSingleFilterFromSpecBytes() {
        val wire =
            byteArrayOf(
                0x82.toByte(),
                0x08,
                0x00,
                0x0A,
                0x00,
                0x03,
                't'.code.toByte(),
                '/'.code.toByte(),
                '1'.code.toByte(),
                0x01,
            )
        val buf = bigEndianBufferOf(wire)
        val decoded = MqttPacketSubscribeCodec.decode(buf, DecodeContext.Empty)
        assertEquals(MqttFixedHeader(0x82u), decoded.header)
        assertEquals(0x000Au.toUShort(), decoded.packetIdentifier)
        assertEquals(listOf(MqttTopicFilter("t/1", 0x01u)), decoded.topicFilters)
    }

    @Test
    fun decodeRespectsRemainingLengthBoundEvenWithTrailingBytes() {
        // body = 2 (pid) + 2 + 1 (filter "x") + 1 (qos) = 6
        val wire =
            byteArrayOf(
                0x82.toByte(),
                0x06,
                0x00,
                0x01,
                0x00,
                0x01,
                'x'.code.toByte(),
                0x00,
                // Trailing bytes (would be the next MQTT packet)
                0xC0.toByte(),
                0x00,
                0xDE.toByte(),
                0xAD.toByte(),
            )
        val buf = bigEndianBufferOf(wire)
        val decoded = MqttPacketSubscribeCodec.decode(buf, DecodeContext.Empty)
        assertEquals(1, decoded.topicFilters.size, "decode bounded by remainingLength, not buffer remaining")
        assertEquals(8, buf.position(), "decode advanced exactly through SUBSCRIBE")
        assertEquals(4, buf.remaining(), "trailing 4 bytes left in buffer for next packet")
    }

    @Test
    fun decodeRestoresBufferLimitAfterCompletion() {
        val buf = BufferFactory.Default.allocate(32, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0x82.toByte())
        buf.writeByte(0x06)
        buf.writeShort(0x0001.toShort())
        buf.writeShort(0x0001.toShort())
        buf.writeByte('x'.code.toByte())
        buf.writeByte(0x00)
        buf.resetForRead()
        val originalLimit = buf.limit()
        MqttPacketSubscribeCodec.decode(buf, DecodeContext.Empty)
        assertEquals(originalLimit, buf.limit(), "decode restored the outer limit")
    }

    @Test
    fun roundTripsSpecExample() {
        // body = 2 (pid) + 12 (LP=2 + "sensors/+"=9 + qos=1)
        //              + 4 (LP=2 + "x"=1 + qos=1) = 18
        val original =
            MqttPacket.Subscribe(
                header = MqttFixedHeader(0x82u),
                packetIdentifier = 0xCAFEu,
                topicFilters =
                    listOf(
                        MqttTopicFilter(filter = "sensors/+", qos = 0x01u),
                        MqttTopicFilter(filter = "x", qos = 0x02u),
                    ),
            )
        val buf = encode(original)
        assertEquals(original, MqttPacketSubscribeCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun decodeRejectsMalformedVarInt() {
        val wire =
            byteArrayOf(
                0x82.toByte(),
                0x80.toByte(),
                0x80.toByte(),
                0x80.toByte(),
                0x80.toByte(),
                0x80.toByte(),
            )
        val buf = bigEndianBufferOf(wire)
        val ex =
            assertFailsWith<DecodeException> {
                MqttPacketSubscribeCodec.decode(buf, DecodeContext.Empty)
            }
        assertEquals("MqttRemainingLength", ex.fieldPath)
    }

    @Test
    fun peekFrameSizeWalksDripFed() {
        val pool = BufferPool()
        val original =
            MqttPacket.Subscribe(
                packetIdentifier = 0x000Au,
                topicFilters = listOf(MqttTopicFilter("t/1", 0x01u)),
            )
        val encoded = encode(original)
        val totalBytes = encoded.remaining()
        // 1 (header) + 1 (var-int) + 8 (body) = 10
        assertEquals(10, totalBytes)

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            for (i in 0 until totalBytes - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(encoded.readByte())
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    MqttPacketSubscribeCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), MqttPacketSubscribeCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun encodeAndAssertBytes(
        msg: MqttPacket.Subscribe,
        expected: ByteArray,
    ) {
        val buf = encode(msg)
        assertEquals(expected.size, buf.remaining(), "encoded byte count matches MQTT-3.1.1 §3.8 layout")
        val actual = buf.readByteArray(expected.size)
        assertContentEquals(expected, actual, "encoded bytes match MQTT-3.1.1 §3.8")
    }

    private fun bigEndianBufferOf(wire: ByteArray) =
        BufferFactory.Default
            .allocate(wire.size, ByteOrder.BIG_ENDIAN)
            .also { it.writeBytes(wire) }
            .also { it.resetForRead() }

    private fun encode(value: MqttPacket.Subscribe): ReadBuffer =
        MqttPacketSubscribeCodec.encode(value, EncodeContext.Empty, BufferFactory.Default)
}
