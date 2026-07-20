package com.ditchoom.buffer.codec.test.protocols.boundary

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.encodeToPlatformBuffer
import com.ditchoom.buffer.codec.test.protocols.count.CountNamed
import com.ditchoom.buffer.codec.test.protocols.count.CountVariableList
import com.ditchoom.buffer.codec.test.protocols.count.CountVariableListCodec
import com.ditchoom.buffer.codec.test.protocols.simple.TwoStrings
import com.ditchoom.buffer.codec.test.protocols.simple.TwoStringsCodec
import com.ditchoom.buffer.codec.test.protocols.valueclassstring.LpIntValueClassIdCodec
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

/**
 * Malformed / truncated wire input must make `decode` throw on every
 * platform — never silently return a short or garbage value. This suite
 * runs on JVM, JS, and native via commonTest; the platform spread is the
 * point (buffer bounds signals differ per backing store).
 */
class MalformedInputRejectionTest {
    // (a) truncated buffer — one byte short of a @LengthPrefixed body ------

    @Test
    fun truncatedLengthPrefixedBodyThrows() {
        val whole = TwoStringsCodec.encodeToPlatformBuffer(TwoStrings("hello", "world"))
        // Chop the final body byte: the second field's 2-byte prefix still
        // claims 5 bytes, but only 4 remain.
        whole.setLimit(whole.limit() - 1)
        assertFails { TwoStringsCodec.decode(whole, DecodeContext.Empty) }
    }

    @Test
    fun truncatedInsidePrefixThrows() {
        val whole = TwoStringsCodec.encodeToPlatformBuffer(TwoStrings("hello", "world"))
        // Cut mid-way through the second field's prefix itself.
        whole.setLimit(2 + 5 + 1)
        assertFails { TwoStringsCodec.decode(whole, DecodeContext.Empty) }
    }

    // (b) valid-but-oversized length prefix ---------------------------------

    @Test
    fun oversizedIntPrefixThrows() {
        // 4-byte prefix claiming ~2^31 bytes with only 2 bytes present.
        val buf = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        buf.writeInt(0x7FFFFFF0)
        buf.writeByte('h'.code.toByte())
        buf.writeByte('i'.code.toByte())
        buf.resetForRead()
        assertFails { LpIntValueClassIdCodec.decode(buf, DecodeContext.Empty) }
    }

    @Test
    fun oversizedShortPrefixThrows() {
        // 2-byte prefix claiming 65535 bytes with 3 present.
        val buf = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        buf.writeShort(0xFFFF.toShort())
        buf.writeByte('a'.code.toByte())
        buf.writeByte('b'.code.toByte())
        buf.writeByte('c'.code.toByte())
        buf.resetForRead()
        assertFails { TwoStringsCodec.decode(buf, DecodeContext.Empty) }
    }

    // (c) count prefix claiming more elements than the buffer holds ---------

    @Test
    fun countBeyondBufferThrows() {
        // varint count = 5, but only one valid element follows.
        val buf = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0x05.toByte()) // count = 5
        buf.writeShort(0x0002.toShort()) // element 0: LP len 2
        buf.writeByte('h'.code.toByte())
        buf.writeByte('i'.code.toByte())
        buf.resetForRead()
        assertFails { CountVariableListCodec.decode(buf, DecodeContext.Empty) }
    }

    @Test
    fun hugeCountWithEmptyBodyThrows() {
        // varint count = 127 with zero element bytes.
        val buf = BufferFactory.Default.allocate(4, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0x7F.toByte())
        buf.resetForRead()
        assertFails { CountVariableListCodec.decode(buf, DecodeContext.Empty) }
    }

    // (d) unknown @PacketType discriminator ---------------------------------

    @Test
    fun unknownDiscriminatorThrowsDecodeException() {
        val buf = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0x63.toByte()) // no BoundaryDisp variant uses 0x63
        buf.resetForRead()
        assertFailsWith<DecodeException> { BoundaryDispCodec.decode(buf, DecodeContext.Empty) }
    }

    @Test
    fun unknownNestedDiscriminatorThrowsDecodeException() {
        // Valid host string, then a bogus discriminator for the nested
        // sealed field.
        val buf = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buf.writeShort(0x0002.toShort())
        buf.writeByte('h'.code.toByte())
        buf.writeByte('i'.code.toByte())
        buf.writeByte(0x63.toByte())
        buf.resetForRead()
        assertFailsWith<DecodeException> { BoundaryHostCodec.decode(buf, DecodeContext.Empty) }
    }

    // Sanity: the well-formed twins of the malformed vectors decode fine ----

    @Test
    fun wellFormedTwinsStillDecode() {
        val ts = TwoStringsCodec.encodeToPlatformBuffer(TwoStrings("hello", "world"))
        TwoStringsCodec.decode(ts, DecodeContext.Empty)
        val cl = CountVariableListCodec.encodeToPlatformBuffer(CountVariableList(listOf(CountNamed("hi"))))
        CountVariableListCodec.decode(cl, DecodeContext.Empty)
    }
}
