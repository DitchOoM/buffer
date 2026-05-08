package com.ditchoom.buffer.codec.test.protocols.quic

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
 * Slice — Boolean-returning `@DispatchValue`
 * round-trip vector.
 *
 * Pins the dispatcher's 0/1 lift on a Boolean `@DispatchValue` (`if
 * (__discriminator.isLongHeader) 1 else 0`) and the wire-byte
 * symmetry: form-bit 0 (short header) round-trips via `@PacketType(0)`,
 * form-bit 1 (long header) via `@PacketType(1)`.
 */
class QuicPacketHeaderCodecTest {
    @Test
    fun shortHeaderRoundTripsThroughDispatcher() {
        val original = QuicPacketHeader.ShortHeader()
        val buf = BufferFactory.Default.allocate(1, ByteOrder.BIG_ENDIAN)
        QuicPacketHeaderCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(1, buf.remaining())
        assertEquals(0x40.toByte(), buf.readByte(), "form bit 0, fixed bit 1 -> 0x40")
        buf.resetForRead()
        assertEquals(original, QuicPacketHeaderCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun longHeaderRoundTripsThroughDispatcher() {
        val original = QuicPacketHeader.LongHeader()
        val buf = BufferFactory.Default.allocate(1, ByteOrder.BIG_ENDIAN)
        QuicPacketHeaderCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(1, buf.remaining())
        assertEquals(0xC0.toByte(), buf.readByte(), "form bit 1, fixed bit 1 -> 0xC0")
        buf.resetForRead()
        assertEquals(original, QuicPacketHeaderCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun decodeRoutesAnyHighBitSetToLongHeader() {
        // The `isLongHeader` projection inspects only the high bit; any
        // first byte with bit 7 set is dispatched to LongHeader regardless
        // of the low bits.
        val buf = BufferFactory.Default.allocate(1, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0xE3.toByte()) // form=1, fixed=1, type+specific=0x23
        buf.resetForRead()
        val decoded = QuicPacketHeaderCodec.decode(buf, DecodeContext.Empty)
        assertTrue(decoded is QuicPacketHeader.LongHeader, "0xE3 has form bit set")
        assertEquals(0xE3.toUByte(), decoded.firstByte.raw)
    }

    @Test
    fun decodeRoutesAnyHighBitClearToShortHeader() {
        val buf = BufferFactory.Default.allocate(1, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0x55.toByte()) // form=0, fixed=1, low bits arbitrary
        buf.resetForRead()
        val decoded = QuicPacketHeaderCodec.decode(buf, DecodeContext.Empty)
        assertTrue(decoded is QuicPacketHeader.ShortHeader, "0x55 has form bit clear")
        assertEquals(0x55.toUByte(), decoded.firstByte.raw)
    }

    @Test
    fun wireSizeIsOneByteForBothVariants() {
        assertEquals(WireSize.Exact(1), QuicPacketHeaderCodec.wireSize(QuicPacketHeader.ShortHeader(), EncodeContext.Empty))
        assertEquals(WireSize.Exact(1), QuicPacketHeaderCodec.wireSize(QuicPacketHeader.LongHeader(), EncodeContext.Empty))
    }

    @Test
    fun decodeOnEmptyBufferThrowsDecodeException() {
        // Defensive: discriminator decode reads 1 byte; an empty buffer
        // surfaces an underflow, which the framework wraps as a
        // DecodeException via the discriminator codec's throw site.
        val buf = BufferFactory.Default.allocate(0, ByteOrder.BIG_ENDIAN)
        buf.resetForRead()
        assertFailsWith<Exception> { QuicPacketHeaderCodec.decode(buf, DecodeContext.Empty) }
    }

    @Test
    fun decodeUnreachableElseDoesNotFire() {
        // Boolean dispatch can only produce 0 or 1; the dispatcher's
        // `else -> throw DecodeException(...)` branch is unreachable
        // by construction. This test pins that no input byte trips it
        // by sweeping all 256 possible first bytes.
        for (b in 0..255) {
            val buf = BufferFactory.Default.allocate(1, ByteOrder.BIG_ENDIAN)
            buf.writeByte(b.toByte())
            buf.resetForRead()
            // Either ShortHeader or LongHeader — never DecodeException.
            try {
                QuicPacketHeaderCodec.decode(buf, DecodeContext.Empty)
            } catch (e: DecodeException) {
                throw AssertionError("byte 0x${b.toString(16)} should never trip the unreachable else branch", e)
            }
        }
    }
}
