package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.PngChunkType
import com.ditchoom.buffer.codec.test.protocols.PngDataChunk
import com.ditchoom.buffer.codec.test.protocols.PngDataChunkCodec
import com.ditchoom.buffer.codec.test.protocols.PngIendChunk
import com.ditchoom.buffer.codec.test.protocols.PngIendChunkCodec
import com.ditchoom.buffer.codec.test.protocols.PngIhdrChunk
import com.ditchoom.buffer.codec.test.protocols.PngIhdrChunkCodec
import com.ditchoom.buffer.codec.testRoundTrip
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PNG chunk format tests (PNG Specification §5.3).
 *
 * Verifies exact wire byte layout: length(4) + type(4) + data(length) + crc(4).
 * Each test validates both round-trip correctness and spec-compliant byte positions.
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

    // ========== IHDR round-trip ==========

    @Test
    fun ihdrRoundTrip() {
        val original = PngIhdrChunk(
            length = 13u,
            type = PngChunkType.IHDR.raw,
            width = 1920u,
            height = 1080u,
            bitDepth = 8u,
            colorType = 2u, // RGB
            compressionMethod = 0u,
            filterMethod = 0u,
            interlaceMethod = 0u,
            crc = 0xDEADBEEFu,
        )
        val decoded = PngIhdrChunkCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun ihdrExactWireBytes() {
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        PngIhdrChunkCodec.encode(
            buffer,
            PngIhdrChunk(13u, PngChunkType.IHDR.raw, 640u, 480u, 8u, 6u, 0u, 0u, 0u, 0u),
        )
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
    }

    // ========== IEND round-trip ==========

    @Test
    fun iendRoundTrip() {
        val original = PngIendChunk(0u, PngChunkType.IEND.raw, 0xAE426082u)
        val decoded = PngIendChunkCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun iendExactWireBytes() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        PngIendChunkCodec.encode(buffer, PngIendChunk(0u, PngChunkType.IEND.raw, 0xAE426082u))
        // Length = 0
        assertEquals(0x00.toByte(), buffer[0])
        assertEquals(0x00.toByte(), buffer[1])
        assertEquals(0x00.toByte(), buffer[2])
        assertEquals(0x00.toByte(), buffer[3])
        // Type = "IEND"
        assertEquals(0x49.toByte(), buffer[4]) // 'I'
        assertEquals(0x45.toByte(), buffer[5]) // 'E'
        assertEquals(0x4E.toByte(), buffer[6]) // 'N'
        assertEquals(0x44.toByte(), buffer[7]) // 'D'
        // CRC = 0xAE426082
        assertEquals(0xAE.toByte(), buffer[8])
        assertEquals(0x42.toByte(), buffer[9])
        assertEquals(0x60.toByte(), buffer[10])
        assertEquals(0x82.toByte(), buffer[11])
        assertEquals(12, buffer.position()) // exactly 12 bytes
    }

    // ========== Data chunk with payload ==========

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
        buffer.writeInt(0) // CRC placeholder
        buffer.resetForRead()

        val decoded = PngIhdrChunkCodec.decode(buffer)
        assertEquals(13u, decoded.length)
        assertEquals(PngChunkType.IHDR.raw, decoded.type)
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

        val decoded = PngIendChunkCodec.decode(buffer)
        assertEquals(0u, decoded.length)
        assertEquals(PngChunkType.IEND.raw, decoded.type)
        assertEquals(0xAE426082u, decoded.crc)
    }
}
