package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.test.protocols.DecodeOnlyColoredPoint
import com.ditchoom.buffer.codec.test.protocols.DecodeOnlyColoredPointCodec
import com.ditchoom.buffer.codec.test.protocols.DecodeOnlyPrefixedColor
import com.ditchoom.buffer.codec.test.protocols.DecodeOnlyPrefixedColorCodec
import com.ditchoom.buffer.codec.test.protocols.EncodeOnlyColoredPoint
import com.ditchoom.buffer.codec.test.protocols.EncodeOnlyColoredPointCodec
import com.ditchoom.buffer.codec.test.protocols.ForcedDecodeOnlyPoint
import com.ditchoom.buffer.codec.test.protocols.ForcedDecodeOnlyPointCodec
import com.ditchoom.buffer.codec.test.protocols.Rgb
import kotlin.test.Test
import kotlin.test.assertEquals

class DirectionalCodecTest {
    private val factory = BufferFactory.Default

    // ──────────────────── Decode-only basic ────────────────────

    @Test
    fun decodeOnlyPointDecodesCorrectly() {
        val buffer = factory.allocate(11, ByteOrder.BIG_ENDIAN)
        buffer.writeInt(42) // x
        buffer.writeInt(99) // y
        buffer.writeUByte(0xAAu) // r
        buffer.writeUByte(0xBBu) // g
        buffer.writeUByte(0xCCu) // b
        buffer.resetForRead()

        val decoded = DecodeOnlyColoredPointCodec.decode(buffer)
        assertEquals(DecodeOnlyColoredPoint(42, 99, Rgb(0xAAu, 0xBBu, 0xCCu)), decoded)
    }

    @Test
    fun decodeOnlyPointWithContextDecodesCorrectly() {
        val buffer = factory.allocate(11, ByteOrder.BIG_ENDIAN)
        buffer.writeInt(1) // x
        buffer.writeInt(2) // y
        buffer.writeUByte(10u) // r
        buffer.writeUByte(20u) // g
        buffer.writeUByte(30u) // b
        buffer.resetForRead()

        val decoded = DecodeOnlyColoredPointCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(DecodeOnlyColoredPoint(1, 2, Rgb(10u, 20u, 30u)), decoded)
    }

    @Test
    fun forcedDecodeOnlyPointDecodesCorrectly() {
        val buffer = factory.allocate(11, ByteOrder.BIG_ENDIAN)
        buffer.writeInt(7)
        buffer.writeInt(8)
        buffer.writeUByte(1u)
        buffer.writeUByte(2u)
        buffer.writeUByte(3u)
        buffer.resetForRead()

        val decoded = ForcedDecodeOnlyPointCodec.decode(buffer)
        assertEquals(ForcedDecodeOnlyPoint(7, 8, Rgb(1u, 2u, 3u)), decoded)
    }

    // ──────────────────── Encode-only basic ────────────────────

    @Test
    fun encodeOnlyPointEncodesCorrectly() {
        val value = EncodeOnlyColoredPoint(42, 99, Rgb(0xAAu, 0xBBu, 0xCCu))
        val buffer = factory.allocate(11, ByteOrder.BIG_ENDIAN)
        EncodeOnlyColoredPointCodec.encode(buffer, value)
        buffer.resetForRead()

        assertEquals(42, buffer.readInt())
        assertEquals(99, buffer.readInt())
        assertEquals(0xAAu, buffer.readUnsignedByte())
        assertEquals(0xBBu, buffer.readUnsignedByte())
        assertEquals(0xCCu, buffer.readUnsignedByte())
    }

    @Test
    fun encodeOnlyPointWithContextEncodesCorrectly() {
        val value = EncodeOnlyColoredPoint(1, 2, Rgb(10u, 20u, 30u))
        val buffer = factory.allocate(11, ByteOrder.BIG_ENDIAN)
        EncodeOnlyColoredPointCodec.encode(buffer, value, EncodeContext.Empty)
        buffer.resetForRead()

        assertEquals(1, buffer.readInt())
        assertEquals(2, buffer.readInt())
        assertEquals(10u, buffer.readUnsignedByte())
        assertEquals(20u, buffer.readUnsignedByte())
        assertEquals(30u, buffer.readUnsignedByte())
    }

    // ──────────────────── Decode-only with @LengthPrefixed ────────────────────

    @Test
    fun decodeOnlyPrefixedColorDecodesCorrectly() {
        val buffer = factory.allocate(6, ByteOrder.BIG_ENDIAN)
        buffer.writeUByte(0x42u) // id
        buffer.writeShort(3) // length prefix (3 bytes for RGB)
        buffer.writeUByte(0xFFu) // r
        buffer.writeUByte(0x80u) // g
        buffer.writeUByte(0x00u) // b
        buffer.resetForRead()

        val decoded = DecodeOnlyPrefixedColorCodec.decode(buffer)
        assertEquals(DecodeOnlyPrefixedColor(0x42u, Rgb(0xFFu, 0x80u, 0x00u)), decoded)
    }

    // ──────────────────── Byte layout verification ────────────────────

    @Test
    fun decodeOnlyExactByteLayout() {
        // Build buffer manually with known byte pattern
        val buffer = factory.allocate(11, ByteOrder.BIG_ENDIAN)
        // x = 0x00000001 (4 bytes)
        buffer.writeByte(0)
        buffer.writeByte(0)
        buffer.writeByte(0)
        buffer.writeByte(1)
        // y = 0x00000002 (4 bytes)
        buffer.writeByte(0)
        buffer.writeByte(0)
        buffer.writeByte(0)
        buffer.writeByte(2)
        // RGB = (10, 20, 30)
        buffer.writeByte(10)
        buffer.writeByte(20)
        buffer.writeByte(30)
        buffer.resetForRead()

        val decoded = DecodeOnlyColoredPointCodec.decode(buffer)
        assertEquals(1, decoded.x)
        assertEquals(2, decoded.y)
        assertEquals(10u, decoded.color.r)
        assertEquals(20u, decoded.color.g)
        assertEquals(30u, decoded.color.b)
    }

    @Test
    fun encodeOnlyExactByteLayout() {
        val value = EncodeOnlyColoredPoint(1, 2, Rgb(10u, 20u, 30u))
        val buffer = factory.allocate(11, ByteOrder.BIG_ENDIAN)
        EncodeOnlyColoredPointCodec.encode(buffer, value)
        buffer.resetForRead()

        // x = 0x00000001
        assertEquals(0.toByte(), buffer.readByte())
        assertEquals(0.toByte(), buffer.readByte())
        assertEquals(0.toByte(), buffer.readByte())
        assertEquals(1.toByte(), buffer.readByte())
        // y = 0x00000002
        assertEquals(0.toByte(), buffer.readByte())
        assertEquals(0.toByte(), buffer.readByte())
        assertEquals(0.toByte(), buffer.readByte())
        assertEquals(2.toByte(), buffer.readByte())
        // RGB
        assertEquals(10.toByte(), buffer.readByte())
        assertEquals(20.toByte(), buffer.readByte())
        assertEquals(30.toByte(), buffer.readByte())
    }
}
