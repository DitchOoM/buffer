package com.ditchoom.buffer.codec.test.protocols.tcp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Slice — UByte-returning `@DispatchValue` round
 * trip vector.
 *
 * Pins the dispatcher's `.toInt()` coercion on a UByte `@DispatchValue`
 * and the wire-byte symmetry across the five named TCP segment kinds
 * (SYN / SYN+ACK / ACK / FIN+ACK / RST). Also verifies the
 * dispatcher's `else -> throw` branch fires for unmatched flag
 * combinations (any byte not in the variant set).
 */
class TcpSegmentByFlagsCodecTest {
    @Test
    fun synRoundTripsThroughDispatcher() = roundTrip(TcpSegmentByFlags.Syn(), 0x02)

    @Test
    fun synAckRoundTripsThroughDispatcher() = roundTrip(TcpSegmentByFlags.SynAck(), 0x12)

    @Test
    fun ackRoundTripsThroughDispatcher() = roundTrip(TcpSegmentByFlags.Ack(), 0x10)

    @Test
    fun finAckRoundTripsThroughDispatcher() = roundTrip(TcpSegmentByFlags.FinAck(), 0x11)

    @Test
    fun rstRoundTripsThroughDispatcher() = roundTrip(TcpSegmentByFlags.Rst(), 0x04)

    @Test
    fun decodeRoutesByExactFlagsByte() {
        val buf = BufferFactory.Default.allocate(1, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0x12.toByte())
        buf.resetForRead()
        val decoded = TcpSegmentByFlagsCodec.decode(buf, DecodeContext.Empty)
        assertTrue(decoded is TcpSegmentByFlags.SynAck)
        assertEquals(0x12.toUByte(), decoded.flags.raw)
    }

    @Test
    fun decodeUnmatchedFlagsByteThrows() {
        // 0x00 (no flags) is not a named variant — dispatcher's else
        // branch fires with a DecodeException.
        val buf = BufferFactory.Default.allocate(1, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0x00.toByte())
        buf.resetForRead()
        val ex =
            assertFailsWith<DecodeException> {
                TcpSegmentByFlagsCodec.decode(buf, DecodeContext.Empty)
            }
        assertEquals("TcpSegmentByFlags.discriminator", ex.fieldPath)
    }

    @Test
    fun decodeUnmatchedHighFlagsByteThrows() {
        // 0xFF is in UByte range and would silently pass the validator's
        // 0..255 check, but it isn't a named variant — dispatcher else
        // branch must fire.
        val buf = BufferFactory.Default.allocate(1, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0xFF.toByte())
        buf.resetForRead()
        assertFailsWith<DecodeException> {
            TcpSegmentByFlagsCodec.decode(buf, DecodeContext.Empty)
        }
    }

    @Test
    fun wireSizeIsOneByteForEveryVariant() {
        for (v in listOf(
            TcpSegmentByFlags.Syn(),
            TcpSegmentByFlags.SynAck(),
            TcpSegmentByFlags.Ack(),
            TcpSegmentByFlags.FinAck(),
            TcpSegmentByFlags.Rst(),
        )) {
            assertEquals(WireSize.Exact(1), TcpSegmentByFlagsCodec.wireSize(v, EncodeContext.Empty))
        }
    }

    private fun roundTrip(
        original: TcpSegmentByFlags,
        expectedByte: Int,
    ) {
        val buf = BufferFactory.Default.allocate(1, ByteOrder.BIG_ENDIAN)
        TcpSegmentByFlagsCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(1, buf.remaining())
        assertEquals(expectedByte.toByte(), buf.readByte())
        buf.resetForRead()
        assertEquals(original, TcpSegmentByFlagsCodec.decode(buf, DecodeContext.Empty))
    }
}
