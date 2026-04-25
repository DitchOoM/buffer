package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * Probe protocols built specifically to test what the codec processor can and cannot
 * generate for the MQTT v5 property-bag pattern. Goal: identify the precise gaps that
 * force `MqttPropertyCodecExt.kt`'s 265 lines of hand-written code in mqtt-models-v5.
 */

@JvmInline
@ProtocolMessage
value class ProbePropId(
    val raw: UByte,
) {
    @DispatchValue
    val id: Int get() = raw.toInt()
}

/**
 * Probe 1 — a sealed property dispatch with TWO distinct `@Payload` variants, each
 * with its OWN type parameter name. Mirrors `MqttProperty`'s `CorrelationData<D>` +
 * `AuthenticationData<D>`.
 *
 * The hand-written `BinaryPropertyDecoders<CD, AD>` uses different types per variant.
 * Question: does the processor generate two distinct type parameters, or fold them
 * into one shared `<D>`? (Read the generated code to see.)
 */
@DispatchOn(ProbePropId::class)
@ProtocolMessage
sealed interface ProbeProp {
    @PacketType(value = 0x01, wire = 0x01)
    @ProtocolMessage
    @JvmInline
    value class BoolProp(val raw: UByte) : ProbeProp

    @PacketType(value = 0x09, wire = 0x09)
    @ProtocolMessage
    data class CorrelationProbe<@Payload CD>(
        val length: UShort,
        @LengthFrom("length") val data: CD,
    ) : ProbeProp

    @PacketType(value = 0x16, wire = 0x16)
    @ProtocolMessage
    data class AuthDataProbe<@Payload AD>(
        val length: UShort,
        @LengthFrom("length") val data: AD,
    ) : ProbeProp

    @PacketType(value = 0x21, wire = 0x21)
    @ProtocolMessage
    @JvmInline
    value class UShortProp(val value: UShort) : ProbeProp

    @PacketType(value = 0x26, wire = 0x26)
    @ProtocolMessage
    data class StringPair(
        @LengthPrefixed val key: String,
        @LengthPrefixed val value: String,
    ) : ProbeProp
}

/**
 * Probe 2 — wrap a `List<ProbeProp>` in a PropertyBag and try the existing
 * `@LengthPrefixed(LengthPrefix.Short)` mechanism to see whether the byte-count
 * slicing path (NestedMessageWithLengthField) works for an arbitrary-element list.
 *
 * If this round-trips: gap #1 is solely the absence of `LengthPrefix.VariableByteInteger`
 * — the byte-slicing infrastructure for nested messages already exists.
 */
@ProtocolMessage
data class ProbePropertyBag(
    @com.ditchoom.buffer.codec.annotations.RemainingBytes val items: List<ProbeProp>,
)

@ProtocolMessage
data class ProbeAckShortPrefixed(
    val packetId: UShort,
    @LengthPrefixed val bag: ProbePropertyBag,
)

// Probe 3 — try the natural MQTT v5 declarative form: VBI byte-length prefix on a
// nested property bag. EXPECTED TO FAIL TO COMPILE: LengthPrefix enum has only
// Byte / Short / Int. No VariableByteInteger variant exists. Uncomment to confirm:
//
// @ProtocolMessage
// data class ProbeAckVbiPrefixed(
//     val packetId: UShort,
//     @LengthPrefixed(LengthPrefix.VariableByteInteger) val bag: ProbePropertyBag,
// )

/**
 * Probe 4 — does `@WhenRemaining` compose with a nested length-prefixed bag?
 * The v5 ack packets need: optional reason code, optional property section.
 * If this round-trips with `bag = null`, gap is purely about VBI; otherwise
 * `@WhenRemaining` on a nested-length field is also broken.
 */
@ProtocolMessage
data class ProbeAckOptional(
    val packetId: UShort,
    @com.ditchoom.buffer.codec.annotations.WhenRemaining(1) val reasonCode: UByte? = null,
    @com.ditchoom.buffer.codec.annotations.WhenRemaining(2) @LengthPrefixed val bag: ProbePropertyBag? = null,
)
