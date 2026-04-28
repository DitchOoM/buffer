package com.ditchoom.buffer.codec.processor.ir

import com.squareup.kotlinpoet.ClassName

/**
 * One field on a `Plan.Leaf` or `VariantPlan`.
 *
 * Pairs the declared name + type with an IR-level encoding strategy and an optional
 * `@When` conditionality.
 */
data class FieldPlan(
    val name: String,
    val type: TypeFqn,
    val strategy: FieldStrategy,
    val conditionality: Conditionality = Conditionality.Always,
)

/** A group of bit-extracted fields that share a single underlying byte / multi-byte read. */
data class Batch(
    val sourceField: String,
    val widthBytes: Int,
    val extractions: List<BatchExtraction>,
)

/** One destination field within a `Batch`. */
data class BatchExtraction(
    val targetField: String,
    val mask: Int,
    val shift: Int,
)

/**
 * IR-level encoding strategy for a single field.
 *
 * Each variant captures the full information needed to emit decode + encode + wireSize
 * for the field; mutually exclusive shapes (e.g., inline length vs. length-from-other-field)
 * are mutually exclusive types.
 */
sealed interface FieldStrategy {
    /** Fixed-width primitive read/write. */
    data class Primitive(
        val kind: PrimitiveKind,
        val wireBytes: Int,
        val order: Endianness,
    ) : FieldStrategy

    /** Variable-byte integer (`@VariableByteInteger`). */
    data class VarInt(
        val maxBytes: Int,
    ) : FieldStrategy

    /** UTF-8 string with an associated length source. */
    data class StringField(
        val length: LengthSource,
    ) : FieldStrategy

    /** Collection field; element codec + length source. */
    @Suppress("ktlint:standard:class-naming")
    data class Collection_(
        val elementCodec: ElementCodecRef,
        val length: LengthSource,
    ) : FieldStrategy

    /** Nested `@ProtocolMessage` field — delegates to the nested type's generated codec. */
    data class NestedMessage(
        val codec: ClassName,
    ) : FieldStrategy

    /** Field handled by an external `@UseCodec` codec class. */
    data class External(
        val codec: ClassName,
        val contextualOverloads: Boolean,
    ) : FieldStrategy

    /**
     * Variant-side field whose value comes from the parent dispatcher's discriminator.
     * The variant decoder reads it from `DecodeContext`; the variant encoder skips writing it
     * (the dispatcher already wrote the discriminator bytes).
     */
    data class DiscriminatorOwned(
        val parentDispatchOn: TypeFqn,
    ) : FieldStrategy

    /** Field whose declared type is a `@Payload` type parameter — sliced from the body buffer. */
    data class PayloadSlot(
        val typeParam: String,
        val length: LengthSource,
    ) : FieldStrategy

    /** Field handled by a registered `CodecFieldProvider` SPI. */
    data class Spi(
        val provider: ProviderId,
        val descriptor: SpiDescriptor,
    ) : FieldStrategy
}

/** Where a length-prefixed / framed field reads its length from. */
sealed interface LengthSource {
    /** Inline prefix on the wire: `@LengthPrefixed`. */
    data class Inline(
        val encoding: LengthEncoding,
        val maxBytes: Int,
    ) : LengthSource

    /** Length supplied by another field on the same message: `@LengthFrom("name")`. */
    data class FromField(
        val name: String,
        val kind: PrimitiveKind,
    ) : LengthSource

    /** Length is "everything that remains in the buffer": `@RemainingBytes`. */
    data class Remaining(
        val trailingBytes: Int,
    ) : LengthSource
}

/** Whether a field is always present, or only when a `@When` expression evaluates true. */
sealed interface Conditionality {
    data object Always : Conditionality

    data class WhenExpr(
        val expr: BooleanExpression,
    ) : Conditionality
}

/** Reference to the element codec for a `Collection_` field. */
data class ElementCodecRef(
    val codec: ClassName,
    val elementType: TypeFqn,
)

/** Identifier of a registered `CodecFieldProvider`. */
@JvmInline
value class ProviderId(
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "ProviderId must not be blank" }
    }
}

/** Provider-specific descriptor produced by a `CodecFieldProvider` for a single field.
 *
 * - [raw] is the legacy single-string inline expression (kept for backward compatibility
 *   with fixtures that don't yet split decode vs encode). When non-blank it backs both
 *   [decodeRaw] and [encodeRaw] unless the caller passes asymmetric overrides.
 * - [decodeRaw] is the inline **decode** expression text the LeafEmitter substitutes on
 *   the decode site, e.g. `buffer.readFoo()` or `MyCodec.decode(buffer, context)`. When
 *   blank in the constructor, falls back to [raw] at access time.
 * - [encodeRaw] is the inline **encode** expression text the LeafEmitter substitutes on
 *   the encode site, e.g. `buffer.writeFoo(value.field)` or
 *   `MyCodec.encode(buffer, value.field, context)`. When blank in the constructor,
 *   falls back to [raw] at access time.
 * - [fixedSize] is the constant wire size in bytes; `-1` means variable-size and requires
 *   [wireSizeRaw] to be non-blank so the emitter can substitute a runtime size expression
 *   (mirrors legacy `CustomFieldDescriptor.wireSizeFunction` lowering — typically
 *   `MyCodec.wireSize(value.field)`).
 *
 * Asymmetric SPI providers (legacy `CustomFieldDescriptor` with separate
 * `readFunction` / `writeFunction` `FunctionRef`s) construct a descriptor where
 * [decodeRaw] and [encodeRaw] differ — Slice 5.5 splits them so the emitter can
 * route each to the correct call site.
 */
data class SpiDescriptor(
    val raw: String = "",
    val fixedSize: Int = -1,
    val wireSizeRaw: String = "",
    val decodeRaw: String = raw,
    val encodeRaw: String = raw,
)
