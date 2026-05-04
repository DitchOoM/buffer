package com.ditchoom.buffer.codec.test.protocols.mqtt

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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Stage E slice 5b doctrine vector. Validates the full MQTT v3.1.1
 * CONNECT variable-header + payload byte-exactly against
 * MQTT-3.1.1 §3.1, exercising every Stage E annotation in
 * combination:
 *
 *   - Non-terminal `@LengthPrefixed val: String` (slice 5a).
 *   - Value-class field with bit-packed Boolean getters (slice 3).
 *   - Multiple `@LengthPrefixed @WhenTrue("connectFlags.<bit>")
 *     val: String?` slots (slice 3 dotted form + slice 3.5
 *     LengthPrefixed inner + slice 5b non-terminal Conditional).
 *   - Sequential peek walk through alternating fixed,
 *     variable-length, and conditional fields (slice 5a).
 */
class MqttConnectCodecTest {
    @Test
    fun roundTripsClientIdOnly() {
        val msg =
            MqttConnect(
                protocolName = "MQTT",
                protocolLevel = 0x04u,
                connectFlags = MqttConnectFlags(0x02u),
                keepAliveSeconds = 60u,
                clientId = "abc",
            )
        val expected =
            byteArrayOf(
                // Variable header
                0x00, 0x04, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(),
                0x04,
                0x02.toByte(), // cleanSession only
                0x00, 0x3C,
                // Payload — clientId only
                0x00, 0x03, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(),
            )
        roundTripBytewise(msg, expected)
    }

    @Test
    fun roundTripsUsernameAndPassword() {
        // Flags: usernamePresent (0x80) | passwordPresent (0x40) | cleanSession (0x02) = 0xC2
        val msg =
            MqttConnect(
                protocolName = "MQTT",
                protocolLevel = 0x04u,
                connectFlags = MqttConnectFlags(0xC2u),
                keepAliveSeconds = 60u,
                clientId = "test",
                username = "user",
                password = "pass",
            )
        val expected =
            byteArrayOf(
                0x00, 0x04, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(),
                0x04,
                0xC2.toByte(),
                0x00, 0x3C,
                0x00, 0x04, 't'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(),
                // willTopic / willMessage absent (willPresent bit not set)
                0x00, 0x04, 'u'.code.toByte(), 's'.code.toByte(), 'e'.code.toByte(), 'r'.code.toByte(),
                0x00, 0x04, 'p'.code.toByte(), 'a'.code.toByte(), 's'.code.toByte(), 's'.code.toByte(),
            )
        roundTripBytewise(msg, expected)
    }

    @Test
    fun roundTripsWillFields() {
        // Flags: willPresent (0x04) | willRetain (0x20) | cleanSession (0x02) = 0x26
        val msg =
            MqttConnect(
                protocolName = "MQTT",
                protocolLevel = 0x04u,
                connectFlags = MqttConnectFlags(0x26u),
                keepAliveSeconds = 30u,
                clientId = "c",
                willTopic = "t",
                willMessage = "m",
            )
        val expected =
            byteArrayOf(
                0x00, 0x04, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(),
                0x04,
                0x26.toByte(),
                0x00, 0x1E,
                0x00, 0x01, 'c'.code.toByte(),
                0x00, 0x01, 't'.code.toByte(),
                0x00, 0x01, 'm'.code.toByte(),
                // username / password absent
            )
        roundTripBytewise(msg, expected)
    }

    @Test
    fun roundTripsAllOptionalFieldsPresent() {
        // Flags: usernamePresent | passwordPresent | willRetain | willPresent | cleanSession = 0xE6
        val msg =
            MqttConnect(
                protocolName = "MQTT",
                protocolLevel = 0x04u,
                connectFlags = MqttConnectFlags(0xE6u),
                keepAliveSeconds = 120u,
                clientId = "client",
                willTopic = "will/t",
                willMessage = "bye",
                username = "alice",
                password = "secret",
            )
        val buf = encode(msg)
        buf.resetForRead()
        val decoded = MqttConnectCodec.decode(buf, DecodeContext.Empty)
        assertEquals(msg, decoded)
    }

    @Test
    fun decodeWithUsernameAndPasswordRecoversNullablesAsNullForAbsentFields() {
        // Same wire bytes as roundTripsUsernameAndPassword.
        val wire =
            byteArrayOf(
                0x00, 0x04, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(),
                0x04,
                0xC2.toByte(),
                0x00, 0x3C,
                0x00, 0x04, 't'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(),
                0x00, 0x04, 'u'.code.toByte(), 's'.code.toByte(), 'e'.code.toByte(), 'r'.code.toByte(),
                0x00, 0x04, 'p'.code.toByte(), 'a'.code.toByte(), 's'.code.toByte(), 's'.code.toByte(),
            )
        val buf = BufferFactory.Default.allocate(wire.size).also { it.writeBytes(wire) }
        buf.resetForRead()
        val decoded = MqttConnectCodec.decode(buf, DecodeContext.Empty)
        assertEquals("test", decoded.clientId)
        assertNull(decoded.willTopic, "willPresent bit not set → willTopic should be null")
        assertNull(decoded.willMessage, "willPresent bit not set → willMessage should be null")
        assertEquals("user", decoded.username)
        assertEquals("pass", decoded.password)
    }

    @Test
    fun wireSizeIsBackPatch() {
        // Locked Decision rows 15 and 19 both apply: any LPS String AND any
        // @WhenTrue field collapse to BackPatch. MQTT CONNECT has both.
        val msg =
            MqttConnect(
                protocolName = "MQTT",
                protocolLevel = 0x04u,
                connectFlags = MqttConnectFlags(0x02u),
                keepAliveSeconds = 60u,
                clientId = "abc",
            )
        assertEquals(WireSize.BackPatch, MqttConnectCodec.wireSize(msg, EncodeContext.Empty))
    }

    @Test
    fun peekFrameSizeWalksToCompleteOnFullMessage() {
        val pool = BufferPool()
        val original =
            MqttConnect(
                protocolName = "MQTT",
                protocolLevel = 0x04u,
                connectFlags = MqttConnectFlags(0xC2u),
                keepAliveSeconds = 60u,
                clientId = "test",
                username = "user",
                password = "pass",
            )
        val encoded = encode(original)
        encoded.resetForRead()
        val totalBytes = encoded.remaining()

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, MqttConnectCodec.peekFrameSize(stream))

            for (i in 0 until totalBytes - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(encoded.readByte())
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    MqttConnectCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), MqttConnectCodec.peekFrameSize(stream))

            val decoded =
                stream.readBufferScoped(totalBytes) {
                    MqttConnectCodec.decode(this, DecodeContext.Empty)
                }
            assertEquals(original, decoded)
            assertEquals(0, stream.available(), "stream should be drained")
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeShortCircuitsWhenOptionalsAbsent() {
        // When the connectFlags byte signals no optional fields, the peek
        // walk's predicate-gated branches all skip — total is just the
        // fixed prefix + protocol name + client id length-prefixed body.
        val pool = BufferPool()
        val original =
            MqttConnect(
                protocolName = "MQTT",
                protocolLevel = 0x04u,
                connectFlags = MqttConnectFlags(0x02u),
                keepAliveSeconds = 60u,
                clientId = "abc",
            )
        val encoded = encode(original)
        encoded.resetForRead()
        val expectedTotal = encoded.remaining()
        // 6 (protocol name) + 1 (level) + 1 (flags) + 2 (keepalive) + 5 (clientId) = 15
        assertEquals(15, expectedTotal, "wire layout sanity check")

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            stream.append(BufferFactory.Default.allocate(expectedTotal).also {
                while (encoded.remaining() > 0) it.writeByte(encoded.readByte())
                it.resetForRead()
            })
            assertEquals(PeekResult.Complete(expectedTotal), MqttConnectCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun roundTripBytewise(
        original: MqttConnect,
        expected: ByteArray,
    ) {
        val buf = encode(original)
        assertEquals(expected.size, buf.position(), "encoded byte count matches spec layout")
        buf.resetForRead()
        val actual = buf.readByteArray(expected.size)
        assertContentEquals(expected, actual, "encoded bytes match MQTT-3.1.1 §3.1")

        val readBuf = BufferFactory.Default.allocate(expected.size).also { it.writeBytes(expected) }
        readBuf.resetForRead()
        val decoded = MqttConnectCodec.decode(readBuf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    private fun encode(value: MqttConnect) =
        BufferFactory.Default
            .allocate(512)
            .also { MqttConnectCodec.encode(it, value, EncodeContext.Empty) }
}
