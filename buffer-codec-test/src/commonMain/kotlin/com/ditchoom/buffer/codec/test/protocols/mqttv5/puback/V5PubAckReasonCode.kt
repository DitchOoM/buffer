package com.ditchoom.buffer.codec.test.protocols.mqttv5.puback

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * Phase J.M.5 slice 11b — typed reason code for v5 PUBACK / PUBREC /
 * PUBREL / PUBCOMP (§3.4.2.1, §3.5.2.1, §3.6.2.1, §3.7.2.1). Shared
 * parent — the spec lists the same union of codes across all four
 * packets (PUBACK / PUBREC have a 9-code superset, PUBREL / PUBCOMP
 * have a 2-code subset). The audit-2d per-packet allowlists are
 * deleted as redundant on this typed parent.
 *
 * Per-packet validity (e.g. PUBACK rejecting 0x92, PUBREL rejecting
 * 0x10) is intentionally not type-enforced. Splitting into per-packet
 * sealed parents would force four near-duplicate copies of every
 * variant; type-system protection of the union is sufficient to
 * eliminate the bogus-byte impossible-state without that ceremony.
 *
 * Lives in its own subpackage `mqttv5.puback` so per-variant codec
 * file names (e.g. `SuccessCodec.kt`) don't collide with the same
 * mnemonics in the other reason-code families (Unsubscribe,
 * Disconnect, Auth, Connect — each has its own `Success`).
 */
@JvmInline
@ProtocolMessage
value class V5PubAckReasonCodeRaw(
    val raw: UByte,
) {
    @DispatchValue
    val id: Int get() = raw.toInt()
}

@DispatchOn(V5PubAckReasonCodeRaw::class)
@ProtocolMessage
sealed interface V5PubAckReasonCode {
    @PacketType(value = 0x00)
    @ProtocolMessage
    data class Success(
        val id: V5PubAckReasonCodeRaw = V5PubAckReasonCodeRaw(0x00u),
    ) : V5PubAckReasonCode

    @PacketType(value = 0x10)
    @ProtocolMessage
    data class NoMatchingSubscribers(
        val id: V5PubAckReasonCodeRaw = V5PubAckReasonCodeRaw(0x10u),
    ) : V5PubAckReasonCode

    @PacketType(value = 0x80)
    @ProtocolMessage
    data class UnspecifiedError(
        val id: V5PubAckReasonCodeRaw = V5PubAckReasonCodeRaw(0x80u),
    ) : V5PubAckReasonCode

    @PacketType(value = 0x83)
    @ProtocolMessage
    data class ImplementationSpecificError(
        val id: V5PubAckReasonCodeRaw = V5PubAckReasonCodeRaw(0x83u),
    ) : V5PubAckReasonCode

    @PacketType(value = 0x87)
    @ProtocolMessage
    data class NotAuthorized(
        val id: V5PubAckReasonCodeRaw = V5PubAckReasonCodeRaw(0x87u),
    ) : V5PubAckReasonCode

    @PacketType(value = 0x90)
    @ProtocolMessage
    data class TopicNameInvalid(
        val id: V5PubAckReasonCodeRaw = V5PubAckReasonCodeRaw(0x90u),
    ) : V5PubAckReasonCode

    @PacketType(value = 0x91)
    @ProtocolMessage
    data class PacketIdentifierInUse(
        val id: V5PubAckReasonCodeRaw = V5PubAckReasonCodeRaw(0x91u),
    ) : V5PubAckReasonCode

    @PacketType(value = 0x92)
    @ProtocolMessage
    data class PacketIdentifierNotFound(
        val id: V5PubAckReasonCodeRaw = V5PubAckReasonCodeRaw(0x92u),
    ) : V5PubAckReasonCode

    @PacketType(value = 0x97)
    @ProtocolMessage
    data class QuotaExceeded(
        val id: V5PubAckReasonCodeRaw = V5PubAckReasonCodeRaw(0x97u),
    ) : V5PubAckReasonCode

    @PacketType(value = 0x99)
    @ProtocolMessage
    data class PayloadFormatInvalid(
        val id: V5PubAckReasonCodeRaw = V5PubAckReasonCodeRaw(0x99u),
    ) : V5PubAckReasonCode
}
