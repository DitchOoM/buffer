package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.PacketTypeRange
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
    @PacketType(wire = 0)
    @ProtocolMessage
    data object Reserved : ControlPacketV4

    @PacketType(wire = 1)
    @ProtocolMessage
    data class Connect(
        val header: MqttFixedHeader = MqttFixedHeader(0x10u),
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

    @PacketType(wire = 2)
    @ProtocolMessage
    data class ConnAck(
        val header: MqttFixedHeader = MqttFixedHeader(0x20u),
        val flags: V4ConnAckFlags,
        val returnCode: V4ConnectReturnCode,
    ) : ControlPacketV4

    @PacketTypeRange(0x30, 0x3F)
    @ProtocolMessage
    data class Publish<@Payload P>(
        val header: MqttFixedHeader,
        @LengthPrefixed val topicName: String,
        @WhenTrue("header.publishHasPacketIdentifier") val packetIdentifier: UShort? = null,
        @RemainingBytes val payload: P,
    ) : ControlPacketV4

    @PacketType(wire = 4)
    @ProtocolMessage
    data class PubAck(
        val header: MqttFixedHeader = MqttFixedHeader(0x40u),
        val packetIdentifier: UShort,
    ) : ControlPacketV4

    @PacketType(wire = 5)
    @ProtocolMessage
    data class PubRec(
        val header: MqttFixedHeader = MqttFixedHeader(0x50u),
        val packetIdentifier: UShort,
    ) : ControlPacketV4

    @PacketType(wire = 6)
    @ProtocolMessage
    data class PubRel(
        val header: MqttFixedHeader = MqttFixedHeader(0x62u),
        val packetIdentifier: UShort,
    ) : ControlPacketV4

    @PacketType(wire = 7)
    @ProtocolMessage
    data class PubComp(
        val header: MqttFixedHeader = MqttFixedHeader(0x70u),
        val packetIdentifier: UShort,
    ) : ControlPacketV4

    @PacketType(wire = 8)
    @ProtocolMessage
    data class Subscribe(
        val header: MqttFixedHeader = MqttFixedHeader(0x82u),
        val packetIdentifier: UShort = 0u,
        @RemainingBytes val subscriptions: List<V4Subscription> = emptyList(),
    ) : ControlPacketV4

    @PacketType(wire = 9)
    @ProtocolMessage
    data class SubAck(
        val header: MqttFixedHeader = MqttFixedHeader(0x90u),
        val packetIdentifier: UShort = 0u,
        @RemainingBytes val returnCodes: List<V4SubAckReturnCode> = emptyList(),
    ) : ControlPacketV4

    @PacketType(wire = 10)
    @ProtocolMessage
    data class Unsubscribe(
        val header: MqttFixedHeader = MqttFixedHeader(0xA2u),
        val packetIdentifier: UShort = 0u,
        @RemainingBytes val topicFilters: List<V4TopicFilter> = emptyList(),
    ) : ControlPacketV4

    @PacketType(wire = 11)
    @ProtocolMessage
    data class UnsubAck(
        val header: MqttFixedHeader = MqttFixedHeader(0xB0u),
        val packetIdentifier: UShort,
    ) : ControlPacketV4

    @PacketType(wire = 12)
    @ProtocolMessage
    data class PingReq(
        val header: MqttFixedHeader = MqttFixedHeader(0xC0u),
    ) : ControlPacketV4

    @PacketType(wire = 13)
    @ProtocolMessage
    data class PingResp(
        val header: MqttFixedHeader = MqttFixedHeader(0xD0u),
    ) : ControlPacketV4

    @PacketType(wire = 14)
    @ProtocolMessage
    data class Disconnect(
        val header: MqttFixedHeader = MqttFixedHeader(0xE0u),
    ) : ControlPacketV4
}
