package com.ditchoom.buffer.codec.test.protocols.simple

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
import kotlin.test.assertEquals

/**
 * Doctrine vector. Validates round-trip across the empty,
 * ASCII, and multi-byte UTF-8 cases for `@LengthPrefixed val: String`,
 * confirms `wireSize` returns `BackPatch` (locked decision row 15),
 * and walks `peekFrameSize` from `NeedsMoreData` through `Complete`
 * via drip-feeding the prefix and body bytes.
 *
 * Wire layout for `SimpleHeader(id=42, name="hi")`:
 *   bytes [00 00 00 2A | 00 02 | 'h' 'i']
 *   id (BE Int) = 0x0000_002A
 *   prefix (BE UShort) = 2  (UTF-8 byte length of "hi")
 *   body = 0x68 0x69
 */
class SimpleHeaderCodecTest {
    @Test
    fun roundTripsEmptyString() {
        roundTrip(SimpleHeader(id = 0, name = ""), expectedTotalBytes = 4 + 2)
    }

    @Test
    fun roundTripsAscii() {
        roundTrip(SimpleHeader(id = 42, name = "hello"), expectedTotalBytes = 4 + 2 + 5)
    }

    @Test
    fun roundTripsMultiByteUtf8() {
        // "héllo 🌍" — h(1) é(2) l(1) l(1) o(1) space(1) earth-emoji(4) = 11 UTF-8 bytes.
        val name = "héllo 🌍"
        roundTrip(SimpleHeader(id = -1, name = name), expectedTotalBytes = 4 + 2 + 11)
    }

    @Test
    fun roundTripsLargestSignedInt() {
        // Int range coverage — confirms signed scalar emit handles negatives and Int.MAX_VALUE.
        roundTrip(SimpleHeader(id = Int.MAX_VALUE, name = "max"), expectedTotalBytes = 4 + 2 + 3)
        roundTrip(SimpleHeader(id = Int.MIN_VALUE, name = "min"), expectedTotalBytes = 4 + 2 + 3)
    }

    @Test
    fun wireSizeIsBackPatch() {
        // Locked decision row 15: `@LengthPrefixed val: String` defaults to BackPatch.
        assertEquals(
            WireSize.BackPatch,
            SimpleHeaderCodec.wireSize(SimpleHeader(id = 1, name = "anything"), EncodeContext.Empty),
        )
    }

    @Test
    fun peekFrameSizeWalksNeedsMoreDataToComplete() {
        val pool = BufferPool()
        val original = SimpleHeader(id = 0x12345678, name = "abc")
        val encoded = encode(original)
        encoded.resetForRead()
        val totalBytes = encoded.remaining()

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, SimpleHeaderCodec.peekFrameSize(stream))

            // Drip-feed everything but the last byte. Each call must report NeedsMoreData.
            for (i in 0 until totalBytes - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(encoded.readByte())
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    SimpleHeaderCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }

            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), SimpleHeaderCodec.peekFrameSize(stream))

            val decoded =
                stream.readBufferScoped(totalBytes) {
                    SimpleHeaderCodec.decode(this, DecodeContext.Empty)
                }
            assertEquals(original, decoded)
            assertEquals(0, stream.available(), "stream should be drained")
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun roundTrip(
        original: SimpleHeader,
        expectedTotalBytes: Int,
    ) {
        val buf = encode(original)
        assertEquals(expectedTotalBytes, buf.position(), "encode wrote $expectedTotalBytes bytes")
        buf.resetForRead()
        val decoded = SimpleHeaderCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    private fun encode(value: SimpleHeader) =
        BufferFactory.Default
            .allocate(256)
            .also { SimpleHeaderCodec.encode(it, value, EncodeContext.Empty) }
}
