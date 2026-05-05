package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Phase I.1 step 7 — round-trip + byte-exact wire tests for
 * [MqttRemainingLengthCodec]. Verifies the user-codec produces the
 * same wire output that the slice 8 `appendDecodeRemainingLength` /
 * `appendEncodeRemainingLength` emit produced for `@RemainingLength`,
 * so step 8's PingReq migration can be a pure annotation swap.
 *
 * Boundary values exercise every byte-width transition (1 ↔ 2 ↔ 3 ↔ 4)
 * per MQTT v3.1.1 §2.2.3.
 */
class MqttRemainingLengthCodecTest {
    @Test
    fun encodesZeroAsSingleByte() {
        assertEncodes(0u, byteArrayOf(0x00))
    }

    @Test
    fun encodes127AsSingleByte() {
        // 127 is the largest 1-byte value (continuation bit clear).
        assertEncodes(127u, byteArrayOf(0x7F))
    }

    @Test
    fun encodes128AsTwoBytes() {
        // 128 = first 2-byte value: low 7 bits = 0 (with cont), then 1.
        assertEncodes(128u, byteArrayOf(0x80.toByte(), 0x01))
    }

    @Test
    fun encodes16383AsTwoBytes() {
        // 16_383 = 0x3FFF = largest 2-byte value: 0xFF, 0x7F.
        assertEncodes(16_383u, byteArrayOf(0xFF.toByte(), 0x7F))
    }

    @Test
    fun encodes16384AsThreeBytes() {
        // 16_384 = 0x4000 = first 3-byte value: 0x80, 0x80, 0x01.
        assertEncodes(16_384u, byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x01))
    }

    @Test
    fun encodesMaxValueAsFourBytes() {
        // 0x0FFF_FFFF = 268_435_455 = largest legal value (4 bytes).
        assertEncodes(0x0FFF_FFFFu, byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F))
    }

    @Test
    fun decodesZero() {
        assertDecodes(byteArrayOf(0x00), 0u)
    }

    @Test
    fun decodes127() {
        assertDecodes(byteArrayOf(0x7F), 127u)
    }

    @Test
    fun decodes128() {
        assertDecodes(byteArrayOf(0x80.toByte(), 0x01), 128u)
    }

    @Test
    fun decodes16383() {
        assertDecodes(byteArrayOf(0xFF.toByte(), 0x7F), 16_383u)
    }

    @Test
    fun decodes16384() {
        assertDecodes(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x01), 16_384u)
    }

    @Test
    fun decodesMaxValue() {
        assertDecodes(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F),
            0x0FFF_FFFFu,
        )
    }

    @Test
    fun roundTripsBoundaryValues() {
        for (value in listOf(0u, 127u, 128u, 16_383u, 16_384u, 2_097_151u, 2_097_152u, 0x0FFF_FFFFu)) {
            val buffer = BufferFactory.Default.allocate(8)
            MqttRemainingLengthCodec.encode(buffer, value, EncodeContext.Empty)
            buffer.resetForRead()
            assertEquals(value, MqttRemainingLengthCodec.decode(buffer, DecodeContext.Empty))
        }
    }

    @Test
    fun decodeOfFiveContinuationBytesThrowsDecodeException() {
        // 5 bytes all with the continuation bit set is malformed per §2.2.3.
        // After 4 bytes the codec must throw rather than read a 5th.
        val buffer = BufferFactory.Default.allocate(8)
        repeat(5) { buffer.writeByte(0xFF.toByte()) }
        buffer.resetForRead()
        val ex =
            assertFailsWith<DecodeException> {
                MqttRemainingLengthCodec.decode(buffer, DecodeContext.Empty)
            }
        assertEquals("MqttRemainingLength", ex.fieldPath)
    }

    @Test
    fun encodeRejectsValueAboveMax() {
        val buffer = BufferFactory.Default.allocate(8)
        assertFailsWith<IllegalArgumentException> {
            MqttRemainingLengthCodec.encode(buffer, 0x1000_0000u, EncodeContext.Empty)
        }
    }

    @Test
    fun wireSizeReportsExactByteWidth() {
        assertEquals(WireSize.Exact(1), MqttRemainingLengthCodec.wireSize(0u, EncodeContext.Empty))
        assertEquals(WireSize.Exact(1), MqttRemainingLengthCodec.wireSize(127u, EncodeContext.Empty))
        assertEquals(WireSize.Exact(2), MqttRemainingLengthCodec.wireSize(128u, EncodeContext.Empty))
        assertEquals(WireSize.Exact(2), MqttRemainingLengthCodec.wireSize(16_383u, EncodeContext.Empty))
        assertEquals(WireSize.Exact(3), MqttRemainingLengthCodec.wireSize(16_384u, EncodeContext.Empty))
        assertEquals(WireSize.Exact(3), MqttRemainingLengthCodec.wireSize(2_097_151u, EncodeContext.Empty))
        assertEquals(WireSize.Exact(4), MqttRemainingLengthCodec.wireSize(2_097_152u, EncodeContext.Empty))
        assertEquals(WireSize.Exact(4), MqttRemainingLengthCodec.wireSize(0x0FFF_FFFFu, EncodeContext.Empty))
    }

    @Test
    fun applyBoundNarrowsLimitToPositionPlusValue() {
        val buffer = BufferFactory.Default.allocate(64)
        repeat(64) { buffer.writeByte(0x00) }
        buffer.resetForRead()
        // Advance position to 5; outer limit is 64.
        repeat(5) { buffer.readByte() }
        assertEquals(64, buffer.limit())
        assertEquals(5, buffer.position())
        MqttRemainingLengthCodec.applyBound(buffer, 12u)
        assertEquals(17, buffer.limit())
        assertEquals(5, buffer.position())
    }

    private fun assertEncodes(
        value: UInt,
        expected: ByteArray,
    ) {
        val buffer = BufferFactory.Default.allocate(8)
        MqttRemainingLengthCodec.encode(buffer, value, EncodeContext.Empty)
        val written = buffer.position()
        buffer.resetForRead()
        val actual = ByteArray(written)
        for (i in 0 until written) actual[i] = buffer.readByte()
        assertContentEquals(expected, actual)
    }

    private fun assertDecodes(
        wire: ByteArray,
        expected: UInt,
    ) {
        val buffer = BufferFactory.Default.allocate(wire.size + 4)
        for (b in wire) buffer.writeByte(b)
        buffer.resetForRead()
        assertEquals(expected, MqttRemainingLengthCodec.decode(buffer, DecodeContext.Empty))
    }
}
