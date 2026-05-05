package com.ditchoom.buffer.codec.test.protocols.mqttv5.connack

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * MQTT v5 CONNACK reason code (§3.2.2.2). Named `V5ConnectReasonCode`
 * per the audit doc convention even though it lives on the CONNACK
 * packet — the codes describe the outcome of a CONNECT attempt.
 *
 * Substituted into [com.ditchoom.buffer.codec.test.protocols.mqttv5.MqttV5Packet.ConnAck.reasonCode]
 * via the slice 11b non-conditional `ProtocolMessageScalar` analyzer
 * branch (no `@When`, always-present field).
 */
@JvmInline
@ProtocolMessage
value class V5ConnectReasonCodeRaw(
    val raw: UByte,
) {
    @DispatchValue
    val id: Int get() = raw.toInt()
}

@DispatchOn(V5ConnectReasonCodeRaw::class)
@ProtocolMessage
sealed interface V5ConnectReasonCode {
    @PacketType(value = 0x00)
    @ProtocolMessage
    data class Success(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x00u),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x80)
    @ProtocolMessage
    data class UnspecifiedError(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x80u),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x81)
    @ProtocolMessage
    data class MalformedPacket(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x81u),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x82)
    @ProtocolMessage
    data class ProtocolError(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x82u),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x83)
    @ProtocolMessage
    data class ImplementationSpecificError(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x83u),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x84)
    @ProtocolMessage
    data class UnsupportedProtocolVersion(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x84u),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x85)
    @ProtocolMessage
    data class ClientIdentifierNotValid(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x85u),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x86)
    @ProtocolMessage
    data class BadUserNameOrPassword(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x86u),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x87)
    @ProtocolMessage
    data class NotAuthorized(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x87u),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x88)
    @ProtocolMessage
    data class ServerUnavailable(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x88u),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x89)
    @ProtocolMessage
    data class ServerBusy(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x89u),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x8A)
    @ProtocolMessage
    data class Banned(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x8Au),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x8C)
    @ProtocolMessage
    data class BadAuthenticationMethod(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x8Cu),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x90)
    @ProtocolMessage
    data class TopicNameInvalid(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x90u),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x95)
    @ProtocolMessage
    data class PacketTooLarge(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x95u),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x97)
    @ProtocolMessage
    data class QuotaExceeded(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x97u),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x99)
    @ProtocolMessage
    data class PayloadFormatInvalid(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x99u),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x9A)
    @ProtocolMessage
    data class RetainNotSupported(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x9Au),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x9B)
    @ProtocolMessage
    data class QoSNotSupported(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x9Bu),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x9C)
    @ProtocolMessage
    data class UseAnotherServer(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x9Cu),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x9D)
    @ProtocolMessage
    data class ServerMoved(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x9Du),
    ) : V5ConnectReasonCode

    @PacketType(value = 0x9F)
    @ProtocolMessage
    data class ConnectionRateExceeded(
        val id: V5ConnectReasonCodeRaw = V5ConnectReasonCodeRaw(0x9Fu),
    ) : V5ConnectReasonCode
}
