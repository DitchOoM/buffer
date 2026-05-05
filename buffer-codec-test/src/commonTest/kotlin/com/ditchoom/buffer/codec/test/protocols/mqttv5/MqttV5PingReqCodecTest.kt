package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.Codec
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
 * Phase J.M.5 slice 1 — smoke test for the [MqttV5Packet] sealed
 * dispatcher. PINGREQ v5 is wire-bytewise identical to v3.1.1 PINGREQ
 * (`C0 00`); the value here is purely structural — proves the v5 sealed
 * parent + generated codec + peek path land cleanly.
 *
 * As of slice 2 the parent is `<out P : Payload>`, so the dispatcher
 * is the generic class `MqttV5PacketCodec<P>(payloadCodec)`. Payload-free
 * variants are `: MqttV5Packet<Nothing>` — covariance makes them
 * assignable to any `MqttV5Packet<P>` instantiation, so tests for those
 * variants can pick any payload codec (we use [JpegImageCodec] by
 * convention).
 */
class MqttV5PingReqCodecTest {
    @Test
    fun encodesPingReqAsTwoBytes() {
        // §3.12.1 — PINGREQ wire is exactly C0 00.
        val msg = MqttV5Packet.PingReq()
        val buf = encode(msg)
        assertEquals(2, buf.position(), "encoded byte count matches §3.12.1 layout")
        buf.resetForRead()
        val actual = buf.readByteArray(2)
        assertContentEquals(
            byteArrayOf(0xC0.toByte(), 0x00),
            actual,
            "encoded bytes match MQTT-5.0 §3.12 layout",
        )
    }

    @Test
    fun decodesPingReqFromSpecBytes() {
        val buf =
            BufferFactory.Default.allocate(2).also {
                it.writeByte(0xC0.toByte())
                it.writeByte(0x00)
            }
        buf.resetForRead()
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        val pingReq = assertIs<MqttV5Packet.PingReq>(decoded)
        assertEquals(MqttFixedHeader(0xC0u), pingReq.header)
        assertEquals(0u, pingReq.remainingLength)
    }

    @Test
    fun roundTripsPingReq() {
        val msg = MqttV5Packet.PingReq()
        val buf = encode(msg)
        buf.resetForRead()
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        assertEquals(msg, decoded)
    }

    @Test
    fun peekFrameSizeForPingReqCompletesAtTwoBytes() {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            val two = BufferFactory.Default.allocate(2)
            two.writeByte(0xC0.toByte())
            two.writeByte(0x00)
            two.resetForRead()
            stream.append(two)
            assertEquals(PeekResult.Complete(2), jpegDispatcher().peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeNeedsMoreDataDripFed() {
        // Header alone is insufficient — peek must read the var-int RL byte
        // before it can decide total frame length.
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, jpegDispatcher().peekFrameSize(stream))

            val one = BufferFactory.Default.allocate(1)
            one.writeByte(0xC0.toByte())
            one.resetForRead()
            stream.append(one)
            assertEquals(
                PeekResult.NeedsMoreData,
                jpegDispatcher().peekFrameSize(stream),
                "header alone — peek still needs the var-int byte",
            )

            val two = BufferFactory.Default.allocate(1)
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
    private fun encode(value: MqttV5Packet<*>) =
        BufferFactory.Default
            .allocate(256)
            .also {
                (jpegDispatcher() as Codec<MqttV5Packet<*>>)
                    .encode(it, value, EncodeContext.Empty)
            }

    private fun jpegDispatcher(): MqttV5PacketCodec<JpegImage> = MqttV5PacketCodec(JpegImageCodec)
}
