package com.ditchoom.buffer.codec.test.protocols.dns

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.test.PeekOutcome
import com.ditchoom.buffer.codec.test.peekOutcome
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Vector — pure-BE multi-scalar.
 *
 * Wire vector: a real DNS query header for `example.com IN A`:
 *   `id = 0x4D2A`, `flags = 0x0100` (standard query, RD set),
 *   `qdCount = 1`, `anCount = 0`, `nsCount = 0`, `arCount = 0`.
 *
 * Twelve bytes BE: `4D 2A 01 00 00 01 00 00 00 00 00 00`.
 */
class DnsHeaderCodecTest {
    private val sampleHeader =
        DnsHeader(
            id = 0x4D2Au,
            flags = 0x0100u,
            qdCount = 1u,
            anCount = 0u,
            nsCount = 0u,
            arCount = 0u,
        )
    private val sampleWire =
        byteArrayOf(
            0x4D,
            0x2A,
            0x01,
            0x00,
            0x00,
            0x01,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
        )

    @Test
    fun roundTripsByteExact() {
        val buf = BufferFactory.Default.allocate(12)
        DnsHeaderCodec.encode(buf, sampleHeader, EncodeContext.Empty)
        assertEquals(12, buf.position(), "encode wrote exactly 12 bytes")

        buf.resetForRead()
        val wire = ByteArray(12) { buf.readByte() }
        for (i in sampleWire.indices) {
            assertEquals(sampleWire[i].toInt() and 0xFF, wire[i].toInt() and 0xFF, "byte $i")
        }

        val decodeBuf = BufferFactory.Default.wrap(wire)
        val decoded = DnsHeaderCodec.decode(decodeBuf, DecodeContext.Empty)
        assertEquals(sampleHeader, decoded)
    }

    @Test
    fun wireSizeIsAlwaysExact12() {
        assertEquals(WireByteCount.exact(12), DnsHeaderCodec.wireBytes(sampleHeader, EncodeContext.Empty))
        // Vary every field; size stays 12 regardless of values.
        val maxed =
            DnsHeader(
                id = UShort.MAX_VALUE,
                flags = UShort.MAX_VALUE,
                qdCount = UShort.MAX_VALUE,
                anCount = UShort.MAX_VALUE,
                nsCount = UShort.MAX_VALUE,
                arCount = UShort.MAX_VALUE,
            )
        assertEquals(WireByteCount.exact(12), DnsHeaderCodec.wireBytes(maxed, EncodeContext.Empty))
    }

    @Test
    fun peekFrameSizeWalksNeedsMoreDataToComplete() {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekOutcome.NeedsMoreData, DnsHeaderCodec.peekOutcome(stream))
            for (i in 0 until 11) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(sampleWire[i])
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekOutcome.NeedsMoreData,
                    DnsHeaderCodec.peekOutcome(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(sampleWire[11])
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekOutcome.Complete(12), DnsHeaderCodec.peekOutcome(stream))

            val decoded =
                stream.readBufferScoped(12) {
                    DnsHeaderCodec.decode(this, DecodeContext.Empty)
                }
            assertEquals(sampleHeader, decoded)
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeRespectsBaseOffset() {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            // Push two back-to-back DNS headers (24 bytes) so baseOffset = 12 lands on the second.
            val twoHeaders = BufferFactory.Default.allocate(24)
            DnsHeaderCodec.encode(twoHeaders, sampleHeader, EncodeContext.Empty)
            DnsHeaderCodec.encode(twoHeaders, sampleHeader, EncodeContext.Empty)
            twoHeaders.resetForRead()
            stream.append(twoHeaders)

            assertEquals(PeekOutcome.Complete(12), DnsHeaderCodec.peekOutcome(stream, baseOffset = 0))
            assertEquals(PeekOutcome.Complete(12), DnsHeaderCodec.peekOutcome(stream, baseOffset = 12))
            assertEquals(PeekOutcome.NeedsMoreData, DnsHeaderCodec.peekOutcome(stream, baseOffset = 13))
        } finally {
            stream.release()
            pool.clear()
        }
    }
}
