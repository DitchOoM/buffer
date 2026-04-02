package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.WireOrderCustomWidthMessage
import com.ditchoom.buffer.codec.test.protocols.WireOrderCustomWidthMessageCodec
import com.ditchoom.buffer.codec.test.protocols.WireOrderMessage
import com.ditchoom.buffer.codec.test.protocols.WireOrderMessageCodec
import com.ditchoom.buffer.codec.testRoundTrip
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for @WireOrder annotation — per-field byte order control.
 * Verifies both round-trip correctness and exact wire byte layout.
 */
class WireOrderRoundTripTest {
    // ========== Basic round-trips ==========

    @Test
    fun mixedEndianRoundTrip() {
        val original =
            WireOrderMessage(
                beByte = 0xAAu,
                leShort = 0x1234u,
                beInt = 0x56789ABC.toInt(),
                leInt = 0xDEADBEEFu,
                leLong = 0x0102030405060708L,
            )
        val decoded = WireOrderMessageCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun wireOrderWithCustomWidthRoundTrip() {
        val original =
            WireOrderCustomWidthMessage(
                tag = 0x42u,
                leLength = 0x123456u,
                leFlags = 0x7890,
            )
        val decoded = WireOrderCustomWidthMessageCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    // ========== Exact wire byte verification ==========

    @Test
    fun mixedEndianExactWireBytes() {
        val original =
            WireOrderMessage(
                beByte = 0xFFu,
                leShort = 0x0102u, // LE on wire: 02 01
                beInt = 0x03040506, // BE on wire: 03 04 05 06
                leInt = 0x0708090Au, // LE on wire: 0A 09 08 07
                leLong = 0x0B0C0D0E0F101112L, // LE on wire: 12 11 10 0F 0E 0D 0C 0B
            )
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        WireOrderMessageCodec.encode(buffer, original)

        // beByte: single byte, no swap
        assertEquals(0xFF.toByte(), buffer[0])

        // leShort: 0x0102 → LE bytes: 02 01
        assertEquals(0x02.toByte(), buffer[1])
        assertEquals(0x01.toByte(), buffer[2])

        // beInt: 0x03040506 → BE bytes: 03 04 05 06
        assertEquals(0x03.toByte(), buffer[3])
        assertEquals(0x04.toByte(), buffer[4])
        assertEquals(0x05.toByte(), buffer[5])
        assertEquals(0x06.toByte(), buffer[6])

        // leInt: 0x0708090A → LE bytes: 0A 09 08 07
        assertEquals(0x0A.toByte(), buffer[7])
        assertEquals(0x09.toByte(), buffer[8])
        assertEquals(0x08.toByte(), buffer[9])
        assertEquals(0x07.toByte(), buffer[10])

        // leLong: 0x0B0C0D0E0F101112 → LE bytes: 12 11 10 0F 0E 0D 0C 0B
        assertEquals(0x12.toByte(), buffer[11])
        assertEquals(0x11.toByte(), buffer[12])
        assertEquals(0x10.toByte(), buffer[13])
        assertEquals(0x0F.toByte(), buffer[14])
        assertEquals(0x0E.toByte(), buffer[15])
        assertEquals(0x0D.toByte(), buffer[16])
        assertEquals(0x0C.toByte(), buffer[17])
        assertEquals(0x0B.toByte(), buffer[18])

        assertEquals(19, buffer.position())
    }

    @Test
    fun wireOrderCustomWidthExactBytes() {
        val original =
            WireOrderCustomWidthMessage(
                tag = 0x42u,
                leLength = 0x010203u, // 3-byte LE: 03 02 01
                leFlags = 0x0405, // 2-byte LE: 05 04
            )
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        WireOrderCustomWidthMessageCodec.encode(buffer, original)

        // tag: single byte
        assertEquals(0x42.toByte(), buffer[0])

        // leLength: 0x010203 as 3 LE bytes: 03 02 01
        assertEquals(0x03.toByte(), buffer[1])
        assertEquals(0x02.toByte(), buffer[2])
        assertEquals(0x01.toByte(), buffer[3])

        // leFlags: 0x0405 as 2 LE bytes: 05 04
        assertEquals(0x05.toByte(), buffer[4])
        assertEquals(0x04.toByte(), buffer[5])

        assertEquals(6, buffer.position())
    }

    // ========== Decode from hand-written LE bytes ==========

    @Test
    fun decodeFromHandWrittenLeBytes() {
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        // Write raw bytes in the expected wire format
        buffer.writeByte(0xAA.toByte()) // beByte
        buffer.writeByte(0x34)
        buffer.writeByte(0x12) // leShort=0x1234 as LE
        buffer.writeInt(0x56789ABC.toInt()) // beInt as BE
        buffer.writeByte(0xEF.toByte())
        buffer.writeByte(0xBE.toByte())
        buffer.writeByte(0xAD.toByte())
        buffer.writeByte(0xDE.toByte()) // leInt=0xDEADBEEF as LE
        buffer.writeByte(0x08)
        buffer.writeByte(0x07)
        buffer.writeByte(0x06)
        buffer.writeByte(0x05)
        buffer.writeByte(0x04)
        buffer.writeByte(0x03)
        buffer.writeByte(0x02)
        buffer.writeByte(0x01) // leLong=0x0102030405060708 as LE
        buffer.resetForRead()

        val decoded = WireOrderMessageCodec.decode(buffer)
        assertEquals(0xAAu.toUByte(), decoded.beByte)
        assertEquals(0x1234u.toUShort(), decoded.leShort)
        assertEquals(0x56789ABC.toInt(), decoded.beInt)
        assertEquals(0xDEADBEEFu, decoded.leInt)
        assertEquals(0x0102030405060708L, decoded.leLong)
    }

    @Test
    fun decodeCustomWidthFromHandWrittenLeBytes() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeByte(0x42) // tag
        buffer.writeByte(0x56)
        buffer.writeByte(0x34)
        buffer.writeByte(0x12) // leLength=0x123456 LE
        buffer.writeByte(0x90.toByte())
        buffer.writeByte(0x78) // leFlags=0x7890 LE
        buffer.resetForRead()

        val decoded = WireOrderCustomWidthMessageCodec.decode(buffer)
        assertEquals(0x42u.toUByte(), decoded.tag)
        assertEquals(0x123456u, decoded.leLength)
        assertEquals(0x7890, decoded.leFlags)
    }

    // ========== Edge cases ==========

    @Test
    fun zeroValuesRoundTrip() {
        val original = WireOrderMessage(0u, 0u, 0, 0u, 0L)
        assertEquals(original, WireOrderMessageCodec.testRoundTrip(original))
    }

    @Test
    fun maxValuesRoundTrip() {
        val original =
            WireOrderMessage(
                UByte.MAX_VALUE,
                UShort.MAX_VALUE,
                Int.MAX_VALUE,
                UInt.MAX_VALUE,
                Long.MAX_VALUE,
            )
        assertEquals(original, WireOrderMessageCodec.testRoundTrip(original))
    }

    @Test
    fun customWidthMaxValuesRoundTrip() {
        val original =
            WireOrderCustomWidthMessage(
                tag = UByte.MAX_VALUE,
                leLength = 0xFFFFFFu, // max 3-byte value
                leFlags = 0x7FFF, // max signed 2-byte value
            )
        assertEquals(original, WireOrderCustomWidthMessageCodec.testRoundTrip(original))
    }
}
