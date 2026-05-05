package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.annotations.WhenTrue
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
     * on `@WhenTrue("header.qosGreaterThanZero")` via the slice-3
     * dotted value-class predicate path.
     */
    val qosGreaterThanZero: Boolean get() = (raw.toUInt() and 0x06u) != 0u
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
 *   <var-int>                remaining length (= 2 + 2 + clientId.length bytes)
 *   00 02                    keep-alive seconds (2-byte BE)
 *   00 04 'a' 'b' 'c' 'd'    client id (LengthPrefixed)
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
@ProtocolMessage
sealed interface MqttPacket<out P : Payload> {
    /**
     * Type-1 CONNECT, simplified to the fields slice 6 cleanly
     * exercises (full CONNECT lives in `MqttConnect`; combining the
     * full body with `@DispatchOn` would require duplicating the
     * dispatcher fixture and isn't load-bearing for the slice 6
     * dispatcher mechanic).
     */
    @PacketType(value = 1)
    @ProtocolMessage
    data class Connect(
        val header: MqttFixedHeader = MqttFixedHeader(0x10u),
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt,
        val keepAliveSeconds: UShort,
        @LengthPrefixed val clientId: String,
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
     *     Modeled with `@WhenTrue("header.qosGreaterThanZero")`
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
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt,
        @LengthPrefixed val topic: String,
        @WhenTrue("header.qosGreaterThanZero") val packetId: PacketId? = null,
        @RemainingBytes val payload: P,
    ) : MqttPacket<P>

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
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt,
        val packetIdentifier: UShort,
        @RemainingBytes val returnCodes: List<UByte>,
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
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt = 0u,
    ) : MqttPacket<Nothing>

    /** Type-13 PINGRESP per §3.13 — fixed header `0xD0` + RL `0`, total `D0 00`. */
    @PacketType(value = 13)
    @ProtocolMessage
    data class PingResp(
        val header: MqttFixedHeader = MqttFixedHeader(0xD0u),
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt = 0u,
    ) : MqttPacket<Nothing>

    /**
     * Type-14 DISCONNECT — fixed header + zero-byte remaining
     * length per §3.14, total `E0 00` on the wire.
     */
    @PacketType(value = 14)
    @ProtocolMessage
    data class Disconnect(
        val header: MqttFixedHeader = MqttFixedHeader(0xE0u),
        @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt = 0u,
    ) : MqttPacket<Nothing>
}
