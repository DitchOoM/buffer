package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.symbol.KSNode
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeName

/**
 * A diagnostic produced by the emitter's analysis pass. [message]
 * describes why a shape is unsupported; [node] is the offending
 * source element to anchor a KSP error on.
 *
 * Note: in the byte-identical threading commit these diagnostics are
 * constructed but NOT emitted — [CodecEmitter.tryEmit] silently skips
 * `Rejected`/`NotApplicable` results. Emission is wired up in the
 * follow-on diagnostics pass.
 */
internal data class Diagnostic(
    val message: String,
    val node: KSNode,
)

/**
 * Result of analyzing a top-level `@ProtocolMessage` symbol into a
 * [CodecShape]. Replaces the prior `CodecShape?` return:
 *   - [Supported] — a codec is generated from [shape].
 *   - [Rejected] — the shape is a SILENT GAP (per SUPPORT_MATRIX §2.5/§2.6);
 *     the follow-on pass will turn the [diagnostics] into KSP errors.
 *   - [NotApplicable] — the symbol is genuinely not this analyzer's
 *     concern (handled elsewhere) OR is already rejected by a validator
 *     diagnostic, so the analyzer stays silent.
 */
internal sealed interface AnalysisResult {
    data class Supported(
        val shape: CodecShape,
    ) : AnalysisResult

    data class Rejected(
        val diagnostics: List<Diagnostic>,
    ) : AnalysisResult

    data object NotApplicable : AnalysisResult
}

/**
 * Result of analyzing a single constructor parameter into a
 * [FieldSpec]. Replaces the prior `FieldSpec?` return:
 *   - [Ok] — the field is supported.
 *   - [Err] — the field is unsupported; [diagnostic] explains why and
 *     anchors on the offending parameter.
 */
internal sealed interface FieldAnalysis {
    data class Ok(
        val field: FieldSpec,
    ) : FieldAnalysis

    data class Err(
        val diagnostic: Diagnostic,
    ) : FieldAnalysis
}

/**
 * Result of analyzing a sealed `@ProtocolMessage` parent into a
 * [DispatchShape]. The dispatcher mirror of [AnalysisResult], replacing
 * the prior `DispatchShape?` return so the two dispatcher analyzers
 * (`analyzeSealedDispatcher` / `analyzeDispatchOnSealedDispatcher`) model
 * their outcomes as an explicit sum type rather than nullable-as-state:
 *   - [Supported] — a dispatcher codec is generated from [shape].
 *   - [Rejected] — the shape is a SILENT GAP with no paired validator
 *     diagnostic (e.g. a non-data/object variant under a simple
 *     `@PacketType` parent, or a non-peekable discriminator inner kind
 *     under `@DispatchOn`); [CodecEmitter.tryEmit] turns the
 *     [diagnostics] into KSP errors so the build fails loudly.
 *   - [NotApplicable] — not this analyzer's concern (the `@DispatchOn`
 *     analyzer falls back to the simple path) OR already rejected by a
 *     `ProtocolMessageProcessor` validator diagnostic, so the analyzer
 *     stays silent to avoid double-reporting.
 */
internal sealed interface DispatchAnalysisResult {
    data class Supported(
        val shape: DispatchShape,
    ) : DispatchAnalysisResult

    data class Rejected(
        val diagnostics: List<Diagnostic>,
    ) : DispatchAnalysisResult

    data object NotApplicable : DispatchAnalysisResult
}

internal sealed interface VariantWireSize {
    data class LiteralExact(
        val bytes: Int,
    ) : VariantWireSize

    data object RuntimeExact : VariantWireSize

    data object BackPatch : VariantWireSize

    /**
     * The dispatcher delegates `wireSize` to the variant codec rather
     * than aggregating a discriminator byte itself. Used by
     * [DiscriminatorOwnership.ReReadByVariant] dispatchers, where the
     * variant counts its own re-read discriminator — so there is no
     * dispatcher-side size to classify.
     */
    data object Delegated : VariantWireSize
}

// ---------------------------------------------------------------------------
// Unified dispatch IR (dispatch-path unification — see
// DISPATCH_UNIFICATION_PLAN.md). One DispatchShape subsumes both the simple
// @PacketType and @DispatchOn dispatch paths. Both analyzers
// (analyzeSealedDispatcher / analyzeDispatchOnSealedDispatcher) produce this
// shape directly. Every optional/variant dimension is an explicit sum type —
// no nullable-as-state — so the emitters branch on named cases rather than on
// null checks.
// ---------------------------------------------------------------------------

internal data class DispatchShape(
    val packageName: String,
    val parentClassName: ClassName,
    val parentSimpleName: String,
    val codecSimpleName: String,
    val discriminator: Discriminator,
    val variants: List<DispatchVariant>,
    val genericity: Genericity,
    val framing: Framing,
    val forwardCompat: ForwardCompat,
    val visibility: CodecVisibility,
    /**
     * Consumer-supplied frame-size override for the dispatcher. Non-null when
     * the sealed parent declares a companion object implementing
     * `com.ditchoom.buffer.codec.FrameDetector`; holds the parent type whose
     * companion to call. The dispatcher's `peekFrameSize` delegates to
     * `<customPeek>.peekFrameSize(stream, baseOffset)` instead of routing through
     * per-variant peeks — for frames whose size is opcode-independent and not
     * walker-derivable (RFC 6455 WebSocket: bit-packed escape length + folded
     * mask). See [CodecShape.customPeek].
     */
    val customPeek: ClassName? = null,
)

internal data class DispatchVariant(
    val simpleName: String,
    val className: ClassName,
    val codecClassName: ClassName,
    /** Matched value as an Int: the @PacketType byte, or the @DispatchValue. */
    val dispatchValue: Int,
    /** How the dispatcher references the variant codec (static object vs generic instance). */
    val codecRef: VariantCodecRef,
    /**
     * Dispatcher-side size classification. [VariantWireSize.Delegated]
     * for [DiscriminatorOwnership.ReReadByVariant] variants; otherwise the
     * concrete classification the dispatcher aggregates the discriminator into.
     */
    val wireSize: VariantWireSize,
)

/** How the dispatcher names the variant codec at a dispatch site. */
internal sealed interface VariantCodecRef {
    /** A static `object FooCodec` referenced by type. */
    data object StaticObject : VariantCodecRef

    /** A generic `class FooCodec<P>` instance held in a dispatcher field. */
    data class GenericInstance(
        val fieldName: String,
    ) : VariantCodecRef
}

/** The discriminator that selects a variant — the axis the two paths diverge on. */
internal sealed interface Discriminator {
    val labelFormat: LabelFormat
    val wireWidth: WireWidth
    val ownership: DiscriminatorOwnership

    /** Simple `@PacketType`: one implicit UByte, consumed by the dispatcher, hex labels. */
    data object FixedByte : Discriminator {
        override val labelFormat: LabelFormat get() = LabelFormat.Hex
        override val ownership: DiscriminatorOwnership get() = DiscriminatorOwnership.ConsumedByDispatcher
        override val wireWidth: WireWidth get() = WireWidth.Fixed(1)
    }

    /** `@DispatchOn(value class)`: peek/rewind, variant re-reads it, decimal labels. */
    data class ValueClass(
        val className: ClassName,
        val codecClassName: ClassName,
        val innerKind: ScalarKind,
        val innerWireOrder: Endianness,
        val dispatchValueProperty: String,
        val dispatchValueKind: ScalarKind,
    ) : Discriminator {
        override val labelFormat: LabelFormat get() = LabelFormat.Decimal
        override val ownership: DiscriminatorOwnership get() = DiscriminatorOwnership.ReReadByVariant
        override val wireWidth: WireWidth get() = innerKind.wireWidth
    }

    /**
     * `@DispatchOn(value class)` whose inner scalar carries
     * `@UseCodec(VariableLengthCodec)` — an HTTP/3 QUIC-varint-style
     * self-delimiting discriminator. Wire width is variable, recovered at
     * runtime from the value class's own generated codec ([codecClassName],
     * which delegates to the consumer's `VariableLengthCodec`). Like
     * [ValueClass] this is peek/rewind + re-read-by-variant with decimal
     * labels; it differs only in that the dispatcher can't pre-compute a
     * fixed discriminator width, so peek measures it via the codec instead
     * of reconstructing fixed inner-scalar bytes.
     */
    data class Varint(
        val className: ClassName,
        val codecClassName: ClassName,
        val dispatchValueProperty: String,
        val dispatchValueKind: ScalarKind,
        /**
         * The value class's single constructor property carrying the
         * varint-decoded value (e.g. `raw`). The `@ForwardCompatible`
         * skip+preserve arms read `<decoded>.<innerPropertyName>` to
         * capture the full-width opcode (the dispatch value may be a
         * narrowing projection) and re-wrap it (`ValueClass(opcode)`)
         * on the re-encode path.
         */
        val innerPropertyName: String,
        /** The inner property's scalar kind (Long / ULong for QUIC varints). */
        val innerKind: ScalarKind,
    ) : Discriminator {
        override val labelFormat: LabelFormat get() = LabelFormat.Decimal
        override val ownership: DiscriminatorOwnership get() = DiscriminatorOwnership.ReReadByVariant
        override val wireWidth: WireWidth get() = WireWidth.Variable
    }
}

/** Who consumes the discriminator bytes off the wire. */
internal enum class DiscriminatorOwnership {
    /** Dispatcher reads+consumes the discriminator; variant body starts after it. */
    ConsumedByDispatcher,

    /** Dispatcher peeks+rewinds; the variant re-reads the discriminator as its first field. */
    ReReadByVariant,
}

/** Radix for `when` labels and the expected-set diagnostic string. */
internal enum class LabelFormat {
    /** `0x01`; expected "one of {0x01, 0x02}". */
    Hex,

    /** `1`; expected "one of {1, 2}". */
    Decimal,
}

/** Payload genericity of the dispatcher (and its variants). */
internal sealed interface Genericity {
    data object Monomorphic : Genericity

    data class Generic(
        val binding: PayloadTypeParameter,
    ) : Genericity
}

/** Whether the sealed parent inherits `@FramedBy` framing. */
internal sealed interface Framing {
    data object Unframed : Framing

    data class Framed(
        val config: FramedByConfig,
    ) : Framing
}

/** Whether the sealed parent carries `@ForwardCompatible`. */
internal sealed interface ForwardCompat {
    data object Disabled : ForwardCompat

    data class Enabled(
        val config: ForwardCompatibleConfig,
    ) : ForwardCompat
}

/** Generated codec visibility (issue #175) — explicit rather than a nullable KModifier. */
internal sealed interface CodecVisibility {
    data object Public : CodecVisibility

    data object Internal : CodecVisibility
}

/** Map the legacy nullable visibility modifier into the explicit state. */
internal fun KModifier?.toCodecVisibility(): CodecVisibility =
    if (this == KModifier.INTERNAL) CodecVisibility.Internal else CodecVisibility.Public

internal data class CodecShape(
    val packageName: String,
    val messageClassName: ClassName,
    val ownerSimpleName: String,
    val codecSimpleName: String,
    val fields: List<FieldSpec>,
    /** Issue #175 — source class visibility, applied to the codec object/class. */
    val visibility: KModifier? = null,
    /**
     * When the @ProtocolMessage data class
     * carries a `<P : Payload>` type parameter and a
     * `RemainingBytesPayload` field whose source is
     * `ConstructorInjected`, this is the type-parameter name
     * (`P`) and the constructor-injected codec parameter name
     * (`payloadCodec`). When non-null, the emitter generates
     * `class FooCodec<P : Payload>(private val payloadCodec:
     * Codec<P>) : Codec<Foo<P>>` instead of
     * `object FooCodec : Codec<Foo>`.
     */
    val payloadTypeParameter: PayloadTypeParameter? = null,
    /**
     * When the @ProtocolMessage class is
     * also annotated with `@FramedBy(codec, after)`, this captures
     * the framing codec's class name and the optional `after` field
     * name. When non-null, the emitter routes to a different file
     * spec (no `Codec<T>` superinterface, encode signature returns
     * `ReadBuffer`, decode adds a strict bound check).
     */
    val framedBy: FramedByConfig? = null,
    /**
     * Issue #150 — true when the symbol is a `@ProtocolMessage data
     * object` or plain `@ProtocolMessage object`. Empty `fields`,
     * decode emits `return ObjectName` (singleton reference), encode
     * emits an empty body, wireSize is `Exact(0)`. Standalone codecs
     * still implement `Codec<T>`; sealed-variant codecs are invoked
     * by the dispatcher after it consumes the discriminator.
     */
    val isSingletonObject: Boolean = false,
    /**
     * Non-null when the symbol is a singleton
     * object variant under a sealed parent annotated
     * `@DispatchOn(value class)`. The parent dispatcher peeks the
     * discriminator and resets the buffer position before delegating,
     * so each variant codec must self-frame the discriminator (data-
     * class variants do this naturally via their `id: ValueClass`
     * first field; singleton variants have no field, so the codec
     * emits a literal write on encode and a read-and-discard on
     * decode of the discriminator's inner-scalar width). Width is
     * captured for `wireSize`/`peekFrameSize`; the `@PacketType.value`
     * literal drives the encode-side write.
     */
    val singletonDispatchDiscriminator: SingletonDispatchDiscriminator? = null,
    /**
     * Consumer-supplied frame-size override. Non-null when the
     * `@ProtocolMessage` type declares a companion object implementing
     * `com.ditchoom.buffer.codec.FrameDetector`; holds the type whose companion
     * to call. The emitter makes `peekFrameSize` delegate to
     * `<customPeek>.peekFrameSize(stream, baseOffset)` instead of running the
     * derived walker — the escape hatch for framings the walker can't express
     * (e.g. RFC 6455 WebSocket's escape-coded length + folded mask).
     */
    val customPeek: ClassName? = null,
)

/**
 * Discriminator self-frame for a singleton
 * sealed variant under `@DispatchOn(value class)`. [innerKind] is the
 * value class's inner scalar (UByte for the in-scope MQTT v3 SUBACK
 * fixture; UShort/UInt/Byte are also peekable and would round-trip
 * via the same emit). [literalValue] is the variant's
 * `@PacketType.value`, narrowed to the inner kind at write time.
 */
internal data class SingletonDispatchDiscriminator(
    val innerKind: ScalarKind,
    val literalValue: Int,
) {
    /**
     * Phase 1: the discriminator's wire width as a WireWidth, routed
     * from its inner scalar kind (always Fixed). Consumers read this
     * instead of `innerKind.width` when they want a wire width.
     */
    val wireWidth: WireWidth get() = innerKind.wireWidth
}

/**
 * `@FramedBy` configuration captured during
 * analyze. The emitter consumes this to switch the file spec to the
 * slicing-scheme encode + strict-decode shape. `afterFieldName` is
 * empty for prefix-at-offset-0 (the standalone probe case);
 * non-empty values are reserved for sealed-parent + @PacketType
 * dispatch.
 */
internal data class FramedByConfig(
    val codecClassName: ClassName,
    val afterFieldName: String,
)

/**
 * `@ForwardCompatible` configuration captured off a `@DispatchOn`
 * framed sealed parent. The unknown-variant sink is excluded from
 * the dispatcher's variant table (it carries no `@PacketType`) and
 * instead drives the decode `else` (skip + preserve) and encode
 * (`is Unknown ->` re-frame) arms.
 *
 * [opcodeFieldName] / [rawFieldName] are the unknown variant's
 * constructor parameter names — the validator guarantees the shape
 * `(Int, PlatformBuffer | ReadBuffer)`, the analyzer resolves the
 * names by type so emission references `value.<opcode>` /
 * `value.<raw>` regardless of the consumer's chosen identifiers.
 */
internal data class ForwardCompatibleConfig(
    val unknownClassName: ClassName,
    val opcodeFieldName: String,
    val rawFieldName: String,
    /**
     * The opcode parameter's declared scalar kind. [ScalarKind.Int] is
     * the legacy single-byte-discriminator shape (the opcode carries the
     * discriminator *byte*); [ScalarKind.Long] / [ScalarKind.ULong] are
     * the varint-discriminator shapes, carrying the discriminator's full
     * decoded value (F2/F5 require it to match the discriminator's inner
     * scalar kind so the preserve→re-encode round trip is lossless).
     */
    val opcodeKind: ScalarKind,
)

/**
 * Type-parameter binding for a generic-bounded
 * codec class. `typeVariableName` is the Kotlin type variable
 * (e.g., `P`); `codecParameterName` is the constructor parameter
 * holding the user-supplied codec (e.g., `payloadCodec`); `bound`
 * is the upper bound (always `Payload` for ).
 */
internal data class PayloadTypeParameter(
    val typeVariableName: String,
    val codecParameterName: String,
    val bound: ClassName,
)

/**
 * # FieldSpec — analyzer's classification of one constructor parameter.
 *
 * ## By-name codec resolution for `@ProtocolMessage` typed fields
 *
 * Every field whose declared type is a `@ProtocolMessage` data class
 * or sealed parent (with `@DispatchOn`) resolves its codec by-name:
 * `${T.simpleName}Codec` in T's package. The annotation processor
 * does NOT require an explicit `@UseCodec(<T>Codec::class)` wired
 * up to that codec — by-name resolution sidesteps the KSP first-
 * round chicken-and-egg (annotation references to as-yet-ungenerated
 * codec classes don't resolve in the round that emits T's codec).
 *
 * This rule applies uniformly across framing annotations:
 *
 *   - `@LengthPrefixed val: T` → [LengthPrefixedMessage] (terminal)
 *   - `@RemainingBytes val: List<T>` → [RemainingBytesProtocolMessageList]
 *   - `@LengthPrefixed @UseCodec(C) val: List<T>` → [LengthPrefixedUseCodecList]
 *   - `@When val: T?` → [Conditional] with [ConditionalInner.ProtocolMessageScalar]
 *   - `val: T` (no framing) → [ProtocolMessageScalar]
 *
 * Each branch shares the same element-type predicate (`@ProtocolMessage`,
 * data class OR sealed parent, non-payload-generic) and the same
 * by-name codec resolution. The framing annotation describes how the
 * field is bounded on the wire — not whether the field's type is
 * accepted.
 *
 * Payload-generic types (`<P : Payload>`) reject across all branches:
 * their generated codec is a class taking a constructor-injected
 * payload codec, not a singleton object, so the by-name
 * `<T>Codec.decode/encode(...)` form fails to resolve.
 */
internal sealed interface FieldSpec {
    val name: String

    /**
     * Onward — fields whose wire byte count is fixed at
     * compile time. The `peekFrameSize` prefix walk and the
     * fixed-size variant `wireSize` summation type-narrow to this
     * shape so they no longer need runtime casts to read
     * `wireBytes`.
     *
     * Keeps this interface unchanged: the variable-length
     * prefix walk for MQTT v3 CONNECT lives on a separate branch,
     * not as a third member here.
     */
    sealed interface FixedSize : FieldSpec {
        val wireBytes: Int

        /**
         * Phase 1: the variable-width axis projected onto this field.
         * Always `Fixed(wireBytes)` because every concrete member
         * (Scalar, ValueClassScalar) constructs from an Int `wireBytes`.
         * Consumers that conceptually want a wire width read this;
         * `wireBytes` readers are untouched.
         */
        val wireWidth: WireWidth get() = WireWidth.Fixed(wireBytes)
    }

    data class Scalar(
        override val name: String,
        val kind: ScalarKind,
        val resolvedWireOrder: Endianness,
        override val wireBytes: Int,
    ) : FixedSize

    /**
     * A Kotlin `enum class` field. The entry's `ordinal` rides the wire as an unsigned LEB128
     * varint via the shipped `UnsignedVarIntCodec` — self-delimiting, so adding entries never
     * breaks an older decoder's framing (it reads the right bytes and resolves an unknown ordinal
     * to [defaultEntryName] when set, else throws). NOT [FixedSize]: the width is value-dependent
     * (1 byte for ordinals 0..127, more beyond), so the containing message's wireSize is
     * runtime-Exact (summed via `UnsignedVarIntCodec.wireSize(...) as Exact`), mirroring an
     * `isVariableLength` `@UseCodec` field.
     *
     * [entryCount] is informational (diagnostics / future width bounds); [defaultEntryName] is the
     * `@EnumDefault` entry's simple name, or null for strict decode.
     *
     * [entryNames] are the enum's entry simple names in declaration order — i.e. the
     * `ordinal → name` mapping (index == ordinal). The schema descriptor (SCHEMA_DRIFT.md)
     * projects this into the `enum` record so a reorder of same-count enums is visible to the
     * drift differ; without it the descriptor could only see [entryCount] and an ordinal swap
     * would be invisible.
     */
    data class EnumScalar(
        override val name: String,
        val ownerSimpleName: String,
        val enumType: ClassName,
        val entryCount: Int,
        val defaultEntryName: String?,
        val entryNames: List<String>,
    ) : FieldSpec

    data class LengthPrefixedMessage(
        override val name: String,
        val ownerSimpleName: String,
        val messageType: ClassName,
        val codecType: ClassName,
        val prefixWidth: Int,
        val prefixWireOrder: Endianness,
    ) : FieldSpec

    /**
     * (issue #151 part 1) — `@LengthFrom("siblingField") val: T`
     * where `T` is a `@ProtocolMessage` data class or sealed parent.
     * Sister of [LengthPrefixedMessage] for the sibling-bounded variant:
     * the body's byte count comes from the sibling's `LengthSource`
     * rather than an inline prefix. Decode narrows the buffer's limit
     * to the sibling-derived end and restores the outer limit in a
     * `try/finally`; encode delegates to `<codecType>.encode` and the
     * user is responsible for sizing the sibling. wireSize collapses
     * to BackPatch on the parent (mirror of [LengthPrefixedMessage]).
     */
    data class LengthFromMessage(
        override val name: String,
        val ownerSimpleName: String,
        val source: LengthSource,
        val messageType: ClassName,
        val codecType: ClassName,
    ) : FieldSpec

    data class LengthPrefixedString(
        override val name: String,
        val ownerSimpleName: String,
        val prefixWidth: Int,
        val prefixWireOrder: Endianness,
    ) : FieldSpec

    /**
     * Bare `val: T` where T is a
     * `@ProtocolMessage` data class or sealed parent. The codec
     * resolves to `${T.simpleName}Codec` by-name in T's package.
     * Decode: `<codecType>.decode(buffer, context)`. Encode:
     * `<codecType>.encode(buffer, value.<name>, context)`.
     *
     * Establishes the by-name principle for `@ProtocolMessage` typed
     * fields uniformly. Where [LengthPrefixedMessage] frames the body
     * with a length prefix and [RemainingBytesProtocolMessageList]
     * frames a list of bodies with a caller-set buffer limit, this
     * shape is the unframed case — the codec self-frames (sealed
     * parents emit `<id> <body>`; data classes have a static layout
     * known to their codec).
     *
     * wireSize collapses the containing message to BackPatch
     * unconditionally (mirror of [UseCodecScalar] and
     * [LengthPrefixedUseCodecList]). The codec's own wireSize is
     * RuntimeExact — promoting the parent to runtime-Exact-via-cast
     * is a follow-on once a vector measurably benefits.
     *
     * Payload-generic types (`<P : Payload>`) reject — same rule as
     * [analyzeRemainingBytesProtocolMessageListField] /
     * [analyzeLengthPrefixedListSpec]: their generated codec is a
     * class taking a constructor-injected payload codec, not a
     * singleton object, so the by-name `<T>Codec.decode(...)` form
     * fails to resolve.
     */
    data class ProtocolMessageScalar(
        override val name: String,
        val ownerSimpleName: String,
        val fieldType: ClassName,
        val codecType: ClassName,
    ) : FieldSpec

    /**
     * `@UseCodec(C::class) val: <scalar>` (no framing
     * annotation), where `C` is a Kotlin `object` implementing
     * `Codec<T>` for `T` matching the field type. The decoded value
     * is whatever the user codec produces; encode delegates to
     * `C.encode(buffer, value.<name>, context)`.
     *
     * `isBounding` is `true` when `C` implements
     * `com.ditchoom.buffer.codec.BoundingLengthCodec<T>`. In that
     * case the decode emit captures the outer `buffer.limit()` into
     * `__<name>OuterLimit`, calls `C.applyBound(buffer, <name>)`
     * after decode, and the surrounding `buildDecodeFun` wraps every
     * subsequent field in `try { ... } finally { buffer.setLimit(
     * __<name>OuterLimit) }` — the outer-limit-restore
     * template, driven by interface inspection on the codec target.
     *
     * `fieldType` carries the field's declared `TypeName` so the
     * generated decode local and constructor argument bind to the
     * exact source type (UInt / Int / value class wrapper / etc.).
     * `codecType` is the user-supplied codec object's `ClassName`,
     * referenced directly (`<codecType>.decode(buffer, context)`)
     * Kotlin linker resolves
     * `expect`/`actual` codecs.
     */
    data class UseCodecScalar(
        override val name: String,
        val ownerSimpleName: String,
        val fieldType: TypeName,
        val codecType: ClassName,
        val isBounding: Boolean,
        /**
         * `true` when `C` implements
         * `com.ditchoom.buffer.codec.VariableLengthCodec<T>` — a
         * self-delimiting, variable-width encoding (QUIC varint, LEB128, …).
         * Decode/encode are unchanged (they already delegate to `C`); this
         * flag makes `peekFrameSize` framable via the codec's observed width
         * (`total = prior + width + fixed-suffix`) instead of collapsing to
         * `NoFraming`. Mutually exclusive with [isBounding] (a bounding length
         * adds its value to the total; a self-delimiting value does not).
         */
        val isVariableLength: Boolean,
    ) : FieldSpec

    /**
     * `@RemainingBytes val: String` — UTF-8 string consuming the rest of the
     * buffer. Decode reads `buffer.readString(buffer.remaining(), Charset.UTF8)`;
     * encode writes the value's UTF-8 bytes. Same caller-bounds-buffer contract
     * as [RemainingBytesPayload]: an outer dispatcher
     * sets `buffer.limit()` before this codec runs.
     *
     * Terminal-only (must be the last non-conditional field). The annotation
     * kdoc has documented this shape since `@RemainingBytes` was introduced;
     * the analyzer / emitter branch landed here.
     */
    data class RemainingBytesString(
        override val name: String,
        val ownerSimpleName: String,
        /**
         * Sum of `wireBytes` for trailing FixedSize fields
         * after this one. 0 when terminal; non-zero only when the
         * shape carries fixed-size scalars / value classes after the
         * `@RemainingBytes` body. Decode emit subtracts this from
         * `buffer.limit()` so trailing bytes survive the body read.
         */
        val reservedTrailingBytes: Int = 0,
    ) : FieldSpec

    /**
     * `@RemainingBytes @UseCodec(C::class) val:
     * P` where `P` extends `com.ditchoom.buffer.codec.Payload` and
     * `C` is a Kotlin `object` implementing `Codec<P>`. Decode
     * delegates to `C.decode(buffer, context)` against the bounded
     * buffer; encode delegates to `C.encode(buffer, value.<name>,
     * context)`. Caller-bounds-buffer contract: an outer dispatcher
     * (for example MQTT) sets `buffer.limit` to bound the
     * payload region before this codec runs.
     *
     * Narrow: terminal-only (no fields after the
     * payload), no generics on the outer message,
     * concrete `Payload`-typed field (no `<P : Payload>`
     * type parameter), no `Partial` decode pattern,
     * no aggregator, no `expect`/`actual` resolution
     * across platforms (single-platform `object`
     * declaration only).
     */
    data class RemainingBytesPayload(
        override val name: String,
        val ownerSimpleName: String,
        val payloadType: TypeName,
        val source: PayloadCodecSource,
        /** See [RemainingBytesString.reservedTrailingBytes]. */
        val reservedTrailingBytes: Int = 0,
    ) : FieldSpec

    /**
     * `@LengthPrefixed @UseCodec(C::class) val xs:
     * List<E>` where `C` is a Kotlin `object` implementing
     * `BoundingLengthCodec<UInt>` and `E` is a `@ProtocolMessage data
     * class`. The codec reads/writes the body byte count via its own
     * wire shape (e.g. MQTT var-byte-int via [MqttRemainingLengthCodec])
     * and applies the resulting bound to the buffer's limit; the list
     * is read/written element-by-element via `E`'s generated codec
     * inside the bounded region.
     *
     * Decode is self-contained: outer limit captured into
     * `__<name>OuterLimit`, prefix codec drives the bound via
     * `applyBound`, elements read until `buffer.position() ==
     * buffer.limit()`, outer limit restored in a `try { ... } finally`.
     * Subsequent fields run at the original outer limit — the new
     * shape is NOT registered in `isBoundingShape()` (which is
     * reserved for fields whose narrowed limit must persist for
     * subsequent decodes).
     *
     * Encode pre-measures body bytes via the element codec's wireSize
     * (cast to `Exact`), writes the prefix via `C.encode(buffer,
     * bodyBytes.toUInt(), ctx)`, then iterates and encodes elements.
     * BackPatch element codecs throw `ClassCastException` — same
     * fixture-design contract as `RemainingBytesProtocolMessageList`
     * and `LengthPrefixedMessage`.
     *
     * Unblocks: MQTT v5 property-list shape
     * (`@LengthPrefixed @UseCodec(MqttRemainingLengthCodec::class) val
     * properties: List<MqttProperty>`).
     */
    data class LengthPrefixedUseCodecList(
        override val name: String,
        val ownerSimpleName: String,
        val spec: LengthPrefixedListSpec,
    ) : FieldSpec {
        val codecType: ClassName get() = spec.codecType
        val elementClassName: ClassName get() = spec.elementClassName
        val elementCodecClassName: ClassName get() = spec.elementCodecClassName
    }

    /**
     * `@LengthPrefixed @UseCodec(C::class) val:
     * T` where `T` is a `Payload`-marked type and `C` is a Kotlin
     * `object` implementing `Codec<T>`. The scalar counterpart of
     * [LengthPrefixedUseCodecList].
     *
     * Wire shape: fixed-width unsigned-int prefix (default 2 bytes /
     * UShort BE; same prefix shape as `@LengthPrefixed val: String`)
     * followed by exactly that many body bytes consumed by `C`. The
     * codec carries no `BoundingLengthCodec` constraint — the prefix
     * is owned by the framework, not by `C`. This is the design
     * difference from [LengthPrefixedUseCodecList] (whose codec drives
     * a variable-width prefix via `BoundingLengthCodec<UInt>`).
     *
     * Decode: read the prefix, narrow `buffer.limit()` to position +
     * length, run `C.decode(buffer, context)`, restore the outer
     * limit in a `try/finally`. Encode: BackPatch — reserve the prefix
     * slot, run `C.encode(buffer, value.<name>, context)` against the
     * accumulating buffer, measure the body byte count from the
     * position delta, patch the prefix in place.
     *
     * The `T: Payload` marker is enforced by the validator (
     * D2) — typed binary data crossing the codec boundary clusters
     * under one marker, mirroring the existing `@RemainingBytes
     * @UseCodec val: P: Payload` (/10b/10d/10f) shape.
     *
     * wireSize collapses the containing message to BackPatch (mirror
     * of [UseCodecScalar] / [LengthPrefixedString]): the user codec's
     * own wireSize is opaque, and pre-measuring would require running
     * the codec twice. peekFrameSize follows the
     * [LengthPrefixedString] / [LengthPrefixedMessage] sequential
     * walker: the prefix tells us the body byte count, no codec-side
     * peek needed.
     */
    data class LengthPrefixedUseCodecPayload(
        override val name: String,
        val ownerSimpleName: String,
        val payloadType: TypeName,
        val payloadCodecType: ClassName,
        val prefixWidth: Int,
        val prefixWireOrder: Endianness,
    ) : FieldSpec

    /**
     * `@RemainingBytes val: List<T>` where `T` is a
     * `@ProtocolMessage data class` (or a sealed parent with
     * `@DispatchOn`). The decoder reads elements
     * while `buffer.position() < buffer.limit()`, dispatching each
     * iteration to the element's own codec; the caller is
     * responsible for setting `buffer.limit()` externally (typical:
     * outer protocol carries a remaining-length variable-length
     * integer parsed by an outer dispatcher, which sets the limit
     * before delegating).
     *
     * Encode iterates the list and writes each element via the
     * element codec — same shape as `LengthFromList`'s encode loop,
     * minus the sibling-bound length carrier; the byte count is
     * implicit in the outer protocol's framing.
     *
     * `wireSize` is `Exact(headerBytes + sumOf elements' wireSize)`
     * via the same runtime `as Exact` cast that
     * `LengthPrefixedMessage` uses. Element wireSize must be Exact
     * at runtime; BackPatch elements throw `ClassCastException`,
     * matching the existing convention.
     *
     * Element must be a `@ProtocolMessage data class` or sealed
     * parent. The scalar-element path is rejected at the validator;
     * typed binary blobs use
     * `@RemainingBytes @UseCodec(C::class) val: T : Payload`
     * instead.
     *
     * Unblocks: MQTT v3.1.1 SUBSCRIBE / UNSUBSCRIBE topic-filter
     * lists.
     */
    data class RemainingBytesProtocolMessageList(
        override val name: String,
        val ownerSimpleName: String,
        val elementClassName: ClassName,
        val elementCodecClassName: ClassName,
        // Analyze-time predicate driving the
        // outer message's wireSize / variant-classification short-
        // circuit. Mirrors `LengthPrefixedListSpec.elementIsBackPatch`
        // . When `true`, [buildWireSizeFun] and
        // [classifyVariantWireSize] collapse the containing message
        // to BackPatch — without it, the runtime `as Exact` cast on
        // each element wireSize CCEs for sealed-parent variants
        // carrying `@LengthPrefixed val: String` or `@When` trailers.
        val elementIsBackPatch: Boolean,
        /** See [RemainingBytesString.reservedTrailingBytes]. */
        val reservedTrailingBytes: Int = 0,
    ) : FieldSpec

    /**
     * `@LengthFrom("siblingField") val: List<T>`
     * where `T` is a `@ProtocolMessage data class`. The sibling
     * provides the body byte count; the decoder bounds the buffer
     * via `setLimit` and reads elements via the element's own
     * codec until the bounded position is reached.
     *
     * Encode iterates the list and writes each element via the
     * element codec; the user is responsible for keeping the
     * sibling consistent with the encoded byte count (same row 16
     * trust contract as `LengthFromString`).
     *
     * Narrow: element must be a `@ProtocolMessage data
     * class`. List of scalar (`List<UByte>` / `List<Int>` etc.)
     * is the shape with `@RemainingBytes`.
     *
     * `source` carries the resolved length carrier: 's
     * sibling-Scalar form (`LengthSource.Sibling`) or 's
     * dotted value-class-property form
     * (`LengthSource.ValueClassProperty`).
     */
    data class LengthFromList(
        override val name: String,
        val ownerSimpleName: String,
        val source: LengthSource,
        val elementClassName: ClassName,
        val elementCodecClassName: ClassName,
    ) : FieldSpec

    /**
     * `@Count val: List<T>` where `T` is a `@ProtocolMessage data class`
     * (or a sealed parent with `@DispatchOn`). The element-count complement
     * to the byte-length list framings ([RemainingBytesProtocolMessageList],
     * [LengthFromList]): instead of a byte span that the decoder drains to a
     * limit, an unsigned LEB128 varint carries the element COUNT `N`, and the
     * decoder reads exactly `N` elements via the element codec.
     *
     * Wire shape: `varint(N)` (the shipped `UnsignedVarIntCodec`, the same
     * self-delimiting encoding an enum ordinal rides on) followed by `N`
     * self-delimiting elements. Because the count is explicit the field is
     * self-delimiting — unlike the drain-to-limit list shapes it need NOT be
     * the terminal constructor parameter.
     *
     * Decode: read the varint count, then `repeat(N)` appending
     * `<E>Codec.decode(buffer, context)`. Encode: write
     * `UnsignedVarIntCodec.encode(buffer, list.size.toUInt(), context)` then
     * iterate. wireSize = varint width of the count + sum of element
     * wireSizes (each cast to `Exact` at runtime — same convention as
     * [RemainingBytesProtocolMessageList]); the containing message collapses
     * to BackPatch when the element is BackPatch-shaped.
     *
     * `elementIsBackPatch` mirrors [RemainingBytesProtocolMessageList]'s flag:
     * when `true` the containing message's wireSize / variant classification
     * collapses to BackPatch (the runtime `as Exact` cast would CCE on
     * BackPatch element variants).
     */
    data class CountPrefixedProtocolMessageList(
        override val name: String,
        val ownerSimpleName: String,
        val elementClassName: ClassName,
        val elementCodecClassName: ClassName,
        val elementIsBackPatch: Boolean,
    ) : FieldSpec

    /**
     * /
     * `@LengthFrom("ref") val: String`. The body wire bytes are
     * determined by a non-adjacent length carrier decoded
     * earlier. Decode reads `source.localAccessor` UTF-8 bytes;
     * encode writes the body without a prefix slot — the user
     * is responsible for setting the carrier to the correct
     * UTF-8 byte count.
     *
     * `source` carries the resolved length carrier. See
     * `LengthSource` — 's sibling-Scalar form and slice
     * 9's dotted value-class-property form share this field
     * type.
     */
    data class LengthFromString(
        override val name: String,
        val ownerSimpleName: String,
        val source: LengthSource,
    ) : FieldSpec

    /**
     * A `@JvmInline value class` field whose primary
     * constructor takes a single supported scalar. Wire form is the
     * inner scalar at its natural width.
     *
     * `valueClassWireOrder` is the value class's own
     * `@ProtocolMessage(wireOrder)` (defaults to `Endianness.Default`
     * which collapses to big-endian). This is propagated to the
     * sequential walk's value-class peek-stash so multi-byte inner
     * kinds (UShort/UInt) assemble bytes in the correct order during
     * peek, regardless of the runtime buffer's `ByteOrder`. Decode
     * and encode of the value-class field still use `buffer.read*`
     * / `buffer.write*` and rely on the buffer's runtime byte order
     * (consistent with how the codec treats Scalar fields without
     * `@WireOrder`).
     *
     * `@WireBytes` / `@WireOrder` on the outer parameter are out
     * of scope and silently rejected (caught by the non-conditional
     * analyzeField path).
     */
    data class ValueClassScalar(
        override val name: String,
        val ownerSimpleName: String,
        val valueClassType: ClassName,
        val innerKind: ScalarKind,
        val innerPropertyName: String,
        override val wireBytes: Int,
        val valueClassWireOrder: Endianness,
    ) : FixedSize

    /**
     * `@When` conditional wrapper. /3 support
     * `ConditionalInner.Scalar` at natural width (no `@WireBytes`,
     * no `@WireOrder`);.5 widens `inner` to
     * `ConditionalInner.LengthPrefixedString` for the MQTT v3
     * CONNECT optional-field shape (`@LengthPrefixed @When
     * val: String?`).
     *
     * `condition` carries the resolved source: 's sibling
     * Boolean form (`ConditionRef.Sibling`) and 's dotted
     * value-class-property form (`ConditionRef.ValueClassProperty`).
     */
    data class Conditional(
        override val name: String,
        val ownerSimpleName: String,
        val condition: ConditionRef,
        val nullableTypeName: TypeName,
        val inner: ConditionalInner,
    ) : FieldSpec
}

/**
 * Typed shape of a `@When` field's bound (inner)
 * type. Doctrine row 19 lists the slot's underlying type universe
 * as anything Stages A/B/C/D already emit; the emitter implements
 * that universe one shape at a time:
 *   - `Scalar`: any natural-width supported scalar (slices 2/3).
 *   - `LengthPrefixedString`: `@LengthPrefixed val: String?`
 * (.5).
 *   - `ValueClassScalar`: `val: T?` where `T` is a `@JvmInline
 * value class` over a single supported scalar (
 *     step 2 — the MQTT v3.1.1 PUBLISH `packetId: PacketId?`
 *     QoS-conditional shape). Decode reads the inner scalar at
 *     natural width and wraps via the value-class constructor;
 *     encode unwraps the non-null value via the inner property
 *     and writes the inner scalar. `@WireBytes` / `@WireOrder`
 *     on the inner property are out of scope (matches the
 *     narrowing applied to the non-conditional `ValueClassScalar`
 * field shape — ).
 * Future widenings (`@WireBytes`-narrowed scalars, `@LengthPrefixed
 * @ProtocolMessage` body) are additional members; the existing
 * branches stay closed by the sealed.
 */
internal sealed interface ConditionalInner {
    data class Scalar(
        val kind: ScalarKind,
        // Wire order resolved from the parent's @ProtocolMessage(wireOrder)
        // (or Default if absent). Honored on the decode/encode emit via the
        // same swap pattern Scalar / ValueClassScalar use, so a multi-byte
        // conditional field inside a non-Default parent produces the right
        // wire bytes regardless of buffer.byteOrder.
        val wireOrder: Endianness,
    ) : ConditionalInner

    data class LengthPrefixedString(
        val prefixWidth: Int,
        val prefixWireOrder: Endianness,
    ) : ConditionalInner

    data class ValueClassScalar(
        val valueClassType: ClassName,
        val innerKind: ScalarKind,
        val innerPropertyName: String,
        // Mirror of Scalar.wireOrder — for value-class conditionals, use the
        // value class's own @ProtocolMessage(wireOrder).
        val valueClassWireOrder: Endianness,
    ) : ConditionalInner

    /**
     * Conditional `@LengthPrefixed @UseCodec(C) val:
     * List<E>?`. The cascading-trailer property bag for v5 acks
     * (PUBACK et al. §3.4.2.2.1). When the predicate is true (and
     * for grammar 2, when `value.<name> != null` at encode time),
     * the inner shape is the length-prefixed property-bag
     * decode/encode. Mirrors `FieldSpec.LengthPrefixedUseCodecList`'s
     * fields one-for-one — the same emit is used inside the
     * conditional `if` block.
     */
    data class LengthPrefixedUseCodecList(
        val spec: LengthPrefixedListSpec,
    ) : ConditionalInner {
        val codecType: ClassName get() = spec.codecType
        val elementClassName: ClassName get() = spec.elementClassName
        val elementCodecClassName: ClassName get() = spec.elementCodecClassName
    }

    /**
     * Conditional `@When @LengthPrefixed
     * @UseCodec(C) val: T?` where `T : Payload`. Mirrors
     * [FieldSpec.LengthPrefixedUseCodecPayload]'s emit one-for-one
     * inside the predicate-true branch.
     *
     * Drives the v3/v5 CONNECT will-payload + password slots
     * (gated on `connectFlags.willPresent` / `passwordPresent`).
     * The cascading-trailer cases use predicate truthfulness from
     * the connect-flag value class properties ( dotted
     * value-class-property predicates).
     */
    data class LengthPrefixedUseCodecPayload(
        val payloadType: TypeName,
        val payloadCodecType: ClassName,
        val prefixWidth: Int,
        val prefixWireOrder: Endianness,
    ) : ConditionalInner

    /**
     * Conditional `@When @UseCodec(C) val: T?`.
     * Mirrors the non-conditional [FieldSpec.UseCodecScalar] emit one-
     * for-one inside the predicate-true branch. `T` is any type the
     * referenced codec object implements `Codec<T>` for — supported
     * scalar via [SUPPORTED_SCALARS], value class via [classNameOf],
     * or `@ProtocolMessage` sealed parent (with `@DispatchOn`) whose
     * generated dispatcher is a singleton `Codec<T>` object. Decode
     * emit: `if (predicate) C.decode(buffer, context) else null`.
     * Encode emit: `C.encode(buffer, value.<name>, context)` inside
     * the existing predicate-gated block.
     *
     * The naming mirrors `FieldSpec.UseCodecScalar` for symmetry; the
     * "Scalar" suffix is historical — both shapes accept non-scalar
     * types via their `KSClassDeclaration` fallback in the analyzer.
     *
     * BackPatch impact on the containing message is already absorbed
     * by [classifyVariantWireSize] / [buildWireSizeFun]: any
     * `Conditional` field collapses the message wireSize to BackPatch
     * (row 19), which subsumes whatever the codec object's wireSize
     * does.
     */
    data class UseCodecScalar(
        val fieldType: TypeName,
        val codecType: ClassName,
    ) : ConditionalInner

    /**
     * Bare `@When val: T?` where T is a
     * `@ProtocolMessage` data class or sealed parent. The codec is
     * resolved by-name from T's package (`${T.simpleName}Codec`),
     * not from a `@UseCodec` annotation, so first-round KSP can wire
     * up the call without the codec class existing yet. Decode and
     * encode emit are byte-identical to [UseCodecScalar] — both
     * route through `<codec>.decode(buffer, context)` /
     * `<codec>.encode(buffer, value, context)`. The variants are
     * kept distinct because their analyzer entry conditions and
     * user-facing surface differ: `UseCodecScalar` requires
     * `@UseCodec`, `ProtocolMessageScalar` rejects it (the by-name
     * path is the no-annotation form).
     */
    data class ProtocolMessageScalar(
        val fieldType: ClassName,
        val codecType: ClassName,
    ) : ConditionalInner
}

/**
 * Single source of truth for the
 * "VBI-prefixed list of typed elements" wire shape.
 *
 * Both `FieldSpec.LengthPrefixedUseCodecList` and
 * `ConditionalInner.LengthPrefixedUseCodecList`
 * compose this spec. A future shape change (e.g., promoting the
 * codec value type beyond `UInt`) now lands in one place.
 *
 * `elementIsBackPatch` gates the encode path:
 *  - `true` → scratch-buffer encode (handles BackPatch-wireSize
 *    elements transparently — variants whose `wireSize` returns
 *    `BackPatch` rather than `Exact`),
 *  - `false` → pre-measure encode via element codec's `wireSize as
 *    Exact`. Cheaper but requires Exact-measured elements.
 *
 * The flag is set by
 * [detectElementBackPatch] which mirrors the message-wide BackPatch
 * short-circuits in [classifyVariantWireSize] / [buildWireSizeFun]:
 * any of `@When`, `@RemainingBytes`, `@UseCodec`, or `@LengthPrefixed
 * val: String` on a constructor parameter forces the BackPatch
 * encode path. Sealed parents stay conservatively BackPatch (defer
 * the all-variants-Exact promotion until a fixture wants it).
 *
 * Before the flag was named `elementIsSealed` and was
 * driven solely by the source-language `Modifier.SEALED` check —
 * which would `ClassCastException` at runtime on a data class with
 * a BackPatch-shaped field (no fixture tripped it because 's
 * v5 property bag wraps such elements under a sealed parent).
 */
internal data class LengthPrefixedListSpec(
    val codecType: ClassName,
    val elementClassName: ClassName,
    val elementCodecClassName: ClassName,
    val elementIsBackPatch: Boolean,
)

/**
 * Resolved source of a `@When` predicate.
 *
 * The `Sibling` form names a sibling `Boolean` constructor parameter
 * declared before the bound field. The `ValueClassProperty` form
 * names a sibling parameter (a value class with a single
 * supported-scalar inner) plus a `Boolean`-returning `val` property
 * declared on that value class.
 */
internal sealed interface ConditionRef {
    data class Sibling(
        val name: String,
    ) : ConditionRef

    /**
     * The value class's inner kind and ClassName are not stored
     * here — by the time the peek emitter needs them it has
     * already located the sibling's `FieldSpec.ValueClassScalar`
     * in the prefix walk and reads them from there. Keeping them
     * out of `ConditionRef` removes the duplicate-source-of-truth
     * trap (the FieldSpec is authoritative).
     */
    data class ValueClassProperty(
        val siblingName: String,
        val propertyName: String,
    ) : ConditionRef

    /**
     * Grammar 2 — `remaining <op> <int>`. References
     * no sibling; the decode emit expands to `buffer.remaining()
     * <op> <threshold>` and the encode emit expands to
     * `value.<field> != null` (cascading-trailer semantics).
     */
    data class RemainingCmp(
        val op: RemainingComparisonOp,
        val threshold: Int,
    ) : ConditionRef
}

/**
 * Typed source of a `@LengthFrom` byte count.
 *
 * Closed sealed: row 18 lists the simple-name sibling form
 *  and the dotted `<sibling>.<property>` form
 * as the only two valid sources. The type system enforces
 * exhaustive `when` at every emit site, removing the implicit
 * "if propertyName non-null ignore the kind" relationship the
 * earlier flat-fields shape required.
 *
 * Both forms reference a sibling field (the simple form's
 * sibling IS the numeric scalar; the dotted form's sibling is
 * a value class wrapping a numeric scalar). `siblingName` is
 * therefore present in both shapes — which lets
 * `collectPeekStashSources` and the sequential walk treat both
 * uniformly (always stash the sibling field's local).
 */
internal sealed interface LengthSource {
    val siblingName: String

    /**
     * Simple form: sibling is a `Scalar` field. Body
     * byte count = `<sibling>.toInt()`. Decode applies an
     * `Int.MAX_VALUE` guard for kinds whose range exceeds Int
     * (UInt / ULong / Long).
     */
    data class Sibling(
        override val siblingName: String,
        val siblingKind: ScalarKind,
    ) : LengthSource

    /**
     * Dotted form: sibling is a `value class` wrapping
     * a numeric scalar; body byte count = `<sibling>.<property>`.
     * The property must return non-nullable `Int`, so the byte
     * count is `Int` directly — no `.toInt()` conversion or
     * `Int.MAX_VALUE` guard needed at decode time.
     *
     * `valueClassInnerKind` IS load-bearing for peek (the peek-
     * stash for the value-class field reconstructs the value
     * class from its inner-scalar bytes; the inner kind drives
     * the byte width). Stored on the source — not the
     * referenced `FieldSpec.ValueClassScalar` — to keep the
     * peek emit site self-contained when the LengthFrom is
     * itself the field being walked. The wireOrder propagation
     * for the value class's inner-byte assembly is a known
     * limitation: today's emit defaults to big-endian (correct
     * for HTTP/2 SETTINGS, the vector); little-endian
     * value-class siblings would need additional plumbing.
     */
    data class ValueClassProperty(
        override val siblingName: String,
        val propertyName: String,
        val valueClassInnerKind: ScalarKind,
    ) : LengthSource
}

/**
 * Typed source of a `Codec<T>` instance for a
 * `RemainingBytesPayload` field. Mirrors the `LengthSource`
 * pattern (doctrine #2: no nullable fields representing form
 * distinction; the type system enforces exhaustive `when`).
 *
 * Two forms:
 * `UserCodecObject`: the field carries
 *     `@UseCodec(Foo::class)` referencing a Kotlin `object`
 *     declaration. Emit calls `Foo.decode(...)` /
 *     `Foo.encode(...)` directly.
 * `ConstructorInjected`: the message has a
 *     `<P : Payload>` type parameter and the field type IS that
 *     parameter. The codec is supplied as a constructor parameter
 *     of the generated codec class; emit calls
 *     `payloadCodec.decode(...)` (or whatever name the parameter
 *     takes).
 */
internal sealed interface PayloadCodecSource {
    data class UserCodecObject(
        val codecType: ClassName,
    ) : PayloadCodecSource

    /**
     * `parameterName` is the name of the constructor field on the
     * generated codec class. Conventionally derived from the
     * payload field's name (`payload` → `payloadCodec`).
     */
    data class ConstructorInjected(
        val parameterName: String,
    ) : PayloadCodecSource
}

/**
 * Phase 1: makes the variable-width axis representable in the IR.
 *
 * Today every wire byte count is a compile-time `Int`. This sum type
 * names the two cases so each consumer must acknowledge the
 * `Variable` arm. Phase 1 only STUBS `Variable` (error/TODO); the
 * `Fixed(n)` arm must produce byte-for-byte identical output to the
 * pre-refactor `Int` code.
 *
 * The eventual `Variable` implementations already exist in spirit on
 * the `UseCodecScalar` / `LengthPrefixedUseCodecPayload` field shapes:
 *   - wireSize collapses the containing message to VariantWireSize.BackPatch
 *   - peek bails to NoFraming (runtime-measured, not prefix-walkable)
 *   - decode/encode measure the body at runtime rather than from a literal.
 * The stubs below are intentionally placed at exactly the sites those
 * three behaviors will eventually attach, so Phase 2 fills them in
 * without re-threading the type.
 */
internal sealed interface WireWidth {
    /** Compile-time-known byte count. `bytes` is always >= 0. */
    data class Fixed(
        val bytes: Int,
    ) : WireWidth

    /**
     * Width is not known until encode/decode runs (length-prefixed or
     * codec-measured body). Phase 1: every consumer stubs this arm.
     * Phase 2 routes it to BackPatch wireSize + NoFraming peek +
     * runtime-measured decode/encode, exactly as UseCodecScalar does
     * today.
     */
    data object Variable : WireWidth

    companion object {
        /** Identity for [plus] folds; equals the empty-field-list sum. */
        val Zero: Fixed = Fixed(0)

        /**
         * Lift a legacy `Int` byte count into the sum type. Used only at
         * the IR-construction boundary while consumers migrate; produces
         * Fixed, so it can never change output.
         */
        fun ofFixed(bytes: Int): Fixed = Fixed(bytes)
    }
}

internal enum class ScalarKind(
    val width: Int,
    val isSigned: Boolean,
) {
    // (members below; computed `wireWidth` declared after the entries)
    // Boolean is a 1-byte scalar with no byte order and no `@WireBytes` narrowing.
    // Precondition for `@When` ( mandates a
    // `Boolean`-typed source field).
    Boolean(1, false),
    UByte(1, false),
    UShort(2, false),
    UInt(4, false),
    ULong(8, false),
    Byte(1, true),
    Short(2, true),
    Int(4, true),
    Long(8, true),

    // IEEE 754 floating point — wire form is the raw bit pattern of
    // toRawBits() / fromBits() at fixed natural width. Treated as
    // signed only insofar as @WireBytes narrowing is rejected (same
    // rule as integer signed types — partial-read sign extension is
    // out of scope).
    Float(4, true),
    Double(8, true),
    ;

    /**
     * Phase 1: the variable-width axis projected onto this kind.
     * Always `Fixed(width)` — scalar kinds are intrinsically fixed.
     * Routing point for `.width` reads that conceptually want a wire
     * width; the natural-read fast-path `when (width) { 2,4,8 }`
     * switches keep reading `.width` directly.
     */
    val wireWidth: WireWidth get() = WireWidth.Fixed(width)
}

internal enum class Endianness {
    Default,
    Big,
    Little,
}

/**
 * Comparison operator inside the `remaining <op> <int>`
 * grammar. Closed sealed (three values match the documented grammar).
 * `Equal` is rare in practice (one-byte sentinel checks) but
 * documented as part of the grammar.
 */
internal enum class RemainingComparisonOp(
    val symbol: String,
) {
    GreaterOrEqual(">="),
    Greater(">"),
    Equal("=="),
}
