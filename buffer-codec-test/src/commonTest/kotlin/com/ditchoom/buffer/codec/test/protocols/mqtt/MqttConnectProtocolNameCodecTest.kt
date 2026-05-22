package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * MQTT v3.1.1 §3.1.2.1 protocol-name field. The wire bytes are fixed:
 *   0x00 0x04 'M' 'Q' 'T' 'T'
 * Validates that Stage B's value-class top-level path composes with
 * Stage C's `@LengthPrefixed val: String` emission and that the codec
 * round-trips the literal spec bytes without modification.
 */
class MqttConnectProtocolNameCodecTest {
    private val canonicalWire =
        byteArrayOf(
            0x00,
            0x04,
            0x4D, // 'M'
            0x51, // 'Q'
            0x54, // 'T'
            0x54, // 'T'
        )

    @Test
    fun roundTripsCanonicalSpecBytes() {
        val original = MqttConnectProtocolName("MQTT")
        val buf = BufferFactory.Default.allocate(canonicalWire.size)
        MqttConnectProtocolNameCodec.encode(buf, original, EncodeContext.Empty)
        assertEquals(canonicalWire.size, buf.position(), "encode wrote 6 bytes")

        buf.resetForRead()
        for ((i, expected) in canonicalWire.withIndex()) {
            assertEquals(expected.toInt() and 0xFF, buf.readByte().toInt() and 0xFF, "byte $i")
        }

        val decodeBuf = BufferFactory.Default.wrap(canonicalWire)
        val decoded = MqttConnectProtocolNameCodec.decode(decodeBuf, DecodeContext.Empty)
        assertEquals(original, decoded)
        assertEquals("MQTT", decoded.name)
    }

    @Test
    fun roundTripsEmptyName() {
        val original = MqttConnectProtocolName("")
        val buf = BufferFactory.Default.allocate(2)
        MqttConnectProtocolNameCodec.encode(buf, original, EncodeContext.Empty)
        assertEquals(2, buf.position(), "encode wrote prefix-only for empty name")
        buf.resetForRead()
        val decoded = MqttConnectProtocolNameCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun wireSizeIsBackPatch() {
        assertEquals(
            WireSize.BackPatch,
            MqttConnectProtocolNameCodec.wireSize(MqttConnectProtocolName("MQTT"), EncodeContext.Empty),
        )
    }
}
