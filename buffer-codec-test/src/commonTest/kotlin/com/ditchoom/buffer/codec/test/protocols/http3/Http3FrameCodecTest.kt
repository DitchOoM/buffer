package com.ditchoom.buffer.codec.test.protocols.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.OwnedBytesHandle
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.byteSize
import com.ditchoom.buffer.codec.handleEquals
import com.ditchoom.buffer.codec.ownedBytesFrom
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryData
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real HTTP/3 frame (RFC 9114 §7.1): `Type (varint)` + `Length (varint)` +
 * `Frame Payload`. This pins two variable-width mechanisms composing in one
 * codec:
 *
 *  - the **variable-width dispatcher** ([Http3FrameType], a `@DispatchOn`
 *    discriminator whose QUIC-varint width is 1 byte for types `0x00`/`0x01`/
 *    `0x04` and 2 bytes for [Http3Frame.Extension] = `0x40`), and
 *  - the **variable-width bounding length** ([Http3LengthCodec], the RFC 9114
 *    §7.1 Length): 1-byte varint for payloads ≤63, 2-byte for larger.
 *
 * What this pins:
 *  - round-trip through `Http3FrameCodec` for every variant across the
 *    discriminator-width × length-width cross product;
 *  - `applyBound` caps the `@RemainingBytes` payload at exactly `length` bytes,
 *    so trailing bytes past the frame don't bleed into the payload;
 *  - `peekFrameSize` drip-feeding one byte at a time: `NeedsMoreData` until the
 *    whole frame (`typeWidth + lengthWidth + length`) is buffered, then
 *    `Complete(total)` — and `total` equals what `decode` consumes.
 */
class Http3FrameCodecTest {
    // Short payload → 1-byte varint length; long payload (>63) → 2-byte varint.
    private val short = 3
    private val long = 200

    private fun payload(size: Int): BinaryData {
        val buf = BufferFactory.Default.allocate(size)
        for (i in 0 until size) buf.writeByte((i and 0x7F).toByte())
        buf.resetForRead()
        return BinaryData(ownedBytesFrom(buf))
    }

    /** (frameType, length, payload-handle) projected uniformly across variants. */
    private fun parts(frame: Http3Frame): Triple<Http3FrameType, ULong, OwnedBytesHandle> =
        when (frame) {
            is Http3Frame.Data -> Triple(frame.frameType, frame.length, frame.payload.data)
            is Http3Frame.Headers -> Triple(frame.frameType, frame.length, frame.fieldSection.data)
            is Http3Frame.Settings -> Triple(frame.frameType, frame.length, frame.parameters.data)
            is Http3Frame.Extension -> Triple(frame.frameType, frame.length, frame.payload.data)
        }

    private fun cases(): List<Pair<Http3Frame, Int>> =
        listOf(
            // (frame, expected total wire bytes = typeWidth + lengthWidth + payload)
            Http3Frame.Data(length = short.toULong(), payload = payload(short)) to (1 + 1 + short),
            Http3Frame.Data(length = long.toULong(), payload = payload(long)) to (1 + 2 + long),
            Http3Frame.Headers(length = short.toULong(), fieldSection = payload(short)) to (1 + 1 + short),
            Http3Frame.Settings(length = short.toULong(), parameters = payload(short)) to (1 + 1 + short),
            // 2-byte discriminator (type 0x40) × both length widths.
            Http3Frame.Extension(length = short.toULong(), payload = payload(short)) to (2 + 1 + short),
            Http3Frame.Extension(length = long.toULong(), payload = payload(long)) to (2 + 2 + long),
        )

    @Test
    fun roundTripsEveryVariantAcrossDiscriminatorAndLengthWidths() {
        for ((frame, total) in cases()) {
            val buf = BufferFactory.Default.allocate(256)
            Http3FrameCodec.encode(buf, frame, EncodeContext.Empty)
            assertEquals(total, buf.position(), "encoded size for $frame")
            buf.resetForRead()
            val decoded = Http3FrameCodec.decode(buf, DecodeContext.Empty)
            assertEquals(frame::class, decoded::class, "variant for $frame")
            val (type, length, payload) = parts(frame)
            val (dType, dLength, dPayload) = parts(decoded)
            assertEquals(type, dType, "frameType for $frame")
            assertEquals(length, dLength, "length for $frame")
            assertTrue(payload.handleEquals(dPayload), "payload bytes for $frame")
        }
    }

    @Test
    fun applyBoundCapsPayloadEvenWithTrailingBytes() {
        // The Length varint bounds the payload; bytes appended past the frame
        // must not be swallowed by the trailing @RemainingBytes field.
        val frame = Http3Frame.Data(length = short.toULong(), payload = payload(short))
        val buf = BufferFactory.Default.allocate(64)
        Http3FrameCodec.encode(buf, frame, EncodeContext.Empty)
        buf.writeByte(0x7F) // trailing bytes past the frame
        buf.writeByte(0x7E)
        buf.resetForRead()
        val decoded = Http3FrameCodec.decode(buf, DecodeContext.Empty)
        assertEquals(short, parts(decoded).third.byteSize(), "payload capped at length")
        assertTrue(parts(frame).third.handleEquals(parts(decoded).third), "payload bytes")
    }

    @Test
    fun peekFramesByVariableDiscriminatorWidthPlusBoundedBody() {
        for ((frame, total) in cases()) {
            assertDripPeek(frame, expectedTotal = total)
        }
    }

    private fun assertDripPeek(
        frame: Http3Frame,
        expectedTotal: Int,
    ) {
        val pool = BufferPool()
        val source = BufferFactory.Default.allocate(256)
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
            // Peeked frame size must equal what decode consumes.
            val decodeBuffer = BufferFactory.Default.allocate(256)
            Http3FrameCodec.encode(decodeBuffer, frame, EncodeContext.Empty)
            decodeBuffer.resetForRead()
            Http3FrameCodec.decode(decodeBuffer, DecodeContext.Empty)
            assertEquals(total, decodeBuffer.position(), "decode consumed the peeked frame size for $frame")
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
