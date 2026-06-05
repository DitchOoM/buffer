package com.ditchoom.buffer.codec.test.protocols.varintfield

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Stage 2 — the generic, encoding-agnostic variable-width **field** path.
 *
 * `VarintLengthFrame` carries a `@UseCodec(QuicVarintCodec)` `ULong` (whose
 * codec implements `VariableLengthCodec`) followed by a fixed `tag` byte.
 * Decode/encode delegating to the codec already worked; what this pins is that
 * `peekFrameSize` now frames the message — `width(value) + 1` — instead of
 * returning `NoFraming`. The frame width changes with the value (1/2/4/8-byte
 * varint), so the same message type peeks to different totals.
 */
class VarintLengthFrameCodecTest {
    @Test
    fun roundTripsAcrossVarintWidths() {
        // (value, varint byte width) — one per QUIC length class.
        for ((value, varintWidth) in listOf(
            0uL to 1,
            63uL to 1,
            64uL to 2,
            16383uL to 2,
            16384uL to 4,
            0x3FFF_FFFFuL to 4,
            0x4000_0000uL to 8,
            0x3FFF_FFFF_FFFF_FFFFuL to 8,
        )) {
            val original = VarintLengthFrame(value = value, tag = 0xABu)
            val buf = BufferFactory.Default.allocate(16)
            VarintLengthFrameCodec.encode(buf, original, EncodeContext.Empty)
            assertEquals(varintWidth + 1, buf.position(), "encoded size for value $value")
            buf.resetForRead()
            assertEquals(original, VarintLengthFrameCodec.decode(buf, DecodeContext.Empty), "round-trip $value")
        }
    }

    @Test
    fun peekFramesByObservedVarintWidthPlusSuffix() {
        // 64 → 2-byte varint + 1 tag = 3 bytes total.
        assertPeekComplete(VarintLengthFrame(value = 64uL, tag = 0x01u), expectedTotal = 3)
        // 5 → 1-byte varint + 1 tag = 2 bytes.
        assertPeekComplete(VarintLengthFrame(value = 5uL, tag = 0x02u), expectedTotal = 2)
        // 0x4000_0000 → 8-byte varint + 1 tag = 9 bytes.
        assertPeekComplete(VarintLengthFrame(value = 0x4000_0000uL, tag = 0x03u), expectedTotal = 9)
    }

    private fun assertPeekComplete(
        original: VarintLengthFrame,
        expectedTotal: Int,
    ) {
        val pool = BufferPool()
        val source = BufferFactory.Default.allocate(16)
        VarintLengthFrameCodec.encode(source, original, EncodeContext.Empty)
        source.resetForRead()
        val total = source.remaining()
        assertEquals(expectedTotal, total, "encoded total for $original")
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            for (i in 0 until total - 1) {
                appendByte(stream, source.readByte())
                assertEquals(
                    PeekResult.NeedsMoreData,
                    VarintLengthFrameCodec.peekFrameSize(stream),
                    "after ${i + 1}/$total bytes",
                )
            }
            appendByte(stream, source.readByte())
            assertEquals(
                PeekResult.Complete(total),
                VarintLengthFrameCodec.peekFrameSize(stream),
                "fully buffered",
            )
        } finally {
            stream.release()
            pool.clear()
        }
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
