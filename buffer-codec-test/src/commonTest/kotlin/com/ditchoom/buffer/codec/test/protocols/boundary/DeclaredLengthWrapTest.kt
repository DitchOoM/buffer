package com.ditchoom.buffer.codec.test.protocols.boundary

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.Decoder
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.MaxFrameBytesKey
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.readFrame
import com.ditchoom.buffer.codec.test.protocols.http3.Http3FrameSettingsCodec
import com.ditchoom.buffer.codec.test.protocols.http3fc.Http3FcFrameCodec
import com.ditchoom.buffer.codec.test.protocols.http3fc.Http3FcFrameDataCodec
import com.ditchoom.buffer.codec.test.protocols.lengthprefixedusecodec.Le32PropertyBagCodec
import com.ditchoom.buffer.codec.test.protocols.usecodecscalar.BoundedFrameCodec
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A bounding length is a number the peer chose. Its frame total must be
 * computed without wrapping: a declaration past `Int` range is rejected with
 * [FrameTooLargeException] by peek and by decode, never folded into a small,
 * plausible size that passes the frame-size ceiling and decodes.
 */
class DeclaredLengthWrapTest {
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun le32(value: Long): ByteArray = ByteArray(4) { (value ushr (8 * it)).toByte() }

    private fun quicVarint8(value: Long): ByteArray =
        ByteArray(8) { i -> (value ushr (8 * (7 - i))).toByte() }.also { it[0] = (it[0].toInt() or 0xC0).toByte() }

    private fun bufferOf(data: ByteArray): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(data.size, ByteOrder.BIG_ENDIAN)
        for (b in data) buf.writeByte(b)
        buf.resetForRead()
        return buf
    }

    private fun <R> withStream(
        data: ByteArray,
        block: (StreamProcessor) -> R,
    ): R {
        val stream = StreamProcessor.create(BufferPool(), ByteOrder.BIG_ENDIAN)
        stream.append(bufferOf(data))
        return try {
            block(stream)
        } finally {
            stream.release()
        }
    }

    private fun peek(
        detector: FrameDetector,
        data: ByteArray,
    ): PeekResult = withStream(data) { detector.peekFrameSize(it) }

    private fun <T> decode(
        decoder: Decoder<T>,
        data: ByteArray,
    ): T = decoder.decode(bufferOf(data), DecodeContext.Empty)

    // HTTP/3 SETTINGS: QUIC-varint type, QUIC-varint (ULong) bounding length.

    private val settingsLengthTwoToThe32Plus3 =
        bytes(0x04, 0xC0, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x03, 0x01, 0x02, 0x03)
    private val settingsLengthTwoToThe32Minus1 = bytes(0x04, 0xC0, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF)

    @Test
    fun settingsDeclaring2To32Plus3IsRejectedByPeek() {
        val thrown =
            assertFailsWith<FrameTooLargeException> { peek(Http3FrameSettingsCodec, settingsLengthTwoToThe32Plus3) }
        assertEquals(1L + 8 + (1L shl 32) + 3, thrown.declaredBytes)
        assertEquals(Int.MAX_VALUE, thrown.maxBytes)
    }

    @Test
    fun settingsDeclaring2To32Plus3IsRejectedByDecode() {
        assertFailsWith<FrameTooLargeException> { decode(Http3FrameSettingsCodec, settingsLengthTwoToThe32Plus3) }
    }

    @Test
    fun settingsDeclaring2To32Plus3IsRejectedByReadFrame() {
        withStream(settingsLengthTwoToThe32Plus3) { stream ->
            assertFailsWith<FrameTooLargeException> { Http3FrameSettingsCodec.readFrame(stream) }
        }
    }

    @Test
    fun settingsDeclaring2To32Minus1IsRejectedByPeekAndDecode() {
        assertFailsWith<FrameTooLargeException> { peek(Http3FrameSettingsCodec, settingsLengthTwoToThe32Minus1) }
        assertFailsWith<FrameTooLargeException> { decode(Http3FrameSettingsCodec, settingsLengthTwoToThe32Minus1) }
    }

    // BoundedFrame: 2-byte tag, 4-byte little-endian UInt bounding length. Header = 6 bytes.

    private fun boundedFrame(
        length: Long,
        body: Int = 0,
    ): ByteArray = bytes(0x00, 0x01) + le32(length) + ByteArray(body)

    @Test
    fun boundedFrameTotalExactlyIntMaxIsStillAPlausibleIncompleteFrame() {
        assertEquals(PeekResult.NeedsMoreData, peek(BoundedFrameCodec, boundedFrame(Int.MAX_VALUE - 6L)))
        // Inside Int range, decode fails on the missing bytes, not on the size.
        val thrown = assertFailsWith<DecodeException> { decode(BoundedFrameCodec, boundedFrame(Int.MAX_VALUE - 6L)) }
        assertEquals(false, thrown is FrameTooLargeException)
    }

    @Test
    fun boundedFrameTotalOnePastIntMaxIsRejectedByPeek() {
        val thrown =
            assertFailsWith<FrameTooLargeException> { peek(BoundedFrameCodec, boundedFrame(Int.MAX_VALUE - 5L)) }
        assertEquals(Int.MAX_VALUE + 1L, thrown.declaredBytes)
    }

    @Test
    fun boundedFrameLengthsPastIntRangeAreRejectedByPeekAndDecode() {
        for (length in listOf(Int.MAX_VALUE.toLong(), 0x8000_0000L, 0x8000_0003L, 0xFFFF_FFFFL)) {
            val frame = boundedFrame(length, body = 3)
            val thrown = assertFailsWith<FrameTooLargeException>("peek, $length") { peek(BoundedFrameCodec, frame) }
            assertEquals(6L + length, thrown.declaredBytes)
            // Int.MAX_VALUE itself is a representable body: decode rejects it as truncated.
            val decodeFailure = assertFailsWith<DecodeException>("decode, $length") { decode(BoundedFrameCodec, frame) }
            assertEquals(length > Int.MAX_VALUE, decodeFailure is FrameTooLargeException, "$length")
        }
    }

    @Test
    fun boundedFrameAtAndPastTheConfiguredCeiling() {
        val ceiling = DecodeContext.Empty.with(MaxFrameBytesKey, 64)
        withStream(boundedFrame(58, body = 58)) { stream ->
            assertEquals(58, BoundedFrameCodec.readFrame(stream, ceiling)?.length?.toInt())
        }
        withStream(boundedFrame(59, body = 59)) { stream ->
            val thrown = assertFailsWith<FrameTooLargeException> { BoundedFrameCodec.readFrame(stream, ceiling) }
            assertEquals(65L, thrown.declaredBytes)
            assertEquals(64, thrown.maxBytes)
        }
    }

    // `@LengthPrefixed @UseCodec List<E>` with a 4-byte LE UInt length. Header = 4 bytes.

    @Test
    fun lengthPrefixedListPastIntRangeIsRejectedByPeekAndDecode() {
        assertEquals(PeekResult.NeedsMoreData, peek(Le32PropertyBagCodec, le32(Int.MAX_VALUE - 4L)))
        for (length in listOf(Int.MAX_VALUE - 3L, 0x8000_0000L, 0xFFFF_FFFFL)) {
            val thrown =
                assertFailsWith<FrameTooLargeException>("peek, $length") { peek(Le32PropertyBagCodec, le32(length)) }
            assertEquals(4L + length, thrown.declaredBytes)
        }
        for (length in listOf(0x8000_0000L, 0x8000_0005L, 0xFFFF_FFFFL)) {
            val frame = le32(length) + ByteArray(5)
            assertFailsWith<FrameTooLargeException>("decode, $length") { decode(Le32PropertyBagCodec, frame) }
        }
    }

    // `@FramedBy` HTTP/3 frames: QUIC-varint type, QUIC-varint length narrowed to UInt.

    private fun framed(
        type: Int,
        length: Long,
    ): ByteArray = bytes(type) + quicVarint8(length) + ByteArray(5)

    @Test
    fun framedByLengthPastIntRangeIsRejectedByDispatcherAndVariantPeek() {
        for (length in listOf(0x8000_0000L, 0x8000_0003L, 0xFFFF_FFFFL)) {
            val frame = framed(0x00, length)
            assertFailsWith<FrameTooLargeException>("dispatcher, $length") { peek(Http3FcFrameCodec, frame) }
            assertFailsWith<FrameTooLargeException>("variant, $length") { peek(Http3FcFrameDataCodec, frame) }
        }
    }

    @Test
    fun framedByLengthPastIntRangeIsRejectedByDecodeForKnownAndUnknownTypes() {
        for (length in listOf(0x8000_0000L, 0x8000_0003L, 0xFFFF_FFFFL)) {
            assertFailsWith<FrameTooLargeException>("known, length=$length") {
                Http3FcFrameCodec.decode(bufferOf(framed(0x00, length)), DecodeContext.Empty)
            }
            // An unknown type takes the @ForwardCompatible preserve arm, which allocates the
            // declared length: it must be rejected before that allocation.
            assertFailsWith<FrameTooLargeException>("unknown, length=$length") {
                Http3FcFrameCodec.decode(bufferOf(framed(0x21, length)), DecodeContext.Empty)
            }
        }
    }

    @Test
    fun aDeclarationThatFitsStillDecodes() {
        val frame = bytes(0x04, 0x03, 0x01, 0x02, 0x03)
        withStream(frame) { stream ->
            assertEquals(PeekResult.Complete(5), Http3FrameSettingsCodec.peekFrameSize(stream))
            assertEquals(3uL, Http3FrameSettingsCodec.readFrame(stream)?.length)
        }
    }

    @Test
    fun aStreamWithNothingAfterAnUnderCeilingDeclarationWaits() {
        withStream(boundedFrame(16, body = 1)) { stream ->
            assertNull(BoundedFrameCodec.readFrame(stream))
        }
    }
}
