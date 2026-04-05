package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.readLengthPrefixedUtf8String
import com.ditchoom.buffer.readVariableByteInteger
import com.ditchoom.buffer.writeLengthPrefixedUtf8String
import com.ditchoom.buffer.writeVariableByteInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// Simple two-field struct: UShort id + Int value = 6 bytes
data class SimpleStruct(
    val id: UShort,
    val value: Int,
)

object SimpleStructCodec : Codec<SimpleStruct> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): SimpleStruct = SimpleStruct(buffer.readUnsignedShort(), buffer.readInt())

    override fun encode(
        buffer: WriteBuffer,
        value: SimpleStruct,
        context: EncodeContext,
    ) {
        buffer.writeUShort(value.id)
        buffer.writeInt(value.value)
    }

    override fun sizeOf(value: SimpleStruct): SizeEstimate = SizeEstimate.Exact(6)
}

// Struct with a variable-length integer field (hand-written, simulating @VariableLength)
data class VariableLengthStruct(
    val tag: Byte,
    val length: Int,
)

object VariableLengthStructCodec : Codec<VariableLengthStruct> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): VariableLengthStruct = VariableLengthStruct(buffer.readByte(), buffer.readVariableByteInteger())

    override fun encode(
        buffer: WriteBuffer,
        value: VariableLengthStruct,
        context: EncodeContext,
    ) {
        buffer.writeByte(value.tag)
        buffer.writeVariableByteInteger(value.length)
    }

    // sizeOf defaults to UnableToPrecalculate since variable length depends on value
}

// Struct with a length-prefixed string (hand-written, simulating @LengthPrefixed)
data class LengthPrefixedStruct(
    val type: UByte,
    val name: String,
)

object LengthPrefixedStructCodec : Codec<LengthPrefixedStruct> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): LengthPrefixedStruct = LengthPrefixedStruct(buffer.readUnsignedByte(), buffer.readLengthPrefixedUtf8String().second)

    override fun encode(
        buffer: WriteBuffer,
        value: LengthPrefixedStruct,
        context: EncodeContext,
    ) {
        buffer.writeUByte(value.type)
        buffer.writeLengthPrefixedUtf8String(value.name)
    }

    // 1 byte for type + 2 bytes for length prefix + string byte length
    override fun sizeOf(value: LengthPrefixedStruct): SizeEstimate = SizeEstimate.Exact(1 + 2 + value.name.encodeToByteArray().size)
}

class CodecTest {
    // ========== SimpleStruct round-trip ==========

    @Test
    fun simpleStructRoundTrip() {
        val original = SimpleStruct(42u.toUShort(), 123456)
        val buf = BufferFactory.Default.allocate(6)
        SimpleStructCodec.encode(buf, original)
        buf.resetForRead()
        val decoded = SimpleStructCodec.decode(buf)
        assertEquals(original, decoded)
    }

    @Test
    fun simpleStructZeroValues() {
        val original = SimpleStruct(0u.toUShort(), 0)
        val buf = BufferFactory.Default.allocate(6)
        SimpleStructCodec.encode(buf, original)
        buf.resetForRead()
        val decoded = SimpleStructCodec.decode(buf)
        assertEquals(original, decoded)
    }

    @Test
    fun simpleStructMaxValues() {
        val original = SimpleStruct(UShort.MAX_VALUE, Int.MAX_VALUE)
        val buf = BufferFactory.Default.allocate(6)
        SimpleStructCodec.encode(buf, original)
        buf.resetForRead()
        val decoded = SimpleStructCodec.decode(buf)
        assertEquals(original, decoded)
    }

    @Test
    fun simpleStructNegativeIntValue() {
        val original = SimpleStruct(1u.toUShort(), -1)
        val buf = BufferFactory.Default.allocate(6)
        SimpleStructCodec.encode(buf, original)
        buf.resetForRead()
        val decoded = SimpleStructCodec.decode(buf)
        assertEquals(original, decoded)
    }

    @Test
    fun simpleStructSizeOf() {
        val value = SimpleStruct(42u.toUShort(), 123)
        val estimate = SimpleStructCodec.sizeOf(value)
        assertIs<SizeEstimate.Exact>(estimate)
        assertEquals(6, estimate.bytes)
    }

    // ========== VariableLengthStruct round-trip ==========

    @Test
    fun variableLengthStructRoundTripOneByte() {
        val original = VariableLengthStruct(0x01, 0)
        val buf = BufferFactory.Default.allocate(8)
        VariableLengthStructCodec.encode(buf, original)
        buf.resetForRead()
        val decoded = VariableLengthStructCodec.decode(buf)
        assertEquals(original, decoded)
    }

    @Test
    fun variableLengthStructRoundTripTwoBytes() {
        val original = VariableLengthStruct(0x02, 128)
        val buf = BufferFactory.Default.allocate(8)
        VariableLengthStructCodec.encode(buf, original)
        buf.resetForRead()
        val decoded = VariableLengthStructCodec.decode(buf)
        assertEquals(original, decoded)
    }

    @Test
    fun variableLengthStructRoundTripMaxValue() {
        val original = VariableLengthStruct(0x03, 268435455)
        val buf = BufferFactory.Default.allocate(8)
        VariableLengthStructCodec.encode(buf, original)
        buf.resetForRead()
        val decoded = VariableLengthStructCodec.decode(buf)
        assertEquals(original, decoded)
    }

    @Test
    fun variableLengthStructRoundTrip127() {
        val original = VariableLengthStruct(0x04, 127)
        val buf = BufferFactory.Default.allocate(8)
        VariableLengthStructCodec.encode(buf, original)
        buf.resetForRead()
        val decoded = VariableLengthStructCodec.decode(buf)
        assertEquals(original, decoded)
    }

    @Test
    fun variableLengthStructSizeOfReturnsNull() {
        val value = VariableLengthStruct(0x01, 128)
        assertIs<SizeEstimate.UnableToPrecalculate>(VariableLengthStructCodec.sizeOf(value))
    }

    // ========== LengthPrefixedStruct round-trip ==========

    @Test
    fun lengthPrefixedStructRoundTrip() {
        val original = LengthPrefixedStruct(0x01u.toUByte(), "Hello")
        val size = (LengthPrefixedStructCodec.sizeOf(original) as SizeEstimate.Exact).bytes
        val buf = BufferFactory.Default.allocate(size)
        LengthPrefixedStructCodec.encode(buf, original)
        buf.resetForRead()
        val decoded = LengthPrefixedStructCodec.decode(buf)
        assertEquals(original, decoded)
    }

    @Test
    fun lengthPrefixedStructEmptyString() {
        val original = LengthPrefixedStruct(0x02u.toUByte(), "")
        val size = (LengthPrefixedStructCodec.sizeOf(original) as SizeEstimate.Exact).bytes
        val buf = BufferFactory.Default.allocate(size)
        LengthPrefixedStructCodec.encode(buf, original)
        buf.resetForRead()
        val decoded = LengthPrefixedStructCodec.decode(buf)
        assertEquals(original, decoded)
    }

    @Test
    fun lengthPrefixedStructUtf8Multibyte() {
        val original = LengthPrefixedStruct(0x03u.toUByte(), "cafe\u0301")
        val size = (LengthPrefixedStructCodec.sizeOf(original) as SizeEstimate.Exact).bytes
        val buf = BufferFactory.Default.allocate(size)
        LengthPrefixedStructCodec.encode(buf, original)
        buf.resetForRead()
        val decoded = LengthPrefixedStructCodec.decode(buf)
        assertEquals(original, decoded)
    }

    @Test
    fun lengthPrefixedStructSizeOf() {
        val value = LengthPrefixedStruct(0x01u.toUByte(), "Hello")
        // 1 (UByte) + 2 (length prefix) + 5 (Hello in UTF-8) = 8
        val estimate = LengthPrefixedStructCodec.sizeOf(value)
        assertIs<SizeEstimate.Exact>(estimate)
        assertEquals(8, estimate.bytes)
    }

    @Test
    fun lengthPrefixedStructMaxUByte() {
        val original = LengthPrefixedStruct(UByte.MAX_VALUE, "test")
        val size = (LengthPrefixedStructCodec.sizeOf(original) as SizeEstimate.Exact).bytes
        val buf = BufferFactory.Default.allocate(size)
        LengthPrefixedStructCodec.encode(buf, original)
        buf.resetForRead()
        val decoded = LengthPrefixedStructCodec.decode(buf)
        assertEquals(original, decoded)
    }
}
