package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * MQTT v3.1.1 §3.1.2.3 Connect Flags. The byte is bit-packed; the
 * codec reads/writes a single `UByte` and exposes the meaningful
 * boolean bits as `Boolean`-returning `val` properties so that
 * `@WhenTrue("connectFlags.<bit>")` predicates resolve naturally
 * against the slice 3 dotted value-class predicate path.
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
 * not show up as a `@WhenTrue` source. That's the intent — QoS is
 * routing information, not a presence bit.
 *
 * Lives in its own file rather than nested inside `MqttPacket.kt`
 * so the slice 3 value-class doctrine fixture stays load-bearing
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
}
