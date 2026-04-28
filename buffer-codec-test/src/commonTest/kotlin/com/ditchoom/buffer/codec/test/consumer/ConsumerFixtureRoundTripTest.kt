// PHASE 9 FIXTURE — round-trip drivers for the consumer-fixture wire shapes.
// Targets:
//   * MQTT v4 PUBLISH (@Payload P)
//   * MQTT v5 PUBLISH (@Payload P)
//   * WebSocket Text frame (@Payload T)
//   * Value-class-bearing struct (MqttFixedHeader)
// These tests SHOULD provoke whatever gaps remain in the new pipeline. Failures here
// are the work surface for Phase 9 Steps 2-5 (per the redesign plan); legacy is the
// safety net via pipelineEligible() until the new pipeline is a strict superset.
// Deleted in Phase 9 Step 7 once consumer cutover is verified.
package com.ditchoom.buffer.codec.test.consumer

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.MqttFixedHeader
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.MqttFixedHeaderCodec
import com.ditchoom.buffer.codec.test.consumer.mqtt3.controlpacket.PublishMessageV4
import com.ditchoom.buffer.codec.test.consumer.mqtt3.controlpacket.PublishMessageV4Codec
import com.ditchoom.buffer.codec.test.consumer.mqtt5.controlpacket.ControlPacketV5
import com.ditchoom.buffer.codec.test.consumer.mqtt5.controlpacket.ControlPacketV5PublishCodec
import com.ditchoom.buffer.codec.test.consumer.websocket.frame.FrameHeaderByte1
import com.ditchoom.buffer.codec.test.consumer.websocket.Opcode
import com.ditchoom.buffer.codec.test.consumer.websocket.frame.WsFrame
import com.ditchoom.buffer.codec.test.consumer.websocket.frame.WsFrameHeader
import com.ditchoom.buffer.codec.test.consumer.websocket.frame.WsFrameTextCodec
import com.ditchoom.buffer.codec.test.consumer.websocket.frame.WsHeaderByte2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsumerFixtureRoundTripTest {

    @Test
    fun `value class round trips through MqttFixedHeader codec`() {
        val original = MqttFixedHeader(0x32u) // PUBLISH, QoS 1, no DUP/RETAIN
        val buffer = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        MqttFixedHeaderCodec.encode(buffer, original, EncodeContext.Empty)
        assertEquals(1, buffer.position(), "MqttFixedHeader is a single UByte on the wire")
        buffer.resetForRead()
        val decoded = MqttFixedHeaderCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
        assertEquals(3, decoded.packetType)
        assertEquals(1, decoded.publishQos)
    }

    @Test
    fun `mqtt v4 publish round trips a typed payload via decode lambda`() {
        val payloadBytes = encodeStringToBuffer("hello v4")
        val original =
            PublishMessageV4(
                header = MqttFixedHeader(0x30u), // PUBLISH, QoS 0
                topicName = "test/topic",
                packetId = null,
                payload = payloadBytes,
            )
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        PublishMessageV4Codec.encode(buffer, original, EncodeContext.Empty) { buf, p ->
            buf.write(p)
        }
        buffer.resetForRead()
        val decoded =
            PublishMessageV4Codec.decode(buffer, DecodeContext.Empty) { slice ->
                slice
            }
        assertEquals(original.header, decoded.header)
        assertEquals(original.topicName, decoded.topicName)
        assertEquals(original.packetId, decoded.packetId)
        // payload is a ReadBuffer slice; compare content
        assertEquals(bufferToString(decoded.payload), "hello v4")
    }

    @Test
    fun `mqtt v5 publish round trips with empty properties and typed payload`() {
        val payloadBytes = encodeStringToBuffer("hello v5")
        val original =
            ControlPacketV5.Publish(
                header = MqttFixedHeader(0x30u),
                topicName = "v5/topic",
                packetId = null,
                properties = emptyList(),
                payload = payloadBytes,
            )
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ControlPacketV5PublishCodec.encode(buffer, original, EncodeContext.Empty) { buf, p ->
            buf.write(p)
        }
        buffer.resetForRead()
        val decoded =
            ControlPacketV5PublishCodec.decode(buffer, DecodeContext.Empty) { slice ->
                slice
            }
        assertEquals(original.header, decoded.header)
        assertEquals(original.topicName, decoded.topicName)
        assertEquals(original.packetId, decoded.packetId)
        assertEquals(bufferToString(decoded.payload), "hello v5")
    }

    @Test
    fun `websocket text frame round trips a typed payload`() {
        val payloadBytes = encodeStringToBuffer("ws hello")
        val payloadLen = payloadBytes.remaining().toLong()
        val original =
            WsFrame.Text(
                header =
                    WsFrameHeader(
                        byte1 = FrameHeaderByte1.pack(fin = true, rsv1 = false, rsv2 = false, rsv3 = false, opcode = Opcode.Text),
                        byte2 = WsHeaderByte2.pack(payloadLen, masked = false),
                    ),
                payload = payloadBytes,
            )
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        WsFrameTextCodec.encode(buffer, original, EncodeContext.Empty) { buf, p ->
            buf.write(p)
        }
        buffer.resetForRead()
        val decoded =
            WsFrameTextCodec.decode(buffer, DecodeContext.Empty) { slice ->
                slice
            }
        assertEquals(original.header, decoded.header)
        assertEquals(bufferToString(decoded.payload), "ws hello")
        assertTrue(decoded.header.byte1.fin)
        assertEquals(Opcode.Text, decoded.header.byte1.opcode)
    }

    private fun encodeStringToBuffer(text: String): ReadBuffer {
        val buf = BufferFactory.Default.allocate(text.length, ByteOrder.BIG_ENDIAN)
        buf.writeString(text)
        buf.resetForRead()
        return buf
    }

    private fun bufferToString(rb: Any): String {
        require(rb is ReadBuffer)
        return rb.readUtf8(rb.remaining()).toString()
    }
}
