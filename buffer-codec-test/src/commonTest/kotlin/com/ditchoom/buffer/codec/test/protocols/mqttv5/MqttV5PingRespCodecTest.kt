package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImage
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImageCodec
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * PINGRESP v5. Wire-bytewise identical to v3.1.1
 * PINGRESP (`D0 00`); v5 carries no reason code or property bag for
 * this packet. Adds the dispatcher symmetric pair to PINGREQ.
 */
class MqttV5PingRespCodecTest {
    @Test
    fun encodesPingRespAsTwoBytes() {
        val msg = MqttV5Packet.PingResp()
        val buf = encode(msg)
        assertEquals(2, buf.remaining(), "encoded byte count matches §3.13.1 layout")
        val actual = buf.readByteArray(2)
        assertContentEquals(
            byteArrayOf(0xD0.toByte(), 0x00),
            actual,
            "encoded bytes match MQTT-5.0 §3.13 layout",
        )
    }

    @Test
    fun decodesPingRespFromSpecBytes() {
        val buf =
            BufferFactory.Default.allocate(2).also {
                it.writeByte(0xD0.toByte())
                it.writeByte(0x00)
            }
        buf.resetForRead()
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        val pingResp = assertIs<MqttV5Packet.PingResp>(decoded)
        assertEquals(MqttFixedHeader(0xD0u), pingResp.header)
    }

    @Test
    fun roundTripsPingResp() {
        val msg = MqttV5Packet.PingResp()
        val buf = encode(msg)
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        assertEquals(msg, decoded)
    }

    @Test
    fun peekFrameSizeForPingRespCompletesAtTwoBytes() {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            val two = BufferFactory.Default.allocate(2)
            two.writeByte(0xD0.toByte())
            two.writeByte(0x00)
            two.resetForRead()
            stream.append(two)
            assertEquals(PeekResult.Complete(2), jpegDispatcher().peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun encode(value: MqttV5Packet<*>): ReadBuffer =
        jpegDispatcher().encode(value as MqttV5Packet<JpegImage>, EncodeContext.Empty, BufferFactory.Default)

    private fun jpegDispatcher(): MqttV5PacketCodec<JpegImage> = MqttV5PacketCodec(JpegImageCodec)
}
