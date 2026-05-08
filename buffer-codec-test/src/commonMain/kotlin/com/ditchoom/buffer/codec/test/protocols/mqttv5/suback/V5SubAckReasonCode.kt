package com.ditchoom.buffer.codec.test.protocols.mqttv5.suback

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * Typed reason code for v5 SUBACK (§3.9.3).
 *
 * Substituted into
 * [com.ditchoom.buffer.codec.test.protocols.mqttv5.MqttV5Packet.SubAck.reasonCodes]
 * as `List<V5SubAckReasonCode>` via 's `@RemainingBytes
 * List<sealed parent>` widening — no emitter work needed beyond what
 * already landed.
 *
 * Lives in its own subpackage `mqttv5.suback` to keep generated
 * `${VariantSimpleName}Codec.kt` filenames distinct from the other
 * five reason-code families (which already share names like
 * `NotAuthorized`, `TopicFilterInvalid`, etc.).
 */
@JvmInline
@ProtocolMessage
value class V5SubAckReasonCodeRaw(
    val raw: UByte,
) {
    @DispatchValue
    val id: Int get() = raw.toInt()
}

@DispatchOn(V5SubAckReasonCodeRaw::class)
@ProtocolMessage
sealed interface V5SubAckReasonCode {
    @PacketType(value = 0x00)
    @ProtocolMessage
    data class GrantedQoS0(
        val id: V5SubAckReasonCodeRaw = V5SubAckReasonCodeRaw(0x00u),
    ) : V5SubAckReasonCode

    @PacketType(value = 0x01)
    @ProtocolMessage
    data class GrantedQoS1(
        val id: V5SubAckReasonCodeRaw = V5SubAckReasonCodeRaw(0x01u),
    ) : V5SubAckReasonCode

    @PacketType(value = 0x02)
    @ProtocolMessage
    data class GrantedQoS2(
        val id: V5SubAckReasonCodeRaw = V5SubAckReasonCodeRaw(0x02u),
    ) : V5SubAckReasonCode

    @PacketType(value = 0x80)
    @ProtocolMessage
    data class UnspecifiedError(
        val id: V5SubAckReasonCodeRaw = V5SubAckReasonCodeRaw(0x80u),
    ) : V5SubAckReasonCode

    @PacketType(value = 0x83)
    @ProtocolMessage
    data class ImplementationSpecificError(
        val id: V5SubAckReasonCodeRaw = V5SubAckReasonCodeRaw(0x83u),
    ) : V5SubAckReasonCode

    @PacketType(value = 0x87)
    @ProtocolMessage
    data class NotAuthorized(
        val id: V5SubAckReasonCodeRaw = V5SubAckReasonCodeRaw(0x87u),
    ) : V5SubAckReasonCode

    @PacketType(value = 0x8F)
    @ProtocolMessage
    data class TopicFilterInvalid(
        val id: V5SubAckReasonCodeRaw = V5SubAckReasonCodeRaw(0x8Fu),
    ) : V5SubAckReasonCode

    @PacketType(value = 0x91)
    @ProtocolMessage
    data class PacketIdentifierInUse(
        val id: V5SubAckReasonCodeRaw = V5SubAckReasonCodeRaw(0x91u),
    ) : V5SubAckReasonCode

    @PacketType(value = 0x97)
    @ProtocolMessage
    data class QuotaExceeded(
        val id: V5SubAckReasonCodeRaw = V5SubAckReasonCodeRaw(0x97u),
    ) : V5SubAckReasonCode

    @PacketType(value = 0x9E)
    @ProtocolMessage
    data class SharedSubscriptionsNotSupported(
        val id: V5SubAckReasonCodeRaw = V5SubAckReasonCodeRaw(0x9Eu),
    ) : V5SubAckReasonCode

    @PacketType(value = 0xA1)
    @ProtocolMessage
    data class SubscriptionIdentifiersNotSupported(
        val id: V5SubAckReasonCodeRaw = V5SubAckReasonCodeRaw(0xA1u),
    ) : V5SubAckReasonCode

    @PacketType(value = 0xA2)
    @ProtocolMessage
    data class WildcardSubscriptionsNotSupported(
        val id: V5SubAckReasonCodeRaw = V5SubAckReasonCodeRaw(0xA2u),
    ) : V5SubAckReasonCode
}
