package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryData
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec
import kotlin.jvm.JvmInline

/**
 * MQTT v5.0 §2.2.2.2 Property Identifier — a single byte that names a
 * property variant. Modeled as a `@JvmInline value class` over `UByte`
 * carrying the byte verbatim, with the `@DispatchValue` exposed as `Int`
 * so the codec emitter can route the sealed parent on it. Mirrors the
 * `MqttFixedHeader` pattern (Stage F slice 6 doctrine vector).
 */
@JvmInline
@ProtocolMessage
value class MqttV5PropertyId(
    val raw: UByte,
) {
    @DispatchValue
    val id: Int get() = raw.toInt()
}

/**
 * Phase J.M.5 audit-2e — `@DispatchValue` byte invariant. The variant's
 * primary-constructor `id` field defaults to the variant's
 * `@PacketType.value`, but Kotlin lets callers override the default with
 * a non-matching byte:
 *
 * ```kotlin
 * MqttV5Property.MessageExpiryInterval(
 *     id = MqttV5PropertyId(0xFFu),  // bogus
 *     seconds = 60u,
 * )
 * ```
 *
 * That construction encodes the wrong dispatch byte; on decode the
 * dispatcher fails to find a matching variant. This helper fires the
 * `init { require }` at construction time, closing the impossible
 * state caller-side. Same pattern as audit-2d's `MqttFixedHeader`
 * raw-byte invariant on each `MqttV5Packet` variant.
 *
 * Variant-codec emit reads/writes the byte through the value-class
 * scalar path; structural enforcement (drop the field from the primary
 * constructor) would need an emitter widening — deferred.
 */
private fun requireMatchingPropertyId(
    id: MqttV5PropertyId,
    expected: Int,
    variantName: String,
) {
    require(id.raw.toInt() == expected) {
        "v5 property $variantName id-byte invariant: id.raw must be 0x" +
            expected.toString(16).padStart(2, '0') +
            ", got 0x" + id.raw.toString(16) + " (spec §2.2.2.2)"
    }
}

/**
 * Typed MQTT v5.0 property dispatcher. v5 §2.2.2 defines ~30 properties;
 * Phase J.M.5 slice 2 landed two (MessageExpiryInterval, ContentType)
 * as a smoke test, and Phase J.M.5 slice 10 (Tier A) lands the
 * remaining non-VBI / non-binary variants — covering scalar, boolean
 * (0/1-validated UByte), single-LP-string, and two-LP-string (User
 * Property) shapes.
 *
 * Slice 13 added 0x0B SubscriptionIdentifier via the VBI scalar codec
 * (`@UseCodec(VariableByteIntegerCodec)`). Slice 15c adds 0x09
 * CorrelationData and 0x16 AuthenticationData via the new
 * `@LengthPrefixed @UseCodec val: T : Payload` shape (slice 15a).
 * No property variants stay deferred.
 *
 * Each variant carries the property-id byte as its first field; the
 * dispatcher peeks the byte, extracts `id`, matches `@PacketType.value`,
 * and delegates to the variant codec which re-reads the byte through the
 * value-class scalar path. Same pattern as [MqttFixedHeader] /
 * [com.ditchoom.buffer.codec.test.protocols.mqtt.MqttPacket].
 *
 * Boolean-shaped properties (PayloadFormatIndicator,
 * RequestProblemInformation, RequestResponseInformation, MaximumQoS,
 * RetainAvailable, WildcardSubscriptionAvailable,
 * SubscriptionIdentifiersAvailable, SharedSubscriptionAvailable) carry
 * a UByte on the wire but are semantically 0/1; an `init { require }`
 * block forecloses construction with values outside that range. v5
 * peers that violate this should be rejected by the caller (validator
 * gap addressed by the v5-handwritten-audit Gap B finding).
 */
@DispatchOn(MqttV5PropertyId::class)
@ProtocolMessage(wireOrder = Endianness.Big)
sealed interface MqttV5Property {
    /**
     * MQTT v5.0 §3.3.2.3.2 Payload Format Indicator (PUBLISH property,
     * Will properties). 0x00 = unspecified bytes; 0x01 = UTF-8.
     * Boolean-shaped — values outside `0..1` are protocol errors.
     */
    @PacketType(value = 0x01)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class PayloadFormatIndicator(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x01u),
        val value: UByte,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x01, "PayloadFormatIndicator")
            require(value in 0u..1u) { "PayloadFormatIndicator must be 0 or 1; got $value" }
        }
    }

    /**
     * MQTT v5.0 §3.3.2.3.3 Message Expiry Interval (PUBLISH property).
     * Wire shape: `02 <expiry_be_4>` (1-byte id + 4-byte BE UInt).
     */
    @PacketType(value = 0x02)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class MessageExpiryInterval(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x02u),
        val seconds: UInt,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x02, "MessageExpiryInterval")
        }
    }

    /**
     * MQTT v5.0 §3.3.2.3.9 Content Type (PUBLISH property). Wire shape:
     * `03 <len_be_2> <utf8_bytes...>`. The UTF-8 string carries the
     * MIME-style content type describing the PUBLISH payload.
     */
    @PacketType(value = 0x03)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class ContentType(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x03u),
        @LengthPrefixed val value: String,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x03, "ContentType")
        }
    }

    /**
     * MQTT v5.0 §3.3.2.3.5 Response Topic (PUBLISH property). Topic
     * name for a request/response style PUBLISH.
     */
    @PacketType(value = 0x08)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class ResponseTopic(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x08u),
        @LengthPrefixed val value: String,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x08, "ResponseTopic")
        }
    }

    /**
     * MQTT v5.0 §3.3.2.3.6 Correlation Data (PUBLISH property, also
     * Will properties §3.1.3.2.7). Opaque binary blob the publisher
     * uses to correlate a request with its response in a request/
     * response style PUBLISH; the broker forwards it unchanged to
     * subscribers.
     *
     * Phase J.M.5 slice 15c — first production-shaped use of the new
     * `@LengthPrefixed @UseCodec val: T : Payload` (slice 15a) shape.
     * The wire form is v5 §1.5.6 Binary Data: 2-byte UShort BE prefix
     * + body bytes. [BinaryData] is a `Payload`-marked value class
     * over `ByteArray` (slice 15 D1/D2); [BinaryDataCodec] is the
     * `Codec<BinaryData>` referenced via `@UseCodec`.
     */
    @PacketType(value = 0x09)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class CorrelationData(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x09u),
        @LengthPrefixed @UseCodec(BinaryDataCodec::class) val data: BinaryData,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x09, "CorrelationData")
        }
    }

    /**
     * MQTT v5.0 §3.8.2.1.2 Subscription Identifier (SUBSCRIBE property,
     * also forwarded on PUBLISH per §3.3.2.3.8). Variable-byte-integer
     * body, range 1..268_435_455 (0 is reserved). Multiple identifiers
     * may be carried on a PUBLISH if the message matched several
     * subscriptions; the SUBSCRIBE form is single-valued.
     *
     * Phase J.M.5 slice 13 lights up VBI-bodied properties via a
     * non-bounding `Codec<UInt>` ([VariableByteIntegerCodec]) on the
     * bare-`@UseCodec val: <scalar>` path. Distinct from
     * `MqttRemainingLengthCodec` — the remaining-length codec
     * implements `BoundingLengthCodec<UInt>` because its decoded value
     * narrows the buffer for subsequent fields; this property's value
     * is just data, no buffer narrowing.
     */
    @PacketType(value = 0x0B)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class SubscriptionIdentifier(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x0Bu),
        @UseCodec(VariableByteIntegerCodec::class) val value: UInt,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x0B, "SubscriptionIdentifier")
            // [MQTT-3.8.2.1.2-1] — value 0 is reserved.
            require(value > 0u) {
                "SubscriptionIdentifier value must be > 0; got $value (spec §3.8.2.1.2)"
            }
            // [MQTT-3.8.2.1.2-1] — VBI max is 0x0FFF_FFFF (268_435_455).
            require(value <= 0x0FFF_FFFFu) {
                "SubscriptionIdentifier value must be <= 268_435_455; got $value " +
                    "(spec §3.8.2.1.2)"
            }
        }
    }

    /**
     * MQTT v5.0 §3.1.2.11.2 Session Expiry Interval (CONNECT, CONNACK,
     * DISCONNECT properties). Seconds the session may be retained
     * after disconnect; 0 = ends with the network connection,
     * 0xFFFFFFFF = never expires.
     */
    @PacketType(value = 0x11)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class SessionExpiryInterval(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x11u),
        val seconds: UInt,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x11, "SessionExpiryInterval")
        }
    }

    /**
     * MQTT v5.0 §3.2.2.3.7 Assigned Client Identifier (CONNACK
     * property). Server-assigned client id when the client sent an
     * empty Client Identifier.
     */
    @PacketType(value = 0x12)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class AssignedClientIdentifier(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x12u),
        @LengthPrefixed val value: String,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x12, "AssignedClientIdentifier")
        }
    }

    /**
     * MQTT v5.0 §3.2.2.3.14 Server Keep Alive (CONNACK property).
     * Server-imposed keep-alive interval in seconds.
     */
    @PacketType(value = 0x13)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class ServerKeepAlive(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x13u),
        val seconds: UShort,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x13, "ServerKeepAlive")
        }
    }

    /**
     * MQTT v5.0 §3.1.2.11.9 Authentication Method (CONNECT, CONNACK,
     * AUTH properties). UTF-8 name of the authentication scheme.
     */
    @PacketType(value = 0x15)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class AuthenticationMethod(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x15u),
        @LengthPrefixed val value: String,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x15, "AuthenticationMethod")
        }
    }

    /**
     * MQTT v5.0 §3.1.2.11.10 Authentication Data (CONNECT, CONNACK,
     * AUTH properties). Opaque binary blob carrying the authentication
     * scheme's protocol data (e.g., a SASL challenge/response). The
     * scheme name is carried separately in [AuthenticationMethod]
     * (id 0x15).
     *
     * Phase J.M.5 slice 15c — second production-shaped use of the new
     * `@LengthPrefixed @UseCodec val: T : Payload` (slice 15a) shape.
     * Wire form is v5 §1.5.6 Binary Data: 2-byte UShort BE prefix +
     * body bytes. Same shape as [CorrelationData]; the two variants
     * differ only in the property identifier byte.
     */
    @PacketType(value = 0x16)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class AuthenticationData(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x16u),
        @LengthPrefixed @UseCodec(BinaryDataCodec::class) val data: BinaryData,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x16, "AuthenticationData")
        }
    }

    /**
     * MQTT v5.0 §3.1.2.11.7 Request Problem Information (CONNECT
     * property). 0 = server may suppress Reason String / User
     * Properties on errors; 1 = include them. Boolean-shaped.
     */
    @PacketType(value = 0x17)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class RequestProblemInformation(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x17u),
        val value: UByte,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x17, "RequestProblemInformation")
            require(value in 0u..1u) { "RequestProblemInformation must be 0 or 1; got $value" }
        }
    }

    /**
     * MQTT v5.0 §3.1.3.2.2 Will Delay Interval (Will properties). Delay
     * in seconds before the Will message is published after a network
     * disconnect.
     */
    @PacketType(value = 0x18)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class WillDelayInterval(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x18u),
        val seconds: UInt,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x18, "WillDelayInterval")
        }
    }

    /**
     * MQTT v5.0 §3.1.2.11.6 Request Response Information (CONNECT
     * property). 0 = server must not return Response Information in
     * CONNACK; 1 = may. Boolean-shaped.
     */
    @PacketType(value = 0x19)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class RequestResponseInformation(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x19u),
        val value: UByte,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x19, "RequestResponseInformation")
            require(value in 0u..1u) { "RequestResponseInformation must be 0 or 1; got $value" }
        }
    }

    /**
     * MQTT v5.0 §3.2.2.3.15 Response Information (CONNACK property).
     * UTF-8 string used by request/response clients to derive a
     * Response Topic prefix.
     */
    @PacketType(value = 0x1A)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class ResponseInformation(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x1Au),
        @LengthPrefixed val value: String,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x1A, "ResponseInformation")
        }
    }

    /**
     * MQTT v5.0 §3.2.2.3.16 Server Reference (CONNACK / DISCONNECT
     * properties). Used to redirect the client to another server.
     */
    @PacketType(value = 0x1C)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class ServerReference(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x1Cu),
        @LengthPrefixed val value: String,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x1C, "ServerReference")
        }
    }

    /**
     * MQTT v5.0 §3.4.2.2.2 Reason String (PUBACK / PUBREC / PUBREL /
     * PUBCOMP / SUBACK / UNSUBACK / DISCONNECT / AUTH properties).
     * Human-readable reason for the operation outcome.
     */
    @PacketType(value = 0x1F)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class ReasonString(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x1Fu),
        @LengthPrefixed val value: String,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x1F, "ReasonString")
        }
    }

    /**
     * MQTT v5.0 §3.1.2.11.3 Receive Maximum (CONNECT / CONNACK
     * properties). Maximum number of in-flight QoS 1/2 PUBLISH
     * packets the receiver is willing to process concurrently.
     * Spec disallows 0 per [MQTT-3.1.2-32].
     */
    @PacketType(value = 0x21)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class ReceiveMaximum(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x21u),
        val value: UShort,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x21, "ReceiveMaximum")
            // Phase J.M.5 audit-2f — §3.1.2.11.3 [MQTT-3.1.2-32]: 0 is invalid.
            require(value > 0u) {
                "ReceiveMaximum value must be > 0 (spec §3.1.2.11.3 [MQTT-3.1.2-32]); got $value"
            }
        }
    }

    /**
     * MQTT v5.0 §3.1.2.11.5 Topic Alias Maximum (CONNECT / CONNACK
     * properties). Maximum value the receiver will accept as a Topic
     * Alias. 0 = topic aliases are not supported.
     */
    @PacketType(value = 0x22)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class TopicAliasMaximum(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x22u),
        val value: UShort,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x22, "TopicAliasMaximum")
        }
    }

    /**
     * MQTT v5.0 §3.3.2.3.4 Topic Alias (PUBLISH property). Integer
     * mapped to the topic name on the connection; 0 is invalid per
     * §3.3.2.3.4 [MQTT-3.3.2-8].
     */
    @PacketType(value = 0x23)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class TopicAlias(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x23u),
        val value: UShort,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x23, "TopicAlias")
            // Phase J.M.5 audit-2f — §3.3.2.3.4 [MQTT-3.3.2-8]: 0 is invalid.
            require(value > 0u) {
                "TopicAlias value must be > 0 (spec §3.3.2.3.4 [MQTT-3.3.2-8]); got $value"
            }
        }
    }

    /**
     * MQTT v5.0 §3.2.2.3.4 Maximum QoS (CONNACK property). Highest
     * QoS the server will accept for PUBLISH packets. Boolean-shaped:
     * 0 = QoS 0 only, 1 = QoS 0 and 1; servers that support QoS 2
     * omit this property entirely (defaults to 2).
     */
    @PacketType(value = 0x24)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class MaximumQoS(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x24u),
        val value: UByte,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x24, "MaximumQoS")
            require(value in 0u..1u) { "MaximumQoS must be 0 or 1; got $value" }
        }
    }

    /**
     * MQTT v5.0 §3.2.2.3.5 Retain Available (CONNACK property).
     * 0 = retained messages not supported; 1 = supported.
     * Boolean-shaped.
     */
    @PacketType(value = 0x25)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class RetainAvailable(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x25u),
        val value: UByte,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x25, "RetainAvailable")
            require(value in 0u..1u) { "RetainAvailable must be 0 or 1; got $value" }
        }
    }

    /**
     * MQTT v5.0 §3.1.2.11.8 User Property (any packet's properties).
     * Two-LP-string shape `26 <key_lp> <value_lp>`. Unlike the other
     * variants, User Property may appear multiple times in a single
     * property bag (each occurrence is independent — the bag carries
     * a list of pairs, not a map).
     */
    @PacketType(value = 0x26)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class UserProperty(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x26u),
        @LengthPrefixed val key: String,
        @LengthPrefixed val value: String,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x26, "UserProperty")
        }
    }

    /**
     * MQTT v5.0 §3.1.2.11.4 Maximum Packet Size (CONNECT / CONNACK
     * properties). Largest packet the receiver will accept, in bytes.
     * 0 is invalid per §3.1.2.11.4 [MQTT-3.1.2-31].
     */
    @PacketType(value = 0x27)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class MaximumPacketSize(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x27u),
        val value: UInt,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x27, "MaximumPacketSize")
            // Phase J.M.5 audit-2f — §3.1.2.11.4 [MQTT-3.1.2-31]: 0 is invalid.
            require(value > 0u) {
                "MaximumPacketSize value must be > 0 (spec §3.1.2.11.4 [MQTT-3.1.2-31]); got $value"
            }
        }
    }

    /**
     * MQTT v5.0 §3.2.2.3.11 Wildcard Subscription Available (CONNACK
     * property). 0 = server doesn't support wildcards; 1 = supports.
     * Boolean-shaped.
     */
    @PacketType(value = 0x28)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class WildcardSubscriptionAvailable(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x28u),
        val value: UByte,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x28, "WildcardSubscriptionAvailable")
            require(value in 0u..1u) { "WildcardSubscriptionAvailable must be 0 or 1; got $value" }
        }
    }

    /**
     * MQTT v5.0 §3.2.2.3.12 Subscription Identifiers Available
     * (CONNACK property). 0 = server doesn't support subscription
     * identifiers; 1 = supports. Boolean-shaped.
     */
    @PacketType(value = 0x29)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class SubscriptionIdentifiersAvailable(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x29u),
        val value: UByte,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x29, "SubscriptionIdentifiersAvailable")
            require(value in 0u..1u) { "SubscriptionIdentifiersAvailable must be 0 or 1; got $value" }
        }
    }

    /**
     * MQTT v5.0 §3.2.2.3.13 Shared Subscription Available (CONNACK
     * property). 0 = server doesn't support shared subscriptions;
     * 1 = supports. Boolean-shaped.
     */
    @PacketType(value = 0x2A)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class SharedSubscriptionAvailable(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x2Au),
        val value: UByte,
    ) : MqttV5Property {
        init {
            requireMatchingPropertyId(id, 0x2A, "SharedSubscriptionAvailable")
            require(value in 0u..1u) { "SharedSubscriptionAvailable must be 0 or 1; got $value" }
        }
    }
}
