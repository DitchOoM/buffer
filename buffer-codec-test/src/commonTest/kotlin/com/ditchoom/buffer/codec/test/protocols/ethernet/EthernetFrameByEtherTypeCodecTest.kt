package com.ditchoom.buffer.codec.test.protocols.ethernet

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Slice — UShort-returning `@DispatchValue`
 * round-trip vector.
 *
 * Pins the dispatcher's `.toInt()` coercion on a UShort `@DispatchValue`
 * (where the discriminator's *inner* kind is also UShort, making the
 * wire shape 2 bytes big-endian per IEEE 802.3) and the wire-byte
 * symmetry across the four named EtherType variants. Also exercises
 * the 2-byte peek path: peek-side reconstruction reads 2 bytes,
 * assembles them big-endian, narrows back to UShort, then runs the
 * `.toInt()` coercion.
 */
class EthernetFrameByEtherTypeCodecTest {
    @Test
    fun ipv4RoundTripsThroughDispatcher() = roundTrip(EthernetFrameByEtherType.Ipv4(), byteArrayOf(0x08, 0x00))

    @Test
    fun arpRoundTripsThroughDispatcher() = roundTrip(EthernetFrameByEtherType.Arp(), byteArrayOf(0x08, 0x06))

    @Test
    fun vlanTagRoundTripsThroughDispatcher() {
        roundTrip(EthernetFrameByEtherType.VlanTag(), byteArrayOf(0x81.toByte(), 0x00))
    }

    @Test
    fun ipv6RoundTripsThroughDispatcher() {
        roundTrip(EthernetFrameByEtherType.Ipv6(), byteArrayOf(0x86.toByte(), 0xDD.toByte()))
    }

    @Test
    fun decodeRoutesByExactEtherTypeBytes() {
        val buf = BufferFactory.Default.allocate(2, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0x86.toByte())
        buf.writeByte(0xDD.toByte())
        buf.resetForRead()
        val decoded = EthernetFrameByEtherTypeCodec.decode(buf, DecodeContext.Empty)
        assertTrue(decoded is EthernetFrameByEtherType.Ipv6)
        assertEquals(0x86DDu.toUShort(), decoded.etherType.raw)
    }

    @Test
    fun decodeUnmatchedEtherTypeThrows() {
        // 0x0000 is in UShort range but isn't a named variant — dispatcher
        // else branch fires with a DecodeException.
        val buf = BufferFactory.Default.allocate(2, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0x00)
        buf.writeByte(0x00)
        buf.resetForRead()
        val ex =
            assertFailsWith<DecodeException> {
                EthernetFrameByEtherTypeCodec.decode(buf, DecodeContext.Empty)
            }
        assertEquals("EthernetFrameByEtherType.discriminator", ex.fieldPath)
    }

    @Test
    fun wireSizeIsTwoBytesForEveryVariant() {
        for (v in listOf(
            EthernetFrameByEtherType.Ipv4(),
            EthernetFrameByEtherType.Arp(),
            EthernetFrameByEtherType.VlanTag(),
            EthernetFrameByEtherType.Ipv6(),
        )) {
            assertEquals(WireSize.Exact(2), EthernetFrameByEtherTypeCodec.wireSize(v, EncodeContext.Empty))
        }
    }

    private fun roundTrip(
        original: EthernetFrameByEtherType,
        expectedBytes: ByteArray,
    ) {
        val buf = BufferFactory.Default.allocate(2, ByteOrder.BIG_ENDIAN)
        EthernetFrameByEtherTypeCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(2, buf.remaining())
        val actual = buf.readByteArray(2)
        assertContentEquals(expectedBytes, actual)
        buf.resetForRead()
        assertEquals(original, EthernetFrameByEtherTypeCodec.decode(buf, DecodeContext.Empty))
    }
}
