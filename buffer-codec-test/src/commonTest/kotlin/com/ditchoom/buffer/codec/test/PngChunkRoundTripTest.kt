package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.PngChunk
import com.ditchoom.buffer.codec.test.protocols.PngChunkCodec
import com.ditchoom.buffer.codec.test.protocols.PngChunkHeader
import com.ditchoom.buffer.codec.test.protocols.PngChunkIhdrCodec
import com.ditchoom.buffer.codec.test.protocols.PngChunkType
import com.ditchoom.buffer.codec.test.protocols.PngDataChunk
import com.ditchoom.buffer.codec.test.protocols.PngDataChunkCodec
import com.ditchoom.buffer.codec.testRoundTrip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * PNG chunk format tests (PNG Specification §5.3).
 *
 * Validates:
 * - Data class @DispatchOn with length-before-type wire order
 * - Discriminator field populated from context (not re-read from buffer)
 * - Exact wire byte positions matching the spec
 * - Full round-trip through dispatch codec
 */
class PngChunkRoundTripTest {
    // ========== Chunk type constants ==========

    @Test
    fun chunkTypeIhdrValue() {
        assertEquals(0x49484452u, PngChunkType.IHDR.raw) // "IHDR"
    }

    @Test
    fun chunkTypeIendValue() {
        assertEquals(0x49454E44u, PngChunkType.IEND.raw) // "IEND"
    }

    // ========== Exact wire byte verification ==========

    @Test
    fun ihdrExactWireBytes() {
        // PNG spec: 00 00 00 0D 49 48 44 52 [13 bytes data] [4 bytes CRC]
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        val ihdr = PngChunk.Ihdr(
            header = PngChunkHeader(13u, PngChunkType.IHDR.raw),
            width = 640u, height = 480u,
            bitDepth = 8u, colorType = 6u, // RGBA
            compressionMethod = 0u, filterMethod = 0u, interlaceMethod = 0u,
            crc = 0u,
        )
        PngChunkCodec.encode(buffer, ihdr)

        // Length = 13 (0x0000000D)
        assertEquals(0x00.toByte(), buffer[0])
        assertEquals(0x00.toByte(), buffer[1])
        assertEquals(0x00.toByte(), buffer[2])
        assertEquals(0x0D.toByte(), buffer[3])
        // Type = "IHDR" (0x49484452)
        assertEquals(0x49.toByte(), buffer[4]) // 'I'
        assertEquals(0x48.toByte(), buffer[5]) // 'H'
        assertEquals(0x44.toByte(), buffer[6]) // 'D'
        assertEquals(0x52.toByte(), buffer[7]) // 'R'
        // Width = 640 (0x00000280)
        assertEquals(0x00.toByte(), buffer[8])
        assertEquals(0x00.toByte(), buffer[9])
        assertEquals(0x02.toByte(), buffer[10])
        assertEquals(0x80.toByte(), buffer[11])
        // Total: 8 (header) + 13 (data) + 4 (CRC) = 25 bytes
        assertEquals(25, buffer.position())
    }

    @Test
    fun iendExactWireBytes() {
        // PNG spec: 00 00 00 00 49 45 4E 44 AE 42 60 82
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        val iend = PngChunk.Iend(
            header = PngChunkHeader(0u, PngChunkType.IEND.raw),
            crc = 0xAE426082u,
        )
        PngChunkCodec.encode(buffer, iend)

        // Length = 0
        assertEquals(0x00.toByte(), buffer[0])
        assertEquals(0x00.toByte(), buffer[1])
        assertEquals(0x00.toByte(), buffer[2])
        assertEquals(0x00.toByte(), buffer[3])
        // Type = "IEND"
        assertEquals(0x49.toByte(), buffer[4])
        assertEquals(0x45.toByte(), buffer[5])
        assertEquals(0x4E.toByte(), buffer[6])
        assertEquals(0x44.toByte(), buffer[7])
        // CRC
        assertEquals(0xAE.toByte(), buffer[8])
        assertEquals(0x42.toByte(), buffer[9])
        assertEquals(0x60.toByte(), buffer[10])
        assertEquals(0x82.toByte(), buffer[11])
        assertEquals(12, buffer.position())
    }

    // ========== Decode from spec-compliant bytes ==========

    @Test
    fun decodeIhdrFromSpecBytes() {
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        buffer.writeInt(13) // length
        buffer.writeInt(0x49484452) // "IHDR"
        buffer.writeInt(800) // width
        buffer.writeInt(600) // height
        buffer.writeByte(8) // bitDepth
        buffer.writeByte(2) // colorType (RGB)
        buffer.writeByte(0) // compressionMethod
        buffer.writeByte(0) // filterMethod
        buffer.writeByte(0) // interlaceMethod
        buffer.writeInt(0) // CRC
        buffer.resetForRead()

        val decoded = PngChunkCodec.decode(buffer)
        assertTrue(decoded is PngChunk.Ihdr)
        assertEquals(13u, decoded.header.length)
        assertEquals(PngChunkType.IHDR.raw, decoded.header.type)
        assertEquals(800u, decoded.width)
        assertEquals(600u, decoded.height)
        assertEquals(8u.toUByte(), decoded.bitDepth)
        assertEquals(2u.toUByte(), decoded.colorType)
    }

    @Test
    fun decodeIendFromSpecBytes() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeInt(0) // length = 0
        buffer.writeInt(0x49454E44) // "IEND"
        buffer.writeInt(0xAE426082.toInt()) // CRC
        buffer.resetForRead()

        val decoded = PngChunkCodec.decode(buffer)
        assertTrue(decoded is PngChunk.Iend)
        assertEquals(0u, decoded.header.length)
        assertEquals(PngChunkType.IEND.raw, decoded.header.type)
        assertEquals(0xAE426082u, decoded.crc)
    }

    @Test
    fun unknownChunkTypeThrows() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeInt(0) // length
        buffer.writeInt(0x58585858) // "XXXX"
        buffer.resetForRead()
        assertFailsWith<IllegalArgumentException> {
            PngChunkCodec.decode(buffer)
        }
    }

    // ========== Full dispatch round-trip ==========

    @Test
    fun ihdrDispatchRoundTrip() {
        val original: PngChunk = PngChunk.Ihdr(
            PngChunkHeader(13u, PngChunkType.IHDR.raw),
            1920u, 1080u, 8u, 2u, 0u, 0u, 0u, 0xDEADBEEFu,
        )
        val decoded = PngChunkCodec.testRoundTrip(original)
        assertTrue(decoded is PngChunk.Ihdr)
        assertEquals(original, decoded)
    }

    @Test
    fun iendDispatchRoundTrip() {
        val original: PngChunk = PngChunk.Iend(
            PngChunkHeader(0u, PngChunkType.IEND.raw),
            0xAE426082u,
        )
        val decoded = PngChunkCodec.testRoundTrip(original)
        assertTrue(decoded is PngChunk.Iend)
        assertEquals(original, decoded)
    }

    // ========== Standalone data chunk (variable-length) ==========

    @Test
    fun dataChunkRoundTrip() {
        val text = "Comment"
        val original = PngDataChunk(
            length = text.length.toUInt(),
            type = PngChunkType.tEXt.raw,
            data = text,
            crc = 0x12345678u,
        )
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        PngDataChunkCodec.encode(buffer, original) { buf, s -> buf.writeString(s) }
        buffer.resetForRead()
        val decoded = PngDataChunkCodec.decode<String>(buffer) { pr -> pr.readString(pr.remaining()) }
        assertEquals(text.length.toUInt(), decoded.length)
        assertEquals(PngChunkType.tEXt.raw, decoded.type)
        assertEquals(text, decoded.data)
        assertEquals(0x12345678u, decoded.crc)
    }
}
