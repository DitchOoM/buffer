package com.ditchoom.buffer.codec.test.protocols.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Stage 3/4a — the variable-width **dispatcher**.
 *
 * `Http3Frame` dispatches on [Http3FrameType], a value class whose inner scalar
 * is a QUIC varint (`@UseCodec(QuicVarintCodec)`). The discriminator's wire
 * width therefore isn't fixed: frame types `0x00`/`0x01`/`0x04` are 1-byte
 * varints, while `Extension` (type `0x40` = 64) is a 2-byte varint. The
 * dispatcher must measure that width at runtime — via the value class's own
 * codec — both to rewind on decode and to frame on peek.
 *
 * What this pins:
 *  - round-trip through `Http3FrameCodec` for every variant, including the
 *    multi-byte discriminator, and
 *  - `peekFrameSize` drip-feeding one byte at a time: `NeedsMoreData` until the
 *    full frame (varint type + fixed body) is buffered, then `Complete(total)`.
 */
class Http3FrameCodecTest {
    @Test
    fun roundTripsEveryVariantIncludingMultiByteDiscriminator() {
        // (frame, total wire bytes) — type-varint-width + fixed body.
        for ((frame, total) in listOf(
            Http3Frame.Data(firstByte = 0xABu) to 2, // 1-byte type + 1
            Http3Frame.Headers(fieldSectionTag = 0x1234u) to 3, // 1-byte type + 2
            Http3Frame.Settings(identifier = 0xDEAD_BEEFu) to 5, // 1-byte type + 4
            Http3Frame.Extension(payload = 0x7Fu) to 3, // 2-byte type + 1
        )) {
            val buf = BufferFactory.Default.allocate(16)
            Http3FrameCodec.encode(buf, frame, EncodeContext.Empty)
            assertEquals(total, buf.position(), "encoded size for $frame")
            buf.resetForRead()
            assertEquals(frame, Http3FrameCodec.decode(buf, DecodeContext.Empty), "round-trip $frame")
        }
    }

    @Test
    fun peekFramesByVariableDiscriminatorWidthPlusBody() {
        // 1-byte discriminator variants.
        assertDripPeek(Http3Frame.Data(firstByte = 0x01u), expectedTotal = 2)
        assertDripPeek(Http3Frame.Settings(identifier = 0x01020304u), expectedTotal = 5)
        // 2-byte discriminator (type 0x40): proves the dispatcher measures the
        // varint width rather than assuming one byte.
        assertDripPeek(Http3Frame.Extension(payload = 0x55u), expectedTotal = 3)
    }

    private fun assertDripPeek(
        frame: Http3Frame,
        expectedTotal: Int,
    ) {
        val pool = BufferPool()
        val source = BufferFactory.Default.allocate(16)
        Http3FrameCodec.encode(source, frame, EncodeContext.Empty)
        source.resetForRead()
        val total = source.remaining()
        assertEquals(expectedTotal, total, "encoded total for $frame")
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            for (i in 0 until total - 1) {
                appendByte(stream, source.readByte())
                assertEquals(
                    PeekResult.NeedsMoreData,
                    Http3FrameCodec.peekFrameSize(stream),
                    "after ${i + 1}/$total bytes of $frame",
                )
            }
            appendByte(stream, source.readByte())
            assertEquals(
                PeekResult.Complete(total),
                Http3FrameCodec.peekFrameSize(stream),
                "fully buffered $frame",
            )
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun appendByte(
        stream: StreamProcessor,
        byte: Byte,
    ) {
        val one: PlatformBuffer = BufferFactory.Default.allocate(1)
        one.writeByte(byte)
        one.resetForRead()
        stream.append(one)
    }
}
