@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.readText
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Each interface with an old and a new size method, both ways round: a codec written against
 * the old API (overriding only what that API required) answers through the new methods, and a
 * codec written against the new API answers through the old ones. A codec overriding neither
 * terminates with the interface's constant answer.
 */
class DeprecatedSizeApiBridgeTest {
    private fun streamOf(vararg bytes: Int): StreamProcessor {
        val buf = BufferFactory.Default.allocate(maxOf(bytes.size, 1), ByteOrder.BIG_ENDIAN)
        for (b in bytes) buf.writeByte(b.toByte())
        buf.resetForRead()
        return StreamProcessor.create(BufferPool(), ByteOrder.BIG_ENDIAN).also { if (bytes.isNotEmpty()) it.append(buf) }
    }

    private inline fun <R> withStream(
        vararg bytes: Int,
        block: (StreamProcessor) -> R,
    ): R {
        val stream = streamOf(*bytes)
        try {
            return block(stream)
        } finally {
            stream.release()
        }
    }

    // ---- FrameDetector / Encoder ---------------------------------------------------------

    /** `[u8 length][body]`, written against the old API only. */
    private object OldStyleCodec : Codec<String> {
        override fun encode(
            buffer: WriteBuffer,
            value: String,
            context: EncodeContext,
        ) {
            buffer.writeByte(value.length.toByte())
            buffer.writeText(value)
        }

        override fun decode(
            buffer: ReadBuffer,
            context: DecodeContext,
        ): String = buffer.readText(buffer.readUnsignedByte().toInt())

        override fun wireSize(
            value: String,
            context: EncodeContext,
        ): WireSize = WireSize.Exact(1 + value.length)

        override fun peekFrameSize(
            stream: StreamProcessor,
            baseOffset: Int,
        ): PeekResult {
            if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
            val total = 1 + (stream.peekByte(baseOffset).toInt() and 0xFF)
            return if (stream.available() - baseOffset >= total) PeekResult.Complete(total) else PeekResult.NeedsMoreData
        }
    }

    /** The same wire format written against the new API, with the documented one-line forwarders. */
    private object NewStyleCodec : Codec<String> {
        override fun encode(
            buffer: WriteBuffer,
            value: String,
            context: EncodeContext,
        ) = OldStyleCodec.encode(buffer, value, context)

        override fun decode(
            buffer: ReadBuffer,
            context: DecodeContext,
        ): String = OldStyleCodec.decode(buffer, context)

        override fun wireBytes(
            value: String,
            context: EncodeContext,
        ): WireByteCount = WireByteCount.exact(1L + value.length)

        override fun frameSize(
            stream: StreamProcessor,
            baseOffset: Int,
        ): FrameSize =
            if (stream.available() - baseOffset < 1) {
                FrameSize.NeedsMoreData
            } else {
                FrameSize.declared(1, (stream.peekByte(baseOffset).toLong() and 0xFF))
            }

        override fun wireSize(
            value: String,
            context: EncodeContext,
        ): WireSize = wireBytes(value, context).toWireSize()

        override fun peekFrameSize(
            stream: StreamProcessor,
            baseOffset: Int,
        ): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
    }

    @Test
    fun anOldStyleDetectorAnswersThroughFrameSizeAndReadFrame() {
        withStream(3, 'a'.code, 'b'.code) { stream ->
            assertEquals(FrameSize.NeedsMoreData, OldStyleCodec.frameSize(stream), "the old API cannot declare early")
        }
        withStream(3, 'a'.code, 'b'.code, 'c'.code) { stream ->
            assertEquals(FrameSize.declared(0, 4L), OldStyleCodec.frameSize(stream))
            assertEquals("abc", OldStyleCodec.readFrame(stream))
        }
    }

    @Test
    fun aNewStyleDetectorAnswersThroughPeekFrameSize() {
        withStream(3, 'a'.code, 'b'.code) { stream ->
            assertEquals(FrameSize.declared(1, 3L), NewStyleCodec.frameSize(stream))
            assertEquals(PeekResult.NeedsMoreData, NewStyleCodec.peekFrameSize(stream))
        }
        withStream(3, 'a'.code, 'b'.code, 'c'.code) { stream ->
            assertEquals(PeekResult.Complete(4), NewStyleCodec.peekFrameSize(stream))
        }
        withStream { stream -> assertEquals(PeekResult.NeedsMoreData, NewStyleCodec.peekFrameSize(stream)) }
    }

    @Test
    fun anOldStyleEncoderAnswersThroughWireBytes() {
        assertEquals(WireByteCount.exact(3), OldStyleCodec.wireBytes("ab", EncodeContext.Empty))
        val buffer = OldStyleCodec.encodeToPlatformBuffer("ab")
        try {
            assertEquals(3, buffer.capacity, "the exact size reaches the allocation")
        } finally {
            buffer.freeNativeMemory()
        }
    }

    @Test
    fun aNewStyleEncoderAnswersThroughWireSize() {
        assertEquals(WireSize.Exact(3), NewStyleCodec.wireSize("ab", EncodeContext.Empty))
    }

    @Test
    fun aCodecOverridingNeitherTerminatesWithTheConstantAnswers() {
        val bare =
            object : Codec<Int> {
                override fun encode(
                    buffer: WriteBuffer,
                    value: Int,
                    context: EncodeContext,
                ) = Unit

                override fun decode(
                    buffer: ReadBuffer,
                    context: DecodeContext,
                ): Int = 0
            }
        withStream(1) { stream ->
            assertEquals(FrameSize.Unframed, bare.frameSize(stream))
            assertEquals(PeekResult.NoFraming, bare.peekFrameSize(stream))
        }
        assertEquals(WireByteCount.BackPatch, bare.wireBytes(1, EncodeContext.Empty))
        assertEquals(WireSize.BackPatch, bare.wireSize(1, EncodeContext.Empty))
    }

    @Test
    fun aNewStyleCodecWithoutTheForwardersLooksUnframedToTheOldApi() {
        val newOnly =
            object : Codec<Int> {
                override fun encode(
                    buffer: WriteBuffer,
                    value: Int,
                    context: EncodeContext,
                ) = Unit

                override fun decode(
                    buffer: ReadBuffer,
                    context: DecodeContext,
                ): Int = 0

                override fun frameSize(
                    stream: StreamProcessor,
                    baseOffset: Int,
                ): FrameSize = FrameSize.declared(0, 1L)

                override fun wireBytes(
                    value: Int,
                    context: EncodeContext,
                ): WireByteCount = WireByteCount.exact(1)
            }
        withStream(1) { stream -> assertEquals(PeekResult.NoFraming, newOnly.peekFrameSize(stream)) }
        assertEquals(WireSize.BackPatch, newOnly.wireSize(1, EncodeContext.Empty))
    }

    @Test
    fun theBridgesMapEveryState() {
        assertEquals(FrameSize.declared(0, 7L), FrameSize.from(PeekResult.Complete(7)))
        assertEquals(FrameSize.NeedsMoreData, FrameSize.from(PeekResult.NeedsMoreData))
        assertEquals(FrameSize.Unframed, FrameSize.from(PeekResult.NoFraming))
        assertFailsWith<FrameTooLargeException> { FrameSize.from(PeekResult.Complete(-1)) }
        assertEquals(PeekResult.Complete(5), FrameSize.declared(2, 3L).toPeekResult(available = 5))
        assertEquals(PeekResult.NeedsMoreData, FrameSize.declared(2, 3L).toPeekResult(available = 4))
        assertEquals(PeekResult.NoFraming, FrameSize.Unframed.toPeekResult(available = 4))
        val wide = assertFailsWith<FrameTooLargeException> { FrameSize.declared(1, Int.MAX_VALUE.toLong()).toPeekResult(0) }
        assertEquals(Int.MAX_VALUE + 1L, wide.declaredBytes)

        assertEquals(WireByteCount.exact(9), WireByteCount.from(WireSize.Exact(9)))
        assertEquals(WireByteCount.BackPatch, WireByteCount.from(WireSize.BackPatch))
        assertFailsWith<EncodeException> { WireByteCount.from(WireSize.Exact(-1)) }
        assertEquals(WireSize.Exact(9), WireByteCount.exact(9).toWireSize())
        assertEquals(WireSize.BackPatch, WireByteCount.exact(Int.MAX_VALUE + 1L).toWireSize())
        assertEquals(WireSize.BackPatch, WireByteCount.BackPatch.toWireSize())
    }

    // ---- VariableLengthCodec -------------------------------------------------------------

    /** A one-or-two-byte varint: high bit of byte 0 set means a second byte follows. */
    private interface TwoByteVarint : VariableLengthCodec<Int> {
        override fun encodedLength(value: Int): Int = if (value < 0x80) 1 else 2

        override fun encode(
            buffer: WriteBuffer,
            value: Int,
            context: EncodeContext,
        ) {
            if (value < 0x80) {
                buffer.writeByte(value.toByte())
            } else {
                buffer.writeByte((0x80 or (value shr 8)).toByte())
                buffer.writeByte(value.toByte())
            }
        }

        override fun decode(
            buffer: ReadBuffer,
            context: DecodeContext,
        ): Int {
            val first = buffer.readUnsignedByte().toInt()
            return if (first < 0x80) first else ((first and 0x7F) shl 8) or buffer.readUnsignedByte().toInt()
        }

        override fun peekValue(
            stream: StreamProcessor,
            baseOffset: Int,
        ): VarLenPeek<Int> {
            if (stream.available() - baseOffset < 1) return VarLenPeek.NeedsMoreData
            val first = stream.peekByte(baseOffset).toInt() and 0xFF
            if (first < 0x80) return VarLenPeek.Decoded(first, 1)
            if (stream.available() - baseOffset < 2) return VarLenPeek.NeedsMoreData
            return VarLenPeek.Decoded(((first and 0x7F) shl 8) or (stream.peekByte(baseOffset + 1).toInt() and 0xFF), 2)
        }
    }

    /** Overrides exactly what main required: decode, encode, encodedLength, peekValue. */
    private object OldStyleVarint : TwoByteVarint

    /** Adds the width-only frameSize the new API asks for. */
    private object NewStyleVarint : TwoByteVarint {
        override fun frameSize(
            stream: StreamProcessor,
            baseOffset: Int,
        ): FrameSize {
            if (stream.available() - baseOffset < 1) return FrameSize.NeedsMoreData
            val width = if (stream.peekByte(baseOffset).toInt() and 0x80 == 0) 1 else 2
            return if (stream.available() - baseOffset < width) FrameSize.NeedsMoreData else FrameSize.declared(0, width.toLong())
        }
    }

    @Test
    fun anOldStyleVarintAnswersThroughFrameSizeAndWireBytes() {
        withStream(0x81) { stream -> assertEquals(FrameSize.NeedsMoreData, OldStyleVarint.frameSize(stream)) }
        withStream(0x81, 0x02) { stream -> assertEquals(FrameSize.declared(0, 2L), OldStyleVarint.frameSize(stream)) }
        assertEquals(WireByteCount.exact(2), OldStyleVarint.wireBytes(0x102, EncodeContext.Empty))
    }

    @Test
    fun aNewStyleVarintAnswersThroughTheOldApi() {
        withStream(0x81) { stream -> assertEquals(PeekResult.NeedsMoreData, NewStyleVarint.peekFrameSize(stream)) }
        withStream(0x81, 0x02) { stream ->
            assertEquals(PeekResult.Complete(2), NewStyleVarint.peekFrameSize(stream))
            assertEquals(VarLenPeek.Decoded(0x102, 2), NewStyleVarint.peekValue(stream))
        }
        assertEquals(WireSize.Exact(1), NewStyleVarint.wireSize(0x7F, EncodeContext.Empty))
    }

    // ---- BoundingLengthCodec -------------------------------------------------------------

    /** A big-endian u16 length, overriding exactly what main required. */
    private object OldStyleLength : BoundingLengthCodec<UInt> {
        override val maxWireSize: Int = 2

        override fun decode(
            buffer: ReadBuffer,
            context: DecodeContext,
        ): UInt = buffer.readUShort().toUInt()

        override fun encode(
            buffer: WriteBuffer,
            value: UInt,
            context: EncodeContext,
        ) {
            buffer.writeUShort(value.toUShort())
        }

        override fun wireSize(
            value: UInt,
            context: EncodeContext,
        ): WireSize = WireSize.Exact(2)

        override fun applyBound(
            buffer: ReadBuffer,
            decodedValue: UInt,
        ) {
            buffer.setLimit(buffer.position() + decodedValue.toInt())
        }
    }

    /** The same field written against the new API, with the wireSize forwarder. */
    private object NewStyleLength : BoundingLengthCodec<UInt> {
        override val maxWireSize: Int = 2

        override fun decode(
            buffer: ReadBuffer,
            context: DecodeContext,
        ): UInt = OldStyleLength.decode(buffer, context)

        override fun encode(
            buffer: WriteBuffer,
            value: UInt,
            context: EncodeContext,
        ) = OldStyleLength.encode(buffer, value, context)

        override fun wireBytes(
            value: UInt,
            context: EncodeContext,
        ): WireByteCount = WireByteCount.exact(2)

        override fun wireSize(
            value: UInt,
            context: EncodeContext,
        ): WireSize = wireBytes(value, context).toWireSize()

        override fun peekLength(
            stream: StreamProcessor,
            baseOffset: Int,
        ): FrameSize =
            if (stream.available() - baseOffset < 2) {
                FrameSize.NeedsMoreData
            } else {
                FrameSize.declared(2, (stream.peekByte(baseOffset).toLong() and 0xFF shl 8) or (stream.peekByte(baseOffset + 1).toLong() and 0xFF))
            }

        override fun encodedWidth(bodyBytes: Long): WireByteCount = WireByteCount.exact(2)

        override fun applyBound(
            buffer: ReadBuffer,
            decodedValue: UInt,
        ) = OldStyleLength.applyBound(buffer, decodedValue)
    }

    @Test
    fun anOldStyleLengthCodecAnswersThroughPeekLengthByDecoding() {
        withStream(0x01) { stream -> assertEquals(FrameSize.NeedsMoreData, OldStyleLength.peekLength(stream, 0)) }
        withStream(0x01, 0x02, 0xAA) { stream ->
            assertEquals(FrameSize.declared(2, 0x102L), OldStyleLength.peekLength(stream, 0))
            assertEquals(FrameSize.NeedsMoreData, OldStyleLength.peekLength(stream, 2))
            assertEquals(3, stream.available(), "the peek consumes nothing")
        }
        assertEquals(WireByteCount.BackPatch, OldStyleLength.encodedWidth(5))
    }

    @Test
    fun framedEncoderSizesAnOldStyleLengthThroughWireBytes() {
        for (codec in listOf<BoundingLengthCodec<UInt>>(OldStyleLength, NewStyleLength)) {
            val framed =
                FramedEncoder.encode(BufferFactory.Default, codec, EncodeContext.Empty) { body ->
                    body.writeByte(7)
                    body.writeByte(8)
                }
            assertEquals(4, framed.remaining())
            assertEquals(2, framed.readUnsignedShort().toInt())
            assertEquals(7, framed.readByte().toInt())
        }
    }

    @Test
    fun aNewStyleLengthCodecAnswersThroughTheOldApi() {
        assertEquals(WireSize.Exact(2), NewStyleLength.wireSize(9u, EncodeContext.Empty))
        withStream(0x00, 0x03) { stream -> assertEquals(FrameSize.declared(2, 3L), NewStyleLength.peekLength(stream, 0)) }
    }
}
