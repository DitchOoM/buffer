package com.ditchoom.buffer.codec.test.protocols.multibytedisc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Cross-platform round-trip + `peekFrameSize` coverage for multi-byte
 * `@DispatchOn` discriminators across all four newly-supported inner kinds:
 * signed `Short` / `Int` / `Long` and unsigned `ULong`.
 *
 * The discriminator's decode/encode always worked; what these vectors pin
 * is the dispatcher's order-aware `peekFrameSize` reconstruction — which
 * was previously rejected for every kind here. Each test exercises:
 *
 *   1. encode → decode round-trip through the dispatcher;
 *   2. the physical discriminator bytes (proving the declared `wireOrder`);
 *   3. `peekFrameSize` returning `Complete(totalBytes)` once the frame is
 *      fully buffered, and `NeedsMoreData` before the discriminator's own
 *      bytes have arrived (the reconstruction never reads past the stream);
 *   4. `wireSize` pure-delegation (the variant counts its re-read
 *      discriminator; the dispatcher does not add a `1 +`);
 *   5. the unmatched-discriminator failure mode.
 */
class MultiByteDispatchCodecTest {
    // — Short inner, big-endian (2-byte discriminator) —

    @Test
    fun shortDiscriminatorNegativeRoundTrips() {
        val original = SignedOpcodeFrame.Negative(payload = 0x0A0B0C0D)
        val buf = encode { SignedOpcodeFrameCodec.encode(it, original, EncodeContext.Empty) }
        val decoded = SignedOpcodeFrameCodec.decode(buf, DecodeContext.Empty)
        assertIs<SignedOpcodeFrame.Negative>(decoded)
        assertEquals(original, decoded)
        assertEquals((-2).toShort(), decoded.opcode.raw)
        // BE -2 → 0xFF 0xFE (decode consumed the whole frame, flip restores it).
        buf.resetForRead()
        assertEquals(0xFF, buf.readUByte().toInt(), "opcode hi byte")
        assertEquals(0xFE, buf.readUByte().toInt(), "opcode lo byte (BE)")
    }

    @Test
    fun shortDiscriminatorPositiveRoundTrips() {
        val original = SignedOpcodeFrame.Positive(payload = 7)
        val buf = encode { SignedOpcodeFrameCodec.encode(it, original, EncodeContext.Empty) }
        assertEquals(original, SignedOpcodeFrameCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun shortDiscriminatorWireSizeDelegates() {
        // 2 (discriminator) + 4 (payload Int) = 6; dispatcher does NOT add the
        // discriminator a second time (ReReadByVariant ownership).
        assertEquals(
            WireSize.Exact(6),
            SignedOpcodeFrameCodec.wireSize(SignedOpcodeFrame.Positive(payload = 0), EncodeContext.Empty),
        )
    }

    @Test
    fun shortDiscriminatorPeekFrameSize() =
        peekDripFeed({ SignedOpcodeFrameCodec.peekFrameSize(it) }) {
            SignedOpcodeFrameCodec.encode(it, SignedOpcodeFrame.Negative(payload = 1), EncodeContext.Empty)
        }

    // — Int inner, little-endian (4-byte discriminator) —

    @Test
    fun intDiscriminatorBetaRoundTripsLittleEndian() {
        val original = SignedTagFrame.Beta(value = 0x1234)
        val buf = encode { SignedTagFrameCodec.encode(it, original, EncodeContext.Empty) }
        assertEquals(original, SignedTagFrameCodec.decode(buf, DecodeContext.Empty))
        // LE 7 → 0x07 0x00 0x00 0x00.
        buf.resetForRead()
        assertEquals(0x07, buf.readUByte().toInt(), "tag byte 0 (LE low)")
        assertEquals(0x00, buf.readUByte().toInt(), "tag byte 1")
        assertEquals(0x00, buf.readUByte().toInt(), "tag byte 2")
        assertEquals(0x00, buf.readUByte().toInt(), "tag byte 3 (LE high)")
    }

    @Test
    fun intDiscriminatorAlphaRoundTripsNegative() {
        val original = SignedTagFrame.Alpha(value = -3)
        val buf = encode { SignedTagFrameCodec.encode(it, original, EncodeContext.Empty) }
        val decoded = SignedTagFrameCodec.decode(buf, DecodeContext.Empty)
        assertIs<SignedTagFrame.Alpha>(decoded)
        assertEquals(-1, decoded.tag.raw)
        assertEquals(original, decoded)
    }

    @Test
    fun intDiscriminatorPeekFrameSize() =
        peekDripFeed({ SignedTagFrameCodec.peekFrameSize(it) }) {
            SignedTagFrameCodec.encode(it, SignedTagFrame.Alpha(value = 9), EncodeContext.Empty)
        }

    // — Long inner, big-endian (8-byte discriminator) —

    @Test
    fun longDiscriminatorRoundTripsBigEndian() {
        val original = SignedSelectorFrame.Nine(payload = 0x7F00FF01)
        val buf = encode { SignedSelectorFrameCodec.encode(it, original, EncodeContext.Empty) }
        val decoded = SignedSelectorFrameCodec.decode(buf, DecodeContext.Empty)
        assertIs<SignedSelectorFrame.Nine>(decoded)
        assertEquals(9L, decoded.selector.raw)
        assertEquals(original, decoded)
        // BE 9 → seven 0x00 then 0x09.
        buf.resetForRead()
        repeat(7) { i -> assertEquals(0x00, buf.readUByte().toInt(), "selector byte $i") }
        assertEquals(0x09, buf.readUByte().toInt(), "selector byte 7 (BE low)")
    }

    @Test
    fun longDiscriminatorWireSizeDelegates() {
        // 8 (discriminator Long) + 4 (payload Int) = 12, no dispatcher `1 +`.
        assertEquals(
            WireSize.Exact(12),
            SignedSelectorFrameCodec.wireSize(SignedSelectorFrame.Two(payload = 0), EncodeContext.Empty),
        )
    }

    @Test
    fun longDiscriminatorPeekFrameSize() =
        peekDripFeed({ SignedSelectorFrameCodec.peekFrameSize(it) }) {
            SignedSelectorFrameCodec.encode(it, SignedSelectorFrame.Two(payload = 1), EncodeContext.Empty)
        }

    // — ULong inner, little-endian (8-byte discriminator) —

    @Test
    fun uLongDiscriminatorRoundTripsLittleEndian() {
        val original = UnsignedMagicFrame.First(payload = 0x0BADF00D)
        val buf = encode { UnsignedMagicFrameCodec.encode(it, original, EncodeContext.Empty) }
        val decoded = UnsignedMagicFrameCodec.decode(buf, DecodeContext.Empty)
        assertIs<UnsignedMagicFrame.First>(decoded)
        assertEquals(0x11uL, decoded.magic.raw)
        assertEquals(original, decoded)
        // LE 0x11 → 0x11 then seven 0x00.
        buf.resetForRead()
        assertEquals(0x11, buf.readUByte().toInt(), "magic byte 0 (LE low)")
        repeat(7) { i -> assertEquals(0x00, buf.readUByte().toInt(), "magic byte ${i + 1}") }
    }

    @Test
    fun uLongDiscriminatorSecondRoundTrips() {
        val original = UnsignedMagicFrame.Second(payload = -1)
        val buf = encode { UnsignedMagicFrameCodec.encode(it, original, EncodeContext.Empty) }
        assertEquals(original, UnsignedMagicFrameCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun uLongDiscriminatorPeekFrameSize() =
        peekDripFeed({ UnsignedMagicFrameCodec.peekFrameSize(it) }) {
            UnsignedMagicFrameCodec.encode(it, UnsignedMagicFrame.Second(payload = 5), EncodeContext.Empty)
        }

    @Test
    fun unmatchedDiscriminatorThrows() {
        // 0x00000000_00000099 — low byte 0x99 is not a named variant; the
        // dispatcher else-branch fires after reconstructing the ULong.
        val buf = BufferFactory.Default.allocate(12)
        repeat(7) { buf.writeByte(0x00) }
        buf.writeByte(0x99.toByte())
        buf.writeInt(0)
        buf.resetForRead()
        val ex =
            assertFailsWith<DecodeException> {
                UnsignedMagicFrameCodec.decode(buf, DecodeContext.Empty)
            }
        assertEquals("UnsignedMagicFrame.discriminator", ex.fieldPath)
    }

    // — helpers —

    private fun encode(block: (PlatformBuffer) -> Unit): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(64)
        block(buf)
        buf.resetForRead()
        return buf
    }

    /**
     * Drip-feeds an encoded frame one byte at a time, asserting
     * `NeedsMoreData` for every prefix shorter than the whole frame and
     * `Complete(total)` once the last byte arrives. Proves the order-aware
     * discriminator reconstruction never reads past the available stream.
     */
    private fun peekDripFeed(
        peek: (StreamProcessor) -> PeekResult,
        encodeBlock: (PlatformBuffer) -> Unit,
    ) {
        val pool = BufferPool()
        val source = BufferFactory.Default.allocate(64)
        encodeBlock(source)
        source.resetForRead()
        val total = source.remaining()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            for (i in 0 until total - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(source.readByte())
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    peek(stream),
                    "after ${i + 1}/$total bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(source.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(total), peek(stream), "fully buffered")
        } finally {
            stream.release()
            pool.clear()
        }
    }
}
