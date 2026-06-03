package com.ditchoom.buffer.codec.test.protocols.partialnonterminal

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayload
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayloadCodec
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `Partial` generation for a non-terminal `@RemainingBytes` payload (issue
 * #168): the payload is followed by a fixed-size `checksum` trailer, yet a
 * `Partial<P>` is still emitted. `partial(...)` reads the header and trailer
 * eagerly and defers the payload; `complete(payloadCodec)` decodes it.
 */
class CommandFrameCodecTest {
    private val codec = CommandFrameCodec(TextPayloadCodec)

    private fun encode(frame: CommandFrame<TextPayload>): ReadBuffer {
        val size =
            2 + 2 +
                frame.payload.text
                    .encodeToByteArray()
                    .size + 2
        val buffer = BufferFactory.Default.allocate(size, ByteOrder.BIG_ENDIAN)
        codec.encode(buffer, frame, EncodeContext.Empty)
        buffer.resetForRead()
        return buffer
    }

    private val sample =
        CommandFrame(
            counter = 0x0102u,
            length = 0x0005u,
            payload = TextPayload("Hello"),
            checksum = 0xBEEFu,
        )

    @Test
    fun fullDecodeRoundTrips() {
        val decoded = codec.decode(encode(sample), DecodeContext.Empty)
        assertEquals(sample, decoded)
    }

    @Test
    fun partialReadsHeaderAndTrailerEagerlyThenCompletesPayload() {
        val partial = CommandFrameCodec.partial<TextPayload>(encode(sample), DecodeContext.Empty)
        // Header + fixed-size trailer are available before decoding the payload.
        assertEquals(0x0102u.toUShort(), partial.counter)
        assertEquals(0x0005u.toUShort(), partial.length)
        assertEquals(0xBEEFu.toUShort(), partial.checksum)
        // Deferred payload decodes from the captured region.
        val full = partial.complete(TextPayloadCodec)
        assertEquals(sample, full)
    }

    @Test
    fun partialCompleteMatchesFullDecode() {
        val viaFull = codec.decode(encode(sample), DecodeContext.Empty)
        val viaPartial =
            CommandFrameCodec
                .partial<TextPayload>(encode(sample), DecodeContext.Empty)
                .complete(TextPayloadCodec)
        assertEquals(viaFull, viaPartial)
    }

    @Test
    fun handlesEmptyPayload() {
        val empty =
            CommandFrame(
                counter = 0x0001u,
                length = 0x0000u,
                payload = TextPayload(""),
                checksum = 0x1234u,
            )
        val partial = CommandFrameCodec.partial<TextPayload>(encode(empty), DecodeContext.Empty)
        assertEquals(0x1234u.toUShort(), partial.checksum)
        assertEquals(empty, partial.complete(TextPayloadCodec))
    }
}
