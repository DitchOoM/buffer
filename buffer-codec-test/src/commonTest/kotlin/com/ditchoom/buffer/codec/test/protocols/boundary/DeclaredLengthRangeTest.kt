package com.ditchoom.buffer.codec.test.protocols.boundary

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.Decoder
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.test.protocols.count.CountFixedListCodec
import com.ditchoom.buffer.codec.test.protocols.simple.IntPrefixedStringCodec
import com.ditchoom.buffer.codec.test.protocols.simple.SignedLengthFromFrameCodec
import com.ditchoom.buffer.codec.test.protocols.simple.WideLengthFrameCodec
import com.ditchoom.buffer.codec.test.protocols.usecodecscalar.SignedBoundedFrameCodec
import com.ditchoom.buffer.codec.test.protocols.usecodecscalar.U64BoundedFrameCodec
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Every shape whose wire carries a length or a count rejects a value no buffer can
 * address with the same typed failure, [FrameTooLargeException]: past `Int` range
 * (including a u64 at or past 2^63), negative from a signed carrier, or an element
 * count that a loop cannot run. A rejecting peek consumes nothing.
 */
class DeclaredLengthRangeTest {
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun be(
        value: Long,
        width: Int,
    ): ByteArray = ByteArray(width) { i -> (value ushr (8 * (width - 1 - i))).toByte() }

    private fun bufferOf(data: ByteArray): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(data.size, ByteOrder.BIG_ENDIAN)
        for (b in data) buf.writeByte(b)
        buf.resetForRead()
        return buf
    }

    /** Peeks [data] and asserts the rejection left every byte in the stream. */
    private fun rejectedByPeek(
        detector: FrameDetector,
        data: ByteArray,
    ): FrameTooLargeException {
        val stream = StreamProcessor.create(BufferPool(), ByteOrder.BIG_ENDIAN)
        stream.append(bufferOf(data))
        try {
            val thrown = assertFailsWith<FrameTooLargeException> { detector.peekFrameSize(stream) }
            assertEquals(data.size, stream.available(), "a rejecting peek consumes nothing")
            return thrown
        } finally {
            stream.release()
        }
    }

    private fun <T> rejectedByDecode(
        decoder: Decoder<T>,
        data: ByteArray,
    ): FrameTooLargeException = assertFailsWith { decoder.decode(bufferOf(data), DecodeContext.Empty) }

    @Test
    fun aU64LengthAtOrPast2To63IsRejectedNotWrappedNegative() {
        for (length in listOf(Long.MIN_VALUE, -1L)) { // 2^63 and 2^64-1 as raw bits
            val frame = bytes(0x00, 0x01) + be(length, 8) + ByteArray(3)
            assertEquals(Long.MAX_VALUE, rejectedByPeek(U64BoundedFrameCodec, frame).declaredBytes)
            assertEquals(Long.MAX_VALUE, rejectedByDecode(U64BoundedFrameCodec, frame).declaredBytes)
        }
    }

    @Test
    fun aNegativeSignedBoundingLengthIsRejected() {
        for (length in listOf(-1L, Int.MIN_VALUE.toLong())) {
            val frame = bytes(0x00, 0x01) + be(length, 4) + ByteArray(3)
            assertEquals(length, rejectedByPeek(SignedBoundedFrameCodec, frame).declaredBytes)
            assertEquals(length, rejectedByDecode(SignedBoundedFrameCodec, frame).declaredBytes)
        }
    }

    @Test
    fun aNegativeLengthFromSiblingIsRejected() {
        for (length in listOf(-1L, -128L)) {
            val frame = be(length, 1) + bytes(0x00) + ByteArray(3)
            rejectedByPeek(SignedLengthFromFrameCodec, frame)
            assertEquals(length, rejectedByDecode(SignedLengthFromFrameCodec, frame).declaredBytes)
        }
    }

    @Test
    fun anUnsignedLengthFromSiblingPastIntRangeIsRejectedWithTheTypedFailure() {
        val frame = be(0xFFFF_FFFFL, 4) + bytes(0x00) + ByteArray(3)
        assertEquals(0xFFFF_FFFFL, rejectedByPeek(WideLengthFrameCodec, frame).declaredBytes)
        assertEquals(0xFFFF_FFFFL, rejectedByDecode(WideLengthFrameCodec, frame).declaredBytes)
    }

    @Test
    fun aLengthPrefixPastIntRangeIsRejectedWithTheTypedFailure() {
        val frame = be(0x8000_0000L, 4) + ByteArray(3)
        assertEquals(4L + 0x8000_0000L, rejectedByPeek(IntPrefixedStringCodec, frame).declaredBytes)
        assertEquals(0x8000_0000L, rejectedByDecode(IntPrefixedStringCodec, frame).declaredBytes)
    }

    @Test
    fun anElementCountPastIntRangeIsRejectedNotLoopedZeroTimes() {
        // id, then an unsigned LEB128 count of 2^31 and of 2^32-1.
        for ((count, varint) in listOf(
            0x8000_0000L to bytes(0x80, 0x80, 0x80, 0x80, 0x08),
            0xFFFF_FFFFL to bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x0F),
        )) {
            val frame = bytes(0x01) + varint + ByteArray(6)
            assertEquals(count, rejectedByDecode(CountFixedListCodec, frame).declaredBytes)
        }
    }
}
