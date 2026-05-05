package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Stage H slice 10b doctrine vector — generic-bounded
 * `MqttPublishV3<P : Payload>` with a constructor-injected
 * `Codec<P>`. Validates:
 *
 *   1. Generic codec emits as `class MqttPublishV3Codec<P : Payload>(
 *      private val payloadCodec: Codec<P>) : Codec<MqttPublishV3<P>>`
 *      and instantiates per payload type at the call site.
 *   2. The generic codec accepts arbitrary `Codec<P>`: same wire
 *      format with two distinct payload types (`JpegImage` and
 *      `TextPayload`) confirms it isn't accidentally JpegImage-
 *      specific.
 *   3. Wire bytes match the slice 10a concrete vector for the
 *      JpegImage instantiation — both shapes encode the same wire
 *      format; only the codec emit path differs.
 *   4. Round-trip succeeds for both payload types.
 */
class MqttPublishV3GenericCodecTest {
    @Test
    fun jpegInstantiationByteExactMatchesConcreteVector() {
        // Wire bytes must match MqttPublishV3ConcreteCodecTest's
        // `encodesByteExactWireFormat` exactly — slice 10a concrete
        // and slice 10b generic emit the same wire format; only the
        // codec emit shape differs.
        val msg =
            MqttPublishV3<JpegImage>(
                header = MqttFixedHeader(0x30u),
                topic = "a/b",
                packetId = PacketId(0x1234u),
                payload =
                    JpegImage(
                        width = 4u,
                        height = 8u,
                        data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
                    ),
            )
        val codec = MqttPublishV3Codec(JpegImageCodec)
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        codec.encode(buf, msg, EncodeContext.Empty)
        buf.resetForRead()
        val actual = buf.readByteArray(buf.remaining())
        val expected =
            byteArrayOf(
                0x30,
                0x00,
                0x03,
                'a'.code.toByte(),
                '/'.code.toByte(),
                'b'.code.toByte(),
                0x12,
                0x34,
                0x00,
                0x04,
                0x00,
                0x08,
                0xDE.toByte(),
                0xAD.toByte(),
                0xBE.toByte(),
                0xEF.toByte(),
            )
        assertContentEquals(expected, actual)
    }

    @Test
    fun roundTripsJpegPayload() {
        val codec = MqttPublishV3Codec(JpegImageCodec)
        val original =
            MqttPublishV3<JpegImage>(
                header = MqttFixedHeader(0x30u),
                topic = "sensors/jpeg",
                packetId = PacketId(0x0042u),
                payload = JpegImage(320u, 240u, ByteArray(32) { (it * 5).toByte() }),
            )
        val buf = BufferFactory.Default.allocate(128, ByteOrder.BIG_ENDIAN)
        codec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(original, codec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun roundTripsTextPayload() {
        // Same generic class, different payload type. The codec's
        // generic-ness is meaningful only if it accepts arbitrary
        // Codec<P>, not just JpegImageCodec.
        val codec = MqttPublishV3Codec(TextPayloadCodec)
        val original =
            MqttPublishV3<TextPayload>(
                header = MqttFixedHeader(0x30u),
                topic = "sensors/text",
                packetId = PacketId(0x0007u),
                payload = TextPayload("hello, slice 10b"),
            )
        val buf = BufferFactory.Default.allocate(128, ByteOrder.BIG_ENDIAN)
        codec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(original, codec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun decodeRespectsCallerLimit() {
        val codec = MqttPublishV3Codec(JpegImageCodec)
        val original =
            MqttPublishV3<JpegImage>(
                header = MqttFixedHeader(0x30u),
                topic = "t",
                packetId = PacketId(1u),
                payload = JpegImage(2u, 3u, byteArrayOf(0x11, 0x22)),
            )
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        codec.encode(buf, original, EncodeContext.Empty)
        val publishBytes = buf.position()
        // Trailing bytes that the publish decode must NOT read.
        buf.writeByte(0xFE.toByte())
        buf.writeByte(0xED.toByte())
        buf.resetForRead()
        buf.setLimit(publishBytes)
        val decoded = codec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
        assertEquals(publishBytes, buf.position())
    }

    @Test
    fun wireSizeIsBackPatchForGeneric() {
        val codec = MqttPublishV3Codec(JpegImageCodec)
        val msg =
            MqttPublishV3<JpegImage>(
                header = MqttFixedHeader(0x30u),
                topic = "x",
                packetId = PacketId(0u),
                payload = JpegImage(0u, 0u, ByteArray(0)),
            )
        // Same conservative-BackPatch contract as slice 10a — the
        // user codec might be Exact or BackPatch; we don't optimize
        // the runtime cast in slice 10b.
        assertEquals(WireSize.BackPatch, codec.wireSize(msg, EncodeContext.Empty))
    }
}
