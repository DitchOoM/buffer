package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
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
    @Suppress("unused") private val logger: KSPLogger,
) {
    fun tryEmit(symbol: KSClassDeclaration) {
        val sourceFile = symbol.containingFile ?: return
        if (Modifier.SEALED in symbol.modifiers && symbol.classKind == ClassKind.INTERFACE) {
            // Try the @DispatchOn dispatcher path first; if the
            // parent doesn't carry the annotation, fall back to 's
            // simple dispatcher.
            val dispatchOnShape = analyzeDispatchOnSealedDispatcher(symbol)
            if (dispatchOnShape != null) {
                val file = buildDispatchOnDispatcherFileSpec(dispatchOnShape)
                codeGenerator
                    .createNewFile(
                        Dependencies(aggregating = false, sourceFile),
                        dispatchOnShape.packageName,
                        dispatchOnShape.codecSimpleName,
                    ).bufferedWriter()
                    .use { writer -> file.writeTo(writer) }
                return
            }
            val dispatcher = analyzeSealedDispatcher(symbol) ?: return
            val file = buildSealedDispatcherFileSpec(dispatcher)
            codeGenerator
                .createNewFile(
                    Dependencies(aggregating = false, sourceFile),
                    dispatcher.packageName,
                    dispatcher.codecSimpleName,
                ).bufferedWriter()
                .use { writer -> file.writeTo(writer) }
            return
        }
        val shape = analyze(symbol) ?: return
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

    private fun analyze(symbol: KSClassDeclaration): CodecShape? {
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
            if (symbol.annotations.any { it.shortName.asString() == "DispatchOn" }) return null
            val ownerSimpleName = symbol.simpleName.asString()
            return CodecShape(
                packageName = symbol.packageName.asString(),
                messageClassName = classNameOf(symbol),
                ownerSimpleName = ownerSimpleName,
                codecSimpleName = "${ownerSimpleName}Codec",
                fields = emptyList(),
                isSingletonObject = true,
                singletonDispatchDiscriminator = detectSealedDispatchOnParentDiscriminator(symbol),
            )
        }
        if (symbol.classKind != ClassKind.CLASS) return null
        val isData = Modifier.DATA in symbol.modifiers
        val isValue = Modifier.VALUE in symbol.modifiers
        if (!isData && !isValue) return null
        if (Modifier.SEALED in symbol.modifiers) return null
        if (symbol.annotations.any { it.shortName.asString() == "DispatchOn" }) return null
        // `@PacketType` on a data class is a variant — emit its standalone
        // codec via the existing data-class path. The dispatcher (separate emit
        // path keyed on the sealed parent) calls `${VariantSimpleName}Codec`.
        val ctor = symbol.primaryConstructor ?: return null
        if (ctor.parameters.isEmpty()) return null
        // Value class must have exactly one primary constructor parameter (Kotlin
        // already enforces this, but we add a defensive guard rather than relying on it).
        if (isValue && ctor.parameters.size != 1) return null

        val ownerSimpleName = symbol.simpleName.asString()
        val messageWireOrder = readMessageWireOrder(symbol)
        val payloadTypeParameter = detectPayloadTypeParameter(symbol)

        val fields = mutableListOf<FieldSpec>()
        val params = ctor.parameters
        for ((index, param) in params.withIndex()) {
            val isTerminal = index == params.lastIndex
            val field =
                analyzeField(
                    param = param,
                    messageWireOrder = messageWireOrder,
                    ownerSimpleName = ownerSimpleName,
                    isTerminal = isTerminal,
                    params = params,
                    index = index,
                    payloadTypeParameter = payloadTypeParameter,
                ) ?: return null
            fields += field
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
        if (fields.count { it.isBoundingShape() } > 1) return null
        for ((index, field) in fields.withIndex()) {
            if (field is FieldSpec.LengthFromString && index != fields.lastIndex) return null
            if (field is FieldSpec.LengthFromList && index != fields.lastIndex) return null
            // `@LengthFrom val: T: @ProtocolMessage` is terminal-only
            // (mirror of `LengthPrefixedMessage`'s terminal-only rule). Lifting
            // would require dispatcher-side wireSize composition that doesn't
            // exist yet for nested-message bodies sized by a sibling.
            if (field is FieldSpec.LengthFromMessage && index != fields.lastIndex) return null
            // (issue #151 part 2) — `@RemainingBytes` no longer has
            // to be the last field. Trailing fields must all be
            // `FieldSpec.FixedSize` (Scalar + ValueClassScalar today, not
            // UseCodecScalar) so the decode emit can subtract a known byte
            // count from `buffer.limit()` and hand the @RemainingBytes
            // body the correct bounded region. Variable-size trailers
            // would leave the body with no way to know its end without
            // re-encoding — surfaced as a focused validator error in
            // ProtocolMessageProcessor.validateRemainingBytesTrailers.
            if (field is FieldSpec.RemainingBytesProtocolMessageList &&
                index != fields.lastIndex &&
                !trailingFieldsAreFixedSize(fields, index)
            ) {
                return null
            }
            if (field is FieldSpec.RemainingBytesPayload &&
                index != fields.lastIndex &&
                !trailingFieldsAreFixedSize(fields, index)
            ) {
                return null
            }
            if (field is FieldSpec.RemainingBytesString &&
                index != fields.lastIndex &&
                !trailingFieldsAreFixedSize(fields, index)
            ) {
                return null
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
        if (payloadTypeParameter != null &&
            fields.none { f ->
                f is FieldSpec.RemainingBytesPayload &&
                    f.source is PayloadCodecSource.ConstructorInjected
            }
        ) {
            return null
        }
        return CodecShape(
            packageName = pkg,
            messageClassName = classNameOf(symbol),
            ownerSimpleName = ownerSimpleName,
            codecSimpleName = "${ownerSimpleName}Codec",
            fields = fields,
            payloadTypeParameter = payloadTypeParameter,
            framedBy = detectFramedBy(symbol),
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
            if (Modifier.VALUE !in discriminatorDecl.modifiers) continue
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
    ): FieldSpec? {
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
                else -> return null
            }
        }
        val name = param.name?.asString() ?: return null
        val type = param.type.resolve()
        if (type.isError) return null
        if (type.isMarkedNullable) return null

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
            if (lengthFromAnn != null || lengthPrefixed != null || wireBytesAnn != null) return null
            if (useCodecAnn != null && type.implementsPayload()) {
                return analyzeRemainingBytesPayloadField(param, type, useCodecAnn, ownerSimpleName)
            }
            if (useCodecAnn == null && payloadTypeParameter != null && type.matchesTypeParameter(payloadTypeParameter)) {
                val name = param.name?.asString() ?: return null
                return FieldSpec.RemainingBytesPayload(
                    name = name,
                    ownerSimpleName = ownerSimpleName,
                    payloadType = TypeVariableName(payloadTypeParameter.typeVariableName),
                    source = PayloadCodecSource.ConstructorInjected(payloadTypeParameter.codecParameterName),
                )
            }
            val typeQname = type.declaration.qualifiedName?.asString()
            // `@RemainingBytes val: String` — trailing UTF-8 bytes consume the
            // bounded buffer. Documented in the annotation kdoc since
            // `@RemainingBytes` was introduced; the emitter branch landed here.
            if (typeQname == "kotlin.String") {
                val name = param.name?.asString() ?: return null
                return FieldSpec.RemainingBytesString(name = name, ownerSimpleName = ownerSimpleName)
            }
            if (typeQname != "kotlin.collections.List") return null
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
            if (lengthPrefixed != null || wireBytesAnn != null) return null
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
            if (wireBytesAnn != null) return null
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
                // returns null for non-`List<...>` field types, so the two
                // shapes are mutually exclusive.
                analyzeLengthPrefixedUseCodecListField(
                    param = param,
                    listType = type,
                    useCodecAnn = useCodecAnn,
                    ownerSimpleName = ownerSimpleName,
                )?.let { return it }
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
                return FieldSpec.LengthPrefixedString(
                    name = name,
                    ownerSimpleName = ownerSimpleName,
                    prefixWidth = prefixWidth,
                    prefixWireOrder = messageWireOrder,
                )
            }
            // `@LengthPrefixed @ProtocolMessage` body stays terminal-only:
            // wireSize calls into the inner codec's wireSize at encode time,
            // and the dispatcher table needs the body to be the last field
            // for runtime-Exact size composition. Lifting this requires
            // extending the dispatcher / wireSize emit path too.
            if (!isTerminal) return null
            val decl = type.declaration as? KSClassDeclaration ?: return null
            val isProtocolMessage =
                decl.annotations.any { ann ->
                    ann.shortName.asString() == "ProtocolMessage" &&
                        ann.annotationType
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString() == PROTOCOL_MESSAGE_QNAME
                }
            if (!isProtocolMessage) return null
            if (Modifier.DATA !in decl.modifiers) return null
            return FieldSpec.LengthPrefixedMessage(
                name = name,
                ownerSimpleName = ownerSimpleName,
                messageType = classNameOf(decl),
                codecType = ClassName(decl.packageName.asString(), "${decl.simpleName.asString()}Codec"),
                prefixWidth = prefixWidth,
                prefixWireOrder = messageWireOrder,
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
            if (wireBytesAnn != null) return null
            if (param.annotations.any { it.shortName.asString() == "WireOrder" }) return null
            return analyzeUseCodecScalarField(param, type, useCodecAnn, ownerSimpleName)
        }

        val qualified = type.declaration.qualifiedName?.asString() ?: return null
        val kind = SUPPORTED_SCALARS[qualified]
        if (kind == null) {
            // Value-class field. Only the natural-width
            // unannotated path is in scope; @WireBytes / @WireOrder on the
            // outer parameter widen this and are deferred to a later slice.
            if (wireBytesAnn != null) return null
            if (param.annotations.any { it.shortName.asString() == "WireOrder" }) return null
            // Try the bare `val: T: @ProtocolMessage`
            // shape (data class or sealed parent) before the value-class
            // fallback. Value classes carry `Modifier.VALUE` and don't carry
            // `Modifier.DATA`/`Modifier.SEALED`, so the two branches are
            // mutually exclusive on the modifier check.
            analyzeBareProtocolMessageField(param, type, ownerSimpleName)
                ?.let { return it }
            return analyzeValueClassScalarField(param, ownerSimpleName)
        }

        // Boolean is 1-byte natural-width with no byte order; @WireBytes / @WireOrder
        // are meaningless on Boolean and are rejected here so the manual-byte-assembly
        // path (which has no Boolean support) is never reachable. Forces resolved =
        // Default regardless of the message-level wire order.
        if (kind == ScalarKind.Boolean) {
            if (wireBytesAnn != null) return null
            if (param.annotations.any { it.shortName.asString() == "WireOrder" }) return null
            return FieldSpec.Scalar(
                name = name,
                kind = kind,
                resolvedWireOrder = Endianness.Default,
                wireBytes = 1,
            )
        }

        val resolved = readFieldWireOrder(param) ?: messageWireOrder
        val wireBytes = wireBytesAnn?.let { readWireBytes(it) } ?: kind.width
        // R4 narrows what the emitter accepts; the validator emits the actual
        // diagnostic. Skip emission for unsupported widths so generated code
        // never references an out-of-bounds bit shift.
        if (wireBytes < 1 || wireBytes > 8) return null
        if (wireBytes > kind.width) return null
        // Signed scalars (and Float/Double, which carry a sign bit and never
        // narrow) only support natural width — partial-read sign extension
        // is its own design and out of scope. With explicit wireOrder they
        // flow through the manual byte-by-byte assembly path below.
        if (kind.isSigned && wireBytes != kind.width) return null
        return FieldSpec.Scalar(
            name = name,
            kind = kind,
            resolvedWireOrder = resolved,
            wireBytes = wireBytes,
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
    ): FieldSpec.ValueClassScalar? {
        val name = param.name?.asString() ?: return null
        val type = param.type.resolve()
        if (type.isError) return null
        if (type.isMarkedNullable) return null
        val decl = type.declaration as? KSClassDeclaration ?: return null
        if (Modifier.VALUE !in decl.modifiers) return null
        val ctor = decl.primaryConstructor ?: return null
        if (ctor.parameters.size != 1) return null
        val innerParam = ctor.parameters[0]
        val innerName = innerParam.name?.asString() ?: return null
        val innerType = innerParam.type.resolve()
        if (innerType.isError) return null
        if (innerType.isMarkedNullable) return null
        val innerQname = innerType.declaration.qualifiedName?.asString() ?: return null
        val innerKind = SUPPORTED_SCALARS[innerQname] ?: return null
        // Limits the inner scalar to its natural-width default-order
        // path. @WireBytes / @WireOrder on the inner property would widen the
        // shape and are deferred along with the same widening on direct
        // scalar fields.
        if (innerParam.annotations.any { ann ->
                val n = ann.shortName.asString()
                n == "WireBytes" || n == "WireOrder"
            }
        ) {
            return null
        }
        return FieldSpec.ValueClassScalar(
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
    ): FieldSpec.LengthFromString? {
        val name = param.name?.asString() ?: return null
        val type = param.type.resolve()
        if (type.isError) return null
        if (type.isMarkedNullable) return null
        if (type.declaration.qualifiedName?.asString() != "kotlin.String") return null

        val referenced =
            lengthFromAnn.arguments
                .firstOrNull { it.name?.asString() == "field" }
                ?.value as? String ?: return null
        val source = analyzeLengthSource(referenced, params, index) ?: return null
        return FieldSpec.LengthFromString(
            name = name,
            ownerSimpleName = ownerSimpleName,
            source = source,
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
        if (Modifier.VALUE !in siblingDecl.modifiers) return null
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
    ): FieldSpec.LengthFromList? {
        val name = param.name?.asString() ?: return null
        val typeArgs = listType.arguments
        if (typeArgs.size != 1) return null
        val elementType = typeArgs[0].type?.resolve() ?: return null
        if (elementType.isError || elementType.isMarkedNullable) return null
        val elementDecl = elementType.declaration as? KSClassDeclaration ?: return null
        if (Modifier.DATA !in elementDecl.modifiers) return null
        val isProtocolMessage =
            elementDecl.annotations.any { ann ->
                ann.shortName.asString() == "ProtocolMessage" &&
                    ann.annotationType
                        .resolve()
                        .declaration.qualifiedName
                        ?.asString() == PROTOCOL_MESSAGE_QNAME
            }
        if (!isProtocolMessage) return null

        val referenced =
            lengthFromAnn.arguments
                .firstOrNull { it.name?.asString() == "field" }
                ?.value as? String ?: return null
        val source = analyzeLengthSource(referenced, params, index) ?: return null

        return FieldSpec.LengthFromList(
            name = name,
            ownerSimpleName = ownerSimpleName,
            source = source,
            elementClassName = classNameOf(elementDecl),
            elementCodecClassName =
                ClassName(
                    elementDecl.packageName.asString(),
                    "${elementDecl.simpleName.asString()}Codec",
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
    ): FieldSpec.LengthFromMessage? {
        val name = param.name?.asString() ?: return null
        if (type.isError || type.isMarkedNullable) return null
        val decl = type.declaration as? KSClassDeclaration ?: return null
        val isProtocolMessage =
            decl.annotations.any { ann ->
                ann.shortName.asString() == "ProtocolMessage" &&
                    ann.annotationType
                        .resolve()
                        .declaration.qualifiedName
                        ?.asString() == PROTOCOL_MESSAGE_QNAME
            }
        if (!isProtocolMessage) return null
        // Accept both data-class and sealed-parent shapes — the by-name
        // codec resolution is identical (`<TCodec>.decode/encode`); the
        // sealed parent's codec is the dispatcher object.
        val isDataClass = Modifier.DATA in decl.modifiers
        val isSealed = Modifier.SEALED in decl.modifiers
        if (!isDataClass && !isSealed) return null
        // Payload-generic types reject (their codec is a class with
        // constructor-injected codec, not a singleton object).
        if (decl.typeParameters.isNotEmpty()) return null

        val referenced =
            lengthFromAnn.arguments
                .firstOrNull { it.name?.asString() == "field" }
                ?.value as? String ?: return null
        val source = analyzeLengthSource(referenced, params, index) ?: return null

        return FieldSpec.LengthFromMessage(
            name = name,
            ownerSimpleName = ownerSimpleName,
            source = source,
            messageType = classNameOf(decl),
            codecType =
                ClassName(
                    decl.packageName.asString(),
                    "${decl.simpleName.asString()}Codec",
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
    ): FieldSpec.RemainingBytesProtocolMessageList? {
        val name = param.name?.asString() ?: return null
        val typeArgs = listType.arguments
        if (typeArgs.size != 1) return null
        val elementType = typeArgs[0].type?.resolve() ?: return null
        if (elementType.isError || elementType.isMarkedNullable) return null
        val elementDecl = elementType.declaration as? KSClassDeclaration ?: return null
        // Widened to also accept a `@ProtocolMessage`
        // sealed parent (with `@DispatchOn`). Mirrors the widening
        // applied to `analyzeLengthPrefixedListSpec`. The encode emit
        // (`appendEncodeRemainingBytesProtocolMessageList`) calls the
        // element's `Codec.encode(...)` per element — for a sealed parent
        // that's the dispatcher's encode, which handles BackPatch variants
        // internally via its own scratch logic.
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
        // Same payload-generic reject as
        // `analyzeLengthPrefixedListSpec` ( rule). A `<P:
        // Payload>` element generates a generic-class codec, not a
        // singleton object; the per-element `<E>Codec.encode(...)` call
        // requires the singleton form.
        if (detectPayloadTypeParameter(elementDecl) != null) return null
        return FieldSpec.RemainingBytesProtocolMessageList(
            name = name,
            ownerSimpleName = ownerSimpleName,
            elementClassName = classNameOf(elementDecl),
            elementCodecClassName =
                ClassName(
                    elementDecl.packageName.asString(),
                    "${elementDecl.simpleName.asString()}Codec",
                ),
            elementIsBackPatch = detectElementBackPatch(elementDecl),
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
    ): FieldSpec.ProtocolMessageScalar? {
        if (type.isError || type.isMarkedNullable) return null
        val name = param.name?.asString() ?: return null
        val decl = type.declaration as? KSClassDeclaration ?: return null
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
        return FieldSpec.ProtocolMessageScalar(
            name = name,
            ownerSimpleName = ownerSimpleName,
            fieldType = classNameOf(decl),
            codecType =
                ClassName(
                    decl.packageName.asString(),
                    "${decl.simpleName.asString()}Codec",
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
    ): FieldSpec.RemainingBytesPayload? {
        val name = param.name?.asString() ?: return null
        val payloadDecl = type.declaration as? KSClassDeclaration ?: return null
        val codecKsType =
            useCodecAnn.arguments
                .firstOrNull { it.name?.asString() == "codec" }
                ?.value as? KSType ?: return null
        val codecDecl = codecKsType.declaration as? KSClassDeclaration ?: return null
        if (codecDecl.classKind != ClassKind.OBJECT) return null
        val codecPkg = codecDecl.packageName.asString()
        val codecSimple = codecDecl.simpleName.asString()
        return FieldSpec.RemainingBytesPayload(
            name = name,
            ownerSimpleName = ownerSimpleName,
            payloadType = classNameOf(payloadDecl),
            source = PayloadCodecSource.UserCodecObject(ClassName(codecPkg, codecSimple)),
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
    ): FieldSpec.UseCodecScalar? {
        val name = param.name?.asString() ?: return null
        val codecKsType =
            useCodecAnn.arguments
                .firstOrNull { it.name?.asString() == "codec" }
                ?.value as? KSType ?: return null
        val codecDecl = codecKsType.declaration as? KSClassDeclaration ?: return null
        if (codecDecl.classKind != ClassKind.OBJECT) return null
        val fieldTypeName: TypeName =
            SUPPORTED_SCALARS[type.declaration.qualifiedName?.asString()]
                ?.let { scalarTypeName(it) }
                ?: (type.declaration as? KSClassDeclaration)?.let { classNameOf(it) }
                ?: return null
        val codecClassName = ClassName(codecDecl.packageName.asString(), codecDecl.simpleName.asString())
        return FieldSpec.UseCodecScalar(
            name = name,
            ownerSimpleName = ownerSimpleName,
            fieldType = fieldTypeName,
            codecType = codecClassName,
            isBounding = codecDecl.implementsBoundingLengthCodec(),
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
    ): FieldSpec.LengthPrefixedUseCodecList? {
        val name = param.name?.asString() ?: return null
        val spec = analyzeLengthPrefixedListSpec(listType, useCodecAnn) ?: return null
        return FieldSpec.LengthPrefixedUseCodecList(
            name = name,
            ownerSimpleName = ownerSimpleName,
            spec = spec,
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
    ): FieldSpec.LengthPrefixedUseCodecPayload? {
        val name = param.name?.asString() ?: return null
        // `kotlin.String` rides the same shape as `T: Payload`:
        // prefix + body bytes, codec is `Codec<String>`. Validator surfaces
        // any user-facing diagnostic.
        val isString = type.declaration.qualifiedName?.asString() == "kotlin.String"
        if (!isString && !type.implementsPayload()) return null
        val payloadDecl = type.declaration as? KSClassDeclaration ?: return null
        val codecKsType =
            useCodecAnn.arguments
                .firstOrNull { it.name?.asString() == "codec" }
                ?.value as? KSType ?: return null
        val codecDecl = codecKsType.declaration as? KSClassDeclaration ?: return null
        if (codecDecl.classKind != ClassKind.OBJECT) return null
        val codecClassName = ClassName(codecDecl.packageName.asString(), codecDecl.simpleName.asString())
        return FieldSpec.LengthPrefixedUseCodecPayload(
            name = name,
            ownerSimpleName = ownerSimpleName,
            payloadType = classNameOf(payloadDecl),
            payloadCodecType = codecClassName,
            prefixWidth = prefixWidth,
            prefixWireOrder = prefixWireOrder,
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
                    "${elementDecl.simpleName.asString()}Codec",
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
    ): FieldSpec? {
        if (!boundParameterIsConditionalShape(param)) return null
        val name = param.name?.asString() ?: return null

        val expression = parseWhenExpression(whenAnn) ?: return null
        val condition = resolveCondition(expression, params, index) ?: return null
        val inner = analyzeConditionalInner(param, messageWireOrder) ?: return null

        return FieldSpec.Conditional(
            name = name,
            ownerSimpleName = ownerSimpleName,
            condition = condition,
            nullableTypeName = conditionalInnerNullableTypeName(inner),
            inner = inner,
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
        if (Modifier.VALUE !in siblingDecl.modifiers) return null
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
        return ConditionalInner.Scalar(kind = kind)
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
        if (!innerType.implementsPayload()) return null
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
                    "${decl.simpleName.asString()}Codec",
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
        if (Modifier.VALUE !in decl.modifiers) return null
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

    /**
     * Comparison operator inside the `remaining <op> <int>`
     * grammar. Closed sealed (three values match the documented grammar).
     * `Equal` is rare in practice (one-byte sentinel checks) but
     * documented as part of the grammar.
     */
    private enum class RemainingComparisonOp(
        val symbol: String,
    ) {
        GreaterOrEqual(">="),
        Greater(">"),
        Equal("=="),
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
        val typeSpec =
            TypeSpec
                .objectBuilder(shape.codecSimpleName)
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
        for (field in shape.fields) {
            if (field === afterField) continue
            appendDecodeField(body, field)
        }
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
        val headerWireWidth = afterField?.let(::framedByHeaderWireWidth) ?: 0
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
        for (field in shape.fields) {
            if (field === afterField) continue
            appendEncodeField(body, field, shape)
        }
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
        val headerWireWidth = afterField?.let(::framedByHeaderWireWidth) ?: 0
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
    private fun framedByHeaderWireWidth(field: FieldSpec): Int =
        when (field) {
            is FieldSpec.Scalar -> field.wireBytes
            is FieldSpec.ValueClassScalar -> field.wireBytes
            else -> 0
        }

    private fun buildFileSpec(shape: CodecShape): FileSpec {
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
            for (field in shape.fields) appendDecodeField(body, field)
            body.addStatement("return %T(%L)", messageType, ctorArgs)
        } else {
            for (i in 0..boundingIndex) appendDecodeField(body, shape.fields[i])
            body.beginControlFlow("return try")
            for (i in (boundingIndex + 1) until shape.fields.size) appendDecodeField(body, shape.fields[i])
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
        for (field in shape.fields) appendEncodeField(body, field, shape)
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
     * When [FieldSpec.RemainingBytesPayload] appears as a *non-terminal*
     * field (e.g. `PngChunk.data: BinaryData` followed by a 4-byte CRC
     * trailer), the embedded payload is just a bounded body inside the
     * packet's structure, not a deferred-decode tail. Emit only the normal
     * decode/encode; skip Partial. [buildPartialClassTypeSpec] would
     * otherwise error because it expects the Payload field at
     * `fields.lastOrNull()`.
     */
    private fun shouldEmitPartial(shape: CodecShape): Boolean = shape.fields.lastOrNull() is FieldSpec.RemainingBytesPayload

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
        val payloadField =
            shape.fields.lastOrNull() as? FieldSpec.RemainingBytesPayload
                ?: error("buildPartialClassTypeSpec called for shape without trailing RemainingBytesPayload")
        val headerFields = shape.fields.dropLast(1)
        // When the headers contain a bounding `@UseCodec` field (codec
        // implements `BoundingLengthCodec`), the partial decode mid-walk
        // narrows `buffer.limit()` and stashes the prior limit in
        // `__<fieldName>OuterLimit`. Capture that local on the Partial so
        // `complete()` can restore it. `@FramedBy` inherited from the
        // sealed parent supplies the bound externally (no in-shape field),
        // so we treat it as effectively-bounding for Partial purposes.
        val hasBoundingField = shape.fields.any { it.isBoundingShape() } || shape.framedBy != null

        val classBuilder = TypeSpec.classBuilder("Partial")
        val typeVar =
            payloadTypeParameter?.let {
                TypeVariableName(it.typeVariableName, it.bound)
            }
        if (typeVar != null) classBuilder.addTypeVariable(typeVar)

        val ctorBuilder = FunSpec.constructorBuilder().addModifiers(KModifier.INTERNAL)
        for (field in headerFields) {
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
            buildPartialCompleteFun(shape, payloadTypeParameter, payloadField, hasBoundingField),
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
        if (hasBoundingField) {
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
        val headerFields = shape.fields.dropLast(1)
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
        // When the parent supplies framing via
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
            for (field in headerFields) {
                if (field === afterField) continue
                appendDecodeField(body, field)
            }
        } else {
            for (field in headerFields) appendDecodeField(body, field)
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
                headerFields.map { "${it.name} = ${it.name}" } +
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
                val discriminatorBytes = shape.singletonDispatchDiscriminator?.innerKind?.width ?: 0
                val total = shape.fields.sumOfFixedWireBytes() + discriminatorBytes
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
    private fun List<FieldSpec>.sumOfFixedWireBytes(): Int = filterIsInstance<FieldSpec.FixedSize>().sumOf { it.wireBytes }

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
            if (!ucsField.isBounding || budget == null || !priorAreFixed) {
                builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
                return builder.build()
            }
            appendPeekUseCodecScalar(builder, shape, ucsField, budget)
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
            val discriminatorBytes = shape.singletonDispatchDiscriminator?.innerKind?.width ?: 0
            val total = shape.fields.sumOfFixedWireBytes() + discriminatorBytes
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
                    appendPeekAvailabilityCheck(body, field.wireBytes)
                    if (field.name in needsPeekStash) {
                        appendPeekScalar(body, field, field.name, "__offset")
                    }
                    body.addStatement("__offset += %L", field.wireBytes)
                }
                is FieldSpec.ValueClassScalar -> {
                    appendPeekAvailabilityCheck(body, field.wireBytes)
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
                    body.addStatement("__offset += %L", field.wireBytes)
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
        bytes: Int,
    ) {
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
        appendPeekAvailabilityCheck(body, prefixWidth)
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
                appendPeekAvailabilityCheck(body, inner.kind.width)
                body.addStatement("__offset += %L", inner.kind.width)
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
                appendPeekAvailabilityCheck(body, inner.innerKind.width)
                body.addStatement("__offset += %L", inner.innerKind.width)
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

    private fun scalarHeaderBytes(shape: CodecShape): Int = shape.fields.sumOfFixedWireBytes()

    private fun appendDecodeScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
    ) {
        val needsManual =
            field.wireBytes != field.kind.width || field.resolvedWireOrder != Endianness.Default
        if (!needsManual) {
            body.addStatement("val %L = %L", field.name, naturalScalarReadExpr(field.kind))
            return
        }
        val bigEndian =
            when (field.resolvedWireOrder) {
                Endianness.Little -> false
                Endianness.Big, Endianness.Default -> true
            }
        appendManualScalarDecode(body, field, bigEndian)
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
        val needsManual =
            field.wireBytes != field.kind.width || field.resolvedWireOrder != Endianness.Default
        if (!needsManual) {
            body.addStatement(naturalScalarWriteStatement(field.kind, accessor))
            return
        }
        val bigEndian =
            when (field.resolvedWireOrder) {
                Endianness.Little -> false
                Endianness.Big, Endianness.Default -> true
            }
        appendManualScalarEncode(body, field, accessor, bigEndian)
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
                ScalarKind.UByte -> return // wireBytes < 1 is rejected by analyzeField
                ScalarKind.Byte, ScalarKind.Short, ScalarKind.Int, ScalarKind.Long -> return // signed kinds reject @WireBytes narrowing in analyzeField
                ScalarKind.Float, ScalarKind.Double -> return // Float/Double also reject @WireBytes narrowing
                ScalarKind.Boolean -> return // analyzeField pins Boolean to natural width — never narrows
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
     * natural width.
     */
    private fun appendEncodeValueClassScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.ValueClassScalar,
    ) {
        body.addStatement(
            naturalScalarWriteStatement(
                field.innerKind,
                "value.${field.name}.${field.innerPropertyName}",
            ),
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
                body.addStatement(
                    "val %L: %T = if (%L) %L else null",
                    field.name,
                    field.nullableTypeName,
                    decodeConditionExpr(field.condition),
                    naturalScalarReadExpr(inner.kind),
                )
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
                body.addStatement(
                    "val %L: %T = if (%L) %T(%L) else null",
                    field.name,
                    field.nullableTypeName,
                    decodeConditionExpr(field.condition),
                    inner.valueClassType,
                    naturalScalarReadExpr(inner.innerKind),
                )
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
                body.addStatement(naturalScalarWriteStatement(inner.kind, localName))
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
                body.addStatement(
                    naturalScalarWriteStatement(
                        inner.innerKind,
                        "$localName.${inner.innerPropertyName}",
                    ),
                )
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
                val width = kind.width
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
                val width = field.wireBytes
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
     * Returns null (silently skip) when the parent carries
     * `@DispatchOn` ( surface), when the parent has zero
     * sealed subclasses, or when any direct subclass fails to fit
     * /B/C/D's variant shape (missing `@PacketType`,
     * out-of-range value, not a `data class`, or its own field shape
     * is not analyzable). The validator in `ProtocolMessageProcessor`
     * surfaces user-facing diagnostics for the missing-`@PacketType`
     * and duplicate-value cases; this method's silence keeps the
     * emitter consistent with /B/C "out of shape, no codec".
     */
    private fun analyzeSealedDispatcher(symbol: KSClassDeclaration): DispatcherShape? {
        if (symbol.annotations.any { it.shortName.asString() == "DispatchOn" }) return null
        val subclasses = symbol.getSealedSubclasses().toList()
        if (subclasses.isEmpty()) return null
        val variants = mutableListOf<VariantSpec>()
        val seenValues = mutableSetOf<Int>()
        for (sub in subclasses) {
            // Issue #150 — accept `data object` / `object` variants
            // (classKind == OBJECT) in addition to data-class variants.
            // Empty-fields object variants encode/decode through their
            // standalone codec, which buildDecodeFun emits as
            // `return ObjectName` and buildEncodeFun emits as a no-op.
            val isObjectVariant = sub.classKind == ClassKind.OBJECT
            if (!isObjectVariant && Modifier.DATA !in sub.modifiers) return null
            val packetType =
                sub.annotations.firstOrNull { it.shortName.asString() == "PacketType" }
                    ?: return null
            val rawValue =
                packetType.arguments
                    .firstOrNull { it.name?.asString() == "value" }
                    ?.value as? Int ?: return null
            if (rawValue !in 0..255) return null
            if (!seenValues.add(rawValue)) return null
            val variantShape = analyze(sub) ?: return null
            val variantWireSize = classifyVariantWireSize(variantShape)
            variants +=
                VariantSpec(
                    simpleName = sub.simpleName.asString(),
                    className = classNameOf(sub),
                    codecClassName =
                        ClassName(
                            sub.packageName.asString(),
                            "${sub.simpleName.asString()}Codec",
                        ),
                    packetTypeValue = rawValue,
                    wireSize = variantWireSize,
                )
        }
        // Sort by discriminator value so the generated `expected = "one of {...}"`
        // string and the `when` branches are deterministic, and so the dispatcher
        // table reads in the natural ascending order.
        variants.sortBy { it.packetTypeValue }
        val pkg = symbol.packageName.asString()
        val parentSimpleName = symbol.simpleName.asString()
        return DispatcherShape(
            packageName = pkg,
            parentClassName = ClassName(pkg, parentSimpleName),
            parentSimpleName = parentSimpleName,
            codecSimpleName = "${parentSimpleName}Codec",
            variants = variants,
        )
    }

    /**
     * Analyze a `@DispatchOn`-annotated sealed
     * parent into a `DispatchOnDispatcherShape`.
     *
     * Returns null (silent skip) when the parent doesn't carry
     * `@DispatchOn`, when the discriminator type isn't a value class
     * with a single supported-scalar inner, when the discriminator
     * has zero or multiple `@DispatchValue` properties (the
     * validator names this case), or when any variant fails to fit
     * the shape (data class, has `@PacketType(value = N)`,
     * first parameter is the discriminator type). The validator
     * surfaces user-facing diagnostics; the emitter's silence keeps
     * the "out of shape, no codec" pattern intact.
     */
    private fun analyzeDispatchOnSealedDispatcher(symbol: KSClassDeclaration): DispatchOnDispatcherShape? {
        val dispatchOn =
            symbol.annotations.firstOrNull { it.shortName.asString() == "DispatchOn" }
                ?: return null
        val discriminatorType =
            dispatchOn.arguments
                .firstOrNull { it.name?.asString() == "type" }
                ?.value as? KSType ?: return null
        val discriminatorDecl = discriminatorType.declaration as? KSClassDeclaration ?: return null
        if (Modifier.VALUE !in discriminatorDecl.modifiers) return null
        val discriminatorCtor = discriminatorDecl.primaryConstructor ?: return null
        if (discriminatorCtor.parameters.size != 1) return null
        val innerType = discriminatorCtor.parameters[0].type.resolve()
        if (innerType.isError || innerType.isMarkedNullable) return null
        val innerKind =
            SUPPORTED_SCALARS[innerType.declaration.qualifiedName?.asString()] ?: return null
        // Peek-side reconstruction supports single-byte kinds
        //  plus 2/4-byte unsigned kinds. ULong / signed multi-byte
        // discriminators aren't required by any in-scope vector and would
        // need parallel peek paths.
        if (innerKind !in peekableDispatcherInnerKinds) return null
        // Read the discriminator value class's `@ProtocolMessage(
        // wireOrder = ...)` so multi-byte byte assembly during peek matches
        // the encode/decode wire layout. Single-byte kinds ignore this.
        val discriminatorWireOrder = readMessageWireOrder(discriminatorDecl)

        val dispatchProp =
            discriminatorDecl
                .getDeclaredProperties()
                .singleOrNull { prop ->
                    prop.annotations.any { it.shortName.asString() == "DispatchValue" }
                } ?: return null
        if (dispatchProp.isMutable || dispatchProp.extensionReceiver != null) return null
        val returnType = dispatchProp.type.resolve()
        if (returnType.isMarkedNullable) return null
        // Slice — accept the widened set of return
        // types ({Boolean, Byte, UByte, Short, UShort, Int, UInt}) and
        // capture the kind so the dispatch emit site can pick the
        // right Int-coercion expression. The validator surfaces the
        // user-facing diagnostic for unsupported kinds; the analyzer
        // keeps its silent-skip discipline.
        val dispatchValueKind =
            DISPATCH_VALUE_RETURN_KINDS[returnType.declaration.qualifiedName?.asString()]
                ?: return null
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
        if (subclasses.isEmpty()) return null
        val variants = mutableListOf<DispatchOnVariantSpec>()
        val seenValues = mutableSetOf<Int>()
        for (sub in subclasses) {
            // Issue #150 — accept `data object` / `object` variants
            // (classKind == OBJECT). Object variants don't carry the
            // discriminator field (no constructor), so the firstParam
            // discriminator-type check is skipped for them. PR #153's
            // DataObjectCodegenTest only asserts compilation success on
            // this shape; runtime dispatch semantics for an empty-bodied
            // @DispatchOn variant remain a future concern.
            val isObjectVariant = sub.classKind == ClassKind.OBJECT
            if (!isObjectVariant && Modifier.DATA !in sub.modifiers) return null
            val packetType =
                sub.annotations.firstOrNull { it.shortName.asString() == "PacketType" } ?: return null
            val rawValue =
                packetType.arguments
                    .firstOrNull { it.name?.asString() == "value" }
                    ?.value as? Int ?: return null
            // Slice — `@PacketType.value` range is
            // per-kind now (Boolean: 0..1, Byte: -128..127, UByte:
            // 0..255, Short: -32768..32767, UShort: 0..65535, Int /
            // UInt: full Int range). Validator surfaces the user-facing
            // diagnostic on out-of-range values; analyzer silent-skips.
            if (rawValue !in dispatchValuePacketTypeRange(dispatchValueKind)) return null
            if (!seenValues.add(rawValue)) return null
            if (!isObjectVariant) {
                val ctor = sub.primaryConstructor ?: return null
                val firstParam = ctor.parameters.firstOrNull() ?: return null
                val firstParamQname =
                    firstParam.type
                        .resolve()
                        .declaration.qualifiedName
                        ?.asString()
                if (firstParamQname != discriminatorDecl.qualifiedName?.asString()) return null
            }
            // Variant must analyze cleanly via the existing data-class path
            // (or the object-singleton path added by issue #150). The
            // header field, when present, is a FieldSpec.ValueClassScalar
            // object variants resolve to an empty-fields shape.
            analyze(sub) ?: return null
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
                    // shape error caught by the validator (see
                    // ProtocolMessageProcessor's payload-type-parameter
                    // checks).
                    if (payloadTypeParameter == null) return null
                    "${variantSimpleName.replaceFirstChar { it.lowercase() }}Codec"
                } else {
                    null
                }
            variants +=
                DispatchOnVariantSpec(
                    simpleName = variantSimpleName,
                    className = classNameOf(sub),
                    codecClassName =
                        ClassName(
                            sub.packageName.asString(),
                            "${variantSimpleName}Codec",
                        ),
                    dispatchValue = rawValue,
                    genericInstanceFieldName = genericInstanceFieldName,
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
        return DispatchOnDispatcherShape(
            packageName = pkg,
            parentClassName = ClassName(pkg, parentSimpleName),
            parentSimpleName = parentSimpleName,
            codecSimpleName = "${parentSimpleName}Codec",
            discriminatorClassName = classNameOf(discriminatorDecl),
            discriminatorCodecClassName =
                ClassName(
                    discriminatorDecl.packageName.asString(),
                    "${discriminatorDecl.simpleName.asString()}Codec",
                ),
            discriminatorInnerKind = innerKind,
            discriminatorInnerWireOrder = discriminatorWireOrder,
            dispatchValuePropertyName = dispatchValuePropertyName,
            dispatchValueKind = dispatchValueKind,
            variants = variants,
            payloadTypeParameter = payloadTypeParameter,
            framedBy = framedBy,
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
                VariantWireSize.LiteralExact(shape.fields.sumOfFixedWireBytes())
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

    private fun buildSealedDispatcherFileSpec(shape: DispatcherShape): FileSpec {
        val codecType =
            TypeSpec
                .objectBuilder(shape.codecSimpleName)
                .addSuperinterface(CODEC_CN.parameterizedBy(shape.parentClassName))
                .addFunction(buildDispatcherDecodeFun(shape))
                .addFunction(buildDispatcherEncodeFun(shape))
                .addFunction(buildDispatcherWireSizeFun(shape))
                .addFunction(buildDispatcherPeekFrameFun(shape))
                .build()
        return FileSpec
            .builder(shape.packageName, shape.codecSimpleName)
            .addType(codecType)
            .build()
    }

    private fun expectedDiscriminatorSet(shape: DispatcherShape): String =
        shape.variants.joinToString(prefix = "one of {", postfix = "}") {
            "0x${it.packetTypeValue.toString(16).padStart(2, '0').uppercase()}"
        }

    private fun buildDispatcherDecodeFun(shape: DispatcherShape): FunSpec {
        val body = CodeBlock.builder()
        body.addStatement("val discriminatorPosition = buffer.position()")
        body.addStatement("val discriminator = buffer.readUByte().toInt()")
        body.beginControlFlow("return when (discriminator)")
        for (variant in shape.variants) {
            body.addStatement(
                "0x%L -> %T.decode(buffer, context)",
                variant.packetTypeValue
                    .toString(16)
                    .padStart(2, '0')
                    .uppercase(),
                variant.codecClassName,
            )
        }
        body.beginControlFlow("else ->")
        body.addStatement(
            "throw %T(fieldPath = %S, bufferPosition = discriminatorPosition, expected = %S, actual = %P)",
            DECODE_EXCEPTION_CN,
            "${shape.parentSimpleName}.discriminator",
            expectedDiscriminatorSet(shape),
            "0x\${discriminator.toString(16).padStart(2, '0').uppercase()}",
        )
        body.endControlFlow()
        body.endControlFlow()
        return FunSpec
            .builder("decode")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("buffer", READ_BUFFER_CN)
            .addParameter("context", DECODE_CONTEXT_CN)
            .returns(shape.parentClassName)
            .addCode(body.build())
            .build()
    }

    private fun buildDispatcherEncodeFun(shape: DispatcherShape): FunSpec {
        val body = CodeBlock.builder()
        body.beginControlFlow("when (value)")
        for (variant in shape.variants) {
            body.beginControlFlow("is %T ->", variant.className)
            body.addStatement(
                "buffer.writeUByte(0x%L.toUByte())",
                variant.packetTypeValue
                    .toString(16)
                    .padStart(2, '0')
                    .uppercase(),
            )
            body.addStatement(
                "%T.encode(buffer, value, context)",
                variant.codecClassName,
            )
            body.endControlFlow()
        }
        body.endControlFlow()
        return FunSpec
            .builder("encode")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("buffer", WRITE_BUFFER_CN)
            .addParameter("value", shape.parentClassName)
            .addParameter("context", ENCODE_CONTEXT_CN)
            .addCode(body.build())
            .build()
    }

    private fun buildDispatcherWireSizeFun(shape: DispatcherShape): FunSpec {
        val body = CodeBlock.builder()
        body.beginControlFlow("return when (value)")
        for (variant in shape.variants) {
            when (val ws = variant.wireSize) {
                is VariantWireSize.LiteralExact ->
                    body.addStatement(
                        "is %T -> %T.Exact(%L)",
                        variant.className,
                        WIRE_SIZE_CN,
                        1 + ws.bytes,
                    )
                is VariantWireSize.BackPatch ->
                    body.addStatement(
                        "is %T -> %T.BackPatch",
                        variant.className,
                        WIRE_SIZE_CN,
                    )
                is VariantWireSize.RuntimeExact -> {
                    body.beginControlFlow("is %T ->", variant.className)
                    body.addStatement(
                        "val inner = (%T.wireSize(value, context) as %T.Exact).bytes",
                        variant.codecClassName,
                        WIRE_SIZE_CN,
                    )
                    body.addStatement("%T.Exact(1 + inner)", WIRE_SIZE_CN)
                    body.endControlFlow()
                }
            }
        }
        body.endControlFlow()
        return FunSpec
            .builder("wireSize")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("value", shape.parentClassName)
            .addParameter("context", ENCODE_CONTEXT_CN)
            .returns(WIRE_SIZE_CN)
            .addCode(body.build())
            .build()
    }

    private fun buildDispatcherPeekFrameFun(shape: DispatcherShape): FunSpec {
        val body = CodeBlock.builder()
        body.addStatement(
            "if (stream.available() - baseOffset < 1) return %T.NeedsMoreData",
            PEEK_RESULT_CN,
        )
        body.addStatement(
            "val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF",
        )
        body.beginControlFlow("return when (discriminator)")
        for (variant in shape.variants) {
            body.beginControlFlow(
                "0x%L ->",
                variant.packetTypeValue
                    .toString(16)
                    .padStart(2, '0')
                    .uppercase(),
            )
            body.beginControlFlow(
                "when (val inner = %T.peekFrameSize(stream, baseOffset + 1))",
                variant.codecClassName,
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
            expectedDiscriminatorSet(shape),
            "0x\${discriminator.toString(16).padStart(2, '0').uppercase()}",
        )
        body.endControlFlow()
        body.endControlFlow()
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
     * Emit the bit-packed dispatcher codec. Uses the
     * peek-without-consume model on decode (save position, decode
     * discriminator via its codec, restore position, dispatch);
     * the variant codec then reads from the original position with
     * its first field being the discriminator value class.
     *
     * When the sealed parent is generic
     * (`<out P : Payload>`), the dispatcher emits as a class
     * `class FooCodec<P : Payload>(payloadCodec: Codec<P>)` instead
     * of `object FooCodec`. Generic variants have their codec
     * instances constructed once in the primary constructor and
     * stored as private properties; emit sites reference those
     * fields. `Nothing`-typed variants are unchanged — they keep
     * the static-object reference path.
     */
    private fun buildDispatchOnDispatcherFileSpec(shape: DispatchOnDispatcherShape): FileSpec {
        val parentTypeRef = dispatcherParentTypeRef(shape)
        val codecType =
            if (shape.payloadTypeParameter != null) {
                buildGenericDispatchOnDispatcherTypeSpec(shape, shape.payloadTypeParameter, parentTypeRef)
            } else if (shape.framedBy != null) {
                // `@FramedBy` parent. Encode
                // returns ReadBuffer, no Codec<Parent> superinterface,
                // peek owned by the dispatcher (single walker), no
                // wireSize.
                TypeSpec
                    .objectBuilder(shape.codecSimpleName)
                    .addFunction(buildDispatchOnDecodeFun(shape, parentTypeRef))
                    .addFunction(buildFramedByDispatchOnEncodeFun(shape, parentTypeRef))
                    .addFunction(buildFramedByDispatchOnPeekFun(shape))
                    .build()
            } else {
                TypeSpec
                    .objectBuilder(shape.codecSimpleName)
                    .addSuperinterface(CODEC_CN.parameterizedBy(parentTypeRef))
                    .addFunction(buildDispatchOnDecodeFun(shape, parentTypeRef))
                    .addFunction(buildDispatchOnEncodeFun(shape, parentTypeRef))
                    .addFunction(buildDispatchOnWireSizeFun(shape, parentTypeRef))
                    .addFunction(buildDispatchOnPeekFun(shape))
                    .build()
            }
        return FileSpec
            .builder(shape.packageName, shape.codecSimpleName)
            .addType(codecType)
            .build()
    }

    /**
     * Encode for an `@FramedBy@DispatchOn`
     * dispatcher. The signature differs from [buildDispatchOnEncodeFun]
     * by dropping the `WriteBuffer` parameter, adding `factory`, and
     * returning `ReadBuffer`. The `when` body still routes by variant,
     * but every branch calls the variant codec's framed encode (which
     * itself returns `ReadBuffer`). No `OVERRIDE` modifier — the
     * dispatcher does not implement `Codec<T>`.
     *
     * Generic variants are smart-cast to their star-projected form
     * (`is Foo.Data<*>`) and then explicitly cast to `Foo.Data<P>` at
     * the call site so the variant codec's `<P : Payload>` accepts the
     * value (mirrors [buildDispatchOnEncodeFun]'s behaviour).
     */
    private fun buildFramedByDispatchOnEncodeFun(
        shape: DispatchOnDispatcherShape,
        parentTypeRef: TypeName,
    ): FunSpec {
        val body = CodeBlock.builder()
        val anyGeneric = shape.variants.any { it.genericInstanceFieldName != null }
        if (anyGeneric) {
            body.add("@Suppress(%S)\n", "UNCHECKED_CAST")
        }
        body.beginControlFlow("return when (value)")
        for (variant in shape.variants) {
            val branchType = variantBranchTypeName(variant, shape.payloadTypeParameter)
            if (variant.genericInstanceFieldName != null) {
                val typedRef = variantTypedRef(variant, shape.payloadTypeParameter)
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
    private fun buildFramedByDispatchOnPeekFun(shape: DispatchOnDispatcherShape): FunSpec {
        val framedBy =
            shape.framedBy
                ?: error("buildFramedByDispatchOnPeekFun called on shape without @FramedBy")
        val headerWireWidth = shape.discriminatorInnerKind.width
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
     * `class FooCodec<P: Payload>(payloadCodec:
     * Codec<P>) : Codec<Foo<P>>` shape. Each generic variant gets a
     * private property initialized to `<VariantCodec>(payloadCodec)`
     * in the primary constructor; the encode/decode/wireSize/peek
     * emit sites then reference the field instead of the static
     * codec class.
     */
    private fun buildGenericDispatchOnDispatcherTypeSpec(
        shape: DispatchOnDispatcherShape,
        binding: PayloadTypeParameter,
        parentTypeRef: TypeName,
    ): TypeSpec {
        val typeVar = TypeVariableName(binding.typeVariableName, binding.bound)
        val codecOfP = CODEC_CN.parameterizedBy(typeVar)
        val builder =
            TypeSpec
                .classBuilder(shape.codecSimpleName)
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
        // Generic dispatcher × `@FramedBy`
        // drops `Codec<Parent<P>>`, emits framed encode + dispatcher-
        // owned peek walker, and skips wireSize. The aggregator
        // companion stays — `Partial<P>` is decode-only and
        // its framing-aware via the variant's own emit.
        if (shape.framedBy == null) {
            builder.addSuperinterface(CODEC_CN.parameterizedBy(parentTypeRef))
        }
        for (variant in shape.variants) {
            val fieldName = variant.genericInstanceFieldName ?: continue
            // Variant codec is `class <VariantName>Codec<P : Payload>(
            // payloadCodec: Codec<P>)` per. Construct it
            // once with the dispatcher's codec.
            val fieldType = variant.codecClassName.parameterizedBy(typeVar)
            builder.addProperty(
                com.squareup.kotlinpoet.PropertySpec
                    .builder(fieldName, fieldType, KModifier.PRIVATE)
                    .initializer("%T(%L)", variant.codecClassName, binding.codecParameterName)
                    .build(),
            )
        }
        builder.addFunction(buildDispatchOnDecodeFun(shape, parentTypeRef))
        if (shape.framedBy != null) {
            builder.addFunction(buildFramedByDispatchOnEncodeFun(shape, parentTypeRef))
            builder.addFunction(buildFramedByDispatchOnPeekFun(shape))
        } else {
            builder.addFunction(buildDispatchOnEncodeFun(shape, parentTypeRef))
            builder.addFunction(buildDispatchOnWireSizeFun(shape, parentTypeRef))
            builder.addFunction(buildDispatchOnPeekFun(shape))
        }
        return builder
            .addType(buildDispatchOnAggregatorCompanion(shape, binding))
            .build()
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
        shape: DispatchOnDispatcherShape,
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
        shape: DispatchOnDispatcherShape,
        binding: PayloadTypeParameter,
    ): FunSpec {
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
            if (variant.genericInstanceFieldName == null) continue
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
            shape.discriminatorCodecClassName,
        )
        body.addStatement("buffer.position(discriminatorPosition)")
        body.addStatement(
            "val __dispatchValue = %L",
            dispatchValueIntCoercion(
                shape.dispatchValueKind,
                "__discriminator.${shape.dispatchValuePropertyName}",
            ),
        )
        body.beginControlFlow("return when (__dispatchValue)")
        for (variant in shape.variants) {
            if (variant.genericInstanceFieldName != null) {
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
            expectedDispatchValueSet(shape),
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
    private fun aggregatorLambdaParameterName(variant: DispatchOnVariantSpec): String = "on${variant.simpleName}"

    /**
     * Return the parent's TypeName as it should
     * appear in the codec's `Codec<...>` superinterface, encode/decode
     * signatures, and constructor calls.
     *
     * For a non-generic sealed parent, this is `parentClassName`
     * (e.g., `MqttPacket`). For a generic sealed parent, this is
     * `parentClassName.parameterizedBy(P)` (e.g., `Http2Frame<P>`).
     */
    private fun dispatcherParentTypeRef(shape: DispatchOnDispatcherShape): TypeName =
        if (shape.payloadTypeParameter != null) {
            shape.parentClassName.parameterizedBy(
                TypeVariableName(shape.payloadTypeParameter.typeVariableName),
            )
        } else {
            shape.parentClassName
        }

    /**
     * Return the Kotlin code-block that yields
     * the variant's codec receiver for `<receiver>.decode(...)` /
     * `.encode(...)` / `.wireSize(...)` / `.peekFrameSize(...)` calls.
     *
     * Generic variants reference the dispatcher's private property
     * (an instance constructed once with `payloadCodec`); non-generic
     * variants reference the static codec class directly. The two
     * cases are syntactically distinct (`%L` vs `%T`) — the helper
     * keeps the emit sites symmetric.
     */
    private fun DispatchOnVariantSpec.codecReceiver(): CodeBlock =
        if (genericInstanceFieldName != null) {
            CodeBlock.of("%L", genericInstanceFieldName)
        } else {
            CodeBlock.of("%T", codecClassName)
        }

    /**
     * Encode-side `is` check for a variant. For
     * a generic variant, the smart cast uses star projection
     * (`Foo.Data<*>`) since the dispatcher's `value: Foo<P>` doesn't
     * tell us the runtime variant's `P` is the same `P`. The variant
     * codec call site needs the typed `Foo.Data<P>` cast — see
     * `variantCastTypeName`.
     */
    private fun variantBranchTypeName(
        variant: DispatchOnVariantSpec,
        binding: PayloadTypeParameter?,
    ): TypeName =
        if (variant.genericInstanceFieldName != null) {
            requireNotNull(binding) {
                "Generic variant ${variant.simpleName} requires the dispatcher to bind a payload type parameter"
            }
            variant.className.parameterizedBy(com.squareup.kotlinpoet.STAR)
        } else {
            variant.className
        }

    /**
     * Typed cast TypeName for the variant codec
     * call. Generic variants need `value as Foo.Data<P>` so the
     * variant codec's `<P : Payload>` accepts the value; non-generic
     * variants pass the `value` parameter directly without a cast.
     */
    private fun variantTypedRef(
        variant: DispatchOnVariantSpec,
        binding: PayloadTypeParameter?,
    ): TypeName =
        if (variant.genericInstanceFieldName != null) {
            requireNotNull(binding)
            variant.className.parameterizedBy(
                TypeVariableName(binding.typeVariableName),
            )
        } else {
            variant.className
        }

    private fun expectedDispatchValueSet(shape: DispatchOnDispatcherShape): String =
        shape.variants.joinToString(prefix = "one of {", postfix = "}") { it.dispatchValue.toString() }

    private fun buildDispatchOnDecodeFun(
        shape: DispatchOnDispatcherShape,
        parentTypeRef: TypeName,
    ): FunSpec {
        val body = CodeBlock.builder()
        body.addStatement("val discriminatorPosition = buffer.position()")
        body.addStatement(
            "val __discriminator = %T.decode(buffer, context)",
            shape.discriminatorCodecClassName,
        )
        // Rewind so the variant codec re-reads the discriminator bytes
        // as its first FieldSpec.ValueClassScalar field.
        body.addStatement("buffer.position(discriminatorPosition)")
        body.addStatement(
            "val __dispatchValue = %L",
            dispatchValueIntCoercion(
                shape.dispatchValueKind,
                "__discriminator.${shape.dispatchValuePropertyName}",
            ),
        )
        body.beginControlFlow("return when (__dispatchValue)")
        for (variant in shape.variants) {
            body.addStatement(
                "%L -> %L.decode(buffer, context)",
                variant.dispatchValue,
                variant.codecReceiver(),
            )
        }
        body.beginControlFlow("else ->")
        body.addStatement(
            "throw %T(fieldPath = %S, bufferPosition = discriminatorPosition, expected = %S, actual = %P)",
            DECODE_EXCEPTION_CN,
            "${shape.parentSimpleName}.discriminator",
            expectedDispatchValueSet(shape),
            "\${__dispatchValue}",
        )
        body.endControlFlow()
        body.endControlFlow()
        // `@FramedBy` dispatchers don't
        // implement `Codec<T>` (the encode contract differs), so
        // `decode` is a plain object function rather than an override.
        val builder =
            FunSpec
                .builder("decode")
                .addParameter("buffer", READ_BUFFER_CN)
                .addParameter("context", DECODE_CONTEXT_CN)
                .returns(parentTypeRef)
                .addCode(body.build())
        if (shape.framedBy == null) {
            builder.addModifiers(KModifier.OVERRIDE)
        }
        return builder.build()
    }

    private fun buildDispatchOnEncodeFun(
        shape: DispatchOnDispatcherShape,
        parentTypeRef: TypeName,
    ): FunSpec {
        val body = CodeBlock.builder()
        // Generic variants are smart-cast to their
        // star-projected form (`is Foo.Data<*>`) and then explicitly
        // cast to `Foo.Data<P>` at the call site so the variant codec's
        // `<P : Payload>` accepts the value. The cast is statically
        // safe by construction: the dispatcher's `value: Foo<P>`
        // matched as `Foo.Data<*>` must be `Foo.Data<R : P>` for some
        // R, which the variant codec's `Codec<P>` accepts via the
        // `out P` covariance on the sealed parent.
        val anyGeneric = shape.variants.any { it.genericInstanceFieldName != null }
        if (anyGeneric) {
            body.add("@Suppress(%S)\n", "UNCHECKED_CAST")
        }
        body.beginControlFlow("when (value)")
        for (variant in shape.variants) {
            val branchType = variantBranchTypeName(variant, shape.payloadTypeParameter)
            if (variant.genericInstanceFieldName != null) {
                val typedRef = variantTypedRef(variant, shape.payloadTypeParameter)
                body.addStatement(
                    "is %T -> %L.encode(buffer, value as %T, context)",
                    branchType,
                    variant.codecReceiver(),
                    typedRef,
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
        return FunSpec
            .builder("encode")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("buffer", WRITE_BUFFER_CN)
            .addParameter("value", parentTypeRef)
            .addParameter("context", ENCODE_CONTEXT_CN)
            .addCode(body.build())
            .build()
    }

    private fun buildDispatchOnWireSizeFun(
        shape: DispatchOnDispatcherShape,
        parentTypeRef: TypeName,
    ): FunSpec {
        val body = CodeBlock.builder()
        val anyGeneric = shape.variants.any { it.genericInstanceFieldName != null }
        if (anyGeneric) {
            body.add("@Suppress(%S)\n", "UNCHECKED_CAST")
        }
        body.beginControlFlow("return when (value)")
        for (variant in shape.variants) {
            val branchType = variantBranchTypeName(variant, shape.payloadTypeParameter)
            if (variant.genericInstanceFieldName != null) {
                val typedRef = variantTypedRef(variant, shape.payloadTypeParameter)
                body.addStatement(
                    "is %T -> %L.wireSize(value as %T, context)",
                    branchType,
                    variant.codecReceiver(),
                    typedRef,
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
        return FunSpec
            .builder("wireSize")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("value", parentTypeRef)
            .addParameter("context", ENCODE_CONTEXT_CN)
            .returns(WIRE_SIZE_CN)
            .addCode(body.build())
            .build()
    }

    private fun buildDispatchOnPeekFun(shape: DispatchOnDispatcherShape): FunSpec {
        val body = CodeBlock.builder()
        val discriminatorBytes = shape.discriminatorInnerKind.width
        body.addStatement(
            "if (stream.available() - baseOffset < %L) return %T.NeedsMoreData",
            discriminatorBytes,
            PEEK_RESULT_CN,
        )
        // Peek the discriminator's inner-scalar bytes at baseOffset and
        // reconstruct the value class. supports natural-width
        // single-byte kinds via appendPeekFixedScalar — the same path the
        // value-class @When source uses. Wider discriminators
        // would route through appendPeekScalar's order-aware assembly.
        appendPeekFixedScalar(
            body = body,
            kind = shape.discriminatorInnerKind,
            targetVar = "__discRaw",
            offsetExpr = "0",
            wireOrder = shape.discriminatorInnerWireOrder,
        )
        body.addStatement(
            "val __discriminator = %T(__discRaw)",
            shape.discriminatorClassName,
        )
        body.addStatement(
            "val __dispatchValue = %L",
            dispatchValueIntCoercion(
                shape.dispatchValueKind,
                "__discriminator.${shape.dispatchValuePropertyName}",
            ),
        )
        body.beginControlFlow("return when (__dispatchValue)")
        for (variant in shape.variants) {
            // Variant.peek counts the discriminator bytes in its own header
            // field, so we delegate at baseOffset, not baseOffset + 1.
            body.addStatement(
                "%L -> %L.peekFrameSize(stream, baseOffset)",
                variant.dispatchValue,
                variant.codecReceiver(),
            )
        }
        body.beginControlFlow("else ->")
        body.addStatement(
            "throw %T(fieldPath = %S, bufferPosition = baseOffset, expected = %S, actual = %P)",
            DECODE_EXCEPTION_CN,
            "${shape.parentSimpleName}.discriminator",
            expectedDispatchValueSet(shape),
            "\${__dispatchValue}",
        )
        body.endControlFlow()
        body.endControlFlow()
        return FunSpec
            .builder("peekFrameSize")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("stream", STREAM_PROCESSOR_CN)
            .addParameter("baseOffset", INT)
            .returns(PEEK_RESULT_CN)
            .addCode(body.build())
            .build()
    }

    private data class DispatcherShape(
        val packageName: String,
        val parentClassName: ClassName,
        val parentSimpleName: String,
        val codecSimpleName: String,
        val variants: List<VariantSpec>,
    )

    /**
     * Bit-packed dispatcher shape.
     *
     * The discriminator is a `@JvmInline value class` whose
     * `@DispatchValue`-annotated property produces an `Int` to match
     * against `@PacketType.value`. Variants are data classes whose
     * first constructor parameter is the discriminator type, so the
     * variant codec naturally reads/writes the discriminator byte
     * via the `FieldSpec.ValueClassScalar` path.
     */
    private data class DispatchOnDispatcherShape(
        val packageName: String,
        val parentClassName: ClassName,
        val parentSimpleName: String,
        val codecSimpleName: String,
        val discriminatorClassName: ClassName,
        val discriminatorCodecClassName: ClassName,
        val discriminatorInnerKind: ScalarKind,
        val discriminatorInnerWireOrder: Endianness,
        val dispatchValuePropertyName: String,
        /**
         * Slice — kind of the `@DispatchValue`
         * property's return type. Drives the per-emit-site Int
         * coercion at the dispatch site: Int returns flow through
         * unchanged, Boolean lifts to `if (b) 1 else 0`, the other
         * primitive numeric kinds use `.toInt()`. Defaults to
         * `ScalarKind.Int` for backwards compatibility with the
         * pre-widening Int-only contract.
         */
        val dispatchValueKind: ScalarKind = ScalarKind.Int,
        val variants: List<DispatchOnVariantSpec>,
        /**
         * Present when the sealed parent declares
         * `<out P : Payload>` (or `<P : Payload>`). Causes the
         * dispatcher to emit as a generic class
         * `class FooCodec<P : Payload>(payloadCodec: Codec<P>) :
         * Codec<Foo<P>>` instead of `object FooCodec : Codec<Foo>`.
         * Generic variants thread through `payloadCodec`;
         * `Nothing`-typed variants use static codec refs as before.
         */
        val payloadTypeParameter: PayloadTypeParameter? = null,
        /**
         * Present when the sealed parent itself
         * carries `@FramedBy`. The dispatcher then drops the `Codec<T>`
         * superinterface (encode contract differs — returns a
         * `ReadBuffer` slice), changes the encode signature to
         * `(value, context, factory): ReadBuffer`, and owns
         * `peekFrameSize` directly (every variant peeks identically
         * under inherited framing, so a single header+prefix walker on
         * the dispatcher subsumes the per-variant dispatch). `wireSize`
         * is dropped — the slicing-scheme encode returns a sized slice
         * and no caller needs an upfront size.
         *
         * The header wire width comes from the discriminator's inner
         * kind (`discriminatorInnerKind.width`) — the validator's E3
         * check ensures every variant's `after`-named field has Exact
         * wire width, and per the `@DispatchOn` shape contract, the
         * after-field IS the discriminator value class. So the header
         * the framing emit needs to skip equals the discriminator's
         * inner scalar width.
         */
        val framedBy: FramedByConfig? = null,
    )

    /**
     * Variant spec for `@DispatchOn` dispatch. Differs from
     * `VariantSpec` in carrying only the dispatch value (no
     * `wireSize` — the variant codec's own `wireSize` is the source
     * of truth, since the variant's bytes are exactly its body).
     *
     * Adds `genericInstanceFieldName`: when the
     * variant data class declares `<P: Payload>` ( shape),
     * the variant's codec is a generic class that needs a constructor-
     * injected `payloadCodec`. The dispatcher constructs the variant
     * codec instance once in its primary constructor and stores it as
     * a private property under this field name (e.g., `dataCodec`);
     * emit sites reference the field instead of the static codec
     * class. `null` for `Nothing`-typed variants — those use static
     * codec object references unchanged.
     */
    private data class DispatchOnVariantSpec(
        val simpleName: String,
        val className: ClassName,
        val codecClassName: ClassName,
        val dispatchValue: Int,
        val genericInstanceFieldName: String? = null,
    )

    private data class VariantSpec(
        val simpleName: String,
        val className: ClassName,
        val codecClassName: ClassName,
        val packetTypeValue: Int,
        val wireSize: VariantWireSize,
    )

    private sealed interface VariantWireSize {
        data class LiteralExact(
            val bytes: Int,
        ) : VariantWireSize

        data object RuntimeExact : VariantWireSize

        data object BackPatch : VariantWireSize
    }

    private data class CodecShape(
        val packageName: String,
        val messageClassName: ClassName,
        val ownerSimpleName: String,
        val codecSimpleName: String,
        val fields: List<FieldSpec>,
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
    )

    /**
     * Discriminator self-frame for a singleton
     * sealed variant under `@DispatchOn(value class)`. [innerKind] is the
     * value class's inner scalar (UByte for the in-scope MQTT v3 SUBACK
     * fixture; UShort/UInt/Byte are also peekable and would round-trip
     * via the same emit). [literalValue] is the variant's
     * `@PacketType.value`, narrowed to the inner kind at write time.
     */
    private data class SingletonDispatchDiscriminator(
        val innerKind: ScalarKind,
        val literalValue: Int,
    )

    /**
     * `@FramedBy` configuration captured during
     * analyze. The emitter consumes this to switch the file spec to the
     * slicing-scheme encode + strict-decode shape. `afterFieldName` is
     * empty for prefix-at-offset-0 (the standalone probe case);
     * non-empty values are reserved for sealed-parent + @PacketType
     * dispatch.
     */
    private data class FramedByConfig(
        val codecClassName: ClassName,
        val afterFieldName: String,
    )

    /**
     * Type-parameter binding for a generic-bounded
     * codec class. `typeVariableName` is the Kotlin type variable
     * (e.g., `P`); `codecParameterName` is the constructor parameter
     * holding the user-supplied codec (e.g., `payloadCodec`); `bound`
     * is the upper bound (always `Payload` for ).
     */
    private data class PayloadTypeParameter(
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
    private sealed interface FieldSpec {
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
        }

        data class Scalar(
            override val name: String,
            val kind: ScalarKind,
            val resolvedWireOrder: Endianness,
            override val wireBytes: Int,
        ) : FixedSize

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
    private sealed interface ConditionalInner {
        data class Scalar(
            val kind: ScalarKind,
        ) : ConditionalInner

        data class LengthPrefixedString(
            val prefixWidth: Int,
            val prefixWireOrder: Endianness,
        ) : ConditionalInner

        data class ValueClassScalar(
            val valueClassType: ClassName,
            val innerKind: ScalarKind,
            val innerPropertyName: String,
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
    private data class LengthPrefixedListSpec(
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
    private sealed interface ConditionRef {
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
    private sealed interface LengthSource {
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
    private sealed interface PayloadCodecSource {
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

    private enum class ScalarKind(
        val width: Int,
        val isSigned: Boolean,
    ) {
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
    }

    private enum class Endianness {
        Default,
        Big,
        Little,
    }

    private companion object {
        private const val PROTOCOL_MESSAGE_QNAME = "com.ditchoom.buffer.codec.annotations.ProtocolMessage"
        private const val PAYLOAD_QNAME = "com.ditchoom.buffer.codec.Payload"
        private const val PAYLOAD_PKG = "com.ditchoom.buffer.codec"
        private const val PAYLOAD_SIMPLE = "Payload"
        private const val BOUNDING_LENGTH_CODEC_QNAME = "com.ditchoom.buffer.codec.BoundingLengthCodec"
        private const val FRAMED_BY_QNAME = "com.ditchoom.buffer.codec.annotations.FramedBy"

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
        private val CHARSET_CN = ClassName("com.ditchoom.buffer", "Charset")
        private val STRING_NULLABLE_TN = ClassName("kotlin", "String").copy(nullable = true)
    }
}
