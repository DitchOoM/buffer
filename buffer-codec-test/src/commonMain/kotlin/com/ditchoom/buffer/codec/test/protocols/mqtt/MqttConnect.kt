package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.annotations.WhenTrue
import kotlin.jvm.JvmInline

/**
 * MQTT v3.1.1 §3.1.2.3 Connect Flags. The byte is bit-packed; the
 * codec reads/writes a single `UByte` and exposes the meaningful
 * boolean bits as `Boolean`-returning `val` properties so that
 * `@WhenTrue("connectFlags.<bit>")` predicates resolve naturally.
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

/**
 * Stage E slice 5b + Stage G slice 8 doctrine vector — full
 * MQTT v3.1.1 CONNECT packet per §3.1 as a single
 * `@ProtocolMessage`. Composes the fixed header byte + var-int
 * `@RemainingLength` (slice 8) with the variable header + payload
 * (slice 5b). Load-bearing vector for every Stage E + G annotation
 * `PHASE_9_RESET.md` mentions:
 *
 *   - `@LengthPrefixed val: String` — protocol name, client id,
 *     and the four optional payload fields. Slice 5a allowed
 *     non-terminal placement so the protocol name and client id
 *     can sit before the conditional body.
 *   - Value-class field — `connectFlags: MqttConnectFlags`. Slice
 *     3 lifted value-class fields to a first-class shape; the
 *     dotted-form `@WhenTrue("connectFlags.<bit>")` resolver
 *     exercises the same machinery slice 3 introduced.
 *   - `@WhenTrue` dotted form — every optional payload field
 *     gates on a `Boolean`-returning property of `connectFlags`.
 *     Slice 3.5 widened the inner type to `LengthPrefixedString`,
 *     which is exactly the optional shape MQTT specifies.
 *   - Sequential peek walk — slice 5a's peek generalisation is
 *     what lets the four optional fields plus the leading
 *     variable-length protocol name + client id all coexist with
 *     deterministic stream framing.
 *
 * Wire layout per MQTT-3.1.1 §3.1 (variable header + payload, not
 * including the fixed header which is a separate dispatcher
 * concern handled by Stage F):
 *
 * ```text
 * Variable header
 *   00 04 'M' 'Q' 'T' 'T'    protocol name "MQTT" with 2-byte BE prefix
 *   04                        protocol level 4 (v3.1.1)
 *   <flags>                   bit-packed connect flags
 *   <ka_msb> <ka_lsb>         keepalive seconds (UShort BE)
 *
 * Payload (in this order, each 2-byte BE prefix + UTF-8 body)
 *   client id     (always present)
 *   will topic    (only if willPresent)
 *   will message  (only if willPresent)
 *   username      (only if usernamePresent)
 *   password      (only if passwordPresent)
 * ```
 *
 * Will-message and password are technically arbitrary bytes per
 * the spec; this fixture models them as `String` because the
 * Stage E `@LengthPrefixed`-inner universe is `String` only
 * (Stage H widens to `@Payload` slots for arbitrary bytes).
 */
@ProtocolMessage
data class MqttConnect(
    val header: MqttFixedHeader,
    @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt,
    @LengthPrefixed val protocolName: String,
    val protocolLevel: UByte,
    val connectFlags: MqttConnectFlags,
    val keepAliveSeconds: UShort,
    @LengthPrefixed val clientId: String,
    @LengthPrefixed @WhenTrue("connectFlags.willPresent") val willTopic: String? = null,
    @LengthPrefixed @WhenTrue("connectFlags.willPresent") val willMessage: String? = null,
    @LengthPrefixed @WhenTrue("connectFlags.usernamePresent") val username: String? = null,
    @LengthPrefixed @WhenTrue("connectFlags.passwordPresent") val password: String? = null,
)
