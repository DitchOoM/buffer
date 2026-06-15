package com.ditchoom.buffer.codec.test.protocols.enums

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.UnsignedVarIntCodec
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Enum-field codegen: round-trip, runtime-Exact wireSize, and the evolution-safety property — an
 * old decoder meeting a newer entry's ordinal reads the self-delimiting varint correctly (framing
 * intact) and resolves to `@EnumDefault` (or throws when none is declared).
 */
class StyleCodecTest {
    @Test
    fun roundTripsEveryEnumCombination() {
        for (color in Color.entries) {
            for (priority in Priority.entries) {
                val original = Style(color, priority, 0x2Au)
                val buf = BufferFactory.Default.allocate(16)
                StyleCodec.encode(buf, original, EncodeContext.Empty)
                buf.resetForRead()
                assertEquals(original, StyleCodec.decode(buf, DecodeContext.Empty), "round-trip $original")
            }
        }
    }

    @Test
    fun wireSizeIsRuntimeExact() {
        // Every ordinal is < 128 → 1-byte varint each: color(1) + priority(1) + weight(1) = 3.
        assertEquals(
            WireSize.Exact(3),
            StyleCodec.wireSize(Style(Color.Blue, Priority.High, 1u), EncodeContext.Empty),
            "wireSize sums the two varint ordinals + the fixed weight byte",
        )
    }

    @Test
    fun unknownOrdinalDecodesToEnumDefaultWithFramingIntact() {
        // Simulate a newer peer: a Color ordinal (99) this build doesn't know, then a valid
        // Priority and weight. The self-delimiting varint means the unknown ordinal consumes
        // exactly its bytes, so Priority + weight still decode — proving framing survives.
        val buf = BufferFactory.Default.allocate(16)
        UnsignedVarIntCodec.encode(buf, 99u, EncodeContext.Empty)
        UnsignedVarIntCodec.encode(buf, Priority.Medium.ordinal.toUInt(), EncodeContext.Empty)
        buf.writeUByte(0x7Fu)
        buf.resetForRead()

        val decoded = StyleCodec.decode(buf, DecodeContext.Empty)
        assertEquals(Color.Unknown, decoded.color, "unknown ordinal falls back to @EnumDefault")
        assertEquals(Priority.Medium, decoded.priority, "field after the unknown ordinal still decodes")
        assertEquals(0x7Fu.toUByte(), decoded.weight, "framing intact past the unknown ordinal")
    }

    @Test
    fun singleEnumMessageFramesByPeek() {
        // Tagged = one enum varint + a fixed byte → peekFrameSize frames it: NeedsMoreData until
        // the varint + suffix are buffered, then Complete(total).
        val original = Tagged(Priority.High, 0x5Au)
        val src = BufferFactory.Default.allocate(8)
        TaggedCodec.encode(src, original, EncodeContext.Empty)
        src.resetForRead()
        val total = src.remaining()
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            for (i in 0 until total - 1) {
                appendByte(stream, src.readByte())
                assertEquals(PeekResult.NeedsMoreData, TaggedCodec.peekFrameSize(stream), "after ${i + 1}/$total bytes")
            }
            appendByte(stream, src.readByte())
            assertEquals(PeekResult.Complete(total), TaggedCodec.peekFrameSize(stream), "fully buffered")
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun unknownOrdinalWithoutDefaultThrows() {
        // Priority has no @EnumDefault, so an out-of-range ordinal is a hard DecodeException.
        val buf = BufferFactory.Default.allocate(16)
        UnsignedVarIntCodec.encode(buf, Color.Red.ordinal.toUInt(), EncodeContext.Empty)
        UnsignedVarIntCodec.encode(buf, 99u, EncodeContext.Empty)
        buf.writeUByte(0x01u)
        buf.resetForRead()
        assertFailsWith<DecodeException> { StyleCodec.decode(buf, DecodeContext.Empty) }
    }

    private fun appendByte(
        stream: StreamProcessor,
        byte: Byte,
    ) {
        val one: PlatformBuffer = BufferFactory.Default.allocate(1)
        one.writeByte(byte)
        one.resetForRead()
        stream.append(one)
    }
}
