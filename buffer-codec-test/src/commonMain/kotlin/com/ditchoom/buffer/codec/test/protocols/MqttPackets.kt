package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.WhenTrue
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@JvmInline
value class MqttConnectFlags(
    val raw: UByte,
) {
    val cleanSession: Boolean get() = (raw.toInt() shr 1) and 1 == 1
    val willFlag: Boolean get() = (raw.toInt() shr 2) and 1 == 1
    val willQos: Int get() = (raw.toInt() shr 3) and 3
    val willRetain: Boolean get() = (raw.toInt() shr 5) and 1 == 1
    val passwordFlag: Boolean get() = (raw.toInt() shr 6) and 1 == 1
    val usernameFlag: Boolean get() = (raw.toInt() shr 7) and 1 == 1
}

@JvmInline
value class ConnAckFlags(
    val raw: UByte,
) {
    val sessionPresent: Boolean get() = raw.toInt() and 1 == 1
}

@JvmInline
value class ConnectReturnCode(
    val raw: UByte,
)

@JvmInline
value class PacketId(
    val raw: UShort,
)

@JvmInline
value class PublishFlags(
    val raw: UByte,
) {
    val retain: Boolean get() = raw.toInt() and 1 == 1
    val qos: Int get() = (raw.toInt() shr 1) and 3
    val dup: Boolean get() = (raw.toInt() shr 3) and 1 == 1
    val hasPacketIdentifier: Boolean get() = qos > 0
}

@JvmInline
value class SubAckReturnCode(
    val raw: UByte,
)

@JvmInline
value class ProtocolLevel(
    val raw: UByte,
)

@JvmInline
value class KeepAlive(
    val raw: UShort,
) {
    val duration: Duration get() = raw.toInt().seconds
}

@JvmInline
value class QosLevel(
    val raw: UByte,
) {
    companion object {
        val AT_MOST_ONCE = QosLevel(0u)
        val AT_LEAST_ONCE = QosLevel(1u)
        val EXACTLY_ONCE = QosLevel(2u)
    }
}

@ProtocolMessage
sealed interface MqttPacket

@ProtocolMessage
@PacketType(0x10)
data class MqttPacketConnect(
    @LengthPrefixed val protocolName: String,
    val protocolLevel: ProtocolLevel,
    val connectFlags: MqttConnectFlags,
    val keepAlive: KeepAlive,
    @LengthPrefixed val clientId: String,
    @WhenTrue("connectFlags.willFlag") @LengthPrefixed val willTopic: String? = null,
    @WhenTrue("connectFlags.willFlag") @LengthPrefixed val willMessage: String? = null,
    @WhenTrue("connectFlags.usernameFlag") @LengthPrefixed val username: String? = null,
    @WhenTrue("connectFlags.passwordFlag") @LengthPrefixed val password: String? = null,
) : MqttPacket

@ProtocolMessage
@PacketType(0x20)
data class MqttPacketConnAck(
    val acknowledgeFlags: ConnAckFlags,
    val returnCode: ConnectReturnCode,
) : MqttPacket

@ProtocolMessage
@PacketType(0x40)
@JvmInline
value class MqttPacketPubAck(
    val packetId: PacketId,
) : MqttPacket

@ProtocolMessage
@PacketType(0x50)
@JvmInline
value class MqttPacketPubRec(
    val packetId: PacketId,
) : MqttPacket

@ProtocolMessage
@PacketType(0x62)
@JvmInline
value class MqttPacketPubRel(
    val packetId: PacketId,
) : MqttPacket

@ProtocolMessage
@PacketType(0x70)
@JvmInline
value class MqttPacketPubComp(
    val packetId: PacketId,
) : MqttPacket

@ProtocolMessage
data class MqttPublish<@Payload P>(
    val flags: PublishFlags,
    @LengthPrefixed val topicName: String,
    @WhenTrue("flags.hasPacketIdentifier") val packetId: PacketId? = null,
    @RemainingBytes val payload: P,
)

@ProtocolMessage
data class MqttSubscribeSingle(
    val packetId: PacketId,
    @LengthPrefixed val topicFilter: String,
    val requestedQos: QosLevel,
)

@ProtocolMessage
data class MqttSubAckSingle(
    val packetId: PacketId,
    val returnCode: SubAckReturnCode,
)

@ProtocolMessage
data class MqttUnsubscribeSingle(
    val packetId: PacketId,
    @LengthPrefixed val topicFilter: String,
)

@ProtocolMessage
@JvmInline
value class MqttUnsubAck(
    val packetId: PacketId,
)
