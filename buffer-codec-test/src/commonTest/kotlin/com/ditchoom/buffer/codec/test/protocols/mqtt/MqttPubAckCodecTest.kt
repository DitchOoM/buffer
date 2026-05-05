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
 * Phase J.M step 5 first tranche — MQTT v3.1.1 §3.4 PUBACK packet.
 * Fixed-shape 4-byte ack: header `0x40` + remainingLength=2 +
 * packetIdentifier (UShort BE). Drives the per-variant `PubAckCodec`
 * object emitted by the slice 6 dispatcher (option 1 from the brief).
 */
class MqttPubAckCodecTest {
    @Test
    fun encodesByteExact() {
        val msg =
            MqttPacket.PubAck(
                header = MqttFixedHeader(0x40u),
                packetIdentifier = 0x1234u,
            )
        val expected = byteArrayOf(0x40, 0x02, 0x12, 0x34)
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun decodesFromSpecBytes() {
        val wire = byteArrayOf(0x40, 0x02, 0x12, 0x34)
        val buf = bigEndianBufferOf(wire)
        val decoded = PubAckCodec.decode(buf, DecodeContext.Empty)
        assertEquals(MqttFixedHeader(0x40u), decoded.header)
        assertEquals(2u, decoded.remainingLength)
        assertEquals(0x1234u.toUShort(), decoded.packetIdentifier)
    }

    @Test
    fun decodeRespectsRemainingLengthBoundEvenWithTrailingBytes() {
        val wire =
            byteArrayOf(
                0x40, 0x02,
                0x00, 0x01,
                // Trailing bytes (would be the next MQTT packet)
                0xC0.toByte(), 0x00, 0xDE.toByte(), 0xAD.toByte(),
            )
        val buf = bigEndianBufferOf(wire)
        PubAckCodec.decode(buf, DecodeContext.Empty)
        assertEquals(4, buf.position(), "decode advanced exactly through PUBACK")
        assertEquals(4, buf.remaining(), "trailing 4 bytes left in buffer for next packet")
    }

    @Test
    fun decodeRestoresBufferLimitAfterCompletion() {
        val buf = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0x40)
        buf.writeByte(0x02)
        buf.writeShort(0x0001.toShort())
        buf.resetForRead()
        val originalLimit = buf.limit()
        PubAckCodec.decode(buf, DecodeContext.Empty)
        assertEquals(originalLimit, buf.limit(), "decode restored the outer limit")
    }

    @Test
    fun roundTripsSpecExample() {
        val original =
            MqttPacket.PubAck(
                header = MqttFixedHeader(0x40u),
                packetIdentifier = 0xCAFEu,
            )
        val buf = encode(original)
        buf.resetForRead()
        assertEquals(original, PubAckCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun wireSizeIsBackPatchWithUseCodecScalar() {
        // Phase I.1 step 9 — `@UseCodec(MqttRemainingLengthCodec)` collapses
        // wireSize to BackPatch unconditionally; same shape SubAck exercises.
        val msg = MqttPacket.PubAck(packetIdentifier = 1u)
        assertEquals(WireSize.BackPatch, PubAckCodec.wireSize(msg, EncodeContext.Empty))
    }

    @Test
    fun decodeRejectsMalformedVarInt() {
        // 5 bytes all with continuation bit — exceeds 4-byte var-int max.
        val wire =
            byteArrayOf(
                0x40,
                0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
            )
        val buf = bigEndianBufferOf(wire)
        val ex =
            assertFailsWith<DecodeException> {
                PubAckCodec.decode(buf, DecodeContext.Empty)
            }
        assertEquals("MqttRemainingLength", ex.fieldPath)
    }

    @Test
    fun peekFrameSizeWalksDripFed() {
        val pool = BufferPool()
        val original = MqttPacket.PubAck(packetIdentifier = 0x0042u)
        val encoded = encode(original)
        encoded.resetForRead()
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
                    PubAckCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), PubAckCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun encodeAndAssertBytes(
        msg: MqttPacket.PubAck,
        expected: ByteArray,
    ) {
        val buf = encode(msg)
        assertEquals(expected.size, buf.position(), "encoded byte count matches MQTT-3.1.1 §3.4 layout")
        buf.resetForRead()
        val actual = buf.readByteArray(expected.size)
        assertContentEquals(expected, actual, "encoded bytes match MQTT-3.1.1 §3.4")
    }

    private fun bigEndianBufferOf(wire: ByteArray) =
        BufferFactory.Default.allocate(wire.size, ByteOrder.BIG_ENDIAN)
            .also { it.writeBytes(wire) }
            .also { it.resetForRead() }

    private fun encode(value: MqttPacket.PubAck) =
        BufferFactory.Default
            .allocate(8, ByteOrder.BIG_ENDIAN)
            .also { PubAckCodec.encode(it, value, EncodeContext.Empty) }
}
