package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.LengthPrefix
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.WhenTrue
import com.ditchoom.buffer.codec.test.annotations.PropertyBag
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
    value class BoolProp(
        val raw: UByte,
    ) : ProbeProp

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
    value class UShortProp(
        val value: UShort,
    ) : ProbeProp

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

/**
 * Probe 5 — does `@WhenTrue` compose with an SPI-driven custom strategy (`@PropertyBag`)?
 * This is the question that gates the v5 CONNECT migration: CONNECT carries an always-present
 * property bag plus a will-property bag that is only present when `connectFlags.willFlag` is set.
 *
 * `addFieldRead`/`addFieldWrite` in `FieldCodeEmitter` wrap any strategy in the conditional
 * block (including the `Custom` strategy produced by SPI providers), and `ConditionalValidator`
 * does not gate by strategy — so the expectation is that this composes cleanly. Empirical
 * confirmation lives below.
 */
@JvmInline
@ProtocolMessage
value class ProbeConnectFlags(
    val raw: UByte,
) {
    val willFlag: Boolean get() = (raw.toInt() shr 2) and 1 == 1
}

@ProtocolMessage
data class ProbeConnectWithWillProps(
    val flags: ProbeConnectFlags,
    @PropertyBag val properties: Map<Int, Int>,
    @WhenTrue("flags.willFlag") @PropertyBag val willProperties: Map<Int, Int>? = null,
)

/**
 * Probe 6 — does `@PropertyBag` (an SPI custom strategy) need to combine with `@LengthPrefixed`
 * or `@LengthFrom`?
 *
 * Looking at `FieldAnalyzer.resolveStrategy`: the SPI custom-annotation branch returns
 * immediately without ever inspecting other length annotations on the same parameter. So
 * `@LengthPrefixed @PropertyBag` and `@PropertyBag` should produce identical wire bytes —
 * the prefix is silently dropped. Probe confirms this empirically; the migration design
 * decision is to keep the SPI self-bounded (the property bag already carries its own VBI
 * length prefix internally) and treat any combination as a processor warning at minimum.
 */
@ProtocolMessage
data class ProbePropBagPlain(
    val packetId: UShort,
    @PropertyBag val properties: Map<Int, Int>,
)

@ProtocolMessage
data class ProbePropBagWithRedundantPrefix(
    val packetId: UShort,
    @LengthPrefixed @PropertyBag val properties: Map<Int, Int>,
)

/**
 * Probe 7 — regression probe for B-6: codec processor must emit `@WhenTrue` if-blocks
 * for fields in a sealed `@PacketType` variant that ALSO carries a `<@Payload P>` type
 * parameter.
 *
 * Empirical evidence the bug exists today (in `mqtt/models-v5/build`):
 * - Top-level `@ProtocolMessage data class ConnectV5Body<@Payload WP>(...)` with
 *   `@WhenTrue("connectFlags.willFlag")` on willTopic/willProperties/willPayload/etc.
 *   generates correct `if (connectFlags.willFlag) { read } else null` blocks.
 * - The IDENTICAL field shape declared as a NESTED `@PacketType` sealed variant inside
 *   a `@DispatchOn(...) sealed interface` generates UNCONDITIONAL reads/writes —
 *   wire-format-incorrect AND a compile error from passing `String?` to non-null
 *   `writeLengthPrefixedUtf8String`.
 *
 * This probe reproduces the bug as the SMALLEST shape that triggers it. When the
 * processor is fixed, the test below (`whenTrueComposesInsideSealedPayloadVariant`)
 * will pass; before the fix, it should fail at compile time with the same error
 * `Argument type mismatch: actual type is 'String?', but 'String' was expected.` that
 * blocks slice 5 (CONNECT) and slice 6 (PUBLISH).
 *
 * The fix lives in `buffer-codec-processor` — likely in `FieldAnalyzer.kt`'s
 * extraction path for sealed-variant @PacketType classes that carry @Payload, where
 * `field.condition` is somehow lost. See SESSION_RESUME.md → "B-6" for the
 * investigation notes.
 */
@JvmInline
@ProtocolMessage
value class ProbeWillFlags(
    val raw: UByte,
) {
    @DispatchValue
    val packetType: Int get() = (raw.toInt() shr 4) and 0x0F

    val willFlag: Boolean get() = (raw.toInt() shr 2) and 1 == 1
}

@DispatchOn(ProbeWillFlags::class)
@ProtocolMessage
sealed interface ProbeWillTree {
    /**
     * Mirror of V5 CONNECT shape: generic `<@Payload WP>` plus a `@PropertyBag` SPI custom
     * strategy plus three @WhenTrue conditional fields including a `@WhenTrue @PropertyBag`.
     * Reproduces B-6 as a sealed-variant.
     */
    @PacketType(value = 1, wire = 0x10)
    @ProtocolMessage
    data class ConnectLike<@Payload WP>(
        val flags: ProbeWillFlags,
        @PropertyBag val properties: Map<Int, Int>,
        @LengthPrefixed val clientId: String,
        @WhenTrue("flags.willFlag") @PropertyBag val willProperties: Map<Int, Int>? = null,
        @WhenTrue("flags.willFlag") @LengthPrefixed val willTopic: String? = null,
        @WhenTrue("flags.willFlag") @LengthPrefixed val willPayload: WP? = null,
        @WhenTrue("flags.willFlag") @LengthPrefixed val willTrace: String? = null,
    ) : ProbeWillTree

    /**
     * Sibling non-payload variant — proves the failure is specific to the payload variant,
     * not the sealed dispatch itself.
     */
    @PacketType(value = 2, wire = 0x20)
    @ProtocolMessage
    data object Marker : ProbeWillTree
}

/**
 * Control case — top-level `@ProtocolMessage data class` (not a sealed variant) with
 * IDENTICAL field shape to `ProbeWillTree.ConnectLike`. The codec processor generates
 * this correctly TODAY. If this stops working alongside the sealed-variant fix, the fix
 * over-corrected and broke the working case.
 */
@ProtocolMessage
data class ProbeTopLevelConnectLike<@Payload WP>(
    val flags: ProbeWillFlags,
    @PropertyBag val properties: Map<Int, Int>,
    @LengthPrefixed val clientId: String,
    @WhenTrue("flags.willFlag") @PropertyBag val willProperties: Map<Int, Int>? = null,
    @WhenTrue("flags.willFlag") @LengthPrefixed val willTopic: String? = null,
    @WhenTrue("flags.willFlag") @LengthPrefixed val willPayload: WP? = null,
    @WhenTrue("flags.willFlag") @LengthPrefixed val willTrace: String? = null,
)

// =====================================================================================
// Phase 1 probes — `LengthPrefix.Varint` + `@DispatchOn(bodyLength = ...)`.
//
// The Probe[1..7] block above audited what the codec processor *can* generate today.
// The 1.0 phase below adds 9 probes that audit what it *cannot* yet generate — the
// missing primitives that force ~700 lines of hand-written orchestration in mqtt
// models-v5. Each probe must fail to compile or fail at runtime until Phase 1.1 lands.
//
// Boundary intent (probes 8/9): exercise every VBI byte-width transition so the
// patch-up logic is constrained to be correct at 1↔2, 2↔3, 3↔4, 4-byte-overflow.
//   1-byte VBI  → bodyLen ≤ 127           (0x7F)
//   2-byte VBI  → 128 ≤ bodyLen ≤ 16383   (0x3FFF)
//   3-byte VBI  → 16384 ≤ bodyLen ≤ 2097151 (0x1FFFFF)
//   4-byte VBI  → 2097152 ≤ bodyLen ≤ 268435455 (0xFFFFFFF)
// =====================================================================================

/**
 * Probe 8 — VBI byte-length-prefixed nested @ProtocolMessage. Mirrors the v5 MQTT
 * property-bag wire shape: outer reads VBI byte length, slices, nested decodes from
 * slice via @RemainingBytes. The hand-written `MqttPropertyCodecExt.kt` exists today
 * solely because no `LengthPrefix.Varint` enum value was available.
 *
 * Test [V5GapProbeTest.varintNestedRoundTripsAtAllVbiBoundaries] round-trips at
 * each VBI byte-width boundary (0, 127, 128, 16383, 16384, 2097151, 2097152, 268435455).
 */
@ProtocolMessage
data class ProbeVarLenBody(
    @RemainingBytes val data: String,
)

@ProtocolMessage
data class ProbeVarintNested(
    val packetId: UShort,
    @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val body: ProbeVarLenBody,
)

/**
 * Probe 9 — `maxBytes = 2` caps the VBI prefix to a 2-byte width. A body that needs
 * 3-byte VBI must throw `IllegalArgumentException` at encode-time with a message that
 * names the field and the offending length. This is the spec-cap path: MQTT uses
 * `maxBytes = 4`; protocols with tighter caps (HTTP/2 SETTINGS, gRPC stream IDs) want
 * the same shape with smaller caps.
 *
 * Test [V5GapProbeTest.varintMaxBytesOverflowThrowsAtEncode] constructs a 16384-byte
 * body and asserts the encode throws with the expected message shape.
 */
@ProtocolMessage
data class ProbeVarintMaxBytesOverflow(
    val packetId: UShort,
    @LengthPrefixed(LengthPrefix.Varint, maxBytes = 2) val body: ProbeVarLenBody,
)

/**
 * Probe 10 — VBI byte-length prefix on a nested message that holds a `List<ProbeProp>`.
 * Demonstrates that varint composes with the existing `@RemainingBytes List<T>` slicing
 * path (probe 2 in the audit block above already validated that path with `Short` prefix).
 * If probes 2 and 10 round-trip with identical decoded values across the same items,
 * the only difference is the prefix encoding — confirming the missing primitive is
 * isolated to `LengthPrefix.Varint` and not the byte-slicing infrastructure.
 */
@ProtocolMessage
data class ProbeVarintListBag(
    @RemainingBytes val items: List<ProbeProp>,
)

@ProtocolMessage
data class ProbeVarintCollection(
    val packetId: UShort,
    @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val bag: ProbeVarintListBag,
)

/**
 * Probe 11 — VBI byte-length prefix on a String. Same shape as `@LengthPrefixed val
 * name: String` (default `Short` prefix) but with a varint prefix instead. Confirms
 * the codec processor can emit varint patch-up for primitive String fields, not just
 * nested @ProtocolMessage fields.
 */
@ProtocolMessage
data class ProbeVarintString(
    @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val name: String,
)

/**
 * Probe 12 — sealed dispatch with `bodyLength = LengthPrefix.Varint` on `@DispatchOn`.
 * The dispatcher must:
 *   - encode: write discriminator byte, reserve VBI placeholder, encode body, patch VBI
 *   - decode: read discriminator, read VBI bodyLen, slice body, dispatch on slice
 * Together these lift the `[byte1][VBI(remainingLength)][body]` framing out of every
 * MQTT call site (deletes ~80 lines of `serialize()`/`encodeBody()`/`fixedHeader()`
 * from `ControlPacket.kt` plus 116 lines of `ControlPacketV5.fromTyped()`).
 */
@JvmInline
@ProtocolMessage
value class ProbeFramedTag(
    val raw: UByte,
) {
    @DispatchValue
    val typeId: Int get() = raw.toInt()
}

@DispatchOn(ProbeFramedTag::class, bodyLength = LengthPrefix.Varint, bodyLengthMaxBytes = 4)
@ProtocolMessage
sealed interface ProbeFramedDispatchSimple {
    @PacketType(value = 1, wire = 0x01)
    @ProtocolMessage
    data class Alpha(
        val x: UShort,
    ) : ProbeFramedDispatchSimple

    @PacketType(value = 2, wire = 0x02)
    @ProtocolMessage
    data class Beta(
        val y: UInt,
        val z: UByte,
    ) : ProbeFramedDispatchSimple

    @PacketType(value = 3, wire = 0x03)
    @ProtocolMessage
    data class Gamma(
        @LengthPrefixed val message: String,
    ) : ProbeFramedDispatchSimple
}

/**
 * Probe 13 — sealed dispatch with `bodyLength = LengthPrefix.Varint` plus a
 * `<@Payload P>` variant. This is the shape needed by MQTT v5 PUBLISH, where the
 * payload is application-typed but framed by the same VBI body length as every other
 * V5 packet. Validates that the VBI patch-up emit code interacts correctly with
 * payload reader/writer scope: the patch position is captured *outside* the payload
 * encode lambda but the body length must include payload bytes.
 */
@DispatchOn(ProbeFramedTag::class, bodyLength = LengthPrefix.Varint, bodyLengthMaxBytes = 4)
@ProtocolMessage
sealed interface ProbeFramedDispatchWithPayload {
    @PacketType(value = 1, wire = 0x01)
    @ProtocolMessage
    data class WithBytes<@Payload P>(
        val header: UByte,
        @LengthPrefixed val topic: String,
        @RemainingBytes val payload: P,
    ) : ProbeFramedDispatchWithPayload

    @PacketType(value = 2, wire = 0x02)
    @ProtocolMessage
    data class Plain(
        val x: UShort,
    ) : ProbeFramedDispatchWithPayload
}
