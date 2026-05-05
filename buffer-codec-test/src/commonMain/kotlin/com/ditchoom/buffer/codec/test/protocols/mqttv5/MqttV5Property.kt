package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
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
 * Phase J.M.5 slice 2 — typed MQTT v5.0 property dispatcher. v5 §2.2.2
 * defines ~30 properties; this slice lands two as a smoke test for the
 * dispatcher + property-bag composition. Slice 3+ grows breadth.
 *
 * Variants picked to exercise two distinct value shapes without pulling
 * in the VBI-bodied property path (`SubscriptionIdentifier` id 0x0B,
 * still deferred):
 *
 *  - [MessageExpiryInterval] (id 0x02, §3.3.2.3.3) — fixed 4-byte BE
 *    UInt body. Exercises the plain-scalar property body.
 *  - [ContentType] (id 0x03, §3.3.2.3.9) — UTF-8 string body with the
 *    standard MQTT 2-byte length prefix. Exercises `@LengthPrefixed`
 *    inside a property body.
 *
 * Each variant carries the property-id byte as its first field; the
 * dispatcher peeks the byte, extracts `id`, matches `@PacketType.value`,
 * and delegates to the variant codec which re-reads the byte through the
 * value-class scalar path. Same pattern as [MqttFixedHeader] /
 * [com.ditchoom.buffer.codec.test.protocols.mqtt.MqttPacket].
 */
@DispatchOn(MqttV5PropertyId::class)
@ProtocolMessage(wireOrder = Endianness.Big)
sealed interface MqttV5Property {
    /**
     * MQTT v5.0 §3.3.2.3.3 Message Expiry Interval (PUBLISH property).
     * Wire shape: `02 <expiry_be_4>` (1-byte id + 4-byte BE UInt).
     */
    @PacketType(value = 0x02)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class MessageExpiryInterval(
        val id: MqttV5PropertyId = MqttV5PropertyId(0x02u),
        val seconds: UInt,
    ) : MqttV5Property

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
    ) : MqttV5Property
}
