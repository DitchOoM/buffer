package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Stage E slice 5b + Stage G slice 8 doctrine vector. Validates
 * the full MQTT v3.1.1 CONNECT packet byte-exactly against
 * MQTT-3.1.1 §3.1, exercising every Stage E + G annotation in
 * combination:
 *
 *   - `@RemainingLength` var-int header (slice 8) bounds decode.
 *   - Value-class fixed-header field (slice 3 / 6).
 *   - Non-terminal `@LengthPrefixed val: String` (slice 5a).
 *   - Value-class connectFlags field with bit-packed Boolean getters
 *     (slice 3).
 *   - Multiple `@LengthPrefixed @When("connectFlags.<bit>")
 *     val: String?` slots (slice 3 dotted form + slice 3.5
 *     LengthPrefixed inner + slice 5b non-terminal Conditional).
 *   - Sequential peek walk via the slice 8 RemainingLength fast
 *     path (header byte + var-int + value covers the full message).
 *
 * Phase J.M step 4 — folded onto the `MqttPacket.Connect` sealed
 * variant. Drives `ConnectCodec` (the per-variant codec object emitted
 * by the slice 6 dispatcher) per the brief's option 1: same byte-
 * exact assertions, same var-int boundary coverage, same drip-fed
 * peekFrameSize coverage. The standalone `MqttConnect` data class +
 * `MqttConnectCodec` are gone with this fold.
 */
class MqttConnectCodecTest {
    @Test
    fun roundTripsClientIdOnly() {
        // body: 6 (protocolName "MQTT" with prefix) + 1 (level) + 1 (flags) +
        //       2 (keepalive) + 5 (clientId "abc" with prefix) = 15
        val msg =
            MqttPacket.Connect(
                header = MqttFixedHeader(0x10u),
                protocolName = "MQTT",
                protocolLevel = 0x04u,
                connectFlags = MqttConnectFlags(0x02u),
                keepAliveSeconds = 60u,
                clientId = "abc",
            )
        val expected =
            byteArrayOf(
                0x10, // fixed header (type=1 << 4)
                0x0F, // remaining length = 15 (1-byte var-int)
                // Variable header
                0x00,
                0x04,
                'M'.code.toByte(),
                'Q'.code.toByte(),
                'T'.code.toByte(),
                'T'.code.toByte(),
                0x04,
                0x02.toByte(), // cleanSession only
                0x00,
                0x3C,
                // Payload — clientId only
                0x00,
                0x03,
                'a'.code.toByte(),
                'b'.code.toByte(),
                'c'.code.toByte(),
            )
        roundTripBytewise(msg, expected)
    }

    @Test
    fun roundTripsUsernameAndPassword() {
        // body: 6 (proto) + 1 (level) + 1 (flags) + 2 (keepalive) +
        //       6 (clientId "test") + 6 (username "user") + 6 (password "pass") = 28
        // Flags: usernamePresent (0x80) | passwordPresent (0x40) | cleanSession (0x02) = 0xC2
        val msg =
            MqttPacket.Connect(
                header = MqttFixedHeader(0x10u),
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
                0x10,
                0x1C, // remaining length = 28
                0x00,
                0x04,
                'M'.code.toByte(),
                'Q'.code.toByte(),
                'T'.code.toByte(),
                'T'.code.toByte(),
                0x04,
                0xC2.toByte(),
                0x00,
                0x3C,
                0x00,
                0x04,
                't'.code.toByte(),
                'e'.code.toByte(),
                's'.code.toByte(),
                't'.code.toByte(),
                // willTopic / willMessage absent (willPresent bit not set)
                0x00,
                0x04,
                'u'.code.toByte(),
                's'.code.toByte(),
                'e'.code.toByte(),
                'r'.code.toByte(),
                0x00,
                0x04,
                'p'.code.toByte(),
                'a'.code.toByte(),
                's'.code.toByte(),
                's'.code.toByte(),
            )
        roundTripBytewise(msg, expected)
    }

    @Test
    fun roundTripsWillFields() {
        // body: 6 + 1 + 1 + 2 + 3 (clientId "c") + 3 (willTopic "t") + 3 (willMessage "m") = 19
        // Flags: willPresent (0x04) | willRetain (0x20) | cleanSession (0x02) = 0x26
        val msg =
            MqttPacket.Connect(
                header = MqttFixedHeader(0x10u),
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
                0x10,
                0x13, // remaining length = 19
                0x00,
                0x04,
                'M'.code.toByte(),
                'Q'.code.toByte(),
                'T'.code.toByte(),
                'T'.code.toByte(),
                0x04,
                0x26.toByte(),
                0x00,
                0x1E,
                0x00,
                0x01,
                'c'.code.toByte(),
                0x00,
                0x01,
                't'.code.toByte(),
                0x00,
                0x01,
                'm'.code.toByte(),
                // username / password absent
            )
        roundTripBytewise(msg, expected)
    }

    @Test
    fun roundTripsAllOptionalFieldsPresent() {
        // body: 6 + 1 + 1 + 2 + 8 (clientId "client") + 8 (willTopic "will/t") +
        //       5 (willMessage "bye") + 7 (username "alice") + 8 (password "secret") = 46
        // Flags: usernamePresent | passwordPresent | willRetain | willPresent | cleanSession = 0xE6
        val msg =
            MqttPacket.Connect(
                header = MqttFixedHeader(0x10u),
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
        val decoded = ConnectCodec.decode(buf, DecodeContext.Empty)
        assertEquals(msg, decoded)
    }

    @Test
    fun decodeWithUsernameAndPasswordRecoversNullablesAsNullForAbsentFields() {
        // Same wire bytes as roundTripsUsernameAndPassword.
        val wire =
            byteArrayOf(
                0x10,
                0x1C,
                0x00,
                0x04,
                'M'.code.toByte(),
                'Q'.code.toByte(),
                'T'.code.toByte(),
                'T'.code.toByte(),
                0x04,
                0xC2.toByte(),
                0x00,
                0x3C,
                0x00,
                0x04,
                't'.code.toByte(),
                'e'.code.toByte(),
                's'.code.toByte(),
                't'.code.toByte(),
                0x00,
                0x04,
                'u'.code.toByte(),
                's'.code.toByte(),
                'e'.code.toByte(),
                'r'.code.toByte(),
                0x00,
                0x04,
                'p'.code.toByte(),
                'a'.code.toByte(),
                's'.code.toByte(),
                's'.code.toByte(),
            )
        val buf = BufferFactory.Default.allocate(wire.size).also { it.writeBytes(wire) }
        buf.resetForRead()
        val decoded = ConnectCodec.decode(buf, DecodeContext.Empty)
        assertEquals("test", decoded.clientId)
        assertNull(decoded.willTopic, "willPresent bit not set → willTopic should be null")
        assertNull(decoded.willMessage, "willPresent bit not set → willMessage should be null")
        assertEquals("user", decoded.username)
        assertEquals("pass", decoded.password)
    }

    @Test
    fun decodeRespectsRemainingLengthBoundEvenWithTrailingBytes() {
        // CONNECT body bounded to 15 bytes by remainingLength. Following
        // bytes simulate the start of a second packet — must NOT be consumed.
        val wire =
            byteArrayOf(
                0x10,
                0x0F,
                0x00,
                0x04,
                'M'.code.toByte(),
                'Q'.code.toByte(),
                'T'.code.toByte(),
                'T'.code.toByte(),
                0x04,
                0x02.toByte(),
                0x00,
                0x3C,
                0x00,
                0x03,
                'a'.code.toByte(),
                'b'.code.toByte(),
                'c'.code.toByte(),
                // Trailing bytes (start of next packet, e.g., a PINGREQ 0xC0 0x00)
                0xC0.toByte(),
                0x00,
            )
        val buf = BufferFactory.Default.allocate(wire.size).also { it.writeBytes(wire) }
        buf.resetForRead()
        val decoded = ConnectCodec.decode(buf, DecodeContext.Empty)
        assertEquals("abc", decoded.clientId)
        assertEquals(2, buf.remaining(), "trailing 2 bytes (next packet) left in buffer")
    }

    @Test
    fun peekFrameSizeWalksToCompleteOnFullMessage() {
        val pool = BufferPool()
        val original =
            MqttPacket.Connect(
                header = MqttFixedHeader(0x10u),
                protocolName = "MQTT",
                protocolLevel = 0x04u,
                connectFlags = MqttConnectFlags(0xC2u),
                keepAliveSeconds = 60u,
                clientId = "test",
                username = "user",
                password = "pass",
            )
        val encoded = encode(original)
        val totalBytes = encoded.remaining()
        // 1 (header) + 1 (var-int) + 28 (body) = 30
        assertEquals(30, totalBytes)

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            assertEquals(PeekResult.NeedsMoreData, ConnectCodec.peekFrameSize(stream))

            for (i in 0 until totalBytes - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(encoded.readByte())
                one.resetForRead()
                stream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    ConnectCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(encoded.readByte())
            last.resetForRead()
            stream.append(last)
            assertEquals(PeekResult.Complete(totalBytes), ConnectCodec.peekFrameSize(stream))

            val decoded =
                stream.readBufferScoped(totalBytes) {
                    ConnectCodec.decode(this, DecodeContext.Empty)
                }
            assertEquals(original, decoded)
            assertEquals(0, stream.available(), "stream should be drained")
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeShortCircuitsAfterReadingHeaderAndVarInt() {
        // With slice 8's RemainingLength fast path, peek can complete after
        // reading just header (1 byte) + var-int (1-4 bytes) — it doesn't
        // need to walk the payload.
        val pool = BufferPool()
        val original =
            MqttPacket.Connect(
                header = MqttFixedHeader(0x10u),
                protocolName = "MQTT",
                protocolLevel = 0x04u,
                connectFlags = MqttConnectFlags(0x02u),
                keepAliveSeconds = 60u,
                clientId = "abc",
            )
        val encoded = encode(original)
        val expectedTotal = encoded.remaining()
        // 1 (header) + 1 (var-int) + 15 (body) = 17
        assertEquals(17, expectedTotal)

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            stream.append(
                BufferFactory.Default.allocate(expectedTotal).also {
                    while (encoded.remaining() > 0) it.writeByte(encoded.readByte())
                    it.resetForRead()
                },
            )
            assertEquals(PeekResult.Complete(expectedTotal), ConnectCodec.peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    private fun roundTripBytewise(
        original: MqttPacket.Connect,
        expected: ByteArray,
    ) {
        val buf = encode(original)
        assertEquals(expected.size, buf.remaining(), "encoded byte count matches spec layout")
        val actual = buf.readByteArray(expected.size)
        assertContentEquals(expected, actual, "encoded bytes match MQTT-3.1.1 §3.1")

        val readBuf = BufferFactory.Default.allocate(expected.size).also { it.writeBytes(expected) }
        readBuf.resetForRead()
        val decoded = ConnectCodec.decode(readBuf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    private fun encode(value: MqttPacket.Connect): ReadBuffer = ConnectCodec.encode(value, EncodeContext.Empty, BufferFactory.Default)
}
