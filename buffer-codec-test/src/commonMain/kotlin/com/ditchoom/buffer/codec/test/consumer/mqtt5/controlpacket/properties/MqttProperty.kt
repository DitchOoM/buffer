// PHASE 9 FIXTURE — copied from mqtt/models-v5/src/commonMain/kotlin/com/ditchoom/mqtt5/controlpacket/properties/MqttProperty.kt
// Deleted in Phase 9 Step 7 once consumer cutover is verified.
package com.ditchoom.buffer.codec.test.consumer.mqtt5.controlpacket.properties

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.VariableByteInteger
import kotlin.jvm.JvmInline

@JvmInline
@ProtocolMessage
value class PropertyId(
    val raw: UByte,
) {
    @DispatchValue
    val id: Int get() = raw.toInt()
}

@DispatchOn(PropertyId::class)
@ProtocolMessage
sealed interface MqttProperty

// ── Boolean properties ───────────────────────────────────────────────────

@PacketType(wire = 0x01)
@ProtocolMessage
@JvmInline
value class PayloadFormatIndicator(val isUtf8: Boolean) : MqttProperty

@PacketType(wire = 0x17)
@ProtocolMessage
@JvmInline
value class RequestProblemInformation(val enabled: Boolean) : MqttProperty

@PacketType(wire = 0x19)
@ProtocolMessage
@JvmInline
value class RequestResponseInformation(val enabled: Boolean) : MqttProperty

@PacketType(wire = 0x24)
@ProtocolMessage
@JvmInline
value class MaximumQos(val qos1Allowed: Boolean) : MqttProperty

@PacketType(wire = 0x25)
@ProtocolMessage
@JvmInline
value class RetainAvailable(val supported: Boolean) : MqttProperty

@PacketType(wire = 0x28)
@ProtocolMessage
@JvmInline
value class WildcardSubscriptionAvailable(val supported: Boolean) : MqttProperty

@PacketType(wire = 0x29)
@ProtocolMessage
@JvmInline
value class SubscriptionIdentifierAvailable(val supported: Boolean) : MqttProperty

@PacketType(wire = 0x2A)
@ProtocolMessage
@JvmInline
value class SharedSubscriptionAvailable(val supported: Boolean) : MqttProperty

// ── UShort properties ────────────────────────────────────────────────────

@PacketType(wire = 0x21)
@ProtocolMessage
@JvmInline
value class ReceiveMaximum(val max: UShort) : MqttProperty

@PacketType(wire = 0x22)
@ProtocolMessage
@JvmInline
value class TopicAlias(val value: UShort) : MqttProperty

@PacketType(wire = 0x23)
@ProtocolMessage
@JvmInline
value class TopicAliasMaximum(val max: UShort) : MqttProperty

@PacketType(wire = 0x13)
@ProtocolMessage
@JvmInline
value class ServerKeepAlive(val seconds: UShort) : MqttProperty

// ── UInt properties ──────────────────────────────────────────────────────

@PacketType(wire = 0x02)
@ProtocolMessage
@JvmInline
value class MessageExpiryInterval(val seconds: UInt) : MqttProperty

@PacketType(wire = 0x11)
@ProtocolMessage
@JvmInline
value class SessionExpiryInterval(val seconds: UInt) : MqttProperty

@PacketType(wire = 0x18)
@ProtocolMessage
@JvmInline
value class WillDelayInterval(val seconds: UInt) : MqttProperty

@PacketType(wire = 0x27)
@ProtocolMessage
@JvmInline
value class MaximumPacketSize(val bytes: UInt) : MqttProperty

// ── String properties ────────────────────────────────────────────────────

@PacketType(wire = 0x03)
@ProtocolMessage
@JvmInline
value class ContentType(@LengthPrefixed val value: String) : MqttProperty

@PacketType(wire = 0x08)
@ProtocolMessage
@JvmInline
value class ResponseTopic(@LengthPrefixed val value: String) : MqttProperty

@PacketType(wire = 0x12)
@ProtocolMessage
@JvmInline
value class AssignedClientIdentifier(@LengthPrefixed val value: String) : MqttProperty

@PacketType(wire = 0x15)
@ProtocolMessage
@JvmInline
value class AuthenticationMethod(@LengthPrefixed val value: String) : MqttProperty

@PacketType(wire = 0x1A)
@ProtocolMessage
@JvmInline
value class ResponseInformation(@LengthPrefixed val value: String) : MqttProperty

@PacketType(wire = 0x1C)
@ProtocolMessage
@JvmInline
value class ServerReference(@LengthPrefixed val value: String) : MqttProperty

@PacketType(wire = 0x1F)
@ProtocolMessage
@JvmInline
value class ReasonString(@LengthPrefixed val value: String) : MqttProperty

// ── String pair ──────────────────────────────────────────────────────────

@PacketType(wire = 0x26)
@ProtocolMessage
data class UserProperty(
    @LengthPrefixed val key: String,
    @LengthPrefixed val value: String,
) : MqttProperty

// ── Variable byte integer property ───────────────────────────────────────

@PacketType(wire = 0x0B)
@ProtocolMessage
@JvmInline
value class SubscriptionIdentifier(
    @VariableByteInteger val value: Int,
) : MqttProperty

// ── Binary data properties (consumer-defined type via @Payload) ──────────

@PacketType(wire = 0x09)
@ProtocolMessage
data class CorrelationData<@Payload CD>(
    val length: UShort,
    @LengthFrom("length") val data: CD,
) : MqttProperty

@PacketType(wire = 0x16)
@ProtocolMessage
data class AuthenticationData<@Payload AD>(
    val length: UShort,
    @LengthFrom("length") val data: AD,
) : MqttProperty
