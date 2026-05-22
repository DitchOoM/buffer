package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Follow-up doctrine vector — exercises the value-class
 * field `wireOrder` propagation through the sequential peek walk.
 *
 * Without the fix, the peek-side byte assembly defaulted to
 * big-endian even when the value class declared
 * `@ProtocolMessage(wireOrder = Endianness.Little)`. For the
 * `LePacket(LeHeader.of(length=1, tag=5), "x")` vector below, the
 * pre-fix peek would assemble `01 05` as `0x0105` (BE) and read
 * `length` from the low byte of that = 5, then expect 5 body bytes
 * instead of 1 — total = 7, not 3. The drip-feed peek test would
 * fail at byte 3 (Complete(7) vs the actual 3-byte frame).
 *
 * Decode/encode rely on the buffer's runtime ByteOrder
 * (LITTLE_ENDIAN here) — same coupling pattern as Scalar fields
 * without `@WireOrder`.
 */
class LePacketCodecTest {
    @Test
    fun encodesHeaderLittleEndian() {
        // length=1, tag=5 → raw = 0x0501 → LE wire = 01 05
        val msg = LePacket(header = LeHeader.of(length = 1, tag = 5), payload = "x")
        val expected =
            byteArrayOf(
                0x01,
                0x05, // header LE: low byte (length=1) first, then tag=5
                'x'.code.toByte(),
            )
        encodeAndAssertBytes(msg, expected)
    }

    @Test
    fun decodesHeaderLittleEndian() {
        val wire = byteArrayOf(0x03, 0x42, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())
        val buf = leBufferOf(wire)
        val decoded = LePacketCodec.decode(buf, DecodeContext.Empty)
        assertEquals(3, decoded.header.length, "low byte = length")
        assertEquals(0x42, decoded.header.tag, "high byte = tag")
        assertEquals("abc", decoded.payload)
    }

    @Test
    fun roundTripsAcrossSeveralLengths() {
        for (n in listOf(0, 1, 5, 16, 255)) {
            val payload = "x".repeat(n)
            val original = LePacket(header = LeHeader.of(length = n, tag = 0xAB), payload = payload)
            val buf = encode(original)
            buf.resetForRead()
            assertEquals(original, LePacketCodec.decode(buf, DecodeContext.Empty), "round-trip n=$n")
        }
    }

    @Test
    fun peekFrameSizeAssemblesValueClassLittleEndian() {
        // The load-bearing test: forces the sequential walk's
        // value-class peek-stash to assemble `LeHeader.raw` from LE
        // bytes. Pre-fix this peek would mis-interpret length and
        // total = 7 instead of 3.
        val pool = BufferPool()
        val original = LePacket(header = LeHeader.of(length = 1, tag = 5), payload = "x")
        val encoded = encode(original)
        encoded.resetForRead()
        val totalBytes = encoded.remaining()
        assertEquals(3, totalBytes, "wire shape sanity: header(2) + body(1)")

        val stream = StreamProcessor.create(pool, ByteOrder.LITTLE_ENDIAN)
        try {
            // Drip in bytes one at a time. Need 2 (header) before peek can
            // resolve the length; then 1 more body byte to complete.
            for (i in 0 until totalBytes - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(encoded.readByte())
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    LePacketCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), LePacketCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun encodeAndAssertBytes(
        msg: LePacket,
        expected: ByteArray,
    ) {
        val buf = encode(msg)
        assertEquals(expected.size, buf.position(), "encoded byte count")
        buf.resetForRead()
        val actual = buf.readByteArray(expected.size)
        assertContentEquals(expected, actual, "encoded bytes match LE wire")
    }

    private fun leBufferOf(wire: ByteArray) =
        BufferFactory.Default
            .allocate(wire.size, ByteOrder.LITTLE_ENDIAN)
            .also { it.writeBytes(wire) }
            .also { it.resetForRead() }

    private fun encode(value: LePacket) =
        BufferFactory.Default
            .allocate(value.header.length + 8, ByteOrder.LITTLE_ENDIAN)
            .also { LePacketCodec.encode(it, value, EncodeContext.Empty) }
}
