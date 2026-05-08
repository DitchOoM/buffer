package com.ditchoom.buffer.codec.test.protocols.mqtt.suback

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * Typed return code for MQTT v3.1.1 SUBACK
 * (§3.9.3). Mirrors 's
 * [com.ditchoom.buffer.codec.test.protocols.mqttv5.suback.V5SubAckReasonCode]
 * pattern: a [JvmInline] value-class raw byte holder with a
 * [DispatchValue], plus a sealed parent dispatching on it.
 *
 * v3 §3.9.3 enumerates exactly four valid bytes:
 *
 *   - `0x00` — Success — Maximum QoS 0
 *   - `0x01` — Success — Maximum QoS 1
 *   - `0x02` — Success — Maximum QoS 2
 *   - `0x80` — Failure
 *
 * Modeling them as a sealed parent makes the value-space type-system
 * enforced — no per-byte `init { require }` invariant needed — and
 * substitutes into [com.ditchoom.buffer.codec.test.protocols.mqtt.MqttPacket.SubAck.returnCodes]
 * via 's `@RemainingBytes List<sealed parent>` widening, no
 * emitter work beyond what already landed.
 *
 * Replaces the previous `@RemainingBytes List<UByte>` shape — that path
 * is being retired ( principle: don't promote copy-based byte
 * handling). On Kotlin/JS the prior shape boxed every UByte into a
 * heap object during decode; with sealed-variant elements each list
 * slot is just a reference, no per-element box.
 *
 * Lives in subpackage `mqtt.suback` so the generated
 * `${VariantSimpleName}Codec.kt` filenames don't collide with the v5
 * variant codecs in `mqttv5.suback`.
 */
@JvmInline
@ProtocolMessage
value class MqttV3SubAckReturnCodeRaw(
    val raw: UByte,
) {
    @DispatchValue
    val id: Int get() = raw.toInt()
}

@DispatchOn(MqttV3SubAckReturnCodeRaw::class)
@ProtocolMessage
sealed interface MqttV3SubAckReturnCode {
    /**
     * §3.9.3 — `0x00` Success - Maximum QoS 0.
     *
     * Variants are `data object` singletons.
     * Decoding N return codes reuses the same four instances regardless
     * of N, so the per-element allocation cost (data-class variants
     * with an `id` field) collapses to zero. The emitter generates a
     * 1-byte self-framing codec per variant: the parent dispatcher
     * peeks + resets, and the variant codec consumes the discriminator
     * byte before returning the singleton.
     */
    @PacketType(value = 0x00)
    @ProtocolMessage
    data object SuccessMaximumQoS0 : MqttV3SubAckReturnCode

    /** §3.9.3 — `0x01` Success - Maximum QoS 1. */
    @PacketType(value = 0x01)
    @ProtocolMessage
    data object SuccessMaximumQoS1 : MqttV3SubAckReturnCode

    /** §3.9.3 — `0x02` Success - Maximum QoS 2. */
    @PacketType(value = 0x02)
    @ProtocolMessage
    data object SuccessMaximumQoS2 : MqttV3SubAckReturnCode

    /** §3.9.3 — `0x80` Failure. */
    @PacketType(value = 0x80)
    @ProtocolMessage
    data object Failure : MqttV3SubAckReturnCode
}
