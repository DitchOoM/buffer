package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImage
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImageCodec
import com.ditchoom.buffer.codec.test.protocols.payload.PacketId
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayload
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayloadCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Stage H slice 10d.5 doctrine vector — `decodeAggregating(buffer,
 * context, on<Variant> = ...)` companion-object overload on the
 * generic dispatcher.
 *
 * What the aggregator adds over `decode(buffer, context)`:
 *
 *   - **Per-call codec selection.** The standard `decode` uses the
 *     dispatcher's constructor-injected `payloadCodec` for every
 *     payload-bearing variant. The aggregator lets the consumer
 *     supply a `(VariantCodec.Partial<P>) -> Variant<P>` lambda
 *     per payload-bearing variant; the lambda calls
 *     `partial.complete(theirOwnCodec)` choosing the codec at the
 *     call site (the topic-keyed dispatch use case).
 *   - **No instance required.** The aggregator lives on the
 *     dispatcher class's companion object — consumers call
 *     `MqttPacketCodec.decodeAggregating<P>(...)` without first
 *     constructing the dispatcher class. The aggregator never
 *     invokes the constructor-injected `payloadCodec`.
 *   - **Throwing-default lambdas.** Each payload-bearing variant's
 *     lambda defaults to a `DecodeException` with `fieldPath =
 *     "<Parent>.<Variant>.handler"`. Consumers override only the
 *     variants they expect; un-overridden payload-bearing variants
 *     throw at lambda invocation (i.e., when an unexpected variant
 *     arrives), not at dispatcher construction.
 *
 * `<Nothing>`-typed variants (Connect, PingReq, PingResp,
 * Disconnect) take the standard codec dispatch unchanged — they
 * have no codec to defer.
 */
class MqttPacketAggregatorCodecTest {
    @Test
    fun aggregatorRoundTripsPublishWithSuppliedHandler() {
        // Encode a Publish via the standard dispatcher; decode via
        // the aggregator with a per-call onPublish handler that
        // selects JpegImageCodec at the call site (rather than
        // baking it into the dispatcher's constructor).
        val publishCodec = MqttPacketCodec(JpegImageCodec)
        val original =
            MqttPacket.Publish<JpegImage>(
                header = MqttFixedHeader(0x32u),
                remainingLength = 13u, // 2 + 1 (topic) + 2 (pid) + 4 + 4 (jpeg)
                topic = "x",
                packetId = PacketId(42u),
                payload = JpegImage(1u, 1u, byteArrayOf(0x10, 0x20, 0x30, 0x40)),
            )
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        publishCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        val decoded =
            MqttPacketCodec.decodeAggregating<JpegImage>(
                buf,
                DecodeContext.Empty,
                onPublish = { partial ->
                    // Per-call codec selection: the lambda picks
                    // JpegImageCodec rather than the dispatcher
                    // pinning it at construction.
                    partial.complete(JpegImageCodec)
                },
            )
        assertEquals(original, decoded)
    }

    @Test
    fun aggregatorAllowsHeaderInspectionBeforePayloadDecode() {
        // The aggregator's lambda receives a Partial — the consumer
        // can inspect headers before deciding the payload codec.
        // This is the topic-keyed dispatch shape that motivates
        // slice 10d.5 (acceptance #4 in PHASE_9_RESET §Stage H).
        val publishCodec = MqttPacketCodec(JpegImageCodec)
        val original =
            MqttPacket.Publish<JpegImage>(
                header = MqttFixedHeader(0x32u),
                remainingLength = 22u, // 2 + 11 (topic) + 2 (pid) + 4 + 3 (jpeg)
                topic = "topic/foo/1",
                packetId = PacketId(7u),
                payload = JpegImage(2u, 3u, byteArrayOf(0x11, 0x22, 0x33)),
            )
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        publishCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()

        var observedTopic: String? = null
        val decoded =
            MqttPacketCodec.decodeAggregating<JpegImage>(
                buf,
                DecodeContext.Empty,
                onPublish = { partial ->
                    observedTopic = partial.topic
                    partial.complete(JpegImageCodec)
                },
            )
        assertEquals("topic/foo/1", observedTopic, "headers visible to lambda before payload decode")
        assertEquals(original, decoded)
    }

    @Test
    fun aggregatorThrowsWhenPublishHandlerOmitted() {
        // The default onPublish lambda throws DecodeException with
        // field-path attribution per row 17. Consumers that only
        // expect Connect/PingReq/etc. don't supply onPublish; an
        // unexpected Publish arrival surfaces with the missing-
        // handler error instead of silently routing through some
        // fallback.
        val publishCodec = MqttPacketCodec(JpegImageCodec)
        val original =
            MqttPacket.Publish<JpegImage>(
                header = MqttFixedHeader(0x32u),
                remainingLength = 13u,
                topic = "x",
                packetId = PacketId(42u),
                payload = JpegImage(1u, 1u, byteArrayOf(0x10, 0x20, 0x30, 0x40)),
            )
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        publishCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()

        val ex =
            assertFailsWith<DecodeException> {
                // No onPublish supplied — the throwing default fires.
                MqttPacketCodec.decodeAggregating<JpegImage>(buf, DecodeContext.Empty)
            }
        assertEquals("MqttPacket.Publish.handler", ex.fieldPath)
        assertEquals("consumer-supplied Publish handler", ex.expected)
        assertEquals("no handler supplied", ex.actual)
    }

    @Test
    fun aggregatorRoutesNothingVariantsWithoutInvokingLambdaPath() {
        // Connect/PingReq/etc. take the standard codec dispatch
        // unchanged — they have no codec to defer. The consumer
        // supplies no onPublish (default throws), and routing a
        // payload-free variant must NOT trigger the default.
        val publishCodec = MqttPacketCodec(JpegImageCodec)
        val original =
            MqttPacket.Connect(
                header = MqttFixedHeader(0x10u),
                remainingLength = 8u,
                keepAliveSeconds = 60u,
                clientId = "abcd",
            )
        val buf = BufferFactory.Default.allocate(64)
        publishCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()

        // No onPublish — would throw if Publish arrived; Connect
        // routes through the standard <Nothing>-variant path.
        val decoded = MqttPacketCodec.decodeAggregating<JpegImage>(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun aggregatorAcceptsArbitraryPayloadCodecPerCall() {
        // Same dispatcher class, two different payload codecs
        // selected per call. The aggregator's `<P : Payload>` is
        // a function-level type variable — bind it differently per
        // call without re-instantiating any dispatcher.
        val jpegPublish =
            MqttPacket.Publish<JpegImage>(
                header = MqttFixedHeader(0x32u),
                remainingLength = 13u,
                topic = "x",
                packetId = PacketId(1u),
                payload = JpegImage(1u, 1u, byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())),
            )
        val textPublish =
            MqttPacket.Publish<TextPayload>(
                header = MqttFixedHeader(0x32u),
                remainingLength = 23u, // 2 + 1 (topic) + 2 (pid) + 18 (text "hello, slice 10d.5")
                topic = "y",
                packetId = PacketId(2u),
                payload = TextPayload("hello, slice 10d.5"),
            )

        val jpegBuf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        MqttPacketCodec(JpegImageCodec).encode(jpegBuf, jpegPublish, EncodeContext.Empty)
        jpegBuf.resetForRead()
        val jpegDecoded =
            MqttPacketCodec.decodeAggregating<JpegImage>(
                jpegBuf,
                DecodeContext.Empty,
                onPublish = { it.complete(JpegImageCodec) },
            )
        assertEquals(jpegPublish, jpegDecoded)

        val textBuf = BufferFactory.Default.allocate(128, ByteOrder.BIG_ENDIAN)
        MqttPacketCodec(TextPayloadCodec).encode(textBuf, textPublish, EncodeContext.Empty)
        textBuf.resetForRead()
        val textDecoded =
            MqttPacketCodec.decodeAggregating<TextPayload>(
                textBuf,
                DecodeContext.Empty,
                onPublish = { it.complete(TextPayloadCodec) },
            )
        assertEquals(textPublish, textDecoded)
    }

    @Test
    fun aggregatorPropagatesUnknownDiscriminatorThrow() {
        // Unknown discriminator (type=5, PUBACK — not in our
        // sealed set) throws the same DecodeException as the
        // standard dispatcher. The aggregator's catch-all `else`
        // branch matches the standard dispatcher's row-17
        // attribution.
        val buf = BufferFactory.Default.allocate(1).also { it.writeByte(0x50.toByte()) }
        buf.resetForRead()
        val ex =
            assertFailsWith<DecodeException> {
                MqttPacketCodec.decodeAggregating<JpegImage>(buf, DecodeContext.Empty)
            }
        assertEquals("MqttPacket.discriminator", ex.fieldPath)
    }

    @Test
    fun aggregatorReturnTypeIsParentNotVariant() {
        // The aggregator's overall return type is `MqttPacket<P>`
        // (the parent), even though each lambda returns the
        // matched variant. Confirm the call site assigns to a
        // parent-typed variable without a cast (Kotlin compiler
        // assignment) and downcasts cleanly via assertIs.
        val publishCodec = MqttPacketCodec(JpegImageCodec)
        val original =
            MqttPacket.Publish<JpegImage>(
                header = MqttFixedHeader(0x32u),
                remainingLength = 13u,
                topic = "x",
                packetId = PacketId(99u),
                payload = JpegImage(1u, 1u, byteArrayOf(0x01, 0x02, 0x03, 0x04)),
            )
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        publishCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()

        val decoded: MqttPacket<JpegImage> =
            MqttPacketCodec.decodeAggregating<JpegImage>(
                buf,
                DecodeContext.Empty,
                onPublish = { it.complete(JpegImageCodec) },
            )
        // Downcast cleanly via Kotlin's smart cast / assertIs.
        val publish = assertIs<MqttPacket.Publish<JpegImage>>(decoded)
        assertEquals(original, publish)
    }
}
