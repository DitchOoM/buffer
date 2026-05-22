package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Issue #150 round-trip vector (restored from PR #153).
 *
 * Validates `data object` and plain `object` sealed variants — singleton
 * variants carry zero wire bytes beyond the `@PacketType` discriminator,
 * decode returns the singleton instance via `assertSame`. Mixed shapes
 * (data class + data object under the same sealed parent) confirm
 * dispatcher table emission stays consistent across variant kinds.
 *
 * Test plan covers:
 *  - Standalone non-singleton variant (`SetRgbState`) byte-exact
 *    round-trip — sanity for the dispatcher bytes.
 *  - Singleton variants (`GetRgbState` / `ResetDevice`) decode to the
 *    `INSTANCE`, encode to a single discriminator byte.
 *  - Singleton wireSize collapses to `Exact(1)` (1-byte discriminator
 *    only).
 *  - Nested usage via `DeviceState` — outer data class with a sealed
 *    inner whose variants include singletons — exercises the
 *    sealed-dispatch path inside a typed-message field.
 */
class CommandPayloadRoundTripTest {
    @Test
    fun setRgbStateRoundTrips() {
        val original: CommandPayload = CommandPayload.SetRgbState(r = 0x10u, g = 0x20u, b = 0x30u)
        roundTripCommand(original, byteArrayOf(0x22, 0x10, 0x20, 0x30))
    }

    @Test
    fun getRgbStateDataObjectRoundTrips() {
        val original: CommandPayload = CommandPayload.GetRgbState
        val decoded = roundTripCommand(original, byteArrayOf(0x23))
        assertSame(CommandPayload.GetRgbState, decoded, "data object should round-trip to the singleton")
    }

    @Test
    fun resetDevicePlainObjectRoundTrips() {
        val original: CommandPayload = CommandPayload.ResetDevice
        val decoded = roundTripCommand(original, byteArrayOf(0x24))
        assertSame(CommandPayload.ResetDevice, decoded, "plain object should round-trip to the singleton")
    }

    @Test
    fun singletonVariantWireSizeIsDiscriminatorOnly() {
        // 1-byte discriminator, zero body bytes.
        assertEquals(
            WireSize.Exact(1),
            CommandPayloadCodec.wireSize(CommandPayload.GetRgbState, EncodeContext.Empty),
        )
        assertEquals(
            WireSize.Exact(1),
            CommandPayloadCodec.wireSize(CommandPayload.ResetDevice, EncodeContext.Empty),
        )
    }

    // ── Nested data-object states via an outer data class ──

    @Test
    fun deviceStateDisconnectedRoundTrips() {
        val original = DeviceState(deviceId = 7u, connection = ConnectionStatus.Disconnected)
        val decoded = roundTripDeviceState(original, byteArrayOf(0x07, 0x00))
        assertEquals(original.deviceId, decoded.deviceId)
        assertSame(ConnectionStatus.Disconnected, decoded.connection)
    }

    @Test
    fun deviceStateConnectingRoundTrips() {
        val original = DeviceState(deviceId = 7u, connection = ConnectionStatus.Connecting(attempt = 3u))
        val decoded = roundTripDeviceState(original, byteArrayOf(0x07, 0x01, 0x03))
        assertEquals(original, decoded)
    }

    @Test
    fun deviceStateConnectedRoundTrips() {
        val original = DeviceState(deviceId = 42u, connection = ConnectionStatus.Connected)
        val decoded = roundTripDeviceState(original, byteArrayOf(0x2A, 0x02))
        assertTrue(decoded.connection is ConnectionStatus.Connected)
        assertSame(ConnectionStatus.Connected, decoded.connection)
    }

    @Test
    fun deviceStateFailedRoundTrips() {
        val original = DeviceState(deviceId = 9u, connection = ConnectionStatus.Failed)
        val decoded = roundTripDeviceState(original, byteArrayOf(0x09, 0x03))
        assertSame(ConnectionStatus.Failed, decoded.connection)
    }

    private fun roundTripCommand(
        sample: CommandPayload,
        expectedBytes: ByteArray,
    ): CommandPayload {
        val buf = BufferFactory.Default.allocate(expectedBytes.size + 16)
        CommandPayloadCodec.encode(buf, sample, EncodeContext.Empty)
        assertEquals(expectedBytes.size, buf.position(), "encoded byte count")
        buf.resetForRead()
        val actualBytes = ByteArray(expectedBytes.size)
        for (i in expectedBytes.indices) actualBytes[i] = buf.readByte()
        assertContentEqualsHex(expectedBytes, actualBytes)
        buf.resetForRead()
        return CommandPayloadCodec.decode(buf, DecodeContext.Empty)
    }

    private fun roundTripDeviceState(
        sample: DeviceState,
        expectedBytes: ByteArray,
    ): DeviceState {
        val buf = BufferFactory.Default.allocate(expectedBytes.size + 16)
        DeviceStateCodec.encode(buf, sample, EncodeContext.Empty)
        assertEquals(expectedBytes.size, buf.position(), "encoded byte count")
        buf.resetForRead()
        val actualBytes = ByteArray(expectedBytes.size)
        for (i in expectedBytes.indices) actualBytes[i] = buf.readByte()
        assertContentEqualsHex(expectedBytes, actualBytes)
        buf.resetForRead()
        return DeviceStateCodec.decode(buf, DecodeContext.Empty)
    }

    private fun assertContentEqualsHex(
        expected: ByteArray,
        actual: ByteArray,
    ) {
        if (expected.contentEquals(actual)) return
        val expectedHex = expected.joinToString(" ") { hex(it) }
        val actualHex = actual.joinToString(" ") { hex(it) }
        kotlin.test.fail("wire bytes mismatch.\n  expected: $expectedHex\n    actual: $actualHex")
    }

    private fun hex(b: Byte): String =
        b
            .toUByte()
            .toString(16)
            .padStart(2, '0')
            .uppercase()
}
