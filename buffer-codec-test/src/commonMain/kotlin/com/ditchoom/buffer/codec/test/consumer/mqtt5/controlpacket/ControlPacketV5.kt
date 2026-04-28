// PHASE 9 FIXTURE — copied from mqtt/models-v5/src/commonMain/kotlin/com/ditchoom/mqtt5/controlpacket/ControlPacketV5.kt
// References to generated codec classes (ControlPacketV5*Codec, etc.) and convenience
// constructors / typed-property helpers / typealiases are removed. Wire-shape data
// classes, init validation, sealed tree, and codec annotations are preserved verbatim
// so KSP exercises the real consumer surface.
// Deleted in Phase 9 Step 7 once consumer cutover is verified.
package com.ditchoom.buffer.codec.test.consumer.mqtt5.controlpacket

import com.ditchoom.buffer.codec.annotations.DiscriminatorField
import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.LengthPrefix
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.PacketTypeRange
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.When
import com.ditchoom.buffer.codec.test.consumer.mqtt.MalformedPacketException
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
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.TopicName
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.buffer.codec.test.consumer.mqtt5.controlpacket.properties.MqttProperty
import kotlin.jvm.JvmInline

// ── Wire-shape element types for list-payload packets ─────────────────────

@ProtocolMessage
data class SubscriptionV5Entry(
    @LengthPrefixed val topicFilter: String,
    val subscriptionOptions: UByte,
)

@ProtocolMessage
data class TopicFilterV5Entry(
    @LengthPrefixed val topicFilter: String,
)

@JvmInline
@ProtocolMessage
value class SubAckReasonCodeV5(
    val raw: UByte,
)

@JvmInline
@ProtocolMessage
value class UnsubAckReasonCodeV5(
    val raw: UByte,
)

@JvmInline
value class ConnectFlagsV5(
    val raw: UByte,
) {
    val reserved: Boolean get() = raw.toInt() and 1 == 1
    val cleanStart: Boolean get() = (raw.toInt() shr 1) and 1 == 1
    val willFlag: Boolean get() = (raw.toInt() shr 2) and 1 == 1
    val willQosBit1: Boolean get() = (raw.toInt() shr 3) and 1 == 1
    val willQosBit2: Boolean get() = (raw.toInt() shr 4) and 1 == 1
    val willQos: Int get() = (raw.toInt() shr 3) and 3
    val willRetain: Boolean get() = (raw.toInt() shr 5) and 1 == 1
    val passwordFlag: Boolean get() = (raw.toInt() shr 6) and 1 == 1
    val usernameFlag: Boolean get() = (raw.toInt() shr 7) and 1 == 1

    companion object {
        fun from(
            cleanStart: Boolean = false,
            willFlag: Boolean = false,
            willQos: QualityOfService = QualityOfService.AT_MOST_ONCE,
            willRetain: Boolean = false,
            hasPassword: Boolean = false,
            hasUserName: Boolean = false,
        ): ConnectFlagsV5 {
            val raw =
                (if (hasUserName) 0b10000000 else 0) or
                    (if (hasPassword) 0b1000000 else 0) or
                    (if (willRetain) 0b100000 else 0) or
                    (willQos.integerValue.toInt() shl 3) or
                    (if (willFlag) 0b100 else 0) or
                    (if (cleanStart) 0b10 else 0)
            return ConnectFlagsV5(raw.toUByte())
        }
    }
}

// ── Sealed root ────────────────────────────────────────────────────────────

@DispatchOn(MqttFixedHeader::class)
@ProtocolMessage(onUnknownDiscriminator = "com.ditchoom.buffer.codec.test.consumer.mqtt.MalformedPacketException")
sealed interface ControlPacketV5 : ControlPacket {
    override val mqttVersion: Byte get() = 5
    override val controlPacketFactory: ControlPacketFactory get() = ControlPacketV5Factory

    @PacketType(wire = 1)
    @ProtocolMessage
    data class Connect<@Payload WP>(
        @DiscriminatorField val header: MqttFixedHeader = MqttFixedHeader(0x10u),
        @LengthPrefixed override val protocolName: String,
        val protocolLevel: UByte,
        val connectFlags: ConnectFlagsV5,
        val keepAlive: UShort,
        @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty> = emptyList(),
        @LengthPrefixed val clientId: String,
        @When(
            "connectFlags.willFlag",
        ) @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val willProperties: List<MqttProperty>? = null,
        @When("connectFlags.willFlag") @LengthPrefixed val willTopicString: String? = null,
        @When("connectFlags.willFlag") @LengthPrefixed val willPayloadValue: WP? = null,
        @When("connectFlags.usernameFlag") @LengthPrefixed val username: String? = null,
        @When("connectFlags.passwordFlag") @LengthPrefixed override val password: String? = null,
    ) : ControlPacketV5,
        IConnectionRequest {
        init {
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for CONNECT must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
            if (connectFlags.reserved) {
                throw MalformedPacketException(
                    "Reserved flag in CONNECT Variable Header is set incorrectly to 1 (§3.1.2.3)",
                )
            }
            if (connectFlags.willQos == 3) {
                throw MalformedPacketException("Will QoS = 3 is a Malformed Packet (§3.1.2-12)")
            }
            if (!connectFlags.willFlag && connectFlags.willQos != 0) {
                throw MalformedPacketException("[MQTT-3.1.2-11] Will QoS must be 0 when Will Flag is 0")
            }
            if (!connectFlags.willFlag && connectFlags.willRetain) {
                throw MalformedPacketException("[MQTT-3.1.2-13] Will Retain must be 0 when Will Flag is 0")
            }
        }

        override val controlPacketValue: Byte get() = 1
        override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER
        override val clientIdentifier: String get() = clientId
        override val keepAliveTimeoutSeconds: UShort get() = keepAlive
        override val protocolVersion: Int get() = protocolLevel.toInt()
        override val cleanStart: Boolean get() = connectFlags.cleanStart
        override val hasUserName: Boolean get() = connectFlags.usernameFlag
        override val hasPassword: Boolean get() = connectFlags.passwordFlag
        override val userName: String? get() = username

        override val will: com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.WillConfig
            get() {
                val payload = willPayloadValue
                return if (
                    connectFlags.willFlag &&
                    willTopicString != null &&
                    payload is com.ditchoom.buffer.ReadBuffer
                ) {
                    com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.WillConfig.Enabled(
                        TopicName.fromOrThrow(willTopicString),
                        payload as com.ditchoom.buffer.ReadBuffer,
                        QualityOfService.fromBooleans(connectFlags.willQosBit2, connectFlags.willQosBit1),
                        connectFlags.willRetain,
                    )
                } else {
                    com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.WillConfig.Disabled
                }
            }
    }

    @PacketType(wire = 2)
    @ProtocolMessage
    data class ConnAck(
        @DiscriminatorField val header: MqttFixedHeader = MqttFixedHeader(0x20u),
        val acknowledgeFlags: UByte,
        val connectReasonCode: UByte,
        @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty> = emptyList(),
    ) : ControlPacketV5,
        IConnectionAcknowledgment {
        init {
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for CONNACK must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
            require((acknowledgeFlags.toInt() and 0xFE) == 0) {
                "CONNACK Acknowledge Flags reserved bits 1-7 must be 0 (§3.2.2.1)"
            }
            if (connectReasonCode != ReasonCode.SUCCESS.byte) {
                require((acknowledgeFlags.toInt() and 0x01) == 0) {
                    "CONNACK with non-success reason code MUST have sessionPresent=0 (§3.2.2-6)"
                }
            }
        }

        override val controlPacketValue: Byte get() = 2
        override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT
        override val sessionPresent: Boolean get() = (acknowledgeFlags.toInt() and 0x01) == 1
        override val isSuccessful: Boolean get() = connectReasonCode == ReasonCode.SUCCESS.byte
        override val connectionReason: String get() = "code=$connectReasonCode"
    }

    @PacketTypeRange(0x30, 0x3F)
    @ProtocolMessage
    data class Publish<@Payload P>(
        @DiscriminatorField val header: MqttFixedHeader,
        @LengthPrefixed val topicName: String,
        @When("header.publishHasPacketIdentifier") val packetId: UShort? = null,
        @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty> = emptyList(),
        @RemainingBytes val payload: P,
    ) : ControlPacketV5,
        PublishMessage {
        init {
            if (header.publishQos == 3) {
                throw MalformedPacketException(
                    "[MQTT-3.3.1-4] PUBLISH MUST NOT have both QoS bits set to 1.",
                )
            }
        }

        override val controlPacketValue: Byte get() = 3
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

        override fun rawPayload(): com.ditchoom.buffer.ReadBuffer? = payload as? com.ditchoom.buffer.ReadBuffer

        override fun expectedResponse(
            reasonCode: ReasonCode,
            reasonString: String?,
            userProperty: List<Pair<String, String>>,
        ): ControlPacket? = null

        override fun setDupFlagNewPubMessage(): PublishMessage {
            val rawByte = header.raw.toInt()
            return if (qualityOfService == QualityOfService.AT_MOST_ONCE && dup) {
                copy(header = MqttFixedHeader((rawByte and 0xF7).toUByte()))
            } else if (qualityOfService != QualityOfService.AT_MOST_ONCE && !dup) {
                copy(header = MqttFixedHeader((rawByte or 0x08).toUByte()))
            } else {
                this
            }
        }

        override fun maybeCopyWithNewPacketIdentifier(packetIdentifier: Int): PublishMessage =
            when (qualityOfService) {
                QualityOfService.AT_MOST_ONCE -> this
                else -> copy(packetId = packetIdentifier.toUShort())
            }
    }

    @PacketType(wire = 4)
    @ProtocolMessage
    data class PubAck(
        @DiscriminatorField val header: MqttFixedHeader = MqttFixedHeader(0x40u),
        val packetId: UShort,
        @When("remaining >= 1") val reasonCode: UByte? = null,
        @When("remaining >= 1") @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty>? = null,
    ) : ControlPacketV5,
        IPublishAcknowledgment {
        init {
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for PUBACK must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
            require(properties == null || reasonCode != null) {
                "PUBACK properties cannot be present without a reason code"
            }
        }

        override val controlPacketValue: Byte get() = 4
        override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
        override val packetIdentifier: Int get() = packetId.toInt()
    }

    @PacketType(wire = 5)
    @ProtocolMessage
    data class PubRec(
        @DiscriminatorField val header: MqttFixedHeader = MqttFixedHeader(0x50u),
        val packetId: UShort,
        @When("remaining >= 1") val reasonCode: UByte? = null,
        @When("remaining >= 1") @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty>? = null,
    ) : ControlPacketV5,
        IPublishReceived {
        init {
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for PUBREC must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
            require(properties == null || reasonCode != null) {
                "PUBREC properties cannot be present without a reason code"
            }
        }

        override val controlPacketValue: Byte get() = 5
        override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
        override val packetIdentifier: Int get() = packetId.toInt()
    }

    @PacketType(wire = 6)
    @ProtocolMessage
    data class PubRel(
        @DiscriminatorField val header: MqttFixedHeader = MqttFixedHeader(0x62u),
        val packetId: UShort,
        @When("remaining >= 1") val reasonCode: UByte? = null,
        @When("remaining >= 1") @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty>? = null,
    ) : ControlPacketV5,
        IPublishRelease {
        init {
            if (header.flags != 0b10) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for PUBREL must be 0x2, got 0x${header.flags.toString(16)}",
                )
            }
            require(properties == null || reasonCode != null) {
                "PUBREL properties cannot be present without a reason code"
            }
        }

        override val controlPacketValue: Byte get() = 6
        override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
        override val flags: Byte get() = 0b10
        override val packetIdentifier: Int get() = packetId.toInt()
    }

    @PacketType(wire = 7)
    @ProtocolMessage
    data class PubComp(
        @DiscriminatorField val header: MqttFixedHeader = MqttFixedHeader(0x70u),
        val packetId: UShort,
        @When("remaining >= 1") val reasonCode: UByte? = null,
        @When("remaining >= 1") @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty>? = null,
    ) : ControlPacketV5,
        IPublishComplete {
        init {
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for PUBCOMP must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
            require(properties == null || reasonCode != null) {
                "PUBCOMP properties cannot be present without a reason code"
            }
        }

        override val controlPacketValue: Byte get() = 7
        override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
        override val packetIdentifier: Int get() = packetId.toInt()
    }

    @PacketType(wire = 12)
    @ProtocolMessage
    data class PingReq(
        @DiscriminatorField val header: MqttFixedHeader = MqttFixedHeader(0xC0u),
    ) : ControlPacketV5,
        IPingRequest {
        init {
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for PINGREQ must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
        }
        override val controlPacketValue: Byte get() = 12
        override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER
    }

    @PacketType(wire = 13)
    @ProtocolMessage
    data class PingResp(
        @DiscriminatorField val header: MqttFixedHeader = MqttFixedHeader(0xD0u),
    ) : ControlPacketV5,
        IPingResponse {
        init {
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for PINGRESP must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
        }
        override val controlPacketValue: Byte get() = 13
        override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT
    }

    @PacketType(wire = 14)
    @ProtocolMessage
    data class Disconnect(
        @DiscriminatorField val header: MqttFixedHeader,
        @When("remaining >= 1") val reasonCode: UByte? = null,
        @When("remaining >= 1") @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty>? = null,
    ) : ControlPacketV5,
        IDisconnectNotification {
        init {
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for DISCONNECT must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
            require(properties == null || reasonCode != null) {
                "DISCONNECT properties cannot be present without a reason code"
            }
        }

        override val controlPacketValue: Byte get() = 14
        override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
    }

    @PacketType(wire = 15)
    @ProtocolMessage
    data class Auth(
        @DiscriminatorField val header: MqttFixedHeader,
        @When("remaining >= 1") val reasonCode: UByte? = null,
        @When("remaining >= 1") @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty>? = null,
    ) : ControlPacketV5 {
        init {
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for AUTH must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
            require(properties == null || reasonCode != null) {
                "AUTH properties cannot be present without a reason code"
            }
        }

        override val controlPacketValue: Byte get() = 15
        override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
    }

    @PacketType(wire = 8)
    @ProtocolMessage
    data class Subscribe(
        @DiscriminatorField val header: MqttFixedHeader = MqttFixedHeader(0x82u),
        val packetId: UShort,
        @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty> = emptyList(),
        @RemainingBytes val subscriptionEntries: List<SubscriptionV5Entry>,
    ) : ControlPacketV5,
        ISubscribeRequest {
        init {
            if (header.flags != 0b10) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for SUBSCRIBE must be 0x2, got 0x${header.flags.toString(16)}",
                )
            }
            require(subscriptionEntries.isNotEmpty()) {
                "SUBSCRIBE payload must contain at least one Topic Filter (Protocol Error §3.8.3)"
            }
            for (entry in subscriptionEntries) {
                val opts = entry.subscriptionOptions.toInt()
                require(opts shr 6 == 0) { "Subscription Options reserved bits 6-7 must be 0" }
                val rh = (opts shr 4) and 0x3
                require(rh != 3) { "Retain Handling value 3 is a Protocol Error" }
                val q = opts and 0x3
                require(q != 3) { "Maximum QoS field value 3 is a Protocol Error" }
            }
        }

        override val controlPacketValue: Byte get() = 8
        override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER
        override val flags: Byte get() = 0b10
        override val packetIdentifier: Int get() = packetId.toInt()

        override val subscriptions: Set<ISubscription> get() = emptySet()

        override fun copyWithNewPacketIdentifier(packetIdentifier: Int): ISubscribeRequest =
            copy(packetId = packetIdentifier.toUShort())
    }

    @PacketType(wire = 9)
    @ProtocolMessage
    data class SubAck(
        @DiscriminatorField val header: MqttFixedHeader = MqttFixedHeader(0x90u),
        val packetId: UShort,
        @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty> = emptyList(),
        @RemainingBytes val reasonCodeEntries: List<SubAckReasonCodeV5>,
    ) : ControlPacketV5,
        ISubscribeAcknowledgement {
        init {
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for SUBACK must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
            require(reasonCodeEntries.isNotEmpty()) {
                "SUBACK payload must contain at least one Reason Code"
            }
        }

        override val controlPacketValue: Byte get() = 9
        override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT
        override val packetIdentifier: Int get() = packetId.toInt()
    }

    @PacketType(wire = 10)
    @ProtocolMessage
    data class Unsubscribe(
        @DiscriminatorField val header: MqttFixedHeader = MqttFixedHeader(0xA2u),
        val packetId: UShort,
        @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty> = emptyList(),
        @RemainingBytes val topicEntries: List<TopicFilterV5Entry>,
    ) : ControlPacketV5,
        IUnsubscribeRequest {
        init {
            if (header.flags != 0b10) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for UNSUBSCRIBE must be 0x2, got 0x${header.flags.toString(16)}",
                )
            }
            if (topicEntries.isEmpty()) {
                throw ProtocolError("UNSUBSCRIBE payload must contain at least one Topic Filter")
            }
        }

        override val controlPacketValue: Byte get() = 10
        override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER
        override val flags: Byte get() = 0b10
        override val packetIdentifier: Int get() = packetId.toInt()

        override fun copyWithNewPacketIdentifier(packetIdentifier: Int): IUnsubscribeRequest =
            copy(packetId = packetIdentifier.toUShort())
    }

    @PacketType(wire = 11)
    @ProtocolMessage
    data class UnsubAck(
        @DiscriminatorField val header: MqttFixedHeader = MqttFixedHeader(0xB0u),
        val packetId: UShort,
        @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty> = emptyList(),
        @RemainingBytes val reasonCodeEntries: List<UnsubAckReasonCodeV5>,
    ) : ControlPacketV5,
        IUnsubscribeAcknowledgment {
        init {
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for UNSUBACK must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
            if (reasonCodeEntries.isEmpty()) {
                throw ProtocolError("UNSUBACK must contain at least one reason code")
            }
        }

        override val controlPacketValue: Byte get() = 11
        override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT
        override val packetIdentifier: Int get() = packetId.toInt()
    }
}

object ControlPacketV5Factory : ControlPacketFactory
