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
 * MQTT v3.1.1 §3.2 CONNACK packet.
 * Fixed-shape 4-byte ack: header `0x20` + remainingLength=2 +
 * connectAckFlags (UByte; bit 0 carries Session Present per §3.2.2.1)
 * + returnCode (UByte per §3.2.2.3). Drives `ConnAckCodec`.
 */
class MqttConnAckCodecTest {
    @Test
    fun encodesAcceptedByteExact() {
        // Connection accepted, no session — flags=0x00, rc=0x00.
        val msg =
            MqttPacket.ConnAck(
                header = MqttFixedHeader(0x20u),
                connectAckFlags = 0x00u,
                returnCode = 0x00u,
            )
        val expected = byteArrayOf(0x20, 0x02, 0x00, 0x00)
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun encodesSessionPresentWithBadUserNameByteExact() {
        // sessionPresent bit set + return code 4 (bad user name / password).
        val msg =
            MqttPacket.ConnAck(
                header = MqttFixedHeader(0x20u),
                connectAckFlags = 0x01u,
                returnCode = 0x04u,
            )
        val expected = byteArrayOf(0x20, 0x02, 0x01, 0x04)
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun decodesFromSpecBytes() {
        val wire = byteArrayOf(0x20, 0x02, 0x01, 0x00)
        val buf = bigEndianBufferOf(wire)
        val decoded = ConnAckCodec.decode(buf, DecodeContext.Empty)
        assertEquals(MqttFixedHeader(0x20u), decoded.header)
        assertEquals(0x01u.toUByte(), decoded.connectAckFlags, "session-present bit")
        assertEquals(0x00u.toUByte(), decoded.returnCode, "accepted")
    }

    @Test
    fun decodeRespectsRemainingLengthBoundEvenWithTrailingBytes() {
        val wire =
            byteArrayOf(
                0x20,
                0x02,
                0x00,
                0x00,
                0xC0.toByte(),
                0x00,
                0xDE.toByte(),
                0xAD.toByte(),
            )
        val buf = bigEndianBufferOf(wire)
        ConnAckCodec.decode(buf, DecodeContext.Empty)
        assertEquals(4, buf.position(), "decode advanced exactly through CONNACK")
        assertEquals(4, buf.remaining(), "trailing 4 bytes left in buffer for next packet")
    }

    @Test
    fun decodeRestoresBufferLimitAfterCompletion() {
        val buf = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0x20)
        buf.writeByte(0x02)
        buf.writeByte(0x00)
        buf.writeByte(0x00)
        buf.resetForRead()
        val originalLimit = buf.limit()
        ConnAckCodec.decode(buf, DecodeContext.Empty)
        assertEquals(originalLimit, buf.limit(), "decode restored the outer limit")
    }

    @Test
    fun roundTripsSpecExample() {
        val original =
            MqttPacket.ConnAck(
                header = MqttFixedHeader(0x20u),
                connectAckFlags = 0x01u,
                returnCode = 0x05u,
            )
        val buf = encode(original)
        assertEquals(original, ConnAckCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun decodeRejectsMalformedVarInt() {
        val wire =
            byteArrayOf(
                0x20,
                0x80.toByte(),
                0x80.toByte(),
                0x80.toByte(),
                0x80.toByte(),
                0x80.toByte(),
            )
        val buf = bigEndianBufferOf(wire)
        val ex =
            assertFailsWith<DecodeException> {
                ConnAckCodec.decode(buf, DecodeContext.Empty)
            }
        assertEquals("MqttRemainingLength", ex.fieldPath)
    }

    @Test
    fun peekFrameSizeWalksDripFed() {
        val pool = BufferPool()
        val original = MqttPacket.ConnAck(connectAckFlags = 0x01u, returnCode = 0x00u)
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
                    ConnAckCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), ConnAckCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun encodeAndAssertBytes(
        msg: MqttPacket.ConnAck,
        expected: ByteArray,
    ) {
        val buf = encode(msg)
        assertEquals(expected.size, buf.remaining(), "encoded byte count matches MQTT-3.1.1 §3.2 layout")
        val actual = buf.readByteArray(expected.size)
        assertContentEquals(expected, actual, "encoded bytes match MQTT-3.1.1 §3.2")
    }

    private fun bigEndianBufferOf(wire: ByteArray) =
        BufferFactory.Default
            .allocate(wire.size, ByteOrder.BIG_ENDIAN)
            .also { it.writeBytes(wire) }
            .also { it.resetForRead() }

    private fun encode(value: MqttPacket.ConnAck): ReadBuffer = ConnAckCodec.encode(value, EncodeContext.Empty, BufferFactory.Default)
}
