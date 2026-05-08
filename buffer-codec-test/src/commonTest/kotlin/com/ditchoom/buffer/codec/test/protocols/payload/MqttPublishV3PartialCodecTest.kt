package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Doctrine vector — `Partial` decode pattern over
 * the (`MqttPublishV3Concrete`) and
 * (`MqttPublishV3<P>`) data classes already in this package.
 *
 * What `Partial` adds over the existing `decode`:
 * ** `Partial`** lets a consumer decode the headers
 *     (header / topic / packetId), inspect them, and only then
 *     complete the payload via the `@UseCodec`-pinned codec.
 * ** `Partial`** lets a consumer decode the headers
 *     **without instantiating the surrounding generic codec class**
 *     (`MqttPublishV3Codec(...)`), then choose the payload codec
 *     at the call site by passing it to `complete(payloadCodec)`.
 *     This is the shape `:buffer-flow`'s acceptance #4 needs to
 *     thread a topic-keyed payload decoder through the dispatcher
 *     without committing to a payload type at the type-erased
 *     `Connection<MqttControlPacket<…>>` boundary.
 *
 * Trust contract (documented on the generated `Partial` class):
 *   - `Partial` captures the buffer + context references.
 *   - The consumer must call `complete(...)` before any external
 *     buffer mutation (position / limit changes, release).
 *   - `complete(...)` is single-use; calling it twice would
 *     re-decode against an advanced position.
 */
class MqttPublishV3PartialCodecTest {
    @Test
    fun slice10aPartialDecodesHeadersThenCompletes() {
        val original =
            MqttPublishV3Concrete(
                header = MqttFixedHeader(0x30u),
                topic = "sensors/jpeg",
                packetId = PacketId(0x0042u),
                payload = JpegImage(320u, 240u, ByteArray(16) { (it * 3).toByte() }),
            )
        val buf = BufferFactory.Default.allocate(128, ByteOrder.BIG_ENDIAN)
        MqttPublishV3ConcreteCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        val partial = MqttPublishV3ConcreteCodec.partial(buf, DecodeContext.Empty)
        // Headers are accessible without committing to the payload yet.
        assertEquals(MqttFixedHeader(0x30u), partial.header)
        assertEquals("sensors/jpeg", partial.topic)
        assertEquals(PacketId(0x0042u), partial.packetId)
        // Complete consumes the rest of the buffer through JpegImageCodec.
        val full = partial.complete()
        assertEquals(original, full)
    }

    @Test
    fun slice10aPartialCompleteRespectsCallerLimit() {
        // Same setup as MqttPublishV3ConcreteCodecTest.decodeRespectsCallerLimitForPayloadRegion
        // but routed through Partial — the caller-set limit must bind the
        // payload region during complete(), not just during partial().
        val original =
            MqttPublishV3Concrete(
                header = MqttFixedHeader(0x30u),
                topic = "t",
                packetId = PacketId(1u),
                payload = JpegImage(2u, 3u, byteArrayOf(0x11, 0x22, 0x33)),
            )
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        MqttPublishV3ConcreteCodec.encode(buf, original, EncodeContext.Empty)
        val publishBytes = buf.position()
        buf.writeByte(0xCA.toByte())
        buf.writeByte(0xFE.toByte())
        buf.resetForRead()
        buf.setLimit(publishBytes)
        val partial = MqttPublishV3ConcreteCodec.partial(buf, DecodeContext.Empty)
        val full = partial.complete()
        assertEquals(original, full)
        assertEquals(publishBytes, buf.position())
    }

    @Test
    fun slice10bPartialDoesNotRequireSurroundingCodecInstance() {
        // The whole point of 's Partial: no need to construct
        // MqttPublishV3Codec(somePayloadCodec) just to decode the headers.
        // The static `partial<P>(...)` entry is on the codec class's
        // companion and takes its own type parameter.
        val original =
            MqttPublishV3<JpegImage>(
                header = MqttFixedHeader(0x30u),
                topic = "sensors/jpeg",
                packetId = PacketId(0x0042u),
                payload = JpegImage(8u, 8u, byteArrayOf(1, 2, 3, 4)),
            )
        // Encode through a fully instantiated codec (encode side is unchanged).
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        MqttPublishV3Codec(JpegImageCodec).encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        // Decode headers WITHOUT instantiating MqttPublishV3Codec.
        val partial = MqttPublishV3Codec.partial<JpegImage>(buf, DecodeContext.Empty)
        assertEquals("sensors/jpeg", partial.topic)
        // Complete with a codec chosen at the call site.
        val full = partial.complete(JpegImageCodec)
        assertEquals(original, full)
    }

    @Test
    fun slice10bPartialAcceptsArbitraryPayloadCodecAtCompleteTime() {
        // The same Partial-decode shape works for any Codec<P> the
        // consumer plumbs in — this is exactly the per-call codec
        // selection 's Partial unlocks for the:buffer-flow
        // smoke test.
        val original =
            MqttPublishV3<TextPayload>(
                header = MqttFixedHeader(0x30u),
                topic = "sensors/text",
                packetId = PacketId(0x0007u),
                payload = TextPayload("hello, slice 10c"),
            )
        val buf = BufferFactory.Default.allocate(128, ByteOrder.BIG_ENDIAN)
        MqttPublishV3Codec(TextPayloadCodec).encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        val partial = MqttPublishV3Codec.partial<TextPayload>(buf, DecodeContext.Empty)
        val full = partial.complete(TextPayloadCodec)
        assertEquals(original, full)
    }

    @Test
    fun slice10bPartialCompleteRespectsCallerLimit() {
        val original =
            MqttPublishV3<JpegImage>(
                header = MqttFixedHeader(0x30u),
                topic = "t",
                packetId = PacketId(1u),
                payload = JpegImage(2u, 3u, byteArrayOf(0x11, 0x22)),
            )
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        MqttPublishV3Codec(JpegImageCodec).encode(buf, original, EncodeContext.Empty)
        val publishBytes = buf.position()
        buf.writeByte(0xFE.toByte())
        buf.writeByte(0xED.toByte())
        buf.resetForRead()
        buf.setLimit(publishBytes)
        val partial = MqttPublishV3Codec.partial<JpegImage>(buf, DecodeContext.Empty)
        val full = partial.complete(JpegImageCodec)
        assertEquals(original, full)
        assertEquals(publishBytes, buf.position())
    }
}
