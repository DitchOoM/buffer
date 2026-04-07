package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlin.test.Test
import kotlin.test.assertEquals

class CodecExtensionsTest {
    @Test
    fun decodeFromReadBuffer() {
        val buf = BufferFactory.Default.allocate(6)
        buf.writeShort(42.toShort()) // id as UShort
        buf.writeInt(123456) // value
        buf.resetForRead()

        val decoded = SimpleStructCodec.decode(buf)
        assertEquals(SimpleStruct(42u.toUShort(), 123456), decoded)
    }

    @Test
    fun encodeToWriteBuffer() {
        val original = SimpleStruct(42u.toUShort(), 123456)
        val buf = BufferFactory.Default.allocate(6)
        SimpleStructCodec.encode(buf, original)
        buf.resetForRead()

        assertEquals(42.toShort(), buf.readShort())
        assertEquals(123456, buf.readInt())
    }

    @Test
    fun encodeToBuffer() {
        val original = SimpleStruct(42u.toUShort(), 123456)
        val encoded = SimpleStructCodec.encodeToBuffer(original)

        assertEquals(6, encoded.remaining())
        assertEquals(42u.toUShort(), encoded.readUnsignedShort())
        assertEquals(123456, encoded.readInt())
    }

    @Test
    fun encodeToBufferAndDecode() {
        val original = SimpleStruct(42u.toUShort(), 123456)
        val encoded = SimpleStructCodec.encodeToBuffer(original)
        val decoded = SimpleStructCodec.decode(encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun testRoundTripSimple() {
        val original = SimpleStruct(42u.toUShort(), 123456)
        val decoded = SimpleStructCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun testRoundTripZeroValues() {
        val original = SimpleStruct(0u.toUShort(), 0)
        val decoded = SimpleStructCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun testRoundTripMaxValues() {
        val original = SimpleStruct(UShort.MAX_VALUE, Int.MAX_VALUE)
        val decoded = SimpleStructCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun testRoundTripNegativeValue() {
        val original = SimpleStruct(1u.toUShort(), Int.MIN_VALUE)
        val decoded = SimpleStructCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun testRoundTripWithExpectedBytes() {
        val original = SimpleStruct(0x0001u.toUShort(), 0x00000002)
        // UShort 1 = 0x00 0x01, Int 2 = 0x00 0x00 0x00 0x02
        val expectedBytes = byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x02)
        val decoded = SimpleStructCodec.testRoundTrip(original, expectedBytes)
        assertEquals(original, decoded)
    }

    @Test
    fun testRoundTripVariableLengthStruct() {
        val original = VariableLengthStruct(0x01, 128)
        val buf = BufferFactory.Default.allocate(16)
        VariableLengthStructCodec.encode(buf, original)
        buf.resetForRead()
        val decoded = VariableLengthStructCodec.decode(buf)
        assertEquals(original, decoded)
    }

    @Test
    fun testRoundTripLengthPrefixedStruct() {
        val original = LengthPrefixedStruct(0x01u.toUByte(), "Hello")
        val encoded = LengthPrefixedStructCodec.encodeToBuffer(original)
        val decoded = LengthPrefixedStructCodec.decode(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun encodeToBufferGrowsAutomatically() {
        val original = VariableLengthStruct(0x01, 0)
        val encoded = VariableLengthStructCodec.encodeToBuffer(original)
        val decoded = VariableLengthStructCodec.decode(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun encodeToBufferRemainingMatchesBytesWritten() {
        // 1 byte for tag + 1 byte for VBI(0) = 2 bytes
        val original = VariableLengthStruct(0x01, 0)
        val encoded = VariableLengthStructCodec.encodeToBuffer(original)
        assertEquals(2, encoded.remaining())
    }

    @Test
    fun encodeToBufferRemainingMatchesBytesWrittenForMultiByteVBI() {
        // VBI(128) encodes to 2 bytes → total = 1 (tag) + 2 (VBI) = 3
        val original = VariableLengthStruct(0x01, 128)
        val encoded = VariableLengthStructCodec.encodeToBuffer(original)
        assertEquals(3, encoded.remaining())
    }

    @Test
    fun decodeFromReadBufferMultipleStructs() {
        val buf = BufferFactory.Default.allocate(12)
        // Write two structs back-to-back
        buf.writeShort(1.toShort())
        buf.writeInt(100)
        buf.writeShort(2.toShort())
        buf.writeInt(200)
        buf.resetForRead()

        val first = SimpleStructCodec.decode(buf)
        val second = SimpleStructCodec.decode(buf)

        assertEquals(SimpleStruct(1u.toUShort(), 100), first)
        assertEquals(SimpleStruct(2u.toUShort(), 200), second)
    }
}
