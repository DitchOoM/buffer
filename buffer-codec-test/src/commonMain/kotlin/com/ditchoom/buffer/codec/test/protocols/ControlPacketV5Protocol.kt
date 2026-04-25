package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.WhenRemaining
import com.ditchoom.buffer.codec.annotations.WhenTrue
import com.ditchoom.buffer.codec.test.annotations.PropertyBag
import kotlin.jvm.JvmInline

/**
 * MQTT v5 control-packet sealed tree (demonstration).
 *
 * Reuses [MqttFixedHeader] (top nibble = packet type, low nibble = flags) — same dispatcher
 * machinery as v4. Differences from v4 packets:
 *
 * - PUBACK / PUBREC / PUBREL / PUBCOMP / UNSUBACK / DISCONNECT all gain optional trailing
 *   `reasonCode: UByte?` and a `Map<Int, Int>?` property bag, gated by `@WhenRemaining(1)`.
 *   When `remainingLength` is 2 (just packetId) the reason code defaults to SUCCESS and
 *   properties are absent on the wire. The cascading null contract is enforced by the
 *   codec processor.
 *
 * - SUBACK return-codes become reason codes (still single byte, different meanings) — same
 *   wire shape as v4 so the existing `List<V4SubAckReturnCode>` continues to work, just
 *   wrapped here as [V5ReasonCode] aliasing.
 *
 * - AUTH (packet type 15, wire 0xF0) is new in v5: `reasonCode: UByte?` + properties.
 *
 * - The property bag is modeled with the existing test-only `@PropertyBag Map<Int, Int>`
 *   helper. Production migration to `mqtt-models-v5` will substitute the typed
 *   `MqttProperty` sealed (already declared in
 *   `models-v5/src/commonMain/kotlin/com/ditchoom/mqtt5/controlpacket/properties/`).
 *
 * - PUBLISH carries the same fixed-header self-encoding pattern as v4, plus a property
 *   bag inserted between `topicName`/`packetIdentifier` and `payload`.
 *
 * - CONNECT/CONNACK have rich v5 additions (will properties, server reference,
 *   assigned-client-id, etc.) — out of scope for this minimal demo; see the v5 plan doc.
 */
@JvmInline
@ProtocolMessage
value class V5ReasonCode(
    val raw: UByte,
) {
    companion object {
        val SUCCESS = V5ReasonCode(0x00u)
        val NORMAL_DISCONNECTION = V5ReasonCode(0x00u)
        val NO_MATCHING_SUBSCRIBERS = V5ReasonCode(0x10u)
        val UNSPECIFIED_ERROR = V5ReasonCode(0x80u)
        val NOT_AUTHORIZED = V5ReasonCode(0x87u)
        val PACKET_IDENTIFIER_NOT_FOUND = V5ReasonCode(0x92u)
        val CONTINUE_AUTHENTICATION = V5ReasonCode(0x18u)
        val REAUTHENTICATE = V5ReasonCode(0x19u)
    }
}

@DispatchOn(MqttFixedHeader::class)
@ProtocolMessage
sealed interface ControlPacketV5 {
    @PacketType(value = 4, wire = 0x40)
    @ProtocolMessage
    data class PubAck(
        val packetIdentifier: UShort,
        @WhenRemaining(1) val reasonCode: V5ReasonCode? = null,
        @WhenRemaining(1) @PropertyBag val properties: Map<Int, Int>? = null,
    ) : ControlPacketV5

    @PacketType(value = 5, wire = 0x50)
    @ProtocolMessage
    data class PubRec(
        val packetIdentifier: UShort,
        @WhenRemaining(1) val reasonCode: V5ReasonCode? = null,
        @WhenRemaining(1) @PropertyBag val properties: Map<Int, Int>? = null,
    ) : ControlPacketV5

    @PacketType(value = 6, wire = 0x62)
    @ProtocolMessage
    data class PubRel(
        val packetIdentifier: UShort,
        @WhenRemaining(1) val reasonCode: V5ReasonCode? = null,
        @WhenRemaining(1) @PropertyBag val properties: Map<Int, Int>? = null,
    ) : ControlPacketV5

    @PacketType(value = 7, wire = 0x70)
    @ProtocolMessage
    data class PubComp(
        val packetIdentifier: UShort,
        @WhenRemaining(1) val reasonCode: V5ReasonCode? = null,
        @WhenRemaining(1) @PropertyBag val properties: Map<Int, Int>? = null,
    ) : ControlPacketV5

    @PacketType(value = 11, wire = 0xB0)
    @ProtocolMessage
    data class UnsubAck(
        val packetIdentifier: UShort,
        @WhenRemaining(1) val reasonCode: V5ReasonCode? = null,
        @WhenRemaining(1) @PropertyBag val properties: Map<Int, Int>? = null,
    ) : ControlPacketV5

    @PacketType(value = 12, wire = 0xC0)
    @ProtocolMessage
    data object PingReq : ControlPacketV5

    @PacketType(value = 13, wire = 0xD0)
    @ProtocolMessage
    data object PingResp : ControlPacketV5

    @PacketType(value = 14, wire = 0xE0)
    @ProtocolMessage
    data class Disconnect(
        @WhenRemaining(1) val reasonCode: V5ReasonCode? = null,
        @WhenRemaining(1) @PropertyBag val properties: Map<Int, Int>? = null,
    ) : ControlPacketV5

    @PacketType(value = 15, wire = 0xF0)
    @ProtocolMessage
    data class Auth(
        @WhenRemaining(1) val reasonCode: V5ReasonCode? = null,
        @WhenRemaining(1) @PropertyBag val properties: Map<Int, Int>? = null,
    ) : ControlPacketV5

    @PacketType(value = 3)
    @ProtocolMessage
    data class Publish<@Payload P>(
        val header: MqttFixedHeader,
        @LengthPrefixed val topicName: String,
        @WhenTrue("header.publishHasPacketIdentifier") val packetIdentifier: UShort? = null,
        @PropertyBag val properties: Map<Int, Int>,
        @RemainingBytes val payload: P,
    ) : ControlPacketV5
}
