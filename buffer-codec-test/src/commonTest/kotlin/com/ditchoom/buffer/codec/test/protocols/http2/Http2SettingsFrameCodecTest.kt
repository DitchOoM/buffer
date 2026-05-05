package com.ditchoom.buffer.codec.test.protocols.http2

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
 * Stage G slice 7a doctrine vector. Validates `@LengthFrom` on
 * `List<@ProtocolMessage>`: the SETTINGS frame's `length` field
 * provides the byte count for the variable-length entries list.
 * The decoder bounds the buffer via `setLimit`, loops reading
 * 6-byte `Http2Setting` entries via the element codec until the
 * bounded position is reached, restores the outer limit. Encoder
 * iterates the list and writes each element. WireSize is `Exact`
 * via the sibling.
 *
 * Vectors are byte-exact against RFC 7540 §6.5.
 */
class Http2SettingsFrameCodecTest {
    @Test
    fun encodesEmptySettingsFrameByteExact() {
        val msg =
            Http2SettingsFrame(
                length = 0u,
                type = 0x04u,
                flags = 0x00u,
                streamId = Http2StreamId(0u),
                entries = emptyList(),
            )
        val expected =
            byteArrayOf(
                // length = 0 (24-bit BE)
                0x00,
                0x00,
                0x00,
                // type = 0x04 (SETTINGS)
                0x04,
                // flags
                0x00,
                // streamId = 0
                0x00,
                0x00,
                0x00,
                0x00,
            )
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun encodesSettingsFrameWithTwoEntriesByteExact() {
        // SETTINGS_MAX_CONCURRENT_STREAMS (0x3) = 100,
        // SETTINGS_INITIAL_WINDOW_SIZE (0x4) = 65535
        val msg =
            Http2SettingsFrame(
                length = 12u,
                type = 0x04u,
                flags = 0x00u,
                streamId = Http2StreamId(0u),
                entries =
                    listOf(
                        Http2Setting(identifier = 0x0003u, value = 100u),
                        Http2Setting(identifier = 0x0004u, value = 65535u),
                    ),
            )
        val expected =
            byteArrayOf(
                // header: length=12, type=4, flags=0, streamId=0
                0x00,
                0x00,
                0x0C,
                0x04,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                // entry 1: identifier=3, value=100
                0x00,
                0x03,
                0x00,
                0x00,
                0x00,
                0x64,
                // entry 2: identifier=4, value=65535
                0x00,
                0x04,
                0x00,
                0x00,
                0xFF.toByte(),
                0xFF.toByte(),
            )
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun decodesEmptySettingsFrame() {
        val wire =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x04,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
            )
        val buf = bigEndianBufferOf(wire)
        val decoded = Http2SettingsFrameCodec.decode(buf, DecodeContext.Empty)
        assertEquals(0u, decoded.length)
        assertEquals(0x04u.toUByte(), decoded.type)
        assertEquals(emptyList(), decoded.entries)
    }

    @Test
    fun decodesSettingsFrameWithEntries() {
        val wire =
            byteArrayOf(
                0x00,
                0x00,
                0x0C,
                0x04,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x03,
                0x00,
                0x00,
                0x00,
                0x64,
                0x00,
                0x04,
                0x00,
                0x00,
                0xFF.toByte(),
                0xFF.toByte(),
            )
        val buf = bigEndianBufferOf(wire)
        val decoded = Http2SettingsFrameCodec.decode(buf, DecodeContext.Empty)
        assertEquals(12u, decoded.length)
        assertEquals(2, decoded.entries.size)
        assertEquals(Http2Setting(0x0003u, 100u), decoded.entries[0])
        assertEquals(Http2Setting(0x0004u, 65535u), decoded.entries[1])
    }

    @Test
    fun decodeRespectsLengthBoundEvenIfMoreBytesAvailable() {
        // Wire has 12-byte SETTINGS payload but extra trailing bytes
        // after — decode must stop at length=12 and leave remaining
        // bytes for the next read.
        val wire =
            byteArrayOf(
                0x00,
                0x00,
                0x06,
                0x04,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                // one entry (6 bytes) — bounded by length=6
                0x00,
                0x03,
                0x00,
                0x00,
                0x00,
                0x64,
                // trailing bytes that look like a second entry — must NOT be consumed
                0x00,
                0x04,
                0x00,
                0x00,
                0xFF.toByte(),
                0xFF.toByte(),
            )
        val buf = bigEndianBufferOf(wire)
        val decoded = Http2SettingsFrameCodec.decode(buf, DecodeContext.Empty)
        assertEquals(1, decoded.entries.size, "decode bounded by length, not by buffer remaining")
        assertEquals(6, buf.remaining(), "trailing 6 bytes left in buffer")
    }

    @Test
    fun roundTripsThreeEntries() {
        val original =
            Http2SettingsFrame(
                length = 18u,
                type = 0x04u,
                flags = 0x00u,
                streamId = Http2StreamId(0u),
                entries =
                    listOf(
                        Http2Setting(identifier = 0x0001u, value = 4096u),
                        Http2Setting(identifier = 0x0005u, value = 16384u),
                        Http2Setting(identifier = 0x0006u, value = 0xFFFFFFFFu),
                    ),
            )
        val buf = encode(original)
        buf.resetForRead()
        val decoded = Http2SettingsFrameCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun wireSizeIsExactBasedOnLength() {
        val msg =
            Http2SettingsFrame(
                length = 12u,
                type = 0x04u,
                flags = 0x00u,
                streamId = Http2StreamId(0u),
                entries =
                    listOf(
                        Http2Setting(identifier = 1u, value = 100u),
                        Http2Setting(identifier = 2u, value = 200u),
                    ),
            )
        // 9 (fixed header bytes) + 12 (length value) = 21
        assertEquals(WireSize.Exact(21), Http2SettingsFrameCodec.wireSize(msg, EncodeContext.Empty))
    }

    @Test
    fun peekFrameSizeWalksDripFedSettingsFrame() {
        val pool = BufferPool()
        val original =
            Http2SettingsFrame(
                length = 6u,
                type = 0x04u,
                flags = 0x00u,
                streamId = Http2StreamId(0u),
                entries = listOf(Http2Setting(identifier = 1u, value = 4096u)),
            )
        val encoded = encode(original)
        encoded.resetForRead()
        val totalBytes = encoded.remaining()
        // 9 (header) + 6 (one entry) = 15
        assertEquals(15, totalBytes)

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            for (i in 0 until totalBytes - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(encoded.readByte())
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    Http2SettingsFrameCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), Http2SettingsFrameCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun encodeAndAssertBytes(
        msg: Http2SettingsFrame,
        expected: ByteArray,
    ) {
        val buf = encode(msg)
        assertEquals(expected.size, buf.position(), "encoded byte count matches RFC 7540 §6.5 layout")
        buf.resetForRead()
        val actual = buf.readByteArray(expected.size)
        assertContentEquals(expected, actual, "encoded bytes match RFC 7540 §6.5")
    }

    private fun bigEndianBufferOf(wire: ByteArray) =
        BufferFactory.Default
            .allocate(wire.size, ByteOrder.BIG_ENDIAN)
            .also { it.writeBytes(wire) }
            .also { it.resetForRead() }

    private fun encode(value: Http2SettingsFrame) =
        BufferFactory.Default
            .allocate(256, ByteOrder.BIG_ENDIAN)
            .also { Http2SettingsFrameCodec.encode(it, value, EncodeContext.Empty) }
}
