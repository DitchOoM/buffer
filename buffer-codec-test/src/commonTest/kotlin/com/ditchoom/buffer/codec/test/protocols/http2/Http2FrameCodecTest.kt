package com.ditchoom.buffer.codec.test.protocols.http2

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Stage F slice 6.5 doctrine vector. Validates the bit-packed
 * `@DispatchOn(Http2LengthAndType::class)` dispatcher with a
 * 4-byte (UInt) discriminator big-endian per RFC 7540 §4.1:
 * peek-without-consume decode reconstructs the value class from
 * the first 4 wire bytes, extracts `type` (low 8 bits), dispatches
 * to the variant codec which then reads the same 4 bytes as its
 * `header` field plus the rest of the frame.
 *
 * Vectors are byte-exact against RFC 7540 §6.7 (PING) and §6.9
 * (WINDOW_UPDATE).
 */
class Http2FrameCodecTest {
    @Test
    fun lengthAndTypeExtractsPackedFields() {
        val h = Http2LengthAndType.of(length = 8, type = 6)
        assertEquals(8, h.length)
        assertEquals(6, h.type)
        // Packed wire form: top 24 bits = length, low 8 bits = type.
        assertEquals(0x00_00_08_06u, h.raw)
    }

    @Test
    fun encodesPingByteExact() {
        // RFC 7540 §6.7: PING is type=6, length=8, streamId=0, 8-byte opaque payload.
        val msg =
            Http2Frame.Ping(
                header = Http2LengthAndType.of(length = 8, type = 6),
                flags = 0x00u,
                streamId = Http2StreamId(0u),
                opaqueData = 0xDEAD_BEEF_CAFE_F00DuL,
            )
        val expected =
            byteArrayOf(
                // Header: length=8 (24-bit BE), type=6
                0x00, 0x00, 0x08, 0x06,
                // Flags
                0x00,
                // StreamId (UInt BE), 0
                0x00, 0x00, 0x00, 0x00,
                // Opaque data (ULong BE)
                0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
                0xCA.toByte(), 0xFE.toByte(), 0xF0.toByte(), 0x0D.toByte(),
            )
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun encodesWindowUpdateByteExact() {
        // RFC 7540 §6.9: WINDOW_UPDATE is type=8, length=4.
        val msg =
            Http2Frame.WindowUpdate(
                header = Http2LengthAndType.of(length = 4, type = 8),
                flags = 0x00u,
                streamId = Http2StreamId(1u),
                windowSizeIncrement = 65535u,
            )
        val expected =
            byteArrayOf(
                // Header: length=4 (24-bit BE), type=8
                0x00, 0x00, 0x04, 0x08,
                0x00,
                // StreamId (UInt BE) = 1
                0x00, 0x00, 0x00, 0x01,
                // windowSizeIncrement (UInt BE) = 65535
                0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(),
            )
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun decodesPingFromSpecBytes() {
        val wire =
            byteArrayOf(
                0x00, 0x00, 0x08, 0x06,
                0x00,
                0x00, 0x00, 0x00, 0x00,
                0x12, 0x34, 0x56, 0x78,
                0x9A.toByte(), 0xBC.toByte(), 0xDE.toByte(), 0xF0.toByte(),
            )
        val buf = bigEndianBufferOf(wire)
        val decoded = Http2FrameCodec.decode(buf, DecodeContext.Empty)
        val ping = assertIs<Http2Frame.Ping>(decoded)
        assertEquals(8, ping.header.length)
        assertEquals(6, ping.header.type)
        assertEquals(0x00u.toUByte(), ping.flags)
        assertEquals(Http2StreamId(0u), ping.streamId)
        assertEquals(0x1234_5678_9ABC_DEF0uL, ping.opaqueData)
    }

    @Test
    fun decodesWindowUpdateFromSpecBytes() {
        val wire =
            byteArrayOf(
                0x00, 0x00, 0x04, 0x08,
                0x00,
                0x00, 0x00, 0x00, 0x05,
                0x00, 0x00, 0x10, 0x00,
            )
        val buf = bigEndianBufferOf(wire)
        val decoded = Http2FrameCodec.decode(buf, DecodeContext.Empty)
        val wu = assertIs<Http2Frame.WindowUpdate>(decoded)
        assertEquals(4, wu.header.length)
        assertEquals(8, wu.header.type)
        assertEquals(Http2StreamId(5u), wu.streamId)
        assertEquals(4096u, wu.windowSizeIncrement)
    }

    @Test
    fun http2StreamIdRejectsHighBitOnConstruction() {
        // Per RFC 7540 §4.1, the reserved `R` bit MUST be 0 on send.
        // The Http2StreamId value class enforces this at construction
        // time so the encoded bytes are spec-compliant by virtue of
        // the field's type.
        assertFailsWith<IllegalArgumentException> {
            Http2StreamId(0x80000000u)
        }
        // Boundary: 0x7FFFFFFF (max 31-bit value) is legal.
        assertEquals(0x7FFFFFFFu, Http2StreamId(0x7FFFFFFFu).raw)
    }

    @Test
    fun decodeThrowsWhenWireBytesViolateRBit() {
        // A peer that violates the spec by setting the R bit on a
        // streamId — our decode surfaces it loudly via the value class
        // init rather than silently masking. Lenient masking is a
        // connection-layer choice (mask before constructing the value
        // class); the codec stays strict so spec violations don't go
        // unnoticed.
        val wire =
            byteArrayOf(
                // Header: length=8 type=6 (PING)
                0x00, 0x00, 0x08, 0x06,
                0x00,
                // streamId with R bit set: 0x80_00_00_00
                0x80.toByte(), 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
            )
        val buf = bigEndianBufferOf(wire)
        assertFailsWith<IllegalArgumentException> {
            Http2FrameCodec.decode(buf, DecodeContext.Empty)
        }
    }

    @Test
    fun decodeThrowsOnUnknownDispatchValue() {
        // Type 0 (DATA) — not in our sealed set for slice 6.5.
        val wire = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        val buf = bigEndianBufferOf(wire)
        val ex =
            assertFailsWith<DecodeException> {
                Http2FrameCodec.decode(buf, DecodeContext.Empty)
            }
        assertEquals("Http2Frame.discriminator", ex.fieldPath)
    }

    @Test
    fun roundTripsPing() {
        val original =
            Http2Frame.Ping(
                header = Http2LengthAndType.of(length = 8, type = 6),
                flags = 0x01u,
                streamId = Http2StreamId(0u),
                opaqueData = 0x0102_0304_0506_0708uL,
            )
        val buf = encode(original)
        buf.resetForRead()
        assertEquals(original, Http2FrameCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun roundTripsWindowUpdate() {
        val original =
            Http2Frame.WindowUpdate(
                header = Http2LengthAndType.of(length = 4, type = 8),
                flags = 0x00u,
                streamId = Http2StreamId(12345u),
                windowSizeIncrement = 0x7FFF_FFFFu,
            )
        val buf = encode(original)
        buf.resetForRead()
        assertEquals(original, Http2FrameCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun peekFrameSizeNeedsMoreDataWhenLessThanDiscriminator() {
        // Need at least 4 bytes (the discriminator's wire width) to identify a variant.
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, Http2FrameCodec.peekFrameSize(stream))

            // Three bytes — still not enough.
            val three = BufferFactory.Default.allocate(3)
            three.writeByte(0x00.toByte())
            three.writeByte(0x00.toByte())
            three.writeByte(0x08.toByte())
            three.resetForRead()
            stream.append(three)
            assertEquals(PeekResult.NeedsMoreData, Http2FrameCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeWalksDripFedPing() {
        val pool = BufferPool()
        val original =
            Http2Frame.Ping(
                header = Http2LengthAndType.of(length = 8, type = 6),
                flags = 0u,
                streamId = Http2StreamId(0u),
                opaqueData = 0u,
            )
        val encoded = encode(original)
        encoded.resetForRead()
        val totalBytes = encoded.remaining()
        // Sanity: 4 (header) + 1 (flags) + 4 (streamId) + 8 (opaque) = 17.
        assertEquals(17, totalBytes)

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            for (i in 0 until totalBytes - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(encoded.readByte())
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    Http2FrameCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), Http2FrameCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun encodeAndAssertBytes(
        msg: Http2Frame,
        expected: ByteArray,
    ) {
        val buf = encode(msg)
        assertEquals(expected.size, buf.position(), "encoded byte count matches RFC 7540 §4.1 layout")
        buf.resetForRead()
        val actual = buf.readByteArray(expected.size)
        assertContentEquals(expected, actual, "encoded bytes match RFC 7540 §4.1")
    }

    private fun bigEndianBufferOf(wire: ByteArray) =
        BufferFactory.Default.allocate(wire.size, ByteOrder.BIG_ENDIAN)
            .also { it.writeBytes(wire) }
            .also { it.resetForRead() }

    private fun encode(value: Http2Frame) =
        BufferFactory.Default
            .allocate(256, ByteOrder.BIG_ENDIAN)
            .also { Http2FrameCodec.encode(it, value, EncodeContext.Empty) }
}
