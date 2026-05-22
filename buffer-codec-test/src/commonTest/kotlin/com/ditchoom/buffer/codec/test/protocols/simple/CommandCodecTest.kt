package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Doctrine vector. Validates simple `@PacketType` sealed
 * dispatch round-trip across both variants, the per-variant
 * `wireSize` shape (literal `Exact` for all-scalar `Ping`, `BackPatch`
 * for `Echo` whose terminal is `@LengthPrefixed val: String`),
 * `peekFrameSize` drip-feeding through the discriminator + variant
 * frame, and the unknown-discriminator failure mode (Locked Decision
 * row 17).
 *
 * Wire layout:
 *   - `Ping(ts = 0x1122_3344_5566_7788)` → `01 11 22 33 44 55 66 77 88`
 *   - `Echo(msg = "hi")`                  → `02 00 02 68 69`
 */
class CommandCodecTest {
    @Test
    fun roundTripsPing() {
        val sample = Command.Ping(ts = 0x1122_3344_5566_7788L)
        val expected = byteArrayOf(0x01, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77.toByte(), 0x88.toByte())
        roundTrip(sample, expected)
    }

    @Test
    fun roundTripsEcho() {
        val sample = Command.Echo(msg = "hi")
        val expected = byteArrayOf(0x02, 0x00, 0x02, 0x68, 0x69)
        roundTrip(sample, expected)
    }

    @Test
    fun roundTripsEchoEmptyAndMultiByteUtf8() {
        roundTrip(Command.Echo(msg = ""), byteArrayOf(0x02, 0x00, 0x00))
        // "héllo 🌍" — 11 UTF-8 bytes; prefix 0x000B, then body bytes.
        val multiByte = Command.Echo(msg = "héllo 🌍")
        val encoded = encode(multiByte)
        // 1 (discriminator) + 2 (prefix) + 11 (UTF-8 body) = 14
        assertEquals(14, encoded.position(), "encoded byte count for multi-byte Echo")
        encoded.resetForRead()
        val decoded = CommandCodec.decode(encoded, DecodeContext.Empty)
        assertEquals(multiByte, decoded)
    }

    @Test
    fun wireSizePingIsLiteralExact() {
        // Per-variant tightness: Ping reports its real fixed size 1 + 8 = 9
        // even though it shares a sealed parent with the variable-length Echo.
        assertEquals(
            WireSize.Exact(9),
            CommandCodec.wireSize(Command.Ping(ts = 0L), EncodeContext.Empty),
        )
    }

    @Test
    fun wireSizeEchoIsBackPatch() {
        // Echo's terminal is @LengthPrefixed val: String
        // forces BackPatch on that field shape; the dispatcher propagates.
        assertEquals(
            WireSize.BackPatch,
            CommandCodec.wireSize(Command.Echo(msg = "anything"), EncodeContext.Empty),
        )
    }

    @Test
    fun peekFrameSizePingDripFeed() {
        peekFrameSizeDripFeed(Command.Ping(ts = 0x1122_3344_5566_7788L), totalExpected = 9)
    }

    @Test
    fun peekFrameSizeEchoDripFeed() {
        peekFrameSizeDripFeed(Command.Echo(msg = "hi"), totalExpected = 5)
    }

    @Test
    fun decodeUnknownDiscriminatorThrows() {
        val buf = BufferFactory.Default.allocate(4)
        buf.writeByte(0xFF.toByte())
        buf.writeByte(0x00)
        buf.writeByte(0x00)
        buf.resetForRead()
        val ex =
            assertFailsWith<DecodeException> {
                CommandCodec.decode(buf, DecodeContext.Empty)
            }
        assertEquals("Command.discriminator", ex.fieldPath)
        assertEquals(0, ex.bufferPosition)
        assertEquals("one of {0x01, 0x02}", ex.expected)
        assertEquals("0xFF", ex.actual)
    }

    @Test
    fun peekFrameSizeUnknownDiscriminatorThrows() {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            val one = BufferFactory.Default.allocate(1)
            one.writeByte(0xFF.toByte())
            one.resetForRead()
            stream.append(one)
            // Per the dispatcher peek throws on unknown
            // discriminator — peeking a malformed stream isn't recoverable by
            // reading more bytes, so NoFraming would be a misleading signal.
            val ex =
                assertFailsWith<DecodeException> {
                    CommandCodec.peekFrameSize(stream)
                }
            assertEquals("Command.discriminator", ex.fieldPath)
            assertEquals(0, ex.bufferPosition)
            assertEquals("one of {0x01, 0x02}", ex.expected)
            assertEquals("0xFF", ex.actual)
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun peekFrameSizeDripFeed(
        sample: Command,
        totalExpected: Int,
    ) {
        val pool = BufferPool()
        val encoded = encode(sample)
        encoded.resetForRead()
        val totalBytes = encoded.remaining()
        assertEquals(totalExpected, totalBytes, "encoded byte count")

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, CommandCodec.peekFrameSize(stream))
            for (i in 0 until totalBytes - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(encoded.readByte())
                one.resetForRead()
                stream.append(one)
                val result = CommandCodec.peekFrameSize(stream)
                assertTrue(
                    result == PeekResult.NeedsMoreData,
                    "after ${i + 1} bytes expected NeedsMoreData, got $result",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), CommandCodec.peekFrameSize(stream))

            val decoded =
                stream.readBufferScoped(totalBytes) {
                    CommandCodec.decode(this, DecodeContext.Empty)
                }
            assertEquals(sample, decoded)
            assertEquals(0, stream.available(), "stream should be drained")
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun roundTrip(
        sample: Command,
        expectedBytes: ByteArray,
    ) {
        val buf = encode(sample)
        assertEquals(expectedBytes.size, buf.position(), "encode wrote expectedBytes.size bytes")
        buf.resetForRead()
        val actual = ByteArray(expectedBytes.size)
        for (i in expectedBytes.indices) actual[i] = buf.readByte()
        assertContentEqualsHex(expectedBytes, actual)
        buf.resetForRead()
        val decoded = CommandCodec.decode(buf, DecodeContext.Empty)
        assertEquals(sample, decoded)
    }

    private fun assertContentEqualsHex(
        expected: ByteArray,
        actual: ByteArray,
    ) {
        if (expected.contentEquals(actual)) return
        val expectedHex =
            expected.joinToString(" ") {
                it
                    .toUByte()
                    .toString(16)
                    .padStart(2, '0')
                    .uppercase()
            }
        val actualHex =
            actual.joinToString(" ") {
                it
                    .toUByte()
                    .toString(16)
                    .padStart(2, '0')
                    .uppercase()
            }
        kotlin.test.fail("wire bytes mismatch.\n  expected: $expectedHex\n    actual: $actualHex")
    }

    private fun encode(value: Command) =
        BufferFactory.Default
            .allocate(256)
            .also { CommandCodec.encode(it, value, EncodeContext.Empty) }
}
