package com.ditchoom.buffer.codec.test.protocols.deferredpayload

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayload
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayloadCodec
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Issue #293 — a deferred payload sized by a sibling `@LengthFrom`.
 *
 * The load-bearing assertion is [peekFrameSizeCompletesOnceTheBodyArrives]:
 * before #293 this exact shape returned `PeekResult.NoFraming`, because the
 * emitter decided framability from the payload field's *decode strategy*
 * (codec-deferred → unframable) rather than from whether a byte count was
 * on the wire. The wire format never changed; only the taxonomy did.
 */
class SmpFrameCodecTest {
    private val headerBytes = 8

    @Test
    fun roundTripsThroughTheSiblingLength() {
        val payload = TextPayload("hi")
        val frame = frameFor(payload)
        val buf = encode(frame)
        assertEquals(headerBytes + 2, buf.position(), "header + exactly payloadLength body bytes")
        buf.resetForRead()
        assertEquals(frame, SmpFrameCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun roundTripsMultiByteUtf8Body() {
        val payload = TextPayload("héllo")
        val frame = frameFor(payload)
        val buf = encode(frame)
        buf.resetForRead()
        assertEquals(frame, SmpFrameCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun roundTripsEmptyBody() {
        val frame = frameFor(TextPayload(""))
        val buf = encode(frame)
        assertEquals(headerBytes, buf.position(), "no body bytes when payloadLength is 0")
        buf.resetForRead()
        assertEquals(frame, SmpFrameCodec.decode(buf, DecodeContext.Empty))
    }

    /**
     * The point of the issue. `peekFrameSize` walks the fixed header, reads
     * `payloadLength` out of the stream without consuming it, and reports the
     * total — so a stream loop can size the frame before buffering it.
     */
    @Test
    fun peekFrameSizeCompletesOnceTheBodyArrives() {
        val pool = BufferPool()
        val original = frameFor(TextPayload("hi"))
        val encoded = encode(original).also { it.resetForRead() }
        val totalBytes = encoded.remaining()
        assertEquals(headerBytes + 2, totalBytes)

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, SmpFrameCodec.peekFrameSize(stream))
            for (i in 0 until totalBytes - 1) {
                stream.append(singleByte(encoded.readByte()))
                assertEquals(
                    PeekResult.NeedsMoreData,
                    SmpFrameCodec.peekFrameSize(stream),
                    "after ${i + 1} of $totalBytes bytes",
                )
            }
            stream.append(singleByte(encoded.readByte()))
            assertEquals(PeekResult.Complete(totalBytes), SmpFrameCodec.peekFrameSize(stream))

            val decoded =
                stream.readBufferScoped(totalBytes) { SmpFrameCodec.decode(this, DecodeContext.Empty) }
            assertEquals(original, decoded)
            assertEquals(0, stream.available(), "frame consumed exactly")
        } finally {
            stream.release()
            pool.clear()
        }
    }

    /** Two frames back to back — peek must size the first without reading into the second. */
    @Test
    fun peekFrameSizeFramesBackToBackMessages() {
        val pool = BufferPool()
        val first = frameFor(TextPayload("one"))
        val second = frameFor(TextPayload("second"))
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            for (frame in listOf(first, second)) {
                val encoded = encode(frame).also { it.resetForRead() }
                stream.append(encoded)
            }
            val firstSize = headerBytes + 3
            assertEquals(PeekResult.Complete(firstSize), SmpFrameCodec.peekFrameSize(stream))
            assertEquals(
                first,
                stream.readBufferScoped(firstSize) { SmpFrameCodec.decode(this, DecodeContext.Empty) },
            )
            val secondSize = headerBytes + 6
            assertEquals(PeekResult.Complete(secondSize), SmpFrameCodec.peekFrameSize(stream))
            assertEquals(
                second,
                stream.readBufferScoped(secondSize) { SmpFrameCodec.decode(this, DecodeContext.Empty) },
            )
            assertEquals(0, stream.available())
        } finally {
            stream.release()
            pool.clear()
        }
    }

    /**
     * The sibling bound must win over the buffer's limit. A payload codec that
     * reads `remaining()` would swallow the trailing fields if the region were
     * not narrowed to `payloadLength`.
     */
    @Test
    fun payloadIsBoundedToTheSiblingLengthNotTheBufferLimit() {
        val original =
            SmpFrameWithTrailer(
                payloadLength = 2u,
                payload = TextPayload("hi"),
                checksum = 0xBEEFu,
                note = "trailing",
            )
        val buf = buildBuffer { SmpFrameWithTrailerCodec.encode(it, original, EncodeContext.Empty) }
        buf.resetForRead()
        val decoded = SmpFrameWithTrailerCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
        assertEquals(TextPayload("hi"), decoded.payload, "payload stopped at payloadLength")
    }

    /**
     * Strict consumption. A runtime-supplied codec that stops short has
     * desynchronised everything after it, so the generated decode rejects
     * rather than trusting it — the `@LengthFrom @ProtocolMessage` path's
     * trust-and-restore is not extended to arbitrary user codecs.
     */
    @Test
    fun underReadingPayloadCodecIsRejected() {
        val buf = BufferFactory.Default.allocate(32)
        buf.writeUShort(4u)
        buf.writeString("abcd", Charset.UTF8)
        buf.resetForRead()
        val failure =
            assertFailsWith<DecodeException> {
                ShortReadFrameCodec.decode(buf, DecodeContext.Empty)
            }
        assertEquals("ShortReadFrame.payload", failure.fieldPath)
        assertTrue(
            failure.actual.contains("1 bytes left unread"),
            "diagnostic names the shortfall, was: ${failure.actual}",
        )
    }

    @Test
    fun genericFrameRoundTripsWithAnInjectedCodec() {
        val codec = SmpGenericFrameCodec(TextPayloadCodec)
        val original =
            SmpGenericFrame(
                op = 0u,
                flags = 0u,
                payloadLength = 2u,
                group = 9u,
                sequence = 1u,
                commandId = 3u,
                payload = TextPayload("hi"),
            )
        val buf = buildBuffer { codec.encode(it, original, EncodeContext.Empty) }
        buf.resetForRead()
        assertEquals(original, codec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun genericFramePeeksWithoutTheInjectedCodec() {
        val pool = BufferPool()
        val codec = SmpGenericFrameCodec(TextPayloadCodec)
        val original =
            SmpGenericFrame(
                op = 0u,
                flags = 0u,
                payloadLength = 3u,
                group = 9u,
                sequence = 1u,
                commandId = 3u,
                payload = TextPayload("abc"),
            )
        val encoded = buildBuffer { codec.encode(it, original, EncodeContext.Empty) }
        encoded.resetForRead()
        val totalBytes = encoded.remaining()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            stream.append(encoded)
            // Framing comes from the wire, not from the payload codec — peek
            // never runs it, which is exactly why the shape is framable.
            assertEquals(PeekResult.Complete(totalBytes), codec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    // ---- partial / complete deferral --------------------------------------

    /**
     * The header/payload split the issue is really about: `partial()` decodes
     * the envelope, the caller picks a `Codec<P>` from what it sees there, and
     * `complete()` decodes the body. Two actors, two times.
     *
     * This is also the migration guarantee. The same message with
     * `@RemainingBytes val payload: P` already emits a `Partial` (issue #168 /
     * #171), so moving to `@LengthFrom` must not take it away.
     */
    @Test
    fun partialExposesTheHeaderAndDefersThePayload() {
        val original =
            SmpGenericFrame(
                op = 0u,
                flags = 0u,
                payloadLength = 2u,
                group = 9u,
                sequence = 1u,
                commandId = 3u,
                payload = TextPayload("hi"),
            )
        val codec = SmpGenericFrameCodec(TextPayloadCodec)
        val buf = buildBuffer { codec.encode(it, original, EncodeContext.Empty) }
        buf.resetForRead()

        val partial = SmpGenericFrameCodec.partial<TextPayload>(buf, DecodeContext.Empty)
        // Every header field is readable before any payload codec is chosen —
        // routing on group/commandId is the whole point.
        assertEquals(9u.toUShort(), partial.group)
        assertEquals(3u.toUByte(), partial.commandId)
        assertEquals(2u.toUShort(), partial.payloadLength)

        assertEquals(original, partial.complete(TextPayloadCodec))
    }

    /**
     * A `Partial` whose payload is followed by a **variable-width** field.
     * Impossible under `@RemainingBytes`, where the payload's end is
     * `limit - <trailer bytes>` and so every trailer must have a known width.
     * A sibling states the length outright, so the restriction lifts.
     */
    @Test
    fun partialSupportsAVariableWidthTrailer() {
        val original =
            SmpFrameWithTrailer(
                payloadLength = 2u,
                payload = TextPayload("hi"),
                checksum = 0xBEEFu,
                note = "trailing",
            )
        val buf = buildBuffer { SmpFrameWithTrailerCodec.encode(it, original, EncodeContext.Empty) }
        buf.resetForRead()

        val partial = SmpFrameWithTrailerCodec.partial(buf, DecodeContext.Empty)
        assertEquals(0xBEEFu.toUShort(), partial.checksum, "trailer read eagerly, past the payload")
        assertEquals("trailing", partial.note)
        assertEquals(original, partial.complete())
    }

    /** `complete()` bounds the payload to the sibling region, not to the buffer. */
    @Test
    fun completeBoundsThePayloadToTheSiblingRegion() {
        val original =
            SmpFrameWithTrailer(
                payloadLength = 2u,
                payload = TextPayload("hi"),
                checksum = 0u,
                note = "xyz",
            )
        val buf = buildBuffer { SmpFrameWithTrailerCodec.encode(it, original, EncodeContext.Empty) }
        buf.resetForRead()
        val completed = SmpFrameWithTrailerCodec.partial(buf, DecodeContext.Empty).complete()
        // TextPayloadCodec reads remaining(); without the bound it would swallow
        // the checksum and note bytes too.
        assertEquals(TextPayload("hi"), completed.payload)
    }

    /** `complete()` enforces the same consumption contract as `decode()`. */
    @Test
    fun completeRejectsAnUnderReadingPayloadCodec() {
        val buf = BufferFactory.Default.allocate(32)
        buf.writeUShort(4u)
        buf.writeString("abcd", Charset.UTF8)
        buf.resetForRead()
        val partial = ShortReadFrameCodec.partial(buf, DecodeContext.Empty)
        val failure = assertFailsWith<DecodeException> { partial.complete() }
        assertEquals("ShortReadFrame.payload", failure.fieldPath)
    }

    private fun frameFor(payload: TextPayload) =
        SmpFrame(
            op = 0u,
            flags = 0u,
            payloadLength =
                payload.text
                    .encodeToByteArray()
                    .size
                    .toUShort(),
            group = 9u,
            sequence = 1u,
            commandId = 3u,
            payload = payload,
        )

    private fun singleByte(b: Byte) =
        BufferFactory.Default.allocate(1).also {
            it.writeByte(b)
            it.resetForRead()
        }

    private fun encode(value: SmpFrame) = buildBuffer { SmpFrameCodec.encode(it, value, EncodeContext.Empty) }

    private fun buildBuffer(write: (PlatformBuffer) -> Unit) = BufferFactory.Default.allocate(256).also(write)
}
