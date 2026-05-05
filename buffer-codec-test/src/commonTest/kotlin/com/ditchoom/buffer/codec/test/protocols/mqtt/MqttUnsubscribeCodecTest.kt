package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Phase J.M step 5 third tranche — MQTT v3.1.1 §3.10 UNSUBSCRIBE
 * packet. Variable-shape body: header `0xA2` + remainingLength +
 * packetIdentifier + a non-empty list of `MqttUnsubscribeTopic`
 * elements (each `<2-byte LP><name>` per element). Drives
 * `UnsubscribeCodec` over the J.M.0 emitter slice
 * (`@RemainingBytes List<@ProtocolMessage T>`); the topic-element
 * data class wraps the LP string per the brief's gap-5 resolution.
 */
class MqttUnsubscribeCodecTest {
    @Test
    fun encodesSingleTopicByteExact() {
        // body = 2 (pid) + 2 (LP) + 3 (topic "t/1") = 7
        val msg =
            MqttPacket.Unsubscribe(
                header = MqttFixedHeader(0xA2u),
                remainingLength = 7u,
                packetIdentifier = 0x000Au,
                topics = listOf(MqttUnsubscribeTopic(name = "t/1")),
            )
        val expected =
            byteArrayOf(
                0xA2.toByte(), // fixed header (type=10, flags=0x2 reserved)
                0x07,
                0x00, 0x0A,
                0x00, 0x03, 't'.code.toByte(), '/'.code.toByte(), '1'.code.toByte(),
            )
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun encodesMultipleTopicsByteExact() {
        // Two topics: "a" and "b/c". body = 2 (pid) + 2 + 1 + 2 + 3 = 10
        val msg =
            MqttPacket.Unsubscribe(
                header = MqttFixedHeader(0xA2u),
                remainingLength = 10u,
                packetIdentifier = 0x0001u,
                topics =
                    listOf(
                        MqttUnsubscribeTopic("a"),
                        MqttUnsubscribeTopic("b/c"),
                    ),
            )
        val expected =
            byteArrayOf(
                0xA2.toByte(),
                0x0A,
                0x00, 0x01,
                0x00, 0x01, 'a'.code.toByte(),
                0x00, 0x03, 'b'.code.toByte(), '/'.code.toByte(), 'c'.code.toByte(),
            )
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun defaultHeaderEncodesSpecMandatedFlagBit() {
        // Default constructor must emit 0xA2 — bottom-bit-2 set per §3.10.1.
        val msg =
            MqttPacket.Unsubscribe(
                remainingLength = 7u,
                packetIdentifier = 1u,
                topics = listOf(MqttUnsubscribeTopic("t/1")),
            )
        assertEquals(MqttFixedHeader(0xA2u), msg.header, "default header byte = 0xA2")
        assertEquals(0x2u.toUByte(), msg.header.flags, "flags = 0x2 per §3.10.1")
    }

    @Test
    fun decodesSingleTopicFromSpecBytes() {
        val wire =
            byteArrayOf(
                0xA2.toByte(), 0x07,
                0x00, 0x0A,
                0x00, 0x03, 't'.code.toByte(), '/'.code.toByte(), '1'.code.toByte(),
            )
        val buf = bigEndianBufferOf(wire)
        val decoded = UnsubscribeCodec.decode(buf, DecodeContext.Empty)
        assertEquals(MqttFixedHeader(0xA2u), decoded.header)
        assertEquals(7u, decoded.remainingLength)
        assertEquals(0x000Au.toUShort(), decoded.packetIdentifier)
        assertEquals(listOf(MqttUnsubscribeTopic("t/1")), decoded.topics)
    }

    @Test
    fun decodeRespectsRemainingLengthBoundEvenWithTrailingBytes() {
        // body = 2 (pid) + 2 + 1 (topic "x") = 5
        val wire =
            byteArrayOf(
                0xA2.toByte(), 0x05,
                0x00, 0x01,
                0x00, 0x01, 'x'.code.toByte(),
                // Trailing bytes (would be the next MQTT packet)
                0xC0.toByte(), 0x00, 0xDE.toByte(), 0xAD.toByte(),
            )
        val buf = bigEndianBufferOf(wire)
        val decoded = UnsubscribeCodec.decode(buf, DecodeContext.Empty)
        assertEquals(1, decoded.topics.size, "decode bounded by remainingLength, not buffer remaining")
        assertEquals(7, buf.position(), "decode advanced exactly through UNSUBSCRIBE")
        assertEquals(4, buf.remaining(), "trailing 4 bytes left in buffer for next packet")
    }

    @Test
    fun decodeRestoresBufferLimitAfterCompletion() {
        val buf = BufferFactory.Default.allocate(32, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0xA2.toByte())
        buf.writeByte(0x05)
        buf.writeShort(0x0001.toShort())
        buf.writeShort(0x0001.toShort())
        buf.writeByte('x'.code.toByte())
        buf.resetForRead()
        val originalLimit = buf.limit()
        UnsubscribeCodec.decode(buf, DecodeContext.Empty)
        assertEquals(originalLimit, buf.limit(), "decode restored the outer limit")
    }

    @Test
    fun roundTripsSpecExample() {
        // body = 2 (pid) + 11 (LP=2 + "sensors/+"=9) + 3 (LP=2 + "x"=1) = 16
        val original =
            MqttPacket.Unsubscribe(
                header = MqttFixedHeader(0xA2u),
                remainingLength = 16u,
                packetIdentifier = 0xCAFEu,
                topics =
                    listOf(
                        MqttUnsubscribeTopic("sensors/+"),
                        MqttUnsubscribeTopic("x"),
                    ),
            )
        val buf = encode(original)
        buf.resetForRead()
        assertEquals(original, UnsubscribeCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun wireSizeIsBackPatchWithUseCodecScalar() {
        val msg =
            MqttPacket.Unsubscribe(
                remainingLength = 7u,
                packetIdentifier = 1u,
                topics = listOf(MqttUnsubscribeTopic("t/1")),
            )
        assertEquals(WireSize.BackPatch, UnsubscribeCodec.wireSize(msg, EncodeContext.Empty))
    }

    @Test
    fun decodeRejectsMalformedVarInt() {
        val wire =
            byteArrayOf(
                0xA2.toByte(),
                0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
            )
        val buf = bigEndianBufferOf(wire)
        val ex =
            assertFailsWith<DecodeException> {
                UnsubscribeCodec.decode(buf, DecodeContext.Empty)
            }
        assertEquals("MqttRemainingLength", ex.fieldPath)
    }

    @Test
    fun peekFrameSizeWalksDripFed() {
        val pool = BufferPool()
        val original =
            MqttPacket.Unsubscribe(
                remainingLength = 7u,
                packetIdentifier = 0x000Au,
                topics = listOf(MqttUnsubscribeTopic("t/1")),
            )
        val encoded = encode(original)
        encoded.resetForRead()
        val totalBytes = encoded.remaining()
        // 1 (header) + 1 (var-int) + 7 (body) = 9
        assertEquals(9, totalBytes)

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            for (i in 0 until totalBytes - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(encoded.readByte())
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    UnsubscribeCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), UnsubscribeCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun encodeAndAssertBytes(
        msg: MqttPacket.Unsubscribe,
        expected: ByteArray,
    ) {
        val buf = encode(msg)
        assertEquals(expected.size, buf.position(), "encoded byte count matches MQTT-3.1.1 §3.10 layout")
        buf.resetForRead()
        val actual = buf.readByteArray(expected.size)
        assertContentEquals(expected, actual, "encoded bytes match MQTT-3.1.1 §3.10")
    }

    private fun bigEndianBufferOf(wire: ByteArray) =
        BufferFactory.Default.allocate(wire.size, ByteOrder.BIG_ENDIAN)
            .also { it.writeBytes(wire) }
            .also { it.resetForRead() }

    private fun encode(value: MqttPacket.Unsubscribe) =
        BufferFactory.Default
            .allocate(value.remainingLength.toInt() + 8, ByteOrder.BIG_ENDIAN)
            .also { UnsubscribeCodec.encode(it, value, EncodeContext.Empty) }
}
