package com.ditchoom.buffer.codec.test.protocols.mqttv5.disconnectrc

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * MQTT v5 DISCONNECT reason code (§3.14.2.1). Largest of the v5
 * reason-code spaces: 28 codes spanning client- and server-side
 * causes.
 */
@JvmInline
@ProtocolMessage
value class V5DisconnectReasonCodeRaw(
    val raw: UByte,
) {
    @DispatchValue
    val id: Int get() = raw.toInt()
}

@DispatchOn(V5DisconnectReasonCodeRaw::class)
@ProtocolMessage
sealed interface V5DisconnectReasonCode {
    @PacketType(value = 0x00)
    @ProtocolMessage
    data class NormalDisconnection(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x00u),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x04)
    @ProtocolMessage
    data class DisconnectWithWillMessage(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x04u),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x80)
    @ProtocolMessage
    data class UnspecifiedError(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x80u),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x81)
    @ProtocolMessage
    data class MalformedPacket(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x81u),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x82)
    @ProtocolMessage
    data class ProtocolError(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x82u),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x83)
    @ProtocolMessage
    data class ImplementationSpecificError(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x83u),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x87)
    @ProtocolMessage
    data class NotAuthorized(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x87u),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x89)
    @ProtocolMessage
    data class ServerBusy(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x89u),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x8B)
    @ProtocolMessage
    data class ServerShuttingDown(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x8Bu),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x8D)
    @ProtocolMessage
    data class KeepAliveTimeout(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x8Du),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x8E)
    @ProtocolMessage
    data class SessionTakenOver(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x8Eu),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x8F)
    @ProtocolMessage
    data class TopicFilterInvalid(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x8Fu),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x90)
    @ProtocolMessage
    data class TopicNameInvalid(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x90u),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x93)
    @ProtocolMessage
    data class ReceiveMaximumExceeded(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x93u),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x94)
    @ProtocolMessage
    data class TopicAliasInvalid(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x94u),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x95)
    @ProtocolMessage
    data class PacketTooLarge(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x95u),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x97)
    @ProtocolMessage
    data class QuotaExceeded(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x97u),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x98)
    @ProtocolMessage
    data class AdministrativeAction(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x98u),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x99)
    @ProtocolMessage
    data class PayloadFormatInvalid(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x99u),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x9A)
    @ProtocolMessage
    data class RetainNotSupported(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x9Au),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x9B)
    @ProtocolMessage
    data class QoSNotSupported(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x9Bu),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x9C)
    @ProtocolMessage
    data class UseAnotherServer(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x9Cu),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x9D)
    @ProtocolMessage
    data class ServerMoved(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x9Du),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x9E)
    @ProtocolMessage
    data class SharedSubscriptionsNotSupported(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x9Eu),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0x9F)
    @ProtocolMessage
    data class ConnectionRateExceeded(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0x9Fu),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0xA0)
    @ProtocolMessage
    data class MaximumConnectTime(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0xA0u),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0xA1)
    @ProtocolMessage
    data class SubscriptionIdentifiersNotSupported(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0xA1u),
    ) : V5DisconnectReasonCode

    @PacketType(value = 0xA2)
    @ProtocolMessage
    data class WildcardSubscriptionsNotSupported(
        val id: V5DisconnectReasonCodeRaw = V5DisconnectReasonCodeRaw(0xA2u),
    ) : V5DisconnectReasonCode
}
