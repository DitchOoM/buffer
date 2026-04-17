package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.codec.test.protocols.CommandPayload
import com.ditchoom.buffer.codec.test.protocols.CommandPayloadCodec
import com.ditchoom.buffer.codec.test.protocols.ConnectionStatus
import com.ditchoom.buffer.codec.test.protocols.DeviceState
import com.ditchoom.buffer.codec.test.protocols.DeviceStateCodec
import com.ditchoom.buffer.codec.testRoundTrip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CommandPayloadRoundTripTest {
    @Test
    fun setRgbStateRoundTrips() {
        val original: CommandPayload = CommandPayload.SetRgbState(r = 1u, g = 2u, b = 3u)
        val decoded = CommandPayloadCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun setRgbStateEncodesExactBytes() {
        val original: CommandPayload = CommandPayload.SetRgbState(r = 0x10u, g = 0x20u, b = 0x30u)
        val decoded =
            CommandPayloadCodec.testRoundTrip(
                original,
                expectedBytes = byteArrayOf(0x22, 0x10, 0x20, 0x30),
            )
        assertEquals(original, decoded)
    }

    @Test
    fun getRgbStateDataObjectRoundTrips() {
        val original: CommandPayload = CommandPayload.GetRgbState
        val decoded =
            CommandPayloadCodec.testRoundTrip(
                original,
                expectedBytes = byteArrayOf(0x23),
            )
        assertSame(CommandPayload.GetRgbState, decoded, "data object should round-trip to the singleton")
    }

    @Test
    fun resetDevicePlainObjectRoundTrips() {
        val original: CommandPayload = CommandPayload.ResetDevice
        val decoded =
            CommandPayloadCodec.testRoundTrip(
                original,
                expectedBytes = byteArrayOf(0x24),
            )
        assertSame(CommandPayload.ResetDevice, decoded, "plain object should round-trip to the singleton")
    }

    // ── Nested data-object states via an outer data class ──

    @Test
    fun deviceStateDisconnectedRoundTrips() {
        val original = DeviceState(deviceId = 7u, connection = ConnectionStatus.Disconnected)
        val decoded =
            DeviceStateCodec.testRoundTrip(
                original,
                expectedBytes = byteArrayOf(0x07, 0x00),
            )
        assertEquals(original.deviceId, decoded.deviceId)
        assertSame(ConnectionStatus.Disconnected, decoded.connection)
    }

    @Test
    fun deviceStateConnectingRoundTrips() {
        val original = DeviceState(deviceId = 7u, connection = ConnectionStatus.Connecting(attempt = 3u))
        val decoded =
            DeviceStateCodec.testRoundTrip(
                original,
                expectedBytes = byteArrayOf(0x07, 0x01, 0x03),
            )
        assertEquals(original, decoded)
    }

    @Test
    fun deviceStateConnectedRoundTrips() {
        val original = DeviceState(deviceId = 42u, connection = ConnectionStatus.Connected)
        val decoded = DeviceStateCodec.testRoundTrip(original)
        assertTrue(decoded.connection is ConnectionStatus.Connected)
        assertSame(ConnectionStatus.Connected, decoded.connection)
    }

    @Test
    fun deviceStateFailedRoundTrips() {
        val original = DeviceState(deviceId = 9u, connection = ConnectionStatus.Failed)
        val decoded = DeviceStateCodec.testRoundTrip(original)
        assertSame(ConnectionStatus.Failed, decoded.connection)
    }
}
