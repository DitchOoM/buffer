package com.ditchoom.buffer.codec.test.protocols.mysql

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
import kotlin.test.assertFailsWith

/**
 * Stage B vector — value-class-of-raw shape (bit-packed-header
 * ergonomic alternative to per-field `@WireBytes`).
 *
 * Wire vector: a small MySQL packet header with 5-byte payload and
 * sequence id 0 — `payloadLength = 5`, `sequenceId = 0`.
 *
 * Four bytes LE: `05 00 00 00`. Decoded as raw `UInt` LE =
 * `0x00000005`; getters expose `payloadLength = 5`, `sequenceId = 0`.
 */
class MySqlPacketHeaderCodecTest {
    private val sampleHeader = MySqlPacketHeader.of(payloadLength = 5u, sequenceId = 0u)
    private val sampleWire = byteArrayOf(0x05, 0x00, 0x00, 0x00)

    @Test
    fun roundTripsByteExact() {
        val buf = BufferFactory.Default.allocate(4)
        MySqlPacketHeaderCodec.encode(buf, sampleHeader, EncodeContext.Empty)
        assertEquals(4, buf.position(), "encode wrote exactly 4 bytes")

        buf.resetForRead()
        val wire = ByteArray(4) { buf.readByte() }
        for (i in sampleWire.indices) {
            assertEquals(sampleWire[i].toInt() and 0xFF, wire[i].toInt() and 0xFF, "byte $i")
        }

        val decodeBuf = BufferFactory.Default.wrap(wire)
        val decoded = MySqlPacketHeaderCodec.decode(decodeBuf, DecodeContext.Empty)
        assertEquals(sampleHeader, decoded)
        assertEquals(5u, decoded.payloadLength)
        assertEquals(0.toUByte(), decoded.sequenceId)
    }

    @Test
    fun gettersDecomposeRawCorrectly() {
        // Encode payloadLength = 0x123456, sequenceId = 0x42 → wire = 56 34 12 42 (LE).
        val composed = MySqlPacketHeader.of(payloadLength = 0x123456u, sequenceId = 0x42u)
        val buf = BufferFactory.Default.allocate(4)
        MySqlPacketHeaderCodec.encode(buf, composed, EncodeContext.Empty)
        buf.resetForRead()
        val wire = ByteArray(4) { buf.readByte() }
        assertEquals(0x56, wire[0].toInt() and 0xFF, "byte 0 = LE low byte of payloadLength")
        assertEquals(0x34, wire[1].toInt() and 0xFF, "byte 1")
        assertEquals(0x12, wire[2].toInt() and 0xFF, "byte 2 = LE high byte of payloadLength")
        assertEquals(0x42, wire[3].toInt() and 0xFF, "byte 3 = sequenceId")

        val decodeBuf = BufferFactory.Default.wrap(wire)
        val decoded = MySqlPacketHeaderCodec.decode(decodeBuf, DecodeContext.Empty)
        assertEquals(0x123456u, decoded.payloadLength)
        assertEquals(0x42.toUByte(), decoded.sequenceId)
    }

    @Test
    fun ofEnforcesPayloadLengthBound() {
        assertFailsWith<IllegalArgumentException> {
            MySqlPacketHeader.of(payloadLength = 0x01000000u, sequenceId = 0u)
        }
    }

    @Test
    fun peekFrameSizeReturnsComplete4() {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(WireSize.Exact(4), MySqlPacketHeaderCodec.wireSize(sampleHeader, EncodeContext.Empty))
            assertEquals(PeekResult.NeedsMoreData, MySqlPacketHeaderCodec.peekFrameSize(stream))
            for (i in 0 until 3) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(sampleWire[i])
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    MySqlPacketHeaderCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(sampleWire[3])
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(4), MySqlPacketHeaderCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }
}
