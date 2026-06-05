package com.ditchoom.buffer.codec.test.protocols.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.VarLenPeek
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * RFC 9000 §16 conformance for the test-support [QuicVarintCodec], and the
 * cross-method invariants the [com.ditchoom.buffer.codec.VariableLengthCodec]
 * contract promises: `encode` writes exactly `encodedLength` bytes, `decode`
 * recovers the value, and `peekValue` reports the same value + byte count
 * without consuming the stream.
 */
class QuicVarintCodecTest {
    // The four worked vectors from RFC 9000 Appendix A.1, plus length-class
    // boundaries. Each pair is (value, canonical minimal wire bytes).
    private val vectors: List<Pair<ULong, List<Int>>> =
        listOf(
            0uL to listOf(0x00),
            37uL to listOf(0x25), // RFC A.1
            63uL to listOf(0x3F), // 1-byte max
            64uL to listOf(0x40, 0x40), // first 2-byte
            15293uL to listOf(0x7B, 0xBD), // RFC A.1
            16383uL to listOf(0x7F, 0xFF), // 2-byte max
            16384uL to listOf(0x80, 0x00, 0x40, 0x00), // first 4-byte
            494878333uL to listOf(0x9D, 0x7F, 0x3E, 0x7D), // RFC A.1
            0x3FFF_FFFFuL to listOf(0xBF, 0xFF, 0xFF, 0xFF), // 4-byte max
            0x4000_0000uL to listOf(0xC0, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00), // first 8-byte (class 11)
            151288809941952652uL to listOf(0xC2, 0x19, 0x7C, 0x5E, 0xFF, 0x14, 0xE8, 0x8C), // RFC A.1
            0x3FFF_FFFF_FFFF_FFFFuL to listOf(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF), // 8-byte max
        )

    @Test
    fun encodeMatchesCanonicalBytes() {
        for ((value, bytes) in vectors) {
            assertEquals(bytes.size, QuicVarintCodec.encodedLength(value), "encodedLength($value)")
            val buf = BufferFactory.Default.allocate(8)
            QuicVarintCodec.encode(buf, value, EncodeContext.Empty)
            assertEquals(bytes.size, buf.position(), "encoded byte count for $value")
            buf.resetForRead()
            for ((i, expected) in bytes.withIndex()) {
                assertEquals(expected, buf.readUByte().toInt(), "byte $i of $value")
            }
        }
    }

    @Test
    fun decodeRecoversValue() {
        for ((value, bytes) in vectors) {
            val buf = BufferFactory.Default.allocate(bytes.size, ByteOrder.BIG_ENDIAN)
            bytes.forEach { buf.writeByte(it.toByte()) }
            buf.resetForRead()
            assertEquals(value, QuicVarintCodec.decode(buf, DecodeContext.Empty), "decode of $value")
        }
    }

    @Test
    fun wireSizeIsAlwaysExactAndAgreesWithEncodedLength() {
        for ((value, bytes) in vectors) {
            assertEquals(
                WireSize.Exact(bytes.size),
                QuicVarintCodec.wireSize(value, EncodeContext.Empty),
                "wireSize($value)",
            )
        }
    }

    @Test
    fun peekValueReportsValueAndLengthWithoutConsuming() {
        for ((value, bytes) in vectors) {
            withStream(bytes) { stream ->
                val before = stream.available()
                assertEquals(
                    VarLenPeek.Decoded(value, bytes.size),
                    QuicVarintCodec.peekValue(stream),
                    "peekValue($value)",
                )
                assertEquals(before, stream.available(), "peekValue must not consume")
            }
        }
    }

    @Test
    fun decodeAcceptsNonMinimalEncoding() {
        // RFC 9000 §16: 0x40 0x25 is a non-minimal 2-byte encoding of 37.
        val buf = BufferFactory.Default.allocate(2, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0x40)
        buf.writeByte(0x25)
        buf.resetForRead()
        assertEquals(37uL, QuicVarintCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun peekValueNeedsMoreDataOnShortPrefix() {
        // Empty stream — can't even read byte 0.
        withStream(emptyList()) { stream ->
            assertEquals(VarLenPeek.NeedsMoreData, QuicVarintCodec.peekValue(stream))
        }
        // First byte announces a 4-byte varint (class 10) but only 1 byte present.
        withStream(listOf(0x80)) { stream ->
            assertEquals(VarLenPeek.NeedsMoreData, QuicVarintCodec.peekValue(stream))
        }
        // 3 of the 4 bytes present — still short.
        withStream(listOf(0x80, 0x00, 0x00)) { stream ->
            assertEquals(VarLenPeek.NeedsMoreData, QuicVarintCodec.peekValue(stream))
        }
    }

    @Test
    fun encodedLengthRejectsOutOfRange() {
        assertFailsWith<IllegalArgumentException> {
            // 2^62 — one past the varint maximum.
            QuicVarintCodec.encodedLength(0x4000_0000_0000_0000uL)
        }
    }

    private fun withStream(
        bytes: List<Int>,
        block: (StreamProcessor) -> Unit,
    ) {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            if (bytes.isNotEmpty()) {
                val buf = BufferFactory.Default.allocate(bytes.size)
                bytes.forEach { buf.writeByte(it.toByte()) }
                buf.resetForRead()
                stream.append(buf)
            }
            block(stream)
        } finally {
            stream.release()
            pool.clear()
        }
    }
}
