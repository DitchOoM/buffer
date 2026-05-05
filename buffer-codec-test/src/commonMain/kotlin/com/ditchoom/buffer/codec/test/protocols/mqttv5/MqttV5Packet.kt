package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.annotations.When
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
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
}
