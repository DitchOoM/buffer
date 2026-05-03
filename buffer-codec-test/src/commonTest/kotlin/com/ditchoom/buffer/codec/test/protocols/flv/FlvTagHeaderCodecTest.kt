package com.ditchoom.buffer.codec.test.protocols.flv

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
 * Stage B vector — `@WireBytes(3)` BE on a non-natural-width field.
 *
 * Wire vector: an FLV audio tag header for a 22-byte body with a
 * 100 ms timestamp:
 *   `tagType = 0x08` (audio), `dataSize = 22 (0x000016)`,
 *   `timestamp = 100 (0x000064)`, `timestampExtended = 0`,
 *   `streamId = 0`.
 *
 * Eleven bytes BE: `08 00 00 16 00 00 64 00 00 00 00`.
 */
class FlvTagHeaderCodecTest {
    private val sampleHeader =
        FlvTagHeader(
            tagType = 0x08u,
            dataSize = 0x000016u,
            timestamp = 0x000064u,
            timestampExtended = 0x00u,
            streamId = 0x000000u,
        )
    private val sampleWire =
        byteArrayOf(
            0x08,
            0x00, 0x00, 0x16,
            0x00, 0x00, 0x64,
            0x00,
            0x00, 0x00, 0x00,
        )
    private val frameSize = 11

    @Test
    fun roundTripsByteExact() {
        val buf = BufferFactory.Default.allocate(frameSize)
        FlvTagHeaderCodec.encode(buf, sampleHeader, EncodeContext.Empty)
        assertEquals(frameSize, buf.position(), "encode wrote exactly $frameSize bytes")

        buf.resetForRead()
        val wire = ByteArray(frameSize) { buf.readByte() }
        for (i in sampleWire.indices) {
            assertEquals(sampleWire[i].toInt() and 0xFF, wire[i].toInt() and 0xFF, "byte $i")
        }

        val decodeBuf = BufferFactory.Default.wrap(wire)
        val decoded = FlvTagHeaderCodec.decode(decodeBuf, DecodeContext.Empty)
        assertEquals(sampleHeader, decoded)
    }

    @Test
    fun roundTripsAt24BitMaxBoundary() {
        // Largest values representable in @WireBytes(3) UInt fields: 0xFFFFFF.
        val maxed =
            FlvTagHeader(
                tagType = 0xFFu,
                dataSize = 0xFFFFFFu,
                timestamp = 0xFFFFFFu,
                timestampExtended = 0xFFu,
                streamId = 0xFFFFFFu,
            )
        val buf = BufferFactory.Default.allocate(frameSize)
        FlvTagHeaderCodec.encode(buf, maxed, EncodeContext.Empty)
        buf.resetForRead()
        val decoded = FlvTagHeaderCodec.decode(buf, DecodeContext.Empty)
        assertEquals(maxed, decoded)
    }

    @Test
    fun wireSizeIsAlwaysExact11() {
        assertEquals(WireSize.Exact(frameSize), FlvTagHeaderCodec.wireSize(sampleHeader, EncodeContext.Empty))
    }

    @Test
    fun peekFrameSizeWalksNeedsMoreDataToComplete() {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, FlvTagHeaderCodec.peekFrameSize(stream))
            for (i in 0 until frameSize - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(sampleWire[i])
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    FlvTagHeaderCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(sampleWire[frameSize - 1])
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(frameSize), FlvTagHeaderCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }
}
