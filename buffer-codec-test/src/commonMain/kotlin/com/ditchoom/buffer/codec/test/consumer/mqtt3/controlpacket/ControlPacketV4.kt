// PHASE 9 FIXTURE — copied from mqtt/models-v4/src/commonMain/kotlin/com/ditchoom/mqtt3/controlpacket/ControlPacketV4.kt
// as the new-pipeline oracle. References to generated codec classes (ControlPacketV4Codec,
// ConnectionRequestCodec, PublishMessageV4Codec, etc.) and convenience constructors that
// depend on them are removed; the wire-shape data classes, init validation, sealed tree,
// and @ProtocolMessage / @Payload / @When / @PacketType / @PacketTypeRange / @LengthPrefixed /
// @RemainingBytes / @DispatchOn / @DispatchValue / @DiscriminatorField annotations are
// preserved verbatim so KSP exercises the real consumer surface.
// Deleted in Phase 9 Step 7 once consumer cutover is verified.
package com.ditchoom.buffer.codec.test.consumer.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.annotations.DiscriminatorField
import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.PacketTypeRange
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.When
import com.ditchoom.buffer.codec.test.consumer.mqtt.MalformedPacketException
import com.ditchoom.buffer.codec.test.consumer.mqtt.MqttWarning
import com.ditchoom.buffer.codec.test.consumer.mqtt.ProtocolError
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.ControlPacket
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.IDisconnectNotification
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.IPingRequest
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.IPingResponse
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.IPublishComplete
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.IPublishReceived
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.IPublishRelease
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.ISubscription
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.MqttFixedHeader
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.PublishMessage
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.QualityOfService
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.QualityOfService.AT_LEAST_ONCE
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.QualityOfService.AT_MOST_ONCE
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.QualityOfService.EXACTLY_ONCE
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.TopicFilter
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.TopicName
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.WillConfig
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_0
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_1
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_2
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.format.ReasonCode.UNSPECIFIED_ERROR
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.validControlPacketIdentifierRange
import kotlin.jvm.JvmInline

// ── Wire-shape element types for list-payload packets ─────────────────────

@ProtocolMessage
data class SubscriptionEntry(
    @LengthPrefixed val filter: String,
    val qos: UByte,
) : ISubscription {
    override val topicFilter: TopicFilter get() = TopicFilter.fromOrThrow(filter)
    override val maximumQos: QualityOfService
        get() =
            QualityOfService.fromBooleans(
                qos.toInt().shr(1) and 1 == 1,
                qos.toInt() and 1 == 1,
            )
}

@ProtocolMessage
@JvmInline
value class SubAckReturnCode(
    val raw: UByte,
)

@ProtocolMessage
data class TopicFilterEntry(
    @LengthPrefixed val filter: String,
)

// ── CONNECT flags byte (§3.1.2.3) ─────────────────────────────────────────

@JvmInline
value class ConnectV4Flags(
    val raw: UByte,
) {
    val reserved: Boolean get() = raw.toInt() and 1 == 1
    val cleanSession: Boolean get() = (raw.toInt() shr 1) and 1 == 1
    val willFlag: Boolean get() = (raw.toInt() shr 2) and 1 == 1
    val willQos: Int get() = (raw.toInt() shr 3) and 3
    val willRetain: Boolean get() = (raw.toInt() shr 5) and 1 == 1
    val passwordFlag: Boolean get() = (raw.toInt() shr 6) and 1 == 1
    val usernameFlag: Boolean get() = (raw.toInt() shr 7) and 1 == 1

    companion object {
        fun from(
            cleanSession: Boolean = false,
            willFlag: Boolean = false,
            willQos: QualityOfService = AT_MOST_ONCE,
            willRetain: Boolean = false,
            hasPassword: Boolean = false,
            hasUserName: Boolean = false,
        ): ConnectV4Flags {
            val raw =
                (if (hasUserName) 0b10000000 else 0) or
                    (if (hasPassword) 0b1000000 else 0) or
                    (if (willRetain) 0b100000 else 0) or
                    (willQos.integerValue.toInt() shl 3) or
                    (if (willFlag) 0b100 else 0) or
                    (if (cleanSession) 0b10 else 0)
            return ConnectV4Flags(raw.toUByte())
        }
    }
}

// ── Sealed root ───────────────────────────────────────────────────────────

@DispatchOn(MqttFixedHeader::class)
@ProtocolMessage(onUnknownDiscriminator = "com.ditchoom.buffer.codec.test.consumer.mqtt.MalformedPacketException")
sealed interface ControlPacketV4 : ControlPacket {
    override val mqttVersion: Byte get() = 4
    override val controlPacketFactory: ControlPacketFactory get() = ControlPacketV4Factory
}

object ControlPacketV4Factory : ControlPacketFactory

// ── Reserved (wire 0x00) ───────────────────────────────────────────────────

@PacketType(wire = 0)
@ProtocolMessage
data object Reserved : ControlPacketV4 {
    override val controlPacketValue: Byte get() = 0
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
}

// ── CONNECT (§3.1) ────────────────────────────────────────────────────────

@PacketType(wire = 1)
@ProtocolMessage
data class ConnectionRequest<@Payload WP>(
    @DiscriminatorField val fixedHeader: MqttFixedHeader = MqttFixedHeader(0x10u),
    @LengthPrefixed override val protocolName: String = "MQTT",
    val protocolLevel: UByte = 4u,
    val connectFlags: ConnectV4Flags = ConnectV4Flags(0u),
    val keepAlive: UShort = UShort.MAX_VALUE,
    @LengthPrefixed val clientId: String = "",
    @When("connectFlags.willFlag") @LengthPrefixed val willTopicString: String? = null,
    @When("connectFlags.willFlag") @LengthPrefixed val willPayloadValue: WP? = null,
    @When("connectFlags.usernameFlag") @LengthPrefixed val username: String? = null,
    @When("connectFlags.passwordFlag") @LengthPrefixed override val password: String? = null,
) : ControlPacketV4,
    IConnectionRequest {
    init {
        if (fixedHeader.flags != 0) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for CONNECT must be 0x0, got 0x${fixedHeader.flags.toString(16)}",
            )
        }
        if (connectFlags.reserved) {
            throw MalformedPacketException(
                "Reserved flag in CONNECT Variable Header is set incorrectly to 1 (§3.1.2.3)",
            )
        }
        if (connectFlags.willQos == 3) {
            throw MalformedPacketException("Will QoS = 3 is a Malformed Packet (§3.1.2-14)")
        }
    }

    override val controlPacketValue: Byte get() = 1
    override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER

    override val clientIdentifier: String get() = clientId
    override val keepAliveTimeoutSeconds: UShort get() = keepAlive
    override val protocolVersion: Int get() = protocolLevel.toInt()
    override val cleanStart: Boolean get() = connectFlags.cleanSession
    override val hasUserName: Boolean get() = connectFlags.usernameFlag
    override val hasPassword: Boolean get() = connectFlags.passwordFlag
    override val userName: String? get() = username

    override val will: WillConfig
        get() {
            val payload = willPayloadValue
            return if (
                connectFlags.willFlag &&
                willTopicString != null &&
                payload is ReadBuffer
            ) {
                WillConfig.Enabled(
                    TopicName.fromOrThrow(willTopicString),
                    payload as ReadBuffer,
                    QualityOfService.fromBooleans(
                        (connectFlags.willQos shr 1) and 1 == 1,
                        connectFlags.willQos and 1 == 1,
                    ),
                    connectFlags.willRetain,
                )
            } else {
                WillConfig.Disabled
            }
        }

    override fun validate(): MqttWarning? {
        if (connectFlags.willFlag &&
            (willPayloadValue == null || willTopicString == null)
        ) {
            return MqttWarning(
                "[MQTT-3.1.2-9]",
                "Will Flag set but topic/payload missing",
            )
        }
        return null
    }
}

// ── CONNACK (§3.2) ────────────────────────────────────────────────────────

@PacketType(wire = 2)
@ProtocolMessage
data class ConnectionAcknowledgment(
    @DiscriminatorField val fixedHeader: MqttFixedHeader = MqttFixedHeader(0x20u),
    val acknowledgeFlags: UByte = 0u,
    val returnCode: UByte = 0u,
) : ControlPacketV4,
    IConnectionAcknowledgment {
    init {
        if (fixedHeader.flags != 0) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for CONNACK must be 0x0, got 0x${fixedHeader.flags.toString(16)}",
            )
        }
        if ((acknowledgeFlags.toInt() and 0xFE) != 0) {
            throw MalformedPacketException(
                "CONNACK Acknowledge Flags reserved bits 1-7 must be 0 (§3.2.2.1)",
            )
        }
    }

    override val controlPacketValue: Byte get() = 2
    override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT
    override val sessionPresent: Boolean get() = (acknowledgeFlags.toInt() and 0x01) == 1
    override val isSuccessful: Boolean get() = returnCode == 0u.toUByte()
    override val connectionReason: String get() = "code=$returnCode"
}

// ── PUBLISH (§3.3) ────────────────────────────────────────────────────────

@PacketTypeRange(0x30, 0x3F)
@ProtocolMessage
data class PublishMessageV4<@Payload P>(
    @DiscriminatorField val header: MqttFixedHeader,
    @LengthPrefixed val topicName: String,
    @When("header.publishHasPacketIdentifier") val packetId: UShort? = null,
    @RemainingBytes val payload: P,
) : ControlPacketV4,
    PublishMessage {
    init {
        if (header.publishQos == 3) {
            throw MalformedPacketException(
                "[MQTT-3.3.1-4] PUBLISH MUST NOT have both QoS bits set to 1.",
            )
        }
    }

    override val controlPacketValue: Byte get() = PublishMessage.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
    override val flags: Byte get() = (header.raw.toInt() and 0x0F).toByte()

    override val topic: TopicName get() = TopicName.fromOrThrow(topicName)
    override val qualityOfService: QualityOfService
        get() =
            QualityOfService.fromBooleans(
                bit2 = header.publishQos and 0b10 == 0b10,
                bit1 = header.publishQos and 0b01 == 0b01,
            )
    override val dup: Boolean get() = header.publishDup
    override val retain: Boolean get() = header.publishRetain
    override val packetIdentifier: Int get() = packetId?.toInt() ?: NO_PACKET_ID

    override fun rawPayload(): ReadBuffer? = payload as? ReadBuffer

    override fun expectedResponse(
        reasonCode: ReasonCode,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ): ControlPacket? =
        when (qualityOfService) {
            AT_LEAST_ONCE -> PublishAcknowledgment(packetIdentifier.toUShort())
            EXACTLY_ONCE -> PublishReceived(packetIdentifier.toUShort())
            else -> null
        }

    override fun setDupFlagNewPubMessage(): PublishMessage {
        val rawByte = header.raw.toInt()
        return if (qualityOfService == AT_MOST_ONCE && dup) {
            copy(header = MqttFixedHeader((rawByte and 0xF7).toUByte()))
        } else if (qualityOfService != AT_MOST_ONCE && !dup) {
            copy(header = MqttFixedHeader((rawByte or 0x08).toUByte()))
        } else {
            this
        }
    }

    override fun maybeCopyWithNewPacketIdentifier(packetIdentifier: Int): PublishMessage =
        when (qualityOfService) {
            AT_MOST_ONCE -> this
            else -> copy(packetId = packetIdentifier.toUShort())
        }

    override fun validate(): MalformedPacketException? {
        val hasPid = packetId != null
        if (qualityOfService == AT_MOST_ONCE && hasPid &&
            packetIdentifier in validControlPacketIdentifierRange
        ) {
            return MalformedPacketException("[MQTT-2.3.1-5] QoS 0 must not have Packet Identifier")
        } else if (qualityOfService.isGreaterThan(AT_MOST_ONCE) &&
            (!hasPid || packetIdentifier !in validControlPacketIdentifierRange)
        ) {
            return MalformedPacketException("[MQTT-2.3.1-1] QoS > 0 requires non-zero Packet Identifier")
        }
        return null
    }
}

// ── PUBACK (§3.4) ─────────────────────────────────────────────────────────

@PacketType(wire = 4)
@ProtocolMessage
data class PublishAcknowledgment(
    @DiscriminatorField val header: MqttFixedHeader = MqttFixedHeader(0x40u),
    val packetId: UShort,
) : ControlPacketV4,
    IPublishAcknowledgment {
    constructor(packetId: UShort) : this(MqttFixedHeader(0x40u), packetId)

    init {
        if (header.flags != 0) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for PUBACK must be 0x0, got 0x${header.flags.toString(16)}",
            )
        }
    }

    override val packetIdentifier: Int get() = packetId.toInt()
    override val controlPacketValue: Byte get() = IPublishAcknowledgment.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
}

// ── PUBREC (§3.5) ─────────────────────────────────────────────────────────

@PacketType(wire = 5)
@ProtocolMessage
data class PublishReceived(
    @DiscriminatorField val header: MqttFixedHeader = MqttFixedHeader(0x50u),
    val packetId: UShort,
) : ControlPacketV4,
    IPublishReceived {
    constructor(packetId: UShort) : this(MqttFixedHeader(0x50u), packetId)

    init {
        if (header.flags != 0) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for PUBREC must be 0x0, got 0x${header.flags.toString(16)}",
            )
        }
    }

    override val packetIdentifier: Int get() = packetId.toInt()
    override val controlPacketValue: Byte get() = IPublishReceived.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
}

// ── PUBREL (§3.6) ─────────────────────────────────────────────────────────

@PacketType(wire = 6)
@ProtocolMessage
data class PublishRelease(
    @DiscriminatorField val header: MqttFixedHeader = MqttFixedHeader(0x62u),
    val packetId: UShort,
) : ControlPacketV4,
    IPublishRelease {
    constructor(packetId: UShort) : this(MqttFixedHeader(0x62u), packetId)

    init {
        if (header.flags != 0b10) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for PUBREL must be 0x2, got 0x${header.flags.toString(16)}",
            )
        }
    }

    override val packetIdentifier: Int get() = packetId.toInt()
    override val controlPacketValue: Byte get() = IPublishRelease.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
    override val flags: Byte get() = 0b10
}

// ── PUBCOMP (§3.7) ────────────────────────────────────────────────────────

@PacketType(wire = 7)
@ProtocolMessage
data class PublishComplete(
    @DiscriminatorField val header: MqttFixedHeader = MqttFixedHeader(0x70u),
    val packetId: UShort,
) : ControlPacketV4,
    IPublishComplete {
    constructor(packetId: UShort) : this(MqttFixedHeader(0x70u), packetId)

    init {
        if (header.flags != 0) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for PUBCOMP must be 0x0, got 0x${header.flags.toString(16)}",
            )
        }
    }

    override val packetIdentifier: Int get() = packetId.toInt()
    override val controlPacketValue: Byte get() = IPublishComplete.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
}

// ── SUBSCRIBE (§3.8) ──────────────────────────────────────────────────────

@PacketType(wire = 8)
@ProtocolMessage
data class SubscribeRequest(
    @DiscriminatorField val header: MqttFixedHeader = MqttFixedHeader(0x82u),
    val packetId: UShort,
    @RemainingBytes val entries: List<SubscriptionEntry>,
) : ControlPacketV4,
    ISubscribeRequest {
    init {
        if (header.flags != 0b10) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for SUBSCRIBE must be 0x2, got 0x${header.flags.toString(16)}",
            )
        }
    }

    override val packetIdentifier: Int get() = packetId.toInt()
    override val subscriptions: Set<ISubscription> get() = entries.toSet()
    override val controlPacketValue: Byte get() = ISubscribeRequest.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER
    override val flags: Byte get() = 0b10

    override fun copyWithNewPacketIdentifier(packetIdentifier: Int): ISubscribeRequest =
        copy(packetId = packetIdentifier.toUShort())
}

// ── SUBACK (§3.9) ─────────────────────────────────────────────────────────

@PacketType(wire = 9)
@ProtocolMessage
data class SubscribeAcknowledgement(
    @DiscriminatorField val header: MqttFixedHeader = MqttFixedHeader(0x90u),
    val packetId: UShort,
    @RemainingBytes val returnCodes: List<SubAckReturnCode>,
) : ControlPacketV4,
    ISubscribeAcknowledgement {
    init {
        if (header.flags != 0) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for SUBACK must be 0x0, got 0x${header.flags.toString(16)}",
            )
        }
    }

    override val packetIdentifier: Int get() = packetId.toInt()
    override val controlPacketValue: Byte get() = ISubscribeAcknowledgement.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT
}

// ── UNSUBSCRIBE (§3.10) ───────────────────────────────────────────────────

@PacketType(wire = 10)
@ProtocolMessage
data class UnsubscribeRequest(
    @DiscriminatorField val header: MqttFixedHeader = MqttFixedHeader(0xA2u),
    val packetId: UShort,
    @RemainingBytes val topicEntries: List<TopicFilterEntry>,
) : ControlPacketV4,
    IUnsubscribeRequest {
    init {
        if (header.flags != 0b10) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for UNSUBSCRIBE must be 0x2, got 0x${header.flags.toString(16)}",
            )
        }
        if (topicEntries.isEmpty()) {
            throw ProtocolError("An UNSUBSCRIBE packet with no Payload is a Protocol Error")
        }
    }

    override val packetIdentifier: Int get() = packetId.toInt()
    override val controlPacketValue: Byte get() = IUnsubscribeRequest.controlPacketValue
    override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER
    override val flags: Byte get() = 0b10

    override fun copyWithNewPacketIdentifier(packetIdentifier: Int): IUnsubscribeRequest =
        copy(packetId = packetIdentifier.toUShort())
}

// ── UNSUBACK (§3.11) ──────────────────────────────────────────────────────

@PacketType(wire = 11)
@ProtocolMessage
data class UnsubscribeAcknowledgment(
    @DiscriminatorField val header: MqttFixedHeader = MqttFixedHeader(0xB0u),
    val packetId: UShort,
) : ControlPacketV4,
    IUnsubscribeAcknowledgment {
    constructor(packetId: UShort) : this(MqttFixedHeader(0xB0u), packetId)

    init {
        if (header.flags != 0) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for UNSUBACK must be 0x0, got 0x${header.flags.toString(16)}",
            )
        }
    }

    override val packetIdentifier: Int get() = packetId.toInt()
    override val controlPacketValue: Byte get() = IUnsubscribeAcknowledgment.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT
}

// ── PINGREQ / PINGRESP / DISCONNECT (no body) ─────────────────────────────

@PacketType(wire = 12)
@ProtocolMessage
data object PingRequest : ControlPacketV4, IPingRequest {
    override val controlPacketValue: Byte get() = 12
    override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER
}

@PacketType(wire = 13)
@ProtocolMessage
data object PingResponse : ControlPacketV4, IPingResponse {
    override val controlPacketValue: Byte get() = 13
    override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT
}

@PacketType(wire = 14)
@ProtocolMessage
data object DisconnectNotification : ControlPacketV4, IDisconnectNotification {
    override val controlPacketValue: Byte get() = 14
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
}
