package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * MQTT v3.1.1 §3.1.2.3 Connect Flags. The byte is bit-packed; the
 * codec reads/writes a single `UByte` and exposes the meaningful
 * boolean bits as `Boolean`-returning `val` properties so that
 * `@When("connectFlags.<bit>")` predicates resolve naturally
 * against the dotted value-class predicate path.
 *
 * Bit layout (LSB → MSB):
 *   0  reserved (must be 0)
 *   1  cleanSession
 *   2  willPresent
 *   3  willQoS bit 0
 *   4  willQoS bit 1
 *   5  willRetain
 *   6  passwordPresent
 *   7  usernamePresent
 *
 * `willQoS` is exposed as an `Int` (not a `Boolean`), so it does
 * not show up as a `@When` source. That's the intent — QoS is
 * routing information, not a presence bit.
 *
 * Lives in its own file rather than nested inside `MqttPacket.kt`
 * so the value-class doctrine fixture stays load-bearing
 * for emitter regression coverage independently of the sealed
 * dispatcher's lifecycle.
 */
@JvmInline
@ProtocolMessage
value class MqttConnectFlags(
    val raw: UByte,
) {
    val cleanSession: Boolean get() = (raw.toInt() and 0x02) != 0
    val willPresent: Boolean get() = (raw.toInt() and 0x04) != 0
    val willRetain: Boolean get() = (raw.toInt() and 0x20) != 0
    val passwordPresent: Boolean get() = (raw.toInt() and 0x40) != 0
    val usernamePresent: Boolean get() = (raw.toInt() and 0x80) != 0

    init {
        // Bit 0 is reserved per §3.1.2.3 [MQTT-3.1.2-3].
        require((raw.toInt() and 0x01) == 0) {
            "MqttConnectFlags reserved bit 0 must be zero (spec §3.1.2.3); got 0x" + raw.toString(16)
        }
        // WillQoS bits 3-4 form a 2-bit QoS field; value 3
        // is malformed per §3.1.2.6 [MQTT-3.1.2-13]. If !willPresent the bits MUST
        // be 0 [MQTT-3.1.2-14]. Shared between v3 and v5 — both spec sections
        // align here.
        val willQoS = (raw.toInt() shr 3) and 0x03
        require(willQoS != 3) {
            "MqttConnectFlags willQoS must not be 3 (spec §3.1.2.6); got 0x" + raw.toString(16)
        }
        require(willPresent || willQoS == 0) {
            "MqttConnectFlags willQoS must be 0 when willPresent is false (spec §3.1.2.6); " +
                "got 0x" + raw.toString(16)
        }
    }
}
