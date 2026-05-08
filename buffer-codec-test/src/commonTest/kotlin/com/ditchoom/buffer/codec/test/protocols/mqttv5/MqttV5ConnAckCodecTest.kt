package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import com.ditchoom.buffer.codec.test.protocols.mqttv5.connack.V5ConnectReasonCode
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImage
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImageCodec
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * ConnAck v5. Wire layout per MQTT-5.0 §3.2:
 *
 * ```text
 *   20 <RL> <flags> <rc> <propLen> <properties...>
 * ```
 *
 * Always-present property bag — exercises 's
 * `@LengthPrefixed @UseCodec(MqttRemainingLengthCodec) val:
 * List<MqttV5Property>` shape on a non-Publish variant. No new emitter
 * capability beyond.
 */
class MqttV5ConnAckCodecTest {
    @Test
    fun encodesConnAckWithEmptyPropertyBagByteExact() {
        // body = 1 (flags) + 1 (rc) + 1 (propLen=0) = 3
        val msg =
            MqttV5Packet.ConnAck(
                connectAckFlags = 0x00u,
                reasonCode = V5ConnectReasonCode.Success(),
                properties = V5PropertyBag.EMPTY,
            )
        val buf = encode(msg)
        val actual = buf.readByteArray(buf.remaining())
        val expected =
            byteArrayOf(
                0x20, // fixed header
                0x03, // remaining length
                0x00, // session-present + reserved bits
                0x00, // reason code = Success
                0x00, // properties length = 0
            )
        assertContentEquals(expected, actual)
    }

    @Test
    fun encodesConnAckWithSessionPresentAndPropertiesByteExact() {
        // body = 1 (flags) + 1 (rc) + 1 (propLen=5) + 5 (one MessageExpiry)
        //      = 8
        val msg =
            MqttV5Packet.ConnAck(
                connectAckFlags = 0x01u, // session present = true
                reasonCode = V5ConnectReasonCode.Success(),
                properties = V5PropertyBag.of(MqttV5Property.MessageExpiryInterval(seconds = 60u)),
            )
        val buf = encode(msg)
        val actual = buf.readByteArray(buf.remaining())
        val expected =
            byteArrayOf(
                0x20,
                0x08,
                0x01, // session-present = true
                0x00,
                0x05, // properties length = 5
                0x02, // MessageExpiryInterval id
                0x00,
                0x00,
                0x00,
                0x3C, // expiry = 60
            )
        assertContentEquals(expected, actual)
    }

    @Test
    fun decodesConnAckFromSpecBytes() {
        val wire =
            byteArrayOf(
                0x20,
                0x03,
                0x00,
                0x82.toByte(), // reason code = Malformed Packet (per spec)
                0x00,
            )
        val buf =
            BufferFactory.Default
                .allocate(wire.size, ByteOrder.BIG_ENDIAN)
                .also {
                    it.writeBytes(wire)
                    it.resetForRead()
                }
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        val connAck = assertIs<MqttV5Packet.ConnAck>(decoded)
        assertEquals(MqttFixedHeader(0x20u), connAck.header)
        assertEquals(0x00u.toUByte(), connAck.connectAckFlags)
        assertEquals(V5ConnectReasonCode.ProtocolError(), connAck.reasonCode)
        assertEquals(V5PropertyBag.EMPTY, connAck.properties)
    }

    @Test
    fun roundTripsConnAckWithProperties() {
        val original =
            MqttV5Packet.ConnAck(
                // body = 1 + 1 + 1 (propLen=18) + 18 (Expiry + ContentType) = 21
                connectAckFlags = 0x01u,
                reasonCode = V5ConnectReasonCode.Success(),
                properties =
                    V5PropertyBag.of(
                        MqttV5Property.MessageExpiryInterval(seconds = 3_600u),
                        MqttV5Property.ContentType(value = "text/plain"),
                    ),
            )
        val buf = encode(original)
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun peekFrameSizeForConnAckCompletes() {
        val msg =
            MqttV5Packet.ConnAck(
                connectAckFlags = 0x00u,
                reasonCode = V5ConnectReasonCode.Success(),
                properties = V5PropertyBag.EMPTY,
            )
        val buf = encode(msg)
        val totalBytes = buf.remaining()

        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            stream.append(buf)
            assertEquals(PeekResult.Complete(totalBytes), jpegDispatcher().peekFrameSize(stream))
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
