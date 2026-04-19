package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * Protocol using `data object` and `object` sealed variants for type-only commands
 * (e.g., ping, reset) alongside normal data-class variants with payload.
 */
@ProtocolMessage(wireOrder = Endianness.Little)
sealed interface CommandPayload {
    @PacketType(0x22)
    @ProtocolMessage
    data class SetRgbState(
        val r: UByte,
        val g: UByte,
        val b: UByte,
    ) : CommandPayload

    @PacketType(0x23)
    @ProtocolMessage
    data object GetRgbState : CommandPayload

    @PacketType(0x24)
    @ProtocolMessage
    object ResetDevice : CommandPayload
}

/**
 * A data class whose nested `@ProtocolMessage` field is a sealed interface that
 * contains `data object` variants — exercises nested decode/encode of the singleton
 * variants, not just compile-time acceptance.
 */
@ProtocolMessage(wireOrder = Endianness.Little)
data class DeviceState(
    val deviceId: UByte,
    val connection: ConnectionStatus,
)

/** A state-machine-style sealed interface with mixed data class / data object variants. */
@ProtocolMessage(wireOrder = Endianness.Little)
sealed interface ConnectionStatus {
    @PacketType(0x00)
    @ProtocolMessage
    data object Disconnected : ConnectionStatus

    @PacketType(0x01)
    @ProtocolMessage
    data class Connecting(
        val attempt: UByte,
    ) : ConnectionStatus

    @PacketType(0x02)
    @ProtocolMessage
    data object Connected : ConnectionStatus

    @PacketType(0x03)
    @ProtocolMessage
    data object Failed : ConnectionStatus
}
