package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
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
}

/**
 * Stage F slice 6 doctrine vector — sealed dispatcher over
 * `MqttFixedHeader` exercising the `@DispatchOn` value-class
 * discriminator emit path.
 *
 * Each variant carries the fixed header as its first field. The
 * slice 6 dispatcher peeks the header byte without consuming,
 * extracts `header.packetType`, matches against `@PacketType.value`,
 * and delegates to the variant codec — which then reads the same
 * bytes (including the header field) via the slice 3
 * `FieldSpec.ValueClassScalar` path.
 *
 * Two variants are modeled here; the broader MQTT control-packet
 * set (PUBLISH with payload, SUBSCRIBE with topic filters, etc.)
 * needs Stage G's variable-length list shape and is deferred.
 *
 * Wire layout per MQTT-3.1.1:
 *
 * ```text
 * Connect (type 1, header byte typically 0x10):
 *   10                       fixed header
 *   00 02                    keep-alive seconds (2-byte BE)
 *   00 04 'a' 'b' 'c' 'd'    client id (LengthPrefixed)
 *
 * Disconnect (type 14, header byte typically 0xE0):
 *   E0                       fixed header (no body bytes)
 * ```
 */
@DispatchOn(MqttFixedHeader::class)
@ProtocolMessage
sealed interface MqttPacket {
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
        val keepAliveSeconds: UShort,
        @com.ditchoom.buffer.codec.annotations.LengthPrefixed val clientId: String,
    ) : MqttPacket

    /**
     * Type-14 DISCONNECT — header byte only, no payload. Slice 6
     * narrow requires the variant to be a `data class` (not
     * `object`), so we model it with the header field alone.
     */
    @PacketType(value = 14)
    @ProtocolMessage
    data class Disconnect(
        val header: MqttFixedHeader = MqttFixedHeader(0xE0u),
    ) : MqttPacket
}
