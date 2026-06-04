package com.ditchoom.buffer.codec.test.protocols.simplegeneric

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayload
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayloadCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Round-trip + wire-format coverage for a generic payload under a **simple
 * `@PacketType`** dispatcher (no `@DispatchOn`) — the `Generic × FixedByte`
 * combination that previously emitted broken monomorphic code (issue #176 /
 * SUPPORT_MATRIX backlog #4/#6) and now generates
 * `class SimpleGenericFrameCodec<P>(payloadCodec)`.
 *
 * The checks prove the generated generic dispatcher actually works at
 * runtime, not just that it compiles:
 *   1. the generic [Command] variant round-trips through the
 *      constructor-injected `payloadCodec` after the dispatcher consumes the
 *      discriminator byte;
 *   2. the non-generic [Status] variant (extending `<Nothing>`) round-trips
 *      via its static codec ref;
 *   3. the discriminator byte is written/consumed by the dispatcher
 *      (FixedByte ownership) — asserted on the wire;
 *   4. dispatch routes to the correct variant;
 *   5. `wireSize` aggregates the discriminator byte (`Status → Exact(2)`).
 */
class SimpleGenericFrameCodecTest {
    private val codec = SimpleGenericFrameCodec(TextPayloadCodec)

    @Test
    fun commandRoundTripsThroughInjectedPayloadCodec() {
        val original =
            SimpleGenericFrame.Command(
                counter = 0x42u,
                payload = TextPayload("hi"),
            )
        val buffer = BufferFactory.Default.allocate(64)
        codec.encode(buffer, original, EncodeContext.Empty)
        // 0A (discriminator) + 42 (counter) + 68 69 ("hi") = 4 bytes.
        assertEquals(4, buffer.position(), "encoded byte count")
        buffer.resetForRead()
        assertEquals(0x0A, buffer.readUByte().toInt(), "discriminator byte (consumed by dispatcher)")
        assertEquals(0x42, buffer.readUByte().toInt(), "counter")
        assertEquals(0x68, buffer.readUByte().toInt(), "payload[0] = 'h'")
        assertEquals(0x69, buffer.readUByte().toInt(), "payload[1] = 'i'")
        buffer.resetForRead()
        val decoded = codec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun statusVariantRoundTrips() {
        val original = SimpleGenericFrame.Status(code = 0x7Fu)
        val buffer = BufferFactory.Default.allocate(64)
        codec.encode(buffer, original, EncodeContext.Empty)
        assertEquals(2, buffer.position(), "encoded byte count")
        buffer.resetForRead()
        assertEquals(0xA0, buffer.readUByte().toInt(), "discriminator byte")
        assertEquals(0x7F, buffer.readUByte().toInt(), "code")
        buffer.resetForRead()
        val decoded = codec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun dispatchRoutesToCorrectVariant() {
        val command =
            SimpleGenericFrame.Command(counter = 0x01u, payload = TextPayload("payload"))
        val buffer = BufferFactory.Default.allocate(64)
        codec.encode(buffer, command, EncodeContext.Empty)
        buffer.resetForRead()
        assertIs<SimpleGenericFrame.Command<TextPayload>>(codec.decode(buffer, DecodeContext.Empty))

        val status = SimpleGenericFrame.Status(code = 0x10u)
        val statusBuffer = BufferFactory.Default.allocate(64)
        codec.encode(statusBuffer, status, EncodeContext.Empty)
        statusBuffer.resetForRead()
        assertIs<SimpleGenericFrame.Status>(codec.decode(statusBuffer, DecodeContext.Empty))
    }

    @Test
    fun wireSizeAggregatesDiscriminatorByte() {
        // Status body is a single UByte; the dispatcher adds the discriminator
        // byte → Exact(2). Command carries @RemainingBytes payload → BackPatch.
        assertEquals(
            WireSize.Exact(2),
            codec.wireSize(SimpleGenericFrame.Status(code = 0u), EncodeContext.Empty),
        )
        assertEquals(
            WireSize.BackPatch,
            codec.wireSize(
                SimpleGenericFrame.Command(counter = 0u, payload = TextPayload("x")),
                EncodeContext.Empty,
            ),
        )
    }
}
