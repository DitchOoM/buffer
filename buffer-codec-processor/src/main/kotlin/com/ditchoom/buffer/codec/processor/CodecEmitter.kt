package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BYTE
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.SHORT
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.U_BYTE
import com.squareup.kotlinpoet.U_INT
import com.squareup.kotlinpoet.U_LONG
import com.squareup.kotlinpoet.U_SHORT

/**
 * Emitter.
 *
 * Generates a sibling `object ${MessageName}Codec : Codec<${MessageName}>`
 * for each `@ProtocolMessage`-annotated symbol whose shape fits the
 * supported surface:
 *
 * ** — fixed-size unsigned scalar fields.** A `data class`
 *     (or `@JvmInline value class`) with one or more `UByte` /
 *     `UShort` / `UInt` / `ULong` fields, each with optional
 *     `@WireOrder` per-field overrides of the message-level wireOrder.
 * ** — `@LengthPrefixed @ProtocolMessage`-typed body.** A
 *     trailing field of `@ProtocolMessage` data class type, length-
 *     prefixed by `LengthPrefix.{Byte|Short|Int}` in the message wire
 *     order; emitter generates `setLimit` + restore decode, prefix-
 * peek `peekFrameSize`, and the lock #4 `Int.MAX_VALUE`
 *     overflow guards.
 * ** — `@WireBytes(N)` narrowing.** A scalar field whose
 *     wire width is narrower than the Kotlin type's natural size.
 *     Always uses manual byte assembly; effective byte order falls
 *     back to `Big` (network) when neither the field nor the message
 *     declares one. Encode emits an `EncodeException` runtime guard
 *     when the value exceeds the narrowed range.
 * ** — value-class wrapper at the top level.** A
 *     `@JvmInline value class` with a single inner unsigned scalar is
 *     treated as a one-field shape. The codec wraps the read scalar
 *     into the value class on decode and unwraps it via the inner
 *     property name on encode. Bit-packed logical fields exposed as
 *     getters in user code are invisible to the emitter (they
 *     introduce no wire format).
 * ** — signed scalar fields.** `Byte` / `Short` / `Int` /
 *     `Long` at their natural width and the message's default byte
 *     order. Manual byte assembly stays unsigned-only; signed scalars
 *     with `@WireBytes` or explicit `@WireOrder` are silently skipped
 *     until a vector justifies the sign-extension design.
 * ** — `@LengthPrefixed val: String` terminal.** A
 *     trailing `String` field with a `LengthPrefix.{Byte|Short|Int}`
 *     prefix in the message wire order. Encode reserves the prefix
 *     slot, writes the body via the runtime's `writeString(text,
 *     Charset.UTF8)`, measures the byte count from the position
 *     delta, and patches the prefix in place (`WireSize.BackPatch` —
 *     locked decision row 15). Encode emits an `EncodeException`
 *     runtime guard when the UTF-8 byte length exceeds the prefix's
 *     range; for 4-byte prefixes the check is skipped because Int
 *     position deltas can never exceed UInt max.
 * ** — simple sealed dispatch with `@PacketType`.** A
 *     `@ProtocolMessage sealed interface` whose direct sealed
 *     subclasses each carry `@PacketType(value)` produces a
 *     dispatcher object: `decode` reads a 1-byte discriminator and
 *     delegates to the matched variant codec, `encode` writes the
 *     discriminator then delegates, `wireSize` is per-variant (literal
 *     `Exact(1 + N)` for fixed-size variants, `BackPatch` if the
 *     variant terminal is `@LengthPrefixed val: String`, runtime
 *     `Exact(1 + variant.bytes)` if the variant terminal is a
 *     `@LengthPrefixed @ProtocolMessage` body), and `peekFrameSize`
 *     peeks the discriminator and delegates to the variant's peek
 *     with `baseOffset + 1`. Unknown discriminator at decode or peek
 * time throws `DecodeException` per. Skips
 * when the parent carries `@DispatchOn`.
 * ** — `@When` against a sibling `Boolean`.**
 *     A constructor parameter `@When("siblingField") val name: T?`
 *     where `siblingField` is a non-nullable `Boolean` parameter
 *     declared before this one. Decode emits
 *     `val name: T? = if (sibling) <readT> else null`; encode skips
 *     the slot entirely when the predicate is false (zero bytes),
 *     and throws `EncodeException` if the predicate is true and the
 * field is null. Per, any `@When`
 *     field collapses message-level `WireSize` to `BackPatch`.
 *     `peekFrameSize` walks scalar prefix fields, peeks the boolean
 *     source statically, and adds the inner field's bytes only when
 * the predicate is true. also adds `Boolean` as a 1-byte
 * scalar (no `@WireBytes` / `@WireOrder`); inner is
 *     restricted to natural-width Scalar — `@LengthPrefixed` inner
 * lands in alongside MQTT v3 CONNECT.
 * ** — dotted `@When("sibling.property")` plus
 *     value-class fields.** A constructor parameter whose type is a
 *     `value class` with a single supported-scalar primary
 *     constructor parameter is a first-class field shape: decode
 *     reads the inner scalar at natural width and constructs the
 *     value class; encode unwraps via the inner property and writes
 *     the inner scalar. The dotted-form `@When("sibling.property")`
 *     resolves the predicate as `sibling.property` against an in-scope
 *     value-class local, where `sibling` is such a value-class field
 *     declared before the bound parameter and `property` is a
 *     `Boolean`-returning `val` declared on that value class.
 *     `peekFrameSize` peeks the value class's inner-scalar bytes at
 *     the sibling's offset, reconstructs the value class, and calls
 *     the predicate property. `@WireBytes` / `@WireOrder` on the
 * outer parameter are out of scope for.
 *
 * Anything outside this surface — `@LengthFrom`, `@RemainingBytes`,
 * `@UseCodec`, `@DispatchOn`, signed scalars in the manual-byte-
 * assembly path, `@LengthPrefixed` on a non-terminal field, non-
 * terminal `@When`, `@LengthPrefixed`-inner `@When` — is
 * silently skipped here and picked up by later stages as their
 * capability lands.
 */
internal class CodecEmitter(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {
    fun tryEmit(symbol: KSClassDeclaration) {
        val sourceFile = symbol.containingFile ?: return
        if (Modifier.SEALED in symbol.modifiers && symbol.classKind == ClassKind.INTERFACE) {
            // Compute the unified dispatch shape once: try the @DispatchOn
            // dispatcher path first; if the parent doesn't carry the
            // annotation (NotApplicable), fall back to the simple @PacketType
            // dispatcher. Both analyzers produce one DispatchShape directly
            // (plan stage 8) wrapped in a DispatchAnalysisResult (plan stage 9):
            // a Rejected from the @DispatchOn path is NOT retried on the simple
            // path — it means the parent IS @DispatchOn but malformed.
            val result =
                when (val onResult = analyzeDispatchOnSealedDispatcher(symbol)) {
                    DispatchAnalysisResult.NotApplicable -> analyzeSealedDispatcher(symbol)
                    else -> onResult
                }
            when (result) {
                is DispatchAnalysisResult.Supported -> {
                    val shape = result.shape
                    val file = buildDispatchFileSpec(shape)
                    codeGenerator
                        .createNewFile(
                            Dependencies(aggregating = false, sourceFile),
                            shape.packageName,
                            shape.codecSimpleName,
                        ).bufferedWriter()
                        .use { writer -> file.writeTo(writer) }
                }
                // A recognized-but-unsupported dispatcher shape with no paired
                // validator diagnostic — emit the diagnostic(s) so the build
                // fails loudly instead of silently producing no codec.
                is DispatchAnalysisResult.Rejected ->
                    result.diagnostics.forEach { logger.error(it.message, it.node) }
                // Not a dispatcher target, or already rejected by a validator
                // diagnostic — stay silent to avoid double-reporting.
                DispatchAnalysisResult.NotApplicable -> return
            }
            return
        }
        when (val r = analyze(symbol)) {
            is AnalysisResult.Supported -> {
                val shape = r.shape
                val file = buildFileSpec(shape)
                codeGenerator
                    .createNewFile(
                        Dependencies(aggregating = false, sourceFile),
                        shape.packageName,
                        shape.codecSimpleName,
                    ).bufferedWriter()
                    .use { writer ->
                        file.writeTo(writer)
                    }
            }
            // A recognized-but-unsupported shape: emit the diagnostic(s)
            // so the build fails loudly instead of silently producing no
            // codec (the Outcome-3 bug class). These cover the §2.5/§2.6
            // silent gaps the validator does not already catch.
            is AnalysisResult.Rejected -> r.diagnostics.forEach { logger.error(it.message, it.node) }
            // Not a codec target (handled elsewhere or already rejected by
            // the validator) — stay silent to avoid double-reporting.
            AnalysisResult.NotApplicable -> return
        }
    }

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
    private fun codecVisibilityModifier(symbol: KSClassDeclaration): KModifier? {
        var decl: KSDeclaration? = symbol
        while (decl != null) {
            if (Modifier.INTERNAL in decl.modifiers) return KModifier.INTERNAL
            decl = decl.parentDeclaration
        }
        return null
    }

    /** Apply the source class's visibility (issue #175); no-op when public. */
    private fun TypeSpec.Builder.withVisibility(modifier: KModifier?): TypeSpec.Builder =
        if (modifier != null) addModifiers(modifier) else this

    /** Apply the unified [CodecVisibility]; Internal → INTERNAL, Public → no-op. */
    private fun TypeSpec.Builder.withVisibility(visibility: CodecVisibility): TypeSpec.Builder =
        when (visibility) {
            CodecVisibility.Internal -> addModifiers(KModifier.INTERNAL)
            CodecVisibility.Public -> this
        }

    private fun analyze(symbol: KSClassDeclaration): AnalysisResult {
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
        // Empty parameter list: no codifiable fields. Validator-paired
        // (SUPPORT_MATRIX §2.6 lists this as validator-silent today, but it
        // is genuinely not a message shape) — stay silent to preserve behavior.
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
    private fun detectSealedDispatchOnParentDiscriminator(symbol: KSClassDeclaration): SingletonDispatchDiscriminator? {
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
    private fun detectFramedBy(symbol: KSClassDeclaration): FramedByConfig? {
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

    private fun isFramedByAnn(ann: KSAnnotation): Boolean =
        ann.shortName.asString() == "FramedBy" &&
            ann.annotationType
                .resolve()
                .declaration.qualifiedName
                ?.asString() == FRAMED_BY_QNAME

    private fun parseFramedBy(ann: KSAnnotation): FramedByConfig? {
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
    private fun detectPayloadTypeParameter(symbol: KSClassDeclaration): PayloadTypeParameter? {
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
    private fun classNameOf(decl: KSClassDeclaration): ClassName {
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
    private fun trailingFieldsAreFixedSize(
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
    private fun reservedTrailingBytesAfter(
        fields: List<FieldSpec>,
        fromIndex: Int,
    ): Int =
        fields
            .drop(fromIndex + 1)
            .filterIsInstance<FieldSpec.FixedSize>()
            .sumOf { it.wireBytes }

    private fun analyzeField(
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
        for (ann in param.annotations) {
            when (ann.shortName.asString()) {
                "WireOrder" -> { /* allowed on scalars */ }
                "LengthPrefixed" -> lengthPrefixed = ann
                "LengthFrom" -> lengthFromAnn = ann
                "RemainingBytes" -> remainingBytesAnn = ann
                "WireBytes" -> wireBytesAnn = ann
                "UseCodec" -> useCodecAnn = ann
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
            if (useCodecAnn != null && type.implementsPayload()) {
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
            if (typeQname != "kotlin.collections.List") {
                return FieldAnalysis.Err(
                    Diagnostic(
                        "@RemainingBytes supports only String, List<@ProtocolMessage>, or @UseCodec val: Payload",
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
                else ->
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
        if (wireBytes < 1 || wireBytes > 8) {
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
    private fun analyzeValueClassScalarField(
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
    private fun analyzeLengthFromStringField(
        param: KSValueParameter,
        lengthFromAnn: KSAnnotation,
        ownerSimpleName: String,
        params: List<KSValueParameter>,
        index: Int,
    ): FieldAnalysis {
        val name =
            param.name?.asString() ?: return FieldAnalysis.Err(Diagnostic("field has no name", param))
        val type = param.type.resolve()
        if (type.isError) return FieldAnalysis.Err(Diagnostic("field type does not resolve", param))
        if (type.isMarkedNullable) {
            return FieldAnalysis.Err(Diagnostic("@LengthFrom val: String must be non-nullable", param))
        }
        if (type.declaration.qualifiedName?.asString() != "kotlin.String") {
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
    private fun analyzeLengthSource(
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

    private val peekableLengthFromSiblingKinds =
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
    private fun analyzeLengthFromListField(
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
    private fun analyzeLengthFromMessageField(
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
                Diagnostic("@LengthFrom requires the field to be String, List<@ProtocolMessage>, or @ProtocolMessage", param),
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
    private fun analyzeRemainingBytesProtocolMessageListField(
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
    private fun analyzeBareProtocolMessageField(
        param: KSValueParameter,
        type: KSType,
        ownerSimpleName: String,
    ): FieldAnalysis {
        if (type.isError || type.isMarkedNullable) {
            return FieldAnalysis.Err(Diagnostic("bare @ProtocolMessage field must be a resolvable non-nullable type", param))
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
     * `@RemainingBytes @UseCodec(C::class) val: P`
     * where `P` extends `com.ditchoom.buffer.codec.Payload` and `C` is
     * a Kotlin `object` implementing `Codec<P>`. Returns null silently
     * for shapes the validator rejects (target not an `object`, target
     * doesn't implement `Codec<P>`); the validator surfaces the
     * user-facing diagnostic.
     */
    private fun analyzeRemainingBytesPayloadField(
        param: KSValueParameter,
        type: KSType,
        useCodecAnn: KSAnnotation,
        ownerSimpleName: String,
    ): FieldAnalysis {
        val name =
            param.name?.asString() ?: return FieldAnalysis.Err(Diagnostic("field has no name", param))
        val payloadDecl =
            type.declaration as? KSClassDeclaration
                ?: return FieldAnalysis.Err(Diagnostic("@RemainingBytes @UseCodec field type is not a class declaration", param))
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
    private fun analyzeUseCodecScalarField(
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
    private fun analyzeLengthPrefixedUseCodecListField(
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
    private fun analyzeLengthPrefixedUseCodecPayloadField(
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
                ?: return FieldAnalysis.Err(Diagnostic("@LengthPrefixed @UseCodec field type is not a class declaration", param))
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
    private fun analyzeLengthPrefixedListSpec(
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
    private fun detectElementBackPatch(elementDecl: KSClassDeclaration): Boolean {
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
    private fun KSType.implementsPayload(): Boolean {
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
    private fun KSClassDeclaration.implementsBoundingLengthCodec(): Boolean =
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
    private fun KSClassDeclaration.implementsVariableLengthCodec(): Boolean =
        getAllSuperTypes().any { st ->
            st.declaration.qualifiedName?.asString() == VARIABLE_LENGTH_CODEC_QNAME
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
    private fun KSValueParameter.hasVariableLengthUseCodec(): Boolean {
        val useCodecAnn =
            annotations.firstOrNull { it.shortName.asString() == "UseCodec" } ?: return false
        val codecDecl =
            (useCodecAnn.arguments.firstOrNull { it.name?.asString() == "codec" }?.value as? KSType)
                ?.declaration as? KSClassDeclaration ?: return false
        return codecDecl.implementsVariableLengthCodec()
    }

    /**
     * Per-field-type peek-budget table.
     *
     * The framework's generic `@UseCodec` peek walker (step 6) materializes
     * a non-consuming view via `stream.peekBuffer(offset, maxBytes)` and
     * runs `codec.decode` against it. `maxBytes` is computed at emit time
     * from this table, sized to cover both 7-bit-continuation encodings
     * (MQTT var-byte-int, LEB128) and sentinel-extended encodings
     * (WebSocket extended length) without per-codec opt-in.
     *
     * Heuristic: `max(⌈typeBits / 7⌉, 1 + typeBytes)`.
     *
     * | Field type   | typeBits | typeBytes | budget |
     * |--------------|----------|-----------|--------|
     * | `Byte`/`UByte`     | 8  | 1 |  2 |
     * | `Short`/`UShort`   | 16 | 2 |  3 |
     * | `Int`/`UInt`       | 32 | 4 |  5 |
     * | `Long`/`ULong`     | 64 | 8 | 10 |
     *
     * Returns `null` for field types outside this set (value-class
     * wrappers, non-scalar types). The caller falls back to
     * `PeekResult.NoFraming` for the enclosing message — the codec is
     * out of the generic peek-walker's reach until a per-codec opt-in
     * lands. None of the current target protocols (MQTT var-byte-int,
     * LEB128, MIDI VLQ, ASN.1 BER, WebSocket extended length) need
     * a wider budget.
     */
    private fun peekBudgetFor(typeName: TypeName): Int? =
        when (typeName) {
            BYTE, U_BYTE -> 2
            SHORT, U_SHORT -> 3
            INT, U_INT -> 5
            LONG, U_LONG -> 10
            else -> null
        }

    /**
     * Does this type refer to the message's
     * declared `<P : Payload>` type parameter? KSP represents type-
     * parameter references as a `KSTypeParameter` declaration with
     * the parameter's simple name; the qualified name is null. The
     * match is by simple-name comparison against the binding.
     */
    private fun KSType.matchesTypeParameter(binding: PayloadTypeParameter): Boolean {
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
    private fun analyzeConditionalField(
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
    private fun boundParameterIsConditionalShape(param: KSValueParameter): Boolean {
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
    private fun parseWhenExpression(whenAnn: KSAnnotation): WhenExpression? {
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
            if (tokens.size != 3) return null
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
    private fun resolveCondition(
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

    private fun locatePriorSibling(
        siblingName: String,
        params: List<KSValueParameter>,
        boundIndex: Int,
    ): KSValueParameter? {
        val sourceIndex = params.indexOfFirst { it.name?.asString() == siblingName }
        if (sourceIndex < 0 || sourceIndex >= boundIndex) return null
        return params[sourceIndex]
    }

    private fun resolveSimpleCondition(
        expression: WhenExpression.Simple,
        sibling: KSValueParameter,
    ): ConditionRef? {
        val sourceType = sibling.type.resolve()
        if (sourceType.isError || sourceType.isMarkedNullable) return null
        if (sourceType.declaration.qualifiedName?.asString() != "kotlin.Boolean") return null
        return ConditionRef.Sibling(expression.siblingName)
    }

    private fun resolveDottedCondition(
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
    private fun analyzeConditionalInner(
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
            // String only. LengthPrefixed @ProtocolMessage bodies are
            // doctrine-row-19 valid but defer until a vector requires
            // them.
            if (qualified != "kotlin.String") return null
            return ConditionalInner.LengthPrefixedString(
                prefixWidth = readLengthPrefix(lengthPrefixedAnn),
                prefixWireOrder = messageWireOrder,
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
    private fun analyzeConditionalLengthPrefixedUseCodecListInner(
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
    private fun analyzeConditionalLengthPrefixedUseCodecPayloadInner(
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
    private fun analyzeConditionalUseCodecInner(
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
    private fun analyzeConditionalProtocolMessageInner(innerType: KSType): ConditionalInner.ProtocolMessageScalar? {
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
    private fun analyzeConditionalValueClassInner(innerType: KSType): ConditionalInner.ValueClassScalar? {
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

    private fun conditionalInnerNullableTypeName(inner: ConditionalInner): TypeName =
        when (inner) {
            is ConditionalInner.Scalar -> scalarTypeName(inner.kind).copy(nullable = true)
            is ConditionalInner.LengthPrefixedString -> STRING_NULLABLE_TN
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
    private val peekableValueClassInnerKinds =
        setOf(ScalarKind.UByte, ScalarKind.Byte)

    /**
     * Peek-side reconstruction kinds for `@DispatchOn` value-class
     * discriminators. Accepts 1/2/4-byte unsigned kinds for real-spec
     * multi-byte discriminators (e.g., HTTP/2's first 4 bytes packed as
     * `length<<8 | type`). `appendPeekFixedScalar` with the discriminator
     * value class's `wireOrder` handles the byte assembly.
     *
     * `ULong` and signed multi-byte kinds are still rejected — they
     * would need parallel peek paths (ULong promotion, signed
     * sign-extension), and no in-scope discriminator vector
     * requires them.
     */
    private val peekableDispatcherInnerKinds =
        setOf(ScalarKind.UByte, ScalarKind.Byte, ScalarKind.UShort, ScalarKind.UInt)

    /**
     * Typed shape of the `@When("…")` expression
     * literal. Closed by doctrine row 19: only the simple-name and
     * one-level dotted forms are valid; deeper paths are a compile
     * error and never reach the analyzer.
     */
    private sealed interface WhenExpression {
        data class Simple(
            val siblingName: String,
        ) : WhenExpression

        data class Dotted(
            val siblingName: String,
            val propertyName: String,
        ) : WhenExpression

        /**
         * Grammar 2 — `"remaining <op> <int>"`. References no
         * sibling; the resolver returns a `ConditionRef.RemainingCmp`
         * directly without walking prior parameters.
         */
        data class RemainingCmp(
            val op: RemainingComparisonOp,
            val threshold: Int,
        ) : WhenExpression
    }

    private fun scalarTypeName(kind: ScalarKind): TypeName =
        when (kind) {
            ScalarKind.Boolean -> BOOLEAN
            ScalarKind.UByte -> U_BYTE
            ScalarKind.UShort -> U_SHORT
            ScalarKind.UInt -> U_INT
            ScalarKind.ULong -> U_LONG
            ScalarKind.Byte -> BYTE
            ScalarKind.Short -> SHORT
            ScalarKind.Int -> INT
            ScalarKind.Long -> LONG
            ScalarKind.Float -> FLOAT
            ScalarKind.Double -> DOUBLE
        }

    /**
     * Read expression for a natural-width scalar. Used by
     * the conditional emit path (which needs an expression, not a statement)
     * and by the existing non-conditional decode (refactored to share).
     */
    private fun naturalScalarReadExpr(kind: ScalarKind): String =
        when (kind) {
            ScalarKind.Boolean -> "buffer.readByte() != 0.toByte()"
            ScalarKind.UByte -> "buffer.readUByte()"
            ScalarKind.UShort -> "buffer.readUShort()"
            ScalarKind.UInt -> "buffer.readUInt()"
            ScalarKind.ULong -> "buffer.readULong()"
            ScalarKind.Byte -> "buffer.readByte()"
            ScalarKind.Short -> "buffer.readShort()"
            ScalarKind.Int -> "buffer.readInt()"
            ScalarKind.Long -> "buffer.readLong()"
            ScalarKind.Float -> "buffer.readFloat()"
            ScalarKind.Double -> "buffer.readDouble()"
        }

    /**
     * Write statement for a natural-width scalar given an
     * accessor expression. Boolean encodes as `0x00` / `0x01`.
     */
    private fun naturalScalarWriteStatement(
        kind: ScalarKind,
        accessor: String,
    ): String =
        when (kind) {
            ScalarKind.Boolean -> "buffer.writeByte(if ($accessor) 1.toByte() else 0.toByte())"
            ScalarKind.UByte -> "buffer.writeUByte($accessor)"
            ScalarKind.UShort -> "buffer.writeUShort($accessor)"
            ScalarKind.UInt -> "buffer.writeUInt($accessor)"
            ScalarKind.ULong -> "buffer.writeULong($accessor)"
            ScalarKind.Byte -> "buffer.writeByte($accessor)"
            ScalarKind.Short -> "buffer.writeShort($accessor)"
            ScalarKind.Int -> "buffer.writeInt($accessor)"
            ScalarKind.Long -> "buffer.writeLong($accessor)"
            ScalarKind.Float -> "buffer.writeFloat($accessor)"
            ScalarKind.Double -> "buffer.writeDouble($accessor)"
        }

    private fun readMessageWireOrder(symbol: KSClassDeclaration): Endianness {
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

    private fun readFieldWireOrder(param: KSValueParameter): Endianness? {
        val ann = param.annotations.firstOrNull { it.shortName.asString() == "WireOrder" } ?: return null
        val arg = ann.arguments.firstOrNull { it.name?.asString() == "order" }?.value
        return parseEndianness(arg)
    }

    private fun parseEndianness(arg: Any?): Endianness? {
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

    private fun readLengthPrefix(ann: KSAnnotation): Int {
        val arg = ann.arguments.firstOrNull { it.name?.asString() == "prefix" }?.value
        val name =
            when (arg) {
                is KSType -> arg.declaration.simpleName.asString()
                is KSClassDeclaration -> arg.simpleName.asString()
                else -> arg?.toString()?.substringAfterLast('.')
            }
        return when (name) {
            "Byte" -> 1
            "Short" -> 2
            "Int" -> 4
            // Default per Annotations.kt: LengthPrefix.Short.
            else -> 2
        }
    }

    private fun readWireBytes(ann: KSAnnotation): Int {
        val arg =
            ann.arguments.firstOrNull { it.name?.asString() == "value" }?.value
                ?: ann.arguments.firstOrNull()?.value
        return (arg as? Int) ?: -1
    }

    /**
     * /14c — `@FramedBy` file spec. Emits an `object`
     * codec with the new encode signature
     * (`encode(value, context, factory): ReadBuffer`) and the strict
     * decode (`decode(buffer, context): T` with bound assertion). The
     * codec does **not** implement `Codec<T>` — its encode contract
     * differs because the framework owns framing and returns a slice
     * spanning exactly the framed wire bytes (see the handoff,
     * Q5).
     *
     * Adds the `after = "<header>"` path: the named header
     * field sits before the prefix on the wire, so decode reads it
     * first and encode threads it through `FramedEncoder.writeHeader`.
     * Decode + encode emit branches on `framedBy.afterFieldName`; the
     * peek emit reuses the bounding-codec walker shape from
     * (header bytes + observed prefix width + prefix value).
     */
    private fun buildFramedByFileSpec(
        shape: CodecShape,
        framedBy: FramedByConfig,
    ): FileSpec {
        // Reset per-file — see note on buildFileSpec.
        batchCounter = 0
        val typeSpec =
            TypeSpec
                .objectBuilder(shape.codecSimpleName)
                .withVisibility(shape.visibility)
                .addFunction(buildFramedByDecodeFun(shape, framedBy))
                .addFunction(buildFramedByEncodeFun(shape, framedBy))
                .addFunction(buildFramedByPeekFrameFun(shape, framedBy))
                .build()
        return FileSpec
            .builder(shape.packageName, shape.codecSimpleName)
            .addType(typeSpec)
            .build()
    }

    private fun buildFramedByDecodeFun(
        shape: CodecShape,
        framedBy: FramedByConfig,
        messageType: TypeName = shape.messageClassName,
    ): FunSpec {
        val afterField = framedByAfterField(shape, framedBy)
        val body = CodeBlock.builder()
        // `after = "X"` reads the header field
        // before the prefix. The local emitted by appendDecodeField is
        // named after the field, so the constructor invocation below
        // binds it positionally without any extra wiring.
        if (afterField != null) {
            appendDecodeField(body, afterField)
        }
        body.addStatement("val __framingOuterLimit = buffer.limit()")
        body.addStatement(
            "val __framingLength = %T.decode(buffer, context)",
            framedBy.codecClassName,
        )
        body.addStatement("%T.applyBound(buffer, __framingLength)", framedBy.codecClassName)
        body.addStatement("val __framingStart = buffer.position()")
        body.addStatement("val __framingBound = __framingStart + __framingLength.toInt()")
        body.beginControlFlow("return try")
        appendDecodeFields(body, shape.fields.filter { it !== afterField })
        body.beginControlFlow("if (buffer.position() != __framingBound)")
        body.addStatement(
            "throw %T(\n  fieldPath = %S,\n  bufferPosition = buffer.position(),\n" +
                "  expected = \"body to consume \" + __framingLength + \" bytes\",\n" +
                "  actual = (buffer.position() - __framingStart).toString() + \" bytes\",\n)",
            DECODE_EXCEPTION_CN,
            "${shape.ownerSimpleName}.@FramedBy",
        )
        body.endControlFlow()
        val ctorArgs = shape.fields.joinToString(", ") { "${it.name} = ${it.name}" }
        body.addStatement("%T(%L)", messageType, ctorArgs)
        body.nextControlFlow("finally")
        body.addStatement("buffer.setLimit(__framingOuterLimit)")
        body.endControlFlow()
        return FunSpec
            .builder("decode")
            .addParameter("buffer", READ_BUFFER_CN)
            .addParameter("context", DECODE_CONTEXT_CN)
            .returns(messageType)
            .addCode(body.build())
            .build()
    }

    private fun buildFramedByEncodeFun(
        shape: CodecShape,
        framedBy: FramedByConfig,
        messageType: TypeName = shape.messageClassName,
    ): FunSpec {
        val afterField = framedByAfterField(shape, framedBy)
        val headerWireWidth =
            (afterField?.let(::framedByHeaderWireWidth) ?: WireWidth.Zero)
                .requireFixed("framedByHeaderWireWidth")
        val body = CodeBlock.builder()
        body.add("return %T.encode(\n", FRAMED_ENCODER_CN)
        body.indent()
        body.add("factory = factory,\n")
        body.add("framingCodec = %T,\n", framedBy.codecClassName)
        body.add("context = context,\n")
        if (afterField != null) {
            body.add("headerWireWidth = %L,\n", headerWireWidth)
            body.add("writeHeader = { buffer ->\n")
            body.indent()
            appendEncodeField(body, afterField, shape)
            body.unindent()
            body.add("},\n")
        }
        body.unindent()
        body.beginControlFlow(") { buffer ->")
        appendEncodeFields(body, shape.fields.filter { it !== afterField }, shape)
        body.endControlFlow()
        return FunSpec
            .builder("encode")
            .addParameter("value", messageType)
            .addParameter("context", ENCODE_CONTEXT_CN)
            .addParameter("factory", BUFFER_FACTORY_CN)
            .returns(READ_BUFFER_CN)
            .addCode(body.build())
            .build()
    }

    /**
     * Emit `peekFrameSize` for an `@FramedBy`
     * codec. Mirrors [appendPeekLengthPrefixedUseCodecList]: the prefix
     * codec is `BoundingLengthCodec<UInt>` (validator-checked), so the
     * walker drives its `decode` against a non-consuming peek view at
     * `baseOffset + headerWireWidth` and computes total = headerWireWidth
     * + observed-codec-width + decodedValue.toInt().
     */
    private fun buildFramedByPeekFrameFun(
        shape: CodecShape,
        framedBy: FramedByConfig,
    ): FunSpec {
        val afterField = framedByAfterField(shape, framedBy)
        val headerWireWidth =
            (afterField?.let(::framedByHeaderWireWidth) ?: WireWidth.Zero)
                .requireFixed("framedByHeaderWireWidth")
        val builder =
            FunSpec
                .builder("peekFrameSize")
                .addParameter("stream", STREAM_PROCESSOR_CN)
                .addParameter(
                    com.squareup.kotlinpoet.ParameterSpec
                        .builder("baseOffset", INT)
                        .defaultValue("0")
                        .build(),
                ).returns(PEEK_RESULT_CN)
        val body = CodeBlock.builder()
        // Need at least the header bytes plus one prefix byte before
        // attempting the codec read. Wider VBI continuations are caught
        // by the codec's underflow → NeedsMoreData fallback below.
        body.addStatement(
            "if (stream.available() - baseOffset < %L) return %T.NeedsMoreData",
            headerWireWidth + 1,
            PEEK_RESULT_CN,
        )
        // peekBuffer needs a budget large enough for the prefix codec's
        // worst case. MqttRemainingLengthCodec is 1..4 bytes; allow 5 to
        // mirror the emit's UInt VBI peek budget.
        val peekBudget = 5
        body.addStatement(
            "val __framingPeek = stream.peekBuffer(baseOffset + %L, %L) ?: return %T.NeedsMoreData",
            headerWireWidth,
            peekBudget,
            PEEK_RESULT_CN,
        )
        body.beginControlFlow("try")
        body.addStatement("val __framingPeekStart = __framingPeek.position()")
        body.beginControlFlow("val __framingLength = try")
        body.addStatement(
            "%T.decode(__framingPeek, %T.Empty)",
            framedBy.codecClassName,
            DECODE_CONTEXT_CN,
        )
        body.nextControlFlow("catch (__e: %T)", ClassName("kotlin", "Throwable"))
        body.beginControlFlow("when (__e::class.simpleName)")
        body.addStatement(
            "%S, %S, %S -> return %T.NeedsMoreData",
            "BufferUnderflowException",
            "IndexOutOfBoundsException",
            "ArrayIndexOutOfBoundsException",
            PEEK_RESULT_CN,
        )
        body.addStatement("else -> throw __e")
        body.endControlFlow()
        body.endControlFlow()
        body.addStatement(
            "val __framingPrefixWidth = __framingPeek.position() - __framingPeekStart",
        )
        body.addStatement(
            "val __total = %L + __framingPrefixWidth + __framingLength.toInt()",
            headerWireWidth,
        )
        body.addStatement(
            "return if (stream.available() - baseOffset >= __total) %T.Complete(__total) else %T.NeedsMoreData",
            PEEK_RESULT_CN,
            PEEK_RESULT_CN,
        )
        body.nextControlFlow("finally")
        body.addStatement(
            "(__framingPeek as? %T)?.freeNativeMemory()",
            PLATFORM_BUFFER_CN,
        )
        body.endControlFlow()
        builder.addCode(body.build())
        return builder.build()
    }

    /**
     * Resolve the `@FramedByafter`-named
     * field to its [FieldSpec], or `null` when the name doesn't match
     * an analyzed field OR the field shape cannot carry an Exact wire
     * width (only Scalar / ValueClassScalar are accepted; this mirrors
     * the validator's E3 acceptance set).
     *
     * Returning `null` for a non-Exact match is a graceful fallback: the
     * validator already logged an `E3` error against the same shape, so
     * KSP will fail the compile. The emitter just needs to avoid
     * crashing while the validator's diagnostic flows through — silently
     * degrading to the `after = ""` emit shape is enough.
     */
    private fun framedByAfterField(
        shape: CodecShape,
        framedBy: FramedByConfig,
    ): FieldSpec? {
        if (framedBy.afterFieldName.isEmpty()) return null
        val field = shape.fields.firstOrNull { it.name == framedBy.afterFieldName } ?: return null
        return when (field) {
            is FieldSpec.Scalar, is FieldSpec.ValueClassScalar -> field
            else -> null
        }
    }

    /**
     * Exact wire width of the `@FramedBy`
     * `after`-named header field. Only called for fields that
     * [framedByAfterField] already filtered to Scalar / ValueClassScalar,
     * so the `else` branch is structurally unreachable.
     */
    private fun framedByHeaderWireWidth(field: FieldSpec): WireWidth =
        when (field) {
            is FieldSpec.Scalar -> field.wireWidth
            is FieldSpec.ValueClassScalar -> field.wireWidth
            else -> WireWidth.Zero
        }

    private fun buildFileSpec(shape: CodecShape): FileSpec {
        // Reset per-file so __batchN locals are stable across builds.
        // Otherwise the monotonic counter shifts when KSP processes shapes
        // in a different order between runs — the snapshot baseline would
        // drift on every unrelated edit.
        batchCounter = 0
        if (shape.framedBy != null && shape.payloadTypeParameter == null) {
            return buildFramedByFileSpec(shape, shape.framedBy)
        }
        if (shape.framedBy != null && shape.payloadTypeParameter != null) {
            // Generic variant inheriting `@FramedBy`
            // from a sealed parent. Drops the `Codec<Variant<P>>`
            // superinterface (the framed encode shape isn't a `Codec`),
            // emits framed encode/decode/peek + the `Partial<P>`
            // companion (decode-only, framing-aware via shape.framedBy).
            return FileSpec
                .builder(shape.packageName, shape.codecSimpleName)
                .addType(
                    buildGenericFramedByCodecTypeSpec(
                        shape,
                        shape.payloadTypeParameter,
                        shape.framedBy,
                    ),
                ).build()
        }
        val codecType =
            if (shape.payloadTypeParameter != null) {
                buildGenericCodecTypeSpec(shape, shape.payloadTypeParameter)
            } else {
                TypeSpec
                    .objectBuilder(shape.codecSimpleName)
                    .withVisibility(shape.visibility)
                    .addSuperinterface(CODEC_CN.parameterizedBy(shape.messageClassName))
                    .addFunction(buildDecodeFun(shape))
                    .addFunction(buildEncodeFun(shape))
                    .addFunction(buildWireSizeFun(shape))
                    .addFunction(buildPeekFrameFun(shape))
                    .also { builder ->
                        // Every codec carrying a typed payload field
                        // gets a `Partial` nested class plus a `partial(buffer,
                        // context)` decode entry. For the (object)
                        // shape, `partial` is a member of the codec object.
                        if (shouldEmitPartial(shape)) {
                            builder.addType(buildPartialClassTypeSpec(shape, payloadTypeParameter = null))
                            builder.addFunction(
                                buildPartialEntryFun(shape, payloadTypeParameter = null),
                            )
                        }
                    }.build()
            }
        return FileSpec
            .builder(shape.packageName, shape.codecSimpleName)
            .addType(codecType)
            .build()
    }

    /**
     * Emit
     * `class FooCodec<P : Payload>(private val payloadCodec: Codec<P>)
     *  : Codec<Foo<P>>`
     * for shapes whose data class declares a `<P : Payload>` type
     * parameter and a corresponding `RemainingBytesPayload` field with
     * `ConstructorInjected` source.
     */
    private fun buildGenericCodecTypeSpec(
        shape: CodecShape,
        binding: PayloadTypeParameter,
    ): TypeSpec {
        val typeVar = TypeVariableName(binding.typeVariableName, binding.bound)
        val parameterizedMessage = shape.messageClassName.parameterizedBy(typeVar)
        val codecOfP = CODEC_CN.parameterizedBy(typeVar)
        return TypeSpec
            .classBuilder(shape.codecSimpleName)
            .withVisibility(shape.visibility)
            .addTypeVariable(typeVar)
            .primaryConstructor(
                FunSpec
                    .constructorBuilder()
                    .addParameter(binding.codecParameterName, codecOfP)
                    .build(),
            ).addProperty(
                com.squareup.kotlinpoet.PropertySpec
                    .builder(binding.codecParameterName, codecOfP, KModifier.PRIVATE)
                    .initializer(binding.codecParameterName)
                    .build(),
            ).addSuperinterface(CODEC_CN.parameterizedBy(parameterizedMessage))
            .addFunction(buildDecodeFun(shape, parameterizedMessage))
            .addFunction(buildEncodeFun(shape, parameterizedMessage))
            .addFunction(buildWireSizeFun(shape, parameterizedMessage))
            .addFunction(buildPeekFrameFun(shape))
            .also { builder ->
                // For the (class) shape, `Partial` is a
                // nested class (independent type parameter <P : Payload>) and
                // `partial<P>(buffer, context)` lives on a companion object.
                // Companion-side placement matters: consumers must be able to
                // call `MqttPublishV3Codec.partial<JpegImage>(buffer, context)`
                // WITHOUT instantiating the surrounding generic codec class
                // (the whole point of 's Partial is to defer the
                // codec choice past header decode).
                if (shouldEmitPartial(shape)) {
                    builder.addType(buildPartialClassTypeSpec(shape, payloadTypeParameter = binding))
                    builder.addType(buildPartialCompanionObject(shape, payloadTypeParameter = binding))
                }
            }.build()
    }

    /**
     * Generic variant inheriting `@FramedBy`
     * from a sealed parent. Mirrors [buildGenericCodecTypeSpec]'s
     * constructor-injected payload codec field, but emits the framed
     * encode signature (`encode(value, context, factory): ReadBuffer`,
     * no wireSize) and drops the `Codec<Variant<P>>` superinterface
     * (the framed encode shape isn't a `Codec`). The
     * `Partial<P>` + companion `partial<P>(buffer, context)` still
     * emits — those are decode-only, and the partial-flow machinery
     * is framing-aware via [shape.framedBy].
     */
    private fun buildGenericFramedByCodecTypeSpec(
        shape: CodecShape,
        binding: PayloadTypeParameter,
        framedBy: FramedByConfig,
    ): TypeSpec {
        val typeVar = TypeVariableName(binding.typeVariableName, binding.bound)
        val parameterizedMessage = shape.messageClassName.parameterizedBy(typeVar)
        val codecOfP = CODEC_CN.parameterizedBy(typeVar)
        return TypeSpec
            .classBuilder(shape.codecSimpleName)
            .withVisibility(shape.visibility)
            .addTypeVariable(typeVar)
            .primaryConstructor(
                FunSpec
                    .constructorBuilder()
                    .addParameter(binding.codecParameterName, codecOfP)
                    .build(),
            ).addProperty(
                com.squareup.kotlinpoet.PropertySpec
                    .builder(binding.codecParameterName, codecOfP, KModifier.PRIVATE)
                    .initializer(binding.codecParameterName)
                    .build(),
            ).addFunction(buildFramedByDecodeFun(shape, framedBy, parameterizedMessage))
            .addFunction(buildFramedByEncodeFun(shape, framedBy, parameterizedMessage))
            .addFunction(buildFramedByPeekFrameFun(shape, framedBy))
            .also { builder ->
                if (shouldEmitPartial(shape)) {
                    builder.addType(buildPartialClassTypeSpec(shape, payloadTypeParameter = binding))
                    builder.addType(buildPartialCompanionObject(shape, payloadTypeParameter = binding))
                }
            }.build()
    }

    private fun buildDecodeFun(
        shape: CodecShape,
        messageType: TypeName = shape.messageClassName,
    ): FunSpec {
        val body = CodeBlock.builder()
        // When a bounding `@UseCodec(BoundingLengthCodec)`
        // field is present, fields BEFORE it emit normally; the codec's
        // decode + applyBound emits at its position; fields AFTER it run
        // inside `try { ... } finally { setLimit(outer) }` so the
        // buffer's outer limit is restored even on decode failure. The
        // constructor call becomes the try-block's value expression,
        // returned by the function.
        val boundingIndex = shape.fields.indexOfFirst { it.isBoundingShape() }
        // Issue #150 — `@ProtocolMessage data object` / `object` decode
        // returns the singleton instance, NOT a constructor call. Kotlin
        // references singletons by their class name directly.
        //
        // When the singleton is a sealed variant
        // under `@DispatchOn(value class)`, consume (and discard) the
        // discriminator's inner-scalar bytes first. The dispatcher's
        // peek + reset hands control here at the original buffer
        // position; data-class variants self-frame via their `id:
        // ValueClass` first field, and singleton variants must do the
        // same so the buffer position advances by the discriminator's
        // wire width before the singleton is returned.
        if (shape.isSingletonObject) {
            shape.singletonDispatchDiscriminator?.let { d ->
                body.addStatement(naturalScalarReadExpr(d.innerKind))
            }
            body.addStatement("return %T", messageType)
            return FunSpec
                .builder("decode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", READ_BUFFER_CN)
                .addParameter("context", DECODE_CONTEXT_CN)
                .returns(messageType)
                .addCode(body.build())
                .build()
        }
        val ctorArgs = shape.fields.joinToString(", ") { "${it.name} = ${it.name}" }
        if (boundingIndex < 0) {
            appendDecodeFields(body, shape.fields)
            body.addStatement("return %T(%L)", messageType, ctorArgs)
        } else {
            appendDecodeFields(body, shape.fields.subList(0, boundingIndex + 1))
            body.beginControlFlow("return try")
            appendDecodeFields(body, shape.fields.subList(boundingIndex + 1, shape.fields.size))
            body.addStatement("%T(%L)", messageType, ctorArgs)
            body.nextControlFlow("finally")
            val boundingName = shape.fields[boundingIndex].name
            body.addStatement("buffer.setLimit(__%LOuterLimit)", boundingName)
            body.endControlFlow()
        }
        return FunSpec
            .builder("decode")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("buffer", READ_BUFFER_CN)
            .addParameter("context", DECODE_CONTEXT_CN)
            .returns(messageType)
            .addCode(body.build())
            .build()
    }

    private fun appendDecodeField(
        body: CodeBlock.Builder,
        field: FieldSpec,
    ) {
        when (field) {
            is FieldSpec.Scalar -> appendDecodeScalar(body, field)
            is FieldSpec.LengthPrefixedMessage -> appendDecodeLengthPrefixed(body, field)
            is FieldSpec.LengthPrefixedString -> appendDecodeLengthPrefixedString(body, field)
            is FieldSpec.LengthFromString -> appendDecodeLengthFromString(body, field)
            is FieldSpec.LengthFromList -> appendDecodeLengthFromList(body, field)
            is FieldSpec.LengthFromMessage -> appendDecodeLengthFromMessage(body, field)
            is FieldSpec.RemainingBytesProtocolMessageList ->
                appendDecodeRemainingBytesProtocolMessageList(body, field)
            is FieldSpec.RemainingBytesPayload -> appendDecodeRemainingBytesPayload(body, field)
            is FieldSpec.RemainingBytesString -> appendDecodeRemainingBytesString(body, field)
            is FieldSpec.UseCodecScalar -> appendDecodeUseCodecScalar(body, field)
            is FieldSpec.LengthPrefixedUseCodecList -> appendDecodeLengthPrefixedUseCodecList(body, field)
            is FieldSpec.LengthPrefixedUseCodecPayload ->
                appendDecodeLengthPrefixedUseCodecPayload(body, field)
            is FieldSpec.ValueClassScalar -> appendDecodeValueClassScalar(body, field)
            is FieldSpec.Conditional -> appendDecodeConditional(body, field)
            is FieldSpec.ProtocolMessageScalar -> appendDecodeProtocolMessageScalar(body, field)
        }
    }

    private fun buildEncodeFun(
        shape: CodecShape,
        messageType: TypeName = shape.messageClassName,
    ): FunSpec {
        val body = CodeBlock.builder()
        // Singleton variant under
        // `@DispatchOn(value class)` writes the discriminator literal
        // (mirrors the data-class variant emit, where the variant's
        // `id: ValueClass = ValueClass(byte)` first field round-trips
        // the discriminator through the value-class scalar path).
        // Other singletons (standalone or under simple sealed parents)
        // emit nothing — their parent dispatcher writes the
        // discriminator before delegating.
        shape.singletonDispatchDiscriminator?.let { d ->
            body.addStatement(naturalScalarWriteStatement(d.innerKind, singletonDiscriminatorLiteralAccessor(d)))
        }
        appendEncodeFields(body, shape.fields, shape)
        return FunSpec
            .builder("encode")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("buffer", WRITE_BUFFER_CN)
            .addParameter("value", messageType)
            .addParameter("context", ENCODE_CONTEXT_CN)
            .addCode(body.build())
            .build()
    }

    /**
     * Accessor expression for a singleton
     * variant's `@PacketType.value` literal, narrowed to the
     * discriminator's inner scalar kind. Hex literals (e.g. `0x80`)
     * exceed `Int` range only for kinds wider than 4 bytes; UInt is
     * the widest kind in `peekableDispatcherInnerKinds`, so a hex
     * `Int` literal narrowed via `.toX()` always fits.
     */
    private fun singletonDiscriminatorLiteralAccessor(d: SingletonDispatchDiscriminator): String {
        val hex = "0x${d.literalValue.toString(16).uppercase()}"
        return when (d.innerKind) {
            ScalarKind.UByte -> "$hex.toUByte()"
            ScalarKind.UShort -> "$hex.toUShort()"
            ScalarKind.UInt -> "${hex}u"
            ScalarKind.Byte -> "$hex.toByte()"
            ScalarKind.Boolean, ScalarKind.Short, ScalarKind.Int, ScalarKind.Long, ScalarKind.ULong,
            ScalarKind.Float, ScalarKind.Double,
            ->
                error("singleton dispatch discriminator restricted to peekableDispatcherInnerKinds")
        }
    }

    /**
     * Single-field encode dispatch shared by
     * [buildEncodeFun], [buildFramedByEncodeFun]'s body lambda, and
     * [buildFramedByEncodeFun]'s `writeHeader` lambda. Centralizing the
     * `when` keeps the three call sites in lockstep when a new
     * [FieldSpec] member lands.
     */
    private fun appendEncodeField(
        body: CodeBlock.Builder,
        field: FieldSpec,
        shape: CodecShape,
    ) {
        when (field) {
            is FieldSpec.Scalar -> appendEncodeScalar(body, field, shape.ownerSimpleName)
            is FieldSpec.LengthPrefixedMessage -> appendEncodeLengthPrefixed(body, field)
            is FieldSpec.LengthPrefixedString -> appendEncodeLengthPrefixedString(body, field)
            is FieldSpec.LengthFromString -> appendEncodeLengthFromString(body, field)
            is FieldSpec.LengthFromList -> appendEncodeLengthFromList(body, field)
            is FieldSpec.LengthFromMessage -> appendEncodeLengthFromMessage(body, field)
            is FieldSpec.RemainingBytesProtocolMessageList ->
                appendEncodeRemainingBytesProtocolMessageList(body, field)
            is FieldSpec.RemainingBytesPayload -> appendEncodeRemainingBytesPayload(body, field)
            is FieldSpec.RemainingBytesString -> appendEncodeRemainingBytesString(body, field)
            is FieldSpec.UseCodecScalar -> appendEncodeUseCodecScalar(body, field, shape)
            is FieldSpec.LengthPrefixedUseCodecList -> appendEncodeLengthPrefixedUseCodecList(body, field)
            is FieldSpec.LengthPrefixedUseCodecPayload ->
                appendEncodeLengthPrefixedUseCodecPayload(body, field)
            is FieldSpec.ValueClassScalar -> appendEncodeValueClassScalar(body, field)
            is FieldSpec.Conditional -> appendEncodeConditional(body, field)
            is FieldSpec.ProtocolMessageScalar -> appendEncodeProtocolMessageScalar(body, field)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Batching: coalesces adjacent natural-width scalar reads/writes into
    // one wider read/write plus shift+mask extraction. A measurable
    // hot-path win on header-rich protocols (MQTT, DNS, TCP/IP, TLS) that
    // v5.0.0 regressed when the v4 BatchOptimizer was dropped during the
    // strip-and-rebuild.
    //
    // Gate (case 1 + case 2): the entire candidate group must share one
    // resolved wire order — Default, all-Big, or all-Little. Mixed orders
    // break the batch. Boolean is never batched (it has no byte order; the
    // single-scalar path handles it cleanly).
    //
    // Generated emit shape depends on the group's shared order:
    //
    // - `Default` (follow buffer.byteOrder): the wire's per-field
    //   interpretation depends on buffer.byteOrder, so emit a single outer
    //   `if (buffer.byteOrder == BIG_ENDIAN) { ... } else { ... }` branch
    //   with two arms — BE arm extracts the first field from high bits,
    //   LE arm extracts from low bits. Both arms read into the same
    //   accumulator. This matches single-scalar `Default` semantics on
    //   both buffer orders (the pre-fix `Default` batching silently
    //   field-swapped on LITTLE_ENDIAN buffers — latent since v4, unfixed
    //   in v5's worktree port).
    //
    // - `Big`: canonicalize to a big-endian accumulator (no-op when
    //   buffer.byteOrder == BIG_ENDIAN, else `swapBytes(raw)`), then
    //   extract first field from high bits. Matches single-scalar `Big`,
    //   which produces big-endian wire bytes regardless of buffer order.
    //
    // - `Little`: canonicalize to a little-endian accumulator (no-op when
    //   buffer.byteOrder == LITTLE_ENDIAN, else `swapBytes(raw)`), then
    //   extract first field from low bits. Matches single-scalar `Little`.
    private data class BatchablePart(
        val name: String,
        val sizeBytes: Int,
        val kind: ScalarKind,
        val wireOrder: Endianness,
        // null for plain Scalar; populated for ValueClassScalar so
        // decode can wrap and encode can unwrap via the inner property.
        val valueClass: ClassName?,
        val innerPropertyName: String?,
    )

    private data class BatchGroup(
        val parts: List<BatchablePart>,
        val totalBytes: Int,
        val wireOrder: Endianness,
    )

    private sealed interface BatchItem {
        data class Batched(
            val group: BatchGroup,
        ) : BatchItem

        data class Single(
            val field: FieldSpec,
        ) : BatchItem
    }

    private fun batchablePartOrNull(field: FieldSpec): BatchablePart? =
        when (field) {
            is FieldSpec.Scalar ->
                if (field.wireBytes == field.kind.width && field.kind != ScalarKind.Boolean) {
                    BatchablePart(field.name, field.wireBytes, field.kind, field.resolvedWireOrder, null, null)
                } else {
                    null
                }
            is FieldSpec.ValueClassScalar ->
                if (field.wireBytes == field.innerKind.width && field.innerKind != ScalarKind.Boolean) {
                    BatchablePart(
                        name = field.name,
                        sizeBytes = field.wireBytes,
                        kind = field.innerKind,
                        wireOrder = field.valueClassWireOrder,
                        valueClass = field.valueClassType,
                        innerPropertyName = field.innerPropertyName,
                    )
                } else {
                    null
                }
            else -> null
        }

    private fun coalesceBatches(fields: List<FieldSpec>): List<BatchItem> {
        val result = mutableListOf<BatchItem>()
        val current = mutableListOf<Pair<FieldSpec, BatchablePart>>()
        var currentBytes = 0

        fun flush() {
            while (current.size >= 2) {
                var prefixSize = 0
                var bestCount = 0
                var bestSize = 0
                for (i in current.indices) {
                    prefixSize += current[i].second.sizeBytes
                    val count = i + 1
                    if (count >= 2 && prefixSize in BATCH_ALIGNMENTS) {
                        bestCount = count
                        bestSize = prefixSize
                    }
                    if (prefixSize >= 8) break
                }
                if (bestCount >= 2) {
                    val groupParts = current.subList(0, bestCount).map { it.second }
                    result.add(
                        BatchItem.Batched(
                            BatchGroup(groupParts.toList(), bestSize, groupParts[0].wireOrder),
                        ),
                    )
                    val remaining = current.subList(bestCount, current.size).toMutableList()
                    current.clear()
                    current.addAll(remaining)
                    currentBytes = current.sumOf { it.second.sizeBytes }
                } else {
                    val removed = current.removeAt(0)
                    currentBytes -= removed.second.sizeBytes
                    result.add(BatchItem.Single(removed.first))
                }
            }
            for ((field, _) in current) result.add(BatchItem.Single(field))
            current.clear()
            currentBytes = 0
        }

        for (field in fields) {
            val part = batchablePartOrNull(field)
            if (part == null) {
                flush()
                result.add(BatchItem.Single(field))
                continue
            }
            val groupOrder = current.firstOrNull()?.second?.wireOrder
            val orderMismatch = groupOrder != null && groupOrder != part.wireOrder
            if (currentBytes + part.sizeBytes > 8 || orderMismatch) flush()
            current.add(field to part)
            currentBytes += part.sizeBytes
        }
        flush()
        return result
    }

    private fun appendDecodeFields(
        body: CodeBlock.Builder,
        fields: List<FieldSpec>,
    ) {
        for (item in coalesceBatches(fields)) {
            when (item) {
                is BatchItem.Single -> appendDecodeField(body, item.field)
                is BatchItem.Batched -> appendBatchedDecode(body, item.group)
            }
        }
    }

    private fun appendEncodeFields(
        body: CodeBlock.Builder,
        fields: List<FieldSpec>,
        shape: CodecShape,
    ) {
        for (item in coalesceBatches(fields)) {
            when (item) {
                is BatchItem.Single -> appendEncodeField(body, item.field, shape)
                is BatchItem.Batched -> appendBatchedEncode(body, item.group)
            }
        }
    }

    private fun batchReadInfo(totalBytes: Int): Triple<String, String, Int> =
        when (totalBytes) {
            2 -> Triple("readShort", "Int", 16)
            4 -> Triple("readInt", "Int", 32)
            8 -> Triple("readLong", "Long", 64)
            else -> error("unsupported batch size $totalBytes")
        }

    /**
     * Decode-side emitter. Two emit shapes depending on the group's wire
     * order:
     *
     * - `Big` or `Little`: single canonicalizing val. Generated shape:
     *   `val __batchN = if (buffer.byteOrder == ByteOrder.<WIRE>) raw else swapBytes(raw)`,
     *   followed by per-field extraction in the fixed direction the wire
     *   order dictates (first field = high bits for Big, low for Little).
     *
     * - `Default`: single outer `if (buffer.byteOrder == ...) { ... } else { ... }`
     *   branch with two arms. Both arms share the same accumulator val; each
     *   declares the field locals up-front and assigns inside the arm. JIT
     *   sees one boolean check per group, not one per field.
     */
    private fun appendBatchedDecode(
        body: CodeBlock.Builder,
        group: BatchGroup,
    ) {
        val (readMethod, accumulatorType, accumulatorBits) = batchReadInfo(group.totalBytes)
        val accumulatorVar = "__batch${++batchCounter}"
        when (group.wireOrder) {
            Endianness.Big, Endianness.Little -> {
                val canonicalOrder =
                    if (group.wireOrder == Endianness.Big) "BIG_ENDIAN" else "LITTLE_ENDIAN"
                val rawVar = "${accumulatorVar}Raw"
                if (accumulatorType == "Int" && readMethod == "readShort") {
                    body.addStatement("val %L = buffer.%L().toInt() and 0xFFFF", rawVar, readMethod)
                    body.addStatement(
                        "val %L = if (buffer.byteOrder == %T.%L) %L else %M(%L.toShort()).toInt() and 0xFFFF",
                        accumulatorVar,
                        BYTE_ORDER_CN,
                        canonicalOrder,
                        rawVar,
                        SWAP_BYTES_MN,
                        rawVar,
                    )
                } else {
                    body.addStatement("val %L = buffer.%L()", rawVar, readMethod)
                    body.addStatement(
                        "val %L = if (buffer.byteOrder == %T.%L) %L else %M(%L)",
                        accumulatorVar,
                        BYTE_ORDER_CN,
                        canonicalOrder,
                        rawVar,
                        SWAP_BYTES_MN,
                        rawVar,
                    )
                }
                val firstFieldAtHigh = group.wireOrder == Endianness.Big
                appendBatchedDecodeExtractions(
                    body,
                    group,
                    accumulatorVar,
                    accumulatorType,
                    accumulatorBits,
                    firstFieldAtHigh,
                )
            }
            Endianness.Default -> {
                if (accumulatorType == "Int" && readMethod == "readShort") {
                    body.addStatement(
                        "val %L = buffer.%L().toInt() and 0xFFFF",
                        accumulatorVar,
                        readMethod,
                    )
                } else {
                    body.addStatement("val %L = buffer.%L()", accumulatorVar, readMethod)
                }
                for (part in group.parts) {
                    val typeName = batchPartTypeRender(part)
                    body.addStatement("val %L: %L", part.name, typeName)
                }
                body.beginControlFlow("if (buffer.byteOrder == %T.BIG_ENDIAN)", BYTE_ORDER_CN)
                appendBatchedDecodeAssignments(body, group, accumulatorVar, accumulatorType, accumulatorBits, true)
                body.nextControlFlow("else")
                appendBatchedDecodeAssignments(body, group, accumulatorVar, accumulatorType, accumulatorBits, false)
                body.endControlFlow()
            }
        }
    }

    private fun appendBatchedDecodeExtractions(
        body: CodeBlock.Builder,
        group: BatchGroup,
        accumulatorVar: String,
        accumulatorType: String,
        accumulatorBits: Int,
        firstFieldAtHigh: Boolean,
    ) {
        var highBitOffset = group.totalBytes * 8
        var lowBitOffset = 0
        for (part in group.parts) {
            val fieldBits = part.sizeBytes * 8
            val bitOffset: Int
            if (firstFieldAtHigh) {
                highBitOffset -= fieldBits
                bitOffset = highBitOffset
            } else {
                bitOffset = lowBitOffset
                lowBitOffset += fieldBits
            }
            val raw = batchExtractExpr(accumulatorVar, accumulatorType, accumulatorBits, bitOffset, fieldBits)
            val casted = castFromBatchAccumulator(part.kind, accumulatorType, raw)
            if (part.valueClass != null) {
                body.addStatement("val %L = %T(%L)", part.name, part.valueClass, casted)
            } else {
                body.addStatement("val %L = %L", part.name, casted)
            }
        }
    }

    private fun appendBatchedDecodeAssignments(
        body: CodeBlock.Builder,
        group: BatchGroup,
        accumulatorVar: String,
        accumulatorType: String,
        accumulatorBits: Int,
        firstFieldAtHigh: Boolean,
    ) {
        var highBitOffset = group.totalBytes * 8
        var lowBitOffset = 0
        for (part in group.parts) {
            val fieldBits = part.sizeBytes * 8
            val bitOffset: Int
            if (firstFieldAtHigh) {
                highBitOffset -= fieldBits
                bitOffset = highBitOffset
            } else {
                bitOffset = lowBitOffset
                lowBitOffset += fieldBits
            }
            val raw = batchExtractExpr(accumulatorVar, accumulatorType, accumulatorBits, bitOffset, fieldBits)
            val casted = castFromBatchAccumulator(part.kind, accumulatorType, raw)
            if (part.valueClass != null) {
                body.addStatement("%L = %T(%L)", part.name, part.valueClass, casted)
            } else {
                body.addStatement("%L = %L", part.name, casted)
            }
        }
    }

    private fun batchPartTypeRender(part: BatchablePart): String {
        if (part.valueClass != null) {
            val pkg = part.valueClass.packageName
            return if (pkg.isEmpty()) part.valueClass.simpleName else "$pkg.${part.valueClass.simpleNames.joinToString(".")}"
        }
        return when (part.kind) {
            ScalarKind.Boolean -> "kotlin.Boolean"
            ScalarKind.UByte -> "kotlin.UByte"
            ScalarKind.Byte -> "kotlin.Byte"
            ScalarKind.UShort -> "kotlin.UShort"
            ScalarKind.Short -> "kotlin.Short"
            ScalarKind.UInt -> "kotlin.UInt"
            ScalarKind.Int -> "kotlin.Int"
            ScalarKind.ULong -> "kotlin.ULong"
            ScalarKind.Long -> "kotlin.Long"
            ScalarKind.Float -> "kotlin.Float"
            ScalarKind.Double -> "kotlin.Double"
        }
    }

    private fun batchExtractExpr(
        accumulatorVar: String,
        accumulatorType: String,
        accumulatorBits: Int,
        bitOffset: Int,
        fieldBits: Int,
    ): String {
        val mask =
            if (fieldBits >= accumulatorBits) {
                ""
            } else {
                " and " + hexMaskLiteral(fieldBits, accumulatorType)
            }
        val shift = if (bitOffset > 0) " ushr $bitOffset" else ""
        return "($accumulatorVar$shift$mask)"
    }

    private fun hexMaskLiteral(
        bits: Int,
        accumulatorType: String,
    ): String {
        val hexBytes = bits / 8
        val hex = "FF".repeat(hexBytes)
        return if (accumulatorType == "Long") "0x${hex}L" else "0x$hex"
    }

    private fun castFromBatchAccumulator(
        kind: ScalarKind,
        accumulatorType: String,
        rawExpr: String,
    ): String =
        when (kind) {
            ScalarKind.UByte -> "$rawExpr.toUByte()"
            ScalarKind.Byte -> "$rawExpr.toByte()"
            ScalarKind.UShort -> "$rawExpr.toUShort()"
            ScalarKind.Short -> "$rawExpr.toShort()"
            ScalarKind.UInt -> "$rawExpr.toUInt()"
            ScalarKind.Int -> if (accumulatorType == "Long") "$rawExpr.toInt()" else rawExpr
            ScalarKind.ULong -> "$rawExpr.toULong()"
            ScalarKind.Long -> rawExpr
            ScalarKind.Float -> if (accumulatorType == "Long") "Float.fromBits($rawExpr.toInt())" else "Float.fromBits($rawExpr)"
            ScalarKind.Double -> "Double.fromBits($rawExpr)"
            ScalarKind.Boolean -> error("Boolean is not batchable")
        }

    /**
     * Encode-side emitter. Symmetric to [appendBatchedDecode]:
     *
     * - `Big` / `Little`: build the canonical (BE / LE) accumulator from
     *   field accessors, then write with conditional `swapBytes` when the
     *   buffer's runtime order differs.
     *
     * - `Default`: branch the entire assembly+write on `buffer.byteOrder`
     *   so each arm orders bits per the buffer's natural single-scalar
     *   interpretation.
     */
    private fun appendBatchedEncode(
        body: CodeBlock.Builder,
        group: BatchGroup,
    ) {
        val (_, accumulatorType, _) = batchReadInfo(group.totalBytes)
        val writeMethod =
            when (group.totalBytes) {
                2 -> "writeShort"
                4 -> "writeInt"
                8 -> "writeLong"
                else -> error("unsupported batch size ${group.totalBytes}")
            }
        when (group.wireOrder) {
            Endianness.Big, Endianness.Little -> {
                val combined = batchEncodeCombineExpr(group, accumulatorType, group.wireOrder == Endianness.Big)
                val converted = convertBatchAccumulatorForWrite(combined, group.totalBytes)
                val canonicalOrder =
                    if (group.wireOrder == Endianness.Big) "BIG_ENDIAN" else "LITTLE_ENDIAN"
                val combinedVar = "__batch${++batchCounter}"
                body.addStatement("val %L = %L", combinedVar, converted)
                body.addStatement(
                    "buffer.%L(if (buffer.byteOrder == %T.%L) %L else %M(%L))",
                    writeMethod,
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    combinedVar,
                    SWAP_BYTES_MN,
                    combinedVar,
                )
            }
            Endianness.Default -> {
                body.beginControlFlow("if (buffer.byteOrder == %T.BIG_ENDIAN)", BYTE_ORDER_CN)
                val combinedBe = batchEncodeCombineExpr(group, accumulatorType, true)
                val convertedBe = convertBatchAccumulatorForWrite(combinedBe, group.totalBytes)
                body.addStatement("buffer.%L(%L)", writeMethod, convertedBe)
                body.nextControlFlow("else")
                val combinedLe = batchEncodeCombineExpr(group, accumulatorType, false)
                val convertedLe = convertBatchAccumulatorForWrite(combinedLe, group.totalBytes)
                body.addStatement("buffer.%L(%L)", writeMethod, convertedLe)
                body.endControlFlow()
            }
        }
    }

    private fun batchEncodeCombineExpr(
        group: BatchGroup,
        accumulatorType: String,
        firstFieldAtHigh: Boolean,
    ): String {
        var highBitOffset = group.totalBytes * 8
        var lowBitOffset = 0
        val accumulatorBits = if (accumulatorType == "Long") 64 else 32
        val terms = mutableListOf<String>()
        for (part in group.parts) {
            val fieldBits = part.sizeBytes * 8
            val bitOffset: Int
            if (firstFieldAtHigh) {
                highBitOffset -= fieldBits
                bitOffset = highBitOffset
            } else {
                bitOffset = lowBitOffset
                lowBitOffset += fieldBits
            }
            val accessor =
                if (part.valueClass != null) {
                    "value.${part.name}.${part.innerPropertyName}"
                } else {
                    "value.${part.name}"
                }
            val asAccumulator = encodeToBatchAccumulator(part.kind, accumulatorType, accessor)
            val masked =
                if (fieldBits >= accumulatorBits) {
                    asAccumulator
                } else {
                    "($asAccumulator and ${hexMaskLiteral(fieldBits, accumulatorType)})"
                }
            terms.add(if (bitOffset > 0) "($masked shl $bitOffset)" else masked)
        }
        return terms.joinToString(" or ")
    }

    private fun convertBatchAccumulatorForWrite(
        combined: String,
        totalBytes: Int,
    ): String =
        when (totalBytes) {
            // size-2 batches use an Int accumulator (so shift/or arithmetic
            // stays in Int) but writeShort takes Short — narrow at the call.
            2 -> "($combined).toShort()"
            // size-4 / size-8 accumulators are already Int / Long, so emit
            // bare expressions. Wrapping in .toInt()/.toLong() triggers the
            // "Redundant call of conversion method" compiler warning.
            4 -> combined
            8 -> combined
            else -> error("unsupported batch size $totalBytes")
        }

    private fun encodeToBatchAccumulator(
        kind: ScalarKind,
        accumulatorType: String,
        accessor: String,
    ): String {
        val intLike = accumulatorType == "Int"
        return when (kind) {
            ScalarKind.UByte -> if (intLike) "$accessor.toInt()" else "$accessor.toLong()"
            ScalarKind.Byte -> if (intLike) "$accessor.toInt()" else "$accessor.toLong()"
            ScalarKind.UShort -> if (intLike) "$accessor.toInt()" else "$accessor.toLong()"
            ScalarKind.Short -> if (intLike) "$accessor.toInt()" else "$accessor.toLong()"
            ScalarKind.UInt -> if (intLike) "$accessor.toInt()" else "$accessor.toLong()"
            ScalarKind.Int -> if (intLike) accessor else "$accessor.toLong()"
            ScalarKind.ULong -> "$accessor.toLong()"
            ScalarKind.Long -> accessor
            ScalarKind.Float -> if (intLike) "$accessor.toRawBits()" else "$accessor.toRawBits().toLong()"
            ScalarKind.Double -> "$accessor.toRawBits()"
            ScalarKind.Boolean -> error("Boolean is not batchable")
        }
    }

    private var batchCounter = 0

    /**
     * Gate for emitting the `Partial` decode pattern. Partial is emitted
     * only when [FieldSpec.RemainingBytesPayload] is the *last* field of
     * the shape. The Partial flow is the streaming-style "decode the
     * header now, defer the payload" contract — meaningful only when the
     * payload is genuinely trailing (e.g. `MqttPacket.Publish<P>`, v3/v5
     * SUBSCRIBE/UNSUBSCRIBE bodies).
     *
     * When a shape also carries a bounding `@UseCodec(BoundingLengthCodec)`
     * field (the MQTT v3 PUBLISH §3.3 wire shape), the `Partial` captures
     * the outer buffer limit at partial-decode time (the same local that
     * `appendDecodeUseCodecScalar` emits as `__<fieldName>OuterLimit`);
     * `complete()` runs the payload decode inside the bounding-narrowed
     * limit (correct for payload bounding) and restores the outer limit
     * via `try/finally` (correct for caller cleanup). Both correctness
     * concerns are handled by capturing the outer limit on the Partial
     * and restoring on completion — no consumer-visible API change versus
     * the unbounded path.
     *
     * Non-terminal payload (issue #168): when [FieldSpec.RemainingBytesPayload]
     * is followed only by fixed-size trailers (e.g. a `checksum: UShort` after
     * `@RemainingBytes val payload: P`), Partial still applies — `partial(...)`
     * reads the header *and* the trailer eagerly (the payload's wire extent is
     * `limit - reservedTrailingBytes`, computable without decoding it), and
     * `complete(...)` re-seeks to the payload region and decodes it. This is
     * gated to the unframed / unbounded case: combining a non-terminal payload
     * with `@FramedBy` or a bounding `@UseCodec` stays terminal-only (the
     * payload-region math would have to compose with the external bound, out of
     * scope here), so those shapes fall back to normal decode/encode.
     */
    private fun shouldEmitPartial(shape: CodecShape): Boolean {
        val payloadIndex = shape.fields.indexOfFirst { it is FieldSpec.RemainingBytesPayload }
        if (payloadIndex < 0) return false
        val trailing = shape.fields.subList(payloadIndex + 1, shape.fields.size)
        // Terminal payload — the original streaming-defer shape.
        if (trailing.isEmpty()) return true
        // Non-terminal payload: only when the trailer is fixed-size (so the
        // payload's end is computable without decoding) and the payload isn't
        // also externally bounded.
        if (!trailing.all { it is FieldSpec.FixedSize }) return false
        if (shape.framedBy != null) return false
        if (shape.fields.any { it.isBoundingShape() }) return false
        return true
    }

    /** Index of the (single) `RemainingBytesPayload` field, or -1. */
    private fun payloadFieldIndex(shape: CodecShape): Int = shape.fields.indexOfFirst { it is FieldSpec.RemainingBytesPayload }

    /**
     * Emit the nested `Partial` class. The `Partial`
     * captures the buffer and context so `complete(...)` can defer the
     * payload decode, and exposes the header fields as `val`s for
     * pre-payload inspection (the topic-keyed dispatch case from
     * `:buffer-flow` acceptance #4).
     *
     * The constructor is `internal` because consumers always reach
     * `Partial` through the `partial(...)` entry function — there's no
     * legitimate reason for foreign-module construction. Generated
     * codecs and consumer code live in the same module, so `internal`
     * keeps the API surface tight without restricting reachability for
     * the codec's own emit.
     *
     * Type-parameter shape:
     * (concrete payload): no type parameter on `Partial`.
     *     `complete(): MessageType` uses the `@UseCodec`-pinned codec.
     * (generic payload `<P: Payload>`): `Partial<P:
     *     Payload>` carries its own type variable (independent of the
     *     surrounding generic codec class — the whole point of slice
     *     10b's Partial is that the payload codec is supplied at
     *     `complete(...)` time, not at codec instantiation).
     *     `complete(payloadCodec: Decoder<P>): MessageType<P>` takes
     *     the codec as a parameter; `Decoder<P>` (not a separate
     *     `PayloadDecoder<P>` SAM) — the contract is identical and a
     *     parallel SAM would be noise.
     */
    private fun buildPartialClassTypeSpec(
        shape: CodecShape,
        payloadTypeParameter: PayloadTypeParameter?,
    ): TypeSpec {
        val payloadIndex = payloadFieldIndex(shape)
        val payloadField =
            (shape.fields.getOrNull(payloadIndex) as? FieldSpec.RemainingBytesPayload)
                ?: error("buildPartialClassTypeSpec called for shape without a RemainingBytesPayload")
        // Fields before the payload (decoded eagerly in `partial`) and the
        // fixed-size trailer after it (also decoded eagerly — non-terminal
        // payload, issue #168). For the terminal shape `afterFields` is empty
        // and the behavior is unchanged.
        val beforeFields = shape.fields.subList(0, payloadIndex)
        val afterFields = shape.fields.subList(payloadIndex + 1, shape.fields.size)
        val nonTerminal = afterFields.isNotEmpty()
        val exposedFields = beforeFields + afterFields
        // When the headers contain a bounding `@UseCodec` field (codec
        // implements `BoundingLengthCodec`), the partial decode mid-walk
        // narrows `buffer.limit()` and stashes the prior limit in
        // `__<fieldName>OuterLimit`. Capture that local on the Partial so
        // `complete()` can restore it. `@FramedBy` inherited from the
        // sealed parent supplies the bound externally (no in-shape field),
        // so we treat it as effectively-bounding for Partial purposes.
        // (Non-terminal shapes are gated out of both by `shouldEmitPartial`.)
        val hasBoundingField = shape.fields.any { it.isBoundingShape() } || shape.framedBy != null

        val classBuilder = TypeSpec.classBuilder("Partial")
        val typeVar =
            payloadTypeParameter?.let {
                TypeVariableName(it.typeVariableName, it.bound)
            }
        if (typeVar != null) classBuilder.addTypeVariable(typeVar)

        val ctorBuilder = FunSpec.constructorBuilder().addModifiers(KModifier.INTERNAL)
        for (field in exposedFields) {
            val typeName = partialFieldTypeName(field)
            ctorBuilder.addParameter(field.name, typeName)
            classBuilder.addProperty(
                com.squareup.kotlinpoet.PropertySpec
                    .builder(field.name, typeName)
                    .initializer(field.name)
                    .build(),
            )
        }
        if (hasBoundingField) {
            ctorBuilder.addParameter("outerLimit", INT)
        }
        // Non-terminal payload: capture the payload's wire region so
        // `complete()` can re-seek to it (the trailer was already consumed
        // in `partial`, so the buffer position no longer sits at the payload).
        if (nonTerminal) {
            ctorBuilder.addParameter("payloadStart", INT)
            ctorBuilder.addParameter("payloadEnd", INT)
        }
        ctorBuilder.addParameter("buffer", READ_BUFFER_CN)
        ctorBuilder.addParameter("context", DECODE_CONTEXT_CN)
        classBuilder.primaryConstructor(ctorBuilder.build())
        // Buffer + context (and the captured outerLimit, if any) are
        // private state used by complete(); no public getter — the
        // consumer should never re-read the buffer or fiddle with the
        // limit through the Partial.
        if (hasBoundingField) {
            classBuilder.addProperty(
                com.squareup.kotlinpoet.PropertySpec
                    .builder("outerLimit", INT, KModifier.PRIVATE)
                    .initializer("outerLimit")
                    .build(),
            )
        }
        if (nonTerminal) {
            classBuilder.addProperty(
                com.squareup.kotlinpoet.PropertySpec
                    .builder("payloadStart", INT, KModifier.PRIVATE)
                    .initializer("payloadStart")
                    .build(),
            )
            classBuilder.addProperty(
                com.squareup.kotlinpoet.PropertySpec
                    .builder("payloadEnd", INT, KModifier.PRIVATE)
                    .initializer("payloadEnd")
                    .build(),
            )
        }
        classBuilder.addProperty(
            com.squareup.kotlinpoet.PropertySpec
                .builder("buffer", READ_BUFFER_CN, KModifier.PRIVATE)
                .initializer("buffer")
                .build(),
        )
        classBuilder.addProperty(
            com.squareup.kotlinpoet.PropertySpec
                .builder("context", DECODE_CONTEXT_CN, KModifier.PRIVATE)
                .initializer("context")
                .build(),
        )

        classBuilder.addFunction(
            buildPartialCompleteFun(shape, payloadTypeParameter, payloadField, hasBoundingField, nonTerminal),
        )
        return classBuilder.build()
    }

    /**
     * Emit the `Partial.complete(...)` function.
     *
     * Path: the codec is `@UseCodec`-pinned, so `complete`
     * takes no parameters and calls `<UserCodec>.decode(buffer, context)`
     * directly. path: the codec is supplied at the call site,
     * so `complete(payloadCodec: Decoder<P>)` accepts the decoder and
     * calls `payloadCodec.decode(buffer, context)`. Both branches share
     * the trailing constructor call.
     */
    private fun buildPartialCompleteFun(
        shape: CodecShape,
        payloadTypeParameter: PayloadTypeParameter?,
        payloadField: FieldSpec.RemainingBytesPayload,
        hasBoundingField: Boolean,
        nonTerminal: Boolean = false,
    ): FunSpec {
        val funBuilder = FunSpec.builder("complete")
        val returnType =
            if (payloadTypeParameter != null) {
                shape.messageClassName.parameterizedBy(
                    TypeVariableName(payloadTypeParameter.typeVariableName),
                )
            } else {
                shape.messageClassName
            }
        funBuilder.returns(returnType)

        // Resolve the payload-decode statement up front; both the
        // RL and no-RL paths emit the same statement, just inside or
        // outside the try-block.
        val payloadDecodeStmt =
            when (val source = payloadField.source) {
                is PayloadCodecSource.UserCodecObject ->
                    CodeBlock.of(
                        "val %L = %T.decode(buffer, context)\n",
                        payloadField.name,
                        source.codecType,
                    )
                is PayloadCodecSource.ConstructorInjected -> {
                    val pTpName =
                        payloadTypeParameter
                            ?: error("ConstructorInjected payload source requires a payload type parameter")
                    funBuilder.addParameter(
                        source.parameterName,
                        DECODER_CN.parameterizedBy(TypeVariableName(pTpName.typeVariableName)),
                    )
                    CodeBlock.of(
                        "val %L = %L.decode(buffer, context)\n",
                        payloadField.name,
                        source.parameterName,
                    )
                }
            }
        val ctorArgs = shape.fields.joinToString(", ") { "${it.name} = ${it.name}" }
        val body = CodeBlock.builder()
        if (nonTerminal) {
            // The trailer was consumed eagerly in `partial`, so the buffer
            // position no longer sits at the payload. Re-seek to the captured
            // payload region, narrow the limit to it, decode, then restore the
            // limit. `payloadStart`/`payloadEnd` were captured at partial time.
            body.addStatement("buffer.position(payloadStart)")
            body.addStatement("val __payloadSavedLimit = buffer.limit()")
            body.addStatement("buffer.setLimit(payloadEnd)")
            body.beginControlFlow("return try")
            body.add(payloadDecodeStmt)
            body.addStatement("%T(%L)", returnType, ctorArgs)
            body.nextControlFlow("finally")
            body.addStatement("buffer.setLimit(__payloadSavedLimit)")
            body.endControlFlow()
        } else if (hasBoundingField) {
            // Payload decode runs inside the bounding-field-narrowed
            // limit (correct for payload bounding); the outer limit is
            // restored via try/finally so the caller's outer limit
            // survives even if the user codec throws.
            body.beginControlFlow("return try")
            body.add(payloadDecodeStmt)
            body.addStatement("%T(%L)", returnType, ctorArgs)
            body.nextControlFlow("finally")
            body.addStatement("buffer.setLimit(outerLimit)")
            body.endControlFlow()
        } else {
            body.add(payloadDecodeStmt)
            body.addStatement("return %T(%L)", returnType, ctorArgs)
        }
        funBuilder.addCode(body.build())
        return funBuilder.build()
    }

    /**
     * Emit the `partial(buffer, context)` decode
     * entry. places this as a member function on the codec
     * `object`; places it on the codec class's companion
     * object (with a fresh `<P : Payload>` type parameter so the call
     * site can choose the payload type without instantiating the
     * surrounding generic codec class).
     *
     * The body decodes every header field (everything before the
     * trailing `RemainingBytesPayload`), then constructs the nested
     * `Partial` capturing the buffer + context. The header decode
     * statements are the same `appendDecodeField` emit used by the
     * full `decode(...)` — Partial differs only in stopping before
     * the payload field and packaging the locals into `Partial`
     * instead of the full message constructor.
     */
    private fun buildPartialEntryFun(
        shape: CodecShape,
        payloadTypeParameter: PayloadTypeParameter?,
    ): FunSpec {
        val payloadIndex = payloadFieldIndex(shape)
        val payloadField = shape.fields[payloadIndex] as FieldSpec.RemainingBytesPayload
        val beforeFields = shape.fields.subList(0, payloadIndex)
        val afterFields = shape.fields.subList(payloadIndex + 1, shape.fields.size)
        val nonTerminal = afterFields.isNotEmpty()
        val partialClassName = ClassName(shape.packageName, shape.codecSimpleName, "Partial")
        val funBuilder = FunSpec.builder("partial")
        val returnType =
            if (payloadTypeParameter != null) {
                funBuilder.addTypeVariable(
                    TypeVariableName(payloadTypeParameter.typeVariableName, payloadTypeParameter.bound),
                )
                partialClassName.parameterizedBy(
                    TypeVariableName(payloadTypeParameter.typeVariableName),
                )
            } else {
                partialClassName
            }
        funBuilder
            .addParameter("buffer", READ_BUFFER_CN)
            .addParameter("context", DECODE_CONTEXT_CN)
            .returns(returnType)

        val body = CodeBlock.builder()
        if (nonTerminal) {
            // Non-terminal payload (issue #168): read the leading fields, then
            // skip the payload region (its end is `limit - reservedTrailingBytes`,
            // computable without decoding) and read the fixed-size trailer
            // eagerly. `complete()` re-seeks to [payloadStart, payloadEnd) to
            // decode the deferred payload. Gated to the unframed/unbounded case
            // by `shouldEmitPartial`, so no framing/bounding handling here.
            appendDecodeFields(body, beforeFields)
            body.addStatement("val __payloadStart = buffer.position()")
            body.addStatement("val __payloadEnd = buffer.limit() - %L", payloadField.reservedTrailingBytes)
            body.addStatement("buffer.position(__payloadEnd)")
            appendDecodeFields(body, afterFields)
            val ctorArgs =
                (
                    beforeFields.map { "${it.name} = ${it.name}" } +
                        afterFields.map { "${it.name} = ${it.name}" } +
                        listOf(
                            "payloadStart = __payloadStart",
                            "payloadEnd = __payloadEnd",
                            "buffer = buffer",
                            "context = context",
                        )
                ).joinToString(", ")
            body.addStatement("return %T(%L)", returnType, ctorArgs)
            funBuilder.addCode(body.build())
            return funBuilder.build()
        }
        // Terminal payload. When the parent supplies framing via
        // `@FramedBy`, the variant's partial decode reads the after-field
        // first, then applies the framing bound (capturing the outer
        // limit), then walks the remaining header fields inside the
        // narrowed bound. Mirrors [buildFramedByDecodeFun]'s field order
        // so wire bytes line up.
        val framedBy = shape.framedBy
        val afterField = framedBy?.let { framedByAfterField(shape, it) }
        if (framedBy != null) {
            if (afterField != null) appendDecodeField(body, afterField)
            body.addStatement("val __framingOuterLimit = buffer.limit()")
            body.addStatement(
                "val __framingLength = %T.decode(buffer, context)",
                framedBy.codecClassName,
            )
            body.addStatement(
                "%T.applyBound(buffer, __framingLength)",
                framedBy.codecClassName,
            )
            appendDecodeFields(body, beforeFields.filter { it !== afterField })
        } else {
            appendDecodeFields(body, beforeFields)
        }
        val boundingField = shape.fields.firstOrNull { it.isBoundingShape() }
        val outerLimitLocal =
            boundingField?.let { "__${it.name}OuterLimit" }
                ?: framedBy?.let { "__framingOuterLimit" }
        val outerLimitArgs =
            outerLimitLocal?.let {
                // appendDecodeUseCodecScalar emits
                // the outer-limit local as `__<fieldName>OuterLimit`;
                // the framed branch emits `__framingOuterLimit`.
                // Either way, hand it to the Partial so `complete()` can
                // restore the outer buffer limit on finally.
                listOf("outerLimit = $it")
            } ?: emptyList()
        val ctorArgs =
            (
                beforeFields.map { "${it.name} = ${it.name}" } +
                    outerLimitArgs +
                    listOf("buffer = buffer", "context = context")
            ).joinToString(", ")
        body.addStatement("return %T(%L)", returnType, ctorArgs)
        funBuilder.addCode(body.build())
        return funBuilder.build()
    }

    /**
     * Companion-object wrapper for the
     * `partial<P>(...)` entry. Companion-side placement is required:
     * a member-side `partial(...)` would force the consumer to first
     * construct `MqttPublishV3Codec(somePayloadCodec)` just to call
     * `partial`, defeating the purpose of deferring the
     * codec choice past the header decode.
     */
    private fun buildPartialCompanionObject(
        shape: CodecShape,
        payloadTypeParameter: PayloadTypeParameter,
    ): TypeSpec =
        TypeSpec
            .companionObjectBuilder()
            .addFunction(buildPartialEntryFun(shape, payloadTypeParameter))
            .build()

    /**
     * Derive the property `TypeName` for a header
     * field on the `Partial` class. The `Partial` mirrors the data
     * class's header fields with their original Kotlin types, so this
     * map is a closed mirror of `FieldSpec`'s shape-to-type mapping.
     * The trailing `RemainingBytesPayload` field is never asked for
     * (it's stripped by `headerFields = shape.fields.dropLast(1)` at
     * every call site).
     */
    private fun partialFieldTypeName(field: FieldSpec): TypeName =
        when (field) {
            is FieldSpec.Scalar -> scalarTypeName(field.kind)
            is FieldSpec.LengthPrefixedString -> ClassName("kotlin", "String")
            is FieldSpec.LengthPrefixedMessage -> field.messageType
            is FieldSpec.LengthFromString -> ClassName("kotlin", "String")
            is FieldSpec.LengthFromMessage -> field.messageType
            is FieldSpec.LengthFromList ->
                ClassName("kotlin.collections", "List").parameterizedBy(field.elementClassName)
            is FieldSpec.RemainingBytesProtocolMessageList ->
                error(
                    "partialFieldTypeName called on a RemainingBytesProtocolMessageList field — " +
                        "this shape is terminal-only and the Partial decode pattern only fires " +
                        "for shapes with a trailing RemainingBytesPayload. The two shapes are " +
                        "mutually exclusive at the terminal slot, so this branch is unreachable.",
                )
            is FieldSpec.RemainingBytesPayload ->
                error(
                    "partialFieldTypeName called on a RemainingBytesPayload field — caller " +
                        "should strip the payload field before mapping header types.",
                )
            is FieldSpec.RemainingBytesString ->
                error(
                    "partialFieldTypeName called on a RemainingBytesString field — this shape " +
                        "is terminal-only and the Partial decode pattern only fires for shapes " +
                        "with a trailing RemainingBytesPayload. The two are mutually exclusive " +
                        "at the terminal slot, so this branch is unreachable.",
                )
            is FieldSpec.UseCodecScalar -> field.fieldType
            is FieldSpec.LengthPrefixedUseCodecList ->
                ClassName("kotlin.collections", "List").parameterizedBy(field.elementClassName)
            is FieldSpec.LengthPrefixedUseCodecPayload -> field.payloadType
            is FieldSpec.ValueClassScalar -> field.valueClassType
            is FieldSpec.Conditional -> field.nullableTypeName
            is FieldSpec.ProtocolMessageScalar -> field.fieldType
        }

    private fun buildWireSizeFun(
        shape: CodecShape,
        messageType: TypeName = shape.messageClassName,
    ): FunSpec {
        val builder =
            FunSpec
                .builder("wireSize")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("value", messageType)
                .addParameter("context", ENCODE_CONTEXT_CN)
                .returns(WIRE_SIZE_CN)
        // Any `@When` field collapses the message
        // wireSize to BackPatch — we don't attempt conditional-Exact arithmetic.
        if (shape.fields.any { it is FieldSpec.Conditional }) {
            builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
            return builder.build()
        }
        // A `@RemainingBytes @UseCodec val: P` field's
        // wireSize comes from the user codec, which may be Exact or
        // BackPatch. takes the conservative BackPatch path
        // unconditionally — promoting to runtime-Exact-via-cast (mirroring
        // LengthPrefixedMessage) is a follow-on once we have a vector
        // where the size optimization actually matters.
        if (shape.fields.any { it is FieldSpec.RemainingBytesPayload }) {
            builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
            return builder.build()
        }
        // Any `@UseCodec val: <scalar>` field's wireSize comes from
        // the user codec, which may be Exact or BackPatch. Collapse to
        // BackPatch unconditionally — runtime-Exact-via-cast (mirroring
        // LengthPrefixedMessage) is a follow-on once a vector measurably
        // benefits.
        if (shape.fields.any { it is FieldSpec.UseCodecScalar }) {
            builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
            return builder.build()
        }
        // `@LengthPrefixed @UseCodec val: List<E>`
        // wireSize composes the user codec's prefix size with the sum of
        // element wireSizes. Same conservative BackPatch collapse as the
        // bare-scalar case — runtime-Exact-via-cast is a follow-on.
        if (shape.fields.any { it is FieldSpec.LengthPrefixedUseCodecList }) {
            builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
            return builder.build()
        }
        // `@LengthPrefixed @UseCodec val: T: Payload`
        // wireSize composes a fixed prefix with the user codec's body size,
        // which is opaque. Same conservative BackPatch collapse as the
        // bare-scalar / list cases — runtime-Exact-via-cast is a follow-on.
        if (shape.fields.any { it is FieldSpec.LengthPrefixedUseCodecPayload }) {
            builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
            return builder.build()
        }
        // Bare `val: T: @ProtocolMessage` wireSize
        // delegates to T's codec at runtime (RuntimeExact). Same conservative
        // BackPatch collapse on the parent — promoting to runtime-Exact-via-
        // cast is a follow-on once a vector benefits.
        if (shape.fields.any { it is FieldSpec.ProtocolMessageScalar }) {
            builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
            return builder.build()
        }
        // Any `@LengthPrefixed val: String` collapses
        // wireSize to BackPatch (pre-measuring the UTF-8 byte length is the
        // walk the BackPatch path collapses into the single writeString call).
        // The rule applies regardless of position now that LPS
        // String can appear non-terminally.
        if (shape.fields.any { it is FieldSpec.LengthPrefixedString }) {
            builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
            return builder.build()
        }
        // `@RemainingBytes val: String` collapses wireSize to BackPatch for the
        // same reason `@LengthPrefixed val: String` does — pre-measuring the
        // UTF-8 byte count is the walk the BackPatch path collapses into the
        // single writeString call.
        if (shape.fields.any { it is FieldSpec.RemainingBytesString }) {
            builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
            return builder.build()
        }
        // `@RemainingBytes List<E>` where E is a
        // sealed parent (or otherwise BackPatch-element) collapses to
        // BackPatch. The runtime-Exact emit below sums element wireSizes
        // via `as WireSize.Exact` cast; sealed-parent variants carrying
        // `@LengthPrefixed val: String` or `@When` trailers produce
        // BackPatch wireSize and would CCE on that cast. No fixture trips
        // this today, but the guard is required for correctness once a
        // typed-RC list lands.
        if (shape.fields.any {
                it is FieldSpec.RemainingBytesProtocolMessageList && it.elementIsBackPatch
            }
        ) {
            builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
            return builder.build()
        }
        // Non-terminal `@RemainingBytes*` fields collapse the
        // parent's wireSize to BackPatch. The terminal-`when` branches
        // below assume the body field IS the terminal (so they sum
        // header bytes + body bytes); when the body sits before a
        // FixedSize trailer the terminal is the trailer's Scalar /
        // ValueClassScalar, the body's bytes get dropped, and the
        // resulting Exact value would under-count the message.
        // BackPatch sidesteps the bookkeeping — the encoder uses a
        // growable buffer and reports the actual byte count after the
        // body emits, same shape as `@LengthPrefixed val: String`.
        if (shape.fields.any {
                it is FieldSpec.RemainingBytesProtocolMessageList && it.reservedTrailingBytes != 0
            }
        ) {
            builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
            return builder.build()
        }
        when (val terminal = shape.fields.lastOrNull()) {
            is FieldSpec.LengthPrefixedMessage -> {
                val headerBytes = scalarHeaderBytes(shape) + terminal.prefixWidth
                builder.addStatement(
                    "val %L = (%T.wireSize(value.%L, context) as %T.Exact).bytes",
                    "${terminal.name}Size",
                    terminal.codecType,
                    terminal.name,
                    WIRE_SIZE_CN,
                )
                builder.addStatement(
                    "return %T.Exact(%L + %L)",
                    WIRE_SIZE_CN,
                    headerBytes,
                    "${terminal.name}Size",
                )
            }
            is FieldSpec.LengthFromString -> {
                // Body byte count comes from the resolved LengthSource
                // (sibling.toInt() for simple, sibling.property for
                // dotted). User-trusted.
                val prefixBytes = scalarHeaderBytes(shape)
                builder.addStatement(
                    "return %T.Exact(%L + %L)",
                    WIRE_SIZE_CN,
                    prefixBytes,
                    terminal.source.encodeAccessor(),
                )
            }
            is FieldSpec.LengthFromList -> {
                // Same Exact shape via LengthSource.
                val prefixBytes = scalarHeaderBytes(shape)
                builder.addStatement(
                    "return %T.Exact(%L + %L)",
                    WIRE_SIZE_CN,
                    prefixBytes,
                    terminal.source.encodeAccessor(),
                )
            }
            is FieldSpec.LengthFromMessage -> {
                // Body byte count = sibling-resolved length
                // (same row-16 user-trust contract as LengthFromString /
                // LengthFromList). The nested message's own wireSize is
                // RuntimeExact at runtime, but we don't query it here:
                // the user supplies the sibling and is responsible for
                // keeping it consistent with the body's encoded size.
                val prefixBytes = scalarHeaderBytes(shape)
                builder.addStatement(
                    "return %T.Exact(%L + %L)",
                    WIRE_SIZE_CN,
                    prefixBytes,
                    terminal.source.encodeAccessor(),
                )
            }
            is FieldSpec.RemainingBytesProtocolMessageList -> {
                // Body byte count = sum of element wireSizes.
                // Each element codec's wireSize is cast to Exact at runtime —
                // same convention as LengthPrefixedMessage's `as Exact` cast
                // above; BackPatch element codecs throw ClassCastException
                // (fixture-design contract for this slice).
                val prefixBytes = scalarHeaderBytes(shape)
                builder.addStatement(
                    "return %T.Exact(%L + value.%L.sumOf { (%T.wireSize(it, context) as %T.Exact).bytes })",
                    WIRE_SIZE_CN,
                    prefixBytes,
                    terminal.name,
                    terminal.elementCodecClassName,
                    WIRE_SIZE_CN,
                )
            }
            is FieldSpec.RemainingBytesPayload ->
                error(
                    "RemainingBytesPayload terminal shape should be handled by the BackPatch " +
                        "early-return at the top of buildWireSizeFun; reaching this branch " +
                        "indicates a missed early return.",
                )
            else -> {
                // Singleton variant under
                // `@DispatchOn(value class)` self-frames the
                // discriminator (read in decode, write in encode), so
                // its wire byte count is the discriminator's inner-
                // scalar width. All other singletons keep `Exact(0)` —
                // their parent dispatcher writes/reads the discriminator
                // around the call.
                val discriminatorBytes =
                    (shape.singletonDispatchDiscriminator?.wireWidth ?: WireWidth.Zero)
                        .requireFixed("singletonDiscriminator")
                val total = shape.fields.sumOfFixedWireBytes().requireFixed("sumOfFixedWireBytes") + discriminatorBytes
                builder.addStatement("return %T.Exact(%L)", WIRE_SIZE_CN, total)
            }
        }
        return builder.build()
    }

    /**
     * Sum the `wireBytes` of every `FixedSize` field
     * in the list. Variable-length fields (`LengthPrefixed*`,
     * `Conditional`) contribute 0 and are filtered out by the
     * `filterIsInstance` step. Callers that require the result to
     * cover every field gate on terminal shape before calling.
     */
    private fun List<FieldSpec>.sumOfFixedWireBytes(): WireWidth =
        filterIsInstance<FieldSpec.FixedSize>()
            .map { it.wireWidth }
            .fold(WireWidth.Zero as WireWidth) { a, b -> a + b }

    private fun buildPeekFrameFun(shape: CodecShape): FunSpec {
        val builder =
            FunSpec
                .builder("peekFrameSize")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("stream", STREAM_PROCESSOR_CN)
                .addParameter("baseOffset", INT)
                .returns(PEEK_RESULT_CN)
        // Generic `@UseCodec` peek walker. When a
        // bounding `UseCodecScalar` field is present, the framework drives
        // the user codec against a non-consuming `stream.peekBuffer(...)`
        // view to discover the value, then computes total = priorBytes +
        // codec-width + value.toInt() — the user codec does the var-int
        // read against the peek view. Placed BEFORE the
        // `RemainingBytesProtocolMessageList` / `RemainingBytesPayload`
        // NoFraming collapses below: a bounding codec gives peek the
        // value-driven byte count that bounds any trailing
        // `@RemainingBytes` body.
        //
        // Conservative fallback to NoFraming for shapes outside the
        // walker's reach: non-bounding `@UseCodec` (no value-to-byte
        // mapping), value-class / non-scalar field types (no peek-budget
        // entry), or non-FixedSize prior fields (the walker assumes a
        // statically-known prior byte count).
        val ucsField =
            shape.fields.firstOrNull { it is FieldSpec.UseCodecScalar } as? FieldSpec.UseCodecScalar
        if (ucsField != null) {
            val budget = peekBudgetFor(ucsField.fieldType)
            val priorAreFixed =
                shape.fields
                    .takeWhile { it !== ucsField }
                    .all { it is FieldSpec.FixedSize }
            // A non-statically-sized prior field → out of the walker's reach
            // (the walker assumes a statically-known prior byte count).
            if (!priorAreFixed) {
                builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
                return builder.build()
            }
            // Bounding length: the decoded value IS the body byte count, so
            // total = prior + width + value (existing walker). Needs a peek
            // budget to size the materialize-and-decode view; no budget entry
            // (value-class / non-scalar field) → NoFraming.
            if (ucsField.isBounding) {
                if (budget == null) {
                    builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
                    return builder.build()
                }
                appendPeekUseCodecScalar(builder, shape, ucsField, budget)
                return builder.build()
            }
            // Self-delimiting variable-width value (VariableLengthCodec): the
            // value occupies only its own bytes, so total = prior + width +
            // fixed-suffix — provided every field after it is FixedSize (a
            // variable suffix would desync the byte count). Width comes from the
            // codec's own peekFrameSize (no peek budget needed), so this also
            // composes through a value-class codec whose inner is a varint.
            if (ucsField.isVariableLength) {
                val suffixAreFixed =
                    shape.fields
                        .dropWhile { it !== ucsField }
                        .drop(1)
                        .all { it is FieldSpec.FixedSize }
                if (!suffixAreFixed) {
                    builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
                    return builder.build()
                }
                appendPeekVariableLengthUseCodecScalar(builder, shape, ucsField)
                return builder.build()
            }
            // Non-bounding, non-variable-length @UseCodec: no value-to-byte
            // mapping the walker can use.
            builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
            return builder.build()
        }
        // `@LengthPrefixed @UseCodec val: List<E>`
        // peek mirrors the bounding-`UseCodecScalar` walker: total =
        // priorBytes + observed-codec-width + decodedValue.toInt(). The
        // codec is `BoundingLengthCodec<UInt>` (validator-checked); peek
        // budget is the UInt budget (5 bytes — covers 7-bit-continuation
        // var-byte-int up to 4 bytes plus a sentinel byte). NoFraming
        // when prior fields aren't all FixedSize OR the list isn't the
        // last field — fields after the bounded region don't contribute
        // to the value and would desync the formula.
        val lpUcField =
            shape.fields.firstOrNull { it is FieldSpec.LengthPrefixedUseCodecList }
                as? FieldSpec.LengthPrefixedUseCodecList
        if (lpUcField != null) {
            val priorAreFixed =
                shape.fields
                    .takeWhile { it !== lpUcField }
                    .all { it is FieldSpec.FixedSize }
            val isTerminal = shape.fields.last() === lpUcField
            if (!priorAreFixed || !isTerminal) {
                builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
                return builder.build()
            }
            appendPeekLengthPrefixedUseCodecList(builder, shape, lpUcField, peekBudget = 5)
            return builder.build()
        }
        // `@RemainingBytes List<@ProtocolMessage T>` and
        // `@RemainingBytes val: String` collapse peek to NoFraming. The
        // body's byte count comes from the caller-set buffer limit,
        // which the stream-side peek can't see; consumers must use
        // outer-protocol framing (e.g., MQTT's fixed-header remaining-
        // length) to determine the bounded read.
        if (shape.fields.any {
                it is FieldSpec.RemainingBytesProtocolMessageList ||
                    it is FieldSpec.RemainingBytesString
            }
        ) {
            builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
            return builder.build()
        }
        // Same NoFraming collapse for `@RemainingBytes
        // @UseCodec val: P`. Body byte count is whatever the user codec
        // reads against the caller-set limit; 's outer
        // dispatcher will own peek by reading the fixed header's
        // remaining-length first.
        if (shape.fields.any { it is FieldSpec.RemainingBytesPayload }) {
            builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
            return builder.build()
        }
        // Bare `val: T: @ProtocolMessage` collapses
        // peek to NoFraming. The body's byte count is determined by T's
        // codec at runtime (variable for sealed dispatchers, static for
        // data classes), and we don't invoke decoded codecs in peek.
        // ConnAck.reasonCode et al. peek is owned upstream by the bounding
        // RL field via `appendPeekUseCodecScalar`.
        if (shape.fields.any { it is FieldSpec.ProtocolMessageScalar }) {
            builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
            return builder.build()
        }
        // `@When("remaining <op> <int>")` collapses peek to
        // NoFraming when reached at this point. The grammar-2 predicate
        // tests the decode buffer's `remaining()` after the upstream
        // bounding `applyBound`; peek has no symmetric primitive, so the
        // conditional inner's wire presence can't be predicted from a
        // stream-only walk. v5 acks (PUBACK et al.) escape this collapse
        // because their bounding RL field is handled by the
        // `UseCodecScalar` branch above — that branch returns total =
        // priorBytes + vbi_width + rl_value before reaching here, so the
        // sequential walk never visits the grammar-2 field.
        //
        // Also collapse for any conditional whose inner is
        // `LengthPrefixedUseCodecList` (the cascading-trailer property
        // bag). The inner shape is variable-length — a VBI prefix +
        // body bytes — and peek can't predict whether the conditional
        // branch is taken without buffer access. v5 ack peek is owned
        // by the bounding RL upstream (same as above).
        //
        // Same collapse for `UseCodecScalar`
        // inners. The user codec's wire width is opaque to the framework
        // (could be a single byte for a typed RC, could be variable),
        // so peek can't size the field without invoking the codec. v5
        // ack peek again handled by the bounding RL upstream.
        if (shape.fields.any { it.isPeekCollapsingConditional() }) {
            builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
            return builder.build()
        }
        // All-FixedSize messages collapse to a single arithmetic check —
        // no walk needed, and the generated code is significantly tighter
        // than the sequential path (which would emit a per-field
        // availability check + offset advance).
        //
        // Empty-fields singletons fall into this
        // branch (the `all { ... }` predicate is vacuously true). When
        // the singleton self-frames a `@DispatchOn(value class)`
        // discriminator, add the discriminator's inner-scalar width so
        // the peek count matches what decode actually consumes.
        if (shape.fields.all { it is FieldSpec.FixedSize }) {
            val discriminatorBytes =
                (shape.singletonDispatchDiscriminator?.wireWidth ?: WireWidth.Zero)
                    .requireFixed("singletonDiscriminator")
            val total = shape.fields.sumOfFixedWireBytes().requireFixed("sumOfFixedWireBytes") + discriminatorBytes
            builder.addStatement(
                "return if (stream.available() - baseOffset >= %L) %T.Complete(%L) else %T.NeedsMoreData",
                total,
                PEEK_RESULT_CN,
                total,
                PEEK_RESULT_CN,
            )
            return builder.build()
        }
        appendSequentialPeek(builder, shape)
        return builder.build()
    }

    /**
     * Emit peek for a shape carrying a bounding
     * `@UseCodec val: <scalar>` field. Materializes a non-consuming view
     * via `stream.peekBuffer(baseOffset + priorBytes, peekBudget)`, runs
     * `<codec>.decode` against the view, and computes total =
     * priorBytes + observed-codec-width + decodedValue.toInt(). The
     * user codec does the var-int read against the peek view, so the
     * peek path stays in lockstep with the decode path by construction.
     *
     * `IndexOutOfBoundsException` from the codec (the documented
     * underflow signal on `ReadBuffer`) collapses to
     * `PeekResult.NeedsMoreData`. Other exceptions (e.g. the codec's
     * own `DecodeException` for malformed wire) propagate — the stream
     * genuinely has bad data and the caller should observe the
     * exception, not loop on `NeedsMoreData`.
     *
     * The peek view is released via `freeNativeMemory()` in a `finally`
     * so the slow-path pool buffer is returned even when peek aborts.
     * The fast-path slice's `freeNativeMemory()` is a no-op for non-
     * pooled chunks.
     */
    private fun appendPeekUseCodecScalar(
        builder: FunSpec.Builder,
        shape: CodecShape,
        field: FieldSpec.UseCodecScalar,
        peekBudget: Int,
    ) {
        val priorBytes =
            shape.fields
                .takeWhile { it !== field }
                .filterIsInstance<FieldSpec.FixedSize>()
                .sumOf { it.wireBytes }
        val body = CodeBlock.builder()
        body.addStatement(
            "if (stream.available() - baseOffset < %L) return %T.NeedsMoreData",
            priorBytes + 1,
            PEEK_RESULT_CN,
        )
        val peekViewVar = "__${field.name}PeekView"
        body.addStatement(
            "val %L = stream.peekBuffer(baseOffset + %L, %L) ?: return %T.NeedsMoreData",
            peekViewVar,
            priorBytes,
            peekBudget,
            PEEK_RESULT_CN,
        )
        body.beginControlFlow("try")
        val priorPosVar = "__${field.name}PriorPos"
        body.addStatement("val %L = %L.position()", priorPosVar, peekViewVar)
        body.beginControlFlow("val %L = try", field.name)
        body.addStatement(
            "%T.decode(%L, %T.Empty)",
            field.codecType,
            peekViewVar,
            DECODE_CONTEXT_CN,
        )
        // Catch the underflow exception cross-platform via simpleName
        // whitelist: JVM `java.nio.BufferUnderflowException` (extends
        // RuntimeException, NOT IndexOutOfBoundsException), JS/WASM/
        // Native `IndexOutOfBoundsException` / `ArrayIndexOutOfBoundsException`.
        // Any other exception (e.g. the codec's own DecodeException for
        // malformed wire) propagates — the stream genuinely has bad
        // data and the caller should observe it, not loop on
        // NeedsMoreData.
        body.nextControlFlow("catch (__e: %T)", ClassName("kotlin", "Throwable"))
        body.beginControlFlow("when (__e::class.simpleName)")
        body.addStatement(
            "%S, %S, %S -> return %T.NeedsMoreData",
            "BufferUnderflowException",
            "IndexOutOfBoundsException",
            "ArrayIndexOutOfBoundsException",
            PEEK_RESULT_CN,
        )
        body.addStatement("else -> throw __e")
        body.endControlFlow()
        body.endControlFlow()
        val widthVar = "__${field.name}Width"
        body.addStatement("val %L = %L.position() - %L", widthVar, peekViewVar, priorPosVar)
        body.addStatement(
            "val __total = %L + %L + %L.toInt()",
            priorBytes,
            widthVar,
            field.name,
        )
        body.addStatement(
            "return if (stream.available() - baseOffset >= __total) %T.Complete(__total) else %T.NeedsMoreData",
            PEEK_RESULT_CN,
            PEEK_RESULT_CN,
        )
        body.nextControlFlow("finally")
        body.addStatement(
            "(%L as? %T)?.freeNativeMemory()",
            peekViewVar,
            PLATFORM_BUFFER_CN,
        )
        body.endControlFlow()
        builder.addCode(body.build())
    }

    /**
     * Emit peek for a shape carrying a non-bounding, **variable-length**
     * `@UseCodec val: <scalar>` field (codec implements `VariableLengthCodec`).
     * The decoded value is *not* a body length, so the frame total is
     * `priorBytes + codecWidth + fixedSuffixBytes` — no value term.
     *
     * Width comes from the codec's own `peekFrameSize` (every
     * `VariableLengthCodec` derives it from `peekValue`): `Complete(width)` once
     * the self-delimiting value is fully buffered, `NeedsMoreData` otherwise.
     * Delegating to the codec means no peek budget is needed and the same path
     * composes through a generated value-class codec whose inner is a varint.
     *
     * Caller guarantees (in [buildPeekFrameFun]): every prior and suffix field
     * is `FixedSize`.
     */
    private fun appendPeekVariableLengthUseCodecScalar(
        builder: FunSpec.Builder,
        shape: CodecShape,
        field: FieldSpec.UseCodecScalar,
    ) {
        val priorBytes =
            shape.fields
                .takeWhile { it !== field }
                .filterIsInstance<FieldSpec.FixedSize>()
                .sumOf { it.wireBytes }
        val suffixBytes =
            shape.fields
                .dropWhile { it !== field }
                .drop(1)
                .filterIsInstance<FieldSpec.FixedSize>()
                .sumOf { it.wireBytes }
        val body = CodeBlock.builder()
        val frameVar = "__${field.name}Frame"
        body.addStatement(
            "val %L = %T.peekFrameSize(stream, baseOffset + %L)",
            frameVar,
            field.codecType,
            priorBytes,
        )
        // Propagate NeedsMoreData / NoFraming unchanged; only a Complete width
        // lets us size the whole frame.
        body.beginControlFlow("if (%L !is %T.Complete)", frameVar, PEEK_RESULT_CN)
        body.addStatement("return %L", frameVar)
        body.endControlFlow()
        body.addStatement(
            "val __total = %L + %L.bytes + %L",
            priorBytes,
            frameVar,
            suffixBytes,
        )
        body.addStatement(
            "return if (stream.available() - baseOffset >= __total) %T.Complete(__total) else %T.NeedsMoreData",
            PEEK_RESULT_CN,
            PEEK_RESULT_CN,
        )
        builder.addCode(body.build())
    }

    /**
     * Emit peek for a shape carrying a terminal
     * `@LengthPrefixed @UseCodec val: List<E>` field. Mirrors
     * [appendPeekUseCodecScalar]: drives the prefix codec against a
     * non-consuming `stream.peekBuffer(...)` view, measures observed
     * codec width, computes total = priorBytes + width +
     * decodedValue.toInt(). The decoded UInt is the body byte count, so
     * adding it to the prefix bytes yields the full frame size. Caller
     * has already gated on `priorAreFixed && isTerminal`.
     */
    private fun appendPeekLengthPrefixedUseCodecList(
        builder: FunSpec.Builder,
        shape: CodecShape,
        field: FieldSpec.LengthPrefixedUseCodecList,
        peekBudget: Int,
    ) {
        val priorBytes =
            shape.fields
                .takeWhile { it !== field }
                .filterIsInstance<FieldSpec.FixedSize>()
                .sumOf { it.wireBytes }
        val body = CodeBlock.builder()
        body.addStatement(
            "if (stream.available() - baseOffset < %L) return %T.NeedsMoreData",
            priorBytes + 1,
            PEEK_RESULT_CN,
        )
        val peekViewVar = "__${field.name}PeekView"
        body.addStatement(
            "val %L = stream.peekBuffer(baseOffset + %L, %L) ?: return %T.NeedsMoreData",
            peekViewVar,
            priorBytes,
            peekBudget,
            PEEK_RESULT_CN,
        )
        body.beginControlFlow("try")
        val priorPosVar = "__${field.name}PriorPos"
        val lengthVar = "__${field.name}Length"
        body.addStatement("val %L = %L.position()", priorPosVar, peekViewVar)
        body.beginControlFlow("val %L = try", lengthVar)
        body.addStatement(
            "%T.decode(%L, %T.Empty)",
            field.codecType,
            peekViewVar,
            DECODE_CONTEXT_CN,
        )
        body.nextControlFlow("catch (__e: %T)", ClassName("kotlin", "Throwable"))
        body.beginControlFlow("when (__e::class.simpleName)")
        body.addStatement(
            "%S, %S, %S -> return %T.NeedsMoreData",
            "BufferUnderflowException",
            "IndexOutOfBoundsException",
            "ArrayIndexOutOfBoundsException",
            PEEK_RESULT_CN,
        )
        body.addStatement("else -> throw __e")
        body.endControlFlow()
        body.endControlFlow()
        val widthVar = "__${field.name}Width"
        body.addStatement("val %L = %L.position() - %L", widthVar, peekViewVar, priorPosVar)
        body.addStatement(
            "val __total = %L + %L + %L.toInt()",
            priorBytes,
            widthVar,
            lengthVar,
        )
        body.addStatement(
            "return if (stream.available() - baseOffset >= __total) %T.Complete(__total) else %T.NeedsMoreData",
            PEEK_RESULT_CN,
            PEEK_RESULT_CN,
        )
        body.nextControlFlow("finally")
        body.addStatement(
            "(%L as? %T)?.freeNativeMemory()",
            peekViewVar,
            PLATFORM_BUFFER_CN,
        )
        body.endControlFlow()
        builder.addCode(body.build())
    }

    /**
     * General sequential peek walk.
     *
     * Tracks a running `__offset` (relative to `baseOffset`) and per
     * field:
     *   - Ensures enough bytes are available before any peek that
     *     would otherwise read past the buffer end.
     *   - Stashes peeked bytes for `Scalar` and `ValueClassScalar`
     *     fields whose names are referenced by a later `Conditional`
     *     source or `LengthFromString` length-carrier. Stashed locals
     *     are named after the field itself (e.g., `flags`,
     *     `payloadLength`) so the predicate / sibling expressions
     *     read naturally.
     *   - Advances `__offset` by the field's contribution: fixed
     *     bytes for `FixedSize`, prefix-width plus body-byte-count
     *     (peeked) for `LengthPrefixed*`, sibling-driven length for
     *     `LengthFromString`, predicate-gated shape for
     *     `Conditional`.
     *
     * Replaces the /3/3.5/4 specialized peek paths
     * (single-LPS-terminal, single-Conditional-terminal,
     * single-LengthFromString-terminal). Equivalent results for those
     * shapes; previously-skipped shapes (multiple sequential
     * variable-length fields, non-terminal Conditional, non-terminal
     * LengthPrefixedString) become emitable here.
     */
    private fun appendSequentialPeek(
        builder: FunSpec.Builder,
        shape: CodecShape,
    ) {
        val needsPeekStash = collectPeekStashSources(shape)
        val body = CodeBlock.builder()
        body.addStatement("var __offset = 0")
        for (field in shape.fields) {
            when (field) {
                is FieldSpec.Scalar -> {
                    appendPeekAvailabilityCheck(body, field.wireWidth)
                    if (field.name in needsPeekStash) {
                        appendPeekScalar(body, field, field.name, "__offset")
                    }
                    body.addStatement("__offset += %L", field.wireWidth.requireFixed("appendSequentialPeek"))
                }
                is FieldSpec.ValueClassScalar -> {
                    appendPeekAvailabilityCheck(body, field.wireWidth)
                    if (field.name in needsPeekStash) {
                        val rawVar = "${field.name}Raw"
                        // Follow-up: pass the value class's wireOrder
                        // so multi-byte inner kinds (UShort/UInt) assemble in
                        // the correct order on the peek side. Single-byte
                        // kinds ignore the parameter.
                        appendPeekFixedScalar(
                            body = body,
                            kind = field.innerKind,
                            targetVar = rawVar,
                            offsetExpr = "__offset",
                            wireOrder = field.valueClassWireOrder,
                        )
                        body.addStatement(
                            "val %L = %T(%L)",
                            field.name,
                            field.valueClassType,
                            rawVar,
                        )
                    }
                    body.addStatement("__offset += %L", field.wireWidth.requireFixed("appendSequentialPeek"))
                }
                is FieldSpec.LengthPrefixedString ->
                    appendSequentialPeekLengthPrefixed(
                        body = body,
                        name = field.name,
                        ownerSimpleName = field.ownerSimpleName,
                        prefixWidth = field.prefixWidth,
                        prefixWireOrder = field.prefixWireOrder,
                    )
                is FieldSpec.LengthPrefixedMessage ->
                    appendSequentialPeekLengthPrefixed(
                        body = body,
                        name = field.name,
                        ownerSimpleName = field.ownerSimpleName,
                        prefixWidth = field.prefixWidth,
                        prefixWireOrder = field.prefixWireOrder,
                    )
                is FieldSpec.LengthFromString ->
                    appendSequentialPeekLengthFrom(
                        body = body,
                        name = field.name,
                        ownerSimpleName = field.ownerSimpleName,
                        source = field.source,
                    )
                is FieldSpec.LengthFromList ->
                    appendSequentialPeekLengthFrom(
                        body = body,
                        name = field.name,
                        ownerSimpleName = field.ownerSimpleName,
                        source = field.source,
                    )
                is FieldSpec.LengthFromMessage ->
                    // Peek shape identical to LengthFromString /
                    // LengthFromList: body byte count comes from the
                    // sibling, regardless of nested-message contents.
                    appendSequentialPeekLengthFrom(
                        body = body,
                        name = field.name,
                        ownerSimpleName = field.ownerSimpleName,
                        source = field.source,
                    )
                is FieldSpec.RemainingBytesProtocolMessageList ->
                    error(
                        "RemainingBytesProtocolMessageList should be handled by " +
                            "buildPeekFrameFun's upfront NoFraming short-circuit before reaching " +
                            "the sequential walk.",
                    )
                is FieldSpec.RemainingBytesPayload ->
                    error(
                        "RemainingBytesPayload should be handled by buildPeekFrameFun's " +
                            "upfront NoFraming short-circuit before reaching the sequential walk.",
                    )
                is FieldSpec.RemainingBytesString ->
                    error(
                        "RemainingBytesString should be handled by buildPeekFrameFun's " +
                            "upfront NoFraming short-circuit before reaching the sequential walk.",
                    )
                is FieldSpec.UseCodecScalar ->
                    error(
                        "UseCodecScalar should be handled by buildPeekFrameFun's upfront " +
                            "NoFraming short-circuit before reaching the sequential walk; the " +
                            "generic @UseCodec peek walker is not implemented in the sequential path.",
                    )
                is FieldSpec.LengthPrefixedUseCodecList ->
                    error(
                        "LengthPrefixedUseCodecList should be handled by buildPeekFrameFun's " +
                            "upfront NoFraming short-circuit / dedicated peek emitter before " +
                            "reaching the sequential walk; the terminal-only peek walker is " +
                            "not implemented in the sequential path.",
                    )
                is FieldSpec.LengthPrefixedUseCodecPayload ->
                    // Peek walks the fixed-width
                    // prefix and advances by the body byte count without
                    // running the user codec. Same shape as
                    // [LengthPrefixedString] / [LengthPrefixedMessage]:
                    // the prefix tells us the body size.
                    appendSequentialPeekLengthPrefixed(
                        body = body,
                        name = field.name,
                        ownerSimpleName = field.ownerSimpleName,
                        prefixWidth = field.prefixWidth,
                        prefixWireOrder = field.prefixWireOrder,
                    )
                is FieldSpec.ProtocolMessageScalar ->
                    error(
                        "ProtocolMessageScalar should be handled by buildPeekFrameFun's " +
                            "upfront NoFraming short-circuit before reaching the sequential walk.",
                    )
                is FieldSpec.Conditional ->
                    appendSequentialPeekConditional(body, field)
            }
        }
        body.addStatement(
            "return if (stream.available() - baseOffset >= __offset) %T.Complete(__offset) else %T.NeedsMoreData",
            PEEK_RESULT_CN,
            PEEK_RESULT_CN,
        )
        builder.addCode(body.build())
    }

    /**
     * Walk the fields and collect every name whose value will be
     * referenced later in the peek (by a `Conditional` predicate
     * source or a `LengthFromString` length carrier). The walk
     * stashes those fields' values into named locals.
     */
    private fun collectPeekStashSources(shape: CodecShape): Set<String> {
        val sources = mutableSetOf<String>()
        for (field in shape.fields) {
            when (field) {
                is FieldSpec.Conditional ->
                    when (val c = field.condition) {
                        is ConditionRef.Sibling -> sources += c.name
                        is ConditionRef.ValueClassProperty -> sources += c.siblingName
                        // Grammar 2 references no sibling; the peek path
                        // (when reachable) tests `stream.available() - baseOffset`
                        // arithmetic directly. No stash needed.
                        is ConditionRef.RemainingCmp -> {}
                    }
                is FieldSpec.LengthFromString -> sources += field.source.siblingName
                is FieldSpec.LengthFromList -> sources += field.source.siblingName
                is FieldSpec.LengthFromMessage -> sources += field.source.siblingName
                else -> { /* not a source */ }
            }
        }
        return sources
    }

    private fun appendPeekAvailabilityCheck(
        body: CodeBlock.Builder,
        width: WireWidth,
    ) {
        val bytes = width.requireFixed("appendPeekAvailabilityCheck")
        body.addStatement(
            "if (stream.available() - baseOffset < __offset + %L) return %T.NeedsMoreData",
            bytes,
            PEEK_RESULT_CN,
        )
    }

    /**
     * Peek a length-prefixed body (`@LengthPrefixed val:
     * String` or `@LengthPrefixed @ProtocolMessage`) inside the
     * sequential walk. The shape is identical for both: peek the
     * prefix at `__offset`, guard against `Int` overflow when
     * combined with the running offset, advance `__offset` by
     * `prefixWidth + prefix.toInt()`.
     */
    private fun appendSequentialPeekLengthPrefixed(
        body: CodeBlock.Builder,
        name: String,
        ownerSimpleName: String,
        prefixWidth: Int,
        prefixWireOrder: Endianness,
    ) {
        appendPeekAvailabilityCheck(body, WireWidth.Fixed(prefixWidth))
        appendPeekPrefixAssembly(body, name, prefixWidth, prefixWireOrder, "__offset")
        val prefixVar = "${name}Prefix"
        body.beginControlFlow(
            "if (%L > (Int.MAX_VALUE - __offset - %L).toUInt())",
            prefixVar,
            prefixWidth,
        )
        body.addStatement(
            "throw %T(fieldPath = %S, bufferPosition = baseOffset + __offset, expected = %S, actual = %P)",
            DECODE_EXCEPTION_CN,
            "$ownerSimpleName.$name",
            "__offset + $prefixWidth + length prefix <= \${Int.MAX_VALUE}",
            "\${__offset + $prefixWidth + $prefixVar.toInt()}",
        )
        body.endControlFlow()
        body.addStatement(
            "__offset += %L + %L.toInt()",
            prefixWidth,
            prefixVar,
        )
    }

    /**
     * Peek a `@LengthFrom`-style slot (terminal `LengthFromString` or
     * `LengthFromList`) inside the sequential walk. The sibling local was
     * peek-stashed earlier (Scalar-sibling case) or the sibling
     * value-class instance was peek-stashed and reconstructed (dotted
     * case); the `LengthSource.decodeAccessor()` produces the right Int
     * expression for either form. The Int.MAX_VALUE guard only applies to
     * the simple form (the dotted property returns Int).
     */
    private fun appendSequentialPeekLengthFrom(
        body: CodeBlock.Builder,
        name: String,
        ownerSimpleName: String,
        source: LengthSource,
    ) {
        if (source is LengthSource.Sibling) {
            appendLengthFromIntMaxGuard(
                body = body,
                siblingAccessor = source.siblingName,
                siblingKind = source.siblingKind,
                ownerSimpleName = ownerSimpleName,
                fieldName = name,
            )
        }
        body.addStatement(
            "val %LBytes = %L",
            name,
            source.decodeAccessor(),
        )
        body.addStatement(
            "if (stream.available() - baseOffset < __offset + %LBytes) return %T.NeedsMoreData",
            name,
            PEEK_RESULT_CN,
        )
        body.addStatement("__offset += %LBytes", name)
    }

    /**
     * Peek a `@When` slot inside the sequential walk.
     * The predicate source has already been peek-stashed (added to
     * `needsPeekStash` and read when its field was visited); the
     * inner shape is gated on that stashed local.
     */
    private fun appendSequentialPeekConditional(
        body: CodeBlock.Builder,
        field: FieldSpec.Conditional,
    ) {
        val condExpr = decodeConditionExpr(field.condition)
        body.beginControlFlow("if (%L)", condExpr)
        when (val inner = field.inner) {
            is ConditionalInner.Scalar -> {
                appendPeekAvailabilityCheck(body, inner.kind.wireWidth)
                body.addStatement("__offset += %L", inner.kind.wireWidth.requireFixed("appendSequentialPeekConditional"))
            }
            is ConditionalInner.LengthPrefixedString ->
                appendSequentialPeekLengthPrefixed(
                    body = body,
                    name = field.name,
                    ownerSimpleName = field.ownerSimpleName,
                    prefixWidth = inner.prefixWidth,
                    prefixWireOrder = inner.prefixWireOrder,
                )
            is ConditionalInner.ValueClassScalar -> {
                // Peek consumes the inner scalar's
                // natural width when the predicate is true (the value
                // class wraps with no extra wire bytes).
                appendPeekAvailabilityCheck(body, inner.innerKind.wireWidth)
                body.addStatement("__offset += %L", inner.innerKind.wireWidth.requireFixed("appendSequentialPeekConditional"))
            }
            is ConditionalInner.LengthPrefixedUseCodecList ->
                // Unreachable: any shape with this inner
                // collapses the whole frame to NoFraming via
                // `buildPeekFrameFun`'s upfront short-circuit, so the
                // sequential walk never reaches here.
                error(
                    "appendSequentialPeekConditional reached LengthPrefixedUseCodecList — " +
                        "buildPeekFrameFun should have short-circuited the shape to NoFraming.",
                )
            is ConditionalInner.LengthPrefixedUseCodecPayload ->
                // Peek walks the fixed-width
                // prefix and advances by the body byte count. Same
                // shape as [LengthPrefixedString] — the prefix tells
                // the peek walker how many bytes to advance without
                // running the user codec.
                appendSequentialPeekLengthPrefixed(
                    body = body,
                    name = field.name,
                    ownerSimpleName = field.ownerSimpleName,
                    prefixWidth = inner.prefixWidth,
                    prefixWireOrder = inner.prefixWireOrder,
                )
            is ConditionalInner.UseCodecScalar ->
                // Same NoFraming short-circuit as
                // LengthPrefixedUseCodecList. The user codec's wire width
                // is opaque, so `buildPeekFrameFun` collapses the whole
                // frame; the sequential walk never visits this branch.
                error(
                    "appendSequentialPeekConditional reached UseCodecScalar — " +
                        "buildPeekFrameFun should have short-circuited the shape to NoFraming.",
                )
            is ConditionalInner.ProtocolMessageScalar ->
                // Same NoFraming short-circuit. The
                // generated `<T>Codec.peekFrameSize` could in principle size
                // the inner field, but the cascading-trailer cases that drive
                // this shape use grammar-2 `remaining >= N` predicates whose
                // truth depends on the bounding RL field upstream — the
                // outer codec's peek already returns NeedsMoreData / Complete
                // off the RL value, so a per-field peek would be redundant.
                error(
                    "appendSequentialPeekConditional reached ProtocolMessageScalar — " +
                        "buildPeekFrameFun should have short-circuited the shape to NoFraming.",
                )
        }
        body.endControlFlow()
    }

    private fun scalarHeaderBytes(shape: CodecShape): Int = shape.fields.sumOfFixedWireBytes().requireFixed("scalarHeaderBytes")

    private fun appendDecodeScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
    ) {
        val widthMatches = field.wireBytes == field.kind.width
        val explicitOrder = field.resolvedWireOrder != Endianness.Default
        // Natural-width Default — trust buffer.byteOrder.
        if (widthMatches && !explicitOrder) {
            body.addStatement("val %L = %L", field.name, naturalScalarReadExpr(field.kind))
            return
        }
        // Natural-width explicit Big/Little on a multi-byte scalar. Read at
        // the natural width and canonicalize via swapBytes when buffer.byteOrder
        // differs from the wire order. Matches the batched single-field code
        // shape — single readShort/readInt/readLong instead of N readUByte +
        // shift/or assembly. (1-byte scalars fall through to the manual path
        // since they have no byte order; the manual path emits a single byte
        // read for that case.)
        if (widthMatches && explicitOrder && field.kind.width > 1) {
            appendNaturalReadWithSwap(body, field)
            return
        }
        val bigEndian =
            when (field.resolvedWireOrder) {
                Endianness.Little -> false
                Endianness.Big, Endianness.Default -> true
            }
        appendManualScalarDecode(body, field, bigEndian)
    }

    private fun appendNaturalReadWithSwap(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
    ) {
        val canonicalOrder =
            if (field.resolvedWireOrder == Endianness.Big) "BIG_ENDIAN" else "LITTLE_ENDIAN"
        val readMethod =
            when (field.kind.width) {
                2 -> "readShort"
                4 -> "readInt"
                8 -> "readLong"
                else -> error("unsupported natural width ${field.kind.width}")
            }
        val rawVar = "${field.name}Raw"
        body.addStatement("val %L = buffer.%L()", rawVar, readMethod)
        when (field.kind) {
            ScalarKind.Short, ScalarKind.Int, ScalarKind.Long ->
                body.addStatement(
                    "val %L = if (buffer.byteOrder == %T.%L) %L else %M(%L)",
                    field.name,
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    rawVar,
                    SWAP_BYTES_MN,
                    rawVar,
                )
            ScalarKind.UShort, ScalarKind.UInt, ScalarKind.ULong -> {
                val toUnsigned =
                    when (field.kind) {
                        ScalarKind.UShort -> "toUShort"
                        ScalarKind.UInt -> "toUInt"
                        ScalarKind.ULong -> "toULong"
                        else -> error("unreachable")
                    }
                body.addStatement(
                    "val %L = (if (buffer.byteOrder == %T.%L) %L else %M(%L)).%L()",
                    field.name,
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    rawVar,
                    SWAP_BYTES_MN,
                    rawVar,
                    toUnsigned,
                )
            }
            ScalarKind.Float ->
                body.addStatement(
                    "val %L = Float.fromBits(if (buffer.byteOrder == %T.%L) %L else %M(%L))",
                    field.name,
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    rawVar,
                    SWAP_BYTES_MN,
                    rawVar,
                )
            ScalarKind.Double ->
                body.addStatement(
                    "val %L = Double.fromBits(if (buffer.byteOrder == %T.%L) %L else %M(%L))",
                    field.name,
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    rawVar,
                    SWAP_BYTES_MN,
                    rawVar,
                )
            ScalarKind.UByte, ScalarKind.Byte, ScalarKind.Boolean ->
                error("1-byte scalar should not take the natural-read-with-swap path")
        }
    }

    private fun appendManualScalarDecode(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
        bigEndian: Boolean,
    ) {
        val width = field.wireBytes
        if (field.kind == ScalarKind.UByte && width == 1) {
            body.addStatement("val %L = buffer.readUByte()", field.name)
            return
        }
        if (field.kind == ScalarKind.Byte && width == 1) {
            body.addStatement("val %L = buffer.readByte()", field.name)
            return
        }
        // Assemble the wire bytes into a wide unsigned accumulator, then narrow
        // to the field's declared kind. Signed kinds reinterpret the bit pattern
        // via toShort()/toInt()/toLong() (Kotlin's UShort/UInt/ULong .toX() are
        // bit-preserving). Float/Double go through fromBits().
        val accumulator = if (width >= 5) "toULong" else "toUInt"
        for (i in 0 until width) {
            body.addStatement(
                "val %L = buffer.readUByte().%L()",
                "${field.name}B$i",
                accumulator,
            )
        }
        val parts =
            (0 until width).map { i ->
                val byteName = "${field.name}B$i"
                val shiftBits = if (bigEndian) (width - 1 - i) * 8 else i * 8
                if (shiftBits == 0) byteName else "($byteName shl $shiftBits)"
            }
        val combined = if (parts.size == 1) parts[0] else "(${parts.joinToString(" or ")})"
        when (field.kind) {
            ScalarKind.UByte -> body.addStatement("val %L = %L.toUByte()", field.name, combined)
            ScalarKind.UShort -> body.addStatement("val %L = %L.toUShort()", field.name, combined)
            ScalarKind.UInt, ScalarKind.ULong -> body.addStatement("val %L = %L", field.name, combined)
            ScalarKind.Byte -> body.addStatement("val %L = %L.toByte()", field.name, combined)
            ScalarKind.Short -> body.addStatement("val %L = %L.toShort()", field.name, combined)
            ScalarKind.Int -> body.addStatement("val %L = %L.toInt()", field.name, combined)
            ScalarKind.Long -> body.addStatement("val %L = %L.toLong()", field.name, combined)
            ScalarKind.Float ->
                body.addStatement("val %L = Float.fromBits(%L.toInt())", field.name, combined)
            ScalarKind.Double ->
                body.addStatement("val %L = Double.fromBits(%L.toLong())", field.name, combined)
            ScalarKind.Boolean ->
                error("Boolean is pinned to the natural-read path; analyzeField rejects manual-path Boolean")
        }
    }

    private fun appendEncodeScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
        ownerSimpleName: String,
    ) {
        appendEncodeGuard(body, field, ownerSimpleName)
        val accessor = "value.${field.name}"
        val widthMatches = field.wireBytes == field.kind.width
        val explicitOrder = field.resolvedWireOrder != Endianness.Default
        if (widthMatches && !explicitOrder) {
            body.addStatement(naturalScalarWriteStatement(field.kind, accessor))
            return
        }
        // Natural-width explicit Big/Little on a multi-byte scalar. Convert
        // to the natural integer type, conditionally swapBytes, write.
        if (widthMatches && explicitOrder && field.kind.width > 1) {
            appendNaturalWriteWithSwap(body, field, accessor)
            return
        }
        val bigEndian =
            when (field.resolvedWireOrder) {
                Endianness.Little -> false
                Endianness.Big, Endianness.Default -> true
            }
        appendManualScalarEncode(body, field, accessor, bigEndian)
    }

    private fun appendNaturalWriteWithSwap(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
        accessor: String,
    ) {
        val canonicalOrder =
            if (field.resolvedWireOrder == Endianness.Big) "BIG_ENDIAN" else "LITTLE_ENDIAN"
        val writeMethod =
            when (field.kind.width) {
                2 -> "writeShort"
                4 -> "writeInt"
                8 -> "writeLong"
                else -> error("unsupported natural width ${field.kind.width}")
            }
        val rawExpr =
            when (field.kind) {
                ScalarKind.Short, ScalarKind.Int, ScalarKind.Long -> accessor
                ScalarKind.UShort -> "$accessor.toShort()"
                ScalarKind.UInt -> "$accessor.toInt()"
                ScalarKind.ULong -> "$accessor.toLong()"
                ScalarKind.Float -> "$accessor.toRawBits()"
                ScalarKind.Double -> "$accessor.toRawBits()"
                ScalarKind.UByte, ScalarKind.Byte, ScalarKind.Boolean ->
                    error("1-byte scalar should not take the natural-write-with-swap path")
            }
        val rawVar = "${field.name}Raw"
        body.addStatement("val %L = %L", rawVar, rawExpr)
        body.addStatement(
            "buffer.%L(if (buffer.byteOrder == %T.%L) %L else %M(%L))",
            writeMethod,
            BYTE_ORDER_CN,
            canonicalOrder,
            rawVar,
            SWAP_BYTES_MN,
            rawVar,
        )
    }

    private fun appendEncodeGuard(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
        ownerSimpleName: String,
    ) {
        if (field.wireBytes >= field.kind.width) return
        val accessor = "value.${field.name}"
        val (lhs, maxLit) =
            when (field.kind) {
                ScalarKind.ULong -> accessor to "((1uL shl ${8 * field.wireBytes}) - 1uL)"
                ScalarKind.UInt -> accessor to "((1u shl ${8 * field.wireBytes}) - 1u)"
                ScalarKind.UShort -> "$accessor.toUInt()" to "((1u shl ${8 * field.wireBytes}) - 1u)"
                // wireBytes < 1 is rejected by analyzeField
                ScalarKind.UByte -> return
                // signed kinds reject @WireBytes narrowing in analyzeField
                ScalarKind.Byte, ScalarKind.Short, ScalarKind.Int, ScalarKind.Long -> return
                // Float/Double also reject @WireBytes narrowing
                ScalarKind.Float, ScalarKind.Double -> return
                // analyzeField pins Boolean to natural width — never narrows
                ScalarKind.Boolean -> return
            }
        val maxValue = (1L shl (8 * field.wireBytes)) - 1
        body.beginControlFlow("if (%L > %L)", lhs, maxLit)
        body.addStatement(
            "throw %T(fieldPath = %S, reason = %S)",
            ENCODE_EXCEPTION_CN,
            "$ownerSimpleName.${field.name}",
            "value exceeds @WireBytes(${field.wireBytes}) range (max $maxValue)",
        )
        body.endControlFlow()
    }

    private fun appendManualScalarEncode(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
        accessor: String,
        bigEndian: Boolean,
    ) {
        val width = field.wireBytes
        if (field.kind == ScalarKind.UByte && width == 1) {
            body.addStatement("buffer.writeUByte(%L)", accessor)
            return
        }
        if (field.kind == ScalarKind.Byte && width == 1) {
            body.addStatement("buffer.writeByte(%L)", accessor)
            return
        }
        // Convert the field value to a wide unsigned accumulator (UInt for
        // ≤4 bytes, ULong for >4) and shift bytes off the high or low end
        // per wire order. Signed kinds reinterpret via .toUInt()/.toULong()
        // (bit-preserving). Float/Double go through toRawBits().
        val wide =
            when (field.kind) {
                ScalarKind.UByte -> "$accessor.toUInt()"
                ScalarKind.UShort -> "$accessor.toUInt()"
                ScalarKind.UInt -> accessor
                ScalarKind.ULong -> accessor
                ScalarKind.Byte -> "$accessor.toUByte().toUInt()"
                ScalarKind.Short -> "$accessor.toUShort().toUInt()"
                ScalarKind.Int -> "$accessor.toUInt()"
                ScalarKind.Long -> "$accessor.toULong()"
                ScalarKind.Float -> "$accessor.toRawBits().toUInt()"
                ScalarKind.Double -> "$accessor.toRawBits().toULong()"
                ScalarKind.Boolean ->
                    error("Boolean is pinned to the natural-write path; analyzeField rejects manual-path Boolean")
            }
        val maskLit = if (width >= 5) "0xFFuL" else "0xFFu"
        for (i in 0 until width) {
            val shiftBits = if (bigEndian) (width - 1 - i) * 8 else i * 8
            val expr =
                if (shiftBits == 0) {
                    "$wide and $maskLit"
                } else {
                    "($wide shr $shiftBits) and $maskLit"
                }
            body.addStatement("buffer.writeUByte((%L).toUByte())", expr)
        }
    }

    /**
     * Emit decode for a `@JvmInline value class` field
     * with a single supported-scalar inner. Reads the inner scalar at
     * natural width and constructs the value class via its primary
     * constructor. The local is named after the outer parameter so
     * dotted-form `@When` resolvers can address it as `<name>.<property>`.
     */
    private fun appendDecodeValueClassScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.ValueClassScalar,
    ) {
        // Honor the value class's declared @ProtocolMessage(wireOrder) the
        // same way plain Scalars honor @WireOrder / parent wireOrder: explicit
        // Big / Little wins over buffer.byteOrder. Multi-byte inner kinds get
        // the swapBytes fast path (matches the single-scalar Scalar emit).
        // 1-byte inner kinds have no byte order — the natural read suffices.
        if (field.valueClassWireOrder != Endianness.Default && field.innerKind.width > 1) {
            appendValueClassNaturalReadWithSwap(body, field)
            return
        }
        body.addStatement(
            "val %L = %T(%L)",
            field.name,
            field.valueClassType,
            naturalScalarReadExpr(field.innerKind),
        )
    }

    /**
     * Emit encode for a value-class field. Unwraps
     * via the inner property name and writes the inner scalar at
     * natural width — or, when the value class declares an explicit
     * wireOrder, takes the swap fast path so the wire bytes match
     * the value class's contract regardless of buffer.byteOrder.
     */
    private fun appendEncodeValueClassScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.ValueClassScalar,
    ) {
        if (field.valueClassWireOrder != Endianness.Default && field.innerKind.width > 1) {
            appendValueClassNaturalWriteWithSwap(body, field)
            return
        }
        body.addStatement(
            naturalScalarWriteStatement(
                field.innerKind,
                "value.${field.name}.${field.innerPropertyName}",
            ),
        )
    }

    private fun appendValueClassNaturalReadWithSwap(
        body: CodeBlock.Builder,
        field: FieldSpec.ValueClassScalar,
    ) {
        val canonicalOrder =
            if (field.valueClassWireOrder == Endianness.Big) "BIG_ENDIAN" else "LITTLE_ENDIAN"
        val readMethod =
            when (field.innerKind.width) {
                2 -> "readShort"
                4 -> "readInt"
                8 -> "readLong"
                else -> error("unsupported value-class inner width ${field.innerKind.width}")
            }
        val rawVar = "${field.name}Raw"
        body.addStatement("val %L = buffer.%L()", rawVar, readMethod)
        val toUnsigned =
            when (field.innerKind) {
                ScalarKind.UShort -> "toUShort"
                ScalarKind.UInt -> "toUInt"
                ScalarKind.ULong -> "toULong"
                else -> null
            }
        if (toUnsigned != null) {
            body.addStatement(
                "val %L = %T((if (buffer.byteOrder == %T.%L) %L else %M(%L)).%L())",
                field.name,
                field.valueClassType,
                BYTE_ORDER_CN,
                canonicalOrder,
                rawVar,
                SWAP_BYTES_MN,
                rawVar,
                toUnsigned,
            )
        } else {
            body.addStatement(
                "val %L = %T(if (buffer.byteOrder == %T.%L) %L else %M(%L))",
                field.name,
                field.valueClassType,
                BYTE_ORDER_CN,
                canonicalOrder,
                rawVar,
                SWAP_BYTES_MN,
                rawVar,
            )
        }
    }

    private fun appendValueClassNaturalWriteWithSwap(
        body: CodeBlock.Builder,
        field: FieldSpec.ValueClassScalar,
    ) {
        val canonicalOrder =
            if (field.valueClassWireOrder == Endianness.Big) "BIG_ENDIAN" else "LITTLE_ENDIAN"
        val writeMethod =
            when (field.innerKind.width) {
                2 -> "writeShort"
                4 -> "writeInt"
                8 -> "writeLong"
                else -> error("unsupported value-class inner width ${field.innerKind.width}")
            }
        val accessor = "value.${field.name}.${field.innerPropertyName}"
        val rawExpr =
            when (field.innerKind) {
                ScalarKind.Short, ScalarKind.Int, ScalarKind.Long -> accessor
                ScalarKind.UShort -> "$accessor.toShort()"
                ScalarKind.UInt -> "$accessor.toInt()"
                ScalarKind.ULong -> "$accessor.toLong()"
                ScalarKind.Float -> "$accessor.toRawBits()"
                ScalarKind.Double -> "$accessor.toRawBits()"
                else -> error("inner kind ${field.innerKind} cannot reach the swap path")
            }
        val rawVar = "${field.name}Raw"
        body.addStatement("val %L = %L", rawVar, rawExpr)
        body.addStatement(
            "buffer.%L(if (buffer.byteOrder == %T.%L) %L else %M(%L))",
            writeMethod,
            BYTE_ORDER_CN,
            canonicalOrder,
            rawVar,
            SWAP_BYTES_MN,
            rawVar,
        )
    }

    /**
     * Emit a `@When` decode block.
     *
     * Generated shape:
     * ```
     * val <name>: <NullableType> = if (<source>) <readExpr> else null
     * ```
     *
     * The source is a sibling `Boolean` local already in scope (decode visits
     * fields in constructor order, and analyzeConditionalField has verified
     * the source is declared before this field). `readExpr` is the natural-
     * width scalar read for the inner kind ( restricts inner to a
     * natural-width Scalar; widens to LengthPrefixedString).
     */
    private fun appendDecodeConditional(
        body: CodeBlock.Builder,
        field: FieldSpec.Conditional,
    ) {
        when (val inner = field.inner) {
            is ConditionalInner.Scalar -> {
                if (inner.wireOrder != Endianness.Default && inner.kind.width > 1) {
                    appendConditionalScalarSwapDecode(
                        body = body,
                        fieldName = field.name,
                        nullableTypeName = field.nullableTypeName,
                        condition = decodeConditionExpr(field.condition),
                        kind = inner.kind,
                        wireOrder = inner.wireOrder,
                        wrapValueClass = null,
                    )
                } else {
                    body.addStatement(
                        "val %L: %T = if (%L) %L else null",
                        field.name,
                        field.nullableTypeName,
                        decodeConditionExpr(field.condition),
                        naturalScalarReadExpr(inner.kind),
                    )
                }
            }
            is ConditionalInner.LengthPrefixedString -> {
                body.beginControlFlow(
                    "val %L: %T = if (%L)",
                    field.name,
                    field.nullableTypeName,
                    decodeConditionExpr(field.condition),
                )
                val lengthVar =
                    appendLengthPrefixedStringPrefixDecode(
                        body = body,
                        name = field.name,
                        ownerSimpleName = field.ownerSimpleName,
                        prefixWidth = inner.prefixWidth,
                        prefixWireOrder = inner.prefixWireOrder,
                    )
                body.addStatement("buffer.readString(%L, %T.UTF8)", lengthVar, CHARSET_CN)
                body.nextControlFlow("else")
                body.addStatement("null")
                body.endControlFlow()
            }
            is ConditionalInner.ValueClassScalar -> {
                // Wrap the natural-width inner read
                // in the value-class constructor (mirror of 's
                // non-conditional `appendDecodeValueClassScalar`).
                if (inner.valueClassWireOrder != Endianness.Default && inner.innerKind.width > 1) {
                    appendConditionalScalarSwapDecode(
                        body = body,
                        fieldName = field.name,
                        nullableTypeName = field.nullableTypeName,
                        condition = decodeConditionExpr(field.condition),
                        kind = inner.innerKind,
                        wireOrder = inner.valueClassWireOrder,
                        wrapValueClass = inner.valueClassType,
                    )
                } else {
                    body.addStatement(
                        "val %L: %T = if (%L) %T(%L) else null",
                        field.name,
                        field.nullableTypeName,
                        decodeConditionExpr(field.condition),
                        inner.valueClassType,
                        naturalScalarReadExpr(inner.innerKind),
                    )
                }
            }
            is ConditionalInner.LengthPrefixedUseCodecList -> {
                // `@When @LengthPrefixed @UseCodec(C) val
                // xs: List<E>?` — predicate-true branch runs the
                // inner-bag decode ( shared body). Else null.
                body.beginControlFlow(
                    "val %L: %T = if (%L)",
                    field.name,
                    field.nullableTypeName,
                    decodeConditionExpr(field.condition),
                )
                appendDecodeLengthPrefixedListBody(
                    body = body,
                    spec = inner.spec,
                    listLocalName = "${field.name}Value",
                    namespacePrefix = field.name,
                )
                body.addStatement("%LValue", field.name)
                body.nextControlFlow("else")
                body.addStatement("null")
                body.endControlFlow()
            }
            is ConditionalInner.LengthPrefixedUseCodecPayload -> {
                // Predicate-true branch reads the
                // fixed-width prefix, narrows `buffer.limit()` to position
                // + length, runs `<C>.decode`, restores the outer limit.
                // Mirrors [appendDecodeLengthPrefixedUseCodecPayload] but
                // wrapped in the conditional's `if (predicate)` gate.
                body.beginControlFlow(
                    "val %L: %T = if (%L)",
                    field.name,
                    field.nullableTypeName,
                    decodeConditionExpr(field.condition),
                )
                val lengthVar =
                    appendLengthPrefixedStringPrefixDecode(
                        body = body,
                        name = field.name,
                        ownerSimpleName = field.ownerSimpleName,
                        prefixWidth = inner.prefixWidth,
                        prefixWireOrder = inner.prefixWireOrder,
                    )
                val outerLimitVar = "__${field.name}OuterLimit"
                body.addStatement("val %L = buffer.limit()", outerLimitVar)
                body.addStatement("buffer.setLimit(buffer.position() + %L)", lengthVar)
                body.beginControlFlow("try")
                body.addStatement("%T.decode(buffer, context)", inner.payloadCodecType)
                body.nextControlFlow("finally")
                body.addStatement("buffer.setLimit(%L)", outerLimitVar)
                body.endControlFlow()
                body.nextControlFlow("else")
                body.addStatement("null")
                body.endControlFlow()
            }
            is ConditionalInner.UseCodecScalar -> {
                // `@When @UseCodec(C) val: T?`.
                // Predicate-true delegates to the codec object's
                // `decode(buffer, context)`, just like the non-conditional
                // `appendDecodeUseCodecScalar` path; predicate-false yields
                // null. The cascading-trailer cases use grammar-2
                // `remaining >= N` predicates so the read only runs when
                // the bounded buffer still has bytes to spend.
                body.addStatement(
                    "val %L: %T = if (%L) %T.decode(buffer, context) else null",
                    field.name,
                    field.nullableTypeName,
                    decodeConditionExpr(field.condition),
                    inner.codecType,
                )
            }
            is ConditionalInner.ProtocolMessageScalar -> {
                // Bare `@When val: T?` for a
                // `@ProtocolMessage` data class or sealed parent. The
                // codec class resolves to `${T.simpleName}Codec`
                // by-name; the call shape is identical to UseCodecScalar.
                body.addStatement(
                    "val %L: %T = if (%L) %T.decode(buffer, context) else null",
                    field.name,
                    field.nullableTypeName,
                    decodeConditionExpr(field.condition),
                    inner.codecType,
                )
            }
        }
    }

    /**
     * /3 — emit a `@When` encode block.
     *
     * Generated shape:
     * ```
     * if (value.<source>) {
     *     val <name>Value = value.<name> ?: throw EncodeException(...)
     *     <writeStatement(s) using `<name>Value`>
     * }
     * ```
     *
     * `<source>` is `value.<sibling>` for the simple form and
     * `value.<sibling>.<property>` for the dotted form. The body is
     * a single-line scalar write for `ConditionalInner.Scalar` and
     * the BackPatch length-prefix sequence for
     * `ConditionalInner.LengthPrefixedString` (.5).
     *
     * Predicate-false branch writes nothing (zero bytes for the slot, per
     * ). Predicate-true with `value.<name> == null`
     * throws `EncodeException` with field-path attribution (row 20).
     */
    private fun appendEncodeConditional(
        body: CodeBlock.Builder,
        field: FieldSpec.Conditional,
    ) {
        body.beginControlFlow("if (%L)", encodeConditionAccessor(field.condition, field.name))
        val localName = "${field.name}Value"
        body.addStatement(
            "val %L = value.%L ?: throw %T(fieldPath = %S, reason = %S)",
            localName,
            field.name,
            ENCODE_EXCEPTION_CN,
            "${field.ownerSimpleName}.${field.name}",
            "@When(\"${conditionExpressionLiteral(field.condition)}\") predicate is true but field is null",
        )
        when (val inner = field.inner) {
            is ConditionalInner.Scalar ->
                if (inner.wireOrder != Endianness.Default && inner.kind.width > 1) {
                    appendConditionalScalarSwapEncode(
                        body = body,
                        accessor = localName,
                        kind = inner.kind,
                        wireOrder = inner.wireOrder,
                        valueClassInnerProperty = null,
                    )
                } else {
                    body.addStatement(naturalScalarWriteStatement(inner.kind, localName))
                }
            is ConditionalInner.LengthPrefixedString ->
                appendLengthPrefixedStringEncode(
                    body = body,
                    name = field.name,
                    ownerSimpleName = field.ownerSimpleName,
                    prefixWidth = inner.prefixWidth,
                    prefixWireOrder = inner.prefixWireOrder,
                    accessor = localName,
                )
            is ConditionalInner.ValueClassScalar ->
                // Unwrap the value class via the
                // inner property name (mirror of 's
                // non-conditional `appendEncodeValueClassScalar`).
                if (inner.valueClassWireOrder != Endianness.Default && inner.innerKind.width > 1) {
                    appendConditionalScalarSwapEncode(
                        body = body,
                        accessor = localName,
                        kind = inner.innerKind,
                        wireOrder = inner.valueClassWireOrder,
                        valueClassInnerProperty = inner.innerPropertyName,
                    )
                } else {
                    body.addStatement(
                        naturalScalarWriteStatement(
                            inner.innerKind,
                            "$localName.${inner.innerPropertyName}",
                        ),
                    )
                }
            is ConditionalInner.LengthPrefixedUseCodecList ->
                appendEncodeConditionalLengthPrefixedUseCodecList(
                    body = body,
                    field = field,
                    inner = inner,
                    accessor = localName,
                )
            is ConditionalInner.LengthPrefixedUseCodecPayload ->
                // BackPatch shape mirroring
                // [appendEncodeLengthPrefixedUseCodecPayload]: reserve
                // prefix slot, run `<C>.encode`, measure body byte count,
                // patch the prefix in place, restore position. Reads the
                // smart-cast non-null `<name>Value` local established by
                // the outer `appendEncodeConditional`.
                appendEncodeConditionalLengthPrefixedUseCodecPayload(
                    body = body,
                    field = field,
                    inner = inner,
                    accessor = localName,
                )
            is ConditionalInner.UseCodecScalar ->
                // Mirror of the non-conditional
                // `appendEncodeUseCodecScalar`. Predicate-true with
                // smart-cast non-null `<name>Value` (established above)
                // delegates to the user codec's `encode`.
                body.addStatement(
                    "%T.encode(buffer, %L, context)",
                    inner.codecType,
                    localName,
                )
            is ConditionalInner.ProtocolMessageScalar ->
                // Same encode shape as
                // UseCodecScalar; the only thing that differs is how the
                // codec class name was resolved at analyze time.
                body.addStatement(
                    "%T.encode(buffer, %L, context)",
                    inner.codecType,
                    localName,
                )
        }
        body.endControlFlow()
    }

    // Shared helper for the @When + explicit-wireOrder case. Generates a
    // block-expression if/else where the `if` arm reads the natural-width
    // wire value and canonicalizes via swapBytes; matches the contract
    // explicit wire order should beat buffer.byteOrder, mirroring the
    // non-conditional Scalar / ValueClassScalar swap path. `wrapValueClass`
    // routes the swapped value through the value class's constructor when
    // present (ValueClassScalar conditional path).
    private fun appendConditionalScalarSwapDecode(
        body: CodeBlock.Builder,
        fieldName: String,
        nullableTypeName: TypeName,
        condition: String,
        kind: ScalarKind,
        wireOrder: Endianness,
        wrapValueClass: ClassName?,
    ) {
        val canonicalOrder =
            if (wireOrder == Endianness.Big) "BIG_ENDIAN" else "LITTLE_ENDIAN"
        val readMethod =
            when (kind.width) {
                2 -> "readShort"
                4 -> "readInt"
                8 -> "readLong"
                else -> error("unsupported conditional width ${kind.width}")
            }
        val rawVar = "${fieldName}Raw"
        body.beginControlFlow(
            "val %L: %T = if (%L)",
            fieldName,
            nullableTypeName,
            condition,
        )
        body.addStatement("val %L = buffer.%L()", rawVar, readMethod)
        // Emit the swap + cast (+ optional value-class wrap) as the if-block's
        // value expression via KotlinPoet placeholders so ByteOrder and
        // swapBytes resolve through the file's imports (not as FQNs).
        val unsignedCast =
            when (kind) {
                ScalarKind.UShort -> "toUShort"
                ScalarKind.UInt -> "toUInt"
                ScalarKind.ULong -> "toULong"
                else -> null
            }
        when {
            wrapValueClass != null && unsignedCast != null ->
                body.addStatement(
                    "%T((if (buffer.byteOrder == %T.%L) %L else %M(%L)).%L())",
                    wrapValueClass,
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    rawVar,
                    SWAP_BYTES_MN,
                    rawVar,
                    unsignedCast,
                )
            wrapValueClass != null ->
                body.addStatement(
                    "%T(if (buffer.byteOrder == %T.%L) %L else %M(%L))",
                    wrapValueClass,
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    rawVar,
                    SWAP_BYTES_MN,
                    rawVar,
                )
            unsignedCast != null ->
                body.addStatement(
                    "(if (buffer.byteOrder == %T.%L) %L else %M(%L)).%L()",
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    rawVar,
                    SWAP_BYTES_MN,
                    rawVar,
                    unsignedCast,
                )
            kind == ScalarKind.Float ->
                body.addStatement(
                    "Float.fromBits(if (buffer.byteOrder == %T.%L) %L else %M(%L))",
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    rawVar,
                    SWAP_BYTES_MN,
                    rawVar,
                )
            kind == ScalarKind.Double ->
                body.addStatement(
                    "Double.fromBits(if (buffer.byteOrder == %T.%L) %L else %M(%L))",
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    rawVar,
                    SWAP_BYTES_MN,
                    rawVar,
                )
            else ->
                body.addStatement(
                    "if (buffer.byteOrder == %T.%L) %L else %M(%L)",
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    rawVar,
                    SWAP_BYTES_MN,
                    rawVar,
                )
        }
        body.nextControlFlow("else")
        body.addStatement("null")
        body.endControlFlow()
    }

    private fun appendConditionalScalarSwapEncode(
        body: CodeBlock.Builder,
        accessor: String,
        kind: ScalarKind,
        wireOrder: Endianness,
        valueClassInnerProperty: String?,
    ) {
        val canonicalOrder =
            if (wireOrder == Endianness.Big) "BIG_ENDIAN" else "LITTLE_ENDIAN"
        val writeMethod =
            when (kind.width) {
                2 -> "writeShort"
                4 -> "writeInt"
                8 -> "writeLong"
                else -> error("unsupported conditional width ${kind.width}")
            }
        val resolvedAccessor =
            if (valueClassInnerProperty != null) "$accessor.$valueClassInnerProperty" else accessor
        val rawExpr =
            when (kind) {
                ScalarKind.Short, ScalarKind.Int, ScalarKind.Long -> resolvedAccessor
                ScalarKind.UShort -> "$resolvedAccessor.toShort()"
                ScalarKind.UInt -> "$resolvedAccessor.toInt()"
                ScalarKind.ULong -> "$resolvedAccessor.toLong()"
                ScalarKind.Float -> "$resolvedAccessor.toRawBits()"
                ScalarKind.Double -> "$resolvedAccessor.toRawBits()"
                ScalarKind.UByte, ScalarKind.Byte, ScalarKind.Boolean ->
                    error("1-byte kind should not take the conditional swap path")
            }
        val rawVar = "${accessor}Raw"
        body.addStatement("val %L = %L", rawVar, rawExpr)
        body.addStatement(
            "buffer.%L(if (buffer.byteOrder == %T.%L) %L else %M(%L))",
            writeMethod,
            BYTE_ORDER_CN,
            canonicalOrder,
            rawVar,
            SWAP_BYTES_MN,
            rawVar,
        )
    }

    /**
     * Encode a conditional `@LengthPrefixed @UseCodec(C)
     * val xs: List<E>?`. Audit-2a deduplication: delegates to the shared
     * `appendEncodeLengthPrefixedListBody` helper.
     *
     * `accessor` is the smart-cast non-null local established by the
     * outer `appendEncodeConditional` (`<name>Value`). The
     * non-conditional emit reads `value.<name>` instead — same shape,
     * different read expression (the helper takes `accessor` as a
     * parameter to absorb the difference).
     */
    private fun appendEncodeConditionalLengthPrefixedUseCodecList(
        body: CodeBlock.Builder,
        field: FieldSpec.Conditional,
        inner: ConditionalInner.LengthPrefixedUseCodecList,
        accessor: String,
    ) {
        appendEncodeLengthPrefixedListBody(
            body = body,
            spec = inner.spec,
            accessor = accessor,
            namespacePrefix = field.name,
        )
    }

    /**
     * Encode a conditional `@LengthPrefixed
     * @UseCodec(C) val: T?` where T : Payload. BackPatch shape mirroring
     * [appendEncodeLengthPrefixedUseCodecPayload]: reserve prefix slot,
     * run `<C>.encode(buffer, accessor, context)` against the
     * accumulating buffer, measure body byte count from the position
     * delta, patch the prefix, restore position past the body.
     *
     * `accessor` is the smart-cast non-null local established by the
     * outer `appendEncodeConditional` (`<name>Value`). The non-
     * conditional emit reads `value.<name>` directly.
     */
    private fun appendEncodeConditionalLengthPrefixedUseCodecPayload(
        body: CodeBlock.Builder,
        field: FieldSpec.Conditional,
        inner: ConditionalInner.LengthPrefixedUseCodecPayload,
        accessor: String,
    ) {
        val sizePosVar = "${field.name}SizePosition"
        val bodyStartVar = "${field.name}BodyStart"
        val endPosVar = "${field.name}EndPosition"
        val byteCountVar = "${field.name}ByteCount"
        body.addStatement("val %L = buffer.position()", sizePosVar)
        body.addStatement("buffer.position(%L + %L)", sizePosVar, inner.prefixWidth)
        body.addStatement("val %L = buffer.position()", bodyStartVar)
        body.addStatement("%T.encode(buffer, %L, context)", inner.payloadCodecType, accessor)
        body.addStatement("val %L = buffer.position()", endPosVar)
        body.addStatement("val %L = %L - %L", byteCountVar, endPosVar, bodyStartVar)
        if (inner.prefixWidth < 4) {
            val maxValue = (1L shl (inner.prefixWidth * 8)) - 1
            val widthName =
                when (inner.prefixWidth) {
                    1 -> "Byte"
                    2 -> "Short"
                    else -> error("unreachable: prefixWidth must be 1, 2, or 4")
                }
            body.beginControlFlow("if (%L > %L)", byteCountVar, maxValue)
            body.addStatement(
                "throw %T(fieldPath = %S, reason = %P)",
                ENCODE_EXCEPTION_CN,
                "${field.ownerSimpleName}.${field.name}",
                "encoded payload byte length \${$byteCountVar} exceeds " +
                    "@LengthPrefixed(LengthPrefix.$widthName) max $maxValue",
            )
            body.endControlFlow()
        }
        body.addStatement("buffer.position(%L)", sizePosVar)
        val prefixVar = "${field.name}Prefix"
        body.addStatement("val %L = %L.toUInt()", prefixVar, byteCountVar)
        appendBufferPrefixEncode(body, prefixVar, inner.prefixWidth, inner.prefixWireOrder)
        body.addStatement("buffer.position(%L)", endPosVar)
    }

    /**
     * Decode-side predicate accessor. The decode visitor introduces
     * each prior field as a local in scope (constructor order), so
     * the simple form references the local directly and the dotted
     * form chains the property off the value-class local.
     */
    private fun decodeConditionExpr(condition: ConditionRef): String =
        when (condition) {
            is ConditionRef.Sibling -> condition.name
            is ConditionRef.ValueClassProperty -> "${condition.siblingName}.${condition.propertyName}"
            is ConditionRef.RemainingCmp ->
                "buffer.remaining() ${condition.op.symbol} ${condition.threshold}"
        }

    /**
     * Encode-side predicate accessor. Encode reads from the message
     * value, so all paths start at `value.`. Simple form is
     * `value.<sibling>`; dotted form is `value.<sibling>.<property>`.
     *
     * Grammar 2 (`remaining <op> <int>`) encode semantics differ:
     * cascading-trailer fields are gated on whether the caller provided
     * a non-null value (the encode-side has no buffer-`remaining()` to
     * test against — the slot is included iff the field is set). Caller
     * is responsible for keeping the cascade consistent (don't set a
     * later trailer if an earlier one is null).
     */
    private fun encodeConditionAccessor(
        condition: ConditionRef,
        fieldName: String,
    ): String =
        when (condition) {
            is ConditionRef.Sibling -> "value.${condition.name}"
            is ConditionRef.ValueClassProperty -> "value.${condition.siblingName}.${condition.propertyName}"
            is ConditionRef.RemainingCmp -> "value.$fieldName != null"
        }

    /**
     * Reconstruct the original `@When("...")` expression literal
     * for use in `EncodeException` field-path messages (row 20).
     */
    private fun conditionExpressionLiteral(condition: ConditionRef): String =
        when (condition) {
            is ConditionRef.Sibling -> condition.name
            is ConditionRef.ValueClassProperty -> "${condition.siblingName}.${condition.propertyName}"
            is ConditionRef.RemainingCmp ->
                "remaining ${condition.op.symbol} ${condition.threshold}"
        }

    /**
     * Value-class inner-scalar peek. Used for predicate-source
     * reconstruction in `@When` and for discriminator reconstruction in
     * `@DispatchOn`.
     *
     * `offsetExpr` is interpolated into
     * `stream.peekByte(baseOffset + <expr>)`; callers with a fixed
     * offset pass `"$N"`, sequential walk callers pass the running
     * offset variable (`"__offset"`).
     *
     * Single-byte kinds (`UByte` / `Byte` / `Boolean`) read directly.
     * Multi-byte unsigned kinds (`UShort` / `UInt`) assemble bytes
     * BE/LE per `wireOrder`. `ULong` and signed multi-byte kinds
     * aren't required by any in-scope vector and would need parallel
     * paths (ULong promotion / signed sign-extension).
     */
    private fun appendPeekFixedScalar(
        body: CodeBlock.Builder,
        kind: ScalarKind,
        targetVar: String,
        offsetExpr: String,
        wireOrder: Endianness = Endianness.Default,
    ) {
        when (kind) {
            ScalarKind.UByte ->
                body.addStatement(
                    "val %L = stream.peekByte(baseOffset + %L).toUByte()",
                    targetVar,
                    offsetExpr,
                )
            ScalarKind.Byte ->
                body.addStatement(
                    "val %L = stream.peekByte(baseOffset + %L)",
                    targetVar,
                    offsetExpr,
                )
            ScalarKind.Boolean ->
                body.addStatement(
                    "val %L = stream.peekByte(baseOffset + %L) != 0.toByte()",
                    targetVar,
                    offsetExpr,
                )
            ScalarKind.UShort, ScalarKind.UInt -> {
                val width = kind.wireWidth.requireFixed("appendPeekFixedScalar")
                val bigEndian =
                    when (wireOrder) {
                        Endianness.Big, Endianness.Default -> true
                        Endianness.Little -> false
                    }
                for (i in 0 until width) {
                    val byteOffset = if (i == 0) offsetExpr else "$offsetExpr + $i"
                    body.addStatement(
                        "val %L = stream.peekByte(baseOffset + %L).toInt() and 0xFF",
                        "${targetVar}B$i",
                        byteOffset,
                    )
                }
                val parts =
                    (0 until width).map { i ->
                        val byteName = "${targetVar}B$i"
                        val shiftBits = if (bigEndian) (width - 1 - i) * 8 else i * 8
                        if (shiftBits == 0) byteName else "($byteName shl $shiftBits)"
                    }
                val narrow =
                    when (kind) {
                        ScalarKind.UShort -> "(%L).toUInt().toUShort()"
                        ScalarKind.UInt -> "(%L).toUInt()"
                        else -> error("unreachable")
                    }
                body.addStatement("val %L = $narrow", targetVar, parts.joinToString(" or "))
            }
            ScalarKind.ULong, ScalarKind.Short, ScalarKind.Int, ScalarKind.Long,
            ScalarKind.Float, ScalarKind.Double,
            ->
                error(
                    "peek-side reconstruction for value-class inner kind $kind not implemented; " +
                        "the analyzer should have rejected this shape until the wider peek path lands.",
                )
        }
    }

    private fun appendDecodeLengthPrefixed(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthPrefixedMessage,
    ) {
        val prefixVar = "${field.name}Prefix"
        appendBufferPrefixDecode(body, prefixVar, field.prefixWidth, field.prefixWireOrder)
        body.beginControlFlow("if (%L > Int.MAX_VALUE.toUInt())", prefixVar)
        body.addStatement(
            "throw %T(fieldPath = %S, bufferPosition = -1, expected = %S, actual = %L.toString())",
            DECODE_EXCEPTION_CN,
            "${field.ownerSimpleName}.${field.name}",
            "length prefix <= \${Int.MAX_VALUE}",
            prefixVar,
        )
        body.endControlFlow()
        val resolvedVar = "${field.name}Length"
        body.addStatement("val %L = %L.toInt()", resolvedVar, prefixVar)
        val outerVar = "${field.name}OuterLimit"
        body.addStatement("val %L = buffer.limit()", outerVar)
        body.addStatement("buffer.setLimit(buffer.position() + %L)", resolvedVar)
        body.beginControlFlow("val %L = try", field.name)
        body.addStatement("%T.decode(buffer, context)", field.codecType)
        body.nextControlFlow("finally")
        body.addStatement("buffer.setLimit(%L)", outerVar)
        body.endControlFlow()
    }

    private fun appendBufferPrefixDecode(
        body: CodeBlock.Builder,
        targetVar: String,
        prefixWidth: Int,
        wireOrder: Endianness,
    ) {
        if (prefixWidth == 1) {
            body.addStatement("val %L = buffer.readUByte().toUInt()", targetVar)
            return
        }
        val bigEndian =
            when (wireOrder) {
                Endianness.Big -> true
                Endianness.Little -> false
                Endianness.Default -> true
            }
        for (i in 0 until prefixWidth) {
            body.addStatement(
                "val %L = buffer.readUByte().toUInt()",
                "${targetVar}B$i",
            )
        }
        val parts =
            (0 until prefixWidth).map { i ->
                val byteName = "${targetVar}B$i"
                val shiftBits = if (bigEndian) (prefixWidth - 1 - i) * 8 else i * 8
                if (shiftBits == 0) byteName else "($byteName shl $shiftBits)"
            }
        body.addStatement("val %L = (%L)", targetVar, parts.joinToString(" or "))
    }

    private fun appendEncodeLengthPrefixed(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthPrefixedMessage,
    ) {
        val prefixVar = "${field.name}Prefix"
        body.addStatement(
            "val %L = (%T.wireSize(value.%L, context) as %T.Exact).bytes.toUInt()",
            prefixVar,
            field.codecType,
            field.name,
            WIRE_SIZE_CN,
        )
        appendBufferPrefixEncode(body, prefixVar, field.prefixWidth, field.prefixWireOrder)
        body.addStatement("%T.encode(buffer, value.%L, context)", field.codecType, field.name)
    }

    private fun appendDecodeLengthPrefixedString(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthPrefixedString,
    ) {
        val lengthVar =
            appendLengthPrefixedStringPrefixDecode(
                body = body,
                name = field.name,
                ownerSimpleName = field.ownerSimpleName,
                prefixWidth = field.prefixWidth,
                prefixWireOrder = field.prefixWireOrder,
            )
        body.addStatement(
            "val %L = buffer.readString(%L, %T.UTF8)",
            field.name,
            lengthVar,
            CHARSET_CN,
        )
    }

    /**
     * Emit the prefix read + Int.MAX_VALUE guard + length
     * Int conversion shared by length-prefixed-string field decode
     * and the conditional `@LengthPrefixed @When` decode path.
     * Returns the local variable name holding the resolved
     * (Int-typed) length.
     */
    private fun appendLengthPrefixedStringPrefixDecode(
        body: CodeBlock.Builder,
        name: String,
        ownerSimpleName: String,
        prefixWidth: Int,
        prefixWireOrder: Endianness,
    ): String {
        val prefixVar = "${name}Prefix"
        appendBufferPrefixDecode(body, prefixVar, prefixWidth, prefixWireOrder)
        body.beginControlFlow("if (%L > Int.MAX_VALUE.toUInt())", prefixVar)
        body.addStatement(
            "throw %T(fieldPath = %S, bufferPosition = -1, expected = %S, actual = %L.toString())",
            DECODE_EXCEPTION_CN,
            "$ownerSimpleName.$name",
            "length prefix <= \${Int.MAX_VALUE}",
            prefixVar,
        )
        body.endControlFlow()
        val lengthVar = "${name}Length"
        body.addStatement("val %L = %L.toInt()", lengthVar, prefixVar)
        return lengthVar
    }

    private fun appendEncodeLengthPrefixedString(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthPrefixedString,
    ) {
        appendLengthPrefixedStringEncode(
            body = body,
            name = field.name,
            ownerSimpleName = field.ownerSimpleName,
            prefixWidth = field.prefixWidth,
            prefixWireOrder = field.prefixWireOrder,
            accessor = "value.${field.name}",
        )
    }

    /**
     * Shared BackPatch encoder for length-prefixed-string
     * fields and the conditional `@LengthPrefixed @When` encode
     * path.
     *
     * `accessor` is the expression that yields the string value;
     * field-form callers pass `value.<name>`, conditional-form
     * callers pass the locally-bound non-null value (already
     * null-checked at the conditional gate). `name` is used for
     * generated-variable naming and the field-path attribution
     * literal.
     */
    private fun appendLengthPrefixedStringEncode(
        body: CodeBlock.Builder,
        name: String,
        ownerSimpleName: String,
        prefixWidth: Int,
        prefixWireOrder: Endianness,
        accessor: String,
    ) {
        // BackPatch pattern: reserve prefix slot, write
        // the body via the runtime's UTF-8 path, measure byte count from the
        // position delta, patch the prefix in place, restore position past the
        // body. The runtime's `writeString(text, Charset.UTF8)` is zero-`ByteArray`
        // on JVM / Apple / JS; the WASM and nonJvm `writeString` paths still
        // allocate one ByteArray per call (, deferred to a
        // separate runtime task).
        val sizePosVar = "${name}SizePosition"
        val bodyStartVar = "${name}BodyStart"
        val endPosVar = "${name}EndPosition"
        val byteCountVar = "${name}ByteCount"
        body.addStatement("val %L = buffer.position()", sizePosVar)
        body.addStatement("buffer.position(%L + %L)", sizePosVar, prefixWidth)
        body.addStatement("val %L = buffer.position()", bodyStartVar)
        body.addStatement(
            "buffer.writeString(%L, %T.UTF8)",
            accessor,
            CHARSET_CN,
        )
        body.addStatement("val %L = buffer.position()", endPosVar)
        body.addStatement("val %L = %L - %L", byteCountVar, endPosVar, bodyStartVar)
        // Runtime overflow guard. For 4-byte prefixes the max (UInt.MAX_VALUE =
        // 2^32-1) exceeds Int.MAX_VALUE, so a position-delta byte count can never
        // overflow it — the check would be dead code.
        if (prefixWidth < 4) {
            val maxValue = (1L shl (prefixWidth * 8)) - 1
            val widthName =
                when (prefixWidth) {
                    1 -> "Byte"
                    2 -> "Short"
                    else -> error("unreachable: prefixWidth must be 1, 2, or 4")
                }
            body.beginControlFlow("if (%L > %L)", byteCountVar, maxValue)
            body.addStatement(
                "throw %T(fieldPath = %S, reason = %P)",
                ENCODE_EXCEPTION_CN,
                "$ownerSimpleName.$name",
                "UTF-8 byte length \${$byteCountVar} exceeds @LengthPrefixed(LengthPrefix.$widthName) max $maxValue",
            )
            body.endControlFlow()
        }
        body.addStatement("buffer.position(%L)", sizePosVar)
        val prefixVar = "${name}Prefix"
        body.addStatement("val %L = %L.toUInt()", prefixVar, byteCountVar)
        appendBufferPrefixEncode(body, prefixVar, prefixWidth, prefixWireOrder)
        body.addStatement("buffer.position(%L)", endPosVar)
    }

    /**
     * Emit decode for `@LengthFrom("siblingField")
     * val: String`. The sibling local is in scope (decode visits
     * fields in constructor order, and analyzeLengthFromStringField
     * has verified the sibling is declared before this field).
     *
     * Generated shape:
     * ```
     * <Int.MAX_VALUE guard for sibling kinds whose range exceeds Int>
     * val <name>Length = <sibling>.toInt()
     * val <name> = buffer.readString(<name>Length, Charset.UTF8)
     * ```
     *
     * The guard is skipped for `Byte` / `Short` / `Int` / `UByte` /
     * `UShort`, whose values fit in a non-negative `Int`. `UInt`,
     * `ULong`, and `Long` need the runtime guard because their range
     * exceeds `Int.MAX_VALUE`.
     */
    private fun appendDecodeLengthFromString(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthFromString,
    ) {
        // Simple form needs an Int.MAX_VALUE guard for kinds whose
        // range exceeds Int (UInt / ULong / Long); the dotted form's
        // property returns Int directly so no guard is needed.
        if (field.source is LengthSource.Sibling) {
            appendLengthFromIntMaxGuard(
                body = body,
                siblingAccessor = field.source.siblingName,
                siblingKind = field.source.siblingKind,
                ownerSimpleName = field.ownerSimpleName,
                fieldName = field.name,
            )
        }
        // Inline the sibling/property accessor rather than binding
        // an intermediate. A `${field.name}Length` intermediate would
        // shadow the sibling local when the user names the carrier
        // `<bound>Length` — a natural Kotlin convention that the
        // generated code must not break.
        body.addStatement(
            "val %L = buffer.readString(%L, %T.UTF8)",
            field.name,
            field.source.decodeAccessor(),
            CHARSET_CN,
        )
    }

    /**
     * Emit encode for `@LengthFrom("siblingField")
     * val: String`. The sibling field has already been encoded by
     * the prior field's emit step; this step writes only the body.
     * The user is responsible for keeping `value.<sibling>`
     * consistent with `value.<name>.encodeToByteArray().size`; the
     * codec trusts that contract (a runtime cross-check would
     * allocate per row 16).
     */
    private fun appendEncodeLengthFromString(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthFromString,
    ) {
        body.addStatement(
            "buffer.writeString(value.%L, %T.UTF8)",
            field.name,
            CHARSET_CN,
        )
    }

    /**
     * Emit decode for `@LengthFrom("siblingField")
     * val: List<T>`. Bounds the buffer via `setLimit` to the
     * sibling-derived byte count, loops reading elements via the
     * element codec until the bounded position is reached, restores
     * the outer limit. The `try`/`finally` guarantees limit
     * restoration even if an element decode throws.
     *
     * Generated shape:
     * ```
     * <Int.MAX_VALUE guard for the sibling kind, if needed>
     * val <name>Bytes = <sibling>.toInt()
     * val <name>OuterLimit = buffer.limit()
     * buffer.setLimit(buffer.position() + <name>Bytes)
     * val <name> = mutableListOf<ElementType>()
     * try {
     *     while (buffer.position() < buffer.limit()) {
     *         <name> += ElementCodec.decode(buffer, context)
     *     }
     * } finally {
     *     buffer.setLimit(<name>OuterLimit)
     * }
     * ```
     */
    private fun appendDecodeLengthFromList(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthFromList,
    ) {
        if (field.source is LengthSource.Sibling) {
            appendLengthFromIntMaxGuard(
                body = body,
                siblingAccessor = field.source.siblingName,
                siblingKind = field.source.siblingKind,
                ownerSimpleName = field.ownerSimpleName,
                fieldName = field.name,
            )
        }
        val bytesVar = "${field.name}Bytes"
        val outerLimitVar = "${field.name}OuterLimit"
        body.addStatement("val %L = %L", bytesVar, field.source.decodeAccessor())
        body.addStatement("val %L = buffer.limit()", outerLimitVar)
        body.addStatement("buffer.setLimit(buffer.position() + %L)", bytesVar)
        body.addStatement("val %L = mutableListOf<%T>()", field.name, field.elementClassName)
        body.beginControlFlow("try")
        body.beginControlFlow("while (buffer.position() < buffer.limit())")
        body.addStatement("%L += %T.decode(buffer, context)", field.name, field.elementCodecClassName)
        body.endControlFlow()
        body.nextControlFlow("finally")
        body.addStatement("buffer.setLimit(%L)", outerLimitVar)
        body.endControlFlow()
    }

    /**
     * Emit encode for `@LengthFrom("siblingField")
     * val: List<T>`. Iterates the list and writes each element via
     * the element codec. The user is responsible for keeping
     * `value.<sibling>` consistent with the sum of element wire
     * sizes (same row-16 trust contract as the LengthFromString
     * encode path).
     */
    private fun appendEncodeLengthFromList(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthFromList,
    ) {
        body.beginControlFlow("for (__elem in value.%L)", field.name)
        body.addStatement("%T.encode(buffer, __elem, context)", field.elementCodecClassName)
        body.endControlFlow()
    }

    /**
     * (issue #151 part 1) — emit decode for
     * `@LengthFrom("siblingField") val: T : @ProtocolMessage`. Bounds the
     * buffer via `setLimit` to the sibling-derived end, delegates to
     * `<TCodec>.decode(buffer, context)`, restores the outer limit in a
     * `try`/`finally`. Same outer-limit-restore template as
     * [appendDecodeLengthFromList].
     *
     * Generated shape:
     * ```
     * <Int.MAX_VALUE guard for the sibling kind, if needed>
     * val <name>Bytes = <sibling>.toInt()
     * val <name>OuterLimit = buffer.limit()
     * buffer.setLimit(buffer.position() + <name>Bytes)
     * val <name> = try {
     *     <TCodec>.decode(buffer, context)
     * } finally {
     *     buffer.setLimit(<name>OuterLimit)
     * }
     * ```
     */
    private fun appendDecodeLengthFromMessage(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthFromMessage,
    ) {
        if (field.source is LengthSource.Sibling) {
            appendLengthFromIntMaxGuard(
                body = body,
                siblingAccessor = field.source.siblingName,
                siblingKind = field.source.siblingKind,
                ownerSimpleName = field.ownerSimpleName,
                fieldName = field.name,
            )
        }
        val bytesVar = "${field.name}Bytes"
        val outerLimitVar = "${field.name}OuterLimit"
        body.addStatement("val %L = %L", bytesVar, field.source.decodeAccessor())
        body.addStatement("val %L = buffer.limit()", outerLimitVar)
        body.addStatement("buffer.setLimit(buffer.position() + %L)", bytesVar)
        body.beginControlFlow("val %L = try", field.name)
        body.addStatement("%T.decode(buffer, context)", field.codecType)
        body.nextControlFlow("finally")
        body.addStatement("buffer.setLimit(%L)", outerLimitVar)
        body.endControlFlow()
    }

    /**
     * Emit encode for `@LengthFrom("siblingField") val: T:
     * @ProtocolMessage`. Single delegation to `<TCodec>.encode`. The
     * sibling field has already been encoded by the prior field's emit
     * step; the user is responsible for keeping `value.<sibling>`
     * consistent with `<TCodec>.wireSize(value.<name>, context).bytes`.
     */
    private fun appendEncodeLengthFromMessage(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthFromMessage,
    ) {
        body.addStatement(
            "%T.encode(buffer, value.%L, context)",
            field.codecType,
            field.name,
        )
    }

    /**
     * Emit decode for
     * `@RemainingBytes @UseCodec(C::class) val: P`. Delegates to the
     * user-supplied `C.decode(buffer, context)` against whatever
     * `buffer.limit()` already says — same caller-bounds-buffer contract
     * as the other `@RemainingBytes` shapes. The outer dispatcher (slice
     * 10d for MQTT) sets the limit before calling this codec.
     */
    private fun appendDecodeRemainingBytesPayload(
        body: CodeBlock.Builder,
        field: FieldSpec.RemainingBytesPayload,
    ) {
        if (field.reservedTrailingBytes == 0) {
            body.addStatement(
                "val %L = %L.decode(buffer, context)",
                field.name,
                field.source.codecReceiver(),
            )
            return
        }
        // Non-terminal RemainingBytesPayload. Narrow the
        // buffer's limit to leave the trailing FixedSize fields in the
        // outer-limit region; restore the outer limit in a try/finally
        // so the trailing field emits run against the original limit.
        val outerLimitVar = "__${field.name}OuterLimit"
        body.addStatement("val %L = buffer.limit()", outerLimitVar)
        body.addStatement(
            "buffer.setLimit(%L - %L)",
            outerLimitVar,
            field.reservedTrailingBytes,
        )
        body.beginControlFlow("val %L = try", field.name)
        body.addStatement("%L.decode(buffer, context)", field.source.codecReceiver())
        body.nextControlFlow("finally")
        body.addStatement("buffer.setLimit(%L)", outerLimitVar)
        body.endControlFlow()
    }

    /**
     * Emit encode for
     * `@RemainingBytes @UseCodec(C::class) val: P`. Delegates to the
     * user-supplied `C.encode(buffer, value.<name>, context)`. No length
     * carrier on the wire — the user codec writes its bytes against the
     * buffer's current position and the trust contract (row 16) leaves
     * total-byte-count consistency to the outer dispatcher.
     */
    private fun appendEncodeRemainingBytesPayload(
        body: CodeBlock.Builder,
        field: FieldSpec.RemainingBytesPayload,
    ) {
        body.addStatement(
            "%L.encode(buffer, value.%L, context)",
            field.source.codecReceiver(),
            field.name,
        )
    }

    /**
     * `@RemainingBytes val: String` — decode reads UTF-8 bytes from the current
     * position to `buffer.limit()`. The caller (or an outer dispatcher) is
     * responsible for narrowing `buffer.limit()` to the bounded extent before
     * invoking decode; same caller-bounds-buffer contract as
     * [appendDecodeRemainingBytesPayload].
     */
    private fun appendDecodeRemainingBytesString(
        body: CodeBlock.Builder,
        field: FieldSpec.RemainingBytesString,
    ) {
        if (field.reservedTrailingBytes == 0) {
            body.addStatement(
                "val %L = buffer.readString(buffer.remaining(), %T.UTF8)",
                field.name,
                CHARSET_CN,
            )
            return
        }
        // Read the body byte count minus the reserved trailing
        // FixedSize bytes; the trailing field emits run normally after.
        body.addStatement(
            "val %L = buffer.readString(buffer.remaining() - %L, %T.UTF8)",
            field.name,
            field.reservedTrailingBytes,
            CHARSET_CN,
        )
    }

    /**
     * Encode counterpart for `@RemainingBytes val: String`. Writes the value's
     * UTF-8 byte representation. The encoded byte count is reported via
     * [appendBackPatchWireSize] (the parent message's wireSize collapses to
     * BackPatch because the trailing string's byte count isn't known up front
     * without re-encoding).
     */
    private fun appendEncodeRemainingBytesString(
        body: CodeBlock.Builder,
        field: FieldSpec.RemainingBytesString,
    ) {
        body.addStatement(
            "buffer.writeString(value.%L, %T.UTF8)",
            field.name,
            CHARSET_CN,
        )
    }

    /**
     * Emit decode for `@RemainingBytes val: List<T>` where
     * `T` is a `@ProtocolMessage data class`. Loops `while
     * (buffer.position() < buffer.limit())` reading each element via
     * the element's own codec. Caller-bounds-buffer contract: an outer
     * dispatcher (e.g. MQTT's fixed-header remaining-length variable-
     * length integer) sets `buffer.limit()` before delegating.
     *
     * Generated shape:
     * ```
     * val <name> = mutableListOf<ElementType>()
     * while (buffer.position() < buffer.limit()) {
     *     <name> += ElementCodec.decode(buffer, context)
     * }
     * ```
     */
    private fun appendDecodeRemainingBytesProtocolMessageList(
        body: CodeBlock.Builder,
        field: FieldSpec.RemainingBytesProtocolMessageList,
    ) {
        body.addStatement("val %L = mutableListOf<%T>()", field.name, field.elementClassName)
        if (field.reservedTrailingBytes == 0) {
            body.beginControlFlow("while (buffer.position() < buffer.limit())")
        } else {
            // Leave room for the trailing FixedSize fields.
            body.beginControlFlow(
                "while (buffer.position() < buffer.limit() - %L)",
                field.reservedTrailingBytes,
            )
        }
        body.addStatement("%L += %T.decode(buffer, context)", field.name, field.elementCodecClassName)
        body.endControlFlow()
    }

    /**
     * Emit encode for `@RemainingBytes val: List<T>` where
     * `T` is a `@ProtocolMessage data class`. Iterates the list and
     * writes each element via the element codec. The encoded byte
     * count is implicit in the outer protocol's framing — same row 16
     * trust contract as `LengthFromList`'s encode path.
     */
    private fun appendEncodeRemainingBytesProtocolMessageList(
        body: CodeBlock.Builder,
        field: FieldSpec.RemainingBytesProtocolMessageList,
    ) {
        body.beginControlFlow("for (__elem in value.%L)", field.name)
        body.addStatement("%T.encode(buffer, __elem, context)", field.elementCodecClassName)
        body.endControlFlow()
    }

    /**
     * Does this field narrow `buffer.limit` mid-decode?
     * A `UseCodecScalar` does iff its codec target implements
     * `BoundingLengthCodec`. Used by [buildDecodeFun] to decide whether
     * subsequent fields run inside `try { ... } finally {
     * setLimit(__OuterLimit) }` and by the shape-construction
     * uniqueness check.
     */
    private fun FieldSpec.isBoundingShape(): Boolean =
        when (this) {
            is FieldSpec.UseCodecScalar -> isBounding
            else -> false
        }

    /**
     * `true` for `Conditional` fields whose wire presence can't be predicted
     * from a stream-only peek walk. Four cases:
     *  - grammar-2 `remaining <op> <int>` predicates (depend on the bounded
     *    decode buffer's `remaining()` after upstream `applyBound`),
     *  - inner is `LengthPrefixedUseCodecList` (variable-length bag),
     *  - inner is `UseCodecScalar` (opaque codec wire width),
     *  - inner is `ProtocolMessageScalar` (variable-width sealed dispatch /
     * nested message — ).
     *
     * v5 ack peek escapes this collapse because the bounding RL field
     * upstream is handled by `appendPeekUseCodecScalar` before the
     * sequential walk reaches the conditional.
     */
    private fun FieldSpec.isPeekCollapsingConditional(): Boolean =
        this is FieldSpec.Conditional &&
            (
                condition is ConditionRef.RemainingCmp ||
                    inner is ConditionalInner.LengthPrefixedUseCodecList ||
                    inner is ConditionalInner.UseCodecScalar ||
                    inner is ConditionalInner.ProtocolMessageScalar
            )

    /**
     * Emit decode for bare `@UseCodec val: <scalar>`.
     * Delegates to the user-supplied codec object's `decode(buffer,
     * context)`. When the codec implements [BoundingLengthCodec], the
     * outer buffer limit is captured into `__<name>OuterLimit` BEFORE
     * decode (so the surrounding try/finally restores the caller's
     * outer limit even if the user codec or `applyBound` throws), and
     * `applyBound(buffer, <name>)` runs after decode to narrow the
     * limit for subsequent fields — driven by interface inspection on
     * the codec target (the outer-limit-restore pattern).
     */
    private fun appendDecodeUseCodecScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.UseCodecScalar,
    ) {
        if (field.isBounding) {
            body.addStatement("val __%LOuterLimit = buffer.limit()", field.name)
        }
        body.addStatement("val %L = %T.decode(buffer, context)", field.name, field.codecType)
        if (field.isBounding) {
            body.addStatement("%T.applyBound(buffer, %L)", field.codecType, field.name)
        }
    }

    /**
     * Emit encode for bare `@UseCodec val: <scalar>`.
     * Delegates to the user-supplied codec object's `encode(buffer,
     * value.<name>, context)`. The user codec owns the wire shape;
     * the framework neither validates nor measures the encoded width.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun appendEncodeUseCodecScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.UseCodecScalar,
        shape: CodecShape,
    ) {
        body.addStatement("%T.encode(buffer, value.%L, context)", field.codecType, field.name)
    }

    /**
     * Emit decode/encode for bare `val: T:
     * @ProtocolMessage`. Mirrors [appendEncodeUseCodecScalar] /
     * [appendDecodeUseCodecScalar] minus the bounding-codec branch:
     * the by-name-resolved codec is never a `BoundingLengthCodec` (those
     * are user-supplied length codecs, never `@ProtocolMessage` body
     * codecs).
     */
    private fun appendDecodeProtocolMessageScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.ProtocolMessageScalar,
    ) {
        body.addStatement("val %L = %T.decode(buffer, context)", field.name, field.codecType)
    }

    private fun appendEncodeProtocolMessageScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.ProtocolMessageScalar,
    ) {
        body.addStatement("%T.encode(buffer, value.%L, context)", field.codecType, field.name)
    }

    /**
     * Emit decode for `@LengthPrefixed
     * @UseCodec(C::class) val xs: List<E>`. The codec drives the prefix
     * read and applies the resulting bound to `buffer.limit()`; the list
     * is read element-by-element via E's codec inside the bounded region.
     * Self-contained `try`/`finally` restores the outer limit, so
     * subsequent fields run at the original limit.
     *
     * Generated shape:
     * ```
     * val __<name>OuterLimit = buffer.limit()
     * val __<name>Length = <codecType>.decode(buffer, context)
     * <codecType>.applyBound(buffer, __<name>Length)
     * val <name> = mutableListOf<ElementType>()
     * try {
     *     while (buffer.position() < buffer.limit()) {
     *         <name> += ElementCodec.decode(buffer, context)
     *     }
     * } finally {
     *     buffer.setLimit(__<name>OuterLimit)
     * }
     * ```
     */
    private fun appendDecodeLengthPrefixedUseCodecList(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthPrefixedUseCodecList,
    ) {
        appendDecodeLengthPrefixedListBody(
            body = body,
            spec = field.spec,
            listLocalName = field.name,
            namespacePrefix = field.name,
        )
    }

    /**
     * Emit decode for `@LengthPrefixed
     * @UseCodec(C::class) val: T : Payload`. Reads the fixed-width
     * unsigned-int prefix, narrows `buffer.limit()` to position + length,
     * delegates the body decode to `C.decode(buffer, context)`, and
     * restores the outer limit in `try/finally`.
     *
     * Generated shape:
     * ```
     * val <name>Prefix = <prefix-decode>
     * if (<name>Prefix > Int.MAX_VALUE.toUInt()) throw DecodeException(...)
     * val <name>Length = <name>Prefix.toInt()
     * val __<name>OuterLimit = buffer.limit()
     * buffer.setLimit(buffer.position() + <name>Length)
     * val <name> = try {
     *     <PayloadCodec>.decode(buffer, context)
     * } finally {
     *     buffer.setLimit(__<name>OuterLimit)
     * }
     * ```
     */
    private fun appendDecodeLengthPrefixedUseCodecPayload(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthPrefixedUseCodecPayload,
    ) {
        val lengthVar =
            appendLengthPrefixedStringPrefixDecode(
                body = body,
                name = field.name,
                ownerSimpleName = field.ownerSimpleName,
                prefixWidth = field.prefixWidth,
                prefixWireOrder = field.prefixWireOrder,
            )
        val outerLimitVar = "__${field.name}OuterLimit"
        body.addStatement("val %L = buffer.limit()", outerLimitVar)
        body.addStatement("buffer.setLimit(buffer.position() + %L)", lengthVar)
        body.beginControlFlow("val %L = try", field.name)
        body.addStatement("%T.decode(buffer, context)", field.payloadCodecType)
        body.nextControlFlow("finally")
        body.addStatement("buffer.setLimit(%L)", outerLimitVar)
        body.endControlFlow()
    }

    /**
     * Shared decode body for the VBI-prefixed
     * list shape. Emitted by both `FieldSpec.LengthPrefixedUseCodecList`
     *  and the conditional-inner branch in
     * `appendDecodeConditional`. Five-step sequence:
     * capture outer limit → codec.decode VBI prefix → applyBound →
     * mutableListOf → try-while-finally restore outer limit.
     *
     * `listLocalName` is the variable that holds the decoded list. The
     * non-conditional path uses the field's own name (`<field>`); the
     * conditional path uses `<field>Value` because `<field>` is a
     * nullable-typed local that `appendDecodeConditional` sets via the
     * `if (predicate) { ... <listLocal> } else null` construction.
     *
     * `namespacePrefix` keys the local-variable names (`__<prefix>
     * OuterLimit`, `__<prefix>Length`). Field path passes the field
     * name; conditional path also passes the field name (so encode/
     * decode share scratch local names within the same conditional
     * slot).
     */
    private fun appendDecodeLengthPrefixedListBody(
        body: CodeBlock.Builder,
        spec: LengthPrefixedListSpec,
        listLocalName: String,
        namespacePrefix: String,
    ) {
        val outerLimitVar = "__${namespacePrefix}OuterLimit"
        val lengthVar = "__${namespacePrefix}Length"
        body.addStatement("val %L = buffer.limit()", outerLimitVar)
        body.addStatement("val %L = %T.decode(buffer, context)", lengthVar, spec.codecType)
        body.addStatement("%T.applyBound(buffer, %L)", spec.codecType, lengthVar)
        body.addStatement("val %L = mutableListOf<%T>()", listLocalName, spec.elementClassName)
        body.beginControlFlow("try")
        body.beginControlFlow("while (buffer.position() < buffer.limit())")
        body.addStatement("%L += %T.decode(buffer, context)", listLocalName, spec.elementCodecClassName)
        body.endControlFlow()
        body.nextControlFlow("finally")
        body.addStatement("buffer.setLimit(%L)", outerLimitVar)
        body.endControlFlow()
    }

    /**
     * Emit encode for `@LengthPrefixed
     * @UseCodec(C::class) val xs: List<E>`. Pre-measures the body byte
     * count via the element codec's `wireSize` (cast to `Exact`), writes
     * the prefix via the user codec's `encode`, then iterates and encodes
     * elements. BackPatch element codecs throw `ClassCastException` —
     * same fixture-design contract as `RemainingBytesProtocolMessageList`
     * and `LengthPrefixedMessage`.
     *
     * Generated shape:
     * ```
     * val __<name>BodyBytes = value.<name>.sumOf {
     *     (ElementCodec.wireSize(it, context) as WireSize.Exact).bytes
     * }
     * <codecType>.encode(buffer, __<name>BodyBytes.toUInt(), context)
     * for (__elem in value.<name>) {
     *     ElementCodec.encode(buffer, __elem, context)
     * }
     * ```
     */
    private fun appendEncodeLengthPrefixedUseCodecList(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthPrefixedUseCodecList,
    ) {
        appendEncodeLengthPrefixedListBody(
            body = body,
            spec = field.spec,
            accessor = "value.${field.name}",
            namespacePrefix = field.name,
        )
    }

    /**
     * Emit encode for `@LengthPrefixed
     * @UseCodec(C::class) val: T : Payload`. BackPatch shape mirroring
     * [appendLengthPrefixedStringEncode]: reserve prefix slot, run
     * `C.encode(buffer, value.<name>, context)` against the accumulating
     * buffer, measure the body byte count from the position delta,
     * patch the prefix in place, restore position past the body.
     */
    private fun appendEncodeLengthPrefixedUseCodecPayload(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthPrefixedUseCodecPayload,
    ) {
        val sizePosVar = "${field.name}SizePosition"
        val bodyStartVar = "${field.name}BodyStart"
        val endPosVar = "${field.name}EndPosition"
        val byteCountVar = "${field.name}ByteCount"
        body.addStatement("val %L = buffer.position()", sizePosVar)
        body.addStatement("buffer.position(%L + %L)", sizePosVar, field.prefixWidth)
        body.addStatement("val %L = buffer.position()", bodyStartVar)
        body.addStatement(
            "%T.encode(buffer, value.%L, context)",
            field.payloadCodecType,
            field.name,
        )
        body.addStatement("val %L = buffer.position()", endPosVar)
        body.addStatement("val %L = %L - %L", byteCountVar, endPosVar, bodyStartVar)
        if (field.prefixWidth < 4) {
            val maxValue = (1L shl (field.prefixWidth * 8)) - 1
            val widthName =
                when (field.prefixWidth) {
                    1 -> "Byte"
                    2 -> "Short"
                    else -> error("unreachable: prefixWidth must be 1, 2, or 4")
                }
            body.beginControlFlow("if (%L > %L)", byteCountVar, maxValue)
            body.addStatement(
                "throw %T(fieldPath = %S, reason = %P)",
                ENCODE_EXCEPTION_CN,
                "${field.ownerSimpleName}.${field.name}",
                "encoded payload byte length \${$byteCountVar} exceeds " +
                    "@LengthPrefixed(LengthPrefix.$widthName) max $maxValue",
            )
            body.endControlFlow()
        }
        body.addStatement("buffer.position(%L)", sizePosVar)
        val prefixVar = "${field.name}Prefix"
        body.addStatement("val %L = %L.toUInt()", prefixVar, byteCountVar)
        appendBufferPrefixEncode(body, prefixVar, field.prefixWidth, field.prefixWireOrder)
        body.addStatement("buffer.position(%L)", endPosVar)
    }

    /**
     * Shared encode body for the VBI-prefixed
     * list shape. Emitted by both `FieldSpec.LengthPrefixedUseCodecList`
     *  and `appendEncodeConditional`'s
     * `LengthPrefixedUseCodecList` branch.
     *
     * `accessor` is the read-side expression for the list — `value.
     * <name>` for the non-conditional path; the smart-cast non-null
     * local (`<name>Value`) for the conditional path. `namespacePrefix`
     * keys the scratch / body-bytes locals.
     *
     * Two encode paths, gated by `spec.elementIsBackPatch`:
     *
     * **Sealed elements** — variants commonly carry BackPatch-wireSize
     * fields (`@LengthPrefixed val: String`, `@When` trailers), so the
     * pre-measure `as WireSize.Exact` cast doesn't apply. Encode each
     * element into a scratch buffer first to capture the actual byte
     * count, then write the VBI prefix and bulk-copy:
     * ```
     * BufferFactory.Default.allocate(64, buffer.byteOrder).use { __<n>Scratch ->
     *     for (__elem in <accessor>) {
     *         ElementCodec.encode(__<n>Scratch, __elem, context)
     *     }
     *     val __<n>BodyBytes = __<n>Scratch.position()
     *     <codecType>.encode(buffer, __<n>BodyBytes.toUInt(), context)
     *     __<n>Scratch.resetForRead()
     *     buffer.write(__<n>Scratch)
     * }
     * ```
     * 64-byte starting allocation is a heuristic — `BufferFactory` grows
     * on demand for buffers that exceed it. Tunable per-field if a
     * measurable hot path emerges.
     *
     * **Data-class elements** — pre-measure body bytes via the element
     * codec's `wireSize as Exact`, write VBI prefix, iterate. BackPatch
     * elements throw `ClassCastException` — same fixture-design contract
     * as `RemainingBytesProtocolMessageList` and `LengthPrefixedMessage`.
     * Audit 2b notes the latent risk: a data-class element with a
     * `@LengthPrefixed val: String` field has BackPatch wireSize and
     * would CCE; no current fixture trips it because all sealed-parent
     * cases are routed through the scratch path.
     */
    private fun appendEncodeLengthPrefixedListBody(
        body: CodeBlock.Builder,
        spec: LengthPrefixedListSpec,
        accessor: String,
        namespacePrefix: String,
    ) {
        if (spec.elementIsBackPatch) {
            val scratchVar = "__${namespacePrefix}Scratch"
            val bodyBytesVar = "__${namespacePrefix}BodyBytes"
            body.beginControlFlow(
                "%T.%M.allocate(64, buffer.byteOrder).%M { %L ->",
                BUFFER_FACTORY_CN,
                BUFFER_FACTORY_DEFAULT_MN,
                BUFFER_USE_MN,
                scratchVar,
            )
            body.beginControlFlow("for (__elem in %L)", accessor)
            body.addStatement(
                "%T.encode(%L, __elem, context)",
                spec.elementCodecClassName,
                scratchVar,
            )
            body.endControlFlow()
            body.addStatement("val %L = %L.position()", bodyBytesVar, scratchVar)
            body.addStatement(
                "%T.encode(buffer, %L.toUInt(), context)",
                spec.codecType,
                bodyBytesVar,
            )
            body.addStatement("%L.resetForRead()", scratchVar)
            body.addStatement("buffer.write(%L)", scratchVar)
            body.endControlFlow()
        } else {
            val bodyBytesVar = "__${namespacePrefix}BodyBytes"
            body.addStatement(
                "val %L = %L.sumOf { (%T.wireSize(it, context) as %T.Exact).bytes }",
                bodyBytesVar,
                accessor,
                spec.elementCodecClassName,
                WIRE_SIZE_CN,
            )
            body.addStatement(
                "%T.encode(buffer, %L.toUInt(), context)",
                spec.codecType,
                bodyBytesVar,
            )
            body.beginControlFlow("for (__elem in %L)", accessor)
            body.addStatement("%T.encode(buffer, __elem, context)", spec.elementCodecClassName)
            body.endControlFlow()
        }
    }

    /**
     * Order-aware single-scalar peek for the prefix walk. Single-byte
     * kinds (`UByte` / `Byte`) read directly; unsigned multi-byte kinds
     * (`UShort` / `UInt`) assemble bytes BE/LE per the field's
     * resolvedWireOrder. Wider and signed multi-byte kinds aren't required
     * by any in-scope vector; they would need parallel peek paths (signed
     * sign-extension, ULong promotion).
     *
     * `offsetExpr` is the Kotlin sub-expression interpolated into
     * `stream.peekByte(baseOffset + <offsetExpr>)`. Callers with a
     * fixed offset pass `"0"` / `"7"`; the sequential walk
     * passes the running-offset variable (`"__offset"`).
     */
    private fun appendPeekScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
        targetVar: String,
        offsetExpr: String,
    ) {
        when (field.kind) {
            ScalarKind.Boolean -> {
                body.addStatement(
                    "val %L = stream.peekByte(baseOffset + %L) != 0.toByte()",
                    targetVar,
                    offsetExpr,
                )
            }
            ScalarKind.UByte -> {
                body.addStatement(
                    "val %L = stream.peekByte(baseOffset + %L).toUByte()",
                    targetVar,
                    offsetExpr,
                )
            }
            ScalarKind.Byte -> {
                body.addStatement(
                    "val %L = stream.peekByte(baseOffset + %L)",
                    targetVar,
                    offsetExpr,
                )
            }
            ScalarKind.UShort, ScalarKind.UInt -> {
                val width = field.wireWidth.requireFixed("appendPeekScalar")
                val bigEndian =
                    when (field.resolvedWireOrder) {
                        Endianness.Big, Endianness.Default -> true
                        Endianness.Little -> false
                    }
                for (i in 0 until width) {
                    val byteOffset = if (i == 0) offsetExpr else "$offsetExpr + $i"
                    body.addStatement(
                        "val %L = stream.peekByte(baseOffset + %L).toInt() and 0xFF",
                        "${targetVar}B$i",
                        byteOffset,
                    )
                }
                val parts =
                    (0 until width).map { i ->
                        val byteName = "${targetVar}B$i"
                        val shiftBits = if (bigEndian) (width - 1 - i) * 8 else i * 8
                        if (shiftBits == 0) byteName else "($byteName shl $shiftBits)"
                    }
                val narrow =
                    when (field.kind) {
                        ScalarKind.UShort -> "(%L).toUInt().toUShort()"
                        ScalarKind.UInt -> "(%L).toUInt()"
                        else -> error("unreachable")
                    }
                body.addStatement("val %L = $narrow", targetVar, parts.joinToString(" or "))
            }
            ScalarKind.ULong, ScalarKind.Short, ScalarKind.Int, ScalarKind.Long,
            ScalarKind.Float, ScalarKind.Double,
            ->
                error(
                    "peek-side reconstruction for sibling kind ${field.kind} not implemented; " +
                        "the analyzer should have rejected this shape until the wider peek path lands.",
                )
        }
    }

    /**
     * Int.MAX_VALUE guard for `@LengthFrom` siblings whose
     * range exceeds `Int`. `UByte` (max 255), `UShort` (max 65535),
     * `Byte` (max 127), `Short` (max 32767), and `Int` (identity)
     * fit in a non-negative `Int` and skip the guard. `UInt`,
     * `ULong`, and `Long` need the runtime check.
     */
    private fun appendLengthFromIntMaxGuard(
        body: CodeBlock.Builder,
        siblingAccessor: String,
        siblingKind: ScalarKind,
        ownerSimpleName: String,
        fieldName: String,
    ) {
        val needsGuard =
            when (siblingKind) {
                ScalarKind.UByte, ScalarKind.UShort, ScalarKind.Byte, ScalarKind.Short, ScalarKind.Int -> false
                ScalarKind.UInt -> true
                ScalarKind.ULong -> true
                ScalarKind.Long -> true
                ScalarKind.Boolean ->
                    error("Boolean is rejected by analyzeLengthFromStringField; this branch is unreachable.")
                ScalarKind.Float, ScalarKind.Double ->
                    error("Float / Double are not valid @LengthFrom siblings; analyzeLengthFromStringField rejects them.")
            }
        if (!needsGuard) return
        val (cmp, actualExpr) =
            when (siblingKind) {
                ScalarKind.UInt -> "Int.MAX_VALUE.toUInt()" to "$siblingAccessor.toString()"
                ScalarKind.ULong -> "Int.MAX_VALUE.toULong()" to "$siblingAccessor.toString()"
                ScalarKind.Long -> "Int.MAX_VALUE.toLong()" to "$siblingAccessor.toString()"
                else -> error("unreachable")
            }
        body.beginControlFlow("if (%L > %L)", siblingAccessor, cmp)
        body.addStatement(
            "throw %T(fieldPath = %S, bufferPosition = -1, expected = %S, actual = %L)",
            DECODE_EXCEPTION_CN,
            "$ownerSimpleName.$fieldName",
            "@LengthFrom source <= \${Int.MAX_VALUE}",
            actualExpr,
        )
        body.endControlFlow()
        // For signed siblings, also reject negative values. Otherwise
        // toInt() returns a negative length and readString would
        // either throw or read past the buffer end with a confusing
        // error.
        if (siblingKind == ScalarKind.Long) {
            body.beginControlFlow("if (%L < 0L)", siblingAccessor)
            body.addStatement(
                "throw %T(fieldPath = %S, bufferPosition = -1, expected = %S, actual = %L.toString())",
                DECODE_EXCEPTION_CN,
                "$ownerSimpleName.$fieldName",
                "@LengthFrom source >= 0",
                siblingAccessor,
            )
            body.endControlFlow()
        }
    }

    private fun appendBufferPrefixEncode(
        body: CodeBlock.Builder,
        prefixVar: String,
        prefixWidth: Int,
        wireOrder: Endianness,
    ) {
        if (prefixWidth == 1) {
            body.addStatement("buffer.writeUByte((%L and 0xFFu).toUByte())", prefixVar)
            return
        }
        val bigEndian =
            when (wireOrder) {
                Endianness.Big -> true
                Endianness.Little -> false
                Endianness.Default -> true
            }
        for (i in 0 until prefixWidth) {
            val shiftBits = if (bigEndian) (prefixWidth - 1 - i) * 8 else i * 8
            val expr =
                if (shiftBits == 0) {
                    "$prefixVar and 0xFFu"
                } else {
                    "($prefixVar shr $shiftBits) and 0xFFu"
                }
            body.addStatement("buffer.writeUByte((%L).toUByte())", expr)
        }
    }

    /**
     * Peek-assemble a length-prefix as a `UInt`. `prefixOffsetExpr` is
     * interpolated into `stream.peekByte(baseOffset + <expr>)`; callers
     * with a fixed offset pass `"0"` / `"$N"`, the sequential walk passes
     * the running-offset variable.
     */
    private fun appendPeekPrefixAssembly(
        body: CodeBlock.Builder,
        fieldName: String,
        width: Int,
        wireOrder: Endianness,
        prefixOffsetExpr: String,
    ) {
        val prefixVar = "${fieldName}Prefix"
        if (width == 1) {
            body.addStatement(
                "val %L = (stream.peekByte(baseOffset + %L).toInt() and 0xFF).toUInt()",
                prefixVar,
                prefixOffsetExpr,
            )
            return
        }
        val bigEndian =
            when (wireOrder) {
                Endianness.Big -> true
                Endianness.Little -> false
                Endianness.Default -> true
            }
        for (i in 0 until width) {
            val byteOffset = if (i == 0) prefixOffsetExpr else "$prefixOffsetExpr + $i"
            body.addStatement(
                "val %L = stream.peekByte(baseOffset + %L).toInt() and 0xFF",
                "${prefixVar}B$i",
                byteOffset,
            )
        }
        val parts =
            (0 until width).map { i ->
                val byteName = "${prefixVar}B$i"
                val shiftBits = if (bigEndian) (width - 1 - i) * 8 else i * 8
                if (shiftBits == 0) byteName else "($byteName shl $shiftBits)"
            }
        body.addStatement("val %L = (%L).toUInt()", prefixVar, parts.joinToString(" or "))
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
    private fun analyzeSealedDispatcher(symbol: KSClassDeclaration): DispatchAnalysisResult {
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
            if (rawValue !in 0..255) return DispatchAnalysisResult.NotApplicable
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
    private fun analyzeDispatchOnSealedDispatcher(symbol: KSClassDeclaration): DispatchAnalysisResult {
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
        // the shared dispatch-value resolution.
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
                Discriminator.Varint(
                    className = classNameOf(discriminatorDecl),
                    codecClassName = discriminatorCodecClassName,
                    dispatchValueProperty = dispatchValuePropertyName,
                    dispatchValueKind = dispatchValueKind,
                )
            } else {
                val innerKind =
                    SUPPORTED_SCALARS[innerType.declaration.qualifiedName?.asString()]
                        ?: return DispatchAnalysisResult.NotApplicable
                // True silent gap: validateDispatchOnSealed accepts any numeric
                // inner (NUMERIC_SCALAR_QNAMES), but peek-side reconstruction only
                // supports single-byte kinds plus 2/4-byte unsigned kinds. ULong,
                // Int, and signed multi-byte (Short/Long) discriminators aren't
                // required by any in-scope vector and would need parallel peek
                // paths — so they pass validation and are then silently dropped.
                if (innerKind !in peekableDispatcherInnerKinds) {
                    val innerName = innerType.declaration.simpleName.asString()
                    val peekable = peekableDispatcherInnerKinds.joinToString(", ") { it.name }
                    return DispatchAnalysisResult.Rejected(
                        listOf(
                            Diagnostic(
                                "@DispatchOn discriminator on ${symbol.simpleName.asString()} has inner scalar " +
                                    "`$innerName`, which is not a supported dispatch discriminator width. The peek " +
                                    "path supports only single-byte and 2/4-byte unsigned kinds ($peekable); signed " +
                                    "multi-byte (Short/Long), Int, and ULong discriminators are not yet supported.",
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
    private fun resolveForwardCompatibleConfig(subclasses: List<KSClassDeclaration>): ForwardCompatibleConfig? {
        val sink =
            subclasses
                .filter { sub -> sub.annotations.any { it.shortName.asString() == "UnknownVariant" } }
                .singleOrNull() ?: return null
        val ctor = sink.primaryConstructor ?: return null
        val params = ctor.parameters
        if (params.size != 2) return null
        val opcodeParam =
            params.firstOrNull {
                it.type
                    .resolve()
                    .declaration.qualifiedName
                    ?.asString() == "kotlin.Int"
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
        return ForwardCompatibleConfig(
            unknownClassName = classNameOf(sink),
            opcodeFieldName = opcodeName,
            rawFieldName = rawName,
        )
    }

    private fun classifyVariantWireSize(shape: CodecShape): VariantWireSize {
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
        // Same shape — buildWireSizeFun collapses any
        // UseCodecScalar-bearing shape to BackPatch, so the dispatcher must
        // also skip the runtime-Exact cast. Promote later if a vector
        // benefits from runtime-Exact via codec.wireSize forwarding.
        if (shape.fields.any { it is FieldSpec.UseCodecScalar }) return VariantWireSize.BackPatch
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
        // Non-terminal `@RemainingBytes*` collapses the variant
        // codec's wireSize to BackPatch (see [buildWireSizeFun] above);
        // the dispatcher's per-variant size table mirrors that.
        if (shape.fields.any {
                it is FieldSpec.RemainingBytesProtocolMessageList && it.reservedTrailingBytes != 0
            }
        ) {
            return VariantWireSize.BackPatch
        }
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
            is FieldSpec.Scalar, is FieldSpec.ValueClassScalar, null ->
                VariantWireSize.LiteralExact(shape.fields.sumOfFixedWireBytes().requireFixed("sumOfFixedWireBytes"))
            is FieldSpec.LengthPrefixedString, is FieldSpec.Conditional -> VariantWireSize.BackPatch
            // Handled by the upfront BackPatch short-circuit; this
            // branch is unreachable because the early return collapses any
            // shape carrying a RemainingBytesPayload field before the
            // terminal-shape `when` runs.
            is FieldSpec.RemainingBytesPayload -> VariantWireSize.BackPatch
            // Same — handled by the upfront BackPatch short-circuit
            // above; defensive branch keeps the `when` exhaustive.
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
        }
    }

    // -----------------------------------------------------------------------
    // Unified dispatch decode (DISPATCH_UNIFICATION_PLAN.md stage 3). One
    // builder subsumes BOTH the simple @PacketType decode
    // (DiscriminatorOwnership.ConsumedByDispatcher) and the @DispatchOn decode
    // (DiscriminatorOwnership.ReReadByVariant), byte-for-byte. The legacy
    // builders' divergent behavior is recovered from the discriminator sum
    // type: ownership (consume vs peek/rewind), label radix (hex vs decimal),
    // local var names, receiver form (static object vs generic instance), and
    // the forward-compatible else arm.
    // -----------------------------------------------------------------------

    /**
     * The parent type the decode returns: bare for [Genericity.Monomorphic],
     * parameterized by the type variable for [Genericity.Generic]. This is the
     * type used in the codec's `Codec<...>` superinterface, the encode/decode
     * signatures, and the generic variant-codec constructor calls.
     */
    private fun DispatchShape.parentTypeRef(): TypeName =
        when (val g = genericity) {
            Genericity.Monomorphic -> parentClassName
            is Genericity.Generic ->
                parentClassName.parameterizedBy(
                    TypeVariableName(g.binding.typeVariableName),
                )
        }

    /** The `when` label for one variant, in the discriminator's radix. */
    private fun DispatchVariant.dispatchLabel(format: LabelFormat): CodeBlock =
        when (format) {
            // Pre-formatted hex string passed as %L after the `0x` prefix —
            // matches the simple path's `"0x%L"`.
            LabelFormat.Hex ->
                CodeBlock.of(
                    "0x%L",
                    dispatchValue
                        .toString(16)
                        .padStart(2, '0')
                        .uppercase(),
                )
            // Int passed as %L so KotlinPoet underscores large decimals
            // (e.g. `2_048`) — matches the @DispatchOn path.
            LabelFormat.Decimal -> CodeBlock.of("%L", dispatchValue)
        }

    /** How the dispatcher references this variant's codec at a call site. */
    private fun DispatchVariant.codecReceiver(): CodeBlock =
        when (val ref = codecRef) {
            VariantCodecRef.StaticObject -> CodeBlock.of("%T", codecClassName)
            is VariantCodecRef.GenericInstance -> CodeBlock.of("%L", ref.fieldName)
        }

    /** The expected-set diagnostic string in the discriminator's radix. */
    private fun expectedDispatchSet(shape: DispatchShape): String =
        shape.variants.joinToString(prefix = "one of {", postfix = "}") { variant ->
            when (shape.discriminator.labelFormat) {
                LabelFormat.Hex ->
                    "0x${variant.dispatchValue.toString(16).padStart(2, '0').uppercase()}"
                LabelFormat.Decimal -> variant.dispatchValue.toString()
            }
        }

    /**
     * THE unified dispatch decode builder. Reproduces the (now-removed)
     * simple-@PacketType and @DispatchOn decode output byte-for-byte by
     * forking on [Discriminator] ownership/labelFormat.
     */
    private fun buildDispatchDecodeFun(shape: DispatchShape): FunSpec {
        val body = CodeBlock.builder()
        val parentTypeRef = shape.parentTypeRef()
        // The `actual = ...` argument to DecodeException — hex-formatted for
        // FixedByte, the decimal value for ValueClass.
        val actualFormat: String

        when (val disc = shape.discriminator) {
            Discriminator.FixedByte -> {
                // Simple @PacketType: consume the discriminator byte.
                body.addStatement("val discriminatorPosition = buffer.position()")
                body.addStatement("val discriminator = buffer.readUByte().toInt()")
                actualFormat = "0x\${discriminator.toString(16).padStart(2, '0').uppercase()}"
                body.beginControlFlow("return when (discriminator)")
            }
            is Discriminator.ValueClass -> {
                // @DispatchOn: peek the discriminator via its codec, rewind so
                // the variant re-reads it as its first value-class field.
                body.addStatement("val discriminatorPosition = buffer.position()")
                body.addStatement(
                    "val __discriminator = %T.decode(buffer, context)",
                    disc.codecClassName,
                )
                body.addStatement("buffer.position(discriminatorPosition)")
                body.addStatement(
                    "val __dispatchValue = %L",
                    dispatchValueIntCoercion(
                        disc.dispatchValueKind,
                        "__discriminator.${disc.dispatchValueProperty}",
                    ),
                )
                actualFormat = "\${__dispatchValue}"
                body.beginControlFlow("return when (__dispatchValue)")
            }
            is Discriminator.Varint -> {
                // Varint @DispatchOn: identical decode shape to ValueClass —
                // peek the variable-width discriminator via the value class's
                // codec, rewind, and let the variant re-read it. The codec
                // (not a fixed inner-scalar read) does the self-delimiting read.
                body.addStatement("val discriminatorPosition = buffer.position()")
                body.addStatement(
                    "val __discriminator = %T.decode(buffer, context)",
                    disc.codecClassName,
                )
                body.addStatement("buffer.position(discriminatorPosition)")
                body.addStatement(
                    "val __dispatchValue = %L",
                    dispatchValueIntCoercion(
                        disc.dispatchValueKind,
                        "__discriminator.${disc.dispatchValueProperty}",
                    ),
                )
                actualFormat = "\${__dispatchValue}"
                body.beginControlFlow("return when (__dispatchValue)")
            }
        }

        for (variant in shape.variants) {
            body.addStatement(
                "%L -> %L.decode(buffer, context)",
                variant.dispatchLabel(shape.discriminator.labelFormat),
                variant.codecReceiver(),
            )
        }

        when (val fc = shape.forwardCompat) {
            is ForwardCompat.Enabled -> {
                val framedBy =
                    (shape.framing as? Framing.Framed)?.config
                        ?: error("@ForwardCompatible requires @FramedBy; analyzer should not have set forwardCompat")
                appendForwardCompatibleDecodeElse(body, framedBy, fc.config)
            }
            ForwardCompat.Disabled -> {
                body.beginControlFlow("else ->")
                body.addStatement(
                    "throw %T(fieldPath = %S, bufferPosition = discriminatorPosition, expected = %S, actual = %P)",
                    DECODE_EXCEPTION_CN,
                    "${shape.parentSimpleName}.discriminator",
                    expectedDispatchSet(shape),
                    actualFormat,
                )
                body.endControlFlow()
            }
        }
        body.endControlFlow()

        val builder =
            FunSpec
                .builder("decode")
                .addParameter("buffer", READ_BUFFER_CN)
                .addParameter("context", DECODE_CONTEXT_CN)
                .returns(parentTypeRef)
                .addCode(body.build())
        // `@FramedBy` dispatchers don't implement `Codec<T>` (the encode
        // contract differs), so decode is a plain object/class function rather
        // than an override. Every non-framed dispatcher overrides Codec.decode.
        if (shape.framing is Framing.Unframed) {
            builder.addModifiers(KModifier.OVERRIDE)
        }
        return builder.build()
    }

    /**
     * The encode-side `is` branch type for a variant on the unified shape.
     * Generic variants smart-cast to their star-projected form
     * (`Foo.Data<*>`) — the dispatcher's `value: Foo<P>` doesn't prove the
     * runtime variant's `P` matches; non-generic variants use the bare class.
     */
    private fun DispatchVariant.branchTypeName(genericity: Genericity): TypeName =
        when (codecRef) {
            VariantCodecRef.StaticObject -> className
            is VariantCodecRef.GenericInstance -> {
                require(genericity is Genericity.Generic) {
                    "Generic variant $simpleName requires the dispatcher to bind a payload type parameter"
                }
                className.parameterizedBy(com.squareup.kotlinpoet.STAR)
            }
        }

    /**
     * The typed cast TypeName for the variant codec call on the unified shape.
     * Generic variants need `value as Foo.Data<P>` so the variant codec's
     * `<P : Payload>` accepts the value.
     */
    private fun DispatchVariant.typedRef(genericity: Genericity): TypeName =
        when (codecRef) {
            VariantCodecRef.StaticObject -> className
            is VariantCodecRef.GenericInstance -> {
                require(genericity is Genericity.Generic)
                className.parameterizedBy(TypeVariableName(genericity.binding.typeVariableName))
            }
        }

    /**
     * THE unified NON-framed dispatch encode builder. Reproduces the
     * (now-removed) simple-@PacketType and non-framed @DispatchOn encode
     * output byte-for-byte by forking on [Discriminator] ownership.
     *
     * - [DiscriminatorOwnership.ConsumedByDispatcher] (FixedByte): the
     *   dispatcher WRITES the discriminator byte (always a hex literal,
     *   regardless of labelFormat — plan risk #2) then delegates to the
     *   variant codec.
     * - [DiscriminatorOwnership.ReReadByVariant] (ValueClass): the dispatcher
     *   writes NOTHING; the variant self-frames the discriminator. Generic
     *   variants get star-projected `is X<*>` branches, a `value as X<P>` cast,
     *   and the function gets `@Suppress("UNCHECKED_CAST")` when any variant is
     *   generic (plan risk #5).
     *
     * Does NOT cover the framed (`@FramedBy`) encode — that keeps the distinct
     * `(value, context, factory): ReadBuffer` signature in
     * [buildFramedByDispatchOnEncodeFun].
     */
    private fun buildDispatchEncodeFun(shape: DispatchShape): FunSpec {
        val parentTypeRef = shape.parentTypeRef()
        val body = CodeBlock.builder()

        when (shape.discriminator.ownership) {
            DiscriminatorOwnership.ConsumedByDispatcher -> {
                // Generic variants (a `<P : Payload>` parent under simple
                // @PacketType) star-project their `is X<*>` branch and cast
                // `value as X<P>` so the injected variant codec instance
                // accepts it — identical machinery to the ReReadByVariant
                // branch below. For monomorphic shapes `branchTypeName` is the
                // bare class and `codecReceiver` the static object, so the
                // output is byte-identical to the pre-generics simple path.
                val anyGeneric =
                    shape.variants.any { it.codecRef is VariantCodecRef.GenericInstance }
                if (anyGeneric) {
                    body.add("@Suppress(%S)\n", "UNCHECKED_CAST")
                }
                body.beginControlFlow("when (value)")
                for (variant in shape.variants) {
                    body.beginControlFlow("is %T ->", variant.branchTypeName(shape.genericity))
                    // The discriminator literal is ALWAYS hex (risk #2).
                    body.addStatement(
                        "buffer.writeUByte(0x%L.toUByte())",
                        variant.dispatchValue
                            .toString(16)
                            .padStart(2, '0')
                            .uppercase(),
                    )
                    if (variant.codecRef is VariantCodecRef.GenericInstance) {
                        body.addStatement(
                            "%L.encode(buffer, value as %T, context)",
                            variant.codecReceiver(),
                            variant.typedRef(shape.genericity),
                        )
                    } else {
                        body.addStatement(
                            "%L.encode(buffer, value, context)",
                            variant.codecReceiver(),
                        )
                    }
                    body.endControlFlow()
                }
                body.endControlFlow()
            }
            DiscriminatorOwnership.ReReadByVariant -> {
                val anyGeneric =
                    shape.variants.any { it.codecRef is VariantCodecRef.GenericInstance }
                if (anyGeneric) {
                    body.add("@Suppress(%S)\n", "UNCHECKED_CAST")
                }
                body.beginControlFlow("when (value)")
                for (variant in shape.variants) {
                    val branchType = variant.branchTypeName(shape.genericity)
                    if (variant.codecRef is VariantCodecRef.GenericInstance) {
                        body.addStatement(
                            "is %T -> %L.encode(buffer, value as %T, context)",
                            branchType,
                            variant.codecReceiver(),
                            variant.typedRef(shape.genericity),
                        )
                    } else {
                        body.addStatement(
                            "is %T -> %L.encode(buffer, value, context)",
                            branchType,
                            variant.codecReceiver(),
                        )
                    }
                }
                body.endControlFlow()
            }
        }

        return FunSpec
            .builder("encode")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("buffer", WRITE_BUFFER_CN)
            .addParameter("value", parentTypeRef)
            .addParameter("context", ENCODE_CONTEXT_CN)
            .addCode(body.build())
            .build()
    }

    /**
     * THE unified NON-framed dispatch wireSize builder. Reproduces the
     * (now-removed) simple-@PacketType and non-framed @DispatchOn wireSize
     * output byte-for-byte by forking on [Discriminator] ownership — the
     * discriminator-counted-once invariant (plan risk #1).
     *
     * - [DiscriminatorOwnership.ConsumedByDispatcher] (FixedByte): the
     *   DISPATCHER aggregates the discriminator byte — `1 +` the variant body
     *   size. Per variant, fork on [VariantWireSize]: LiteralExact →
     *   `Exact(1 + bytes)`, RuntimeExact → runtime `Exact(1 + inner)`, BackPatch
     *   → `BackPatch`. Delegated is unreachable here (FixedByte variants are
     *   classified concretely).
     * - [DiscriminatorOwnership.ReReadByVariant] (ValueClass): the dispatcher
     *   PURE-DELEGATES (NO `1 +`) — the variant already counts its self-framed
     *   re-read discriminator. `variant.wireSize` is ignored (it's Delegated).
     *   Generic variants get star-projected `is X<*>` branches, a `value as X<P>`
     *   cast, and the function gets `@Suppress("UNCHECKED_CAST")` when any
     *   variant is generic (plan risk #5).
     *
     * Does NOT cover the framed (`@FramedBy`) dispatchers — those OMIT wireSize
     * entirely (plan risk #6); this builder is only routed at the non-framed
     * call sites.
     */
    private fun buildDispatchWireSizeFun(shape: DispatchShape): FunSpec {
        val parentTypeRef = shape.parentTypeRef()
        val body = CodeBlock.builder()

        when (shape.discriminator.ownership) {
            DiscriminatorOwnership.ConsumedByDispatcher -> {
                body.beginControlFlow("return when (value)")
                for (variant in shape.variants) {
                    // Generic variants (a `<P : Payload>` parent under simple
                    // @PacketType) need the star-projected `is X<*>` branch so
                    // the runtime match succeeds. For monomorphic shapes
                    // `branchTypeName` is the bare class, so existing output is
                    // unchanged. A generic variant always carries
                    // `@RemainingBytes val: P`, which classifyVariantWireSize
                    // maps to BackPatch — so the RuntimeExact static-codec call
                    // below is only ever reached by monomorphic variants.
                    val branchType = variant.branchTypeName(shape.genericity)
                    when (val ws = variant.wireSize) {
                        is VariantWireSize.LiteralExact ->
                            body.addStatement(
                                "is %T -> %T.Exact(%L)",
                                branchType,
                                WIRE_SIZE_CN,
                                1 + ws.bytes,
                            )
                        is VariantWireSize.BackPatch ->
                            body.addStatement(
                                "is %T -> %T.BackPatch",
                                branchType,
                                WIRE_SIZE_CN,
                            )
                        is VariantWireSize.RuntimeExact -> {
                            body.beginControlFlow("is %T ->", branchType)
                            body.addStatement(
                                "val inner = (%L.wireSize(value, context) as %T.Exact).bytes",
                                variant.codecReceiver(),
                                WIRE_SIZE_CN,
                            )
                            body.addStatement("%T.Exact(1 + inner)", WIRE_SIZE_CN)
                            body.endControlFlow()
                        }
                        // Delegated is produced only by the @DispatchOn adapter
                        // (ReReadByVariant). ConsumedByDispatcher variants are
                        // always classified concretely, so this is unreachable.
                        VariantWireSize.Delegated ->
                            error("ConsumedByDispatcher variant ${variant.simpleName} is never Delegated")
                    }
                }
                body.endControlFlow()
            }
            DiscriminatorOwnership.ReReadByVariant -> {
                val anyGeneric =
                    shape.variants.any { it.codecRef is VariantCodecRef.GenericInstance }
                if (anyGeneric) {
                    body.add("@Suppress(%S)\n", "UNCHECKED_CAST")
                }
                body.beginControlFlow("return when (value)")
                for (variant in shape.variants) {
                    val branchType = variant.branchTypeName(shape.genericity)
                    if (variant.codecRef is VariantCodecRef.GenericInstance) {
                        body.addStatement(
                            "is %T -> %L.wireSize(value as %T, context)",
                            branchType,
                            variant.codecReceiver(),
                            variant.typedRef(shape.genericity),
                        )
                    } else {
                        body.addStatement(
                            "is %T -> %L.wireSize(value, context)",
                            branchType,
                            variant.codecReceiver(),
                        )
                    }
                }
                body.endControlFlow()
            }
        }

        return FunSpec
            .builder("wireSize")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("value", parentTypeRef)
            .addParameter("context", ENCODE_CONTEXT_CN)
            .returns(WIRE_SIZE_CN)
            .addCode(body.build())
            .build()
    }

    /**
     * THE unified NON-framed dispatch peekFrameSize builder. Reproduces the
     * (now-removed) simple-@PacketType `buildDispatcherPeekFrameFun` and
     * non-framed @DispatchOn `buildDispatchOnPeekFun` output byte-for-byte by
     * forking on [Discriminator] ownership — the discriminator-counted-once
     * invariant (plan §6 risk #1) and the hex-vs-decimal label radix (risk #2).
     *
     * - [DiscriminatorOwnership.ConsumedByDispatcher] (FixedByte): the
     *   DISPATCHER consumes the byte. Min guard `< 1`; inline
     *   `stream.peekByte(baseOffset)` discriminator; hex `when` labels; delegate
     *   to the variant at `baseOffset + 1` and wrap a `Complete` result with
     *   `Complete(1 + inner.bytes)` (the variant body excludes the byte).
     * - [DiscriminatorOwnership.ReReadByVariant] (ValueClass): the dispatcher
     *   PEEKS the discriminator's inner scalar, reconstructs the value class,
     *   coerces the dispatch value. Min guard `< innerWidth`; decimal `when`
     *   labels; PURE-DELEGATE at `baseOffset` (NO `1 +`) — the variant peek
     *   already counts its self-framed re-read discriminator.
     *
     * Does NOT cover the framed (`@FramedBy`) single-walker peek — that keeps
     * [buildFramedByDispatchOnPeekFun]; this builder is only routed at the
     * non-framed call sites.
     */
    private fun buildDispatchPeekFun(shape: DispatchShape): FunSpec {
        val body = CodeBlock.builder()

        when (val disc = shape.discriminator) {
            Discriminator.FixedByte -> {
                body.addStatement(
                    "if (stream.available() - baseOffset < %L) return %T.NeedsMoreData",
                    disc.wireWidth.requireFixed("dispatch peek"),
                    PEEK_RESULT_CN,
                )
                body.addStatement(
                    "val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF",
                )
                body.beginControlFlow("return when (discriminator)")
                for (variant in shape.variants) {
                    body.beginControlFlow(
                        "%L ->",
                        variant.dispatchLabel(shape.discriminator.labelFormat),
                    )
                    body.beginControlFlow(
                        "when (val inner = %L.peekFrameSize(stream, baseOffset + 1))",
                        variant.codecReceiver(),
                    )
                    body.addStatement(
                        "is %T.Complete -> %T.Complete(1 + inner.bytes)",
                        PEEK_RESULT_CN,
                        PEEK_RESULT_CN,
                    )
                    body.addStatement("else -> inner")
                    body.endControlFlow()
                    body.endControlFlow()
                }
                body.beginControlFlow("else ->")
                body.addStatement(
                    "throw %T(fieldPath = %S, bufferPosition = baseOffset, expected = %S, actual = %P)",
                    DECODE_EXCEPTION_CN,
                    "${shape.parentSimpleName}.discriminator",
                    expectedDispatchSet(shape),
                    "0x\${discriminator.toString(16).padStart(2, '0').uppercase()}",
                )
                body.endControlFlow()
                body.endControlFlow()
            }
            is Discriminator.ValueClass -> {
                body.addStatement(
                    "if (stream.available() - baseOffset < %L) return %T.NeedsMoreData",
                    disc.wireWidth.requireFixed("dispatch peek"),
                    PEEK_RESULT_CN,
                )
                // Peek the discriminator's inner-scalar bytes at baseOffset and
                // reconstruct the value class — the same path the value-class
                // @When source uses.
                appendPeekFixedScalar(
                    body = body,
                    kind = disc.innerKind,
                    targetVar = "__discRaw",
                    offsetExpr = "0",
                    wireOrder = disc.innerWireOrder,
                )
                body.addStatement(
                    "val __discriminator = %T(__discRaw)",
                    disc.className,
                )
                body.addStatement(
                    "val __dispatchValue = %L",
                    dispatchValueIntCoercion(
                        disc.dispatchValueKind,
                        "__discriminator.${disc.dispatchValueProperty}",
                    ),
                )
                body.beginControlFlow("return when (__dispatchValue)")
                for (variant in shape.variants) {
                    // Variant.peek counts the discriminator bytes in its own
                    // header field, so we delegate at baseOffset, not + 1.
                    body.addStatement(
                        "%L -> %L.peekFrameSize(stream, baseOffset)",
                        variant.dispatchLabel(shape.discriminator.labelFormat),
                        variant.codecReceiver(),
                    )
                }
                body.beginControlFlow("else ->")
                body.addStatement(
                    "throw %T(fieldPath = %S, bufferPosition = baseOffset, expected = %S, actual = %P)",
                    DECODE_EXCEPTION_CN,
                    "${shape.parentSimpleName}.discriminator",
                    expectedDispatchSet(shape),
                    "\${__dispatchValue}",
                )
                body.endControlFlow()
                body.endControlFlow()
            }
            is Discriminator.Varint -> {
                // Variable-width discriminator: its byte count isn't known
                // statically, so measure it via the value class codec's own
                // peekFrameSize (which delegates to the consumer's
                // VariableLengthCodec). If the prefix is too short to frame the
                // discriminator, propagate NeedsMoreData / NoFraming unchanged.
                body.addStatement(
                    "val __discFrame = %T.peekFrameSize(stream, baseOffset)",
                    disc.codecClassName,
                )
                body.beginControlFlow("if (__discFrame !is %T.Complete)", PEEK_RESULT_CN)
                body.addStatement("return __discFrame")
                body.endControlFlow()
                // Decode the discriminator from a non-consuming view of exactly
                // its measured width to recover the dispatch value, then PURE-
                // DELEGATE to the variant at baseOffset (the variant re-reads the
                // self-delimiting discriminator as its first field, so no `1 +`).
                body.addStatement(
                    "val __discView = stream.peekBuffer(baseOffset, __discFrame.bytes) ?: return %T.NeedsMoreData",
                    PEEK_RESULT_CN,
                )
                body.beginControlFlow("val __dispatchValue = try")
                body.addStatement(
                    "val __discriminator = %T.decode(__discView, %T.Empty)",
                    disc.codecClassName,
                    DECODE_CONTEXT_CN,
                )
                body.addStatement(
                    "%L",
                    dispatchValueIntCoercion(
                        disc.dispatchValueKind,
                        "__discriminator.${disc.dispatchValueProperty}",
                    ),
                )
                body.nextControlFlow("finally")
                body.addStatement(
                    "(__discView as? %T)?.freeNativeMemory()",
                    PLATFORM_BUFFER_CN,
                )
                body.endControlFlow()
                body.beginControlFlow("return when (__dispatchValue)")
                for (variant in shape.variants) {
                    body.addStatement(
                        "%L -> %L.peekFrameSize(stream, baseOffset)",
                        variant.dispatchLabel(shape.discriminator.labelFormat),
                        variant.codecReceiver(),
                    )
                }
                body.beginControlFlow("else ->")
                body.addStatement(
                    "throw %T(fieldPath = %S, bufferPosition = baseOffset, expected = %S, actual = %P)",
                    DECODE_EXCEPTION_CN,
                    "${shape.parentSimpleName}.discriminator",
                    expectedDispatchSet(shape),
                    "\${__dispatchValue}",
                )
                body.endControlFlow()
                body.endControlFlow()
            }
        }

        return FunSpec
            .builder("peekFrameSize")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("stream", STREAM_PROCESSOR_CN)
            .addParameter("baseOffset", INT)
            .returns(PEEK_RESULT_CN)
            .addCode(body.build())
            .build()
    }

    /**
     * THE unified dispatch file-spec builder (plan stage 7). Reproduces all
     * four shell variants from one [DispatchShape] by forking on
     * [Genericity] × [Framing]:
     *
     * - **Monomorphic + Unframed** — `object FooCodec : Codec<Parent>` with
     *   decode/encode/wireSize/peek (the unified fun builders, each carrying
     *   `OVERRIDE`). Covers the simple `@PacketType` path and the non-generic
     *   non-framed `@DispatchOn` path. No `Partial` member (neither legacy
     *   path emitted one here).
     * - **Monomorphic + Framed** — `object FooCodec` with NO `Codec<Parent>`
     *   superinterface, decode + framed encode + framed peek (single walker),
     *   NO wireSize (plan risk #6 triple-coupling).
     * - **Generic + Unframed** — `class FooCodec<P : Payload>(private val
     *   payloadCodec: Codec<P>) : Codec<Parent<P>>` + one private
     *   `val <field> = <VariantCodec>(payloadCodec)` per generic variant +
     *   decode/encode/wireSize/peek + the aggregator companion (`Partial<P>`).
     * - **Generic + Framed** — `class FooCodec<P>(payloadCodec)` with NO
     *   `Codec<Parent<P>>` superinterface + decode + framed encode + framed
     *   peek + aggregator companion, NO wireSize.
     *
     * Visibility comes from [DispatchShape.visibility] via [withVisibility].
     * `OVERRIDE` on the funs is handled inside the fun builders (Unframed →
     * override; framed encode/peek → none), so this shell never re-applies it.
     */
    private fun buildDispatchFileSpec(shape: DispatchShape): FileSpec {
        val parentTypeRef = shape.parentTypeRef()
        val framed = shape.framing is Framing.Framed
        val codecType =
            when (val genericity = shape.genericity) {
                Genericity.Monomorphic -> {
                    val builder =
                        TypeSpec
                            .objectBuilder(shape.codecSimpleName)
                            .withVisibility(shape.visibility)
                    if (!framed) {
                        builder.addSuperinterface(CODEC_CN.parameterizedBy(parentTypeRef))
                    }
                    builder.addFunction(buildDispatchDecodeFun(shape))
                    if (framed) {
                        builder.addFunction(buildFramedByDispatchOnEncodeFun(shape, parentTypeRef))
                        builder.addFunction(buildFramedByDispatchOnPeekFun(shape))
                    } else {
                        builder.addFunction(buildDispatchEncodeFun(shape))
                        builder.addFunction(buildDispatchWireSizeFun(shape))
                        builder.addFunction(buildDispatchPeekFun(shape))
                    }
                    builder.build()
                }
                is Genericity.Generic -> {
                    val binding = genericity.binding
                    val typeVar = TypeVariableName(binding.typeVariableName, binding.bound)
                    val codecOfP = CODEC_CN.parameterizedBy(typeVar)
                    val builder =
                        TypeSpec
                            .classBuilder(shape.codecSimpleName)
                            .withVisibility(shape.visibility)
                            .addTypeVariable(typeVar)
                            .primaryConstructor(
                                FunSpec
                                    .constructorBuilder()
                                    .addParameter(binding.codecParameterName, codecOfP)
                                    .build(),
                            ).addProperty(
                                com.squareup.kotlinpoet.PropertySpec
                                    .builder(binding.codecParameterName, codecOfP, KModifier.PRIVATE)
                                    .initializer(binding.codecParameterName)
                                    .build(),
                            )
                    if (!framed) {
                        builder.addSuperinterface(CODEC_CN.parameterizedBy(parentTypeRef))
                    }
                    for (variant in shape.variants) {
                        val ref = variant.codecRef as? VariantCodecRef.GenericInstance ?: continue
                        val fieldType = variant.codecClassName.parameterizedBy(typeVar)
                        builder.addProperty(
                            com.squareup.kotlinpoet.PropertySpec
                                .builder(ref.fieldName, fieldType, KModifier.PRIVATE)
                                .initializer("%T(%L)", variant.codecClassName, binding.codecParameterName)
                                .build(),
                        )
                    }
                    builder.addFunction(buildDispatchDecodeFun(shape))
                    if (framed) {
                        builder.addFunction(buildFramedByDispatchOnEncodeFun(shape, parentTypeRef))
                        builder.addFunction(buildFramedByDispatchOnPeekFun(shape))
                    } else {
                        builder.addFunction(buildDispatchEncodeFun(shape))
                        builder.addFunction(buildDispatchWireSizeFun(shape))
                        builder.addFunction(buildDispatchPeekFun(shape))
                    }
                    // The `decodeAggregating` companion (per-call payload-codec
                    // selection) is a ReReadByVariant/@DispatchOn construct — it
                    // peeks+rewinds the discriminator. A simple @PacketType
                    // (FixedByte) generic dispatcher consumes the byte and relies
                    // on the constructor-injected payloadCodec, so it omits the
                    // aggregator. (buildDispatchOnAggregatorCompanion requires a
                    // ValueClass discriminator and would otherwise error.)
                    if (shape.discriminator is Discriminator.ValueClass) {
                        builder.addType(buildDispatchOnAggregatorCompanion(shape, binding))
                    }
                    builder.build()
                }
            }
        return FileSpec
            .builder(shape.packageName, shape.codecSimpleName)
            .addType(codecType)
            .build()
    }

    /**
     * Encode for an `@FramedBy@DispatchOn`
     * dispatcher. The signature differs from the non-framed
     * [buildDispatchEncodeFun]
     * by dropping the `WriteBuffer` parameter, adding `factory`, and
     * returning `ReadBuffer`. The `when` body still routes by variant,
     * but every branch calls the variant codec's framed encode (which
     * itself returns `ReadBuffer`). No `OVERRIDE` modifier — the
     * dispatcher does not implement `Codec<T>`.
     *
     * Generic variants are smart-cast to their star-projected form
     * (`is Foo.Data<*>`) and then explicitly cast to `Foo.Data<P>` at
     * the call site so the variant codec's `<P : Payload>` accepts the
     * value (mirrors [buildDispatchEncodeFun]'s behaviour).
     */
    private fun buildFramedByDispatchOnEncodeFun(
        shape: DispatchShape,
        parentTypeRef: TypeName,
    ): FunSpec {
        val body = CodeBlock.builder()
        val anyGeneric = shape.variants.any { it.codecRef is VariantCodecRef.GenericInstance }
        if (anyGeneric) {
            body.add("@Suppress(%S)\n", "UNCHECKED_CAST")
        }
        body.beginControlFlow("return when (value)")
        for (variant in shape.variants) {
            val branchType = variant.branchTypeName(shape.genericity)
            if (variant.codecRef is VariantCodecRef.GenericInstance) {
                val typedRef = variant.typedRef(shape.genericity)
                body.addStatement(
                    "is %T -> %L.encode(value as %T, context, factory)",
                    branchType,
                    variant.codecReceiver(),
                    typedRef,
                )
            } else {
                body.addStatement(
                    "is %T -> %L.encode(value, context, factory)",
                    branchType,
                    variant.codecReceiver(),
                )
            }
        }
        when (val fc = shape.forwardCompat) {
            is ForwardCompat.Enabled -> {
                val framedBy =
                    (shape.framing as? Framing.Framed)?.config
                        ?: error("@ForwardCompatible requires @FramedBy; analyzer should not have set forwardCompat")
                appendForwardCompatibleEncodeArm(body, framedBy, fc.config)
            }
            ForwardCompat.Disabled -> Unit
        }
        body.endControlFlow()
        return FunSpec
            .builder("encode")
            .addParameter("value", parentTypeRef)
            .addParameter("context", ENCODE_CONTEXT_CN)
            .addParameter("factory", BUFFER_FACTORY_CN)
            .returns(READ_BUFFER_CN)
            .addCode(body.build())
            .build()
    }

    /**
     * `peekFrameSize` for an `@FramedBy`
     * `@DispatchOn` dispatcher. Every variant peeks identically (same
     * header width, same prefix codec — that's the point of inheriting
     * `@FramedBy` from the parent), so the per-variant dispatch
     * collapses to a single header+prefix walker.
     */
    private fun buildFramedByDispatchOnPeekFun(shape: DispatchShape): FunSpec {
        val framedBy =
            (shape.framing as? Framing.Framed)?.config
                ?: error("buildFramedByDispatchOnPeekFun called on shape without @FramedBy")
        val headerWireWidth = shape.discriminator.wireWidth.requireFixed("dispatchOnDiscriminator")
        val builder =
            FunSpec
                .builder("peekFrameSize")
                .addParameter("stream", STREAM_PROCESSOR_CN)
                .addParameter(
                    com.squareup.kotlinpoet.ParameterSpec
                        .builder("baseOffset", INT)
                        .defaultValue("0")
                        .build(),
                ).returns(PEEK_RESULT_CN)
        val body = CodeBlock.builder()
        body.addStatement(
            "if (stream.available() - baseOffset < %L) return %T.NeedsMoreData",
            headerWireWidth + 1,
            PEEK_RESULT_CN,
        )
        val peekBudget = 5
        body.addStatement(
            "val __framingPeek = stream.peekBuffer(baseOffset + %L, %L) ?: return %T.NeedsMoreData",
            headerWireWidth,
            peekBudget,
            PEEK_RESULT_CN,
        )
        body.beginControlFlow("try")
        body.addStatement("val __framingPeekStart = __framingPeek.position()")
        body.beginControlFlow("val __framingLength = try")
        body.addStatement(
            "%T.decode(__framingPeek, %T.Empty)",
            framedBy.codecClassName,
            DECODE_CONTEXT_CN,
        )
        body.nextControlFlow("catch (__e: %T)", ClassName("kotlin", "Throwable"))
        body.beginControlFlow("when (__e::class.simpleName)")
        body.addStatement(
            "%S, %S, %S -> return %T.NeedsMoreData",
            "BufferUnderflowException",
            "IndexOutOfBoundsException",
            "ArrayIndexOutOfBoundsException",
            PEEK_RESULT_CN,
        )
        body.addStatement("else -> throw __e")
        body.endControlFlow()
        body.endControlFlow()
        body.addStatement(
            "val __framingPrefixWidth = __framingPeek.position() - __framingPeekStart",
        )
        body.addStatement(
            "val __total = %L + __framingPrefixWidth + __framingLength.toInt()",
            headerWireWidth,
        )
        body.addStatement(
            "return if (stream.available() - baseOffset >= __total) %T.Complete(__total) else %T.NeedsMoreData",
            PEEK_RESULT_CN,
            PEEK_RESULT_CN,
        )
        body.nextControlFlow("finally")
        body.addStatement(
            "(__framingPeek as? %T)?.freeNativeMemory()",
            PLATFORM_BUFFER_CN,
        )
        body.endControlFlow()
        builder.addCode(body.build())
        return builder.build()
    }

    /**
     * Companion object hosting
     * `decodeAggregating(buffer, context, on<Variant>: (...) -> ...,
     * ...)`. The aggregator is a parallel decode pathway that lets
     * the consumer pick the payload codec per call (and per
     * payload-bearing variant) instead of pinning a single codec at
     * dispatcher construction. Each payload-bearing variant gets a
     * lambda parameter `on<VariantName>: (<VariantCodec>.Partial<P>)
     * -> <Parent>.<VariantName><P>` defaulting to a `DecodeException`
     * with field-path attribution per row 17 — consumers override
     * only the variants they expect to receive; un-overridden
     * payload-bearing variants throw at runtime if they arrive.
     *
     * Companion-side placement matches /10c's `partial<P>(
     * ...)` convention: the aggregator's `<P : Payload>` is a
     * function-level type variable, decoupled from any surrounding
     * dispatcher class instantiation. Consumers call
     * `<DispatcherCodec>.decodeAggregating<JpegImage>(buffer,
     * context, onPublish = …)` without instantiating the
     * dispatcher class, since the aggregator never invokes the
     * constructor-injected `payloadCodec` (the per-call lambda
     * supplies the codec via `partial.complete(theirCodec)`).
     *
     * Only emitted on generic dispatchers (non-generic dispatchers
     * have no payload-bearing variants by construction, so the
     * aggregator would have no lambdas).
     */
    private fun buildDispatchOnAggregatorCompanion(
        shape: DispatchShape,
        binding: PayloadTypeParameter,
    ): TypeSpec =
        TypeSpec
            .companionObjectBuilder()
            .addFunction(buildDispatchOnDecodeAggregatingFun(shape, binding))
            .build()

    /**
     * Emit `decodeAggregating(...)` on the
     * generic dispatcher's companion. Same routing logic as
     * `decode(buffer, context)` — but for payload-bearing variants
     * the dispatcher invokes the consumer's lambda with the variant
     * codec's `Partial<P>` instead of calling the constructor-
     * injected variant codec. `<Nothing>`-typed variants take the
     * standard codec dispatch (their codecs need no payload codec).
     *
     * Lambda return type is the **variant** (`Foo.Publish<P>`), not
     * the parent (`Foo<P>`). The discriminator's promise is "this
     * byte means PUBLISH"; the lambda's job is to complete the
     * matched variant from a `Partial`, not to substitute a
     * different variant. Returning the variant gives the dispatcher's
     * `when` branch a typed result that assigns to `Foo<P>` via
     * `out P` covariance with no cast. The "consumer wraps the
     * result" use case is served by wrapping outside the dispatcher
     * call.
     */
    private fun buildDispatchOnDecodeAggregatingFun(
        shape: DispatchShape,
        binding: PayloadTypeParameter,
    ): FunSpec {
        val disc =
            shape.discriminator as? Discriminator.ValueClass
                ?: error("decodeAggregating requires a ValueClass discriminator")
        val typeVar = TypeVariableName(binding.typeVariableName, binding.bound)
        val parentTypeRef = shape.parentClassName.parameterizedBy(typeVar)

        val funBuilder =
            FunSpec
                .builder("decodeAggregating")
                .addTypeVariable(typeVar)
                .addParameter("buffer", READ_BUFFER_CN)
                .addParameter("context", DECODE_CONTEXT_CN)
                .returns(parentTypeRef)

        for (variant in shape.variants) {
            if (variant.codecRef !is VariantCodecRef.GenericInstance) continue
            val partialType =
                variant.codecClassName
                    .nestedClass("Partial")
                    .parameterizedBy(typeVar)
            val variantTypedRef = variant.className.parameterizedBy(typeVar)
            val lambdaType =
                LambdaTypeName.get(
                    receiver = null,
                    parameters = arrayOf(partialType),
                    returnType = variantTypedRef,
                )
            // Default-lambda field-path attribution: identifies the
            // variant whose handler was missing. Throws on lambda
            // invocation (i.e., when a payload-bearing variant
            // actually arrives), not at dispatcher construction —
            // unhandled variants only fail when they're received.
            val defaultLambda =
                CodeBlock.of(
                    "{ _ -> throw %T(fieldPath = %S, bufferPosition = -1, expected = %S, actual = %S) }",
                    DECODE_EXCEPTION_CN,
                    "${shape.parentSimpleName}.${variant.simpleName}.handler",
                    "consumer-supplied ${variant.simpleName} handler",
                    "no handler supplied",
                )
            funBuilder.addParameter(
                ParameterSpec
                    .builder(aggregatorLambdaParameterName(variant), lambdaType)
                    .defaultValue(defaultLambda)
                    .build(),
            )
        }

        val body = CodeBlock.builder()
        body.addStatement("val discriminatorPosition = buffer.position()")
        body.addStatement(
            "val __discriminator = %T.decode(buffer, context)",
            disc.codecClassName,
        )
        body.addStatement("buffer.position(discriminatorPosition)")
        body.addStatement(
            "val __dispatchValue = %L",
            dispatchValueIntCoercion(
                disc.dispatchValueKind,
                "__discriminator.${disc.dispatchValueProperty}",
            ),
        )
        body.beginControlFlow("return when (__dispatchValue)")
        for (variant in shape.variants) {
            if (variant.codecRef is VariantCodecRef.GenericInstance) {
                // Payload-bearing variant: dispatch via the consumer's
                // lambda. The aggregator passes the variant codec's
                // Partial<P> — the lambda completes the decode with the
                // payload codec it chooses for this call.
                body.addStatement(
                    "%L -> %L(%T.partial<%T>(buffer, context))",
                    variant.dispatchValue,
                    aggregatorLambdaParameterName(variant),
                    variant.codecClassName,
                    typeVar,
                )
            } else {
                // <Nothing>-typed variant: standard dispatch unchanged.
                body.addStatement(
                    "%L -> %T.decode(buffer, context)",
                    variant.dispatchValue,
                    variant.codecClassName,
                )
            }
        }
        body.beginControlFlow("else ->")
        body.addStatement(
            "throw %T(fieldPath = %S, bufferPosition = discriminatorPosition, expected = %S, actual = %P)",
            DECODE_EXCEPTION_CN,
            "${shape.parentSimpleName}.discriminator",
            expectedDispatchSet(shape),
            "\${__dispatchValue}",
        )
        body.endControlFlow()
        body.endControlFlow()
        funBuilder.addCode(body.build())
        return funBuilder.build()
    }

    /**
     * Derive the lambda parameter name for a
     * payload-bearing variant. Convention: `on<VariantName>` (camel-
     * case lowering of the leading character). Disambiguates lambdas
     * across multiple payload-bearing variants without ambiguity.
     */
    private fun aggregatorLambdaParameterName(variant: DispatchVariant): String = "on${variant.simpleName}"

    /**
     * Emit the `@ForwardCompatible` decode `else` arm: skip an
     * unrecognized discriminator's framed payload and preserve it
     * verbatim into the unknown variant, instead of throwing.
     *
     * Position on entry is `discriminatorPosition` (the decode rewinds
     * there before dispatching). We re-read the single discriminator
     * byte as the opcode — capturing the *full* wire byte rather than
     * the (possibly sub-byte) dispatch value, which is what makes
     * re-encode byte-identical — then read the inherited framing prefix
     * to bound the payload, copy the payload into a caller-controlled
     * buffer (default `managed()`), and restore the outer limit so the
     * cursor lands exactly past the op.
     *
     * The copy is mandatory (the design's lifetime contract): the
     * preserved bytes outlive the often-pooled frame buffer, so a
     * non-consuming `slice()` would dangle. `factory.allocate + write`
     * gives an independently-owned buffer.
     */
    private fun appendForwardCompatibleDecodeElse(
        body: CodeBlock.Builder,
        framedBy: FramedByConfig,
        fc: ForwardCompatibleConfig,
    ) {
        body.beginControlFlow("else ->")
        // Cursor is at discriminatorPosition (rewound by decode); set it
        // explicitly so the read is robust to future reordering.
        body.addStatement("buffer.position(discriminatorPosition)")
        body.addStatement("val __fcOpcode = buffer.readUByte().toInt()")
        body.addStatement(
            "val __fcLength = %T.decode(buffer, context)",
            framedBy.codecClassName,
        )
        body.addStatement("val __fcFrameEnd = buffer.position() + __fcLength.toInt()")
        body.addStatement(
            "val __fcFactory = context[%T] ?: %T.%M()",
            FORWARD_COMPATIBLE_FACTORY_KEY_CN,
            BUFFER_FACTORY_CN,
            BUFFER_FACTORY_MANAGED_MN,
        )
        body.addStatement("val __fcRaw = __fcFactory.allocate(__fcLength.toInt())")
        body.addStatement("val __fcSavedLimit = buffer.limit()")
        body.addStatement("buffer.setLimit(__fcFrameEnd)")
        body.addStatement("__fcRaw.write(buffer)")
        body.addStatement("buffer.setLimit(__fcSavedLimit)")
        body.addStatement("__fcRaw.resetForRead()")
        body.addStatement(
            "%T(%L = __fcOpcode, %L = __fcRaw)",
            fc.unknownClassName,
            fc.opcodeFieldName,
            fc.rawFieldName,
        )
        body.endControlFlow()
    }

    /**
     * Emit the `@ForwardCompatible` encode arm for an unknown variant:
     * re-frame the preserved payload with the same inherited framing
     * codec the known variants use (via [FramedEncoder]), writing the
     * stored opcode as the single-byte discriminator. Byte-identical to
     * the original wire bytes — same framing codec re-derives the prefix,
     * the discriminator is one byte, and the payload is reproduced
     * verbatim. `raw.slice()` is non-consuming and zero-copy: we own
     * `raw`, and the slice is transient within the encode call.
     */
    private fun appendForwardCompatibleEncodeArm(
        body: CodeBlock.Builder,
        framedBy: FramedByConfig,
        fc: ForwardCompatibleConfig,
    ) {
        body.add("is %T -> %T.encode(\n", fc.unknownClassName, FRAMED_ENCODER_CN)
        body.indent()
        body.add("factory = factory,\n")
        body.add("framingCodec = %T,\n", framedBy.codecClassName)
        body.add("context = context,\n")
        body.add("headerWireWidth = 1,\n")
        body.add("writeHeader = { __fcBuf -> __fcBuf.writeUByte(value.%L.toUByte()) },\n", fc.opcodeFieldName)
        body.unindent()
        body.beginControlFlow(") { __fcBuf ->")
        body.addStatement("__fcBuf.write(value.%L.slice())", fc.rawFieldName)
        body.endControlFlow()
    }

    /**
     * Emit-side accessor for the user codec. Returns the receiver Kotlin
     * sub-expression for `<receiver>.decode(...)` / `<receiver>.encode(...)`
     * / `<receiver>.wireSize(...)` calls.
     */
    private fun PayloadCodecSource.codecReceiver(): CodeBlock =
        when (this) {
            is PayloadCodecSource.UserCodecObject -> CodeBlock.of("%T", codecType)
            is PayloadCodecSource.ConstructorInjected -> CodeBlock.of("%L", parameterName)
        }

    /**
     * Encode-side accessor for a LengthSource. Returns the
     * Kotlin sub-expression that yields the body byte count as an
     * `Int` when prefixed with `value.`. Used by `wireSize` and the
     * @LengthFrom encode path.
     */
    private fun LengthSource.encodeAccessor(): String =
        when (this) {
            is LengthSource.Sibling -> "value.$siblingName.toInt()"
            is LengthSource.ValueClassProperty -> "value.$siblingName.$propertyName"
        }

    /**
     * Decode-side accessor for a LengthSource. Returns the
     * Kotlin sub-expression that yields the body byte count as an
     * `Int` against locals already in scope. The simple form
     * accesses the sibling local directly; the dotted form accesses
     * the value-class local's property (the property returns `Int`,
     * so no `.toInt()` conversion is needed).
     */
    private fun LengthSource.decodeAccessor(): String =
        when (this) {
            is LengthSource.Sibling -> "$siblingName.toInt()"
            is LengthSource.ValueClassProperty -> "$siblingName.$propertyName"
        }

    /**
     * Slice — valid `@PacketType.value` range for
     * a given `@DispatchValue` return kind. Mirror of the validator-
     * side `DISPATCH_VALUE_RETURN_RANGES` map in
     * [com.ditchoom.buffer.codec.processor.ProtocolMessageProcessor].
     * Drives the analyzer's silent-skip on out-of-range values
     * (validator surfaces the user-facing diagnostic).
     */
    private fun dispatchValuePacketTypeRange(kind: ScalarKind): IntRange =
        when (kind) {
            ScalarKind.Boolean -> 0..1
            ScalarKind.Byte -> Byte.MIN_VALUE.toInt()..Byte.MAX_VALUE.toInt()
            ScalarKind.UByte -> 0..0xFF
            ScalarKind.Short -> Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt()
            ScalarKind.UShort -> 0..0xFFFF
            ScalarKind.Int -> Int.MIN_VALUE..Int.MAX_VALUE
            ScalarKind.UInt -> 0..Int.MAX_VALUE
            ScalarKind.Long, ScalarKind.ULong, ScalarKind.Float, ScalarKind.Double ->
                error("Long / ULong / Float / Double are not in DISPATCH_VALUE_RETURN_KINDS — analyze should have rejected this kind")
        }

    /**
     * Slice — Int-coercion for an `@DispatchValue`
     * property's runtime value, lifting it into the `Int` domain that
     * the dispatcher's `when (__dispatchValue)` branches use. Int
     * returns flow through unchanged, Boolean lifts to a 0/1 ternary,
     * the other primitive numeric kinds use `.toInt()` (sign-extending
     * for Byte / Short, zero-extending for UByte / UShort / UInt).
     * Long / ULong are unreachable — `DISPATCH_VALUE_RETURN_KINDS`
     * filters them out at analyze time.
     */
    private fun dispatchValueIntCoercion(
        kind: ScalarKind,
        propertyAccess: String,
    ): String =
        when (kind) {
            ScalarKind.Int -> propertyAccess
            ScalarKind.Boolean -> "if ($propertyAccess) 1 else 0"
            ScalarKind.Byte, ScalarKind.UByte,
            ScalarKind.Short, ScalarKind.UShort,
            ScalarKind.UInt,
            -> "$propertyAccess.toInt()"
            ScalarKind.Long, ScalarKind.ULong, ScalarKind.Float, ScalarKind.Double ->
                error("Long / ULong / Float / Double are not in DISPATCH_VALUE_RETURN_KINDS — analyze should have rejected this kind")
        }

    /**
     * Additive fold over WireWidth. Two Fixed values add numerically
     * (the exact `a + b` the old `Int` arithmetic produced); any Variable
     * operand makes the whole sum Variable. Used by `sumOfFixedWireBytes`
     * and the framed-header `n + 1` arithmetic so the Fixed path stays
     * byte-identical and the Variable path propagates instead of throwing
     * prematurely.
     */
    private operator fun WireWidth.plus(other: WireWidth): WireWidth =
        when {
            this is WireWidth.Fixed && other is WireWidth.Fixed -> WireWidth.Fixed(this.bytes + other.bytes)
            else -> WireWidth.Variable
        }

    /**
     * Unwrap a WireWidth that the call site requires to be Fixed, with a
     * symbol-named error for the stubbed Variable arm. This is THE single
     * Phase-1 stub helper: every consumer that needs a literal byte count
     * calls `width.requireFixed("siteName")` and gets `n` for Fixed,
     * error() for Variable. Centralizing it means the Variable behavior is
     * a one-liner to find and (in Phase 2) replace per site.
     */
    private fun WireWidth.requireFixed(site: String): Int =
        when (this) {
            is WireWidth.Fixed -> bytes
            WireWidth.Variable -> error("$site requires a Fixed wire width; Variable not yet supported (Phase 1 stub)")
        }

    private companion object {
        private const val PROTOCOL_MESSAGE_QNAME = "com.ditchoom.buffer.codec.annotations.ProtocolMessage"
        private const val PAYLOAD_QNAME = "com.ditchoom.buffer.codec.Payload"
        private const val PAYLOAD_PKG = "com.ditchoom.buffer.codec"
        private const val PAYLOAD_SIMPLE = "Payload"
        private const val OWNED_BYTES_HANDLE_QNAME = "com.ditchoom.buffer.codec.OwnedBytesHandle"
        private const val BOUNDING_LENGTH_CODEC_QNAME = "com.ditchoom.buffer.codec.BoundingLengthCodec"
        private const val VARIABLE_LENGTH_CODEC_QNAME = "com.ditchoom.buffer.codec.VariableLengthCodec"
        private const val FRAMED_BY_QNAME = "com.ditchoom.buffer.codec.annotations.FramedBy"

        // Batching gate. A coalesced read targets exactly 2, 4, or 8 bytes —
        // the natural-width reads on `ReadBuffer`. 3/5/6/7 prefixes are
        // never emitted; the coalescer keeps them as individual reads.
        private val BATCH_ALIGNMENTS = setOf(2, 4, 8)

        private val BYTE_ORDER_CN = ClassName("com.ditchoom.buffer", "ByteOrder")
        private val SWAP_BYTES_MN = MemberName("com.ditchoom.buffer", "swapBytes")

        private val SUPPORTED_SCALARS =
            mapOf(
                "kotlin.Boolean" to ScalarKind.Boolean,
                "kotlin.UByte" to ScalarKind.UByte,
                "kotlin.UShort" to ScalarKind.UShort,
                "kotlin.UInt" to ScalarKind.UInt,
                "kotlin.ULong" to ScalarKind.ULong,
                "kotlin.Byte" to ScalarKind.Byte,
                "kotlin.Short" to ScalarKind.Short,
                "kotlin.Int" to ScalarKind.Int,
                "kotlin.Long" to ScalarKind.Long,
                "kotlin.Float" to ScalarKind.Float,
                "kotlin.Double" to ScalarKind.Double,
            )

        // Slice — qnames accepted as `@DispatchValue`
        // property return types, mapped to the kind that drives the
        // dispatch-site Int coercion. Long / ULong are excluded — the
        // `@PacketType.value` annotation parameter is `Int` and can't
        // address values beyond `Int.MAX_VALUE`. Mirror of the
        // ProtocolMessageProcessor `DISPATCH_VALUE_RETURN_RANGES`
        // validator-side set.
        private val DISPATCH_VALUE_RETURN_KINDS =
            mapOf(
                "kotlin.Boolean" to ScalarKind.Boolean,
                "kotlin.Byte" to ScalarKind.Byte,
                "kotlin.UByte" to ScalarKind.UByte,
                "kotlin.Short" to ScalarKind.Short,
                "kotlin.UShort" to ScalarKind.UShort,
                "kotlin.Int" to ScalarKind.Int,
                "kotlin.UInt" to ScalarKind.UInt,
            )

        private val READ_BUFFER_CN = ClassName("com.ditchoom.buffer", "ReadBuffer")
        private val WRITE_BUFFER_CN = ClassName("com.ditchoom.buffer", "WriteBuffer")
        private val PLATFORM_BUFFER_CN = ClassName("com.ditchoom.buffer", "PlatformBuffer")
        private val BUFFER_FACTORY_CN = ClassName("com.ditchoom.buffer", "BufferFactory")
        private val BUFFER_FACTORY_DEFAULT_MN =
            com.squareup.kotlinpoet.MemberName("com.ditchoom.buffer", "Default")
        private val BUFFER_USE_MN =
            com.squareup.kotlinpoet.MemberName("com.ditchoom.buffer", "use")
        private val CODEC_CN = ClassName("com.ditchoom.buffer.codec", "Codec")
        private val DECODER_CN = ClassName("com.ditchoom.buffer.codec", "Decoder")
        private val DECODE_CONTEXT_CN = ClassName("com.ditchoom.buffer.codec", "DecodeContext")
        private val ENCODE_CONTEXT_CN = ClassName("com.ditchoom.buffer.codec", "EncodeContext")
        private val WIRE_SIZE_CN = ClassName("com.ditchoom.buffer.codec", "WireSize")
        private val PEEK_RESULT_CN = ClassName("com.ditchoom.buffer.codec", "PeekResult")
        private val STREAM_PROCESSOR_CN = ClassName("com.ditchoom.buffer.stream", "StreamProcessor")
        private val DECODE_EXCEPTION_CN = ClassName("com.ditchoom.buffer.codec", "DecodeException")
        private val ENCODE_EXCEPTION_CN = ClassName("com.ditchoom.buffer.codec", "EncodeException")
        private val FRAMED_ENCODER_CN = ClassName("com.ditchoom.buffer.codec", "FramedEncoder")
        private val FORWARD_COMPATIBLE_FACTORY_KEY_CN =
            ClassName("com.ditchoom.buffer.codec", "ForwardCompatibleFactoryKey")
        private val BUFFER_FACTORY_MANAGED_MN =
            com.squareup.kotlinpoet.MemberName("com.ditchoom.buffer", "managed")

        // Accepted types for the `@UnknownVariant` `raw` parameter — the
        // opaque preserved payload. `factory.allocate(...)` yields a
        // `PlatformBuffer` (assignable to a `ReadBuffer`-typed field too),
        // so both are valid declared types.
        private val FORWARD_COMPATIBLE_RAW_QNAMES =
            setOf("com.ditchoom.buffer.PlatformBuffer", "com.ditchoom.buffer.ReadBuffer")
        private val CHARSET_CN = ClassName("com.ditchoom.buffer", "Charset")
        private val STRING_NULLABLE_TN = ClassName("kotlin", "String").copy(nullable = true)
    }
}
