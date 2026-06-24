package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Round-trip + framing + robustness for the shipped unsigned LEB128 codec. The (value, byteWidth)
 * table covers every length class up to the UInt ceiling, so encode size, decode value, `wireSize`
 * (runtime-Exact), and `peekValue` framing are all pinned against the same vectors.
 */
class UnsignedVarIntCodecTest {
    private val vectors =
        listOf(
            0u to 1,
            127u to 1,
            128u to 2,
            16383u to 2,
            16384u to 3,
            2097151u to 3,
            2097152u to 4,
            268435455u to 4,
            268435456u to 5,
            UInt.MAX_VALUE to 5,
        )

    @Test
    fun roundTripsAndSizesEveryLengthClass() {
        for ((value, width) in vectors) {
            assertEquals(width, UnsignedVarIntCodec.encodedLength(value), "encodedLength($value)")
            val buf = BufferFactory.Default.allocate(8)
            UnsignedVarIntCodec.encode(buf, value, EncodeContext.Empty)
            assertEquals(width, buf.position(), "encoded byte count for $value")
            assertEquals(
                WireSize.Exact(width),
                UnsignedVarIntCodec.wireSize(value, EncodeContext.Empty),
                "wireSize($value)",
            )
            buf.resetForRead()
            assertEquals(value, UnsignedVarIntCodec.decode(buf, DecodeContext.Empty), "round-trip $value")
        }
    }

    @Test
    fun peekNeedsMoreDataUntilTerminatorThenReportsWidth() {
        for ((value, width) in vectors) {
            val src = BufferFactory.Default.allocate(8)
            UnsignedVarIntCodec.encode(src, value, EncodeContext.Empty)
            src.resetForRead()
            val pool = BufferPool()
            val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
            try {
                for (i in 0 until width - 1) {
                    appendByte(stream, src.readByte())
                    assertEquals(
                        VarLenPeek.NeedsMoreData,
                        UnsignedVarIntCodec.peekValue(stream),
                        "value $value after ${i + 1} bytes",
                    )
                }
                appendByte(stream, src.readByte())
                assertEquals(
                    VarLenPeek.Decoded(value, width),
                    UnsignedVarIntCodec.peekValue(stream),
                    "value $value complete",
                )
            } finally {
                stream.release()
                pool.clear()
            }
        }
    }

    @Test
    fun decodeRejectsOverlongSequence() {
        // Six 0x80 continuation bytes never terminate and exceed the UInt range → DecodeException,
        // not an infinite loop or silent overflow.
        val buf = BufferFactory.Default.allocate(8)
        repeat(6) { buf.writeByte(0x80.toByte()) }
        buf.resetForRead()
        assertFailsWith<DecodeException> { UnsignedVarIntCodec.decode(buf, DecodeContext.Empty) }
    }

    @Test
    fun decodeRejectsOverflowingFifthByte() {
        // Four continuation bytes then a terminating 5th byte whose value bits exceed 2^32
        // (0x10 → bit 32). Must throw rather than silently truncate to a 32-bit result.
        val buf = BufferFactory.Default.allocate(8)
        repeat(4) { buf.writeByte(0x80.toByte()) }
        buf.writeByte(0x10.toByte())
        buf.resetForRead()
        assertFailsWith<DecodeException> { UnsignedVarIntCodec.decode(buf, DecodeContext.Empty) }
    }

    @Test
    fun peekRejectsOverflowingFifthByte() {
        val src = BufferFactory.Default.allocate(8)
        repeat(4) { src.writeByte(0x80.toByte()) }
        src.writeByte(0x10.toByte())
        src.resetForRead()
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            repeat(5) { appendByte(stream, src.readByte()) }
            assertFailsWith<DecodeException> { UnsignedVarIntCodec.peekValue(stream) }
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
