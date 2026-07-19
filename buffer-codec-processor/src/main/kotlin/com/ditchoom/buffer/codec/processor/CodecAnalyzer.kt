package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName

/*
 * CodecAnalyzer — the KSP-declaration to IR analysis layer extracted from
 * CodecEmitter (step 3): the analyze / detect / resolve functions plus their
 * pure KSP/IR helper closure and the peekable-kind sets, all free of emit/log
 * state, moved verbatim to package-internal top-level so CodecEmitter (and the
 * dispatch emitters) call them unqualified and unchanged. Byte-identical codegen
 * verified by the snapshot suite.
 */

/** Minimum / maximum byte width accepted by `@WireBytes` (a Long is 8 bytes wide). */
private const val MIN_WIRE_BYTES = 1
private const val MAX_WIRE_BYTES = Long.SIZE_BYTES

/** A `remaining` comparison shape is exactly three tokens: `remaining <op> <int>`. */
private const val REMAINING_COMPARISON_TOKEN_COUNT = 3

/** Inclusive upper bound of an unsigned byte; `@PacketType` wire values live in `0..255`. */
private const val UBYTE_MAX_VALUE = 0xFF

/**
 * Issue #175 — the generated codec exposes the message type in its
 * public `decode(): T` / `encode(value: T)` signatures, so a codec
 * for an `internal` class must itself be `internal` (otherwise Kotlin
 * rejects it: "'public' function exposes its 'internal' return
 * type"). Returns [KModifier.INTERNAL] when the source class or any
 * enclosing declaration is `internal` (effective visibility), else
 * `null` (default public — keeps every existing public codec
 * byte-identical).
 */
internal fun codecVisibilityModifier(symbol: KSClassDeclaration): KModifier? {
    var decl: KSDeclaration? = symbol
    while (decl != null) {
        if (Modifier.INTERNAL in decl.modifiers) return KModifier.INTERNAL
        decl = decl.parentDeclaration
    }
    return null
}

internal fun analyze(symbol: KSClassDeclaration): AnalysisResult {
    // Issue #150 — `@ProtocolMessage data object` / `@ProtocolMessage object`.
    // Singleton variants carry zero wire bytes beyond the dispatcher's
    // discriminator (or zero bytes total when standalone). Emit a
    // CodecShape with empty fields and `isSingletonObject = true` —
    // buildDecodeFun returns the singleton via `return ObjectName`,
    // buildEncodeFun emits an empty body, buildWireSizeFun collapses
    // to `Exact(0)` via the empty-fields fall-through, and
    // buildPeekFrameFun collapses to the all-FixedSize Complete(0)
    // path.
    //
    // When the singleton is a sealed
    // subclass under `@DispatchOn(value class)`, capture the
    // discriminator's inner scalar kind plus the variant's
    // `@PacketType.value`. The dispatcher peeks-and-resets, so the
    // variant codec must self-frame the discriminator the same way
    // data-class variants do (their `id: ValueClass` first field
    // round-trips the byte through the value-class scalar path).
    // The simple-sealed dispatcher (no `@DispatchOn`) consumes the
    // byte itself before delegating; that path remains unchanged
    // (`detectSealedDispatchOnParentDiscriminator` returns null).
    if (symbol.classKind == ClassKind.OBJECT) {
        // `@DispatchOn` on an object is handled by the dispatcher path,
        // not the data-class path — not this analyzer's concern.
        if (symbol.annotations.any { it.shortName.asString() == "DispatchOn" }) {
            return AnalysisResult.NotApplicable
        }
        val ownerSimpleName = symbol.simpleName.asString()
        return AnalysisResult.Supported(
            CodecShape(
                packageName = symbol.packageName.asString(),
                messageClassName = classNameOf(symbol),
                ownerSimpleName = ownerSimpleName,
                codecSimpleName = symbol.flattenedCodecName(),
                fields = emptyList(),
                visibility = codecVisibilityModifier(symbol),
                isSingletonObject = true,
                singletonDispatchDiscriminator = detectSealedDispatchOnParentDiscriminator(symbol),
            ),
        )
    }
    // Not a class kind we model here (enum, interface, annotation, …).
    if (symbol.classKind != ClassKind.CLASS) return AnalysisResult.NotApplicable
    val isData = Modifier.DATA in symbol.modifiers
    val isValue = symbol.isValueClassDecl()
    // Plain (non-data, non-value) classes are out of scope; handled
    // by a validator diagnostic, so stay silent.
    if (!isData && !isValue) return AnalysisResult.NotApplicable
    // Sealed parents go through the dispatcher path, not here.
    if (Modifier.SEALED in symbol.modifiers) return AnalysisResult.NotApplicable
    // A data class carrying `@DispatchOn` is rejected by the validator
    // (`@DispatchOn` is only valid on sealed parents) — stay silent.
    if (symbol.annotations.any { it.shortName.asString() == "DispatchOn" }) {
        return AnalysisResult.NotApplicable
    }
    // `@PacketType` on a data class is a variant — emit its standalone
    // codec via the existing data-class path. The dispatcher (separate emit
    // path keyed on the sealed parent) calls `${VariantSimpleName}Codec`.
    val ctor = symbol.primaryConstructor ?: return AnalysisResult.NotApplicable
    // Empty parameter list: no codifiable fields. This is genuinely not a
    // message shape (rather than an unsupported one) — stay silent to
    // preserve behavior.
    if (ctor.parameters.isEmpty()) return AnalysisResult.NotApplicable
    // Value class must have exactly one primary constructor parameter (Kotlin
    // already enforces this, but we add a defensive guard rather than relying on it).
    if (isValue && ctor.parameters.size != 1) return AnalysisResult.NotApplicable

    val ownerSimpleName = symbol.simpleName.asString()
    val messageWireOrder = readMessageWireOrder(symbol)
    val payloadTypeParameter = detectPayloadTypeParameter(symbol)

    val fields = mutableListOf<FieldSpec>()
    val params = ctor.parameters
    for ((index, param) in params.withIndex()) {
        val isTerminal = index == params.lastIndex
        when (
            val fa =
                analyzeField(
                    param = param,
                    messageWireOrder = messageWireOrder,
                    ownerSimpleName = ownerSimpleName,
                    isTerminal = isTerminal,
                    params = params,
                    index = index,
                    payloadTypeParameter = payloadTypeParameter,
                )
        ) {
            is FieldAnalysis.Ok -> fields += fa.field
            is FieldAnalysis.Err -> return AnalysisResult.Rejected(listOf(fa.diagnostic))
        }
    }
    // Terminal-only restriction: LengthFromString and LengthFromList
    // bodies consume a (possibly externally-bounded) trailing byte
    // range and the emit logic doesn't model trailing fields after a
    // variable-length tail. The non-terminal-Conditional restriction
    // does not apply.
    // At most one bounding field per message. A bounding
    // `UseCodecScalar` (codec implements `BoundingLengthCodec`) narrows
    // `buffer.limit()` mid-decode; multiple of them would have ambiguous
    // semantics (last-one-wins isn't a real protocol's wire shape).
    // §2.6 Silent Gap (#13, HIGH): the validator never checks for
    // multiple bounding-codec fields, so this is an emitter-only
    // rejection → Rejected (loud in the next pass).
    if (fields.count { it.isBoundingShape() } > 1) {
        return AnalysisResult.Rejected(
            listOf(
                Diagnostic(
                    "at most one bounding (@UseCodec BoundingLengthCodec) field is supported per @ProtocolMessage",
                    symbol,
                ),
            ),
        )
    }
    for ((index, field) in fields.withIndex()) {
        // §2.6 Silent Gaps (#8/#9/#10/#35): @LengthFrom* on a non-terminal
        // field has no validator terminal-position check → Rejected.
        if (field is FieldSpec.LengthFromString && index != fields.lastIndex) {
            return AnalysisResult.Rejected(
                listOf(
                    Diagnostic(
                        "@LengthFrom val: String must be the last field (terminal-only)",
                        symbol,
                    ),
                ),
            )
        }
        if (field is FieldSpec.LengthFromList && index != fields.lastIndex) {
            return AnalysisResult.Rejected(
                listOf(
                    Diagnostic(
                        "@LengthFrom val: List<@ProtocolMessage> must be the last field (terminal-only)",
                        symbol,
                    ),
                ),
            )
        }
        // `@LengthFrom val: T: @ProtocolMessage` is terminal-only
        // (mirror of `LengthPrefixedMessage`'s terminal-only rule). Lifting
        // would require dispatcher-side wireSize composition that doesn't
        // exist yet for nested-message bodies sized by a sibling.
        if (field is FieldSpec.LengthFromMessage && index != fields.lastIndex) {
            return AnalysisResult.Rejected(
                listOf(
                    Diagnostic(
                        "@LengthFrom val: @ProtocolMessage must be the last field (terminal-only)",
                        symbol,
                    ),
                ),
            )
        }
        // (issue #151 part 2) — `@RemainingBytes` no longer has
        // to be the last field. Trailing fields must all be
        // `FieldSpec.FixedSize` (Scalar + ValueClassScalar today, not
        // UseCodecScalar) so the decode emit can subtract a known byte
        // count from `buffer.limit()` and hand the @RemainingBytes
        // body the correct bounded region. Variable-size trailers
        // would leave the body with no way to know its end without
        // re-encoding — surfaced as a focused validator error in
        // ProtocolMessageProcessor.validateRemainingBytesTrailers.
        // §2.5 / SUPPORT_MATRIX: non-terminal @RemainingBytes with a
        // variable-size trailer is *legitimately paired* with the
        // validator diagnostic (validateRemainingBytesTrailers) → stay
        // silent here (NotApplicable) so we don't double-report.
        if (field is FieldSpec.RemainingBytesProtocolMessageList &&
            index != fields.lastIndex &&
            !trailingFieldsAreFixedSize(fields, index)
        ) {
            return AnalysisResult.NotApplicable
        }
        if (field is FieldSpec.RemainingBytesPayload &&
            index != fields.lastIndex &&
            !trailingFieldsAreFixedSize(fields, index)
        ) {
            return AnalysisResult.NotApplicable
        }
        if (field is FieldSpec.RemainingBytesString &&
            index != fields.lastIndex &&
            !trailingFieldsAreFixedSize(fields, index)
        ) {
            return AnalysisResult.NotApplicable
        }
    }

    // Fill in `reservedTrailingBytes` on every non-terminal
    // `RemainingBytes*` field. Terminal cases (default 0) keep
    // existing emit behavior; non-terminal cases drive the decode
    // emit's `buffer.limit() - <reserved>` adjustment.
    for (i in fields.indices) {
        val field = fields[i]
        if (i == fields.lastIndex) continue
        val reserved = reservedTrailingBytesAfter(fields, i)
        if (reserved == 0) continue
        fields[i] =
            when (field) {
                is FieldSpec.RemainingBytesString ->
                    field.copy(reservedTrailingBytes = reserved)
                is FieldSpec.RemainingBytesProtocolMessageList ->
                    field.copy(reservedTrailingBytes = reserved)
                is FieldSpec.RemainingBytesPayload ->
                    field.copy(reservedTrailingBytes = reserved)
                else -> field
            }
    }

    val pkg = symbol.packageName.asString()
    // If the data class declares <P: Payload> but no
    // RemainingBytesPayload field uses it via ConstructorInjected,
    // the type parameter is unused — return null so the emitter
    // skips. The validator in ProtocolMessageProcessor surfaces
    // the user-facing diagnostic.
    // §2.5 Silent Gap (#34): the prior comment claims the validator
    // surfaces this, but per SUPPORT_MATRIX the validator's
    // declared-unused check only fires for data classes via different
    // control flow and does NOT cover this exact emitter path — so this
    // is a true silent gap → Rejected (loud in the next pass).
    if (payloadTypeParameter != null &&
        fields.none { f ->
            f is FieldSpec.RemainingBytesPayload &&
                f.source is PayloadCodecSource.ConstructorInjected
        }
    ) {
        return AnalysisResult.Rejected(
            listOf(
                Diagnostic(
                    "declares <${payloadTypeParameter.typeVariableName} : Payload> but no " +
                        "@RemainingBytes val: ${payloadTypeParameter.typeVariableName} field uses it",
                    symbol,
                ),
            ),
        )
    }
    return AnalysisResult.Supported(
        CodecShape(
            packageName = pkg,
            messageClassName = classNameOf(symbol),
            ownerSimpleName = ownerSimpleName,
            codecSimpleName = symbol.flattenedCodecName(),
            fields = fields,
            visibility = codecVisibilityModifier(symbol),
            payloadTypeParameter = payloadTypeParameter,
            framedBy = detectFramedBy(symbol),
            customPeek = detectCustomFramePeek(symbol),
        ),
    )
}

/**
 * When [symbol] is a sealed subclass under
 * a parent annotated `@DispatchOn(value class)` AND carries
 * `@PacketType(value = N)`, return the discriminator self-frame
 * spec (inner scalar kind + literal value). Returns null when the
 * symbol has no sealed parent, the parent isn't `@DispatchOn`-
 * annotated, the discriminator type isn't a single-supported-scalar
 * value class, the inner kind isn't peek-supported (UByte / Byte /
 * UShort / UInt — same set as the dispatcher's peek path), or the
 * variant lacks `@PacketType`.
 *
 * Walks declared `superTypes` (not `getAllSuperTypes`) to keep the
 * lookup cheap and to match the shape contract: a `@DispatchOn`
 * sealed parent is the variant's direct super, not a transitive
 * one.
 */
internal fun detectSealedDispatchOnParentDiscriminator(symbol: KSClassDeclaration): SingletonDispatchDiscriminator? {
    val packetTypeAnn =
        symbol.annotations.firstOrNull { it.shortName.asString() == "PacketType" }
            ?: return null
    val literalValue =
        packetTypeAnn.arguments
            .firstOrNull { it.name?.asString() == "value" }
            ?.value as? Int ?: return null
    for (superType in symbol.superTypes) {
        val superDecl = superType.resolve().declaration as? KSClassDeclaration ?: continue
        if (Modifier.SEALED !in superDecl.modifiers) continue
        val dispatchOn =
            superDecl.annotations.firstOrNull { it.shortName.asString() == "DispatchOn" } ?: continue
        val discriminatorType =
            dispatchOn.arguments
                .firstOrNull { it.name?.asString() == "type" }
                ?.value as? KSType ?: continue
        val discriminatorDecl = discriminatorType.declaration as? KSClassDeclaration ?: continue
        if (!discriminatorDecl.isValueClassDecl()) continue
        val ctor = discriminatorDecl.primaryConstructor ?: continue
        if (ctor.parameters.size != 1) continue
        val innerType = ctor.parameters[0].type.resolve()
        if (innerType.isError || innerType.isMarkedNullable) continue
        val innerKind =
            SUPPORTED_SCALARS[innerType.declaration.qualifiedName?.asString()] ?: continue
        if (innerKind !in peekableDispatcherInnerKinds) continue
        return SingletonDispatchDiscriminator(innerKind, literalValue)
    }
    return null
}

/**
 * Detect `@FramedBy(codec, after)` on a
 * `@ProtocolMessage` class. Returns null when the annotation is
 * absent. When present, captures the codec target's class name (so
 * the emit can reference it as `MqttRemainingLengthCodec.encode(...)`
 * etc.) and the `after` field name (empty string by default).
 *
 * The validator (`validateFramedBy` in
 * `ProtocolMessageProcessor.kt`) is responsible for surfacing
 * codec-target / after-field diagnostics; this analyzer trusts the
 * validator and only structurally extracts the annotation arguments.
 */
internal fun detectFramedBy(symbol: KSClassDeclaration): FramedByConfig? {
    // Direct annotation on this symbol.
    symbol.annotations.firstOrNull(::isFramedByAnn)?.let { return parseFramedBy(it) }
    // Inherited from a sealed parent. Per Q3 of
    // the 14b handoff, every variant of a `@FramedBy` sealed parent
    // inherits the framing rule. Walking declared `superTypes` (rather
    // than `getAllSuperTypes`) keeps the lookup cheap and matches the
    // narrow shape the validator already enforces: the framed parent
    // is a direct sealed interface above the variant.
    for (superType in symbol.superTypes) {
        val superDecl = superType.resolve().declaration as? KSClassDeclaration ?: continue
        if (Modifier.SEALED !in superDecl.modifiers) continue
        if (superDecl.classKind != ClassKind.INTERFACE) continue
        val ann = superDecl.annotations.firstOrNull(::isFramedByAnn) ?: continue
        return parseFramedBy(ann)
    }
    return null
}

internal fun parseFramedBy(ann: KSAnnotation): FramedByConfig? {
    val codecKsType =
        ann.arguments.firstOrNull { it.name?.asString() == "codec" }?.value as? KSType
            ?: return null
    val codecDecl = codecKsType.declaration as? KSClassDeclaration ?: return null
    val afterName =
        (ann.arguments.firstOrNull { it.name?.asString() == "after" }?.value as? String) ?: ""
    return FramedByConfig(
        codecClassName = classNameOf(codecDecl),
        afterFieldName = afterName,
    )
}

/**
 * Detect whether the `@ProtocolMessage` data
 * class declares a `<P : Payload>` type parameter. Returns the
 * binding (`typeVariableName`, derived `codecParameterName`,
 * `Payload` bound) when exactly one type parameter is present
 * and its upper bound is `com.ditchoom.buffer.codec.Payload`.
 *
 * Narrow: at most one type parameter, single Payload
 * bound. Multiple type parameters and arbitrary bounds defer.
 */
internal fun detectPayloadTypeParameter(symbol: KSClassDeclaration): PayloadTypeParameter? {
    val typeParams = symbol.typeParameters
    if (typeParams.size != 1) return null
    val tp = typeParams[0]
    // Each type parameter has zero-or-more bounds; require at
    // least one bound that resolves to Payload. Other bounds (e.g.,
    // `<P : Payload, Comparable<P>>`) defer.
    val bounds = tp.bounds.toList()
    if (bounds.size != 1) return null
    val bound = bounds[0].resolve()
    if (bound.isError) return null
    if (bound.declaration.qualifiedName?.asString() != PAYLOAD_QNAME) return null
    val tpName = tp.name.asString()
    return PayloadTypeParameter(
        typeVariableName = tpName,
        codecParameterName = "payloadCodec",
        bound = ClassName(PAYLOAD_PKG, PAYLOAD_SIMPLE),
    )
}

/**
 * Builds a `ClassName` that walks parent declarations so a nested
 * variant like `Command.Ping` is referenced correctly. Top-level
 * classes degrade to `ClassName(pkg, simpleName)` — same shape as
 * before. variants are written nested inside the sealed
 * parent (matching the `@PacketType` kdoc), so this is required
 * for variant `messageClassName` references in the generated
 * `Codec<Command.Ping>` interface and the dispatcher's `is Ping`
 * branches.
 */
internal fun classNameOf(decl: KSClassDeclaration): ClassName {
    val pkg = decl.packageName.asString()
    val chain = mutableListOf<String>()
    var cursor: com.google.devtools.ksp.symbol.KSDeclaration? = decl
    while (cursor is KSClassDeclaration) {
        chain.add(0, cursor.simpleName.asString())
        cursor = cursor.parentDeclaration
    }
    return ClassName(pkg, chain.first(), *chain.drop(1).toTypedArray())
}

/**
 * True when every field at index > `fromIndex` is a
 * `FieldSpec.FixedSize` (Scalar + ValueClassScalar). Used by the
 * non-terminal `@RemainingBytes` qualification check: such trailers
 * have a known wire-byte count, so the body decode can subtract that
 * count from `buffer.limit()` and read its bounded region. Variable-
 * size trailers (`UseCodecScalar`, `LengthPrefixedString`,
 * conditionals, …) defer — extending FixedSize to cover them is a
 * follow-up if a real fixture demands it.
 */
internal fun trailingFieldsAreFixedSize(
    fields: List<FieldSpec>,
    fromIndex: Int,
): Boolean {
    for (i in (fromIndex + 1) until fields.size) {
        if (fields[i] !is FieldSpec.FixedSize) return false
    }
    return true
}

/**
 * Sum of `wireBytes` for every `FieldSpec.FixedSize` field
 * after `fromIndex`. Caller must have already verified via
 * [trailingFieldsAreFixedSize] that the tail qualifies; the
 * `filterIsInstance` is defensive (mirrors [sumOfFixedWireBytes]).
 */
internal fun reservedTrailingBytesAfter(
    fields: List<FieldSpec>,
    fromIndex: Int,
): Int =
    fields
        .drop(fromIndex + 1)
        .filterIsInstance<FieldSpec.FixedSize>()
        .sumOf { it.wireBytes }

internal fun analyzeField(
    param: KSValueParameter,
    messageWireOrder: Endianness,
    ownerSimpleName: String,
    isTerminal: Boolean,
    params: List<KSValueParameter>,
    index: Int,
    payloadTypeParameter: PayloadTypeParameter? = null,
): FieldAnalysis {
    // `@When` opens a separate analysis path: nullability is
    // required, the inner shape is built from the non-null type, and the
    // result wraps in `FieldSpec.Conditional`. The non-conditional analysis
    // below stays unchanged for any field without `@When`.
    val whenAnn =
        param.annotations.firstOrNull { it.shortName.asString() == "When" }
    if (whenAnn != null) {
        return analyzeConditionalField(
            param = param,
            whenAnn = whenAnn,
            messageWireOrder = messageWireOrder,
            ownerSimpleName = ownerSimpleName,
            params = params,
            index = index,
        )
    }
    var lengthPrefixed: KSAnnotation? = null
    var lengthFromAnn: KSAnnotation? = null
    var remainingBytesAnn: KSAnnotation? = null
    var wireBytesAnn: KSAnnotation? = null
    var useCodecAnn: KSAnnotation? = null
    var countAnn: KSAnnotation? = null
    for (ann in param.annotations) {
        when (ann.shortName.asString()) {
            "WireOrder" -> { /* allowed on scalars */ }
            "LengthPrefixed" -> lengthPrefixed = ann
            "LengthFrom" -> lengthFromAnn = ann
            "RemainingBytes" -> remainingBytesAnn = ann
            "WireBytes" -> wireBytesAnn = ann
            "UseCodec" -> useCodecAnn = ann
            "Count" -> countAnn = ann
            else ->
                return FieldAnalysis.Err(
                    Diagnostic(
                        "unknown/unsupported annotation @${ann.shortName.asString()} on field",
                        param,
                    ),
                )
        }
    }
    val name =
        param.name?.asString()
            ?: return FieldAnalysis.Err(Diagnostic("field has no name", param))
    val type = param.type.resolve()
    if (type.isError) {
        return FieldAnalysis.Err(Diagnostic("field type does not resolve", param))
    }
    if (type.isMarkedNullable) {
        return FieldAnalysis.Err(
            Diagnostic("nullable field type requires @When (T? without @When is unsupported)", param),
        )
    }

    // `@Count val: List<@ProtocolMessage T>` — an element-count-prefixed
    // list (varint(N) + N self-delimiting elements). Mutually exclusive with
    // the byte-length list framings (@LengthPrefixed / @LengthFrom /
    // @RemainingBytes) and with @WireBytes / @UseCodec — combining any is a
    // compile error. The field-count framing is self-delimiting, so unlike
    // the drain-to-limit shapes it is not terminal-only.
    if (countAnn != null) {
        if (lengthPrefixed != null || lengthFromAnn != null || remainingBytesAnn != null) {
            return FieldAnalysis.Err(
                Diagnostic(
                    "@Count cannot be combined with @LengthPrefixed/@LengthFrom/@RemainingBytes " +
                        "on the same field — these are alternative list framings (element-count " +
                        "prefix vs. byte-length bound). Choose one.",
                    param,
                ),
            )
        }
        if (wireBytesAnn != null || useCodecAnn != null) {
            return FieldAnalysis.Err(
                Diagnostic("@Count cannot be combined with @WireBytes/@UseCodec", param),
            )
        }
        if (type.declaration.qualifiedName?.asString() != "kotlin.collections.List") {
            return FieldAnalysis.Err(
                Diagnostic("@Count supports only List<@ProtocolMessage>", param),
            )
        }
        return analyzeCountListField(param, type, ownerSimpleName)
    }

    if (remainingBytesAnn != null) {
        // `@RemainingBytes val: List<S>` where S is
        // a single-byte scalar. Mutually exclusive with @LengthFrom /
        // @LengthPrefixed / @WireBytes on the SAME parameter (a bounding
        // sibling field — 's `@UseCodec(BoundingLengthCodec)`
        // is expected and composes via the Partial
        // outer-limit machinery).
        // `@RemainingBytes @UseCodec val: P` where
        // P : Payload routes to RemainingBytesPayload.
        // `@RemainingBytes val: P` where P is the
        // type parameter `<P : Payload>` of the enclosing data class
        // routes to RemainingBytesPayload via ConstructorInjected.
        if (lengthFromAnn != null || lengthPrefixed != null || wireBytesAnn != null) {
            return FieldAnalysis.Err(
                Diagnostic(
                    "@RemainingBytes cannot be combined with @LengthFrom/@LengthPrefixed/@WireBytes",
                    param,
                ),
            )
        }
        if (useCodecAnn != null && (type.implementsPayload() || param.hasViewUseCodec())) {
            // Two typed-payload shapes share one emit: a `Payload`-marked
            // self-contained value, or a non-`Payload` borrowed view whose
            // codec opts in via `ViewCodec` (the zero-copy escape — see
            // [implementsViewCodec]).
            return analyzeRemainingBytesPayloadField(param, type, useCodecAnn, ownerSimpleName)
        }
        if (useCodecAnn == null && payloadTypeParameter != null && type.matchesTypeParameter(payloadTypeParameter)) {
            return FieldAnalysis.Ok(
                FieldSpec.RemainingBytesPayload(
                    name = name,
                    ownerSimpleName = ownerSimpleName,
                    payloadType = TypeVariableName(payloadTypeParameter.typeVariableName),
                    source = PayloadCodecSource.ConstructorInjected(payloadTypeParameter.codecParameterName),
                ),
            )
        }
        val typeQname = type.declaration.qualifiedName?.asString()
        // `@RemainingBytes val: String` — trailing UTF-8 bytes consume the
        // bounded buffer. Documented in the annotation kdoc since
        // `@RemainingBytes` was introduced; the emitter branch landed here.
        if (typeQname == "kotlin.String") {
            return FieldAnalysis.Ok(
                FieldSpec.RemainingBytesString(name = name, ownerSimpleName = ownerSimpleName),
            )
        }
        // `@RemainingBytes val: <value class over String>` — wire-identical
        // to `@RemainingBytes String`, wrapping the trailing UTF-8 bytes in
        // the value class. Detect before the List gate below.
        valueClassOverStringWrapperOrNull(type)?.let { wrapper ->
            return FieldAnalysis.Ok(
                FieldSpec.RemainingBytesString(
                    name = name,
                    ownerSimpleName = ownerSimpleName,
                    valueClass = wrapper,
                ),
            )
        }
        if (typeQname != "kotlin.collections.List") {
            return FieldAnalysis.Err(
                Diagnostic(
                    "@RemainingBytes supports only String, a @JvmInline value class over String, " +
                        "List<@ProtocolMessage>, or @UseCodec val: Payload",
                    param,
                ),
            )
        }
        // Only the `@ProtocolMessage` element shape is
        // accepted now; retired the scalar-element fallback
        // (rejected at the validator with a focused diagnostic
        // pointing at the sealed-dispatcher / `BinaryData` paths).
        return analyzeRemainingBytesProtocolMessageListField(param, type, ownerSimpleName)
    }

    if (lengthFromAnn != null) {
        // `@LengthFrom` field types:
        //   - `String`: body is a single UTF-8 string sized by the
        //     sibling.
        //   - `List<T>` where T is a `@ProtocolMessage` data class:
        //     body is a sequence of nested messages, byte-bounded by
        //     the sibling.
        //   - `T` where T is a `@ProtocolMessage` data class or sealed
        //     parent (issue #151 part 1): body is a single nested
        //     message; the sibling length covers the whole nested wire
        //     form. Mirrors `LengthPrefixedMessage` but draws the
        //     length from a sibling rather than an inline prefix.
        // Mutually exclusive with `@LengthPrefixed` / `@WireBytes` on
        // the same parameter. The adjacent-LF migration suggestion is
        // independent.
        if (lengthPrefixed != null || wireBytesAnn != null) {
            return FieldAnalysis.Err(
                Diagnostic("@LengthFrom cannot be combined with @LengthPrefixed/@WireBytes", param),
            )
        }
        val typeQname = type.declaration.qualifiedName?.asString()
        return when (typeQname) {
            "kotlin.String" ->
                analyzeLengthFromStringField(
                    param = param,
                    lengthFromAnn = lengthFromAnn,
                    ownerSimpleName = ownerSimpleName,
                    params = params,
                    index = index,
                )
            "kotlin.collections.List" ->
                analyzeLengthFromListField(
                    param = param,
                    listType = type,
                    lengthFromAnn = lengthFromAnn,
                    ownerSimpleName = ownerSimpleName,
                    params = params,
                    index = index,
                )
            else -> {
                // `@LengthFrom("ref") val: <value class over String>` —
                // wire-identical to `@LengthFrom String`. Detect before the
                // nested-@ProtocolMessage fallback (value classes over
                // String are neither String, List, nor @ProtocolMessage).
                val stringWrapper = valueClassOverStringWrapperOrNull(type)
                if (stringWrapper != null) {
                    analyzeLengthFromStringField(
                        param = param,
                        lengthFromAnn = lengthFromAnn,
                        ownerSimpleName = ownerSimpleName,
                        params = params,
                        index = index,
                        valueClass = stringWrapper,
                    )
                } else {
                    analyzeLengthFromMessageField(
                        param = param,
                        type = type,
                        lengthFromAnn = lengthFromAnn,
                        ownerSimpleName = ownerSimpleName,
                        params = params,
                        index = index,
                    )
                }
            }
        }
    }

    if (lengthPrefixed != null) {
        // `@LengthPrefixed` and `@WireBytes` together is meaningless and
        // out of scope for this emitter; bail rather than try to interpret.
        if (wireBytesAnn != null) {
            return FieldAnalysis.Err(
                Diagnostic("@LengthPrefixed cannot be combined with @WireBytes", param),
            )
        }
        // `@LengthPrefixed @UseCodec(C::class) val xs:
        // List<E>` routes to LengthPrefixedUseCodecList. The codec drives
        // the wire-format prefix (var-byte-int, etc.) and bounds the
        // element-decode region via `applyBound`. The validator surfaces
        // any user-facing diagnostics (codec not bounding, element not
        // @ProtocolMessage); analyzer returns null silently for shapes
        // outside the slice.
        if (useCodecAnn != null) {
            // Try the list shape first, then fall back to the
            // scalar Payload shape. The list analyzer
            // returns Err for non-`List<...>` field types, so the two
            // shapes are mutually exclusive; an Err falls through to the
            // payload analyzer (preserving the prior `?.let` fallthrough).
            val listResult =
                analyzeLengthPrefixedUseCodecListField(
                    param = param,
                    listType = type,
                    useCodecAnn = useCodecAnn,
                    ownerSimpleName = ownerSimpleName,
                )
            if (listResult is FieldAnalysis.Ok) return listResult
            return analyzeLengthPrefixedUseCodecPayloadField(
                param = param,
                type = type,
                useCodecAnn = useCodecAnn,
                ownerSimpleName = ownerSimpleName,
                prefixWidth = readLengthPrefix(lengthPrefixed),
                prefixWireOrder = messageWireOrder,
            )
        }
        val prefixWidth = readLengthPrefix(lengthPrefixed)
        val qualified = type.declaration.qualifiedName?.asString()
        if (qualified == "kotlin.String") {
            // `@LengthPrefixed val: String` is now allowed at any
            // position. Decode reads prefix + body; encode uses BackPatch
            // and restores position past the body, so subsequent fields
            // emit cleanly. The sequential peek walk handles the prefix
            // chase.
            return FieldAnalysis.Ok(
                FieldSpec.LengthPrefixedString(
                    name = name,
                    ownerSimpleName = ownerSimpleName,
                    prefixWidth = prefixWidth,
                    prefixWireOrder = messageWireOrder,
                ),
            )
        }
        // `@LengthPrefixed val: <value class over String>` — a
        // `@JvmInline value class UserId(val value: String)` typed field.
        // Wire form is byte-identical to `@LengthPrefixed String`; only
        // the generated wrap/unwrap differs. Not terminal-only (same as
        // the bare-String case), so detect it ahead of the terminal
        // `@ProtocolMessage` gate below.
        valueClassOverStringWrapperOrNull(type)?.let { wrapper ->
            return FieldAnalysis.Ok(
                FieldSpec.LengthPrefixedString(
                    name = name,
                    ownerSimpleName = ownerSimpleName,
                    prefixWidth = prefixWidth,
                    prefixWireOrder = messageWireOrder,
                    valueClass = wrapper,
                ),
            )
        }
        // `@LengthPrefixed @ProtocolMessage` body stays terminal-only:
        // wireSize calls into the inner codec's wireSize at encode time,
        // and the dispatcher table needs the body to be the last field
        // for runtime-Exact size composition. Lifting this requires
        // extending the dispatcher / wireSize emit path too.
        // §2.3 Silent Gap: non-terminal @LengthPrefixed @ProtocolMessage
        // has no paired validator diagnostic → Rejected.
        if (!isTerminal) {
            return FieldAnalysis.Err(
                Diagnostic("@LengthPrefixed val: @ProtocolMessage must be the last field (terminal-only)", param),
            )
        }
        val decl =
            type.declaration as? KSClassDeclaration
                ?: return FieldAnalysis.Err(
                    Diagnostic("@LengthPrefixed body type is not a class declaration", param),
                )
        val isProtocolMessage =
            decl.annotations.any { ann ->
                ann.shortName.asString() == "ProtocolMessage" &&
                    ann.annotationType
                        .resolve()
                        .declaration.qualifiedName
                        ?.asString() == PROTOCOL_MESSAGE_QNAME
            }
        if (!isProtocolMessage) {
            return FieldAnalysis.Err(
                Diagnostic("@LengthPrefixed body must be a String or @ProtocolMessage data class", param),
            )
        }
        if (Modifier.DATA !in decl.modifiers) {
            return FieldAnalysis.Err(
                Diagnostic("@LengthPrefixed @ProtocolMessage body must be a data class", param),
            )
        }
        return FieldAnalysis.Ok(
            FieldSpec.LengthPrefixedMessage(
                name = name,
                ownerSimpleName = ownerSimpleName,
                messageType = classNameOf(decl),
                codecType = ClassName(decl.packageName.asString(), decl.flattenedCodecName()),
                prefixWidth = prefixWidth,
                prefixWireOrder = messageWireOrder,
            ),
        )
    }

    if (useCodecAnn != null) {
        // Bare `@UseCodec` on a non-Payload, non-type-parameter
        // scalar (or value-class scalar). The validator (step 3) ensures
        // the codec target is a Kotlin `object` implementing `Codec<T>`
        // for T matching the field type; emit just records the codec
        // class + bounding bit. Mutually exclusive with @WireBytes /
        // @WireOrder on the same parameter — the user codec owns the
        // wire shape.
        if (wireBytesAnn != null) {
            return FieldAnalysis.Err(
                Diagnostic("@UseCodec cannot be combined with @WireBytes (codec owns the wire shape)", param),
            )
        }
        if (param.annotations.any { it.shortName.asString() == "WireOrder" }) {
            return FieldAnalysis.Err(
                Diagnostic("@UseCodec cannot be combined with @WireOrder (codec owns the wire shape)", param),
            )
        }
        return analyzeUseCodecScalarField(param, type, useCodecAnn, ownerSimpleName)
    }

    val qualified =
        type.declaration.qualifiedName?.asString()
            ?: return FieldAnalysis.Err(Diagnostic("field type has no qualified name", param))
    val kind = SUPPORTED_SCALARS[qualified]
    if (kind == null) {
        // A bare String has no inline wire length. This is the most
        // common mistake, so give it a targeted message rather than the
        // generic value-class fallthrough below.
        if (qualified == "kotlin.String") {
            return FieldAnalysis.Err(
                Diagnostic(
                    "String field requires @LengthPrefixed, @LengthFrom, or @RemainingBytes " +
                        "to define its wire length",
                    param,
                ),
            )
        }
        // Enum field: the entry's `ordinal` rides the wire as an unsigned LEB128 varint
        // (UnsignedVarIntCodec), self-delimiting so it stays evolution-safe (see EnumScalar).
        val enumDecl = type.declaration as? KSClassDeclaration
        if (enumDecl?.classKind == ClassKind.ENUM_CLASS) {
            return analyzeEnumField(param, enumDecl, ownerSimpleName, wireBytesAnn)
        }
        // Value-class field. Only the natural-width
        // unannotated path is in scope; @WireBytes / @WireOrder on the
        // outer parameter widen this and are deferred to a later slice.
        if (wireBytesAnn != null) {
            return FieldAnalysis.Err(
                Diagnostic("@WireBytes on a value-class field is not yet supported", param),
            )
        }
        if (param.annotations.any { it.shortName.asString() == "WireOrder" }) {
            return FieldAnalysis.Err(
                Diagnostic("@WireOrder on a value-class field is not yet supported", param),
            )
        }
        // Try the bare `val: T: @ProtocolMessage`
        // shape (data class or sealed parent) before the value-class
        // fallback. Value classes carry `Modifier.VALUE` and don't carry
        // `Modifier.DATA`/`Modifier.SEALED`, so the two branches are
        // mutually exclusive on the modifier check. A bare-message Err
        // falls through to the value-class analyzer (preserving the prior
        // `?.let` fallthrough).
        val bareResult = analyzeBareProtocolMessageField(param, type, ownerSimpleName)
        if (bareResult is FieldAnalysis.Ok) return bareResult
        return analyzeValueClassScalarField(param, ownerSimpleName)
    }

    // Boolean is 1-byte natural-width with no byte order; @WireBytes / @WireOrder
    // are meaningless on Boolean and are rejected here so the manual-byte-assembly
    // path (which has no Boolean support) is never reachable. Forces resolved =
    // Default regardless of the message-level wire order.
    if (kind == ScalarKind.Boolean) {
        if (wireBytesAnn != null) {
            return FieldAnalysis.Err(Diagnostic("@WireBytes is meaningless on a Boolean field", param))
        }
        if (param.annotations.any { it.shortName.asString() == "WireOrder" }) {
            return FieldAnalysis.Err(Diagnostic("@WireOrder is meaningless on a Boolean field", param))
        }
        return FieldAnalysis.Ok(
            FieldSpec.Scalar(
                name = name,
                kind = kind,
                resolvedWireOrder = Endianness.Default,
                wireBytes = 1,
            ),
        )
    }

    val resolved = readFieldWireOrder(param) ?: messageWireOrder
    val wireBytes = wireBytesAnn?.let { readWireBytes(it) } ?: kind.width
    // R4 narrows what the emitter accepts; the validator emits the actual
    // diagnostic. Skip emission for unsupported widths so generated code
    // never references an out-of-bounds bit shift.
    if (wireBytes < MIN_WIRE_BYTES || wireBytes > MAX_WIRE_BYTES) {
        return FieldAnalysis.Err(Diagnostic("@WireBytes width must be in 1..8", param))
    }
    if (wireBytes > kind.width) {
        return FieldAnalysis.Err(Diagnostic("@WireBytes width exceeds the natural width of the field type", param))
    }
    // Signed scalars (and Float/Double, which carry a sign bit and never
    // narrow) only support natural width — partial-read sign extension
    // is its own design and out of scope. With explicit wireOrder they
    // flow through the manual byte-by-byte assembly path below.
    if (kind.isSigned && wireBytes != kind.width) {
        return FieldAnalysis.Err(
            Diagnostic("signed scalars only support natural width (narrowed @WireBytes is unsupported)", param),
        )
    }
    return FieldAnalysis.Ok(
        FieldSpec.Scalar(
            name = name,
            kind = kind,
            resolvedWireOrder = resolved,
            wireBytes = wireBytes,
        ),
    )
}

/**
 * Value-class field analysis for the parent
 * data class. The field's type must be a `value class` whose
 * primary constructor takes a single supported-scalar parameter.
 * Wire form is the inner scalar at natural width and
 * `Endianness.Default`; the parent inlines the read/write rather
 * than calling the value class's separately emitted codec, since
 * the value class's wire form is exactly its inner scalar.
 */
internal fun analyzeValueClassScalarField(
    param: KSValueParameter,
    ownerSimpleName: String,
): FieldAnalysis {
    val name =
        param.name?.asString()
            ?: return FieldAnalysis.Err(Diagnostic("field has no name", param))
    val type = param.type.resolve()
    if (type.isError) return FieldAnalysis.Err(Diagnostic("field type does not resolve", param))
    if (type.isMarkedNullable) {
        return FieldAnalysis.Err(Diagnostic("value-class field must be non-nullable (use @When for T?)", param))
    }
    val decl =
        type.declaration as? KSClassDeclaration
            ?: return FieldAnalysis.Err(Diagnostic("field type is not a class declaration", param))
    if (!decl.isValueClassDecl()) {
        return FieldAnalysis.Err(Diagnostic("unsupported field type (not a scalar or @JvmInline value class)", param))
    }
    val ctor =
        decl.primaryConstructor
            ?: return FieldAnalysis.Err(Diagnostic("value class has no primary constructor", param))
    if (ctor.parameters.size != 1) {
        return FieldAnalysis.Err(Diagnostic("value class must have exactly one constructor parameter", param))
    }
    val innerParam = ctor.parameters[0]
    // Varint discriminator field: the value class's inner scalar carries
    // `@UseCodec(VariableLengthCodec)`, so its wire form is variable-width
    // and not an inline fixed scalar. Re-read it through the value class's
    // own generated codec (which delegates to the consumer's variable-length
    // codec) — the same UseCodecScalar machinery a bare varint field uses,
    // so decode/encode/peek all compose. Only varint inners take this path;
    // every existing value class stays on the inline-scalar path below,
    // keeping its goldens byte-identical.
    if (innerParam.hasVariableLengthUseCodec()) {
        return FieldAnalysis.Ok(
            FieldSpec.UseCodecScalar(
                name = name,
                ownerSimpleName = ownerSimpleName,
                fieldType = classNameOf(decl),
                codecType =
                    ClassName(
                        decl.packageName.asString(),
                        decl.flattenedCodecName(),
                    ),
                isBounding = false,
                isVariableLength = true,
            ),
        )
    }
    val innerName =
        innerParam.name?.asString()
            ?: return FieldAnalysis.Err(Diagnostic("value class inner parameter has no name", param))
    val innerType = innerParam.type.resolve()
    if (innerType.isError) {
        return FieldAnalysis.Err(Diagnostic("value class inner type does not resolve", param))
    }
    if (innerType.isMarkedNullable) {
        return FieldAnalysis.Err(Diagnostic("value class inner scalar must be non-nullable", param))
    }
    val innerQname =
        innerType.declaration.qualifiedName?.asString()
            ?: return FieldAnalysis.Err(Diagnostic("value class inner type has no qualified name", param))
    val innerKind =
        SUPPORTED_SCALARS[innerQname]
            ?: return FieldAnalysis.Err(
                Diagnostic("value class inner type is not a supported scalar", param),
            )
    // Limits the inner scalar to its natural-width default-order
    // path. @WireBytes / @WireOrder on the inner property would widen the
    // shape and are deferred along with the same widening on direct
    // scalar fields.
    if (innerParam.annotations.any { ann ->
            val n = ann.shortName.asString()
            n == "WireBytes" || n == "WireOrder"
        }
    ) {
        return FieldAnalysis.Err(
            Diagnostic("@WireBytes/@WireOrder on a value class inner property is not yet supported", param),
        )
    }
    return FieldAnalysis.Ok(
        FieldSpec.ValueClassScalar(
            name = name,
            ownerSimpleName = ownerSimpleName,
            valueClassType = classNameOf(decl),
            innerKind = innerKind,
            innerPropertyName = innerName,
            wireBytes = innerKind.width,
            // Follow-up: propagate the value class's own
            // `@ProtocolMessage(wireOrder)` so multi-byte inner kinds
            // (UShort/UInt) assemble bytes correctly during peek-side
            // reconstruction in the sequential walk. Defaults to
            // Endianness.Default (collapses to big-endian) when the
            // value class doesn't declare an order, matching 's
            // prior behavior.
            valueClassWireOrder = readMessageWireOrder(decl),
        ),
    )
}

/**
 * Detect a `@JvmInline value class` over a single `String`
 * constructor parameter (e.g. `value class UserId(val value:
 * String)`). Returns the wire-insignificant [ValueClassStringWrapper]
 * when [type] matches, or `null` otherwise so callers fall through to
 * their existing bare-`String` / message handling.
 *
 * Mirrors the validity checks applied to [analyzeValueClassScalarField]'s
 * scalar path: exactly one primary-constructor parameter, over a
 * non-nullable inner, with no `@WireBytes` / `@WireOrder` on the inner
 * property (out of scope, same narrowing as the scalar shape). The
 * only difference is the inner type is `kotlin.String` rather than a
 * numeric scalar — so a String value class routes to the length-framed
 * String field logic instead of the inline-scalar logic.
 */
@Suppress("ReturnCount") // guard-clause validity checks, consistent with the analyze* family
internal fun valueClassOverStringWrapperOrNull(type: KSType): ValueClassStringWrapper? {
    val decl = type.declaration as? KSClassDeclaration ?: return null
    if (!decl.isValueClassDecl()) return null
    // A value class that is itself `@ProtocolMessage` has its own generated
    // codec and keeps routing to the nested-message paths — it is not
    // treated as a transparent String wrapper. The target of this shape is
    // the plain id wrapper (`value class UserId(val value: String)`) with no
    // `@ProtocolMessage`.
    if (decl.annotations.any { ann ->
            ann.shortName.asString() == "ProtocolMessage" &&
                ann.annotationType
                    .resolve()
                    .declaration.qualifiedName
                    ?.asString() == PROTOCOL_MESSAGE_QNAME
        }
    ) {
        return null
    }
    val ctor = decl.primaryConstructor ?: return null
    if (ctor.parameters.size != 1) return null
    val innerParam = ctor.parameters[0]
    val innerName = innerParam.name?.asString() ?: return null
    val innerType = innerParam.type.resolve()
    if (innerType.isError || innerType.isMarkedNullable) return null
    if (innerType.declaration.qualifiedName?.asString() != "kotlin.String") return null
    // @WireBytes / @WireOrder on the inner property widen the wire
    // shape and are deferred — same narrowing the inline-scalar value
    // class path applies.
    if (innerParam.annotations.any { ann ->
            val n = ann.shortName.asString()
            n == "WireBytes" || n == "WireOrder"
        }
    ) {
        return null
    }
    return ValueClassStringWrapper(
        valueClassType = classNameOf(decl),
        innerPropertyName = innerName,
    )
}

/**
 * `@LengthFrom("ref") val: String`. Two source-expression forms:
 *   - Simple-name `"sibling"`: sibling is a numeric `Scalar`; body
 *     byte count = `sibling.toInt()`.
 *   - Dotted `"sibling.property"`: sibling is a value-class field;
 *     body byte count = `sibling.property` (the property returns `Int`
 *     directly).
 *
 * Returns null silently for any shape the validator already names —
 * the validator's diagnostic is the user-facing surface.
 */
internal fun analyzeLengthFromStringField(
    param: KSValueParameter,
    lengthFromAnn: KSAnnotation,
    ownerSimpleName: String,
    params: List<KSValueParameter>,
    index: Int,
    valueClass: ValueClassStringWrapper? = null,
): FieldAnalysis {
    val name =
        param.name?.asString() ?: return FieldAnalysis.Err(Diagnostic("field has no name", param))
    val type = param.type.resolve()
    if (type.isError) return FieldAnalysis.Err(Diagnostic("field type does not resolve", param))
    if (type.isMarkedNullable) {
        return FieldAnalysis.Err(Diagnostic("@LengthFrom val: String must be non-nullable", param))
    }
    // A non-null [valueClass] means the caller already matched a value
    // class over `String` (wire-identical to a bare `String`); skip the
    // bare-`String` type check in that case.
    if (valueClass == null && type.declaration.qualifiedName?.asString() != "kotlin.String") {
        return FieldAnalysis.Err(Diagnostic("@LengthFrom String analyzer requires a String field", param))
    }

    val referenced =
        lengthFromAnn.arguments
            .firstOrNull { it.name?.asString() == "field" }
            ?.value as? String
            ?: return FieldAnalysis.Err(Diagnostic("@LengthFrom is missing its field argument", param))
    val source =
        analyzeLengthSource(referenced, params, index)
            ?: return FieldAnalysis.Err(
                Diagnostic("@LengthFrom(\"$referenced\") does not resolve to a supported length carrier", param),
            )
    return FieldAnalysis.Ok(
        FieldSpec.LengthFromString(
            name = name,
            ownerSimpleName = ownerSimpleName,
            source = source,
            valueClass = valueClass,
        ),
    )
}

/**
 * Resolve a `@LengthFrom` annotation argument into a typed
 * `LengthSource`. Simple-name `"sibling"` → `LengthSource.Sibling`;
 * dotted `"sibling.property"` → `LengthSource.ValueClassProperty`.
 * Returns null when the shape is out of scope (missing sibling,
 * declared-after, simple form with non-numeric sibling, dotted form
 * with non-value-class sibling or non-Int-returning property) — the
 * validator's diagnostic is the user-facing surface.
 */
internal fun analyzeLengthSource(
    referenced: String,
    params: List<KSValueParameter>,
    index: Int,
): LengthSource? {
    val parts = referenced.split('.')
    if (parts.size > 2) return null
    val siblingName = parts[0]
    val propertyName = parts.getOrNull(1)
    val sibling = locatePriorSibling(siblingName, params, index) ?: return null
    val siblingType = sibling.type.resolve()
    if (siblingType.isError || siblingType.isMarkedNullable) return null
    if (propertyName == null) {
        // Simple form: sibling must be a numeric scalar in the peekable
        // kind set.
        val siblingQname = siblingType.declaration.qualifiedName?.asString() ?: return null
        val siblingKind = SUPPORTED_SCALARS[siblingQname] ?: return null
        if (siblingKind !in peekableLengthFromSiblingKinds) return null
        return LengthSource.Sibling(siblingName, siblingKind)
    }
    // Dotted form: sibling must be a `value class` with a single
    // peekable-scalar inner; property must be a non-extension `val`
    // returning non-nullable `Int`.
    val siblingDecl = siblingType.declaration as? KSClassDeclaration ?: return null
    if (!siblingDecl.isValueClassDecl()) return null
    val ctor = siblingDecl.primaryConstructor ?: return null
    if (ctor.parameters.size != 1) return null
    val innerType = ctor.parameters[0].type.resolve()
    if (innerType.isError || innerType.isMarkedNullable) return null
    val innerQname = innerType.declaration.qualifiedName?.asString() ?: return null
    val innerKind = SUPPORTED_SCALARS[innerQname] ?: return null
    if (innerKind !in peekableLengthFromSiblingKinds) return null
    val property =
        siblingDecl
            .getDeclaredProperties()
            .firstOrNull { it.simpleName.asString() == propertyName } ?: return null
    if (property.isMutable || property.extensionReceiver != null) return null
    val returnType = property.type.resolve()
    if (returnType.isMarkedNullable) return null
    if (returnType.declaration.qualifiedName?.asString() != "kotlin.Int") return null
    return LengthSource.ValueClassProperty(
        siblingName = siblingName,
        propertyName = propertyName,
        valueClassInnerKind = innerKind,
    )
}

internal val peekableLengthFromSiblingKinds =
    setOf(ScalarKind.UByte, ScalarKind.Byte, ScalarKind.UShort, ScalarKind.UInt)

/**
 * `@LengthFrom("siblingField") val: List<T>`
 * where `T` is a `@ProtocolMessage data class`. Same sibling-
 * resolution rules as `analyzeLengthFromStringField`; the
 * difference is the bound field's wire shape (variable-count
 * sequence of nested-message bodies vs. single UTF-8 string).
 *
 * Returns null silently for shapes the validator doesn't
 * accept yet — non-`@ProtocolMessage` element type, value-class
 * sibling, signed-multi-byte sibling.
 */
internal fun analyzeLengthFromListField(
    param: KSValueParameter,
    listType: KSType,
    lengthFromAnn: KSAnnotation,
    ownerSimpleName: String,
    params: List<KSValueParameter>,
    index: Int,
): FieldAnalysis {
    val name =
        param.name?.asString() ?: return FieldAnalysis.Err(Diagnostic("field has no name", param))
    val typeArgs = listType.arguments
    if (typeArgs.size != 1) {
        return FieldAnalysis.Err(Diagnostic("@LengthFrom List must have exactly one type argument", param))
    }
    val elementType =
        typeArgs[0].type?.resolve()
            ?: return FieldAnalysis.Err(Diagnostic("@LengthFrom List element type does not resolve", param))
    if (elementType.isError || elementType.isMarkedNullable) {
        return FieldAnalysis.Err(Diagnostic("@LengthFrom List element must be a resolvable non-nullable type", param))
    }
    val elementDecl =
        elementType.declaration as? KSClassDeclaration
            ?: return FieldAnalysis.Err(Diagnostic("@LengthFrom List element is not a class declaration", param))
    if (Modifier.DATA !in elementDecl.modifiers) {
        return FieldAnalysis.Err(Diagnostic("@LengthFrom List element must be a @ProtocolMessage data class", param))
    }
    val isProtocolMessage =
        elementDecl.annotations.any { ann ->
            ann.shortName.asString() == "ProtocolMessage" &&
                ann.annotationType
                    .resolve()
                    .declaration.qualifiedName
                    ?.asString() == PROTOCOL_MESSAGE_QNAME
        }
    if (!isProtocolMessage) {
        return FieldAnalysis.Err(Diagnostic("@LengthFrom List element must be annotated @ProtocolMessage", param))
    }

    val referenced =
        lengthFromAnn.arguments
            .firstOrNull { it.name?.asString() == "field" }
            ?.value as? String
            ?: return FieldAnalysis.Err(Diagnostic("@LengthFrom is missing its field argument", param))
    val source =
        analyzeLengthSource(referenced, params, index)
            ?: return FieldAnalysis.Err(
                Diagnostic("@LengthFrom(\"$referenced\") does not resolve to a supported length carrier", param),
            )

    return FieldAnalysis.Ok(
        FieldSpec.LengthFromList(
            name = name,
            ownerSimpleName = ownerSimpleName,
            source = source,
            elementClassName = classNameOf(elementDecl),
            elementCodecClassName =
                ClassName(
                    elementDecl.packageName.asString(),
                    elementDecl.flattenedCodecName(),
                ),
        ),
    )
}

/**
 * (issue #151 part 1) — `@LengthFrom("siblingField") val: T`
 * where `T` is a `@ProtocolMessage` data class or sealed parent. The
 * sibling resolves a length value (same `LengthSource` as
 * [analyzeLengthFromStringField] / [analyzeLengthFromListField]) that
 * bounds the nested message's decode region; encode delegates to
 * `<TCodec>.encode` and the user is responsible for setting the
 * sibling to the body's wire size.
 *
 * Returns null silently when the field type isn't a `@ProtocolMessage`
 * data class or sealed parent. Payload-generic types (`<P : Payload>`)
 * reject — same rule as the other by-name shapes (their codec is a
 * class taking a constructor-injected payload codec, not a singleton).
 */
internal fun analyzeLengthFromMessageField(
    param: KSValueParameter,
    type: KSType,
    lengthFromAnn: KSAnnotation,
    ownerSimpleName: String,
    params: List<KSValueParameter>,
    index: Int,
): FieldAnalysis {
    val name =
        param.name?.asString() ?: return FieldAnalysis.Err(Diagnostic("field has no name", param))
    if (type.isError || type.isMarkedNullable) {
        return FieldAnalysis.Err(Diagnostic("@LengthFrom field must be a resolvable non-nullable type", param))
    }
    val decl =
        type.declaration as? KSClassDeclaration
            ?: return FieldAnalysis.Err(Diagnostic("@LengthFrom field type is not a class declaration", param))
    val isProtocolMessage =
        decl.annotations.any { ann ->
            ann.shortName.asString() == "ProtocolMessage" &&
                ann.annotationType
                    .resolve()
                    .declaration.qualifiedName
                    ?.asString() == PROTOCOL_MESSAGE_QNAME
        }
    if (!isProtocolMessage) {
        return FieldAnalysis.Err(
            Diagnostic(
                "@LengthFrom requires the field to be String, List<@ProtocolMessage>, or @ProtocolMessage",
                param,
            ),
        )
    }
    // Accept both data-class and sealed-parent shapes — the by-name
    // codec resolution is identical (`<TCodec>.decode/encode`); the
    // sealed parent's codec is the dispatcher object.
    val isDataClass = Modifier.DATA in decl.modifiers
    val isSealed = Modifier.SEALED in decl.modifiers
    if (!isDataClass && !isSealed) {
        return FieldAnalysis.Err(
            Diagnostic("@LengthFrom @ProtocolMessage field must be a data class or sealed parent", param),
        )
    }
    // Payload-generic types reject (their codec is a class with
    // constructor-injected codec, not a singleton object).
    if (decl.typeParameters.isNotEmpty()) {
        return FieldAnalysis.Err(
            Diagnostic("@LengthFrom @ProtocolMessage field type cannot carry a <P : Payload> type parameter", param),
        )
    }

    val referenced =
        lengthFromAnn.arguments
            .firstOrNull { it.name?.asString() == "field" }
            ?.value as? String
            ?: return FieldAnalysis.Err(Diagnostic("@LengthFrom is missing its field argument", param))
    val source =
        analyzeLengthSource(referenced, params, index)
            ?: return FieldAnalysis.Err(
                Diagnostic("@LengthFrom(\"$referenced\") does not resolve to a supported length carrier", param),
            )

    return FieldAnalysis.Ok(
        FieldSpec.LengthFromMessage(
            name = name,
            ownerSimpleName = ownerSimpleName,
            source = source,
            messageType = classNameOf(decl),
            codecType =
                ClassName(
                    decl.packageName.asString(),
                    decl.flattenedCodecName(),
                ),
        ),
    )
}

/**
 * `@RemainingBytes val: List<T>` where `T` is a
 * `@ProtocolMessage data class`. Sister of
 * `analyzeLengthFromListField` (, sibling-bounded) for the
 * caller-bounded variant: instead of a sibling length carrier, the
 * outer protocol's framing sets `buffer.limit()` before delegating,
 * and the read loop runs `while (position < limit)`.
 *
 * Returns null silently when the list element isn't a
 * `@ProtocolMessage data class` / sealed parent; retired
 * the scalar-element fallback, so non-message element types are
 * rejected at the validator now.
 */
internal fun analyzeRemainingBytesProtocolMessageListField(
    param: KSValueParameter,
    listType: KSType,
    ownerSimpleName: String,
): FieldAnalysis {
    val name =
        param.name?.asString() ?: return FieldAnalysis.Err(Diagnostic("field has no name", param))
    val typeArgs = listType.arguments
    if (typeArgs.size != 1) {
        return FieldAnalysis.Err(Diagnostic("@RemainingBytes List must have exactly one type argument", param))
    }
    val elementType =
        typeArgs[0].type?.resolve()
            ?: return FieldAnalysis.Err(Diagnostic("@RemainingBytes List element type does not resolve", param))
    if (elementType.isError || elementType.isMarkedNullable) {
        return FieldAnalysis.Err(
            Diagnostic("@RemainingBytes List element must be a resolvable non-nullable type", param),
        )
    }
    val elementDecl =
        elementType.declaration as? KSClassDeclaration
            ?: return FieldAnalysis.Err(Diagnostic("@RemainingBytes List element is not a class declaration", param))
    // Widened to also accept a `@ProtocolMessage`
    // sealed parent (with `@DispatchOn`). Mirrors the widening
    // applied to `analyzeLengthPrefixedListSpec`. The encode emit
    // (`appendEncodeRemainingBytesProtocolMessageList`) calls the
    // element's `Codec.encode(...)` per element — for a sealed parent
    // that's the dispatcher's encode, which handles BackPatch variants
    // internally via its own scratch logic.
    val isDataClass = Modifier.DATA in elementDecl.modifiers
    val isSealed = Modifier.SEALED in elementDecl.modifiers
    if (!isDataClass && !isSealed) {
        return FieldAnalysis.Err(
            Diagnostic("@RemainingBytes List element must be a @ProtocolMessage data class or sealed parent", param),
        )
    }
    val isProtocolMessage =
        elementDecl.annotations.any { ann ->
            ann.shortName.asString() == "ProtocolMessage" &&
                ann.annotationType
                    .resolve()
                    .declaration.qualifiedName
                    ?.asString() == PROTOCOL_MESSAGE_QNAME
        }
    if (!isProtocolMessage) {
        return FieldAnalysis.Err(
            Diagnostic("@RemainingBytes List element must be annotated @ProtocolMessage", param),
        )
    }
    // Same payload-generic reject as
    // `analyzeLengthPrefixedListSpec` ( rule). A `<P:
    // Payload>` element generates a generic-class codec, not a
    // singleton object; the per-element `<E>Codec.encode(...)` call
    // requires the singleton form.
    if (detectPayloadTypeParameter(elementDecl) != null) {
        return FieldAnalysis.Err(
            Diagnostic("@RemainingBytes List element cannot carry a <P : Payload> type parameter", param),
        )
    }
    return FieldAnalysis.Ok(
        FieldSpec.RemainingBytesProtocolMessageList(
            name = name,
            ownerSimpleName = ownerSimpleName,
            elementClassName = classNameOf(elementDecl),
            elementCodecClassName =
                ClassName(
                    elementDecl.packageName.asString(),
                    elementDecl.flattenedCodecName(),
                ),
            elementIsBackPatch = detectElementBackPatch(elementDecl),
        ),
    )
}

/**
 * `@Count val: List<T>` where `T` is a `@ProtocolMessage data class`
 * (or a sealed parent with `@DispatchOn`). The element-count complement to
 * [analyzeRemainingBytesProtocolMessageListField]: same element-type
 * universe (data class OR sealed parent, `@ProtocolMessage`-annotated,
 * non-payload-generic) and the same by-name `${T.simpleName}Codec`
 * resolution; only the framing differs (a varint element count vs. a
 * caller-set byte limit). Produces [FieldSpec.CountPrefixedProtocolMessageList].
 *
 * Uses early returns as guard clauses to reject unsupported element shapes one validation
 * step at a time (mirroring [analyzeRemainingBytesProtocolMessageListField]); collapsing them
 * into a single exit would obscure which specific constraint failed.
 */
@Suppress("ReturnCount")
internal fun analyzeCountListField(
    param: KSValueParameter,
    listType: KSType,
    ownerSimpleName: String,
): FieldAnalysis {
    val name =
        param.name?.asString() ?: return FieldAnalysis.Err(Diagnostic("field has no name", param))
    val typeArgs = listType.arguments
    if (typeArgs.size != 1) {
        return FieldAnalysis.Err(Diagnostic("@Count List must have exactly one type argument", param))
    }
    val elementType =
        typeArgs[0].type?.resolve()
            ?: return FieldAnalysis.Err(Diagnostic("@Count List element type does not resolve", param))
    if (elementType.isError || elementType.isMarkedNullable) {
        return FieldAnalysis.Err(
            Diagnostic("@Count List element must be a resolvable non-nullable type", param),
        )
    }
    val elementDecl =
        elementType.declaration as? KSClassDeclaration
            ?: return FieldAnalysis.Err(Diagnostic("@Count List element is not a class declaration", param))
    val isDataClass = Modifier.DATA in elementDecl.modifiers
    val isSealed = Modifier.SEALED in elementDecl.modifiers
    if (!isDataClass && !isSealed) {
        return FieldAnalysis.Err(
            Diagnostic("@Count List element must be a @ProtocolMessage data class or sealed parent", param),
        )
    }
    val isProtocolMessage =
        elementDecl.annotations.any { ann ->
            ann.shortName.asString() == "ProtocolMessage" &&
                ann.annotationType
                    .resolve()
                    .declaration.qualifiedName
                    ?.asString() == PROTOCOL_MESSAGE_QNAME
        }
    if (!isProtocolMessage) {
        return FieldAnalysis.Err(
            Diagnostic("@Count List element must be annotated @ProtocolMessage", param),
        )
    }
    if (detectPayloadTypeParameter(elementDecl) != null) {
        return FieldAnalysis.Err(
            Diagnostic("@Count List element cannot carry a <P : Payload> type parameter", param),
        )
    }
    return FieldAnalysis.Ok(
        FieldSpec.CountPrefixedProtocolMessageList(
            name = name,
            ownerSimpleName = ownerSimpleName,
            elementClassName = classNameOf(elementDecl),
            elementCodecClassName =
                ClassName(
                    elementDecl.packageName.asString(),
                    elementDecl.flattenedCodecName(),
                ),
            elementIsBackPatch = detectElementBackPatch(elementDecl),
        ),
    )
}

/**
 * Bare `val: T` where T is a `@ProtocolMessage`
 * data class or sealed parent (with `@DispatchOn`). Mirrors
 * [analyzeRemainingBytesProtocolMessageListField] for element-type
 * requirements: data class OR sealed parent, `@ProtocolMessage`-
 * annotated, non-payload-generic. Codec class resolves by-name to
 * `${T.simpleName}Codec` in T's package.
 *
 * Caller-side gate: only invoked from the no-framing-annotation
 * fall-through in `analyzeField` (no `@LengthPrefixed`,
 * `@RemainingBytes`, `@LengthFrom`, `@When`, or `@UseCodec`). The
 * conditional sister case is in [analyzeConditionalProtocolMessageInner].
 *
 * Establishes the by-name principle for `@ProtocolMessage` typed
 * fields uniformly across framing annotations — see
 * [FieldSpec.ProtocolMessageScalar] kdoc for the principle in full.
 */

internal fun analyzeBareProtocolMessageField(
    param: KSValueParameter,
    type: KSType,
    ownerSimpleName: String,
): FieldAnalysis {
    if (type.isError || type.isMarkedNullable) {
        return FieldAnalysis.Err(
            Diagnostic("bare @ProtocolMessage field must be a resolvable non-nullable type", param),
        )
    }
    val name =
        param.name?.asString() ?: return FieldAnalysis.Err(Diagnostic("field has no name", param))
    val decl =
        type.declaration as? KSClassDeclaration
            ?: return FieldAnalysis.Err(Diagnostic("field type is not a class declaration", param))
    val isDataClass = Modifier.DATA in decl.modifiers
    val isSealed = Modifier.SEALED in decl.modifiers
    if (!isDataClass && !isSealed) {
        return FieldAnalysis.Err(
            Diagnostic("bare nested message field must be a @ProtocolMessage data class or sealed parent", param),
        )
    }
    val isProtocolMessage =
        decl.annotations.any { ann ->
            ann.shortName.asString() == "ProtocolMessage" &&
                ann.annotationType
                    .resolve()
                    .declaration.qualifiedName
                    ?.asString() == PROTOCOL_MESSAGE_QNAME
        }
    if (!isProtocolMessage) {
        return FieldAnalysis.Err(
            Diagnostic("bare nested message field type must be annotated @ProtocolMessage", param),
        )
    }
    if (detectPayloadTypeParameter(decl) != null) {
        return FieldAnalysis.Err(
            Diagnostic("bare nested message field type cannot carry a <P : Payload> type parameter", param),
        )
    }
    return FieldAnalysis.Ok(
        FieldSpec.ProtocolMessageScalar(
            name = name,
            ownerSimpleName = ownerSimpleName,
            fieldType = classNameOf(decl),
            codecType =
                ClassName(
                    decl.packageName.asString(),
                    decl.flattenedCodecName(),
                ),
        ),
    )
}

/**
 * Analyze a Kotlin `enum class` field into a [FieldSpec.EnumScalar]. The entry's `ordinal` rides
 * the wire as an unsigned LEB128 varint. At most one `@EnumDefault` entry is allowed (the
 * unknown-ordinal decode sink); `@WireBytes` / `@WireOrder` are meaningless on the varint and are
 * rejected.
 */
internal fun analyzeEnumField(
    param: KSValueParameter,
    enumDecl: KSClassDeclaration,
    ownerSimpleName: String,
    wireBytesAnn: KSAnnotation?,
): FieldAnalysis {
    val name =
        param.name?.asString() ?: return FieldAnalysis.Err(Diagnostic("field has no name", param))
    if (wireBytesAnn != null) {
        return FieldAnalysis.Err(
            Diagnostic("@WireBytes is not supported on an enum field (its ordinal rides as a varint)", param),
        )
    }
    if (param.annotations.any { it.shortName.asString() == "WireOrder" }) {
        return FieldAnalysis.Err(
            Diagnostic("@WireOrder is not supported on an enum field (the varint is byte-order-independent)", param),
        )
    }
    val entries =
        enumDecl.declarations
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.ENUM_ENTRY }
            .toList()
    val defaults = entries.filter { e -> e.annotations.any { it.shortName.asString() == "EnumDefault" } }
    if (defaults.size > 1) {
        return FieldAnalysis.Err(
            Diagnostic(
                "enum ${enumDecl.simpleName.asString()} declares ${defaults.size} @EnumDefault entries; " +
                    "at most one is allowed",
                param,
            ),
        )
    }
    return FieldAnalysis.Ok(
        FieldSpec.EnumScalar(
            name = name,
            ownerSimpleName = ownerSimpleName,
            enumType = classNameOf(enumDecl),
            entryCount = entries.size,
            defaultEntryName = defaults.firstOrNull()?.simpleName?.asString(),
            // Declaration order == ordinal order: `entries` is the source-order
            // list of ENUM_ENTRY declarations, so index i is ordinal i.
            entryNames = entries.map { it.simpleName.asString() },
        ),
    )
}

/**
 * `@RemainingBytes @UseCodec(C::class) val: P`
 * where `P` extends `com.ditchoom.buffer.codec.Payload` and `C` is
 * a Kotlin `object` implementing `Codec<P>`. Returns null silently
 * for shapes the validator rejects (target not an `object`, target
 * doesn't implement `Codec<P>`); the validator surfaces the
 * user-facing diagnostic.
 */
internal fun analyzeRemainingBytesPayloadField(
    param: KSValueParameter,
    type: KSType,
    useCodecAnn: KSAnnotation,
    ownerSimpleName: String,
): FieldAnalysis {
    val name =
        param.name?.asString() ?: return FieldAnalysis.Err(Diagnostic("field has no name", param))
    val payloadDecl =
        type.declaration as? KSClassDeclaration
            ?: return FieldAnalysis.Err(
                Diagnostic("@RemainingBytes @UseCodec field type is not a class declaration", param),
            )
    val codecKsType =
        useCodecAnn.arguments
            .firstOrNull { it.name?.asString() == "codec" }
            ?.value as? KSType
            ?: return FieldAnalysis.Err(Diagnostic("@UseCodec is missing its codec argument", param))
    val codecDecl =
        codecKsType.declaration as? KSClassDeclaration
            ?: return FieldAnalysis.Err(Diagnostic("@UseCodec codec target is not a class declaration", param))
    if (codecDecl.classKind != ClassKind.OBJECT) {
        return FieldAnalysis.Err(Diagnostic("@UseCodec codec target must be a Kotlin object declaration", param))
    }
    val codecPkg = codecDecl.packageName.asString()
    val codecSimple = codecDecl.simpleName.asString()
    return FieldAnalysis.Ok(
        FieldSpec.RemainingBytesPayload(
            name = name,
            ownerSimpleName = ownerSimpleName,
            payloadType = classNameOf(payloadDecl),
            source = PayloadCodecSource.UserCodecObject(ClassName(codecPkg, codecSimple)),
        ),
    )
}

/**
 * Bare `@UseCodec(C::class) val: <scalar>`. Resolves the
 * field's declared `TypeName` (primitive scalar via [scalarTypeName]
 * or value-class via [classNameOf]) and inspects the codec's
 * supertype chain to decide whether `C` implements
 * [BoundingLengthCodec] (drives the `applyBound` + try/finally
 * emit). Returns `null` silently when the codec target isn't a
 * Kotlin `object` — the validator surfaces the diagnostic.
 */
internal fun analyzeUseCodecScalarField(
    param: KSValueParameter,
    type: KSType,
    useCodecAnn: KSAnnotation,
    ownerSimpleName: String,
): FieldAnalysis {
    val name =
        param.name?.asString() ?: return FieldAnalysis.Err(Diagnostic("field has no name", param))
    val codecKsType =
        useCodecAnn.arguments
            .firstOrNull { it.name?.asString() == "codec" }
            ?.value as? KSType
            ?: return FieldAnalysis.Err(Diagnostic("@UseCodec is missing its codec argument", param))
    val codecDecl =
        codecKsType.declaration as? KSClassDeclaration
            ?: return FieldAnalysis.Err(Diagnostic("@UseCodec codec target is not a class declaration", param))
    if (codecDecl.classKind != ClassKind.OBJECT) {
        return FieldAnalysis.Err(Diagnostic("@UseCodec codec target must be a Kotlin object declaration", param))
    }
    val fieldTypeName: TypeName =
        SUPPORTED_SCALARS[type.declaration.qualifiedName?.asString()]
            ?.let { scalarTypeName(it) }
            ?: (type.declaration as? KSClassDeclaration)?.let { classNameOf(it) }
            ?: return FieldAnalysis.Err(
                Diagnostic("@UseCodec field type is neither a supported scalar nor a classifiable type", param),
            )
    val codecClassName = ClassName(codecDecl.packageName.asString(), codecDecl.simpleName.asString())
    return FieldAnalysis.Ok(
        FieldSpec.UseCodecScalar(
            name = name,
            ownerSimpleName = ownerSimpleName,
            fieldType = fieldTypeName,
            codecType = codecClassName,
            isBounding = codecDecl.implementsBoundingLengthCodec(),
            isVariableLength = codecDecl.implementsVariableLengthCodec(),
        ),
    )
}

/**
 * `@LengthPrefixed @UseCodec(C::class) val xs:
 * List<E>` analyzer. Returns the new shape when:
 *   - field type is `kotlin.collections.List<E>` with E a
 *     `@ProtocolMessage data class`,
 *   - `C` is a Kotlin `object` implementing
 *     `BoundingLengthCodec<UInt>`.
 *
 * Returns null silently for shapes the validator already names —
 * the validator's diagnostic is the user-facing surface.
 */
internal fun analyzeLengthPrefixedUseCodecListField(
    param: KSValueParameter,
    listType: KSType,
    useCodecAnn: KSAnnotation,
    ownerSimpleName: String,
): FieldAnalysis {
    val name =
        param.name?.asString() ?: return FieldAnalysis.Err(Diagnostic("field has no name", param))
    // Err here is benign: the caller (`analyzeField`) only treats an Ok
    // as a match and otherwise falls through to the payload analyzer,
    // preserving the prior nullable-fallthrough.
    val spec =
        analyzeLengthPrefixedListSpec(listType, useCodecAnn)
            ?: return FieldAnalysis.Err(
                Diagnostic("@LengthPrefixed @UseCodec field is not a supported List<@ProtocolMessage> shape", param),
            )
    return FieldAnalysis.Ok(
        FieldSpec.LengthPrefixedUseCodecList(
            name = name,
            ownerSimpleName = ownerSimpleName,
            spec = spec,
        ),
    )
}

/**
 * `@LengthPrefixed @UseCodec(C::class) val:
 * T` where `T` is a `Payload`-marked type and `C` is a Kotlin
 * `object` implementing `Codec<T>`. Scalar counterpart of
 * [analyzeLengthPrefixedUseCodecListField] / the
 * [LengthPrefixedUseCodecPayload] shape's analyzer.
 *
 * Returns null silently for shapes the validator rejects (non-Payload
 * type, codec not an object) — the validator's diagnostic is the
 * user-facing surface.
 */
internal fun analyzeLengthPrefixedUseCodecPayloadField(
    param: KSValueParameter,
    type: KSType,
    useCodecAnn: KSAnnotation,
    ownerSimpleName: String,
    prefixWidth: Int,
    prefixWireOrder: Endianness,
): FieldAnalysis {
    val name =
        param.name?.asString() ?: return FieldAnalysis.Err(Diagnostic("field has no name", param))
    // `kotlin.String` and `com.ditchoom.buffer.codec.OwnedBytesHandle`
    // ride the same shape as `T: Payload`: prefix + body bytes, codec is
    // `Codec<T>`. The `OwnedBytesHandle` admission is the framework's
    // canonical opaque-bytes carrier — the codec
    // (`OwnedBytesHandleCodec`) is allocator-aware via
    // `DecodeContext[BufferFactoryKey]` and performs the safe Pattern #2
    // copy at the wire boundary. Validator surfaces any user-facing
    // diagnostic on type/codec mismatches.
    val qname = type.declaration.qualifiedName?.asString()
    val isString = qname == "kotlin.String"
    val isOwnedBytesHandle = qname == OWNED_BYTES_HANDLE_QNAME
    if (!isString && !isOwnedBytesHandle && !type.implementsPayload()) {
        return FieldAnalysis.Err(
            Diagnostic("@LengthPrefixed @UseCodec field type must be String, OwnedBytesHandle, or a Payload", param),
        )
    }
    val payloadDecl =
        type.declaration as? KSClassDeclaration
            ?: return FieldAnalysis.Err(
                Diagnostic("@LengthPrefixed @UseCodec field type is not a class declaration", param),
            )
    val codecKsType =
        useCodecAnn.arguments
            .firstOrNull { it.name?.asString() == "codec" }
            ?.value as? KSType
            ?: return FieldAnalysis.Err(Diagnostic("@UseCodec is missing its codec argument", param))
    val codecDecl =
        codecKsType.declaration as? KSClassDeclaration
            ?: return FieldAnalysis.Err(Diagnostic("@UseCodec codec target is not a class declaration", param))
    if (codecDecl.classKind != ClassKind.OBJECT) {
        return FieldAnalysis.Err(Diagnostic("@UseCodec codec target must be a Kotlin object declaration", param))
    }
    val codecClassName = ClassName(codecDecl.packageName.asString(), codecDecl.simpleName.asString())
    return FieldAnalysis.Ok(
        FieldSpec.LengthPrefixedUseCodecPayload(
            name = name,
            ownerSimpleName = ownerSimpleName,
            payloadType = classNameOf(payloadDecl),
            payloadCodecType = codecClassName,
            prefixWidth = prefixWidth,
            prefixWireOrder = prefixWireOrder,
        ),
    )
}

/**
 * Shared element + codec validation for the
 * VBI-prefixed list shape. Returns the shape `spec` or `null` if any
 * constraint fails (caller falls through, validator surfaces the
 * focused diagnostic).
 *
 * Element must be either a `@ProtocolMessage data class`
 * OR a `@ProtocolMessage` sealed parent with `@DispatchOn` (Phase
 * Widening). Both shapes emit a singleton-object codec whose
 * `decode(buffer, context)` / `encode(buffer, value, context)`
 * signatures match the emit helpers' calls. Payload-generic elements
 * reject ( detection rule) — the emitter's static call form
 * requires a singleton-object codec. Codec must implement
 * `BoundingLengthCodec<UInt>` (validator-checked diagnostic adds
 * the focused message; this analyzer rejects silently).
 */
internal fun analyzeLengthPrefixedListSpec(
    listType: KSType,
    useCodecAnn: KSAnnotation,
): LengthPrefixedListSpec? {
    if (listType.declaration.qualifiedName?.asString() != "kotlin.collections.List") return null
    val typeArgs = listType.arguments
    if (typeArgs.size != 1) return null
    val elementType = typeArgs[0].type?.resolve() ?: return null
    if (elementType.isError || elementType.isMarkedNullable) return null
    val elementDecl = elementType.declaration as? KSClassDeclaration ?: return null
    val isDataClass = Modifier.DATA in elementDecl.modifiers
    val isSealed = Modifier.SEALED in elementDecl.modifiers
    if (!isDataClass && !isSealed) return null
    val isProtocolMessage =
        elementDecl.annotations.any { ann ->
            ann.shortName.asString() == "ProtocolMessage" &&
                ann.annotationType
                    .resolve()
                    .declaration.qualifiedName
                    ?.asString() == PROTOCOL_MESSAGE_QNAME
        }
    if (!isProtocolMessage) return null
    if (detectPayloadTypeParameter(elementDecl) != null) return null
    val codecKsType =
        useCodecAnn.arguments
            .firstOrNull { it.name?.asString() == "codec" }
            ?.value as? KSType ?: return null
    val codecDecl = codecKsType.declaration as? KSClassDeclaration ?: return null
    if (codecDecl.classKind != ClassKind.OBJECT) return null
    if (!codecDecl.implementsBoundingLengthCodec()) return null
    return LengthPrefixedListSpec(
        codecType = ClassName(codecDecl.packageName.asString(), codecDecl.simpleName.asString()),
        elementClassName = classNameOf(elementDecl),
        elementCodecClassName =
            ClassName(
                elementDecl.packageName.asString(),
                elementDecl.flattenedCodecName(),
            ),
        elementIsBackPatch = detectElementBackPatch(elementDecl),
    )
}

/**
 * Analyze-time predicate driving the
 * scratch-vs-pre-measure path selection in
 * [appendEncodeLengthPrefixedListBody]. Returns `true` when the
 * element's variant codec will produce a `WireSize.BackPatch` at
 * runtime, in which case the encode emit must use the scratch
 * buffer (the pre-measure path's `as WireSize.Exact` cast would
 * `ClassCastException`).
 *
 * Mirrors the message-wide BackPatch short-circuits in
 * [classifyVariantWireSize] / `buildWireSizeFun`. Any of these
 * annotations on a primary-constructor parameter forces BackPatch:
 *  - `@When` (`Conditional` field shape, row 19),
 *  - `@RemainingBytes` (variable-bounded body),
 *  - `@UseCodec` (user codec wireSize is opaque, conservatively
 *    BackPatch — covers `UseCodecScalar`, `RemainingBytesPayload`,
 *    `LengthPrefixedUseCodecList`),
 *  - `@LengthPrefixed val: String` (`LengthPrefixedString`, row 15).
 *
 * Sealed parents stay conservatively BackPatch — promoting to
 * Exact-when-all-variants-are-Exact is a follow-on refactor; no
 * current vector benefits.
 */
internal fun detectElementBackPatch(elementDecl: KSClassDeclaration): Boolean {
    if (Modifier.SEALED in elementDecl.modifiers) return true
    val ctor = elementDecl.primaryConstructor ?: return false
    return ctor.parameters.any { param ->
        val paramTypeQname =
            param.type
                .resolve()
                .declaration.qualifiedName
                ?.asString()
        param.annotations.any { ann ->
            when (ann.shortName.asString()) {
                "When", "RemainingBytes", "UseCodec" -> true
                "LengthPrefixed" -> paramTypeQname == "kotlin.String"
                else -> false
            }
        }
    }
}

/**
 * Does this type implement
 * `com.ditchoom.buffer.codec.Payload`? Used in `analyzeField` to
 * route `@RemainingBytes @UseCodec` on a Payload-typed field to
 * `RemainingBytesPayload` (the analyzer falls through to other
 * shapes when this returns false).
 */
internal fun KSType.implementsPayload(): Boolean {
    val decl = declaration as? KSClassDeclaration ?: return false
    if (decl.qualifiedName?.asString() == PAYLOAD_QNAME) return true
    return decl.getAllSuperTypes().any { st ->
        st.declaration.qualifiedName?.asString() == PAYLOAD_QNAME
    }
}

/**
 * Does this codec class implement
 * `com.ditchoom.buffer.codec.BoundingLengthCodec`? Used by the
 * bare-`@UseCodec` analyze path to mark scalar fields whose decoded
 * value should narrow `buffer.limit()` for subsequent decode. Walks
 * the full supertype chain because a codec author may extend
 * `BoundingLengthCodec` indirectly (intermediate interface or open
 * class).
 */
internal fun KSClassDeclaration.implementsBoundingLengthCodec(): Boolean =
    getAllSuperTypes().any { st ->
        st.declaration.qualifiedName?.asString() == BOUNDING_LENGTH_CODEC_QNAME
    }

/**
 * Does this codec class implement
 * `com.ditchoom.buffer.codec.VariableLengthCodec`? Marks a bare-`@UseCodec`
 * scalar field as self-delimiting / variable-width: decode/encode already
 * delegate to the codec, and this flag drives the variable-width
 * `peekFrameSize` branch (`total = prior + observed-width`, no value term —
 * unlike the bounding case). Walks the full supertype chain so an indirect
 * implementer (intermediate interface) is still detected.
 */
internal fun KSClassDeclaration.implementsVariableLengthCodec(): Boolean =
    getAllSuperTypes().any { st ->
        st.declaration.qualifiedName?.asString() == VARIABLE_LENGTH_CODEC_QNAME
    }

/**
 * Consumer-supplied frame-size override: when a `@ProtocolMessage` type
 * declares a companion object implementing
 * `com.ditchoom.buffer.codec.FrameDetector`, the generated codec's
 * `peekFrameSize` delegates to it instead of running the derived walker (or
 * collapsing to `NoFraming`). The escape hatch for wire shapes the walker
 * can't express — RFC 6455 WebSocket's escape-coded payload length with a
 * masking key folded between the length and the body.
 *
 * Returns the type whose companion to call (`<Type>.peekFrameSize(...)` —
 * companion members are referenced through the enclosing class name, so this
 * is the message/parent [ClassName] itself, raw of any type arguments), or
 * `null` when there is no such companion. Walks the full supertype chain of
 * the companion so an indirect `FrameDetector` (e.g. via `Codec`) still
 * counts.
 */
internal fun detectCustomFramePeek(symbol: KSClassDeclaration): ClassName? {
    val companion =
        symbol.declarations
            .filterIsInstance<KSClassDeclaration>()
            .firstOrNull { it.isCompanionObject }
            ?: return null
    val implementsFrameDetector =
        companion.getAllSuperTypes().any { st ->
            st.declaration.qualifiedName?.asString() == FRAME_DETECTOR_QNAME
        }
    return if (implementsFrameDetector) classNameOf(symbol) else null
}

/**
 * Does this parameter carry `@UseCodec(C::class)` where `C` implements
 * `VariableLengthCodec`? Used in two places to recognize a self-delimiting
 * variable-width scalar:
 *  - on a `@DispatchOn` discriminator value class's inner constructor
 *    parameter → the dispatcher is a [Discriminator.Varint];
 *  - on the inner of a variant's value-class discriminator field → that
 *    field re-reads the discriminator through the value class's own codec
 *    rather than an inline fixed-width scalar read.
 */
internal fun KSValueParameter.hasVariableLengthUseCodec(): Boolean {
    val useCodecAnn =
        annotations.firstOrNull { it.shortName.asString() == "UseCodec" } ?: return false
    val codecDecl =
        (useCodecAnn.arguments.firstOrNull { it.name?.asString() == "codec" }?.value as? KSType)
            ?.declaration as? KSClassDeclaration ?: return false
    return codecDecl.implementsVariableLengthCodec()
}

/**
 * Does this codec class implement `com.ditchoom.buffer.codec.ViewCodec`?
 * The explicit opt-in that lets a `@RemainingBytes @UseCodec(C) val: T`
 * field carry a non-`Payload` type (including `ReadBuffer`): the codec's
 * decode returns a borrowed view into the source buffer, and implementing
 * `ViewCodec` is the documented ownership contract the lockdown's
 * raw-bytes prohibition otherwise demands. Walks the full supertype chain
 * so an indirect implementer is still detected.
 */
internal fun KSClassDeclaration.implementsViewCodec(): Boolean =
    getAllSuperTypes().any { st ->
        st.declaration.qualifiedName?.asString() == VIEW_CODEC_QNAME
    }

/**
 * Does this parameter carry `@UseCodec(C::class)` where `C` implements
 * `ViewCodec`? See [implementsViewCodec]; used to route a
 * `@RemainingBytes` non-`Payload` field onto the typed-payload emit path
 * and to exempt its declared type from the raw-bytes prohibition.
 */
internal fun KSValueParameter.hasViewUseCodec(): Boolean {
    val useCodecAnn =
        annotations.firstOrNull { it.shortName.asString() == "UseCodec" } ?: return false
    val codecDecl =
        (useCodecAnn.arguments.firstOrNull { it.name?.asString() == "codec" }?.value as? KSType)
            ?.declaration as? KSClassDeclaration ?: return false
    return codecDecl.implementsViewCodec()
}

/**
 * Does this type refer to the message's
 * declared `<P : Payload>` type parameter? KSP represents type-
 * parameter references as a `KSTypeParameter` declaration with
 * the parameter's simple name; the qualified name is null. The
 * match is by simple-name comparison against the binding.
 */
internal fun KSType.matchesTypeParameter(binding: PayloadTypeParameter): Boolean {
    val decl = declaration
    if (decl !is com.google.devtools.ksp.symbol.KSTypeParameter) return false
    return decl.name.asString() == binding.typeVariableName
}

/**
 * `@When` analysis.
 *
 * Pipeline: parse the expression into a typed `WhenExpression`
 * → resolve the source against the prior siblings into a
 * `ConditionRef` → analyze the bound parameter's inner shape →
 * wrap into `FieldSpec.Conditional`. Each step returns `null` to
 * abort silently when the shape is out-of-scope; the validator in
 * `ProtocolMessageProcessor` surfaces the user-facing diagnostic.
 *
 * Will grow [analyzeConditionalInner] to recognise
 * `@LengthPrefixed val: String` inners; nothing else in this
 * function changes for that step.
 */
internal fun analyzeConditionalField(
    param: KSValueParameter,
    whenAnn: KSAnnotation,
    messageWireOrder: Endianness,
    ownerSimpleName: String,
    params: List<KSValueParameter>,
    index: Int,
): FieldAnalysis {
    if (!boundParameterIsConditionalShape(param)) {
        return FieldAnalysis.Err(
            Diagnostic("@When field must be nullable and carry only @When/@LengthPrefixed/@UseCodec", param),
        )
    }
    val name =
        param.name?.asString() ?: return FieldAnalysis.Err(Diagnostic("field has no name", param))

    val expression =
        parseWhenExpression(whenAnn)
            ?: return FieldAnalysis.Err(Diagnostic("@When predicate is malformed", param))
    val condition =
        resolveCondition(expression, params, index)
            ?: return FieldAnalysis.Err(
                Diagnostic("@When predicate does not resolve to a supported condition source", param),
            )
    val inner =
        analyzeConditionalInner(param, messageWireOrder)
            ?: return FieldAnalysis.Err(
                Diagnostic("@When field's inner shape is not supported", param),
            )

    return FieldAnalysis.Ok(
        FieldSpec.Conditional(
            name = name,
            ownerSimpleName = ownerSimpleName,
            condition = condition,
            nullableTypeName = conditionalInnerNullableTypeName(inner),
            inner = inner,
        ),
    )
}

/**
 * Bound parameter must be nullable (so absence is representable
 * when the predicate is false). Annotations beyond `@When`
 * itself are limited to `@LengthPrefixed` (.5);
 * `@WireBytes` / `@WireOrder` widen the shape and land in a
 * later slice.
 */
internal fun boundParameterIsConditionalShape(param: KSValueParameter): Boolean {
    val type = param.type.resolve()
    if (type.isError) return false
    if (!type.isMarkedNullable) return false
    return param.annotations.all {
        val n = it.shortName.asString()
        n == "When" || n == "LengthPrefixed" || n == "UseCodec"
    }
}

/**
 * Parse the `@When("…")` predicate literal into a typed shape.
 * Returns `null` for malformed annotation arguments and for paths
 * deeper than one dot — both are silently rejected by the emitter
 * and named by the validator.
 *
 * Grammar 1 (sibling-based): `"siblingField"` or
 * `"siblingField.property"`.
 *
 * Grammar 2: `"remaining <op> <int>"` where `<op>` is
 * one of `>=`, `>`, `==`. Used for cascading optional trailing fields
 * in MQTT v5 (PUBACK / PUBREC / PUBREL / PUBCOMP / UNSUBACK /
 * DISCONNECT / AUTH §3.4.2.1 et al). The identifier `remaining` is
 * magic and does not refer to a sibling — at decode time it expands
 * to `buffer.remaining()`. At encode time the predicate has no
 * meaningful left-hand side; the encoded slot is gated on
 * `value.<field> != null` instead (cascading-trailer semantics: the
 * caller signals "include this slot" by providing a non-null value).
 */
internal fun parseWhenExpression(whenAnn: KSAnnotation): WhenExpression? {
    val raw =
        whenAnn.arguments
            .firstOrNull { it.name?.asString() == "predicate" }
            ?.value as? String
            ?: return null
    val trimmed = raw.trim()
    // Grammar 2 — `remaining <op> <int>`. Tokenize on whitespace; reject
    // any malformed shape (non-`remaining` head, unsupported op, non-int
    // threshold, extra tokens).
    if (trimmed.startsWith("remaining ") || trimmed == "remaining") {
        val tokens = trimmed.split(Regex("\\s+"))
        if (tokens.size != REMAINING_COMPARISON_TOKEN_COUNT) return null
        if (tokens[0] != "remaining") return null
        val op =
            when (tokens[1]) {
                ">=" -> RemainingComparisonOp.GreaterOrEqual
                ">" -> RemainingComparisonOp.Greater
                "==" -> RemainingComparisonOp.Equal
                else -> return null
            }
        val threshold = tokens[2].toIntOrNull() ?: return null
        if (threshold < 0) return null
        return WhenExpression.RemainingCmp(op, threshold)
    }
    val parts = trimmed.split('.')
    return when (parts.size) {
        1 -> WhenExpression.Simple(parts[0])
        2 -> WhenExpression.Dotted(parts[0], parts[1])
        else -> null
    }
}

/**
 * Resolve the source expression against the prior siblings of the
 * bound parameter. Returns the `ConditionRef` when the source
 * shape matches doctrine row 19; returns `null` otherwise (the
 * validator emits the diagnostic).
 */
internal fun resolveCondition(
    expression: WhenExpression,
    params: List<KSValueParameter>,
    boundIndex: Int,
): ConditionRef? =
    when (expression) {
        is WhenExpression.RemainingCmp ->
            ConditionRef.RemainingCmp(expression.op, expression.threshold)
        is WhenExpression.Simple -> {
            val sibling = locatePriorSibling(expression.siblingName, params, boundIndex)
            if (sibling == null) null else resolveSimpleCondition(expression, sibling)
        }
        is WhenExpression.Dotted -> {
            val sibling = locatePriorSibling(expression.siblingName, params, boundIndex)
            if (sibling == null) null else resolveDottedCondition(expression, sibling)
        }
    }

internal fun locatePriorSibling(
    siblingName: String,
    params: List<KSValueParameter>,
    boundIndex: Int,
): KSValueParameter? {
    val sourceIndex = params.indexOfFirst { it.name?.asString() == siblingName }
    if (sourceIndex < 0 || sourceIndex >= boundIndex) return null
    return params[sourceIndex]
}

internal fun resolveSimpleCondition(
    expression: WhenExpression.Simple,
    sibling: KSValueParameter,
): ConditionRef? {
    val sourceType = sibling.type.resolve()
    if (sourceType.isError || sourceType.isMarkedNullable) return null
    if (sourceType.declaration.qualifiedName?.asString() != "kotlin.Boolean") return null
    return ConditionRef.Sibling(expression.siblingName)
}

internal fun resolveDottedCondition(
    expression: WhenExpression.Dotted,
    sibling: KSValueParameter,
): ConditionRef? {
    val sourceType = sibling.type.resolve()
    if (sourceType.isError || sourceType.isMarkedNullable) return null
    val siblingDecl = sourceType.declaration as? KSClassDeclaration ?: return null
    if (!siblingDecl.isValueClassDecl()) return null
    // Peek-side reconstructs the value class via its
    // primary constructor, so the value class must have exactly
    // one supported-scalar inner. Without this guard, `analyzeField`
    // would refuse to model the sibling as `ValueClassScalar`,
    // and the conditional emit + peek paths would disagree at
    // codegen time.
    val ctor = siblingDecl.primaryConstructor ?: return null
    if (ctor.parameters.size != 1) return null
    val innerType = ctor.parameters[0].type.resolve()
    if (innerType.isError || innerType.isMarkedNullable) return null
    val innerQname = innerType.declaration.qualifiedName?.asString() ?: return null
    if (SUPPORTED_SCALARS[innerQname] !in peekableValueClassInnerKinds) return null
    val property =
        siblingDecl
            .getDeclaredProperties()
            .firstOrNull { it.simpleName.asString() == expression.propertyName } ?: return null
    if (property.isMutable) return null
    if (property.extensionReceiver != null) return null
    val returnType = property.type.resolve()
    if (returnType.isMarkedNullable) return null
    if (returnType.declaration.qualifiedName?.asString() != "kotlin.Boolean") return null
    return ConditionRef.ValueClassProperty(
        siblingName = expression.siblingName,
        propertyName = expression.propertyName,
    )
}

/**
 * Analyze the bound parameter's inner shape. Slices 2/3 supported
 * any natural-width supported scalar at `Endianness.Default`;
 * Widens to `@LengthPrefixed val: String?` for the MQTT
 * v3 CONNECT optional fields. The branch is on the presence of
 * `@LengthPrefixed` on the bound parameter; further widenings
 * (`@LengthPrefixed @ProtocolMessage` body, `@WireBytes`-narrowed
 * scalars) become additional members of `ConditionalInner` and
 * additional branches here.
 */
internal fun analyzeConditionalInner(
    param: KSValueParameter,
    messageWireOrder: Endianness,
): ConditionalInner? {
    val lengthPrefixedAnn =
        param.annotations.firstOrNull { it.shortName.asString() == "LengthPrefixed" }
    val useCodecAnn =
        param.annotations.firstOrNull { it.shortName.asString() == "UseCodec" }
    val innerType = param.type.resolve().makeNotNullable()
    val qualified = innerType.declaration.qualifiedName?.asString() ?: return null
    if (lengthPrefixedAnn != null && useCodecAnn != null) {
        // `@When @LengthPrefixed @UseCodec(C) val xs:
        // List<E>?` cascading-trailer property bag for v5 acks
        // ( list shape) OR 's
        // `@When @LengthPrefixed @UseCodec(C) val: T?` where
        // T : Payload (CONNECT will-payload + password). Try the
        // list path first; fall through to the payload path.
        analyzeConditionalLengthPrefixedUseCodecListInner(
            innerType = innerType,
            useCodecAnn = useCodecAnn,
        )?.let { return it }
        return analyzeConditionalLengthPrefixedUseCodecPayloadInner(
            innerType = innerType,
            useCodecAnn = useCodecAnn,
            prefixWidth = readLengthPrefix(lengthPrefixedAnn),
            prefixWireOrder = messageWireOrder,
        )
    }
    if (useCodecAnn != null) {
        // `@When @UseCodec(C) val: T?`.
        // Pre-slice this fell through to the bare-scalar branch and
        // returned null silently for non-scalar T (sealed-parent
        // typed reason codes in the v5 PUBACK/etc. cascade). The
        // dedicated branch composes one-for-one with the non-
        // conditional `analyzeUseCodecScalarField`.
        return analyzeConditionalUseCodecInner(innerType, useCodecAnn)
    }
    if (lengthPrefixedAnn != null) {
        // Widens the inner universe to LengthPrefixed
        // String — either a bare `String?` or a `@JvmInline value class`
        // over `String` (wire-identical, wraps/unwraps at the boundary).
        // LengthPrefixed @ProtocolMessage bodies are doctrine-row-19 valid
        // but defer until a vector requires them.
        if (qualified == "kotlin.String") {
            return ConditionalInner.LengthPrefixedString(
                prefixWidth = readLengthPrefix(lengthPrefixedAnn),
                prefixWireOrder = messageWireOrder,
            )
        }
        val stringWrapper = valueClassOverStringWrapperOrNull(innerType) ?: return null
        return ConditionalInner.LengthPrefixedString(
            prefixWidth = readLengthPrefix(lengthPrefixedAnn),
            prefixWireOrder = messageWireOrder,
            valueClass = stringWrapper,
        )
    }
    // Bare `@When val: T?` where T is a
    // `@ProtocolMessage` data class or sealed parent. The codec is
    // resolved by-name (`${T.simpleName}Codec` in T's package),
    // mirroring [analyzeRemainingBytesProtocolMessageListField].
    // This sidesteps the KSP first-round chicken-and-egg: an
    // explicit `@UseCodec(<T>Codec::class)` against a yet-to-be-
    // generated codec class doesn't resolve in the round that emits
    // T's codec. Detect before the value-class branch since
    // `@ProtocolMessage` data classes / sealed parents are neither
    // VALUE-modifier nor in SUPPORTED_SCALARS, so the by-name path
    // is the only one that accepts them.
    val protocolMessageInner = analyzeConditionalProtocolMessageInner(innerType)
    if (protocolMessageInner != null) return protocolMessageInner
    // Value-class-over-scalar Conditional inner
    // (MQTT v3.1.1 PUBLISH `packetId: PacketId?`). Detect before
    // the bare-scalar branch since value classes resolve to their
    // own qualified name (not in SUPPORTED_SCALARS).
    val valueClassInner = analyzeConditionalValueClassInner(innerType)
    if (valueClassInner != null) return valueClassInner
    val kind = SUPPORTED_SCALARS[qualified] ?: return null
    return ConditionalInner.Scalar(kind = kind, wireOrder = messageWireOrder)
}

/**
 * Analyze a conditional `@LengthPrefixed @UseCodec(C)
 * val xs: List<E>?` field. Mirrors
 * [analyzeLengthPrefixedUseCodecListField] (the non-conditional
 * counterpart) for element-type requirements: data class OR sealed
 * parent (interface / class) with `@ProtocolMessage`, non-payload-
 * generic. Returns `null` for any element shape that doesn't satisfy
 * those constraints — caller falls through and the field is rejected
 * upstream.
 */
internal fun analyzeConditionalLengthPrefixedUseCodecListInner(
    innerType: KSType,
    useCodecAnn: KSAnnotation,
): ConditionalInner.LengthPrefixedUseCodecList? {
    val spec = analyzeLengthPrefixedListSpec(innerType, useCodecAnn) ?: return null
    return ConditionalInner.LengthPrefixedUseCodecList(spec = spec)
}

/**
 * Analyze a conditional
 * `@When @LengthPrefixed @UseCodec(C) val: T?` where `T : Payload`.
 * Mirrors [analyzeLengthPrefixedUseCodecPayloadField] (the non-
 * conditional counterpart) for type / codec requirements. Returns
 * `null` for any shape that doesn't satisfy those constraints —
 * caller falls through and the field is rejected upstream.
 */
internal fun analyzeConditionalLengthPrefixedUseCodecPayloadInner(
    innerType: KSType,
    useCodecAnn: KSAnnotation,
    prefixWidth: Int,
    prefixWireOrder: Endianness,
): ConditionalInner.LengthPrefixedUseCodecPayload? {
    // Mirrors `analyzeLengthPrefixedUseCodecPayloadField`: accept
    // `T: Payload` plus the framework's canonical opaque-bytes carrier
    // `com.ditchoom.buffer.codec.OwnedBytesHandle`. The latter widens
    // the `@When @LengthPrefixed @UseCodec val: T?` shape to non-Payload
    // bytes slots (e.g. mqtt CONNECT password §3.1.3.5) without forcing
    // a phantom `: Payload` wrapper.
    val qname = innerType.declaration.qualifiedName?.asString()
    val isOwnedBytesHandle = qname == OWNED_BYTES_HANDLE_QNAME
    if (!isOwnedBytesHandle && !innerType.implementsPayload()) return null
    val payloadDecl = innerType.declaration as? KSClassDeclaration ?: return null
    val codecKsType =
        useCodecAnn.arguments
            .firstOrNull { it.name?.asString() == "codec" }
            ?.value as? KSType ?: return null
    val codecDecl = codecKsType.declaration as? KSClassDeclaration ?: return null
    if (codecDecl.classKind != ClassKind.OBJECT) return null
    val codecClassName = ClassName(codecDecl.packageName.asString(), codecDecl.simpleName.asString())
    return ConditionalInner.LengthPrefixedUseCodecPayload(
        payloadType = classNameOf(payloadDecl),
        payloadCodecType = codecClassName,
        prefixWidth = prefixWidth,
        prefixWireOrder = prefixWireOrder,
    )
}

/**
 * `@When @UseCodec(C) val: T?`. Mirror of
 * the non-conditional [analyzeUseCodecScalarField]: the inner type
 * resolves to a [TypeName] via the supported-scalar table or the
 * `KSClassDeclaration` fallback (value classes, sealed parents),
 * and the codec target must be a Kotlin `object`. Returns `null`
 * for shapes the validator already names (target not an `object`,
 * non-resolvable inner) — the validator surfaces the user-facing
 * diagnostic.
 *
 * Sealed-parent inners reach this analyzer when the user writes
 * `@When("…") @UseCodec(SealedParentCodec::class) val rc:
 * SealedParent? = null`. The generated `SealedParentCodec` is a
 * singleton object implementing `Codec<SealedParent>`; the validator
 * already accepts that shape via `implementsCodecOf`.
 */
internal fun analyzeConditionalUseCodecInner(
    innerType: KSType,
    useCodecAnn: KSAnnotation,
): ConditionalInner.UseCodecScalar? {
    val codecKsType =
        useCodecAnn.arguments
            .firstOrNull { it.name?.asString() == "codec" }
            ?.value as? KSType ?: return null
    val codecDecl = codecKsType.declaration as? KSClassDeclaration ?: return null
    if (codecDecl.classKind != ClassKind.OBJECT) return null
    val fieldTypeName: TypeName =
        SUPPORTED_SCALARS[innerType.declaration.qualifiedName?.asString()]
            ?.let { scalarTypeName(it) }
            ?: (innerType.declaration as? KSClassDeclaration)?.let { classNameOf(it) }
            ?: return null
    val codecClassName = ClassName(codecDecl.packageName.asString(), codecDecl.simpleName.asString())
    return ConditionalInner.UseCodecScalar(
        fieldType = fieldTypeName,
        codecType = codecClassName,
    )
}

/**
 * `@When val: T?` where T is a
 * `@ProtocolMessage` data class or sealed parent (with
 * `@DispatchOn`). The codec class is resolved by-name
 * (`${T.simpleName}Codec` in T's package), mirroring the
 * [analyzeRemainingBytesProtocolMessageListField] convention.
 *
 * No `@UseCodec` annotation — and that's the point. An explicit
 * `@UseCodec(<T>Codec::class)` against the yet-to-be-generated codec
 * class doesn't resolve in KSP's first round (the round that emits
 * T's codec); the silent-reject from the analyzer surfaces as a
 * downstream link failure. By-name resolution is independent of
 * KSP processing order.
 *
 * Payload-generic elements reject — same rule as
 * `analyzeRemainingBytesProtocolMessageListField` and
 * `analyzeLengthPrefixedListSpec`: a `<P : Payload>` element generates
 * a generic-class codec, not a singleton object, and the per-call
 * `<T>Codec.decode(...)` form requires the singleton.
 */
internal fun analyzeConditionalProtocolMessageInner(innerType: KSType): ConditionalInner.ProtocolMessageScalar? {
    if (innerType.isError) return null
    val decl = innerType.declaration as? KSClassDeclaration ?: return null
    val isDataClass = Modifier.DATA in decl.modifiers
    val isSealed = Modifier.SEALED in decl.modifiers
    if (!isDataClass && !isSealed) return null
    val isProtocolMessage =
        decl.annotations.any { ann ->
            ann.shortName.asString() == "ProtocolMessage" &&
                ann.annotationType
                    .resolve()
                    .declaration.qualifiedName
                    ?.asString() == PROTOCOL_MESSAGE_QNAME
        }
    if (!isProtocolMessage) return null
    if (detectPayloadTypeParameter(decl) != null) return null
    return ConditionalInner.ProtocolMessageScalar(
        fieldType = classNameOf(decl),
        codecType =
            ClassName(
                decl.packageName.asString(),
                decl.flattenedCodecName(),
            ),
    )
}

/**
 * Detect a value-class-over-scalar inner for
 * `@When val: T?`. Mirrors the validity checks applied to
 * the non-conditional [analyzeValueClassScalarField] shape:
 * value class with exactly one primary-constructor parameter
 * over a supported non-nullable scalar, no `@WireBytes` /
 * `@WireOrder` on the inner property ( narrow). Returns
 * `null` for any other shape so the caller falls through to the
 * bare-scalar branch.
 */
internal fun analyzeConditionalValueClassInner(innerType: KSType): ConditionalInner.ValueClassScalar? {
    val decl = innerType.declaration as? KSClassDeclaration ?: return null
    if (!decl.isValueClassDecl()) return null
    val ctor = decl.primaryConstructor ?: return null
    if (ctor.parameters.size != 1) return null
    val innerParam = ctor.parameters[0]
    val innerName = innerParam.name?.asString() ?: return null
    val innerInnerType = innerParam.type.resolve()
    if (innerInnerType.isError || innerInnerType.isMarkedNullable) return null
    val innerQname = innerInnerType.declaration.qualifiedName?.asString() ?: return null
    val innerKind = SUPPORTED_SCALARS[innerQname] ?: return null
    if (innerParam.annotations.any { ann ->
            val n = ann.shortName.asString()
            n == "WireBytes" || n == "WireOrder"
        }
    ) {
        return null
    }
    return ConditionalInner.ValueClassScalar(
        valueClassType = classNameOf(decl),
        innerKind = innerKind,
        innerPropertyName = innerName,
        valueClassWireOrder = readMessageWireOrder(decl),
    )
}

internal fun conditionalInnerNullableTypeName(inner: ConditionalInner): TypeName =
    when (inner) {
        is ConditionalInner.Scalar -> scalarTypeName(inner.kind).copy(nullable = true)
        is ConditionalInner.LengthPrefixedString ->
            inner.valueClass?.valueClassType?.copy(nullable = true) ?: STRING_NULLABLE_TN
        is ConditionalInner.ValueClassScalar -> inner.valueClassType.copy(nullable = true)
        is ConditionalInner.LengthPrefixedUseCodecList ->
            ClassName("kotlin.collections", "List")
                .parameterizedBy(inner.elementClassName)
                .copy(nullable = true)
        is ConditionalInner.LengthPrefixedUseCodecPayload ->
            inner.payloadType.copy(nullable = true)
        is ConditionalInner.UseCodecScalar -> inner.fieldType.copy(nullable = true)
        is ConditionalInner.ProtocolMessageScalar -> inner.fieldType.copy(nullable = true)
    }

/**
 * Peek reconstructs the sibling value class by reading
 * the inner scalar bytes and calling the value class's primary
 * constructor. Only the kinds wired into `appendPeekFixedScalar`
 * are accepted; wider scalars need 's order-aware peek
 * path. Boolean is excluded because a value-class around a
 * Boolean is degenerate (the property accessor would just return
 * the wrapped value) and not load-bearing for any in-scope
 * vector.
 */
internal val peekableValueClassInnerKinds =
    setOf(ScalarKind.UByte, ScalarKind.Byte)

/**
 * Peek-side reconstruction kinds for `@DispatchOn` value-class
 * discriminators. Accepts every integer scalar kind — 1/2/4/8-byte,
 * signed and unsigned — so real-spec multi-byte discriminators
 * (e.g., HTTP/2's first 4 bytes packed as `length<<8 | type`, or a
 * QUIC-style 8-byte frame type) reconstruct correctly. The byte
 * assembly is order-aware: `appendPeekFixedScalar` reads the inner
 * scalar's bytes honoring the discriminator value class's `wireOrder`
 * and narrows to the inner kind, matching the discriminator codec's
 * decode/encode wire layout.
 *
 * Int-width kinds (Short / UShort / Int / UInt) assemble in the `Int`
 * domain; Long-width kinds (Long / ULong) assemble in the `Long`
 * domain. Float / Double are not integer discriminators and remain
 * rejected (the validator already restricts inners to
 * `NUMERIC_SCALAR_QNAMES`).
 */
internal val peekableDispatcherInnerKinds =
    setOf(
        ScalarKind.UByte,
        ScalarKind.Byte,
        ScalarKind.UShort,
        ScalarKind.Short,
        ScalarKind.UInt,
        ScalarKind.Int,
        ScalarKind.ULong,
        ScalarKind.Long,
    )

internal fun readMessageWireOrder(symbol: KSClassDeclaration): Endianness {
    val ann =
        symbol.annotations.firstOrNull { a ->
            a.shortName.asString() == "ProtocolMessage" &&
                a.annotationType
                    .resolve()
                    .declaration.qualifiedName
                    ?.asString() == PROTOCOL_MESSAGE_QNAME
        } ?: return Endianness.Default
    val arg = ann.arguments.firstOrNull { it.name?.asString() == "wireOrder" }?.value
    return parseEndianness(arg) ?: Endianness.Default
}

internal fun readFieldWireOrder(param: KSValueParameter): Endianness? {
    val ann = param.annotations.firstOrNull { it.shortName.asString() == "WireOrder" } ?: return null
    val arg = ann.arguments.firstOrNull { it.name?.asString() == "order" }?.value
    return parseEndianness(arg)
}

internal fun parseEndianness(arg: Any?): Endianness? {
    val name =
        when (arg) {
            is KSType -> arg.declaration.simpleName.asString()
            is KSClassDeclaration -> arg.simpleName.asString()
            else -> arg?.toString()?.substringAfterLast('.')
        } ?: return null
    return when (name) {
        "Default" -> Endianness.Default
        "Big" -> Endianness.Big
        "Little" -> Endianness.Little
        else -> null
    }
}

internal fun readLengthPrefix(ann: KSAnnotation): Int {
    val arg = ann.arguments.firstOrNull { it.name?.asString() == "prefix" }?.value
    val name =
        when (arg) {
            is KSType -> arg.declaration.simpleName.asString()
            is KSClassDeclaration -> arg.simpleName.asString()
            else -> arg?.toString()?.substringAfterLast('.')
        }
    return when (name) {
        "Byte" -> Byte.SIZE_BYTES
        "Short" -> Short.SIZE_BYTES
        "Int" -> Int.SIZE_BYTES
        // Default per Annotations.kt: LengthPrefix.Short.
        else -> Short.SIZE_BYTES
    }
}

internal fun readWireBytes(ann: KSAnnotation): Int {
    val arg =
        ann.arguments.firstOrNull { it.name?.asString() == "value" }?.value
            ?: ann.arguments.firstOrNull()?.value
    return (arg as? Int) ?: -1
}

/**
 * Does this field narrow `buffer.limit` mid-decode?
 * A `UseCodecScalar` does iff its codec target implements
 * `BoundingLengthCodec`. Used by [buildDecodeFun] to decide whether
 * subsequent fields run inside `try { ... } finally {
 * setLimit(__OuterLimit) }` and by the shape-construction
 * uniqueness check.
 */
internal fun FieldSpec.isBoundingShape(): Boolean =
    when (this) {
        is FieldSpec.UseCodecScalar -> isBounding
        else -> false
    }

/**
 * Analyze a `@ProtocolMessage sealed interface` parent.
 *
 * Returns [DispatchAnalysisResult.NotApplicable] (fall through to the
 * `@DispatchOn` path or stay silent) when the parent carries
 * `@DispatchOn`, has zero sealed subclasses, or hits a variant-shape
 * gap that `ProtocolMessageProcessor.validateSealedDispatcher` already
 * reports (missing `@PacketType`, missing `value`, out-of-range value,
 * duplicate value) or that the variant's own `analyze` reports
 * separately (its field shape is not analyzable). Staying silent there
 * avoids double-reporting.
 *
 * Returns [DispatchAnalysisResult.Rejected] for the one true silent
 * gap with no paired validator diagnostic: a variant that is neither a
 * `data class` nor an `object`/`data object`. The simple-dispatch
 * validator (unlike the `@DispatchOn` validator) never checks variant
 * class kind, so without this the codec is silently dropped.
 */
internal fun analyzeSealedDispatcher(symbol: KSClassDeclaration): DispatchAnalysisResult {
    if (symbol.annotations.any { it.shortName.asString() == "DispatchOn" }) {
        return DispatchAnalysisResult.NotApplicable
    }
    val subclasses = symbol.getSealedSubclasses().toList()
    if (subclasses.isEmpty()) return DispatchAnalysisResult.NotApplicable
    // Generic-payload parity with the @DispatchOn path: a sealed parent
    // declaring `<out P : Payload>` emits as `class FooCodec<P>(payloadCodec)`
    // and threads the codec to generic variants. A non-generic parent
    // stays Monomorphic and every variant uses a static-object codec ref —
    // byte-identical to the pre-generics simple dispatcher.
    val payloadTypeParameter = detectPayloadTypeParameter(symbol)
    val variants = mutableListOf<DispatchVariant>()
    val seenValues = mutableSetOf<Int>()
    for (sub in subclasses) {
        // Issue #150 — accept `data object` / `object` variants
        // (classKind == OBJECT) in addition to data-class variants.
        // Empty-fields object variants encode/decode through their
        // standalone codec, which buildDecodeFun emits as
        // `return ObjectName` and buildEncodeFun emits as a no-op.
        val isObjectVariant = sub.classKind == ClassKind.OBJECT
        // True silent gap: validateSealedDispatcher does not check the
        // variant's class kind (the @DispatchOn validator does, at
        // ProtocolMessageProcessor.kt:417), so a non-data, non-object
        // variant passes validation and is then silently dropped here.
        if (!isObjectVariant && Modifier.DATA !in sub.modifiers) {
            return DispatchAnalysisResult.Rejected(
                listOf(
                    Diagnostic(
                        "@PacketType variant ${sub.qualifiedName?.asString() ?: sub.simpleName.asString()} " +
                            "under simple sealed dispatch parent ${symbol.simpleName.asString()} must be a " +
                            "`data class` or `data object` / `object`. Non-data class variants are not supported.",
                        sub,
                    ),
                ),
            )
        }
        val packetType =
            sub.annotations.firstOrNull { it.shortName.asString() == "PacketType" }
                ?: return DispatchAnalysisResult.NotApplicable
        val rawValue =
            packetType.arguments
                .firstOrNull { it.name?.asString() == "value" }
                ?.value as? Int ?: return DispatchAnalysisResult.NotApplicable
        if (rawValue !in 0..UBYTE_MAX_VALUE) return DispatchAnalysisResult.NotApplicable
        if (!seenValues.add(rawValue)) return DispatchAnalysisResult.NotApplicable
        // The variant carries `@ProtocolMessage` and is analyzed
        // separately by `tryEmit(sub)`; if its own field shape is not
        // analyzable, that pass emits the diagnostic. Stay silent here.
        val variantShape =
            (analyze(sub) as? AnalysisResult.Supported)?.shape
                ?: return DispatchAnalysisResult.NotApplicable
        val variantWireSize = classifyVariantWireSize(variantShape)
        // A variant declaring its own `<P : Payload>` needs a
        // constructor-injected `payloadCodec`, so the dispatcher holds the
        // variant codec as a generic instance (mirrors the @DispatchOn
        // path). A generic variant under a NON-generic parent is the #176
        // type-unsafe shape, reported by validateGenericPayloadVariantShape
        // (ProtocolMessageProcessor.kt:213) — stay silent here.
        val variantSimpleName = sub.simpleName.asString()
        val genericInstanceFieldName =
            if (detectPayloadTypeParameter(sub) != null) {
                if (payloadTypeParameter == null) return DispatchAnalysisResult.NotApplicable
                "${variantSimpleName.replaceFirstChar { it.lowercase() }}Codec"
            } else {
                null
            }
        variants +=
            DispatchVariant(
                simpleName = variantSimpleName,
                className = classNameOf(sub),
                codecClassName =
                    ClassName(
                        sub.packageName.asString(),
                        sub.flattenedCodecName(),
                    ),
                dispatchValue = rawValue,
                codecRef =
                    genericInstanceFieldName
                        ?.let { VariantCodecRef.GenericInstance(it) }
                        ?: VariantCodecRef.StaticObject,
                wireSize = variantWireSize,
            )
    }
    // Sort by discriminator value so the generated `expected = "one of {...}"`
    // string and the `when` branches are deterministic, and so the dispatcher
    // table reads in the natural ascending order.
    variants.sortBy { it.dispatchValue }
    val pkg = symbol.packageName.asString()
    val parentSimpleName = symbol.simpleName.asString()
    return DispatchAnalysisResult.Supported(
        DispatchShape(
            packageName = pkg,
            parentClassName = ClassName(pkg, parentSimpleName),
            parentSimpleName = parentSimpleName,
            codecSimpleName = symbol.flattenedCodecName(),
            discriminator = Discriminator.FixedByte,
            variants = variants,
            genericity =
                payloadTypeParameter
                    ?.let { Genericity.Generic(it) }
                    ?: Genericity.Monomorphic,
            framing = Framing.Unframed,
            forwardCompat = ForwardCompat.Disabled,
            visibility = codecVisibilityModifier(symbol).toCodecVisibility(),
            customPeek = detectCustomFramePeek(symbol),
        ),
    )
}

/**
 * Analyze a `@DispatchOn`-annotated sealed
 * parent into a `DispatchShape` (ValueClass discriminator).
 *
 * Returns [DispatchAnalysisResult.NotApplicable] when the parent
 * doesn't carry `@DispatchOn` (so [tryEmit] falls back to the simple
 * `@PacketType` path), and also for every malformed-shape case that
 * `ProtocolMessageProcessor.validateDispatchOnSealed` already reports
 * (discriminator not a value class / wrong arity / nullable or
 * non-numeric inner / zero-or-multiple `@DispatchValue` / bad
 * `@DispatchValue` return type; per-variant: non-data/object class,
 * missing/out-of-range/duplicate `@PacketType`, first param not the
 * discriminator) or that the variant's own `analyze` reports
 * separately, or that `validateGenericPayloadVariantShape` reports
 * (generic variant under a non-generic parent). Staying silent there
 * avoids double-reporting.
 *
 * Returns [DispatchAnalysisResult.Rejected] for the one true silent
 * gap with no paired validator diagnostic: a discriminator whose
 * inner scalar kind is not peekable (signed multi-byte Short/Long, or
 * ULong/Int). `validateDispatchOnSealed` accepts any numeric inner, so
 * without this the codec is silently dropped.
 */
internal fun analyzeDispatchOnSealedDispatcher(symbol: KSClassDeclaration): DispatchAnalysisResult {
    val dispatchOn =
        symbol.annotations.firstOrNull { it.shortName.asString() == "DispatchOn" }
            ?: return DispatchAnalysisResult.NotApplicable
    val discriminatorType =
        dispatchOn.arguments
            .firstOrNull { it.name?.asString() == "type" }
            ?.value as? KSType ?: return DispatchAnalysisResult.NotApplicable
    val discriminatorDecl =
        discriminatorType.declaration as? KSClassDeclaration
            ?: return DispatchAnalysisResult.NotApplicable
    if (!discriminatorDecl.isValueClassDecl()) return DispatchAnalysisResult.NotApplicable
    val discriminatorCtor =
        discriminatorDecl.primaryConstructor ?: return DispatchAnalysisResult.NotApplicable
    if (discriminatorCtor.parameters.size != 1) return DispatchAnalysisResult.NotApplicable
    val innerParam = discriminatorCtor.parameters[0]
    val innerType = innerParam.type.resolve()
    if (innerType.isError || innerType.isMarkedNullable) return DispatchAnalysisResult.NotApplicable
    // Varint discriminator: the value class's inner scalar carries
    // `@UseCodec(VariableLengthCodec)`. Width is variable and recovered
    // at runtime from the value class's own generated codec, so the
    // fixed-width peekable-inner-kind machinery below does not apply.
    // The concrete `Discriminator` (Varint vs ValueClass) is built after
    // the shared dispatch-value resolution. For the non-varint case the
    // inner-kind resolution + peekable check (now widened to every integer
    // scalar kind by the multi-byte-discriminator work) live in the
    // `ValueClass` branch below.
    val varintInner = innerParam.hasVariableLengthUseCodec()

    val dispatchProp =
        discriminatorDecl
            .getDeclaredProperties()
            .singleOrNull { prop ->
                prop.annotations.any { it.shortName.asString() == "DispatchValue" }
            } ?: return DispatchAnalysisResult.NotApplicable
    if (dispatchProp.isMutable || dispatchProp.extensionReceiver != null) {
        return DispatchAnalysisResult.NotApplicable
    }
    val returnType = dispatchProp.type.resolve()
    if (returnType.isMarkedNullable) return DispatchAnalysisResult.NotApplicable
    // Slice — accept the widened set of return
    // types ({Boolean, Byte, UByte, Short, UShort, Int, UInt}) and
    // capture the kind so the dispatch emit site can pick the
    // right Int-coercion expression. The validator surfaces the
    // user-facing diagnostic for unsupported kinds; the analyzer
    // stays silent (NotApplicable) to avoid double-reporting.
    val dispatchValueKind =
        DISPATCH_VALUE_RETURN_KINDS[returnType.declaration.qualifiedName?.asString()]
            ?: return DispatchAnalysisResult.NotApplicable
    val dispatchValuePropertyName = dispatchProp.simpleName.asString()

    // Detect whether the sealed parent declares
    // `<P : Payload>` / `<out P : Payload>`. When present, the
    // dispatcher emits as a generic class binding `P` and threads a
    // constructor-injected `payloadCodec: Codec<P>` to any variant
    // that itself carries `<P: Payload>` ( shape).
    // `<Nothing>`-typed variants don't need the codec — they keep
    // static-object references.
    val payloadTypeParameter = detectPayloadTypeParameter(symbol)

    val subclasses = symbol.getSealedSubclasses().toList()
    if (subclasses.isEmpty()) return DispatchAnalysisResult.NotApplicable

    // `@ForwardCompatible` capture. The unknown-variant sink carries
    // `@UnknownVariant` (not `@PacketType`), so it is excluded from
    // the dispatch table and drives the skip+preserve emit instead.
    // Only wired alongside `@FramedBy` — the validator errors on
    // `@ForwardCompatible` without framing, and without a framing
    // length there is no payload boundary to skip.
    val forwardCompatible =
        symbol.annotations
            .firstOrNull { it.shortName.asString() == "ForwardCompatible" }
            ?.takeIf { symbol.annotations.any(::isFramedByAnn) }
            ?.let { resolveForwardCompatibleConfig(subclasses) }

    val variants = mutableListOf<DispatchVariant>()
    val seenValues = mutableSetOf<Int>()
    for (sub in subclasses) {
        // The `@UnknownVariant` sink is handled by the dispatcher's
        // skip+preserve arms, not the variant table — skip it here so
        // its missing `@PacketType` doesn't fail dispatcher analysis.
        if (sub.annotations.any { it.shortName.asString() == "UnknownVariant" }) continue
        // Issue #150 — accept `data object` / `object` variants
        // (classKind == OBJECT). Object variants don't carry the
        // discriminator field (no constructor), so the firstParam
        // discriminator-type check is skipped for them. PR #153's
        // DataObjectCodegenTest only asserts compilation success on
        // this shape; runtime dispatch semantics for an empty-bodied
        // @DispatchOn variant remain a future concern.
        val isObjectVariant = sub.classKind == ClassKind.OBJECT
        // All per-variant shape failures below are reported by
        // validateDispatchOnSealed (non-data/object :417, missing
        // @PacketType :433, missing value :446, out-of-range :453,
        // duplicate :462, first param not discriminator :482) — stay
        // silent (NotApplicable) here to avoid double-reporting.
        if (!isObjectVariant && Modifier.DATA !in sub.modifiers) return DispatchAnalysisResult.NotApplicable
        val packetType =
            sub.annotations.firstOrNull { it.shortName.asString() == "PacketType" }
                ?: return DispatchAnalysisResult.NotApplicable
        val rawValue =
            packetType.arguments
                .firstOrNull { it.name?.asString() == "value" }
                ?.value as? Int ?: return DispatchAnalysisResult.NotApplicable
        // Slice — `@PacketType.value` range is
        // per-kind now (Boolean: 0..1, Byte: -128..127, UByte:
        // 0..255, Short: -32768..32767, UShort: 0..65535, Int /
        // UInt: full Int range). Validator surfaces the user-facing
        // diagnostic on out-of-range values; analyzer stays silent.
        if (rawValue !in dispatchValuePacketTypeRange(dispatchValueKind)) {
            return DispatchAnalysisResult.NotApplicable
        }
        if (!seenValues.add(rawValue)) return DispatchAnalysisResult.NotApplicable
        if (!isObjectVariant) {
            val ctor = sub.primaryConstructor ?: return DispatchAnalysisResult.NotApplicable
            val firstParam = ctor.parameters.firstOrNull() ?: return DispatchAnalysisResult.NotApplicable
            val firstParamQname =
                firstParam.type
                    .resolve()
                    .declaration.qualifiedName
                    ?.asString()
            if (firstParamQname != discriminatorDecl.qualifiedName?.asString()) {
                return DispatchAnalysisResult.NotApplicable
            }
        }
        // Variant must analyze cleanly via the existing data-class path
        // (or the object-singleton path added by issue #150). The
        // header field, when present, is a FieldSpec.ValueClassScalar;
        // object variants resolve to an empty-fields shape. The variant
        // carries `@ProtocolMessage` and is analyzed separately by
        // `tryEmit(sub)`, which emits any field-shape diagnostic — stay
        // silent here.
        if (analyze(sub) !is AnalysisResult.Supported) return DispatchAnalysisResult.NotApplicable
        // Detect whether the variant itself is generic
        // ( shape — `<P: Payload>` type parameter on the
        // variant data class). If so, the dispatcher constructs the
        // variant codec via `VariantCodec(payloadCodec)` and stores
        // the instance under a derived field name (e.g.,
        // `dataCodec`); other variants reach the codec via
        // static-object reference unchanged.
        val variantPayloadParam = detectPayloadTypeParameter(sub)
        val variantSimpleName = sub.simpleName.asString()
        val genericInstanceFieldName =
            if (variantPayloadParam != null) {
                // Outer dispatcher must be generic to thread the
                // codec — a `<P : Payload>` variant under a
                // non-generic parent has no codec source and is a
                // shape error reported by validateGenericPayloadVariantShape
                // (ProtocolMessageProcessor.kt:402,1613). Stay silent here.
                if (payloadTypeParameter == null) return DispatchAnalysisResult.NotApplicable
                "${variantSimpleName.replaceFirstChar { it.lowercase() }}Codec"
            } else {
                null
            }
        variants +=
            DispatchVariant(
                simpleName = variantSimpleName,
                className = classNameOf(sub),
                codecClassName =
                    ClassName(
                        sub.packageName.asString(),
                        sub.flattenedCodecName(),
                    ),
                dispatchValue = rawValue,
                codecRef =
                    genericInstanceFieldName
                        ?.let { VariantCodecRef.GenericInstance(it) }
                        ?: VariantCodecRef.StaticObject,
                wireSize = VariantWireSize.Delegated,
            )
    }
    variants.sortBy { it.dispatchValue }
    val pkg = symbol.packageName.asString()
    val parentSimpleName = symbol.simpleName.asString()
    // Capture parent `@FramedBy`. The
    // dispatcher's emit path forks on this: when present, encode
    // returns `ReadBuffer` (slicing scheme owned by FramedEncoder),
    // the `Codec<Parent>` superinterface drops, peekFrameSize is
    // a single header+prefix walker rather than per-variant
    // dispatch, and `wireSize` is omitted.
    val framedBy = symbol.annotations.firstOrNull(::isFramedByAnn)?.let(::parseFramedBy)
    val discriminatorCodecClassName =
        ClassName(
            discriminatorDecl.packageName.asString(),
            discriminatorDecl.flattenedCodecName(),
        )
    val discriminator: Discriminator =
        if (varintInner) {
            // The inner property's name + scalar kind feed the
            // `@ForwardCompatible` skip+preserve emit (full-width opcode
            // capture / re-wrap). A varint inner that isn't a supported
            // scalar (or has no name) can't carry an opcode — fall back
            // to NotApplicable; the validator reports the user-facing
            // diagnostic.
            val varintInnerName =
                innerParam.name?.asString()
                    ?: return DispatchAnalysisResult.NotApplicable
            val varintInnerKind =
                SUPPORTED_SCALARS[innerType.declaration.qualifiedName?.asString()]
                    ?: return DispatchAnalysisResult.NotApplicable
            Discriminator.Varint(
                className = classNameOf(discriminatorDecl),
                codecClassName = discriminatorCodecClassName,
                dispatchValueProperty = dispatchValuePropertyName,
                dispatchValueKind = dispatchValueKind,
                innerPropertyName = varintInnerName,
                innerKind = varintInnerKind,
            )
        } else {
            val innerKind =
                SUPPORTED_SCALARS[innerType.declaration.qualifiedName?.asString()]
                    ?: return DispatchAnalysisResult.NotApplicable
            // Defensive guard: every integer scalar kind (signed/unsigned,
            // 1/2/4/8-byte) is peekable, so for any inner `validateDispatchOnSealed`
            // accepts (`NUMERIC_SCALAR_QNAMES`) this never fires. It remains as the
            // emitter's own check against a non-integer inner (Float / Double) — the
            // validator reports the user-facing error first, so this is belt-and-
            // suspenders rather than a silent-gap closer.
            if (innerKind !in peekableDispatcherInnerKinds) {
                val innerName = innerType.declaration.simpleName.asString()
                val peekable = peekableDispatcherInnerKinds.joinToString(", ") { it.name }
                return DispatchAnalysisResult.Rejected(
                    listOf(
                        Diagnostic(
                            "@DispatchOn discriminator on ${symbol.simpleName.asString()} has inner scalar " +
                                "`$innerName`, which is not a supported dispatch discriminator type. The peek " +
                                "path supports the integer scalar kinds ($peekable); non-integer inners " +
                                "(Float / Double) cannot be dispatch discriminators.",
                            symbol,
                        ),
                    ),
                )
            }
            Discriminator.ValueClass(
                className = classNameOf(discriminatorDecl),
                codecClassName = discriminatorCodecClassName,
                innerKind = innerKind,
                // Read the discriminator value class's `@ProtocolMessage(
                // wireOrder = ...)` so multi-byte byte assembly during peek
                // matches the encode/decode wire layout. Single-byte kinds
                // ignore this.
                innerWireOrder = readMessageWireOrder(discriminatorDecl),
                dispatchValueProperty = dispatchValuePropertyName,
                dispatchValueKind = dispatchValueKind,
            )
        }
    return DispatchAnalysisResult.Supported(
        DispatchShape(
            packageName = pkg,
            parentClassName = ClassName(pkg, parentSimpleName),
            parentSimpleName = parentSimpleName,
            codecSimpleName = symbol.flattenedCodecName(),
            discriminator = discriminator,
            variants = variants,
            genericity =
                payloadTypeParameter
                    ?.let { Genericity.Generic(it) }
                    ?: Genericity.Monomorphic,
            framing = framedBy?.let { Framing.Framed(it) } ?: Framing.Unframed,
            forwardCompat =
                forwardCompatible
                    ?.let { ForwardCompat.Enabled(it) }
                    ?: ForwardCompat.Disabled,
            visibility = codecVisibilityModifier(symbol).toCodecVisibility(),
            customPeek = detectCustomFramePeek(symbol),
        ),
    )
}

/**
 * Resolve the `@ForwardCompatible` unknown-variant sink among the
 * sealed parent's subclasses: the (single) subclass carrying
 * `@UnknownVariant`, with its `(Int, PlatformBuffer | ReadBuffer)`
 * constructor parameter names resolved by type. Returns null when
 * the shape is malformed (no/duplicate sink, wrong arity, missing
 * either typed parameter) — the validator surfaces the user-facing
 * diagnostic; the analyzer stays silent and simply emits the
 * non-forward-compatible (throwing) dispatcher.
 */
internal fun resolveForwardCompatibleConfig(subclasses: List<KSClassDeclaration>): ForwardCompatibleConfig? {
    val sink =
        subclasses
            .filter { sub -> sub.annotations.any { it.shortName.asString() == "UnknownVariant" } }
            .singleOrNull() ?: return null
    val ctor = sink.primaryConstructor ?: return null
    val params = ctor.parameters
    if (params.size != 2) return null
    // Legacy single-byte shape stores the opcode as `Int`; the varint
    // shapes store the discriminator's full decoded value as `Long` /
    // `ULong`. F2/F5 enforce kind-vs-discriminator compatibility; the
    // analyzer only needs the declared kind for emit-side typing.
    val opcodeParam =
        params.firstOrNull {
            it.type
                .resolve()
                .declaration.qualifiedName
                ?.asString() in FORWARD_COMPATIBLE_OPCODE_QNAMES
        } ?: return null
    val rawParam =
        params.firstOrNull {
            it.type
                .resolve()
                .declaration.qualifiedName
                ?.asString() in FORWARD_COMPATIBLE_RAW_QNAMES
        } ?: return null
    val opcodeName = opcodeParam.name?.asString() ?: return null
    val rawName = rawParam.name?.asString() ?: return null
    if (opcodeName == rawName) return null
    val opcodeKind =
        SUPPORTED_SCALARS[
            opcodeParam.type
                .resolve()
                .declaration.qualifiedName
                ?.asString(),
        ] ?: return null
    return ForwardCompatibleConfig(
        unknownClassName = classNameOf(sink),
        opcodeFieldName = opcodeName,
        rawFieldName = rawName,
        opcodeKind = opcodeKind,
    )
}

internal fun classifyVariantWireSize(shape: CodecShape): VariantWireSize {
    // Any `@When` field collapses wireSize to
    // BackPatch — including inside a sealed variant.
    if (shape.fields.any { it is FieldSpec.Conditional }) return VariantWireSize.BackPatch
    // Any `@LengthPrefixed val: String` (terminal or otherwise)
    // collapses wireSize to BackPatch per row 15. The variant codec's
    // own wireSize already produces BackPatch in this case (see
    // buildWireSizeFun); the dispatcher size table needs to know not to
    // attempt a literal sum.
    if (shape.fields.any { it is FieldSpec.LengthPrefixedString }) return VariantWireSize.BackPatch
    // Same BackPatch classification — `@RemainingBytes val: String` collapses
    // wireSize per the buildWireSizeFun early-return rule.
    if (shape.fields.any { it is FieldSpec.RemainingBytesString }) return VariantWireSize.BackPatch
    // Same BackPatch classification — variant codec's own
    // wireSize is BackPatch (see buildWireSizeFun's RemainingBytesPayload
    // early-return); the dispatcher must skip the runtime-Exact cast.
    if (shape.fields.any { it is FieldSpec.RemainingBytesPayload }) return VariantWireSize.BackPatch
    // A NON-`VariableLengthCodec` `@UseCodec` scalar may report `BackPatch` at runtime, so the
    // dispatcher can't assume Exact — collapse (mirrors buildWireSizeFun's `!isVariableLength`
    // BackPatch guard). A `VariableLengthCodec`-backed `@UseCodec` scalar reports
    // `Exact(encodedLength)` and is promoted to RuntimeExact below — the "promote later if a vector
    // benefits" note made good (a sealed grid-op union whose payloads are LEB128 `@UseCodec` fields).
    if (shape.fields.any { it is FieldSpec.UseCodecScalar && !it.isVariableLength }) return VariantWireSize.BackPatch
    // Same — buildWireSizeFun collapses
    // LengthPrefixedUseCodecList-bearing shapes to BackPatch.
    if (shape.fields.any { it is FieldSpec.LengthPrefixedUseCodecList }) return VariantWireSize.BackPatch
    // Same — buildWireSizeFun collapses
    // LengthPrefixedUseCodecPayload-bearing shapes to BackPatch.
    if (shape.fields.any { it is FieldSpec.LengthPrefixedUseCodecPayload }) return VariantWireSize.BackPatch
    // Same — buildWireSizeFun collapses bare
    // `val: T : @ProtocolMessage` shapes to BackPatch.
    if (shape.fields.any { it is FieldSpec.ProtocolMessageScalar }) return VariantWireSize.BackPatch
    // `@RemainingBytes List<E>` with sealed-parent
    // (or otherwise BackPatch-element) collapses to BackPatch — see
    // [buildWireSizeFun] for the rationale (the runtime `as Exact` cast
    // would CCE on BackPatch element variants).
    if (shape.fields.any {
            it is FieldSpec.RemainingBytesProtocolMessageList && it.elementIsBackPatch
        }
    ) {
        return VariantWireSize.BackPatch
    }
    // `@Count List<E>` with a BackPatch-shaped element collapses to
    // BackPatch for the same reason — see [buildWireSizeFun].
    if (shape.fields.any { it is FieldSpec.CountPrefixedProtocolMessageList && it.elementIsBackPatch }) {
        return VariantWireSize.BackPatch
    }
    // A `@Count List<E>` (Exact-element) is runtime-Exact: the variant
    // codec's own wireSize sums the varint count width and the element
    // wireSizes (the `as Exact` cast); the dispatcher forwards without
    // re-deriving. Early-return (like the enum case) so a non-terminal
    // `@Count` field isn't dropped by the terminal `when`'s FixedSize sum.
    if (shape.fields.any { it is FieldSpec.CountPrefixedProtocolMessageList }) {
        return VariantWireSize.RuntimeExact
    }
    // Non-terminal `@RemainingBytes*` collapses the variant
    // codec's wireSize to BackPatch (see [buildWireSizeFun] above);
    // the dispatcher's per-variant size table mirrors that.
    if (shape.fields.any {
            it is FieldSpec.RemainingBytesProtocolMessageList && it.reservedTrailingBytes != 0
        }
    ) {
        return VariantWireSize.BackPatch
    }
    // A `VariableLengthCodec`-backed `@UseCodec` scalar reports `Exact(encodedLength)` at runtime, so
    // any variant carrying one is runtime-Exact (the variant codec's own wireSize sums it via the
    // `as Exact` cast — buildWireSizeFun's `isRuntimeExactVar`); the dispatcher forwards to the
    // variant codec's wireSize without re-deriving. Early-return (like the enum case below) so a VL
    // `@UseCodec` field in a NON-terminal slot isn't dropped by the terminal `when`'s FixedSize-only
    // sum — e.g. a variant of `(varint, varint, enum, Boolean)` whose Boolean is last.
    if (shape.fields.any { it is FieldSpec.UseCodecScalar && it.isVariableLength }) return VariantWireSize.RuntimeExact
    // An enum field's ordinal is a runtime-width varint, so any variant carrying one is
    // runtime-Exact (the variant codec's own wireSize sums it via `UnsignedVarIntCodec.wireSize
    // as Exact`); the dispatcher forwards without re-deriving. Early-return so an enum in a
    // non-terminal slot isn't mis-classified by the terminal `when` below (which only sums
    // FixedSize fields and would drop the varint).
    if (shape.fields.any { it is FieldSpec.EnumScalar }) return VariantWireSize.RuntimeExact
    return when (shape.fields.lastOrNull()) {
        is FieldSpec.LengthPrefixedMessage -> VariantWireSize.RuntimeExact
        // A LengthFromString variant's body byte count is the
        // sibling value at encode time — same shape as a runtime-Exact
        // length-prefixed-message body. Dispatcher size emission walks
        // the variant codec's wireSize, which is already Exact for this
        // shape.
        is FieldSpec.LengthFromString -> VariantWireSize.RuntimeExact
        // Same Exact-via-sibling shape as LengthFromString.
        is FieldSpec.LengthFromList -> VariantWireSize.RuntimeExact
        // Same Exact-via-sibling shape as LengthFromString.
        is FieldSpec.LengthFromMessage -> VariantWireSize.RuntimeExact
        // Variant codec's own wireSize is Exact (priorBytes +
        // sum of element wireSizes via runtime `as Exact` cast); the
        // dispatcher forwards without re-deriving.
        is FieldSpec.RemainingBytesProtocolMessageList -> VariantWireSize.RuntimeExact
        // Handled by the upfront `any CountPrefixedProtocolMessageList` early-returns above
        // (BackPatch for BackPatch-element, RuntimeExact otherwise); defensive branch keeps
        // the `when` exhaustive.
        is FieldSpec.CountPrefixedProtocolMessageList -> VariantWireSize.RuntimeExact
        is FieldSpec.Scalar, is FieldSpec.ValueClassScalar, null ->
            VariantWireSize.LiteralExact(shape.fields.sumOfFixedWireBytes().requireFixed("sumOfFixedWireBytes"))
        is FieldSpec.LengthPrefixedString, is FieldSpec.Conditional -> VariantWireSize.BackPatch
        // Handled by the upfront BackPatch short-circuit; this
        // branch is unreachable because the early return collapses any
        // shape carrying a RemainingBytesPayload field before the
        // terminal-shape `when` runs.
        is FieldSpec.RemainingBytesPayload -> VariantWireSize.BackPatch
        // Both cases handled upfront — a non-VariableLengthCodec `@UseCodec` scalar by the BackPatch
        // short-circuit, a VariableLengthCodec one by the RuntimeExact early-return. This defensive
        // branch is unreachable; it keeps the `when` exhaustive (BackPatch is the conservative arm).
        is FieldSpec.UseCodecScalar -> VariantWireSize.BackPatch
        // Same — handled by the upfront BackPatch
        // short-circuit above.
        is FieldSpec.LengthPrefixedUseCodecList -> VariantWireSize.BackPatch
        // Same — handled by the upfront BackPatch
        // short-circuit above.
        is FieldSpec.LengthPrefixedUseCodecPayload -> VariantWireSize.BackPatch
        // Same — handled by the upfront BackPatch
        // short-circuit above.
        is FieldSpec.ProtocolMessageScalar -> VariantWireSize.BackPatch
        // `@RemainingBytes val: String` — handled by the upfront BackPatch
        // short-circuit above.
        is FieldSpec.RemainingBytesString -> VariantWireSize.BackPatch
        // Handled by the upfront `any EnumScalar -> RuntimeExact` early-return above; defensive
        // branch keeps the `when` exhaustive.
        is FieldSpec.EnumScalar -> VariantWireSize.RuntimeExact
    }
}

// -----------------------------------------------------------------------
// Unified dispatch decode. One
// builder subsumes BOTH the simple @PacketType decode
// (DiscriminatorOwnership.ConsumedByDispatcher) and the @DispatchOn decode
// (DiscriminatorOwnership.ReReadByVariant), byte-for-byte. The legacy
// builders' divergent behavior is recovered from the discriminator sum
// type: ownership (consume vs peek/rewind), label radix (hex vs decimal),
// local var names, receiver form (static object vs generic instance), and
// the forward-compatible else arm.
// -----------------------------------------------------------------------

internal fun isFramedByAnn(ann: KSAnnotation): Boolean =
    ann.shortName.asString() == "FramedBy" &&
        ann.annotationType
            .resolve()
            .declaration.qualifiedName
            ?.asString() == FRAMED_BY_QNAME

/**
 * Typed shape of the `@When("…")` expression literal. Only the simple-name and
 * one-level dotted forms are valid; deeper paths are a compile error and never
 * reach the analyzer.
 */
internal sealed interface WhenExpression {
    data class Simple(
        val siblingName: String,
    ) : WhenExpression

    data class Dotted(
        val siblingName: String,
        val propertyName: String,
    ) : WhenExpression

    /**
     * Grammar 2 — `"remaining <op> <int>"`. References no sibling; the resolver
     * returns a `ConditionRef.RemainingCmp` directly without walking prior parameters.
     */
    data class RemainingCmp(
        val op: RemainingComparisonOp,
        val threshold: Int,
    ) : WhenExpression
}
