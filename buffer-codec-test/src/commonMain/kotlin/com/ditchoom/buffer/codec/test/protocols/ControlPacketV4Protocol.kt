package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.WhenTrue
import kotlin.jvm.JvmInline

/**
 * Full MQTT v3.1.1 control-packet set assembled as a single sealed tree dispatched on the
 * fixed-header byte (top nibble = packet type, bottom nibble = packet-specific flags).
 *
 * CONNECT keeps concrete `String` payloads here to keep the sealed type signature simple;
 * the production model in `mqtt-4-models` will use `<@Payload WP, @Payload PP>` per the
 * migration plan. PUBLISH stays generic over its payload via `<@Payload P>` so the sealed
 * tree exercises the payload-dispatch codepath.
 *
 * PUBLISH carries `MqttFixedHeader` as its first field — the discriminator self-encodes via
 * type-based detection (decision #2), preserving dup/qos/retain flags. Every other variant
 * relies on the dispatcher's literal `@PacketType(wire = …)` byte.
 */

@JvmInline
@ProtocolMessage
value class MqttFixedHeader(
    val raw: UByte,
) {
    @DispatchValue
    val packetType: Int get() = (raw.toInt() shr 4) and 0x0F

    val flags: Int get() = raw.toInt() and 0x0F

    val publishDup: Boolean get() = (raw.toInt() shr 3) and 1 == 1
    val publishQos: Int get() = (raw.toInt() shr 1) and 0x3
    val publishRetain: Boolean get() = raw.toInt() and 1 == 1
    val publishHasPacketIdentifier: Boolean get() = publishQos > 0
}

@JvmInline
@ProtocolMessage
value class V4ConnectFlags(
    val raw: UByte,
) {
    val hasUserName: Boolean get() = (raw.toInt() shr 7) and 1 == 1
    val hasPassword: Boolean get() = (raw.toInt() shr 6) and 1 == 1
    val willRetain: Boolean get() = (raw.toInt() shr 5) and 1 == 1
    val willQos: Int get() = (raw.toInt() shr 3) and 0x3
    val willFlag: Boolean get() = (raw.toInt() shr 2) and 1 == 1
    val cleanSession: Boolean get() = (raw.toInt() shr 1) and 1 == 1
}

@JvmInline
@ProtocolMessage
value class V4ConnAckFlags(
    val raw: UByte,
) {
    val sessionPresent: Boolean get() = raw.toInt() and 1 == 1
}

@JvmInline
@ProtocolMessage
value class V4ConnectReturnCode(
    val raw: UByte,
) {
    companion object {
        val ACCEPTED = V4ConnectReturnCode(0x00u)
        val UNACCEPTABLE_PROTOCOL_VERSION = V4ConnectReturnCode(0x01u)
        val IDENTIFIER_REJECTED = V4ConnectReturnCode(0x02u)
        val SERVER_UNAVAILABLE = V4ConnectReturnCode(0x03u)
        val BAD_USERNAME_OR_PASSWORD = V4ConnectReturnCode(0x04u)
        val NOT_AUTHORIZED = V4ConnectReturnCode(0x05u)
    }
}

@JvmInline
@ProtocolMessage
value class V4SubscriptionOptions(
    val raw: UByte,
) {
    val qos: Int get() = raw.toInt() and 0x3
}

@JvmInline
@ProtocolMessage
value class V4SubAckReturnCode(
    val raw: UByte,
) {
    companion object {
        val MAX_QOS_0 = V4SubAckReturnCode(0x00u)
        val MAX_QOS_1 = V4SubAckReturnCode(0x01u)
        val MAX_QOS_2 = V4SubAckReturnCode(0x02u)
        val FAILURE = V4SubAckReturnCode(0x80u)
    }
}

@ProtocolMessage
data class V4Subscription(
    @LengthPrefixed val topicFilter: String,
    val options: V4SubscriptionOptions,
)

@ProtocolMessage
data class V4TopicFilter(
    @LengthPrefixed val value: String,
)

@DispatchOn(MqttFixedHeader::class)
@ProtocolMessage
sealed interface ControlPacketV4 {
    @PacketType(value = 0, wire = 0x00)
    @ProtocolMessage
    data object Reserved : ControlPacketV4

    @PacketType(value = 1, wire = 0x10)
    @ProtocolMessage
    data class Connect(
        @LengthPrefixed val protocolName: String,
        val protocolLevel: UByte,
        val flags: V4ConnectFlags,
        val keepAliveSeconds: UShort,
        @LengthPrefixed val clientId: String,
        @WhenTrue("flags.willFlag") @LengthPrefixed val willTopic: String? = null,
        @WhenTrue("flags.willFlag") @LengthPrefixed val willPayload: String? = null,
        @WhenTrue("flags.hasUserName") @LengthPrefixed val userName: String? = null,
        @WhenTrue("flags.hasPassword") @LengthPrefixed val password: String? = null,
    ) : ControlPacketV4

    @PacketType(value = 2, wire = 0x20)
    @ProtocolMessage
    data class ConnAck(
        val flags: V4ConnAckFlags,
        val returnCode: V4ConnectReturnCode,
    ) : ControlPacketV4

    @PacketType(value = 3)
    @ProtocolMessage
    data class Publish<@Payload P>(
        val header: MqttFixedHeader,
        @LengthPrefixed val topicName: String,
        @WhenTrue("header.publishHasPacketIdentifier") val packetIdentifier: UShort? = null,
        @RemainingBytes val payload: P,
    ) : ControlPacketV4

    @PacketType(value = 4, wire = 0x40)
    @ProtocolMessage
    @JvmInline
    value class PubAck(
        val packetIdentifier: UShort,
    ) : ControlPacketV4

    @PacketType(value = 5, wire = 0x50)
    @ProtocolMessage
    @JvmInline
    value class PubRec(
        val packetIdentifier: UShort,
    ) : ControlPacketV4

    @PacketType(value = 6, wire = 0x62)
    @ProtocolMessage
    @JvmInline
    value class PubRel(
        val packetIdentifier: UShort,
    ) : ControlPacketV4

    @PacketType(value = 7, wire = 0x70)
    @ProtocolMessage
    @JvmInline
    value class PubComp(
        val packetIdentifier: UShort,
    ) : ControlPacketV4

    @PacketType(value = 8, wire = 0x82)
    @ProtocolMessage
    data class Subscribe(
        val packetIdentifier: UShort,
        @RemainingBytes val subscriptions: List<V4Subscription>,
    ) : ControlPacketV4

    @PacketType(value = 9, wire = 0x90)
    @ProtocolMessage
    data class SubAck(
        val packetIdentifier: UShort,
        @RemainingBytes val returnCodes: List<V4SubAckReturnCode>,
    ) : ControlPacketV4

    @PacketType(value = 10, wire = 0xA2)
    @ProtocolMessage
    data class Unsubscribe(
        val packetIdentifier: UShort,
        @RemainingBytes val topicFilters: List<V4TopicFilter>,
    ) : ControlPacketV4

    @PacketType(value = 11, wire = 0xB0)
    @ProtocolMessage
    @JvmInline
    value class UnsubAck(
        val packetIdentifier: UShort,
    ) : ControlPacketV4

    @PacketType(value = 12, wire = 0xC0)
    @ProtocolMessage
    data object PingReq : ControlPacketV4

    @PacketType(value = 13, wire = 0xD0)
    @ProtocolMessage
    data object PingResp : ControlPacketV4

    @PacketType(value = 14, wire = 0xE0)
    @ProtocolMessage
    data object Disconnect : ControlPacketV4
}
