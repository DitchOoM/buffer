package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec

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
 * Slice 1 (this commit) lands [PingReq] only — wire-bytewise identical to
 * the v3 PINGREQ (`C0 00`). Its value is purely structural: it proves the
 * v5 sealed parent + generated codec + peek path compose cleanly before
 * the property-bag shape lands in slice 2 (PUBLISH v5).
 *
 * The sealed parent is intentionally non-generic in slice 1 — none of the
 * payload-free variants need a `<P : Payload>` slot. Slice 2 lifts to
 * `MqttV5Packet<out P : Payload>` to introduce `Publish<P>`, mirroring
 * the v3 sealed family's slice 10f lift.
 */
@DispatchOn(MqttFixedHeader::class)
@ProtocolMessage
sealed interface MqttV5Packet {
    /**
     * Type-12 PINGREQ per MQTT v5.0 §3.12 — fixed header `0xC0` + remaining
     * length `0`. Wire-bytewise identical to v3.1.1 PINGREQ; v5 carries no
     * reason code or property bag for this packet.
     *
     * Modeled as a `data class` (not `object`) so the variant carries the
     * `@DispatchOn` discriminator field, matching the v3 modeling
     * decision. The all-defaulted-fields constructor lets callers write
     * `MqttV5Packet.PingReq()` with no args.
     */
    @PacketType(value = 12)
    @ProtocolMessage
    data class PingReq(
        val header: MqttFixedHeader = MqttFixedHeader(0xC0u),
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt = 0u,
    ) : MqttV5Packet
}
