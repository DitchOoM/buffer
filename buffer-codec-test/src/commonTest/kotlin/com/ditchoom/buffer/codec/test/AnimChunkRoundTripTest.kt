package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.AnimChunk
import com.ditchoom.buffer.codec.test.protocols.AnimChunkCodec
import com.ditchoom.buffer.codec.test.protocols.AnimChunkFrameInfoCodec
import com.ditchoom.buffer.codec.test.protocols.AnimChunkHeaderCodec
import com.ditchoom.buffer.codec.test.protocols.AnimChunkMetadataCodec
import com.ditchoom.buffer.codec.test.protocols.ImageSize
import com.ditchoom.buffer.codec.testRoundTrip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for nested sealed interface @PacketType codecs.
 * Validates that the KSP processor:
 * 1. Generates sub-codecs for nested variants (AnimChunkHeaderCodec, etc.)
 * 2. Uses qualified names in the dispatch (is AnimChunk.Header, not is Header)
 * 3. Flattens codec names (AnimChunkHeaderCodec, not HeaderCodec)
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
    fun frameInfoRoundTrip() {
        val original =
            AnimChunk.FrameInfo(
                index = 0u,
                size = ImageSize.of(1920u.toUShort(), 1080u.toUShort()),
                bitmapOffset = 1024,
                bitmapLength = 8294400,
            )
        val decoded = AnimChunkFrameInfoCodec.testRoundTrip(original)
        assertEquals(original, decoded)
        assertEquals(1920u.toUShort(), decoded.size.width)
        assertEquals(1080u.toUShort(), decoded.size.height)
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

    // ========== Sealed dispatch codec ==========

    @Test
    fun sealedDispatchHeader() {
        val original: AnimChunk =
            AnimChunk.Header(
                magic = 0x414E494D,
                version = 1u,
                frameCount = 24u,
            )
        val decoded = AnimChunkCodec.testRoundTrip(original)
        assertTrue(decoded is AnimChunk.Header)
        assertEquals(original, decoded)
    }

    @Test
    fun sealedDispatchFrameInfo() {
        val original: AnimChunk =
            AnimChunk.FrameInfo(
                index = 5u,
                size = ImageSize.of(320u.toUShort(), 240u.toUShort()),
                bitmapOffset = 0,
                bitmapLength = 307200,
            )
        val decoded = AnimChunkCodec.testRoundTrip(original)
        assertTrue(decoded is AnimChunk.FrameInfo)
        assertEquals(original, decoded)
    }

    @Test
    fun sealedDispatchMetadata() {
        val original: AnimChunk =
            AnimChunk.Metadata(
                name = "bounce",
                author = "Bob",
            )
        val decoded = AnimChunkCodec.testRoundTrip(original)
        assertTrue(decoded is AnimChunk.Metadata)
        assertEquals(original, decoded)
    }

    @Test
    fun sealedDispatchUnknownTypeThrows() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeByte(0xFF.toByte())
        buffer.resetForRead()
        assertFailsWith<IllegalArgumentException> {
            AnimChunkCodec.decode(buffer)
        }
    }
}
