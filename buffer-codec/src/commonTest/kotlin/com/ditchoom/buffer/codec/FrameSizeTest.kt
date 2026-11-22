@file:Suppress("DEPRECATION") // exercises the bridges to the deprecated PeekResult

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
import kotlin.test.assertIs
import kotlin.test.assertNull

class FrameSizeTest {
    private fun describe(size: FrameSize): String =
        size.fold(declared = { "declared:$it" }, needsMoreData = { "needsMoreData" }, unframed = { "unframed" })

    @Test
    fun eachStateFoldsToItsOwnBranch() {
        assertEquals("declared:0", describe(FrameSize.declared(0)))
        assertEquals("declared:42", describe(FrameSize.declared(42)))
        assertEquals("declared:${Long.MAX_VALUE}", describe(FrameSize.declared(Long.MAX_VALUE)))
        assertEquals("needsMoreData", describe(FrameSize.NeedsMoreData))
        assertEquals("unframed", describe(FrameSize.Unframed))
        assertEquals(FrameSize.declared(42), FrameSize.declared(42))
        assertEquals("FrameSize.declared(42)", FrameSize.declared(42).toString())
    }

    @Test
    fun aNegativeSizeCannotBeDeclared() {
        assertEquals(-1L, assertFailsWith<FrameTooLargeException> { FrameSize.declared(-1) }.declaredBytes)
        assertFailsWith<FrameTooLargeException> { FrameSize.declared(Long.MIN_VALUE) }
        assertEquals(-5L, assertFailsWith<FrameTooLargeException> { FrameSize.declared(4, -5L) }.declaredBytes)
        assertEquals(-4L, assertFailsWith<FrameTooLargeException> { FrameSize.declared(-4, 5L) }.declaredBytes)
    }

    @Test
    fun aHeaderPlusBodySumSaturatesInsteadOfWrapping() {
        assertEquals(FrameSize.declared(9 + (1L shl 32) + 3), FrameSize.declared(9, (1L shl 32) + 3))
        assertEquals(FrameSize.declared(Long.MAX_VALUE), FrameSize.declared(9, Long.MAX_VALUE))
        assertEquals(FrameSize.declared(Long.MAX_VALUE), FrameSize.declared(9, ULong.MAX_VALUE))
        assertEquals(FrameSize.declared(4_294_967_299L + 9), FrameSize.declared(9, 4_294_967_299uL))
    }

    @Test
    fun theDeprecatedPeekResultBridgeComparesWithWhatHasArrived() {
        assertEquals(PeekResult.Complete(12), FrameSize.declared(12).toPeekResult(available = 12))
        assertEquals(PeekResult.NeedsMoreData, FrameSize.declared(12).toPeekResult(available = 11))
        assertEquals(PeekResult.NeedsMoreData, FrameSize.NeedsMoreData.toPeekResult(available = 100))
        assertEquals(PeekResult.NoFraming, FrameSize.Unframed.toPeekResult(available = 100))
        // An Int cannot carry the size, so the old API rejects it instead of wrapping it.
        val tooLarge = FrameSize.declared(Int.MAX_VALUE + 1L)
        val thrown = assertFailsWith<FrameTooLargeException> { tooLarge.toPeekResult(0) }
        assertEquals(Int.MAX_VALUE + 1L, thrown.declaredBytes)
    }

    @Test
    fun fromPeekResultMapsEveryState() {
        assertEquals(FrameSize.declared(12), FrameSize.from(PeekResult.Complete(12)))
        assertEquals(FrameSize.NeedsMoreData, FrameSize.from(PeekResult.NeedsMoreData))
        assertEquals(FrameSize.Unframed, FrameSize.from(PeekResult.NoFraming))
        assertFailsWith<FrameTooLargeException> { FrameSize.from(PeekResult.Complete(-1)) }
    }

    // ---- the two FrameDetector methods ---------------------------------------

    private class OldStyleDetector(
        private val result: PeekResult,
    ) : FrameDetector {
        override fun peekFrameSize(
            stream: StreamProcessor,
            baseOffset: Int,
        ): PeekResult = result
    }

    private object NewStyleOnlyDetector : FrameDetector {
        override fun frameSize(
            stream: StreamProcessor,
            baseOffset: Int,
        ): FrameSize = FrameSize.declared(6)
    }

    private fun emptyStream(): StreamProcessor = StreamProcessor.create(BufferPool(), ByteOrder.BIG_ENDIAN)

    @Test
    fun theDefaultFrameSizeAsksTheDeprecatedPeekFrameSize() {
        val stream = emptyStream()
        try {
            assertEquals(FrameSize.declared(12), OldStyleDetector(PeekResult.Complete(12)).frameSize(stream))
            assertEquals(FrameSize.NeedsMoreData, OldStyleDetector(PeekResult.NeedsMoreData).frameSize(stream))
            assertEquals(FrameSize.Unframed, OldStyleDetector(PeekResult.NoFraming).frameSize(stream))
            assertEquals(FrameSize.Unframed, object : FrameDetector {}.frameSize(stream))
        } finally {
            stream.release()
        }
    }

    /**
     * The migration hazard, pinned: the defaults cannot call each other both ways, so
     * `frameSize` defaults to `peekFrameSize` and a detector that overrides only
     * `frameSize` looks unframed to a caller still on `peekFrameSize`. The deprecation
     * message on `peekFrameSize` tells the author to forward it.
     */
    @Test
    fun aDetectorOverridingOnlyFrameSizeLooksUnframedToTheDeprecatedPeek() {
        val stream = emptyStream()
        try {
            assertEquals(FrameSize.declared(6), NewStyleOnlyDetector.frameSize(stream))
            assertEquals(PeekResult.NoFraming, NewStyleOnlyDetector.peekFrameSize(stream))
        } finally {
            stream.release()
        }
    }

    // ---- readFrame ------------------------------------------------------------

    private class StubCodec(
        private val size: FrameSize,
    ) : Codec<String> {
        override fun encode(
            buffer: WriteBuffer,
            value: String,
            context: EncodeContext,
        ) = Unit

        override fun decode(
            buffer: ReadBuffer,
            context: DecodeContext,
        ): String = "frame:${buffer.remaining()}"

        override fun frameSize(
            stream: StreamProcessor,
            baseOffset: Int,
        ): FrameSize = size
    }

    private fun streamOf(vararg bytes: Byte): StreamProcessor {
        val buf = BufferFactory.Default.allocate(bytes.size, ByteOrder.BIG_ENDIAN)
        for (b in bytes) buf.writeByte(b)
        buf.resetForRead()
        return emptyStream().also { it.append(buf) }
    }

    @Test
    fun readFrameRejectsADeclarationOverTheCeilingBeforeTheFrameArrives() {
        val stream = streamOf(1, 2)
        try {
            val overDefault = StubCodec(FrameSize.declared(DEFAULT_MAX_FRAME_BYTES + 1L))
            assertEquals(
                DEFAULT_MAX_FRAME_BYTES + 1L,
                assertFailsWith<FrameTooLargeException> { overDefault.readFrame(stream) }.declaredBytes,
            )
            val lowered = DecodeContext.Empty.with(MaxFrameBytesKey, 16)
            assertFailsWith<FrameTooLargeException> { StubCodec(FrameSize.declared(17)).readFrame(stream, lowered) }
            // Past Int range: rejected, never narrowed to an Int first.
            val huge = StubCodec(FrameSize.declared((1L shl 32) + 12))
            val hugeThrown = assertFailsWith<FrameTooLargeException> { huge.readFrame(stream) }
            assertEquals((1L shl 32) + 12, hugeThrown.declaredBytes)
            assertEquals(2, stream.available(), "rejection consumes nothing")
        } finally {
            stream.release()
        }
    }

    @Test
    fun readFrameWaitsUnderTheCeilingAndDecodesOnceTheFrameIsThere() {
        val stream = streamOf(1, 2, 3, 4)
        try {
            assertNull(StubCodec(FrameSize.declared(64)).readFrame(stream))
            assertNull(StubCodec(FrameSize.NeedsMoreData).readFrame(stream))
            assertEquals("frame:4", StubCodec(FrameSize.declared(4)).readFrame(stream))
            assertEquals(0, stream.available())
        } finally {
            stream.release()
        }
    }

    @Test
    fun readFrameRejectsAZeroSizeAndAnUnframedCodec() {
        val stream = emptyStream()
        try {
            assertFailsWith<FrameTooLargeException> { StubCodec(FrameSize.declared(0)).readFrame(stream) }
            val unframed = StubCodec(FrameSize.Unframed)
            val thrown = assertFailsWith<UnframedCodecException> { unframed.readFrame(stream) }
            assertEquals(unframed, thrown.detector)
            assertIs<IllegalStateException>(thrown)
        } finally {
            stream.release()
        }
    }

    @Test
    fun readFrameDecodesACompleteFrameFromAnOldStyleCodec() {
        val stream = streamOf(1, 2, 3, 4)
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

                    // Only the old method, so `readFrame` reaches it through the default `frameSize`.
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
