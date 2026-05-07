package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.FramedBy
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.annotations.When
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryData
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec
import com.ditchoom.buffer.codec.test.protocols.payload.PacketId
import kotlin.jvm.JvmInline

/**
 * MQTT v3.1.1 §2.2 fixed header — top 4 bits encode the packet
 * type, bottom 4 bits encode per-type flags.
 *
 * Modeled as a `@JvmInline value class` over a single `UByte`
 * raw, with `packetType` exposed as the `@DispatchValue` (top 4
 * bits as `Int`) and `flags` as a free-form `UByte` getter for
 * per-variant interpretation.
 *
 * Stage F slice 6 doctrine vector — exercises the bit-packed
 * `@DispatchOn` discriminator path.
 */
@JvmInline
@ProtocolMessage
value class MqttFixedHeader(
    val raw: UByte,
) {
    @DispatchValue
    val packetType: Int get() = raw.toUInt().shr(4).toInt()

    val flags: UByte get() = (raw.toUInt() and 0x0Fu).toUByte()

    /**
     * MQTT v3.1.1 §3.3.2.2 — PUBLISH carries a packet identifier
     * only when QoS > 0 (QoS bits live in `flags & 0x06`). Exposed
     * as a `Boolean`-returning `val` so `Publish.packetId` can gate
     * on `@When("header.qosGreaterThanZero")` via the slice-3
     * dotted value-class predicate path.
     */
    val qosGreaterThanZero: Boolean get() = (raw.toUInt() and 0x06u) != 0u

    init {
        // Phase J.M.5 audit-2f — PUBLISH QoS=3 is malformed per §3.3.1.2
        // [MQTT-3.3.1-4]. Init-block fallback; cleaner future fix is a
        // typed V5PublishFlags companion to MqttFixedHeader.flags. Other
        // packet types' low-nibble invariants are caught by per-variant
        // init-blocks (e.g. PUBREL must be 0x62), so this only fires for
        // PUBLISH (packetType == 3).
        require(!(packetType == 3 && (raw.toInt() and 0x06) == 0x06)) {
            "MqttFixedHeader: PUBLISH QoS=3 is malformed (spec §3.3.1.2 [MQTT-3.3.1-4]); " +
                "got 0x" + raw.toString(16)
        }
    }
}

/**
 * Stage F slice 6 + Stage H slice 10f doctrine vector — sealed
 * dispatcher over `MqttFixedHeader` exercising the `@DispatchOn`
 * value-class discriminator emit path.
 *
 * Slice 10f lifts the parent to `<out P : Payload>` so the new
 * `Publish<P : Payload>` variant (MQTT v3.1.1 §3.3) can carry a
 * typed payload routed through the slice 10d generic dispatcher.
 * Payload-free variants stay `: MqttPacket<Nothing>`; covariance
 * makes them assignable to any `MqttPacket<P>` instantiation.
 *
 * Each variant carries the fixed header as its first field. The
 * slice 6 dispatcher peeks the header byte without consuming,
 * extracts `header.packetType`, matches against `@PacketType.value`,
 * and delegates to the variant codec — which then reads the same
 * bytes (including the header field) via the slice 3
 * `FieldSpec.ValueClassScalar` path.
 *
 * Wire layout per MQTT-3.1.1, with `@RemainingLength` var-int
 * between the fixed header and the body (slice 8 spec compliance):
 *
 * ```text
 * Connect (type 1, header byte typically 0x10):
 *   10                       fixed header
 *   <var-int>                remaining length (var-int per §2.2.3)
 *   00 04 'M' 'Q' 'T' 'T'    protocol name "MQTT" (LengthPrefixed)
 *   04                       protocol level 4 (v3.1.1)
 *   <flags>                  connect flags (bit-packed)
 *   <ka_msb> <ka_lsb>        keep-alive seconds (UShort BE)
 *   00 04 'a' 'b' 'c' 'd'    client id (LengthPrefixed)
 *   <will topic LP>?         conditional on connectFlags.willPresent
 *   <will message LP>?       conditional on connectFlags.willPresent
 *   <username LP>?           conditional on connectFlags.usernamePresent
 *   <password LP>?           conditional on connectFlags.passwordPresent
 *
 * Publish (type 3, header byte typically 0x30 for QoS=0):
 *   30                       fixed header
 *   <var-int>                remaining length (= 2 + topic + 2 + payload bytes)
 *   00 03 't' '/' '1'        topic (LengthPrefixed)
 *   00 2A                    packet id
 *   <payload bytes>          decoded by the user-supplied Codec<P>
 *
 * Disconnect (type 14, header byte typically 0xE0):
 *   E0                       fixed header
 *   00                       remaining length = 0 (per §3.14)
 * ```
 */
@DispatchOn(MqttFixedHeader::class)
@FramedBy(MqttRemainingLengthCodec::class, after = "header")
@ProtocolMessage
sealed interface MqttPacket<out P : Payload> {
    /**
     * Type-1 CONNECT per MQTT v3.1.1 §3.1 — full variable header
     * and payload folded onto the slice-6 sealed dispatcher in
     * Phase J.M step 4. The standalone `MqttConnect` data class is
     * gone; this variant now carries the complete §3.1 body.
     *
     * Wire layout (variable header + payload):
     *
     * ```text
     *   <header>                  fixed header (typically 0x10)
     *   <var-int>                 remaining length
     *   00 04 'M' 'Q' 'T' 'T'     protocol name "MQTT" (LengthPrefixed)
     *   04                        protocol level 4 (v3.1.1)
     *   <flags>                   bit-packed connect flags
     *   <ka_msb> <ka_lsb>         keepalive seconds (UShort BE)
     *   <client id LP>            length-prefixed UTF-8 client id
     *   <will topic LP>?          present iff connectFlags.willPresent
     *   <will message LP>?        present iff connectFlags.willPresent
     *   <username LP>?            present iff connectFlags.usernamePresent
     *   <password LP>?            present iff connectFlags.passwordPresent
     * ```
     *
     * Composes every Stage E + G annotation the standalone fixture
     * exercised: `@LengthPrefixed val: String` (slice 5a non-terminal
     * placement), value-class field (slice 3), dotted
     * `@When("connectFlags.<bit>")` predicates (slice 3 dotted
     * form + slice 3.5 LengthPrefixed inner + slice 5b non-terminal
     * Conditional), and the `@RemainingLength` var-int header (slice 8)
     * bounding decode of the optional payload tail. The dispatcher
     * peeks the fixed header byte without consuming, then the variant
     * codec re-reads it through the slice 3 `FieldSpec.ValueClassScalar`
     * path — same pattern slice 6's dispatcher uses for every other
     * variant in this sealed family.
     *
     * Phase J.M.5 slice 15d retyped will-message and password from
     * `String?` to `BinaryData?` (a `Payload`-marked value class over
     * `ByteArray`) referenced via `@LengthPrefixed @UseCodec(
     * BinaryDataCodec::class)`. The wire form is unchanged (UShort BE
     * prefix + body bytes); the data class now reflects the
     * arbitrary-bytes spec contract per §3.1.3.3 / §3.1.3.5.
     */
    @PacketType(value = 1)
    @ProtocolMessage
    data class Connect(
        val header: MqttFixedHeader = MqttFixedHeader(0x10u),
        @LengthPrefixed val protocolName: String,
        val protocolLevel: UByte,
        val connectFlags: MqttConnectFlags,
        val keepAliveSeconds: UShort,
        @LengthPrefixed val clientId: String,
        @LengthPrefixed @When("connectFlags.willPresent") val willTopic: String? = null,
        @LengthPrefixed
        @UseCodec(BinaryDataCodec::class)
        @When("connectFlags.willPresent")
        val willPayload: BinaryData? = null,
        @LengthPrefixed @When("connectFlags.usernamePresent") val username: String? = null,
        @LengthPrefixed
        @UseCodec(BinaryDataCodec::class)
        @When("connectFlags.passwordPresent")
        val password: BinaryData? = null,
    ) : MqttPacket<Nothing> {
        init {
            // Phase J.M.5 audit-2f — v3.1.1 §3.1.2.9 [MQTT-3.1.2-22]: if the
            // username flag is set to 0, the password flag MUST also be 0.
            // v5 dropped this rule (§3.1.3.5 allows password without
            // username), so the guard lives on the v3 Connect variant
            // rather than on the shared MqttConnectFlags class.
            require(connectFlags.usernamePresent || !connectFlags.passwordPresent) {
                "v3 CONNECT invariant: passwordPresent requires usernamePresent " +
                    "(spec §3.1.2.9 [MQTT-3.1.2-22])"
            }
        }
    }

    /**
     * Type-2 CONNACK per MQTT v3.1.1 §3.2 — fixed header `0x20` +
     * `remainingLength = 2` + `connectAckFlags` (`UByte` per §3.2.2.1
     * — bit 0 carries Session Present, bits 7–1 are reserved) +
     * `returnCode` (`UByte` per §3.2.2.3, 0x00 accepted, 0x01–0x05
     * various failure modes). Total wire length is always 4 bytes:
     * `20 02 <flags> <rc>`.
     *
     * Phase J.M step 5 second-tranche variant. Modeled with raw
     * `UByte` fields rather than a value-class wrapper — there's no
     * `@When` gate that needs to inspect `sessionPresent` as a
     * boolean, and the rest of the byte is reserved per spec, so the
     * `MqttConnectFlags`-style decomposition would be premature.
     */
    @PacketType(value = 2)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class ConnAck(
        val header: MqttFixedHeader = MqttFixedHeader(0x20u),
        val connectAckFlags: UByte,
        val returnCode: UByte,
    ) : MqttPacket<Nothing>

    /**
     * Type-3 PUBLISH per MQTT v3.1.1 §3.3 — generic-bounded payload
     * variant. Wire layout:
     *
     *   - `header` (`MqttFixedHeader`, 1 byte): packet type=3 in
     *     the top 4 bits, flags in the bottom 4. The default
     *     `0x30` is QoS=0 with no DUP/RETAIN.
     *   - `@RemainingLength remainingLength` (1–4 bytes): total
     *     bytes in `topic`, `packetId`, and `payload`. The codec
     *     reads the var-int and bounds the buffer so trailing
     *     fields stop at the var-int's value.
     *   - `@LengthPrefixed topic` (UShort BE prefix + UTF-8 body):
     *     the publish topic name.
     *   - `packetId` (`PacketId?`, UShort BE) — present only when
     *     `header.qosGreaterThanZero` is `true` per §3.3.2.2.
     *     Modeled with `@When("header.qosGreaterThanZero")`
     *     against the value-class header property; for QoS=0 the
     *     slot is skipped on the wire and the field reads back as
     *     `null`. Phase J.M step 2 is the vector that lifts
     *     `ConditionalInner` to cover value-class scalars.
     *   - `@RemainingBytes payload: P` (variable, decoded by the
     *     user-supplied `Codec<P>`): consumes the remaining bytes
     *     of the var-int-bounded region.
     *
     * Slice 10f exercises:
     *   - The new generic `MqttPacket<out P : Payload>` shape with
     *     `Publish<P> : MqttPacket<P>`.
     *   - `@RemainingLength` + `@RemainingBytes payload: P` in the
     *     same data class — the slice 10c carve-out lifts here.
     *   - `Partial.complete()` running inside the var-int-narrowed
     *     bound and restoring the outer limit on completion.
     */
    @PacketType(value = 3)
    @ProtocolMessage
    data class Publish<P : Payload>(
        val header: MqttFixedHeader = MqttFixedHeader(0x30u),
        @LengthPrefixed val topic: String,
        @When("header.qosGreaterThanZero") val packetId: PacketId? = null,
        @RemainingBytes val payload: P,
    ) : MqttPacket<P> {
        init {
            // Phase J.M.5 audit-2f — §2.2.1 [MQTT-2.2.1-3]: PUBLISH carries
            // a packet identifier iff QoS > 0. The @When framework tolerates
            // a packetId set when the predicate is false; this init-block
            // closes the gap caller-side. Cleaner emitter-level fix is to
            // tighten @When-skipped writes — deferred.
            require(header.qosGreaterThanZero == (packetId != null)) {
                "v3 PUBLISH invariant: packetId is required iff header.qosGreaterThanZero " +
                    "(spec §2.2.1); header=0x" + header.raw.toString(16) +
                    " packetId=" + packetId
            }
        }
    }

    /**
     * Type-4 PUBACK per MQTT v3.1.1 §3.4 — fixed header `0x40` +
     * `remainingLength = 2` + `packetIdentifier`. Total wire length
     * is always 4 bytes (`40 02 <pid_msb> <pid_lsb>`). Phase J.M
     * step 5 first-tranche variant; one of five 2-byte-body acks
     * (PUBACK / PUBREC / PUBREL / PUBCOMP / UNSUBACK) that share the
     * same shape.
     */
    @PacketType(value = 4)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class PubAck(
        val header: MqttFixedHeader = MqttFixedHeader(0x40u),
        val packetIdentifier: UShort,
    ) : MqttPacket<Nothing>

    /**
     * Type-5 PUBREC per MQTT v3.1.1 §3.5 — fixed header `0x50` +
     * `remainingLength = 2` + `packetIdentifier`. QoS-2 publish
     * acknowledgement (publish-received). Wire shape mirrors PUBACK.
     */
    @PacketType(value = 5)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class PubRec(
        val header: MqttFixedHeader = MqttFixedHeader(0x50u),
        val packetIdentifier: UShort,
    ) : MqttPacket<Nothing>

    /**
     * Type-6 PUBREL per MQTT v3.1.1 §3.6 — fixed header `0x62` +
     * `remainingLength = 2` + `packetIdentifier`. Per §3.6.1 the
     * bottom-bit-2 flag (0x02) is reserved-and-must-be-set; the
     * variant defaults the header byte to `0x62` to encode this on
     * the wire. The dispatcher routes by the top 4 bits, so a
     * caller passing `MqttFixedHeader(0x60u)` would still decode as
     * PUBREL but produce a malformed §3.6.1 frame on encode.
     * Trust the caller per the sealed-variant doctrine; no `init`
     * guard.
     */
    @PacketType(value = 6)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class PubRel(
        val header: MqttFixedHeader = MqttFixedHeader(0x62u),
        val packetIdentifier: UShort,
    ) : MqttPacket<Nothing>

    /**
     * Type-7 PUBCOMP per MQTT v3.1.1 §3.7 — fixed header `0x70` +
     * `remainingLength = 2` + `packetIdentifier`. Final hop of the
     * QoS-2 publish handshake.
     */
    @PacketType(value = 7)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class PubComp(
        val header: MqttFixedHeader = MqttFixedHeader(0x70u),
        val packetIdentifier: UShort,
    ) : MqttPacket<Nothing>

    /**
     * Type-8 SUBSCRIBE per MQTT v3.1.1 §3.8 — fixed header `0x82` +
     * remainingLength + packetIdentifier + a non-empty list of
     * `MqttTopicFilter` payload elements (each `<2-byte LP><filter
     * bytes><qos>`). Per §3.8.1 the bottom-bit-2 flag (0x02) is
     * reserved-and-must-be-set; the variant defaults the header byte
     * to `0x82` to encode this on the wire.
     *
     * Wire layout per §3.8.1:
     *
     * ```text
     *   82                       fixed header (type=8 << 4 | flags=0x2)
     *   <var-int>                remaining length (1–4 bytes)
     *   <pid_msb> <pid_lsb>      packet identifier (UShort BE)
     *   <topic_lp> <qos>         filter 1
     *   …                        repeated for each filter
     * ```
     *
     * Phase J.M step 5 third-tranche variant. Rides the J.M.0
     * emitter slice (`@RemainingBytes List<@ProtocolMessage T>`); the
     * outer `@UseCodec(MqttRemainingLengthCodec)` var-int bounds the
     * filter-list region so decode stops at the spec-mandated end of
     * the SUBSCRIBE rather than at buffer end.
     */
    @PacketType(value = 8)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Subscribe(
        val header: MqttFixedHeader = MqttFixedHeader(0x82u),
        val packetIdentifier: UShort,
        @RemainingBytes val topicFilters: List<MqttTopicFilter>,
    ) : MqttPacket<Nothing>

    /**
     * Type-9 SUBACK per MQTT v3.1.1 §3.9. Folded into the sealed
     * dispatcher in Phase J.M step 3 — the standalone `MqttSubAck`
     * fixture's body shape lifts unchanged onto the `MqttPacket`
     * sealed family. The bounding `@UseCodec(MqttRemainingLengthCodec)`
     * narrows the buffer limit so the trailing `@RemainingBytes
     * List<UByte>` stops at the var-int's value rather than the raw
     * buffer end (slice 7b + slice 8 composition, per the original
     * fixture).
     *
     * Wire layout per §3.9.1:
     *
     * ```text
     *   90                       fixed header (type=9 << 4 | flags=0)
     *   <var-int>                remaining length (1–4 bytes)
     *   <pid_msb> <pid_lsb>      packet identifier (UShort BE)
     *   <rc_1> <rc_2> ... <rc_N> return codes (each 1 byte)
     * ```
     */
    @PacketType(value = 9)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class SubAck(
        val header: MqttFixedHeader = MqttFixedHeader(0x90u),
        val packetIdentifier: UShort,
        @RemainingBytes val returnCodes: List<UByte>,
    ) : MqttPacket<Nothing>

    /**
     * Type-10 UNSUBSCRIBE per MQTT v3.1.1 §3.10 — fixed header
     * `0xA2` + remainingLength + packetIdentifier + a non-empty list
     * of length-prefixed topic names. Per §3.10.1 the bottom-bit-2
     * flag (0x02) is reserved-and-must-be-set; the variant defaults
     * the header byte to `0xA2` to encode this on the wire.
     *
     * Wire layout per §3.10.1:
     *
     * ```text
     *   A2                       fixed header (type=10 << 4 | flags=0x2)
     *   <var-int>                remaining length (1–4 bytes)
     *   <pid_msb> <pid_lsb>      packet identifier (UShort BE)
     *   <topic_lp>               topic 1 (LP-prefixed UTF-8)
     *   …                        repeated for each topic
     * ```
     *
     * The payload elements wrap as `MqttUnsubscribeTopic` (a single-
     * field data class around the LP string) so the list rides the
     * J.M.0 emitter slice (`@RemainingBytes List<@ProtocolMessage
     * T>`). The wrapper has no fixed overhead on the wire — bytes
     * are exactly `<2-byte LP><name bytes>` per element, matching
     * the spec.
     */
    @PacketType(value = 10)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Unsubscribe(
        val header: MqttFixedHeader = MqttFixedHeader(0xA2u),
        val packetIdentifier: UShort,
        @RemainingBytes val topics: List<MqttUnsubscribeTopic>,
    ) : MqttPacket<Nothing>

    /**
     * Type-11 UNSUBACK per MQTT v3.1.1 §3.11 — fixed header `0xB0`
     * + `remainingLength = 2` + `packetIdentifier`. Unlike SUBACK
     * (which carries a list of return codes), UNSUBACK in v3.1.1 is
     * fixed-shape: total wire length is always 4 bytes.
     */
    @PacketType(value = 11)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class UnsubAck(
        val header: MqttFixedHeader = MqttFixedHeader(0xB0u),
        val packetIdentifier: UShort,
    ) : MqttPacket<Nothing>

    /**
     * Type-12 PINGREQ per §3.12 — fixed header `0xC0` + remaining
     * length `0`. The spec mandates byte 2 (RL) is present and equal
     * to 0 even though there's no body; the codec writes both bytes
     * (`C0 00`) and the data-class defaults match the spec values
     * one-to-one so callers can construct `PingReq()` with no args.
     *
     * Modeling as a `data class` with all-defaulted fields rather
     * than a Kotlin `object` because the dispatcher requires variants
     * to carry the discriminator field — and the spec mandates the
     * RL byte regardless. True `object` support would only suit
     * protocols where the discriminator alone is the entire wire
     * frame; MQTT isn't one of those.
     */
    @PacketType(value = 12)
    @ProtocolMessage
    data class PingReq(
        val header: MqttFixedHeader = MqttFixedHeader(0xC0u),
    ) : MqttPacket<Nothing>

    /** Type-13 PINGRESP per §3.13 — fixed header `0xD0` + RL `0`, total `D0 00`. */
    @PacketType(value = 13)
    @ProtocolMessage
    data class PingResp(
        val header: MqttFixedHeader = MqttFixedHeader(0xD0u),
    ) : MqttPacket<Nothing>

    /**
     * Type-14 DISCONNECT — fixed header + zero-byte remaining
     * length per §3.14, total `E0 00` on the wire.
     */
    @PacketType(value = 14)
    @ProtocolMessage
    data class Disconnect(
        val header: MqttFixedHeader = MqttFixedHeader(0xE0u),
    ) : MqttPacket<Nothing>
}
