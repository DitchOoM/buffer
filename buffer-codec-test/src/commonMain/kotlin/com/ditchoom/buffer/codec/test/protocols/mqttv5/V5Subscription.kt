package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * MQTT v5.0 §3.8.3 Subscription — a single entry in the SUBSCRIBE
 * topic-filter list. Wire layout: `<topic LP> <opts (UByte)>`.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class V5Subscription(
    @LengthPrefixed val topicFilter: String,
    val subscriptionOptions: V5SubscriptionOptions,
)

/**
 * Phase J.M.5 slice 12 — typed subscription-options byte per §3.8.3.1.
 *
 * Wire form is a single `UByte` packing four fields:
 *
 * ```text
 *   bit 7 6 | 5 4 | 3 | 2 | 1 0
 *   reserv  | RH  | RP| NL| QoS
 * ```
 *
 *  - **bits 0-1 — QoS**: Maximum QoS the server may deliver (0, 1, 2;
 *    3 is reserved-must-not-be-used per [MQTT-3.8.3-2]).
 *  - **bit 2 — No Local (NL)**: When `true`, the server must not
 *    forward Application Messages published by the client back to it
 *    on this subscription.
 *  - **bit 3 — Retain As Published (RP)**: When `true`, the server
 *    forwards the RETAIN flag from the original PUBLISH; when
 *    `false`, the server clears it.
 *  - **bits 4-5 — Retain Handling (RH)**: 0 = send retained at
 *    subscribe; 1 = send only if the subscription doesn't already
 *    exist; 2 = don't send retained. 3 is reserved per
 *    [MQTT-3.8.3-4].
 *  - **bits 6-7 — reserved**: Must be zero per [MQTT-3.8.3-1].
 *
 * Modeled as a `@JvmInline value class` over `UByte` carrying the
 * byte verbatim. The codec emits and reads the raw byte through the
 * existing value-class scalar path; computed properties expose the
 * decoded fields, and the init-block forecloses construction with
 * any of the three impossible-state shapes (reserved bits non-zero,
 * QoS=3, RetainHandling=3).
 */
@JvmInline
@ProtocolMessage
value class V5SubscriptionOptions(
    val raw: UByte,
) {
    val qos: Int get() = raw.toInt() and 0b11
    val noLocal: Boolean get() = (raw.toInt() and 0b100) != 0
    val retainAsPublished: Boolean get() = (raw.toInt() and 0b1000) != 0
    val retainHandling: Int get() = (raw.toInt() shr 4) and 0b11

    init {
        // [MQTT-3.8.3-1] — reserved bits 6-7 must be zero. Caller is
        // responsible per row 16 trust contract; the require fires at
        // construction time.
        require(raw.toInt() and 0b1100_0000 == 0) {
            "v5 subscription options invariant: reserved bits 6-7 must be zero, " +
                "got 0x" + raw.toString(16) + " (spec §3.8.3.1, [MQTT-3.8.3-1])"
        }
        // [MQTT-3.8.3-2] — QoS=3 is malformed (only 0, 1, 2 are valid).
        require(raw.toInt() and 0b11 != 0b11) {
            "v5 subscription options invariant: QoS=3 is reserved-must-not-be-used, " +
                "got 0x" + raw.toString(16) + " (spec §3.8.3.1, [MQTT-3.8.3-2])"
        }
        // [MQTT-3.8.3-4] — RetainHandling=3 is reserved.
        require((raw.toInt() shr 4) and 0b11 != 0b11) {
            "v5 subscription options invariant: RetainHandling=3 is reserved, " +
                "got 0x" + raw.toString(16) + " (spec §3.8.3.1, [MQTT-3.8.3-4])"
        }
    }

    companion object {
        /**
         * Convenience constructor that assembles the byte from typed
         * components. Useful at fixture / test sites that would otherwise
         * compute the bit-pack inline. Same init-block invariants apply.
         */
        fun of(
            qos: Int,
            noLocal: Boolean = false,
            retainAsPublished: Boolean = false,
            retainHandling: Int = 0,
        ): V5SubscriptionOptions {
            require(qos in 0..2) {
                "v5 subscription options: qos must be 0, 1, or 2 (got $qos)"
            }
            require(retainHandling in 0..2) {
                "v5 subscription options: retainHandling must be 0, 1, or 2 (got $retainHandling)"
            }
            val raw =
                (qos and 0b11) or
                    (if (noLocal) 0b100 else 0) or
                    (if (retainAsPublished) 0b1000 else 0) or
                    ((retainHandling and 0b11) shl 4)
            return V5SubscriptionOptions(raw.toUByte())
        }
    }
}
