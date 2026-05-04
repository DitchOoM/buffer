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
 * Stage E slice-3 doctrine vector. Validates the dotted-form
 * `@WhenTrue("flags.want")` resolver against the value-class field
 * `flags: SmallFlags`, plus value-class-as-field decode/encode
 * round-trip and `peekFrameSize` reconstruction of the value class
 * from the peeked inner-scalar bytes.
 */
class WithFlagPayloadCodecTest {
    @Test
    fun roundTripsPredicateTrue() {
        roundTrip(
            WithFlagPayload(flags = SmallFlags.WANT, payload = 0x12345678),
            expectedTotalBytes = 1 + 4,
        )
    }

    @Test
    fun roundTripsPredicateFalse() {
        roundTrip(WithFlagPayload(flags = SmallFlags.NONE), expectedTotalBytes = 1)
    }

    @Test
    fun encodePredicateFalseWritesOnlyTheFlagsByte() {
        val buf = encode(WithFlagPayload(flags = SmallFlags.NONE))
        assertEquals(1, buf.position(), "predicate-false encode writes only the flags byte")
        buf.resetForRead()
        assertEquals(0.toByte(), buf.readByte(), "SmallFlags(0) encodes to 0x00")
    }

    @Test
    fun encodePredicateTrueWritesFlagsThenInt() {
        val buf = encode(WithFlagPayload(flags = SmallFlags.WANT, payload = 0x01020304))
        assertEquals(5, buf.position(), "predicate-true encode writes 1 + 4 bytes")
        buf.resetForRead()
        assertEquals(1.toByte(), buf.readByte(), "SmallFlags(1) encodes to 0x01")
        assertEquals(0x01020304, buf.readInt(), "payload encoded big-endian")
    }

    @Test
    fun decodePredicateFalseLeavesPayloadNull() {
        val buf = BufferFactory.Default.allocate(1)
        buf.writeByte(0)
        buf.resetForRead()
        val decoded = WithFlagPayloadCodec.decode(buf, DecodeContext.Empty)
        assertEquals(SmallFlags.NONE, decoded.flags)
        assertNull(decoded.payload)
    }

    @Test
    fun decodePredicateTrueReadsValueClassThenPayload() {
        val buf = BufferFactory.Default.allocate(5)
        buf.writeByte(0x01.toByte())
        buf.writeInt(0x0A0B0C0D)
        buf.resetForRead()
        val decoded = WithFlagPayloadCodec.decode(buf, DecodeContext.Empty)
        assertEquals(SmallFlags.WANT, decoded.flags)
        assertEquals(0x0A0B0C0D, decoded.payload)
    }

    @Test
    fun wireSizeIsBackPatch() {
        // Locked Decision row 19: any @WhenTrue field collapses message wireSize to BackPatch.
        assertEquals(
            WireSize.BackPatch,
            WithFlagPayloadCodec.wireSize(
                WithFlagPayload(flags = SmallFlags.WANT, payload = 1),
                EncodeContext.Empty,
            ),
        )
        assertEquals(
            WireSize.BackPatch,
            WithFlagPayloadCodec.wireSize(WithFlagPayload(flags = SmallFlags.NONE), EncodeContext.Empty),
        )
    }

    @Test
    fun peekFrameSizeNeedsMoreDataWhenEmpty() {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, WithFlagPayloadCodec.peekFrameSize(stream))
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
            assertEquals(PeekResult.Complete(1), WithFlagPayloadCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeWalksNeedsMoreDataToCompleteWhenPredicateTrue() {
        val pool = BufferPool()
        val original = WithFlagPayload(flags = SmallFlags.WANT, payload = 0x12345678)
        val encoded = encode(original)
        encoded.resetForRead()
        val totalBytes = encoded.remaining()

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, WithFlagPayloadCodec.peekFrameSize(stream))

            for (i in 0 until totalBytes - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(encoded.readByte())
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    WithFlagPayloadCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), WithFlagPayloadCodec.peekFrameSize(stream))

            val decoded =
                stream.readBufferScoped(totalBytes) {
                    WithFlagPayloadCodec.decode(this, DecodeContext.Empty)
                }
            assertEquals(original, decoded)
            assertEquals(0, stream.available(), "stream should be drained")
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun roundTrip(
        original: WithFlagPayload,
        expectedTotalBytes: Int,
    ) {
        val buf = encode(original)
        assertEquals(expectedTotalBytes, buf.position(), "encode wrote $expectedTotalBytes bytes")
        buf.resetForRead()
        val decoded = WithFlagPayloadCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    private fun encode(value: WithFlagPayload) =
        BufferFactory.Default
            .allocate(256)
            .also { WithFlagPayloadCodec.encode(it, value, EncodeContext.Empty) }
}
