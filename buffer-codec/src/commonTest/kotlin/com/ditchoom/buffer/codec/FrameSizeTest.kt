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
        size.fold(
            declared = { header, body -> "declared:$header+$body" },
            needsMoreData = { "needsMoreData" },
            unframed = { "unframed" },
        )

    @Test
    fun eachStateFoldsToItsOwnBranch() {
        assertEquals("declared:0+0", describe(FrameSize.declared(0, 0L)))
        assertEquals("declared:9+42", describe(FrameSize.declared(9, 42L)))
        assertEquals("needsMoreData", describe(FrameSize.NeedsMoreData))
        assertEquals("unframed", describe(FrameSize.Unframed))
        assertEquals(FrameSize.declared(9, 42L), FrameSize.declared(9, 42L))
        assertEquals("FrameSize.declared(headerBytes=9, bodyBytes=42)", FrameSize.declared(9, 42L).toString())
    }

    @Test
    fun headerAndBodySurviveAtTheEdgesOfTheirRanges() {
        val max = FrameSize.declared(FrameSize.MAX_HEADER_BYTES, FrameSize.MAX_BODY_BYTES)
        assertEquals("declared:${FrameSize.MAX_HEADER_BYTES}+${FrameSize.MAX_BODY_BYTES}", describe(max))
        assertEquals("declared:${FrameSize.MAX_HEADER_BYTES}+0", describe(FrameSize.declared(FrameSize.MAX_HEADER_BYTES, 0L)))
        assertEquals("declared:0+${FrameSize.MAX_BODY_BYTES}", describe(FrameSize.declared(0, FrameSize.MAX_BODY_BYTES)))
        // The largest declared size is still neither sentinel.
        assertEquals(FrameSize.MAX_HEADER_BYTES + FrameSize.MAX_BODY_BYTES, max.foldTotal({ it }, { -1 }, { -2 }))
    }

    @Test
    fun aBodyPastItsRangeSaturates() {
        val saturated = "declared:4+${FrameSize.MAX_BODY_BYTES}"
        assertEquals(saturated, describe(FrameSize.declared(4, FrameSize.MAX_BODY_BYTES + 1)))
        assertEquals(saturated, describe(FrameSize.declared(4, Long.MAX_VALUE)))
        assertEquals(saturated, describe(FrameSize.declared(4, ULong.MAX_VALUE)))
        assertEquals("declared:9+4294967299", describe(FrameSize.declared(9, 4_294_967_299uL)))
    }

    @Test
    fun anImpossibleShapeIsRefused() {
        assertEquals(-1L, assertFailsWith<FrameTooLargeException> { FrameSize.declared(0, -1L) }.declaredBytes)
        assertEquals(-4L, assertFailsWith<FrameTooLargeException> { FrameSize.declared(-4, 5L) }.declaredBytes)
        val wide = assertFailsWith<FrameTooLargeException> { FrameSize.declared(FrameSize.MAX_HEADER_BYTES + 1, 0L) }
        assertEquals(FrameSize.MAX_HEADER_BYTES + 1L, wide.declaredBytes)
        assertEquals(FrameSize.MAX_HEADER_BYTES, wide.maxBytes)
    }

    @Test
    fun foldTotalAddsHeaderAndBody() {
        assertEquals(51L, FrameSize.declared(9, 42L).foldTotal({ it }, { -1 }, { -2 }))
        assertEquals(-1L, FrameSize.NeedsMoreData.foldTotal({ it }, { -1 }, { -2 }))
        assertEquals(-2L, FrameSize.Unframed.foldTotal({ it }, { -1 }, { -2 }))
    }

    @Test
    fun aDetectorThatOverridesNothingIsUnframed() {
        val stream = StreamProcessor.create(BufferPool(), ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(FrameSize.Unframed, object : FrameDetector {}.frameSize(stream))
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
        return StreamProcessor.create(BufferPool(), ByteOrder.BIG_ENDIAN).also { it.append(buf) }
    }

    @Test
    fun readFrameRejectsADeclarationOverTheCeilingBeforeTheFrameArrives() {
        val stream = streamOf(1, 2)
        try {
            val overDefault = StubCodec(FrameSize.declared(1, DEFAULT_MAX_FRAME_BYTES.toLong()))
            val thrown = assertFailsWith<FrameTooLargeException> { overDefault.readFrame(stream) }
            assertEquals(DEFAULT_MAX_FRAME_BYTES + 1L, thrown.declaredBytes)
            val lowered = DecodeContext.Empty.with(MaxFrameBytesKey, 16)
            assertFailsWith<FrameTooLargeException> { StubCodec(FrameSize.declared(2, 15L)).readFrame(stream, lowered) }
            val huge = StubCodec(FrameSize.declared(9, (1L shl 32) + 3))
            assertEquals((1L shl 32) + 12, assertFailsWith<FrameTooLargeException> { huge.readFrame(stream) }.declaredBytes)
            assertEquals(2, stream.available(), "rejection consumes nothing")
        } finally {
            stream.release()
        }
    }

    @Test
    fun readFrameWaitsUnderTheCeilingAndDecodesOnceTheFrameIsThere() {
        val stream = streamOf(1, 2, 3, 4)
        try {
            assertNull(StubCodec(FrameSize.declared(4, 60L)).readFrame(stream))
            assertNull(StubCodec(FrameSize.NeedsMoreData).readFrame(stream))
            assertEquals("frame:4", StubCodec(FrameSize.declared(1, 3L)).readFrame(stream))
            assertEquals(0, stream.available())
        } finally {
            stream.release()
        }
    }

    @Test
    fun readFrameRejectsAZeroSizeAndAnUnframedCodec() {
        val stream = StreamProcessor.create(BufferPool(), ByteOrder.BIG_ENDIAN)
        try {
            assertFailsWith<FrameTooLargeException> { StubCodec(FrameSize.declared(0, 0L)).readFrame(stream) }
            val unframed = StubCodec(FrameSize.Unframed)
            val thrown = assertFailsWith<UnframedCodecException> { unframed.readFrame(stream) }
            assertEquals(unframed, thrown.detector)
            assertIs<IllegalStateException>(thrown)
        } finally {
            stream.release()
        }
    }
}
