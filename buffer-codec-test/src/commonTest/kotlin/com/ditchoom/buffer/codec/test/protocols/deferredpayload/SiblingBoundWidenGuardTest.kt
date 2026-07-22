package com.ditchoom.buffer.codec.test.protocols.deferredpayload

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayload
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayloadCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Hostile sibling lengths for the deferred-payload shapes (#310) running
 * under an outer narrowed limit — a sealed dispatcher, `@FramedBy`
 * envelope, or bounding `@UseCodec` region upstream. The sibling-derived
 * `setLimit` must never widen past that bound: a lying `payloadLength`
 * would otherwise read the *next* frame's bytes as payload and still pass
 * strict consumption, because the codec consumed exactly the lying region.
 *
 * The outer bound is simulated with a caller-carved `setLimit`, which is
 * byte-for-byte what a dispatcher does before delegating.
 */
class SiblingBoundWidenGuardTest {
    private val headerBytes = 8

    /** `@LengthFrom @UseCodec` deferred payload — [SmpFrameCodec]. */
    @Test
    fun deferredUseCodecPayloadCannotWidenPastTheOuterBound() {
        val buf = hostileSmpFrame()
        val failure =
            assertFailsWith<DecodeException> {
                SmpFrameCodec.decode(buf, DecodeContext.Empty)
            }
        assertEquals("SmpFrame.payload", failure.fieldPath)
    }

    /** Constructor-injected generic payload codec — [SmpGenericFrameCodec]. */
    @Test
    fun constructorInjectedPayloadCannotWidenPastTheOuterBound() {
        val buf = hostileSmpFrame() // SmpGenericFrame shares SmpFrame's wire layout
        val failure =
            assertFailsWith<DecodeException> {
                SmpGenericFrameCodec(TextPayloadCodec).decode(buf, DecodeContext.Empty)
            }
        assertEquals("SmpGenericFrame.payload", failure.fieldPath)
    }

    /** `partial()` computes the payload region from the sibling — same guard, same failure. */
    @Test
    fun partialRejectsALyingSiblingLength() {
        val buf = hostileSmpFrame()
        val failure =
            assertFailsWith<DecodeException> {
                SmpGenericFrameCodec.partial<TextPayload>(buf, DecodeContext.Empty)
            }
        assertEquals("SmpGenericFrame.payload", failure.fieldPath)
    }

    /**
     * The eager-trailer `Partial` (payload followed by more fields) seeks
     * past the payload region before reading the trailer — with a lying
     * sibling that seek would land in the next frame's bytes.
     */
    @Test
    fun eagerTrailerPartialRejectsALyingSiblingLength() {
        val buf = hostileTrailerFrame()
        val failure =
            assertFailsWith<DecodeException> {
                SmpFrameWithTrailerCodec.partial(buf, DecodeContext.Empty)
            }
        assertEquals("SmpFrameWithTrailer.payload", failure.fieldPath)
    }

    /** The inline decode of the same trailer shape hits the same guard. */
    @Test
    fun trailerFrameDecodeRejectsALyingSiblingLength() {
        val buf = hostileTrailerFrame()
        val failure =
            assertFailsWith<DecodeException> {
                SmpFrameWithTrailerCodec.decode(buf, DecodeContext.Empty)
            }
        assertEquals("SmpFrameWithTrailer.payload", failure.fieldPath)
    }

    /** Control: a sibling length that exactly fills the carved region still decodes. */
    @Test
    fun honestLengthAtTheExactBoundStillDecodes() {
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        writeSmpHeader(buf, payloadLength = 2u)
        buf.writeString("hi", Charset.UTF8)
        val frameEnd = buf.position()
        repeat(40) { buf.writeByte(0x5A) }
        buf.setLimit(buf.position())
        buf.position(0)
        buf.setLimit(frameEnd)

        val decoded = SmpFrameCodec.decode(buf, DecodeContext.Empty)
        assertEquals(TextPayload("hi"), decoded.payload)
        assertEquals(frameEnd, buf.position(), "frame consumed exactly")
    }

    /**
     * An SMP frame carved to its honest 10 bytes whose `payloadLength`
     * claims 20 — the extra bytes belong to the next frame. `TextPayload`'s
     * codec reads `remaining()`, so a widened limit would return the
     * sentinel bytes as payload text with no error at all.
     */
    private fun hostileSmpFrame(): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        writeSmpHeader(buf, payloadLength = 20u) // LIES: the carve leaves 2
        buf.writeString("hi", Charset.UTF8)
        val frameEnd = buf.position()
        repeat(40) { buf.writeByte(0x5A) } // next frame's bytes
        buf.setLimit(buf.position())
        buf.position(0)
        buf.setLimit(frameEnd) // outer carve: this frame is exactly 10 bytes
        return buf
    }

    private fun writeSmpHeader(
        buf: PlatformBuffer,
        payloadLength: UShort,
    ) {
        buf.writeUByte(0u) // op
        buf.writeUByte(0u) // flags
        buf.writeUShort(payloadLength)
        buf.writeUShort(9u) // group
        buf.writeUByte(1u) // sequence
        buf.writeUByte(3u) // commandId
    }

    /**
     * `SmpFrameWithTrailer` wire: `payloadLength(2) | payload | checksum(2) |
     * note(2-byte prefix + bytes)`. Honest frame is 2 + 2 + 2 + 2 + 1 = 9
     * bytes; the sibling claims 20.
     */
    private fun hostileTrailerFrame(): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        buf.writeUShort(20u) // payloadLength LIES: the carve leaves 2
        buf.writeString("hi", Charset.UTF8)
        buf.writeUShort(0xBEEFu) // checksum
        buf.writeUShort(1u) // note length prefix
        buf.writeString("x", Charset.UTF8)
        val frameEnd = buf.position()
        repeat(40) { buf.writeByte(0x5A) }
        buf.setLimit(buf.position())
        buf.position(0)
        buf.setLimit(frameEnd)
        return buf
    }
}
