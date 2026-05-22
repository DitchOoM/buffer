package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImage
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImageCodec
import com.ditchoom.buffer.codec.test.protocols.payload.PacketId
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * PUBLISH v5 + the v5 property bag (first two
 * variants). The high-coverage smoke test the handoff specified:
 * composes the generic dispatcher, the /10f
 * outer-limit `Partial` machinery (RL + `@RemainingBytes payload`),
 * the `@When` value-class predicate, and the
 * `@LengthPrefixed @UseCodec` property-bag shape
 * the latter widened in this slice to accept sealed-parent elements
 * (`MqttV5Property` instead of a `data class` element).
 *
 * The two property variants exercise distinct value shapes:
 *   - [MqttV5Property.MessageExpiryInterval] — fixed 4-byte BE UInt body.
 *   - [MqttV5Property.ContentType] — `@LengthPrefixed` UTF-8 string body.
 */
class MqttV5PublishCodecTest {
    @Test
    fun encodesPublishWithEmptyPropertyBagByteExact() {
        // Wire layout at QoS=0 with no properties:
        //   30 / RL / <topic LP> / <props LP=0> / <payload>
        // body = 2 (topic LP) + 3 (topic) + 1 (propLen=0) + 4+1 (jpeg) = 11 bytes
        val codec = MqttV5PacketCodec(JpegImageCodec)
        val msg =
            MqttV5Packet.Publish<JpegImage>(
                header = MqttFixedHeader(0x30u),
                topic = "t/1",
                packetId = null,
                properties = V5PropertyBag.EMPTY,
                payload = JpegImage(1u, 1u, byteArrayOf(0x42)),
            )
        val buf = codec.encode(msg, EncodeContext.Empty, BufferFactory.Default)
        val actual = buf.readByteArray(buf.remaining())
        val expected =
            byteArrayOf(
                0x30, // fixed header (type=3, QoS=0)
                0x0B, // remaining length = 11 (1-byte var-int)
                0x00,
                0x03,
                't'.code.toByte(),
                '/'.code.toByte(),
                '1'.code.toByte(),
                0x00, // properties length = 0 (1-byte var-int)
                0x00,
                0x01,
                0x00,
                0x01, // jpeg width=1, height=1
                0x42, // jpeg data
            )
        assertContentEquals(expected, actual)
    }

    @Test
    fun encodesPublishWithPropertiesAtQos1ByteExact() {
        // Wire layout with one MessageExpiryInterval (5 bytes:
        // 1 id + 4 BE UInt) + one ContentType (13 bytes: 1 id + 2 LP +
        // 10 UTF-8 "text/plain") = 18 property body bytes, 1-byte
        // var-int prefix.
        // body = 2 (topic LP) + 3 (topic) + 2 (pid)
        //      + 1 (propLen=18) + 18 (props) + 4+1 (jpeg) = 31
        val codec = MqttV5PacketCodec(JpegImageCodec)
        val msg =
            MqttV5Packet.Publish<JpegImage>(
                header = MqttFixedHeader(0x32u), // QoS=1
                topic = "t/1",
                packetId = PacketId(0x002Au),
                properties =
                    V5PropertyBag.of(
                        MqttV5Property.MessageExpiryInterval(seconds = 0x01020304u),
                        MqttV5Property.ContentType(value = "text/plain"),
                    ),
                payload = JpegImage(1u, 1u, byteArrayOf(0x42)),
            )
        val buf = codec.encode(msg, EncodeContext.Empty, BufferFactory.Default)
        val actual = buf.readByteArray(buf.remaining())
        val expected =
            byteArrayOf(
                0x32, // fixed header (type=3, QoS=1)
                0x1F, // remaining length = 31
                0x00,
                0x03,
                't'.code.toByte(),
                '/'.code.toByte(),
                '1'.code.toByte(),
                0x00,
                0x2A, // packet id = 42
                0x12, // properties length = 18 (1-byte var-int)
                0x02, // MessageExpiryInterval id
                0x01,
                0x02,
                0x03,
                0x04, // expiry seconds (BE UInt)
                0x03, // ContentType id
                0x00,
                0x0A, // length prefix = 10
                't'.code.toByte(),
                'e'.code.toByte(),
                'x'.code.toByte(),
                't'.code.toByte(),
                '/'.code.toByte(),
                'p'.code.toByte(),
                'l'.code.toByte(),
                'a'.code.toByte(),
                'i'.code.toByte(),
                'n'.code.toByte(),
                0x00,
                0x01,
                0x00,
                0x01,
                0x42, // jpeg payload
            )
        assertContentEquals(expected, actual)
    }

    @Test
    fun roundTripsPublishWithEmptyPropertyBagAtQos0() {
        val codec = MqttV5PacketCodec(JpegImageCodec)
        val original =
            MqttV5Packet.Publish<JpegImage>(
                header = MqttFixedHeader(0x30u),
                // body = 2 (topic LP) + 12 (topic) + 1 (propLen=0) + 4+8 (jpeg)
                topic = "sensors/jpeg",
                packetId = null,
                properties = V5PropertyBag.EMPTY,
                payload = JpegImage(320u, 240u, ByteArray(8) { (it * 5).toByte() }),
            )
        val buf = codec.encode(original, EncodeContext.Empty, BufferFactory.Default)
        val decoded = codec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripsPublishWithPropertiesAtQos1() {
        val codec = MqttV5PacketCodec(JpegImageCodec)
        val original =
            MqttV5Packet.Publish<JpegImage>(
                header = MqttFixedHeader(0x32u),
                // body = 2 + 12 + 2 + 1 (propLen=18) + 18 (props) + 4+8 = 47
                topic = "sensors/jpeg",
                packetId = PacketId(0x0042u),
                properties =
                    V5PropertyBag.of(
                        MqttV5Property.MessageExpiryInterval(seconds = 3_600u),
                        MqttV5Property.ContentType(value = "text/plain"),
                    ),
                payload = JpegImage(320u, 240u, ByteArray(8) { (it * 5).toByte() }),
            )
        val buf = codec.encode(original, EncodeContext.Empty, BufferFactory.Default)
        val decoded = codec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun decodesPublishWithMixedPropertiesIntoTypedSlots() {
        // Spec §3.3.2.3 — properties may appear in any order on the wire.
        // Routes each unique-cardinality variant into its typed
        // V5PropertyBag slot regardless of wire arrival order. Wire here is
        // ContentType first (13 bytes), then MessageExpiryInterval (5
        // bytes); both must end up in their respective typed slots.
        // body = 2 (topic LP) + 1 (topic 't') + 1 (propLen=18) + 18 (props)
        //      + 4+1 (jpeg) = 27
        val codec = MqttV5PacketCodec(JpegImageCodec)
        val wire =
            byteArrayOf(
                0x30,
                0x1B, // RL = 27
                0x00,
                0x01,
                't'.code.toByte(),
                0x12, // properties length = 18
                0x03, // ContentType id
                0x00,
                0x0A,
                't'.code.toByte(),
                'e'.code.toByte(),
                'x'.code.toByte(),
                't'.code.toByte(),
                '/'.code.toByte(),
                'p'.code.toByte(),
                'l'.code.toByte(),
                'a'.code.toByte(),
                'i'.code.toByte(),
                'n'.code.toByte(),
                0x02,
                0x00,
                0x00,
                0x00,
                0x60, // expiry = 96
                0x00,
                0x01,
                0x00,
                0x01,
                0x99.toByte(),
            )
        val buf = BufferFactory.Default.allocate(wire.size, ByteOrder.BIG_ENDIAN).also { it.writeBytes(wire) }
        buf.resetForRead()
        val decoded = assertIs<MqttV5Packet.Publish<JpegImage>>(codec.decode(buf, DecodeContext.Empty))
        val contentType = assertNotNull(decoded.properties.contentType)
        assertEquals("text/plain", contentType.value)
        val expiry = assertNotNull(decoded.properties.messageExpiryInterval)
        assertEquals(96u, expiry.seconds)
    }

    @Test
    fun publishCompleteRestoresOuterLimitFromPartialFlow() {
        // The Partial+@RemainingLength composition extended with
        // step 11's inner property-bag bound. Both bounds must be restored
        // before the outer caller continues; trailing bytes must remain
        // visible after decode.
        val codec = MqttV5PacketCodec(JpegImageCodec)
        val original =
            MqttV5Packet.Publish<JpegImage>(
                header = MqttFixedHeader(0x32u),
                // body = 2 (topic LP) + 1 (topic 't') + 2 (pid)
                //      + 1 (propLen=5) + 5 (one MessageExpiryInterval) + 4+1 (jpeg)
                topic = "t",
                packetId = PacketId(0x0007u),
                properties = V5PropertyBag.of(MqttV5Property.MessageExpiryInterval(seconds = 7u)),
                payload = JpegImage(1u, 1u, byteArrayOf(0x42)),
            )
        val encoded = codec.encode(original, EncodeContext.Empty, BufferFactory.Default)
        val publishBytes = encoded.remaining()
        // Materialize publish bytes + trailing bytes the dispatcher must NOT consume.
        val buf = BufferFactory.Default.allocate(publishBytes + 2, ByteOrder.BIG_ENDIAN)
        buf.writeBytes(encoded.readByteArray(publishBytes))
        buf.writeByte(0xCA.toByte())
        buf.writeByte(0xFE.toByte())
        buf.resetForRead()
        val outerLimitBefore = buf.limit()
        val decoded = codec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
        assertEquals(publishBytes, buf.position(), "decode advanced exactly through the publish")
        assertEquals(outerLimitBefore, buf.limit(), "outer limit restored after RL-bounded decode")
    }

    @Test
    fun peekFrameSizeForPublishCompletes() {
        // Peek through a complete publish frame in one append.
        val codec = MqttV5PacketCodec(JpegImageCodec)
        val msg =
            MqttV5Packet.Publish<JpegImage>(
                header = MqttFixedHeader(0x30u),
                topic = "t/1",
                packetId = null,
                properties = V5PropertyBag.EMPTY,
                payload = JpegImage(1u, 1u, byteArrayOf(0x42)),
            )
        val buf = codec.encode(msg, EncodeContext.Empty, BufferFactory.Default)
        val totalBytes = buf.remaining()

        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            stream.append(buf)
            assertEquals(PeekResult.Complete(totalBytes), codec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }
}
