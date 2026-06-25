package com.ditchoom.buffer.codec.test.protocols.count

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Round-trip + byte-identity coverage for `@Count` element-count-prefixed
 * lists across empty / one / many elements, a fixed-width element type
 * (`CountPoint`), a variable-width element type (`CountNamed`, length-prefixed
 * string), and a non-terminal `@Count` list followed by a fixed trailer.
 *
 * Count rides as an unsigned-LEB128 `varint(N)` — one byte for N in 0..127.
 */
class CountPrefixedListCodecTest {
    // ---- fixed-width element list ----------------------------------------

    @Test
    fun encodesEmptyCountListByteExact() {
        val msg = CountFixedList(id = 0x01u, points = emptyList())
        // id + varint(0)
        encodeAndAssertBytes(msg, byteArrayOf(0x01, 0x00))
    }

    @Test
    fun encodesSingleElementByteExact() {
        val msg = CountFixedList(id = 0x01u, points = listOf(CountPoint(x = 0x0010u, y = 0x00u)))
        encodeAndAssertBytes(
            msg,
            byteArrayOf(
                0x01, // id
                0x01, // varint count = 1
                0x00,
                0x10,
                0x00, // point 0
            ),
        )
    }

    @Test
    fun encodesManyElementsByteExact() {
        val msg =
            CountFixedList(
                id = 0x01u,
                points =
                    listOf(
                        CountPoint(x = 0x0010u, y = 0x00u),
                        CountPoint(x = 0x0020u, y = 0x01u),
                        CountPoint(x = 0x0030u, y = 0x80u),
                    ),
            )
        encodeAndAssertBytes(
            msg,
            byteArrayOf(
                0x01, // id
                0x03, // varint count = 3
                0x00,
                0x10,
                0x00,
                0x00,
                0x20,
                0x01,
                0x00,
                0x30,
                0x80.toByte(),
            ),
        )
    }

    @Test
    fun roundTripsFixedListEmptyOneMany() {
        for (
        points in
        listOf(
            emptyList(),
            listOf(CountPoint(0x0100u, 0x00u)),
            listOf(CountPoint(0x0100u, 0x00u), CountPoint(0x0200u, 0x02u), CountPoint(0xFFFFu, 0xFEu)),
        )
        ) {
            val original = CountFixedList(id = 0x2Au, points = points)
            val buf = encode(original)
            buf.resetForRead()
            assertEquals(original, CountFixedListCodec.decode(buf, DecodeContext.Empty))
        }
    }

    @Test
    fun wireSizeFixedListIsExact() {
        // 1 (id) + 1 (varint count) + 3 × N.
        val three =
            CountFixedList(
                id = 1u,
                points = listOf(CountPoint(0u, 0u), CountPoint(1u, 1u), CountPoint(2u, 2u)),
            )
        assertEquals(WireSize.Exact(1 + 1 + 9), CountFixedListCodec.wireSize(three, EncodeContext.Empty))
        val empty = CountFixedList(id = 1u, points = emptyList())
        assertEquals(WireSize.Exact(2), CountFixedListCodec.wireSize(empty, EncodeContext.Empty))
    }

    @Test
    fun decodeStopsAtCountNotBufferEnd() {
        // Wire holds the message then trailing bytes that must remain
        // unconsumed: count is self-delimiting, so decode stops after N.
        val buf = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0x07.toByte()) // id
        buf.writeByte(0x02.toByte()) // count = 2
        buf.writeShort(0x0001.toShort())
        buf.writeByte(0x00.toByte())
        buf.writeShort(0x0002.toShort())
        buf.writeByte(0x01.toByte())
        // Trailing bytes — next frame, must not be consumed.
        buf.writeByte(0xDE.toByte())
        buf.writeByte(0xAD.toByte())
        buf.resetForRead()

        val decoded = CountFixedListCodec.decode(buf, DecodeContext.Empty)
        assertEquals(2, decoded.points.size)
        assertEquals(8, buf.position(), "decode advanced exactly past the counted elements")
    }

    // ---- variable-width element list -------------------------------------

    @Test
    fun encodesVariableListByteExact() {
        val msg = CountVariableList(names = listOf(CountNamed("hi"), CountNamed("abc")))
        val expected =
            byteArrayOf(
                0x02, // varint count = 2
                0x00,
                0x02,
                'h'.code.toByte(),
                'i'.code.toByte(), // LP "hi"
                0x00,
                0x03,
                'a'.code.toByte(),
                'b'.code.toByte(),
                'c'.code.toByte(), // LP "abc"
            )
        val buf = buildBuffer { CountVariableListCodec.encode(it, msg, EncodeContext.Empty) }
        assertEquals(expected.size, buf.position())
        buf.resetForRead()
        assertContentEquals(expected, buf.readByteArray(expected.size))
    }

    @Test
    fun roundTripsVariableListEmptyOneMany() {
        for (
        names in
        listOf(
            emptyList(),
            listOf(CountNamed("solo")),
            listOf(CountNamed(""), CountNamed("a"), CountNamed("longer name here")),
        )
        ) {
            val original = CountVariableList(names = names)
            val buf = buildBuffer { CountVariableListCodec.encode(it, original, EncodeContext.Empty) }
            buf.resetForRead()
            assertEquals(original, CountVariableListCodec.decode(buf, DecodeContext.Empty))
        }
    }

    @Test
    fun wireSizeVariableListIsBackPatch() {
        // The element `CountNamed` carries a `@LengthPrefixed String`, so its
        // wireSize is BackPatch; a `@Count` list of BackPatch-shaped elements
        // collapses the containing message to BackPatch (the encode path uses a
        // growable buffer and reports the actual byte count after the body).
        val msg = CountVariableList(names = listOf(CountNamed("hi"), CountNamed("abc")))
        assertEquals(WireSize.BackPatch, CountVariableListCodec.wireSize(msg, EncodeContext.Empty))
    }

    // ---- non-terminal @Count + trailer -----------------------------------

    @Test
    fun roundTripsCountThenTrailer() {
        val original =
            CountThenTrailer(
                points = listOf(CountPoint(0x0001u, 0x02u), CountPoint(0x0003u, 0x04u)),
                trailer = 0xDEADBEEFu,
            )
        val buf = buildBuffer { CountThenTrailerCodec.encode(it, original, EncodeContext.Empty) }
        buf.resetForRead()
        assertEquals(original, CountThenTrailerCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun encodesCountThenTrailerByteExact() {
        val msg =
            CountThenTrailer(
                points = listOf(CountPoint(0x0001u, 0x02u), CountPoint(0x0003u, 0x04u)),
                trailer = 0xDEADBEEFu,
            )
        val buf = buildBuffer { CountThenTrailerCodec.encode(it, msg, EncodeContext.Empty) }
        val expected =
            byteArrayOf(
                0x02, // count = 2
                0x00,
                0x01,
                0x02,
                0x00,
                0x03,
                0x04,
                0xDE.toByte(),
                0xAD.toByte(),
                0xBE.toByte(),
                0xEF.toByte(), // trailer
            )
        assertEquals(expected.size, buf.position())
        buf.resetForRead()
        assertContentEquals(expected, buf.readByteArray(expected.size))
    }

    @Test
    fun wireSizeCountThenTrailerIsExact() {
        val msg =
            CountThenTrailer(
                points = listOf(CountPoint(0u, 0u), CountPoint(1u, 1u)),
                trailer = 0u,
            )
        // varint(2) + 3 × 2 + 4 (trailer) = 1 + 6 + 4.
        assertEquals(WireSize.Exact(1 + 6 + 4), CountThenTrailerCodec.wireSize(msg, EncodeContext.Empty))
    }

    // ---- peek -------------------------------------------------------------

    @Test
    fun peekFrameSizeReportsNoFraming() {
        // A @Count list's frame size needs per-element decode (count is an
        // element count, not a byte span) — peek conservatively reports
        // NoFraming, consistent with the byte-length list shapes.
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NoFraming, CountFixedListCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    // ---- helpers ----------------------------------------------------------

    private fun encodeAndAssertBytes(
        msg: CountFixedList,
        expected: ByteArray,
    ) {
        val buf = encode(msg)
        assertEquals(expected.size, buf.position(), "encoded byte count matches wire layout")
        buf.resetForRead()
        assertContentEquals(expected, buf.readByteArray(expected.size), "encoded bytes match expected wire layout")
    }

    private fun encode(v: CountFixedList) = buildBuffer { CountFixedListCodec.encode(it, v, EncodeContext.Empty) }

    private fun buildBuffer(
        capacity: Int = 64,
        block: (com.ditchoom.buffer.PlatformBuffer) -> Unit,
    ) = BufferFactory.Default.allocate(capacity, ByteOrder.BIG_ENDIAN).also(block)
}
