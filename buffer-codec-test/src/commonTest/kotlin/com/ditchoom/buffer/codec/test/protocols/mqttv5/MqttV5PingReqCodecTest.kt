package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Phase J.M.5 slice 1 — smoke test for the new
 * [MqttV5Packet] sealed dispatcher. PINGREQ v5 is wire-bytewise identical
 * to v3.1.1 PINGREQ (`C0 00`); the value here is purely structural —
 * proves the v5 sealed parent + generated codec + peek path land cleanly
 * before the property-bag shape lands in slice 2 (PUBLISH v5).
 *
 * The slice-1 sealed parent is non-generic, so the dispatcher
 * [MqttV5PacketCodec] is a singleton (no payload codec to thread). Slice
 * 2 will lift to `<out P : Payload>` and a generic dispatcher class.
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
        val decoded = MqttV5PacketCodec.decode(buf, DecodeContext.Empty)
        val pingReq = assertIs<MqttV5Packet.PingReq>(decoded)
        assertEquals(MqttFixedHeader(0xC0u), pingReq.header)
        assertEquals(0u, pingReq.remainingLength)
    }

    @Test
    fun roundTripsPingReq() {
        val msg = MqttV5Packet.PingReq()
        val buf = encode(msg)
        buf.resetForRead()
        val decoded = MqttV5PacketCodec.decode(buf, DecodeContext.Empty)
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
            assertEquals(PeekResult.Complete(2), MqttV5PacketCodec.peekFrameSize(stream))
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
            assertEquals(PeekResult.NeedsMoreData, MqttV5PacketCodec.peekFrameSize(stream))

            val one = BufferFactory.Default.allocate(1)
            one.writeByte(0xC0.toByte())
            one.resetForRead()
            stream.append(one)
            assertEquals(
                PeekResult.NeedsMoreData,
                MqttV5PacketCodec.peekFrameSize(stream),
                "header alone — peek still needs the var-int byte",
            )

            val two = BufferFactory.Default.allocate(1)
            two.writeByte(0x00)
            two.resetForRead()
            stream.append(two)
            assertEquals(PeekResult.Complete(2), MqttV5PacketCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun encode(value: MqttV5Packet) =
        BufferFactory.Default
            .allocate(256)
            .also { MqttV5PacketCodec.encode(it, value, EncodeContext.Empty) }
}
