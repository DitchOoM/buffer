package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.PngChunk
import com.ditchoom.buffer.codec.test.protocols.PngChunkCodec
import com.ditchoom.buffer.codec.test.protocols.PngChunkIendCodec
import com.ditchoom.buffer.codec.test.protocols.PngChunkIhdrCodec
import com.ditchoom.buffer.codec.test.protocols.PngChunkTextCodec
import com.ditchoom.buffer.codec.test.protocols.PngChunkType
import com.ditchoom.buffer.codec.testRoundTrip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * PNG chunk format tests (PNG Specification §5.3).
 * Validates 4-byte multi-byte @DispatchOn with spec-compliant chunk types.
 */
class PngChunkRoundTripTest {
    // ========== Chunk type constants ==========

    @Test
    fun chunkTypeIhdrValue() {
        assertEquals(0x49484452, PngChunkType.IHDR.type)
    }

    @Test
    fun chunkTypeIendValue() {
        assertEquals(0x49454E44, PngChunkType.IEND.type)
    }

    // ========== Sub-codec round-trips ==========

    @Test
    fun ihdrRoundTrip() {
        val original = PngChunk.Ihdr(
            width = 1920u,
            height = 1080u,
            bitDepth = 8u,
            colorType = 2u, // RGB
            compressionMethod = 0u,
            filterMethod = 0u,
            interlaceMethod = 0u,
            crc = 0xDEADBEEFu,
        )
        val decoded = PngChunkIhdrCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun iendRoundTrip() {
        val original = PngChunk.Iend(0xAE426082u) // standard IEND CRC
        val decoded = PngChunkIendCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun textChunkRoundTrip() {
        val keyword = "Comment"
        val original = PngChunk.Text(keyword.length.toUInt(), keyword, 0x12345678u)
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        PngChunkTextCodec.encode(buffer, original) { buf, s -> buf.writeString(s) }
        buffer.resetForRead()
        val decoded = PngChunkTextCodec.decode<String>(buffer) { pr -> pr.readString(pr.remaining()) }
        assertEquals(keyword.length.toUInt(), decoded.dataLength)
        assertEquals(keyword, decoded.textData)
        assertEquals(0x12345678u, decoded.crc)
    }

    // ========== Dispatch decode from spec bytes ==========

    @Test
    fun dispatchDecodesIhdrFromSpecBytes() {
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        // Write "IHDR" as 4-byte big-endian: 0x49484452
        buffer.writeInt(0x49484452)
        // IHDR data: width, height, bitDepth, colorType, compression, filter, interlace
        buffer.writeInt(640) // width
        buffer.writeInt(480) // height
        buffer.writeByte(8)  // bitDepth
        buffer.writeByte(6)  // colorType (RGBA)
        buffer.writeByte(0)  // compressionMethod
        buffer.writeByte(0)  // filterMethod
        buffer.writeByte(0)  // interlaceMethod
        buffer.writeInt(0)   // CRC placeholder
        buffer.resetForRead()

        val decoded = PngChunkCodec.decode(buffer)
        assertTrue(decoded is PngChunk.Ihdr)
        assertEquals(640u, decoded.width)
        assertEquals(480u, decoded.height)
        assertEquals(8u.toUByte(), decoded.bitDepth)
        assertEquals(6u.toUByte(), decoded.colorType)
    }

    @Test
    fun dispatchDecodesIendFromSpecBytes() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeInt(0x49454E44) // "IEND"
        buffer.writeInt(0xAE426082.toInt()) // CRC
        buffer.resetForRead()

        val decoded = PngChunkCodec.decode(buffer)
        assertTrue(decoded is PngChunk.Iend)
        assertEquals(0xAE426082u, decoded.crc)
    }

    @Test
    fun dispatchUnknownChunkTypeThrows() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeInt(0x58585858) // "XXXX" — not registered
        buffer.resetForRead()

        assertFailsWith<IllegalArgumentException> {
            PngChunkCodec.decode(buffer)
        }
    }

    // ========== Dispatch round-trip ==========

    @Test
    fun ihdrDispatchRoundTrip() {
        val original: PngChunk = PngChunk.Ihdr(800u, 600u, 8u, 2u, 0u, 0u, 0u, 0u)
        val decoded = PngChunkCodec.testRoundTrip(original)
        assertTrue(decoded is PngChunk.Ihdr)
        assertEquals(original, decoded)
    }

    @Test
    fun iendDispatchRoundTrip() {
        val original: PngChunk = PngChunk.Iend(0xAE426082u)
        val decoded = PngChunkCodec.testRoundTrip(original)
        assertTrue(decoded is PngChunk.Iend)
        assertEquals(original, decoded)
    }

    @Test
    fun encodeWritesCorrectChunkTypeBytes() {
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        PngChunkCodec.encode(buffer, PngChunk.Iend(0u))
        // First 4 bytes should be "IEND" = 0x49454E44
        assertEquals(0x49.toByte(), buffer[0])
        assertEquals(0x45.toByte(), buffer[1])
        assertEquals(0x4E.toByte(), buffer[2])
        assertEquals(0x44.toByte(), buffer[3])
    }
}
