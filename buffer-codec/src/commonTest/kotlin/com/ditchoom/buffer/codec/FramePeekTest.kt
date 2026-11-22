package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FramePeekTest {
    @Test
    fun completeRoundTrips() {
        val peek = FramePeek.complete(42)
        assertEquals(FramePeekKind.Complete, peek.kind)
        assertEquals(42, peek.size)
        assertTrue(peek.isComplete)
        assertFalse(peek.isDeclaredIncomplete || peek.isNeedsMoreData || peek.isNoFraming)
    }

    @Test
    fun declaredIncompleteRoundTrips() {
        val peek = FramePeek.declaredIncomplete(8_000_000)
        assertEquals(FramePeekKind.DeclaredIncomplete, peek.kind)
        assertEquals(8_000_000, peek.size)
        assertTrue(peek.isDeclaredIncomplete)
        assertFalse(peek.isComplete)
    }

    @Test
    fun needsMoreDataAndNoFramingCarryNoSize() {
        assertEquals(FramePeekKind.NeedsMoreData, FramePeek.needsMoreData.kind)
        assertEquals(FramePeekKind.NoFraming, FramePeek.noFraming.kind)
        assertTrue(FramePeek.needsMoreData.isNeedsMoreData)
        assertTrue(FramePeek.noFraming.isNoFraming)
        assertFailsWith<IllegalStateException> { FramePeek.needsMoreData.size }
        assertFailsWith<IllegalStateException> { FramePeek.noFraming.size }
    }

    @Test
    fun nonPositiveSizesSurviveSoCallersCanBoundThem() {
        assertEquals(0, FramePeek.complete(0).size)
        assertEquals(-1, FramePeek.complete(-1).size)
        assertEquals(Int.MIN_VALUE, FramePeek.declaredIncomplete(Int.MIN_VALUE).size)
        assertEquals(Int.MAX_VALUE, FramePeek.complete(Int.MAX_VALUE).size)
        assertEquals(FramePeekKind.Complete, FramePeek.complete(-1).kind)
    }

    @Test
    fun forDeclaredSizeSplitsOnAvailability() {
        assertEquals(FramePeek.complete(10), FramePeek.forDeclaredSize(10, available = 10))
        assertEquals(FramePeek.complete(10), FramePeek.forDeclaredSize(10, available = 99))
        assertEquals(FramePeek.declaredIncomplete(10), FramePeek.forDeclaredSize(10, available = 9))
    }

    @Test
    fun withPrefixShiftsSizedStatesOnly() {
        assertEquals(FramePeek.complete(5), FramePeek.complete(4).withPrefix(1))
        assertEquals(FramePeek.declaredIncomplete(5), FramePeek.declaredIncomplete(4).withPrefix(1))
        assertEquals(FramePeek.needsMoreData, FramePeek.needsMoreData.withPrefix(1))
        assertEquals(FramePeek.noFraming, FramePeek.noFraming.withPrefix(1))
    }

    @Test
    fun withoutDeclaredSizeDropsOnlyTheDeclaredSize() {
        assertEquals(FramePeek.needsMoreData, FramePeek.declaredIncomplete(9).withoutDeclaredSize())
        assertEquals(FramePeek.complete(9), FramePeek.complete(9).withoutDeclaredSize())
        assertEquals(FramePeek.noFraming, FramePeek.noFraming.withoutDeclaredSize())
    }

    @Test
    fun convertsToAndFromPeekResult() {
        assertEquals(FramePeek.complete(7), FramePeek.from(PeekResult.Complete(7)))
        assertEquals(FramePeek.needsMoreData, FramePeek.from(PeekResult.NeedsMoreData))
        assertEquals(FramePeek.noFraming, FramePeek.from(PeekResult.NoFraming))
        assertEquals(PeekResult.Complete(7), FramePeek.complete(7).toPeekResult())
        assertEquals(PeekResult.NeedsMoreData, FramePeek.needsMoreData.toPeekResult())
        assertEquals(PeekResult.NeedsMoreData, FramePeek.declaredIncomplete(7).toPeekResult())
        assertEquals(PeekResult.NoFraming, FramePeek.noFraming.toPeekResult())
    }

    // ---- default delegation ------------------------------------------------

    /** Implements only the older method, as every codec written before `peekFrame` does. */
    private class OldStyleDetector(
        private val answer: PeekResult,
    ) : FrameDetector {
        override fun peekFrameSize(
            stream: StreamProcessor,
            baseOffset: Int,
        ): PeekResult = answer
    }

    private fun emptyStream(): StreamProcessor = StreamProcessor.create(BufferPool(), ByteOrder.BIG_ENDIAN)

    @Test
    fun defaultPeekFrameDelegatesToPeekFrameSize() {
        val stream = emptyStream()
        try {
            assertEquals(FramePeek.complete(12), OldStyleDetector(PeekResult.Complete(12)).peekFrame(stream))
            assertEquals(FramePeek.needsMoreData, OldStyleDetector(PeekResult.NeedsMoreData).peekFrame(stream))
            assertEquals(FramePeek.noFraming, OldStyleDetector(PeekResult.NoFraming).peekFrame(stream))
        } finally {
            stream.release()
        }
    }

    @Test
    fun aDetectorImplementingNeitherMethodHasNoFraming() {
        val stream = emptyStream()
        try {
            assertEquals(FramePeek.noFraming, object : FrameDetector {}.peekFrame(stream))
        } finally {
            stream.release()
        }
    }

    // ---- readFrame ----------------------------------------------------------

    private class StubCodec(
        private val peek: FramePeek,
    ) : Codec<String> {
        override fun encode(
            buffer: WriteBuffer,
            value: String,
            context: EncodeContext,
        ) = Unit

        override fun decode(
            buffer: ReadBuffer,
            context: DecodeContext,
        ): String = "decoded"

        override fun peekFrameSize(
            stream: StreamProcessor,
            baseOffset: Int,
        ): PeekResult = peek.toPeekResult()

        override fun peekFrame(
            stream: StreamProcessor,
            baseOffset: Int,
        ): FramePeek = peek
    }

    @Test
    fun readFrameRejectsADeclaredIncompleteSizeOverTheCeiling() {
        val stream = emptyStream()
        try {
            val codec = StubCodec(FramePeek.declaredIncomplete(DEFAULT_MAX_FRAME_BYTES + 1))
            val thrown = assertFailsWith<FrameTooLargeException> { codec.readFrame(stream) }
            assertEquals(DEFAULT_MAX_FRAME_BYTES + 1L, thrown.declaredBytes)
            val lowered = DecodeContext.Empty.with(MaxFrameBytesKey, 16)
            assertFailsWith<FrameTooLargeException> { StubCodec(FramePeek.declaredIncomplete(17)).readFrame(stream, lowered) }
        } finally {
            stream.release()
        }
    }

    @Test
    fun readFrameWaitsOnADeclaredIncompleteSizeUnderTheCeiling() {
        val stream = emptyStream()
        try {
            assertNull(StubCodec(FramePeek.declaredIncomplete(64)).readFrame(stream))
            assertNull(StubCodec(FramePeek.needsMoreData).readFrame(stream))
            assertFailsWith<FrameTooLargeException> { StubCodec(FramePeek.declaredIncomplete(0)).readFrame(stream) }
        } finally {
            stream.release()
        }
    }

    @Test
    fun readFrameOnANoFramingCodecThrows() {
        val stream = emptyStream()
        try {
            assertFailsWith<IllegalStateException> { StubCodec(FramePeek.noFraming).readFrame(stream) }
        } finally {
            stream.release()
        }
    }

    @Test
    fun readFrameDecodesACompleteFrameFromAnOldStyleCodec() {
        val buf = BufferFactory.Default.allocate(4, ByteOrder.BIG_ENDIAN)
        buf.writeInt(0x01020304)
        buf.resetForRead()
        val stream = emptyStream()
        stream.append(buf)
        try {
            val codec =
                object : Codec<String> {
                    override fun encode(
                        buffer: WriteBuffer,
                        value: String,
                        context: EncodeContext,
                    ) = Unit

                    override fun decode(
                        buffer: ReadBuffer,
                        context: DecodeContext,
                    ): String = "frame:${buffer.remaining()}"

                    // Only the old method, so `readFrame` reaches it through the default `peekFrame`.
                    override fun peekFrameSize(
                        stream: StreamProcessor,
                        baseOffset: Int,
                    ): PeekResult = PeekResult.Complete(4)
                }
            assertEquals("frame:4", codec.readFrame(stream))
        } finally {
            stream.release()
        }
    }
}
