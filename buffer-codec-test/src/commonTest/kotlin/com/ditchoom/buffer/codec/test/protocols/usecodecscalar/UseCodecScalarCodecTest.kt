package com.ditchoom.buffer.codec.test.protocols.usecodecscalar

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase I.1 step 4 — round-trip + behavior tests for the bare
 * `@UseCodec val: <scalar>` emit path. Two fixtures:
 *   - [ZigZagFrame] — non-bounding `Codec<UInt>`.
 *   - [BoundedFrame] — `BoundingLengthCodec<UInt>` driving
 *     `applyBound` + try/finally.
 */
class UseCodecScalarCodecTest {
    @Test
    fun zigZagFrameRoundTrips() {
        val original = ZigZagFrame(id = 7, value = 42u)
        val buffer = BufferFactory.Default.allocate(64)
        ZigZagFrameCodec.encode(buffer, original, EncodeContext.Empty)
        buffer.resetForRead()
        val decoded = ZigZagFrameCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun zigZagFrameDelegatesToUserCodec() {
        // 42 → zigzag = 84 (positive value: shift left by 1) → BE bytes: 00 00 00 54.
        // The framework's natural readUInt would have produced 0x54 = 84, not 42.
        // Decoding 0x54 via ZigZagUIntCodec ((84 ushr 1) xor 0) = 42 confirms
        // the framework actually invoked the user codec.
        val buffer = BufferFactory.Default.allocate(8)
        buffer.writeInt(0)
        buffer.writeInt(0x54)
        buffer.resetForRead()
        val decoded = ZigZagFrameCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(ZigZagFrame(id = 0, value = 42u), decoded)
    }

    @Test
    fun zigZagFrameWireSizeIsBackPatch() {
        assertEquals(
            WireSize.BackPatch,
            ZigZagFrameCodec.wireSize(ZigZagFrame(id = 1, value = 1u), EncodeContext.Empty),
        )
    }

    @Test
    fun zigZagFramePeekFrameSizeIsNoFraming() {
        // Step 6 lands the generic peek walker; until then any UseCodecScalar
        // collapses peek to NoFraming. Verify that contract.
        val pool = BufferPool(defaultBufferSize = 64)
        val processor = StreamProcessor.create(pool)
        try {
            val buffer = BufferFactory.Default.allocate(8)
            buffer.writeInt(0)
            buffer.writeInt(0x54)
            buffer.resetForRead()
            processor.append(buffer)
            assertEquals(PeekResult.NoFraming, ZigZagFrameCodec.peekFrameSize(processor, baseOffset = 0))
        } finally {
            processor.release()
            pool.clear()
        }
    }

    @Test
    fun boundedFrameRoundTrips() {
        val payload = listOf<Byte>(0x10, 0x20, 0x30)
        val original = BoundedFrame(tag = 0x4242, length = payload.size.toUInt(), payload = payload)
        val buffer = BufferFactory.Default.allocate(64)
        BoundedFrameCodec.encode(buffer, original, EncodeContext.Empty)
        buffer.resetForRead()
        val decoded = BoundedFrameCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun boundedFrameApplyBoundCappedsPayloadEvenWhenBufferHasMore() {
        // Build a buffer carrying a frame followed by extra "trailing" bytes
        // that AREN'T part of the frame. If the bounding emit works, the
        // decoded payload is exactly `length` bytes — the trailing bytes
        // remain unread.
        val buffer = BufferFactory.Default.allocate(64)
        buffer.writeShort(0x0001)
        // Le32LengthCodec encodes 3 as the LE bytes 03 00 00 00.
        Le32LengthCodec.encode(buffer, 3u, EncodeContext.Empty)
        buffer.writeByte(0xAA.toByte())
        buffer.writeByte(0xBB.toByte())
        buffer.writeByte(0xCC.toByte())
        buffer.writeByte(0xDD.toByte()) // trailing — must not appear in decoded payload
        buffer.writeByte(0xEE.toByte())
        buffer.resetForRead()

        val decoded = BoundedFrameCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(0x0001.toShort(), decoded.tag)
        assertEquals(3u, decoded.length)
        assertEquals(listOf<Byte>(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), decoded.payload)
    }

    @Test
    fun boundedFrameRestoresOuterLimitAfterDecode() {
        // After decode, the buffer's limit must be the ORIGINAL pre-decode
        // limit, not the narrowed `applyBound` limit. The try/finally
        // restoration is the contract — the next consumer of the same
        // buffer must see all the trailing bytes.
        val buffer = BufferFactory.Default.allocate(64)
        buffer.writeShort(0x0001)
        Le32LengthCodec.encode(buffer, 2u, EncodeContext.Empty)
        buffer.writeByte(0x11)
        buffer.writeByte(0x22)
        buffer.writeByte(0x33) // trailing
        buffer.writeByte(0x44) // trailing
        buffer.resetForRead()

        val originalLimit = buffer.limit()
        BoundedFrameCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(originalLimit, buffer.limit(), "decode must restore the outer buffer limit")
        // The trailing 0x33 and 0x44 must still be readable from the
        // post-decode position.
        assertEquals(2, buffer.limit() - buffer.position(), "two trailing bytes remain past the bounded frame")
        assertEquals(0x33.toByte(), buffer.readByte())
        assertEquals(0x44.toByte(), buffer.readByte())
    }

    @Test
    fun boundedFrameWireSizeIsBackPatch() {
        // Conservative collapse — runtime-Exact composition is a follow-on.
        assertEquals(
            WireSize.BackPatch,
            BoundedFrameCodec.wireSize(BoundedFrame(0, 0u, emptyList()), EncodeContext.Empty),
        )
    }

    @Test
    fun boundedFramePeekFrameSizeReportsCompleteForFullFrame() {
        // tag (2 BE) + length (4 LE = 3) + 3 payload bytes = 9 total bytes.
        // The generic @UseCodec peek walker drives Le32LengthCodec against
        // a non-consuming view, measures its 4-byte width, and reports
        // PeekResult.Complete(2 + 4 + 3) = Complete(9).
        val pool = BufferPool(defaultBufferSize = 64)
        val processor = StreamProcessor.create(pool)
        try {
            val buffer = BufferFactory.Default.allocate(16)
            buffer.writeShort(0x0001)
            Le32LengthCodec.encode(buffer, 3u, EncodeContext.Empty)
            buffer.writeByte(0xAA.toByte())
            buffer.writeByte(0xBB.toByte())
            buffer.writeByte(0xCC.toByte())
            buffer.resetForRead()
            processor.append(buffer)
            val peek = BoundedFrameCodec.peekFrameSize(processor)
            assertTrue(peek is PeekResult.Complete, "expected Complete, got $peek")
            assertEquals(9, peek.bytes)
        } finally {
            processor.release()
            pool.clear()
        }
    }

    @Test
    fun boundedFramePeekReportsNeedsMoreDataDripFeedingHeader() {
        // Drip-feed bytes one at a time; peek must report NeedsMoreData
        // until the codec can decode the length value (tag 2 bytes +
        // codec width 4 bytes = 6 bytes minimum), then NeedsMoreData
        // again until the payload arrives, then Complete.
        val pool = BufferPool(defaultBufferSize = 64)
        val processor = StreamProcessor.create(pool)
        try {
            val full = BufferFactory.Default.allocate(16)
            full.writeShort(0x0001)
            Le32LengthCodec.encode(full, 2u, EncodeContext.Empty)
            full.writeByte(0xAA.toByte())
            full.writeByte(0xBB.toByte())
            full.resetForRead()
            val totalBytes = full.remaining()
            assertEquals(8, totalBytes) // 2 + 4 + 2

            for (i in 1 until totalBytes) {
                val chunk = BufferFactory.Default.allocate(1)
                chunk.writeByte(full.readByte())
                chunk.resetForRead()
                processor.append(chunk)
                val peek = BoundedFrameCodec.peekFrameSize(processor)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    peek,
                    "after $i of $totalBytes bytes, expected NeedsMoreData but got $peek",
                )
            }
            // Final byte arrives — peek now reports Complete(8).
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(full.readByte())
            last.resetForRead()
            processor.append(last)
            val peek = BoundedFrameCodec.peekFrameSize(processor)
            assertTrue(peek is PeekResult.Complete, "expected Complete, got $peek")
            assertEquals(8, peek.bytes)
        } finally {
            processor.release()
            pool.clear()
        }
    }

    @Test
    fun boundedFramePeekDoesNotConsumeBytes() {
        // Repeated peeks must report the same Complete result without
        // consuming any bytes from the stream.
        val pool = BufferPool(defaultBufferSize = 64)
        val processor = StreamProcessor.create(pool)
        try {
            val buffer = BufferFactory.Default.allocate(16)
            buffer.writeShort(0x4242)
            Le32LengthCodec.encode(buffer, 2u, EncodeContext.Empty)
            buffer.writeByte(0x11)
            buffer.writeByte(0x22)
            buffer.resetForRead()
            processor.append(buffer)
            val availableBefore = processor.available()
            assertEquals(8, availableBefore)
            repeat(5) {
                val peek = BoundedFrameCodec.peekFrameSize(processor)
                assertTrue(peek is PeekResult.Complete)
                assertEquals(8, peek.bytes)
                assertEquals(availableBefore, processor.available(), "peek must be non-consuming")
            }
        } finally {
            processor.release()
            pool.clear()
        }
    }
}
