package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.annotations.When
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttConnectFlags
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttUnsubscribeTopic
import com.ditchoom.buffer.codec.test.protocols.payload.PacketId

/**
 * Phase J.M.5 — MQTT v5.0 control-packet sealed dispatcher.
 *
 * Sits alongside (NOT replacing) the v3.1.1 [com.ditchoom.buffer.codec.test.protocols.mqtt.MqttPacket]
 * sealed family. v3.1.1 and v5.0 are distinct wire protocols selected at
 * CONNECT time; modeling them as one sealed parent would smear the
 * dispatcher's per-variant uniqueness checks (v5 adds AUTH = type 15, plus
 * trailing reason-code + property-bag tails on PUBACK et al. that don't
 * exist on the v3 wire).
 *
 * Cross-version primitives are reused as-is, not forked:
 *
 *  - [MqttFixedHeader] — top-nibble dispatcher + `qosGreaterThanZero`
 *    helper. Wire-bytewise identical between v3 and v5; the same
 *    value-class is the `@DispatchOn` discriminator for both sealed
 *    parents.
 *  - [MqttRemainingLengthCodec] — MQTT §1.5.5 variable-byte-integer
 *    length codec. v5's "remaining length" header field uses the same
 *    encoding as v3's; v5 also reuses the same VBI codec for the
 *    inner property-bag length prefix (the slice covered by Phase I.1
 *    step 11's `@LengthPrefixed @UseCodec` shape).
 *
 * Slice 1 landed [PingReq] only — wire-bytewise identical to the v3
 * PINGREQ (`C0 00`); structural smoke test for the v5 sealed parent +
 * generated codec + peek path.
 *
 * Slice 2 (this commit) lifts the parent to `<out P : Payload>` and
 * introduces [Publish] — composes step 11's `@LengthPrefixed @UseCodec`
 * property-bag shape with the slice 10c/10f `Partial` outer-limit
 * machinery and `@RemainingBytes payload: P`. The dispatcher emits as a
 * generic class `MqttV5PacketCodec<P>(payloadCodec)`, mirroring the v3
 * family's slice 10f lift.
 */
@DispatchOn(MqttFixedHeader::class)
@ProtocolMessage
sealed interface MqttV5Packet<out P : Payload> {
    /**
     * Type-1 CONNECT per MQTT v5.0 §3.1 — full variable header and
     * payload. Wire layout:
     *
     * ```text
     *   10 <RL>
     *   00 04 'M' 'Q' 'T' 'T'   protocol name "MQTT" (LengthPrefixed)
     *   05                      protocol level 5 (v5.0)
     *   <flags>                 connect flags (bit-packed UByte)
     *   <ka_msb> <ka_lsb>       keep-alive seconds (UShort BE)
     *   <propLen VBI> <props..> v5 connect properties (always present)
     *   <client id LP>          length-prefixed UTF-8 client id
     *   <willPropLen VBI>?      will properties — present iff flags.willPresent
     *   <will props...>?        will-property entries
     *   <will topic LP>?        present iff flags.willPresent
     *   <will message LP>?      present iff flags.willPresent (modeled as String;
     *                           technically arbitrary bytes per §3.1.3.3 — same
     *                           caveat as v3 Connect, deferred until @Payload
     *                           slot lands)
     *   <username LP>?          present iff flags.usernamePresent
     *   <password LP>?          present iff flags.passwordPresent (modeled as
     *                           String; same arbitrary-bytes caveat)
     * ```
     *
     * Composes:
     *  - the always-present property bag (slice 2 shape) between
     *    `keepAliveSeconds` and `clientId`,
     *  - the conditional will-properties (slice 5 shape) between
     *    `clientId` and `willTopic` — grammar 1 predicate
     *    `flags.willPresent` (sibling value-class property),
     *  - the existing `@LengthPrefixed @When val: String?` shape
     *    (slice 3.5) for will-topic / will-message / username /
     *    password — inherited from the v3 Connect fixture.
     *
     * Reuses `MqttConnectFlags` from the v3 fixtures — bit layout is
     * unchanged in v5 (§3.1.2.3).
     *
     * Modeled as `: MqttV5Packet<Nothing>` for now — binary
     * will-message / password (`@Payload WP`/`@Payload PP`) needs the
     * multi-generic dispatcher lift documented in PHASE_J_M_BRIEF.md
     * §4. Deferred until a vector requires arbitrary will/password
     * bytes; until then String modeling is wire-correct for ASCII.
     */
    @PacketType(value = 1)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Connect(
        val header: MqttFixedHeader = MqttFixedHeader(0x10u),
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt,
        @LengthPrefixed val protocolName: String,
        val protocolLevel: UByte,
        val connectFlags: MqttConnectFlags,
        val keepAliveSeconds: UShort,
        @LengthPrefixed @UseCodec(MqttRemainingLengthCodec::class)
        val properties: List<MqttV5Property>,
        @LengthPrefixed val clientId: String,
        @When("connectFlags.willPresent")
        @LengthPrefixed
        @UseCodec(MqttRemainingLengthCodec::class)
        val willProperties: List<MqttV5Property>? = null,
        @LengthPrefixed @When("connectFlags.willPresent") val willTopic: String? = null,
        @LengthPrefixed @When("connectFlags.willPresent") val willMessage: String? = null,
        @LengthPrefixed @When("connectFlags.usernamePresent") val username: String? = null,
        @LengthPrefixed @When("connectFlags.passwordPresent") val password: String? = null,
    ) : MqttV5Packet<Nothing>

    /**
     * Type-2 CONNACK per MQTT v5.0 §3.2 — fixed header `0x20` +
     * remaining length + connect-ack-flags (`UByte`, §3.2.2.1: bit 0 is
     * Session Present, others reserved) + reason code (`UByte`,
     * §3.2.2.2) + property bag (§3.2.2.3, always present in v5).
     *
     * Wire layout (after `header`):
     *
     * ```text
     *   <var-int>             remaining length
     *   <flags>               connect-ack-flags (UByte)
     *   <rc>                  reason code (UByte)
     *   <var-int>             properties length (VBI)
     *   <properties...>       0..N MqttV5Property entries
     * ```
     *
     * Slice 3 variant — exercises the always-present property bag
     * without new emitter capability beyond what slice 2 lifted.
     * Reason code is modeled as a raw `UByte` (the spec assigns
     * specific values 0x00 through 0xA2; typed sealed enum is a
     * follow-on once a vector requires it).
     */
    @PacketType(value = 2)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class ConnAck(
        val header: MqttFixedHeader = MqttFixedHeader(0x20u),
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt,
        val connectAckFlags: UByte,
        val reasonCode: UByte,
        @LengthPrefixed @UseCodec(MqttRemainingLengthCodec::class)
        val properties: List<MqttV5Property>,
    ) : MqttV5Packet<Nothing>

    /**
     * Type-3 PUBLISH per MQTT v5.0 §3.3 — generic-bounded payload
     * variant with the new v5 property bag inserted between the
     * packet identifier and the payload. Wire layout:
     *
     *   - `header` (`MqttFixedHeader`, 1 byte): packet type=3 in
     *     the top 4 bits, flags (DUP/QoS/RETAIN) in the bottom 4.
     *     The default `0x30` is QoS=0 with no DUP/RETAIN.
     *   - `remainingLength` (1–4 bytes, MQTT VBI): total bytes in
     *     `topic`, `packetId`, `properties`, and `payload`. Decoded
     *     via [MqttRemainingLengthCodec] which calls `applyBound` —
     *     subsequent fields run inside the var-int-narrowed limit.
     *   - `topic` (UShort BE prefix + UTF-8 body, §3.3.2.1): the
     *     publish topic name.
     *   - `packetId` (UShort BE, §3.3.2.2): present only when
     *     `header.qosGreaterThanZero` is `true`. Modeled with
     *     `@When("header.qosGreaterThanZero")` against the
     *     value-class header property.
     *   - `properties` (VBI length prefix + properties, §3.3.2.3):
     *     v5-specific property bag. Encoded as
     *     `@LengthPrefixed @UseCodec(MqttRemainingLengthCodec)
     *     val: List<MqttV5Property>` — the Phase I.1 step 11 shape.
     *     The codec writes a VBI length prefix for the body bytes,
     *     then iterates and encodes each property; decode reads the
     *     VBI prefix, narrows the limit via `applyBound`, and reads
     *     elements until the inner limit is hit (then restores the
     *     outer limit before the payload is read).
     *   - `payload: P` (variable, §3.3.3): consumes the remaining
     *     bytes of the var-int-bounded region via the user-supplied
     *     `Codec<P>`.
     *
     * Composes:
     *  - the slice 10d generic dispatcher (`<P : Payload>` variant),
     *  - the slice 10f outer-limit `Partial` machinery (RL +
     *    `@RemainingBytes payload`),
     *  - the slice 10c `@When` value-class scalar predicate
     *    (`header.qosGreaterThanZero`),
     *  - the Phase I.1 step 11 `@LengthPrefixed @UseCodec` shape
     *    for the property bag — the new capability this slice brings
     *    online.
     *
     * The step-11 doctrine guarantees the inner property-bag bound
     * is restored before the outer RL bound is consulted for
     * `@RemainingBytes payload`, so the two bounding shapes compose
     * without violating the at-most-one-bounding-field check.
     */
    @PacketType(value = 3)
    @ProtocolMessage
    data class Publish<P : Payload>(
        val header: MqttFixedHeader = MqttFixedHeader(0x30u),
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt,
        @LengthPrefixed val topic: String,
        @When("header.qosGreaterThanZero") val packetId: PacketId? = null,
        @LengthPrefixed @UseCodec(MqttRemainingLengthCodec::class)
        val properties: List<MqttV5Property>,
        @RemainingBytes val payload: P,
    ) : MqttV5Packet<P>

    /**
     * Type-4 PUBACK per MQTT v5.0 §3.4. Wire layout:
     *
     * ```text
     *   40 <RL> <pid> [<rc> [<propLen> <props...>]]
     * ```
     *
     * Per §3.4.2.1: "The Reason Code and Property Length can be omitted
     * if the Reason Code is 0x00 (Success) and there are no Properties.
     * In this case the PUBACK has a Remaining Length of 2." Per
     * §3.4.2.2.1: "If the Remaining Length is less than 4 there is no
     * Property Length and the value of 0 is used."
     *
     * The cascade is encoded as two `@When("remaining >= 1")` predicate
     * fields; the decoder tests the bounded buffer's `remaining()`
     * after each step and skips the slot if there isn't a byte to read.
     * The encoder gates each slot on field-non-null (caller signals
     * "include this slot" by setting the field).
     *
     * Four valid wire forms:
     *
     *   - `40 02 <pid>`                      — Success, no rc, no props
     *   - `40 03 <pid> <rc>`                 — rc, no props (RL<4 elides propLen per §3.4.2.2.1)
     *   - `40 04 <pid> <rc> 00`              — rc + empty bag
     *   - `40 (>=5) <pid> <rc> <pl> <ps...>` — rc + non-empty bag
     */
    @PacketType(value = 4)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class PubAck(
        val header: MqttFixedHeader = MqttFixedHeader(0x40u),
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt = 2u,
        val packetIdentifier: UShort,
        @When("remaining >= 1") val reasonCode: UByte? = null,
        @When("remaining >= 1")
        @LengthPrefixed
        @UseCodec(MqttRemainingLengthCodec::class)
        val properties: List<MqttV5Property>? = null,
    ) : MqttV5Packet<Nothing>

    /**
     * Type-5 PUBREC per MQTT v5.0 §3.5 — same shape as PUBACK with
     * fixed header `0x50` and a different reason-code value space.
     */
    @PacketType(value = 5)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class PubRec(
        val header: MqttFixedHeader = MqttFixedHeader(0x50u),
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt = 2u,
        val packetIdentifier: UShort,
        @When("remaining >= 1") val reasonCode: UByte? = null,
    ) : MqttV5Packet<Nothing>

    /**
     * Type-6 PUBREL per MQTT v5.0 §3.6 — same shape as PUBACK with
     * fixed header `0x62` (low-bit-2 reserved-and-must-be-set per
     * §3.6.1).
     */
    @PacketType(value = 6)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class PubRel(
        val header: MqttFixedHeader = MqttFixedHeader(0x62u),
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt = 2u,
        val packetIdentifier: UShort,
        @When("remaining >= 1") val reasonCode: UByte? = null,
    ) : MqttV5Packet<Nothing>

    /**
     * Type-7 PUBCOMP per MQTT v5.0 §3.7 — same shape as PUBACK with
     * fixed header `0x70`.
     */
    @PacketType(value = 7)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class PubComp(
        val header: MqttFixedHeader = MqttFixedHeader(0x70u),
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt = 2u,
        val packetIdentifier: UShort,
        @When("remaining >= 1") val reasonCode: UByte? = null,
    ) : MqttV5Packet<Nothing>

    /**
     * Type-8 SUBSCRIBE per MQTT v5.0 §3.8 — fixed header `0x82` +
     * RL + packetIdentifier + always-present property bag +
     * `@RemainingBytes List<V5Subscription>` topic-filter list.
     *
     * Wire layout per §3.8.1:
     *
     * ```text
     *   82 <RL>
     *   <pid_msb> <pid_lsb>
     *   <propLen VBI> <props...>
     *   <topic LP> <opts>      filter 1
     *   …                      repeated for each filter
     * ```
     *
     * Composes the always-present property bag (slice 2 shape) with
     * the J.M.0 `@RemainingBytes List<@ProtocolMessage T>` shape. The
     * inner property-bag bound is restored before the trailing list
     * reads, and the list reads to the outer RL bound.
     */
    @PacketType(value = 8)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Subscribe(
        val header: MqttFixedHeader = MqttFixedHeader(0x82u),
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt,
        val packetIdentifier: UShort,
        @LengthPrefixed @UseCodec(MqttRemainingLengthCodec::class)
        val properties: List<MqttV5Property>,
        @RemainingBytes val topicFilters: List<V5Subscription>,
    ) : MqttV5Packet<Nothing>

    /**
     * Type-9 SUBACK per MQTT v5.0 §3.9 — fixed header `0x90` + RL +
     * pid + always-present property bag + `@RemainingBytes List<UByte>`
     * reason-code list (one byte per topic filter from the matching
     * SUBSCRIBE).
     *
     * The reason codes are typed `UByte` for now (raw spec values per
     * §3.9.3 — 0x00 = Granted QoS 0 ... 0xA2 = Wildcard Subscriptions
     * not supported). Typed sealed enum is a follow-on.
     */
    @PacketType(value = 9)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class SubAck(
        val header: MqttFixedHeader = MqttFixedHeader(0x90u),
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt,
        val packetIdentifier: UShort,
        @LengthPrefixed @UseCodec(MqttRemainingLengthCodec::class)
        val properties: List<MqttV5Property>,
        @RemainingBytes val reasonCodes: List<UByte>,
    ) : MqttV5Packet<Nothing>

    /**
     * Type-10 UNSUBSCRIBE per MQTT v5.0 §3.10 — fixed header `0xA2` +
     * RL + pid + always-present property bag + `@RemainingBytes
     * List<MqttUnsubscribeTopic>` topic list.
     *
     * Reuses [MqttUnsubscribeTopic] from the v3 fixtures — a single-
     * field LP-string wrapper. v5 adds the property bag between pid
     * and the topic list; the topic list shape itself is unchanged
     * from v3 (LP UTF-8 string per topic).
     */
    @PacketType(value = 10)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Unsubscribe(
        val header: MqttFixedHeader = MqttFixedHeader(0xA2u),
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt,
        val packetIdentifier: UShort,
        @LengthPrefixed @UseCodec(MqttRemainingLengthCodec::class)
        val properties: List<MqttV5Property>,
        @RemainingBytes val topics: List<MqttUnsubscribeTopic>,
    ) : MqttV5Packet<Nothing>

    /**
     * Type-11 UNSUBACK per MQTT v5.0 §3.11 — fixed header `0xB0` +
     * RL + pid + optional reason code (per the cascade gate; the
     * property bag and per-topic reason-code list are deferred).
     */
    @PacketType(value = 11)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class UnsubAck(
        val header: MqttFixedHeader = MqttFixedHeader(0xB0u),
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt = 2u,
        val packetIdentifier: UShort,
        @When("remaining >= 1") val reasonCode: UByte? = null,
    ) : MqttV5Packet<Nothing>

    /**
     * Type-14 DISCONNECT per MQTT v5.0 §3.14 — fixed header `0xE0` +
     * RL + optional reason code. Per §3.14.2.1: "The Reason Code and
     * Property Length can be omitted if the Reason Code is 0x00
     * (Normal disconnection) and there are no Properties. In this case
     * the DISCONNECT has a Remaining Length of 0." DISCONNECT has no
     * packet identifier — the rc is the first conditional trailer
     * after RL.
     */
    @PacketType(value = 14)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Disconnect(
        val header: MqttFixedHeader = MqttFixedHeader(0xE0u),
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt = 0u,
        @When("remaining >= 1") val reasonCode: UByte? = null,
        @When("remaining >= 1")
        @LengthPrefixed
        @UseCodec(MqttRemainingLengthCodec::class)
        val properties: List<MqttV5Property>? = null,
    ) : MqttV5Packet<Nothing>

    /**
     * Type-15 AUTH per MQTT v5.0 §3.15 — v5-only packet. Fixed header
     * `0xF0` + RL + optional reason code. AUTH bodies are typically
     * sent as part of an authentication exchange; an "AUTH continue"
     * has reasonCode = 0x18 (Continue authentication), and the
     * authentication-method/data live in the property bag (deferred).
     */
    @PacketType(value = 15)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Auth(
        val header: MqttFixedHeader = MqttFixedHeader(0xF0u),
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt = 0u,
        @When("remaining >= 1") val reasonCode: UByte? = null,
    ) : MqttV5Packet<Nothing>

    /**
     * Type-12 PINGREQ per MQTT v5.0 §3.12 — fixed header `0xC0` + remaining
     * length `0`. Wire-bytewise identical to v3.1.1 PINGREQ; v5 carries no
     * reason code or property bag for this packet.
     *
     * Payload-free variants are `: MqttV5Packet<Nothing>` — covariance
     * makes them assignable to any `MqttV5Packet<P>` instantiation.
     */
    @PacketType(value = 12)
    @ProtocolMessage
    data class PingReq(
        val header: MqttFixedHeader = MqttFixedHeader(0xC0u),
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt = 0u,
    ) : MqttV5Packet<Nothing>

    /**
     * Type-13 PINGRESP per MQTT v5.0 §3.13 — fixed header `0xD0` +
     * remaining length `0`. Wire-bytewise identical to v3.1.1 PINGRESP;
     * v5 carries no reason code or property bag for this packet.
     */
    @PacketType(value = 13)
    @ProtocolMessage
    data class PingResp(
        val header: MqttFixedHeader = MqttFixedHeader(0xD0u),
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt = 0u,
    ) : MqttV5Packet<Nothing>
}
