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
 * Stage E slice 5a doctrine vector. Validates the sequential peek
 * walk and non-terminal `@LengthPrefixed val: String` support: two
 * consecutive LPS String fields round-trip correctly, encode
 * BackPatches both prefixes in sequence, decode reads both
 * sequentially, `WireSize.BackPatch` per Locked Decision row 15
 * (any LPS String → BackPatch), and `peekFrameSize` walks both
 * variable-length bodies via the running offset.
 */
class TwoStringsCodecTest {
    @Test
    fun roundTripsTwoNonEmptyStrings() {
        roundTrip(TwoStrings("hi", "yo"), expectedTotalBytes = 2 + 2 + 2 + 2)
    }

    @Test
    fun roundTripsEmptyAndNonEmpty() {
        roundTrip(TwoStrings("", "yo"), expectedTotalBytes = 2 + 0 + 2 + 2)
    }

    @Test
    fun roundTripsNonEmptyAndEmpty() {
        roundTrip(TwoStrings("hi", ""), expectedTotalBytes = 2 + 2 + 2 + 0)
    }

    @Test
    fun roundTripsBothEmpty() {
        roundTrip(TwoStrings("", ""), expectedTotalBytes = 2 + 0 + 2 + 0)
    }

    @Test
    fun encodeWritesPrefixedBodiesInSequence() {
        val buf = encode(TwoStrings("hi", "yo"))
        assertEquals(8, buf.position(), "encode wrote 2+2 + 2+2 = 8 bytes")
        buf.resetForRead()
        assertEquals(2.toShort(), buf.readShort(), "first prefix")
        assertEquals('h'.code.toByte(), buf.readByte())
        assertEquals('i'.code.toByte(), buf.readByte())
        assertEquals(2.toShort(), buf.readShort(), "second prefix")
        assertEquals('y'.code.toByte(), buf.readByte())
        assertEquals('o'.code.toByte(), buf.readByte())
    }

    @Test
    fun wireSizeIsBackPatch() {
        // Locked Decision row 15: any @LengthPrefixed val: String collapses to BackPatch.
        assertEquals(
            WireSize.BackPatch,
            TwoStringsCodec.wireSize(TwoStrings("hi", "yo"), EncodeContext.Empty),
        )
        assertEquals(
            WireSize.BackPatch,
            TwoStringsCodec.wireSize(TwoStrings("", ""), EncodeContext.Empty),
        )
    }

    @Test
    fun peekFrameSizeNeedsMoreDataWhenEmpty() {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, TwoStringsCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeWalksThroughBothVariableBodies() {
        val pool = BufferPool()
        val original = TwoStrings("hi", "world")
        val encoded = encode(original)
        encoded.resetForRead()
        val totalBytes = encoded.remaining()

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, TwoStringsCodec.peekFrameSize(stream))

            for (i in 0 until totalBytes - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(encoded.readByte())
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    TwoStringsCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), TwoStringsCodec.peekFrameSize(stream))

            val decoded =
                stream.readBufferScoped(totalBytes) {
                    TwoStringsCodec.decode(this, DecodeContext.Empty)
                }
            assertEquals(original, decoded)
            assertEquals(0, stream.available(), "stream should be drained")
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun roundTrip(
        original: TwoStrings,
        expectedTotalBytes: Int,
    ) {
        val buf = encode(original)
        assertEquals(expectedTotalBytes, buf.position(), "encode wrote $expectedTotalBytes bytes")
        buf.resetForRead()
        val decoded = TwoStringsCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    private fun encode(value: TwoStrings) =
        BufferFactory.Default
            .allocate(256)
            .also { TwoStringsCodec.encode(it, value, EncodeContext.Empty) }
}
