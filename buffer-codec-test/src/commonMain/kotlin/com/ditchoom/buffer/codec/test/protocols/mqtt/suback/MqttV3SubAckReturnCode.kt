package com.ditchoom.buffer.codec.test.protocols.mqtt.suback

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * Phase J.M.5 slice 15g — typed return code for MQTT v3.1.1 SUBACK
 * (§3.9.3). Mirrors slice 12's
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
 * via slice 11a's `@RemainingBytes List<sealed parent>` widening, no
 * emitter work beyond what already landed.
 *
 * Replaces the previous `@RemainingBytes List<UByte>` shape — that path
 * is being retired (slice 15g principle: don't promote copy-based byte
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
     * Variants are `data class` rather than `data object` to round-trip
     * the discriminator byte through the value-class scalar path
     * (each variant's `id` field is read/written by its generated
     * codec). The data-object equivalent under `@DispatchOn(value
     * class)` emits a 0-byte variant codec — the parent peeks +
     * resets, and a data-object variant has no field to consume the
     * discriminator on decode, so the position never advances. Fixing
     * that emitter case is its own slice; for now data-class variants
     * cost one allocation per decoded entry on JS but stay correct
     * on every platform.
     */
    @PacketType(value = 0x00)
    @ProtocolMessage
    data class SuccessMaximumQoS0(
        val id: MqttV3SubAckReturnCodeRaw = MqttV3SubAckReturnCodeRaw(0x00u),
    ) : MqttV3SubAckReturnCode

    /** §3.9.3 — `0x01` Success - Maximum QoS 1. */
    @PacketType(value = 0x01)
    @ProtocolMessage
    data class SuccessMaximumQoS1(
        val id: MqttV3SubAckReturnCodeRaw = MqttV3SubAckReturnCodeRaw(0x01u),
    ) : MqttV3SubAckReturnCode

    /** §3.9.3 — `0x02` Success - Maximum QoS 2. */
    @PacketType(value = 0x02)
    @ProtocolMessage
    data class SuccessMaximumQoS2(
        val id: MqttV3SubAckReturnCodeRaw = MqttV3SubAckReturnCodeRaw(0x02u),
    ) : MqttV3SubAckReturnCode

    /** §3.9.3 — `0x80` Failure. */
    @PacketType(value = 0x80)
    @ProtocolMessage
    data class Failure(
        val id: MqttV3SubAckReturnCodeRaw = MqttV3SubAckReturnCodeRaw(0x80u),
    ) : MqttV3SubAckReturnCode
}
