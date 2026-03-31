package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.test.protocols.AnimChunk
import com.ditchoom.buffer.codec.test.protocols.AnimChunkCodec
import com.ditchoom.buffer.codec.test.protocols.AnimChunkImageFrameCodec
import com.ditchoom.buffer.codec.test.protocols.Frame
import com.ditchoom.buffer.codec.test.protocols.FrameCodec
import com.ditchoom.buffer.codec.test.protocols.ImageSize
import com.ditchoom.buffer.codec.test.protocols.Packet
import com.ditchoom.buffer.codec.test.protocols.PacketCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for deep codec composition via context-based payload lambda registration.
 * Validates that payload sealed interfaces work as nested fields and that
 * impossible states are prevented with clear errors.
 */
class DeepNestingTest {
    private val decodeCtx =
        DecodeContext.Empty.with(AnimChunkImageFrameCodec.BitmapDecodeKey) { pr ->
            pr.readString(pr.remaining())
        }

    private val encodeCtx =
        EncodeContext.Empty.with(AnimChunkImageFrameCodec.BitmapEncodeKey) { buf, v ->
            buf.writeString(v as String)
        }

    // ========== Convention 2: Context-based decode round-trips ==========

    @Test
    fun contextBasedDecodeRoundTripsHeader() {
        val original: AnimChunk = AnimChunk.Header(magic = 0x414E494D, version = 1u, frameCount = 24u)
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        AnimChunkCodec.encode(buffer, original, encodeCtx)
        buffer.resetForRead()
        val decoded = AnimChunkCodec.decode(buffer, decodeCtx)
        assertTrue(decoded is AnimChunk.Header)
        assertEquals(original, decoded)
    }

    @Test
    fun contextBasedDecodeRoundTripsPayloadVariant() {
        val original: AnimChunk =
            AnimChunk.ImageFrame(
                index = 0u,
                size = ImageSize.of(10u.toUShort(), 10u.toUShort()),
                bitmapLength = 5,
                bitmap = "hello",
            )
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        AnimChunkCodec.encode(buffer, original, encodeCtx)
        buffer.resetForRead()
        val decoded = AnimChunkCodec.decode(buffer, decodeCtx)
        assertTrue(decoded is AnimChunk.ImageFrame<*>)
        assertEquals("hello", (decoded as AnimChunk.ImageFrame<*>).bitmap)
    }

    // ========== Nested sealed in @ProtocolMessage ==========

    @Test
    fun frameWithHeaderChunkRoundTrips() {
        val original =
            Frame(
                version = 1u,
                chunk = AnimChunk.Header(magic = 0x414E494D, version = 1u, frameCount = 10u),
            )
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        FrameCodec.encode(buffer, original, encodeCtx)
        buffer.resetForRead()
        val decoded = FrameCodec.decode(buffer, decodeCtx)
        assertEquals(original.version, decoded.version)
        assertTrue(decoded.chunk is AnimChunk.Header)
        assertEquals(original.chunk, decoded.chunk)
    }

    @Test
    fun frameWithPayloadChunkRoundTrips() {
        val original =
            Frame(
                version = 2u,
                chunk =
                    AnimChunk.ImageFrame(
                        index = 5u,
                        size = ImageSize.of(320u.toUShort(), 240u.toUShort()),
                        bitmapLength = 5,
                        bitmap = "world",
                    ),
            )
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        FrameCodec.encode(buffer, original, encodeCtx)
        buffer.resetForRead()
        val decoded = FrameCodec.decode(buffer, decodeCtx)
        assertEquals(2u.toUByte(), decoded.version)
        assertTrue(decoded.chunk is AnimChunk.ImageFrame<*>)
        assertEquals("world", (decoded.chunk as AnimChunk.ImageFrame<*>).bitmap)
    }

    // ========== Sealed inside sealed ==========

    @Test
    fun packetControlRoundTrips() {
        val original: Packet = Packet.Control(flags = 0xFFu)
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        PacketCodec.encode(buffer, original, encodeCtx)
        buffer.resetForRead()
        val decoded = PacketCodec.decode(buffer, decodeCtx)
        assertTrue(decoded is Packet.Control)
        assertEquals(original, decoded)
    }

    @Test
    fun packetMediaWithPayloadChunkRoundTrips() {
        val original: Packet =
            Packet.Media(
                chunk =
                    AnimChunk.ImageFrame(
                        index = 1u,
                        size = ImageSize.of(640u.toUShort(), 480u.toUShort()),
                        bitmapLength = 3,
                        bitmap = "abc",
                    ),
            )
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        PacketCodec.encode(buffer, original, encodeCtx)
        buffer.resetForRead()
        val decoded = PacketCodec.decode(buffer, decodeCtx)
        assertTrue(decoded is Packet.Media)
        val media = decoded as Packet.Media
        assertTrue(media.chunk is AnimChunk.ImageFrame<*>)
        assertEquals("abc", (media.chunk as AnimChunk.ImageFrame<*>).bitmap)
    }

    // ========== Impossible state prevention ==========

    @Test
    fun missingDecodeKeyGivesClearError() {
        val original: AnimChunk =
            AnimChunk.ImageFrame(
                index = 0u,
                size = ImageSize.of(1u.toUShort(), 1u.toUShort()),
                bitmapLength = 1,
                bitmap = "x",
            )
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        // Encode with Convention 1 (direct lambda)
        AnimChunkCodec.encode<String>(buffer, original, encodeImageFrameBitmap = { buf, s -> buf.writeString(s) })
        buffer.resetForRead()

        // Decode with empty context — should fail with clear message
        val error =
            assertFailsWith<IllegalStateException> {
                AnimChunkCodec.decode(buffer, DecodeContext.Empty)
            }
        assertTrue(error.message!!.contains("BitmapDecodeKey"))
    }

    @Test
    fun missingEncodeKeyGivesClearError() {
        val original: AnimChunk =
            AnimChunk.ImageFrame(
                index = 0u,
                size = ImageSize.of(1u.toUShort(), 1u.toUShort()),
                bitmapLength = 1,
                bitmap = "x",
            )
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)

        val error =
            assertFailsWith<IllegalStateException> {
                AnimChunkCodec.encode(buffer, original, EncodeContext.Empty)
            }
        assertTrue(error.message!!.contains("BitmapEncodeKey"))
    }

    // ========== Convention 1 still works (backward compat) ==========

    @Test
    fun convention1DirectLambdaStillWorks() {
        val original: AnimChunk =
            AnimChunk.ImageFrame(
                index = 0u,
                size = ImageSize.of(10u.toUShort(), 10u.toUShort()),
                bitmapLength = 5,
                bitmap = "hello",
            )
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        AnimChunkCodec.encode<String>(buffer, original, encodeImageFrameBitmap = { buf, s -> buf.writeString(s) })
        buffer.resetForRead()
        val decoded = AnimChunkCodec.decode<String>(buffer, decodeImageFrameBitmap = { pr -> pr.readString(pr.remaining()) })
        assertTrue(decoded is AnimChunk.ImageFrame<*>)
        assertEquals("hello", (decoded as AnimChunk.ImageFrame<*>).bitmap)
    }

    // ========== Edge case: empty payload ==========

    @Test
    fun emptyPayloadRoundTrips() {
        val original: AnimChunk =
            AnimChunk.ImageFrame(
                index = 0u,
                size = ImageSize.of(0u.toUShort(), 0u.toUShort()),
                bitmapLength = 0,
                bitmap = "",
            )
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        AnimChunkCodec.encode(buffer, original, encodeCtx)
        buffer.resetForRead()
        val decoded = AnimChunkCodec.decode(buffer, decodeCtx)
        assertTrue(decoded is AnimChunk.ImageFrame<*>)
        assertEquals("", (decoded as AnimChunk.ImageFrame<*>).bitmap)
    }
}
