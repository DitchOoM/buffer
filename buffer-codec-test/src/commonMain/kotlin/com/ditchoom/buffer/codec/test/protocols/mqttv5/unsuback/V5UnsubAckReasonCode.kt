package com.ditchoom.buffer.codec.test.protocols.mqttv5.unsuback

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/** MQTT v5 UNSUBACK reason code (§3.11.3). */
@JvmInline
@ProtocolMessage
value class V5UnsubAckReasonCodeRaw(
    val raw: UByte,
) {
    @DispatchValue
    val id: Int get() = raw.toInt()
}

@DispatchOn(V5UnsubAckReasonCodeRaw::class)
@ProtocolMessage
sealed interface V5UnsubAckReasonCode {
    @PacketType(value = 0x00)
    @ProtocolMessage
    data class Success(
        val id: V5UnsubAckReasonCodeRaw = V5UnsubAckReasonCodeRaw(0x00u),
    ) : V5UnsubAckReasonCode

    @PacketType(value = 0x11)
    @ProtocolMessage
    data class NoSubscriptionExisted(
        val id: V5UnsubAckReasonCodeRaw = V5UnsubAckReasonCodeRaw(0x11u),
    ) : V5UnsubAckReasonCode

    @PacketType(value = 0x80)
    @ProtocolMessage
    data class UnspecifiedError(
        val id: V5UnsubAckReasonCodeRaw = V5UnsubAckReasonCodeRaw(0x80u),
    ) : V5UnsubAckReasonCode

    @PacketType(value = 0x83)
    @ProtocolMessage
    data class ImplementationSpecificError(
        val id: V5UnsubAckReasonCodeRaw = V5UnsubAckReasonCodeRaw(0x83u),
    ) : V5UnsubAckReasonCode

    @PacketType(value = 0x87)
    @ProtocolMessage
    data class NotAuthorized(
        val id: V5UnsubAckReasonCodeRaw = V5UnsubAckReasonCodeRaw(0x87u),
    ) : V5UnsubAckReasonCode

    @PacketType(value = 0x8F)
    @ProtocolMessage
    data class TopicFilterInvalid(
        val id: V5UnsubAckReasonCodeRaw = V5UnsubAckReasonCodeRaw(0x8Fu),
    ) : V5UnsubAckReasonCode

    @PacketType(value = 0x91)
    @ProtocolMessage
    data class PacketIdentifierInUse(
        val id: V5UnsubAckReasonCodeRaw = V5UnsubAckReasonCodeRaw(0x91u),
    ) : V5UnsubAckReasonCode
}
