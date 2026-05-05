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
import kotlin.test.assertNull

/**
 * Stage E slice-2 doctrine vector. Validates round-trip across both
 * predicate values for `@When("hasExtra")`, encoder zero-byte
 * skip when the predicate is false, decoder nullness round-trip,
 * `WireSize.BackPatch` per Locked Decision row 19, and
 * `peekFrameSize` walking from `NeedsMoreData` to `Complete` via
 * drip-feeding the boolean and the conditional body bytes.
 */
class WithOptionalCodecTest {
    @Test
    fun roundTripsPredicateTrue() {
        roundTrip(WithOptional(hasExtra = true, extra = 0x12345678), expectedTotalBytes = 1 + 4)
    }

    @Test
    fun roundTripsPredicateFalse() {
        roundTrip(WithOptional(hasExtra = false), expectedTotalBytes = 1)
    }

    @Test
    fun encodePredicateFalseWritesOnlyTheBooleanByte() {
        val buf = encode(WithOptional(hasExtra = false))
        assertEquals(1, buf.position(), "predicate-false encode writes only the boolean byte")
        buf.resetForRead()
        assertEquals(0.toByte(), buf.readByte(), "false encodes to 0x00")
    }

    @Test
    fun encodePredicateTrueWritesBooleanThenInt() {
        val buf = encode(WithOptional(hasExtra = true, extra = 0x01020304))
        assertEquals(5, buf.position(), "predicate-true encode writes 1 + 4 bytes")
        buf.resetForRead()
        assertEquals(1.toByte(), buf.readByte(), "true encodes to 0x01")
        assertEquals(0x01020304, buf.readInt(), "extra encoded big-endian")
    }

    @Test
    fun decodePredicateFalseLeavesExtraNull() {
        val buf = BufferFactory.Default.allocate(1)
        buf.writeByte(0)
        buf.resetForRead()
        val decoded = WithOptionalCodec.decode(buf, DecodeContext.Empty)
        assertEquals(false, decoded.hasExtra)
        assertNull(decoded.extra)
    }

    @Test
    fun wireSizeIsBackPatch() {
        // Locked Decision row 19: any @When field collapses message wireSize to BackPatch.
        assertEquals(
            WireSize.BackPatch,
            WithOptionalCodec.wireSize(WithOptional(hasExtra = true, extra = 1), EncodeContext.Empty),
        )
        assertEquals(
            WireSize.BackPatch,
            WithOptionalCodec.wireSize(WithOptional(hasExtra = false), EncodeContext.Empty),
        )
    }

    @Test
    fun peekFrameSizeNeedsMoreDataWhenEmpty() {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, WithOptionalCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeCompletesAtOneByteWhenPredicateFalse() {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            val one = BufferFactory.Default.allocate(1)
            one.writeByte(0)
            one.resetForRead()
            stream.append(one)
            assertEquals(PeekResult.Complete(1), WithOptionalCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeWalksNeedsMoreDataToCompleteWhenPredicateTrue() {
        val pool = BufferPool()
        val original = WithOptional(hasExtra = true, extra = 0x12345678)
        val encoded = encode(original)
        encoded.resetForRead()
        val totalBytes = encoded.remaining()

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, WithOptionalCodec.peekFrameSize(stream))

            for (i in 0 until totalBytes - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(encoded.readByte())
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    WithOptionalCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), WithOptionalCodec.peekFrameSize(stream))

            val decoded =
                stream.readBufferScoped(totalBytes) {
                    WithOptionalCodec.decode(this, DecodeContext.Empty)
                }
            assertEquals(original, decoded)
            assertEquals(0, stream.available(), "stream should be drained")
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun roundTrip(
        original: WithOptional,
        expectedTotalBytes: Int,
    ) {
        val buf = encode(original)
        assertEquals(expectedTotalBytes, buf.position(), "encode wrote $expectedTotalBytes bytes")
        buf.resetForRead()
        val decoded = WithOptionalCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    private fun encode(value: WithOptional) =
        BufferFactory.Default
            .allocate(256)
            .also { WithOptionalCodec.encode(it, value, EncodeContext.Empty) }
}
