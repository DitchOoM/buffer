package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.AnimChunk
import com.ditchoom.buffer.codec.test.protocols.AnimChunkCodec
import com.ditchoom.buffer.codec.test.protocols.AnimChunkHeaderCodec
import com.ditchoom.buffer.codec.test.protocols.AnimChunkImageFrameCodec
import com.ditchoom.buffer.codec.test.protocols.AnimChunkMetadataCodec
import com.ditchoom.buffer.codec.test.protocols.ImageSize
import com.ditchoom.buffer.codec.testRoundTrip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for nested sealed interface @PacketType codecs, including:
 * - Sub-codec generation for nested classes
 * - Qualified names in dispatch
 * - Flattened codec names
 * - Mixed payload/non-payload variant dispatch
 */
class AnimChunkRoundTripTest {
    // ========== Individual variant codecs ==========

    @Test
    fun headerRoundTrip() {
        val original =
            AnimChunk.Header(
                magic = 0x414E494D, // "ANIM"
                version = 1u,
                frameCount = 120u,
            )
        val decoded = AnimChunkHeaderCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun headerExactBytes() {
        val original =
            AnimChunk.Header(
                magic = 0x414E494D,
                version = 2u,
                frameCount = 0x030201u,
            )
        val decoded =
            AnimChunkHeaderCodec.testRoundTrip(
                original,
                expectedBytes =
                    byteArrayOf(
                        0x41,
                        0x4E,
                        0x49,
                        0x4D, // magic
                        0x02, // version
                        0x03,
                        0x02,
                        0x01, // frameCount (3 bytes)
                    ),
            )
        assertEquals(original, decoded)
    }

    @Test
    fun imageFrameRoundTripWithPayload() {
        val original =
            AnimChunk.ImageFrame(
                index = 0u,
                size = ImageSize.of(1920u.toUShort(), 1080u.toUShort()),
                bitmapLength = 5,
                bitmap = "hello",
            )
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        AnimChunkImageFrameCodec.encode(
            buffer,
            original,
            encodeBitmap = { buf, s -> buf.writeString(s) },
        )
        buffer.resetForRead()
        val decoded =
            AnimChunkImageFrameCodec.decode<String>(
                buffer,
                decodeBitmap = { pr -> pr.readString(pr.remaining()) },
            )
        assertEquals(original.index, decoded.index)
        assertEquals(original.size, decoded.size)
        assertEquals(original.bitmap, decoded.bitmap)
    }

    @Test
    fun metadataRoundTrip() {
        val original =
            AnimChunk.Metadata(
                name = "walk_cycle",
                author = "Alice",
            )
        val decoded = AnimChunkMetadataCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun imageSizeValueClass() {
        val size = ImageSize.of(640u.toUShort(), 480u.toUShort())
        assertEquals(640u.toUShort(), size.width)
        assertEquals(480u.toUShort(), size.height)
    }

    // ========== Sealed dispatch codec (mixed payload/non-payload) ==========

    @Test
    fun sealedDispatchHeader() {
        val original: AnimChunk =
            AnimChunk.Header(
                magic = 0x414E494D,
                version = 1u,
                frameCount = 24u,
            )
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        AnimChunkCodec.encode<String>(
            buffer,
            original,
            encodeImageFrameBitmap = { buf, s -> buf.writeString(s) },
        )
        buffer.resetForRead()
        val decoded =
            AnimChunkCodec.decode<String>(
                buffer,
                decodeImageFrameBitmap = { pr -> pr.readString(pr.remaining()) },
            )
        assertTrue(decoded is AnimChunk.Header)
        assertEquals(original, decoded)
    }

    @Test
    fun sealedDispatchImageFrame() {
        val original: AnimChunk =
            AnimChunk.ImageFrame(
                index = 5u,
                size = ImageSize.of(320u.toUShort(), 240u.toUShort()),
                bitmapLength = 11,
                bitmap = "hello world",
            )
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        AnimChunkCodec.encode<String>(
            buffer,
            original,
            encodeImageFrameBitmap = { buf, s -> buf.writeString(s) },
        )
        buffer.resetForRead()
        val decoded =
            AnimChunkCodec.decode<String>(
                buffer,
                decodeImageFrameBitmap = { pr -> pr.readString(pr.remaining()) },
            )
        assertTrue(decoded is AnimChunk.ImageFrame<*>)
        assertEquals("hello world", (decoded as AnimChunk.ImageFrame<*>).bitmap)
    }

    @Test
    fun sealedDispatchMetadata() {
        val original: AnimChunk =
            AnimChunk.Metadata(
                name = "bounce",
                author = "Bob",
            )
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        AnimChunkCodec.encode<String>(
            buffer,
            original,
            encodeImageFrameBitmap = { buf, s -> buf.writeString(s) },
        )
        buffer.resetForRead()
        val decoded =
            AnimChunkCodec.decode<String>(
                buffer,
                decodeImageFrameBitmap = { pr -> pr.readString(pr.remaining()) },
            )
        assertTrue(decoded is AnimChunk.Metadata)
        assertEquals(original, decoded)
    }

    @Test
    fun sealedDispatchUnknownTypeThrows() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeByte(0xFF.toByte())
        buffer.resetForRead()
        assertFailsWith<IllegalArgumentException> {
            AnimChunkCodec.decode<String>(
                buffer,
                decodeImageFrameBitmap = { pr -> pr.readString(pr.remaining()) },
            )
        }
    }

    // ========== Sequence of mixed variants through dispatch ==========

    @Test
    fun sealedDispatchMultipleVariantsInSequence() {
        val chunks: List<AnimChunk> =
            listOf(
                AnimChunk.Header(magic = 0x414E494D, version = 1u, frameCount = 3u),
                AnimChunk.ImageFrame(index = 0u, size = ImageSize.of(10u.toUShort(), 10u.toUShort()), bitmapLength = 3, bitmap = "abc"),
                AnimChunk.Metadata(name = "test", author = "dev"),
                AnimChunk.ImageFrame(index = 1u, size = ImageSize.of(20u.toUShort(), 20u.toUShort()), bitmapLength = 5, bitmap = "hello"),
                AnimChunk.ImageFrame(index = 2u, size = ImageSize.of(30u.toUShort(), 30u.toUShort()), bitmapLength = 5, bitmap = "world"),
            )

        val buffer = BufferFactory.Default.allocate(4096, ByteOrder.BIG_ENDIAN)

        // Encode all chunks sequentially
        for (chunk in chunks) {
            AnimChunkCodec.encode<String>(buffer, chunk, encodeImageFrameBitmap = { buf, s -> buf.writeString(s) })
        }
        buffer.resetForRead()

        // Decode all chunks and verify types and values
        val decoded = mutableListOf<AnimChunk>()
        repeat(chunks.size) {
            decoded.add(
                AnimChunkCodec.decode<String>(buffer, decodeImageFrameBitmap = { pr -> pr.readString(pr.remaining()) }),
            )
        }

        assertTrue(decoded[0] is AnimChunk.Header)
        assertEquals(chunks[0], decoded[0])

        assertTrue(decoded[1] is AnimChunk.ImageFrame<*>)
        assertEquals("abc", (decoded[1] as AnimChunk.ImageFrame<*>).bitmap)

        assertTrue(decoded[2] is AnimChunk.Metadata)
        assertEquals(chunks[2], decoded[2])

        assertTrue(decoded[3] is AnimChunk.ImageFrame<*>)
        assertEquals("hello", (decoded[3] as AnimChunk.ImageFrame<*>).bitmap)

        assertTrue(decoded[4] is AnimChunk.ImageFrame<*>)
        assertEquals("world", (decoded[4] as AnimChunk.ImageFrame<*>).bitmap)
    }

    // ========== Boundary values ==========

    @Test
    fun headerWithMaxFrameCount() {
        // @WireBytes(3) means max is 0xFFFFFF (16,777,215)
        val original =
            AnimChunk.Header(
                magic = 0x414E494D,
                version = UByte.MAX_VALUE,
                frameCount = 0xFFFFFFu,
            )
        val decoded = AnimChunkHeaderCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun headerWithZeroValues() {
        val original = AnimChunk.Header(magic = 0, version = 0u, frameCount = 0u)
        val decoded = AnimChunkHeaderCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun metadataWithEmptyStrings() {
        val original = AnimChunk.Metadata(name = "", author = "")
        val decoded = AnimChunkMetadataCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun metadataWithUnicodeStrings() {
        val original = AnimChunk.Metadata(name = "走路循环", author = "café")
        val decoded = AnimChunkMetadataCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun imageFrameWithEmptyPayload() {
        val original =
            AnimChunk.ImageFrame(
                index = 0u,
                size = ImageSize.of(0u.toUShort(), 0u.toUShort()),
                bitmapLength = 0,
                bitmap = "",
            )
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        AnimChunkImageFrameCodec.encode(buffer, original, encodeBitmap = { buf, s -> buf.writeString(s) })
        buffer.resetForRead()
        val decoded = AnimChunkImageFrameCodec.decode<String>(buffer, decodeBitmap = { pr -> pr.readString(pr.remaining()) })
        assertEquals(original.index, decoded.index)
        assertEquals("", decoded.bitmap)
    }
}
