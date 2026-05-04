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
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LONG
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
 * Stage A + B + C + D emitter.
 *
 * Generates a sibling `object ${MessageName}Codec : Codec<${MessageName}>`
 * for each `@ProtocolMessage`-annotated symbol whose shape fits the
 * supported surface:
 *
 *   - **Stage A — fixed-size unsigned scalar fields.** A `data class`
 *     (or `@JvmInline value class`) with one or more `UByte` /
 *     `UShort` / `UInt` / `ULong` fields, each with optional
 *     `@WireOrder` per-field overrides of the message-level wireOrder.
 *   - **Stage A — `@LengthPrefixed @ProtocolMessage`-typed body.** A
 *     trailing field of `@ProtocolMessage` data class type, length-
 *     prefixed by `LengthPrefix.{Byte|Short|Int}` in the message wire
 *     order; emitter generates `setLimit` + restore decode, prefix-
 *     peek `peekFrameSize`, and the slice-4 lock #4 `Int.MAX_VALUE`
 *     overflow guards.
 *   - **Stage B — `@WireBytes(N)` narrowing.** A scalar field whose
 *     wire width is narrower than the Kotlin type's natural size.
 *     Always uses manual byte assembly; effective byte order falls
 *     back to `Big` (network) when neither the field nor the message
 *     declares one. Encode emits an `EncodeException` runtime guard
 *     when the value exceeds the narrowed range.
 *   - **Stage B — value-class wrapper at the top level.** A
 *     `@JvmInline value class` with a single inner unsigned scalar is
 *     treated as a one-field shape. The codec wraps the read scalar
 *     into the value class on decode and unwraps it via the inner
 *     property name on encode. Bit-packed logical fields exposed as
 *     getters in user code are invisible to the emitter (they
 *     introduce no wire format).
 *   - **Stage C — signed scalar fields.** `Byte` / `Short` / `Int` /
 *     `Long` at their natural width and the message's default byte
 *     order. Manual byte assembly stays unsigned-only; signed scalars
 *     with `@WireBytes` or explicit `@WireOrder` are silently skipped
 *     until a vector justifies the sign-extension design.
 *   - **Stage C — `@LengthPrefixed val: String` terminal.** A
 *     trailing `String` field with a `LengthPrefix.{Byte|Short|Int}`
 *     prefix in the message wire order. Encode reserves the prefix
 *     slot, writes the body via the runtime's `writeString(text,
 *     Charset.UTF8)`, measures the byte count from the position
 *     delta, and patches the prefix in place (`WireSize.BackPatch` —
 *     locked decision row 15). Encode emits an `EncodeException`
 *     runtime guard when the UTF-8 byte length exceeds the prefix's
 *     range; for 4-byte prefixes the check is skipped because Int
 *     position deltas can never exceed UInt max.
 *   - **Stage D — simple sealed dispatch with `@PacketType`.** A
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
 *     time throws `DecodeException` per Locked Decision row 17. Skips
 *     when the parent carries `@DispatchOn` (Stage F).
 *   - **Stage E slice 2 — `@WhenTrue` against a sibling `Boolean`.**
 *     A constructor parameter `@WhenTrue("siblingField") val name: T?`
 *     where `siblingField` is a non-nullable `Boolean` parameter
 *     declared before this one. Decode emits
 *     `val name: T? = if (sibling) <readT> else null`; encode skips
 *     the slot entirely when the predicate is false (zero bytes),
 *     and throws `EncodeException` if the predicate is true and the
 *     field is null. Per Locked Decision row 19, any `@WhenTrue`
 *     field collapses message-level `WireSize` to `BackPatch`.
 *     `peekFrameSize` walks scalar prefix fields, peeks the boolean
 *     source statically, and adds the inner field's bytes only when
 *     the predicate is true. Slice 2 also adds `Boolean` as a 1-byte
 *     scalar (no `@WireBytes` / `@WireOrder`); slice 2 inner is
 *     restricted to natural-width Scalar — `@LengthPrefixed` inner
 *     lands in slice 5 alongside MQTT v3 CONNECT.
 *   - **Stage E slice 3 — dotted `@WhenTrue("sibling.property")` plus
 *     value-class fields.** A constructor parameter whose type is a
 *     `value class` with a single supported-scalar primary
 *     constructor parameter is a first-class field shape: decode
 *     reads the inner scalar at natural width and constructs the
 *     value class; encode unwraps via the inner property and writes
 *     the inner scalar. The dotted-form `@WhenTrue("sibling.property")`
 *     resolves the predicate as `sibling.property` against an in-scope
 *     value-class local, where `sibling` is such a value-class field
 *     declared before the bound parameter and `property` is a
 *     `Boolean`-returning `val` declared on that value class.
 *     `peekFrameSize` peeks the value class's inner-scalar bytes at
 *     the sibling's offset, reconstructs the value class, and calls
 *     the predicate property. `@WireBytes` / `@WireOrder` on the
 *     outer parameter are out of scope for slice 3.
 *
 * Anything outside this surface — `@LengthFrom`, `@RemainingBytes`,
 * `@UseCodec`, `@DispatchOn`, signed scalars in the manual-byte-
 * assembly path, `@LengthPrefixed` on a non-terminal field, non-
 * terminal `@WhenTrue`, `@LengthPrefixed`-inner `@WhenTrue` — is
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
            // Slice 6 — try the @DispatchOn dispatcher path first; if the
            // parent doesn't carry the annotation, fall back to Stage D's
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
        if (symbol.classKind != ClassKind.CLASS) return null
        val isData = Modifier.DATA in symbol.modifiers
        val isValue = Modifier.VALUE in symbol.modifiers
        if (!isData && !isValue) return null
        if (Modifier.SEALED in symbol.modifiers) return null
        if (symbol.annotations.any { it.shortName.asString() == "DispatchOn" }) return null
        // `@PacketType` on a data class is a Stage D variant — emit its standalone
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
        // Slice 4 / 7a / 7b restriction (still in force): LengthFromString,
        // LengthFromList, and RemainingBytesScalarList are terminal-only —
        // their bodies consume a (possibly externally-bounded) trailing byte
        // range and the emit logic doesn't model trailing fields after a
        // variable-length tail. Slice 5b lifted the non-terminal-Conditional
        // restriction.
        // Slice 8: at most one RemainingLength per message (multiple var-int
        // remaining-length fields would have ambiguous bounding semantics).
        if (fields.count { it is FieldSpec.RemainingLength } > 1) return null
        for ((index, field) in fields.withIndex()) {
            if (field is FieldSpec.LengthFromString && index != fields.lastIndex) return null
            if (field is FieldSpec.LengthFromList && index != fields.lastIndex) return null
            if (field is FieldSpec.RemainingBytesScalarList && index != fields.lastIndex) return null
            if (field is FieldSpec.RemainingBytesPayload && index != fields.lastIndex) return null
        }

        val pkg = symbol.packageName.asString()
        // Slice 10b: if the data class declares <P : Payload> but no
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
        )
    }

    /**
     * Stage H slice 10b — detect whether the `@ProtocolMessage` data
     * class declares a `<P : Payload>` type parameter. Returns the
     * binding (`typeVariableName`, derived `codecParameterName`,
     * `Payload` bound) when exactly one type parameter is present
     * and its upper bound is `com.ditchoom.buffer.codec.Payload`.
     *
     * Slice 10b narrow: at most one type parameter, single Payload
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
     * before. Stage D variants are written nested inside the sealed
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

    private fun analyzeField(
        param: KSValueParameter,
        messageWireOrder: Endianness,
        ownerSimpleName: String,
        isTerminal: Boolean,
        params: List<KSValueParameter>,
        index: Int,
        payloadTypeParameter: PayloadTypeParameter? = null,
    ): FieldSpec? {
        // Stage E — `@WhenTrue` opens a separate analysis path: nullability is
        // required, the inner shape is built from the non-null type, and the
        // result wraps in `FieldSpec.Conditional`. The non-conditional analysis
        // below stays unchanged for any field without `@WhenTrue`.
        val whenTrueAnn =
            param.annotations.firstOrNull { it.shortName.asString() == "WhenTrue" }
        if (whenTrueAnn != null) {
            return analyzeConditionalField(
                param = param,
                whenTrueAnn = whenTrueAnn,
                messageWireOrder = messageWireOrder,
                ownerSimpleName = ownerSimpleName,
                params = params,
                index = index,
            )
        }
        var lengthPrefixed: KSAnnotation? = null
        var lengthFromAnn: KSAnnotation? = null
        var remainingBytesAnn: KSAnnotation? = null
        var remainingLengthAnn: KSAnnotation? = null
        var wireBytesAnn: KSAnnotation? = null
        var useCodecAnn: KSAnnotation? = null
        for (ann in param.annotations) {
            when (ann.shortName.asString()) {
                "WireOrder" -> { /* allowed on scalars */ }
                "LengthPrefixed" -> lengthPrefixed = ann
                "LengthFrom" -> lengthFromAnn = ann
                "RemainingBytes" -> remainingBytesAnn = ann
                "RemainingLength" -> remainingLengthAnn = ann
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
            // Stage G slice 7b — `@RemainingBytes val: List<S>` where S is
            // a single-byte scalar. Mutually exclusive with @LengthFrom /
            // @LengthPrefixed / @WireBytes / @RemainingLength on the SAME
            // parameter (note: `@RemainingLength` on a SIBLING field is
            // expected — the slice 8 MQTT vector pairs them).
            // Stage H slice 10a — `@RemainingBytes @UseCodec val: P` where
            // P : Payload routes to RemainingBytesPayload.
            // Stage H slice 10b — `@RemainingBytes val: P` where P is the
            // type parameter `<P : Payload>` of the enclosing data class
            // routes to RemainingBytesPayload via ConstructorInjected.
            if (lengthFromAnn != null || lengthPrefixed != null || wireBytesAnn != null) return null
            if (remainingLengthAnn != null) return null
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
            if (typeQname != "kotlin.collections.List") return null
            return analyzeRemainingBytesScalarListField(param, type, ownerSimpleName)
        }

        if (remainingLengthAnn != null) {
            // Stage G slice 8 — `@RemainingLength val: UInt`. Reads/writes
            // the MQTT v3 var-int and bounds subsequent decode. Mutually
            // exclusive with all other field-shape annotations.
            if (lengthFromAnn != null || lengthPrefixed != null || wireBytesAnn != null) return null
            val name = param.name?.asString() ?: return null
            val typeQname = type.declaration.qualifiedName?.asString()
            if (typeQname != "kotlin.UInt") return null
            return FieldSpec.RemainingLength(name = name, ownerSimpleName = ownerSimpleName)
        }

        if (lengthFromAnn != null) {
            // Stage E slice 4 / Stage G slice 7a — `@LengthFrom` field types:
            //   - `String` (slice 4): body is a single UTF-8 string sized by sibling.
            //   - `List<T>` where T is a `@ProtocolMessage data class` (slice 7a):
            //     body is a sequence of nested messages, byte-bounded by sibling.
            // Mutually exclusive with `@LengthPrefixed` / `@WireBytes` on the same
            // parameter. R1 (adjacent-LF migration suggestion) is independent.
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
                else -> null
            }
        }

        if (lengthPrefixed != null) {
            // `@LengthPrefixed` and `@WireBytes` together is meaningless and
            // out of scope for this emitter; bail rather than try to interpret.
            if (wireBytesAnn != null) return null
            val prefixWidth = readLengthPrefix(lengthPrefixed)
            val qualified = type.declaration.qualifiedName?.asString()
            if (qualified == "kotlin.String") {
                // Slice 5a: `@LengthPrefixed val: String` is now allowed at any
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

        val qualified = type.declaration.qualifiedName?.asString() ?: return null
        val kind = SUPPORTED_SCALARS[qualified]
        if (kind == null) {
            // Stage E slice 3 — value-class field. Only the natural-width
            // unannotated path is in scope; @WireBytes / @WireOrder on the
            // outer parameter widen this and are deferred to a later slice.
            if (wireBytesAnn != null) return null
            if (param.annotations.any { it.shortName.asString() == "WireOrder" }) return null
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
        // Signed scalars only support the natural-width default-order path here.
        // Manual byte assembly stays unsigned-only — sign extension on a partial
        // read is its own design and not load-bearing for any Stage C–H vector.
        if (kind.isSigned && (wireBytes != kind.width || resolved != Endianness.Default)) return null
        return FieldSpec.Scalar(
            name = name,
            kind = kind,
            resolvedWireOrder = resolved,
            wireBytes = wireBytes,
        )
    }

    /**
     * Stage E slice 3 — value-class field analysis for the parent
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
        // Slice 3 limits the inner scalar to its natural-width default-order
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
            // Slice 9 follow-up: propagate the value class's own
            // `@ProtocolMessage(wireOrder)` so multi-byte inner kinds
            // (UShort/UInt) assemble bytes correctly during peek-side
            // reconstruction in the sequential walk. Defaults to
            // Endianness.Default (collapses to big-endian) when the
            // value class doesn't declare an order, matching slice 3's
            // prior behavior.
            valueClassWireOrder = readMessageWireOrder(decl),
        )
    }

    /**
     * Stage E slice 4 / Stage G slice 9 — `@LengthFrom("ref") val:
     * String`. Two source-expression forms:
     *   - Simple-name `"sibling"` (slice 4): sibling is a numeric
     *     `Scalar`; body byte count = `sibling.toInt()`.
     *   - Dotted `"sibling.property"` (slice 9): sibling is a
     *     value-class field; body byte count = `sibling.property`
     *     (the property returns `Int` directly).
     *
     * Returns null silently for any shape the validator already
     * names — the validator's diagnostic is the user-facing surface.
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
     * Slice 4 / Stage G slice 9 — resolve a `@LengthFrom` annotation
     * argument into a typed `LengthSource`. Simple-name `"sibling"`
     * → `LengthSource.Sibling`; dotted `"sibling.property"` →
     * `LengthSource.ValueClassProperty`. Returns null when the shape
     * is out of scope (missing sibling, declared-after, simple form
     * with non-numeric sibling, dotted form with non-value-class
     * sibling or non-Int-returning property) — the validator's
     * diagnostic is the user-facing surface.
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
            // kind set (slice 4).
            val siblingQname = siblingType.declaration.qualifiedName?.asString() ?: return null
            val siblingKind = SUPPORTED_SCALARS[siblingQname] ?: return null
            if (siblingKind !in PEEKABLE_LENGTH_FROM_SIBLING_KINDS) return null
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
        if (innerKind !in PEEKABLE_LENGTH_FROM_SIBLING_KINDS) return null
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

    private val PEEKABLE_LENGTH_FROM_SIBLING_KINDS =
        setOf(ScalarKind.UByte, ScalarKind.Byte, ScalarKind.UShort, ScalarKind.UInt)

    /**
     * Stage G slice 7a — `@LengthFrom("siblingField") val: List<T>`
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
     * Stage G slice 7b — `@RemainingBytes val: List<S>` where `S`
     * is a single-byte scalar (`UByte` / `Byte`).
     *
     * Returns null silently for any shape outside the slice 7b
     * narrow set: list of multi-byte scalar (would need order-aware
     * read), list of `@ProtocolMessage` element (would need element
     * codec dispatch in the read loop), or list of value-class
     * scalar.
     */
    private fun analyzeRemainingBytesScalarListField(
        param: KSValueParameter,
        listType: KSType,
        ownerSimpleName: String,
    ): FieldSpec.RemainingBytesScalarList? {
        val name = param.name?.asString() ?: return null
        val typeArgs = listType.arguments
        if (typeArgs.size != 1) return null
        val elementType = typeArgs[0].type?.resolve() ?: return null
        if (elementType.isError || elementType.isMarkedNullable) return null
        val elementQname = elementType.declaration.qualifiedName?.asString() ?: return null
        val elementKind = SUPPORTED_SCALARS[elementQname] ?: return null
        if (elementKind !in REMAINING_BYTES_LIST_ELEMENT_KINDS) return null
        return FieldSpec.RemainingBytesScalarList(
            name = name,
            ownerSimpleName = ownerSimpleName,
            elementKind = elementKind,
        )
    }

    /**
     * Slice 7b — `@RemainingBytes val: List<S>` element-kind set.
     * Single-byte scalars only; the read loop is `while position <
     * limit` and the per-iteration call is a single
     * `buffer.readUByte()` / `buffer.readByte()` with no order
     * concerns. Wider kinds and `@ProtocolMessage` elements compose
     * with `@RemainingBytes` by extension but defer until a vector
     * requires them.
     */
    private val REMAINING_BYTES_LIST_ELEMENT_KINDS =
        setOf(ScalarKind.UByte, ScalarKind.Byte)

    /**
     * Stage H slice 10a — `@RemainingBytes @UseCodec(C::class) val: P`
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
     * Stage H slice 10a — does this type implement
     * `com.ditchoom.buffer.codec.Payload`? Used in `analyzeField` to
     * route `@RemainingBytes @UseCodec` on a Payload-typed field to
     * `RemainingBytesPayload` instead of the default
     * `RemainingBytesScalarList` (which requires a `List<S>` shape).
     */
    private fun KSType.implementsPayload(): Boolean {
        val decl = declaration as? KSClassDeclaration ?: return false
        if (decl.qualifiedName?.asString() == PAYLOAD_QNAME) return true
        return decl.getAllSuperTypes().any { st ->
            st.declaration.qualifiedName?.asString() == PAYLOAD_QNAME
        }
    }

    /**
     * Stage H slice 10b — does this type refer to the message's
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
     * Stage E slices 2–3 — `@WhenTrue` analysis (Locked Decision row 19).
     *
     * Pipeline: parse the expression into a typed `WhenTrueExpression`
     * → resolve the source against the prior siblings into a
     * `ConditionRef` → analyze the bound parameter's inner shape →
     * wrap into `FieldSpec.Conditional`. Each step returns `null` to
     * abort silently when the shape is out-of-scope; the validator in
     * `ProtocolMessageProcessor` surfaces the user-facing diagnostic.
     *
     * Slice 5 will grow [analyzeConditionalInner] to recognise
     * `@LengthPrefixed val: String` inners; nothing else in this
     * function changes for that step.
     */
    private fun analyzeConditionalField(
        param: KSValueParameter,
        whenTrueAnn: KSAnnotation,
        messageWireOrder: Endianness,
        ownerSimpleName: String,
        params: List<KSValueParameter>,
        index: Int,
    ): FieldSpec? {
        if (!boundParameterIsConditionalShape(param)) return null
        val name = param.name?.asString() ?: return null

        val expression = parseWhenTrueExpression(whenTrueAnn) ?: return null
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
     * when the predicate is false). Annotations beyond `@WhenTrue`
     * itself are limited to `@LengthPrefixed` (slice 3.5);
     * `@WireBytes` / `@WireOrder` widen the shape and land in a
     * later slice.
     */
    private fun boundParameterIsConditionalShape(param: KSValueParameter): Boolean {
        val type = param.type.resolve()
        if (type.isError) return false
        if (!type.isMarkedNullable) return false
        return param.annotations.all {
            val n = it.shortName.asString()
            n == "WhenTrue" || n == "LengthPrefixed"
        }
    }

    /**
     * Parse the `@WhenTrue("…")` expression literal into a typed
     * shape. Returns `null` for malformed annotation arguments and
     * for paths deeper than one dot — both are silently rejected by
     * the emitter and named by the validator.
     */
    private fun parseWhenTrueExpression(whenTrueAnn: KSAnnotation): WhenTrueExpression? {
        val raw =
            whenTrueAnn.arguments
                .firstOrNull { it.name?.asString() == "expression" }
                ?.value as? String
                ?: return null
        val parts = raw.split('.')
        return when (parts.size) {
            1 -> WhenTrueExpression.Simple(parts[0])
            2 -> WhenTrueExpression.Dotted(parts[0], parts[1])
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
        expression: WhenTrueExpression,
        params: List<KSValueParameter>,
        boundIndex: Int,
    ): ConditionRef? {
        val sibling = locatePriorSibling(expression.siblingName, params, boundIndex) ?: return null
        return when (expression) {
            is WhenTrueExpression.Simple -> resolveSimpleCondition(expression, sibling)
            is WhenTrueExpression.Dotted -> resolveDottedCondition(expression, sibling)
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
        expression: WhenTrueExpression.Simple,
        sibling: KSValueParameter,
    ): ConditionRef? {
        val sourceType = sibling.type.resolve()
        if (sourceType.isError || sourceType.isMarkedNullable) return null
        if (sourceType.declaration.qualifiedName?.asString() != "kotlin.Boolean") return null
        return ConditionRef.Sibling(expression.siblingName)
    }

    private fun resolveDottedCondition(
        expression: WhenTrueExpression.Dotted,
        sibling: KSValueParameter,
    ): ConditionRef? {
        val sourceType = sibling.type.resolve()
        if (sourceType.isError || sourceType.isMarkedNullable) return null
        val siblingDecl = sourceType.declaration as? KSClassDeclaration ?: return null
        if (Modifier.VALUE !in siblingDecl.modifiers) return null
        // Slice 3 peek-side reconstructs the value class via its
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
        if (SUPPORTED_SCALARS[innerQname] !in PEEKABLE_VALUE_CLASS_INNER_KINDS) return null
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
     * slice 3.5 widens to `@LengthPrefixed val: String?` for the MQTT
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
        val innerType = param.type.resolve().makeNotNullable()
        val qualified = innerType.declaration.qualifiedName?.asString() ?: return null
        if (lengthPrefixedAnn != null) {
            // Slice 3.5 widens the inner universe to LengthPrefixed
            // String only. LengthPrefixed @ProtocolMessage bodies are
            // doctrine-row-19 valid but defer until a vector requires
            // them.
            if (qualified != "kotlin.String") return null
            return ConditionalInner.LengthPrefixedString(
                prefixWidth = readLengthPrefix(lengthPrefixedAnn),
                prefixWireOrder = messageWireOrder,
            )
        }
        val kind = SUPPORTED_SCALARS[qualified] ?: return null
        return ConditionalInner.Scalar(kind = kind)
    }

    private fun conditionalInnerNullableTypeName(inner: ConditionalInner): TypeName =
        when (inner) {
            is ConditionalInner.Scalar -> scalarTypeName(inner.kind).copy(nullable = true)
            is ConditionalInner.LengthPrefixedString -> STRING_NULLABLE_TN
        }

    /**
     * Slice 3 peek reconstructs the sibling value class by reading
     * the inner scalar bytes and calling the value class's primary
     * constructor. Only the kinds wired into `appendPeekFixedScalar`
     * are accepted; wider scalars need slice 5's order-aware peek
     * path. Boolean is excluded because a value-class around a
     * Boolean is degenerate (the property accessor would just return
     * the wrapped value) and not load-bearing for any in-scope
     * vector.
     */
    private val PEEKABLE_VALUE_CLASS_INNER_KINDS =
        setOf(ScalarKind.UByte, ScalarKind.Byte)

    /**
     * Slice 6.5 — peek-side reconstruction kinds for `@DispatchOn`
     * value-class discriminators. Slice 6's narrow set was just
     * single-byte; slice 6.5 widens to 2/4-byte unsigned kinds for
     * real-spec multi-byte discriminators (e.g., HTTP/2's first 4
     * bytes packed as `length<<8 | type`). `appendPeekFixedScalar`
     * with the discriminator value class's `wireOrder` handles the
     * byte assembly.
     *
     * `ULong` and signed multi-byte kinds are still rejected — they
     * would need parallel peek paths (ULong promotion, signed
     * sign-extension), and no in-scope discriminator vector
     * requires them.
     */
    private val PEEKABLE_DISPATCHER_INNER_KINDS =
        setOf(ScalarKind.UByte, ScalarKind.Byte, ScalarKind.UShort, ScalarKind.UInt)

    /**
     * Stage E — typed shape of the `@WhenTrue("…")` expression
     * literal. Closed by doctrine row 19: only the simple-name and
     * one-level dotted forms are valid; deeper paths are a compile
     * error and never reach the analyzer.
     */
    private sealed interface WhenTrueExpression {
        val siblingName: String

        data class Simple(
            override val siblingName: String,
        ) : WhenTrueExpression

        data class Dotted(
            override val siblingName: String,
            val propertyName: String,
        ) : WhenTrueExpression
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
        }

    /**
     * Stage E slice 2 — read expression for a natural-width scalar. Used by
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
        }

    /**
     * Stage E slice 2 — write statement for a natural-width scalar given an
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

    private fun buildFileSpec(shape: CodecShape): FileSpec {
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
                        // Slice 10c — every codec carrying a typed payload field
                        // gets a `Partial` nested class plus a `partial(buffer,
                        // context)` decode entry. For the slice 10a (object)
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
     * Stage H slice 10b — emit
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
                // Slice 10c — for the slice 10b (class) shape, `Partial` is a
                // nested class (independent type parameter <P : Payload>) and
                // `partial<P>(buffer, context)` lives on a companion object.
                // Companion-side placement matters: consumers must be able to
                // call `MqttPublishV3Codec.partial<JpegImage>(buffer, context)`
                // WITHOUT instantiating the surrounding generic codec class
                // (the whole point of slice 10b's Partial is to defer the
                // codec choice past header decode).
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
        // Slice 8 — when @RemainingLength is present, fields BEFORE it
        // emit normally; the var-int + setLimit emits at its position;
        // fields AFTER it run inside `try { ... } finally {
        // setLimit(outer) }` so the buffer's outer limit is restored
        // even on decode failure. The constructor call becomes the
        // try-block's value expression, returned by the function.
        val rlIndex = shape.fields.indexOfFirst { it is FieldSpec.RemainingLength }
        val ctorArgs = shape.fields.joinToString(", ") { "${it.name} = ${it.name}" }
        if (rlIndex < 0) {
            for (field in shape.fields) appendDecodeField(body, field)
            body.addStatement("return %T(%L)", messageType, ctorArgs)
        } else {
            for (i in 0..rlIndex) appendDecodeField(body, shape.fields[i])
            body.beginControlFlow("return try")
            for (i in (rlIndex + 1) until shape.fields.size) appendDecodeField(body, shape.fields[i])
            body.addStatement("%T(%L)", messageType, ctorArgs)
            body.nextControlFlow("finally")
            val rlField = shape.fields[rlIndex] as FieldSpec.RemainingLength
            body.addStatement("buffer.setLimit(__%LOuterLimit)", rlField.name)
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
            is FieldSpec.RemainingBytesScalarList -> appendDecodeRemainingBytesScalarList(body, field)
            is FieldSpec.RemainingBytesPayload -> appendDecodeRemainingBytesPayload(body, field)
            is FieldSpec.RemainingLength -> appendDecodeRemainingLength(body, field)
            is FieldSpec.ValueClassScalar -> appendDecodeValueClassScalar(body, field)
            is FieldSpec.Conditional -> appendDecodeConditional(body, field)
        }
    }

    private fun buildEncodeFun(
        shape: CodecShape,
        messageType: TypeName = shape.messageClassName,
    ): FunSpec {
        val body = CodeBlock.builder()
        for (field in shape.fields) {
            when (field) {
                is FieldSpec.Scalar -> appendEncodeScalar(body, field, shape.ownerSimpleName)
                is FieldSpec.LengthPrefixedMessage -> appendEncodeLengthPrefixed(body, field)
                is FieldSpec.LengthPrefixedString -> appendEncodeLengthPrefixedString(body, field)
                is FieldSpec.LengthFromString -> appendEncodeLengthFromString(body, field)
                is FieldSpec.LengthFromList -> appendEncodeLengthFromList(body, field)
                is FieldSpec.RemainingBytesScalarList -> appendEncodeRemainingBytesScalarList(body, field)
                is FieldSpec.RemainingBytesPayload -> appendEncodeRemainingBytesPayload(body, field)
                is FieldSpec.RemainingLength -> appendEncodeRemainingLength(body, field)
                is FieldSpec.ValueClassScalar -> appendEncodeValueClassScalar(body, field)
                is FieldSpec.Conditional -> appendEncodeConditional(body, field)
            }
        }
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
     * Stage H slice 10c / 10f — gate for emitting the `Partial` decode
     * pattern. Partial is emitted whenever a message carries a typed
     * payload field (slice 10a `@UseCodec` shape or slice 10b `<P :
     * Payload>` shape); `RemainingBytesPayload` is the marker for both.
     *
     * Slice 10f lifts the slice 10c `@RemainingLength` carve-out. When
     * a shape combines `@RemainingLength` with a typed payload (the
     * MQTT v3 PUBLISH §3.3 wire shape), the `Partial` captures the
     * outer buffer limit at partial-decode time (the same local that
     * `appendDecodeRemainingLength` already emits as
     * `__<rlName>OuterLimit`); `complete()` runs the payload decode
     * inside the var-int-narrowed bound (correct for payload bounding)
     * and restores the outer limit via `try/finally` (correct for
     * caller cleanup). Both correctness concerns the slice 10c carve-
     * out flagged are handled by capturing the outer limit on the
     * Partial and restoring on completion — no consumer-visible API
     * change versus the no-RL path.
     */
    private fun shouldEmitPartial(shape: CodecShape): Boolean = shape.fields.any { it is FieldSpec.RemainingBytesPayload }

    /**
     * Stage H slice 10c — emit the nested `Partial` class. The `Partial`
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
     *   - Slice 10a (concrete payload): no type parameter on `Partial`.
     *     `complete(): MessageType` uses the `@UseCodec`-pinned codec.
     *   - Slice 10b (generic payload `<P : Payload>`): `Partial<P :
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
        // Slice 10f — when the headers contain @RemainingLength, the
        // partial decode mid-walk runs `setLimit(position + RL.toInt())`
        // and stashes the prior limit in `__<rlName>OuterLimit`. Capture
        // that local on the Partial so `complete()` can restore it.
        val hasRemainingLength = shape.fields.any { it is FieldSpec.RemainingLength }

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
        if (hasRemainingLength) {
            ctorBuilder.addParameter("outerLimit", INT)
        }
        ctorBuilder.addParameter("buffer", READ_BUFFER_CN)
        ctorBuilder.addParameter("context", DECODE_CONTEXT_CN)
        classBuilder.primaryConstructor(ctorBuilder.build())
        // Buffer + context (and the captured outerLimit, if any) are
        // private state used by complete(); no public getter — the
        // consumer should never re-read the buffer or fiddle with the
        // limit through the Partial.
        if (hasRemainingLength) {
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
            buildPartialCompleteFun(shape, payloadTypeParameter, payloadField, hasRemainingLength),
        )
        return classBuilder.build()
    }

    /**
     * Stage H slice 10c — emit the `Partial.complete(...)` function.
     *
     * Slice 10a path: the codec is `@UseCodec`-pinned, so `complete()`
     * takes no parameters and calls `<UserCodec>.decode(buffer, context)`
     * directly. Slice 10b path: the codec is supplied at the call site,
     * so `complete(payloadCodec: Decoder<P>)` accepts the decoder and
     * calls `payloadCodec.decode(buffer, context)`. Both branches share
     * the trailing constructor call.
     */
    private fun buildPartialCompleteFun(
        shape: CodecShape,
        payloadTypeParameter: PayloadTypeParameter?,
        payloadField: FieldSpec.RemainingBytesPayload,
        hasRemainingLength: Boolean,
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
        if (hasRemainingLength) {
            // Slice 10f — payload decode runs inside the var-int-
            // narrowed bound (correct for payload bounding); the outer
            // limit is restored via try/finally so the caller's outer
            // limit survives even if the user codec throws.
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
     * Stage H slice 10c — emit the `partial(buffer, context)` decode
     * entry. Slice 10a places this as a member function on the codec
     * `object`; slice 10b places it on the codec class's companion
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
        for (field in headerFields) appendDecodeField(body, field)
        val rlField = shape.fields.firstOrNull { it is FieldSpec.RemainingLength } as? FieldSpec.RemainingLength
        val outerLimitArgs =
            if (rlField != null) {
                // appendDecodeRemainingLength emits the outer-limit
                // local as `__<rlName>OuterLimit` (see line ~2964); pass
                // it through so the Partial can restore on complete.
                listOf("outerLimit = __${rlField.name}OuterLimit")
            } else {
                emptyList()
            }
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
     * Stage H slice 10c — companion-object wrapper for the slice 10b
     * `partial<P>(...)` entry. Companion-side placement is required:
     * a member-side `partial(...)` would force the consumer to first
     * construct `MqttPublishV3Codec(somePayloadCodec)` just to call
     * `partial`, defeating the slice 10b purpose of deferring the
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
     * Stage H slice 10c — derive the property `TypeName` for a header
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
            is FieldSpec.LengthFromList ->
                ClassName("kotlin.collections", "List").parameterizedBy(field.elementClassName)
            is FieldSpec.RemainingBytesScalarList ->
                ClassName("kotlin.collections", "List").parameterizedBy(scalarTypeName(field.elementKind))
            is FieldSpec.RemainingBytesPayload ->
                error(
                    "partialFieldTypeName called on a RemainingBytesPayload field — caller " +
                        "should strip the payload field before mapping header types.",
                )
            is FieldSpec.RemainingLength -> U_INT
            is FieldSpec.ValueClassScalar -> field.valueClassType
            is FieldSpec.Conditional -> field.nullableTypeName
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
        // Slice 8 — any `@RemainingLength` field fully determines wireSize:
        // leading-fixed bytes + var-int bytes for the remainingLength value +
        // remainingLength.toInt() bytes (which per spec covers everything
        // after the var-int). This early return overrides the BackPatch
        // collapses for Conditional / LengthPrefixedString below — the
        // user-supplied remainingLength gives us an Exact answer even when
        // the trailing fields would otherwise force BackPatch.
        val remainingLengthField =
            shape.fields.firstOrNull { it is FieldSpec.RemainingLength } as? FieldSpec.RemainingLength
        if (remainingLengthField != null) {
            val prefixBytes =
                shape.fields
                    .takeWhile { it !is FieldSpec.RemainingLength }
                    .filterIsInstance<FieldSpec.FixedSize>()
                    .sumOf { it.wireBytes }
            builder.addStatement(
                "val __remainingLengthBytes = %L",
                varIntByteCountExpr("value.${remainingLengthField.name}"),
            )
            builder.addStatement(
                "return %T.Exact(%L + __remainingLengthBytes + value.%L.toInt())",
                WIRE_SIZE_CN,
                prefixBytes,
                remainingLengthField.name,
            )
            return builder.build()
        }
        // Locked Decision row 19: any `@WhenTrue` field collapses the message
        // wireSize to BackPatch — we don't attempt conditional-Exact arithmetic.
        if (shape.fields.any { it is FieldSpec.Conditional }) {
            builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
            return builder.build()
        }
        // Stage H slice 10a: a `@RemainingBytes @UseCodec val: P` field's
        // wireSize comes from the user codec, which may be Exact or
        // BackPatch. Slice 10a takes the conservative BackPatch path
        // unconditionally — promoting to runtime-Exact-via-cast (mirroring
        // LengthPrefixedMessage) is a follow-on once we have a vector
        // where the size optimization actually matters.
        if (shape.fields.any { it is FieldSpec.RemainingBytesPayload }) {
            builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
            return builder.build()
        }
        // Locked Decision row 15: any `@LengthPrefixed val: String` collapses
        // wireSize to BackPatch (pre-measuring the UTF-8 byte length is the
        // walk the BackPatch path collapses into the single writeString call).
        // Slice 5a — the rule applies regardless of position now that LPS
        // String can appear non-terminally.
        if (shape.fields.any { it is FieldSpec.LengthPrefixedString }) {
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
                // Slice 4 / 9 — body byte count comes from the
                // resolved LengthSource (sibling.toInt() for simple,
                // sibling.property for dotted). User-trusted (row 16).
                val prefixBytes = scalarHeaderBytes(shape)
                builder.addStatement(
                    "return %T.Exact(%L + %L)",
                    WIRE_SIZE_CN,
                    prefixBytes,
                    terminal.source.encodeAccessor(),
                )
            }
            is FieldSpec.LengthFromList -> {
                // Slice 7a / 9 — same Exact shape via LengthSource.
                val prefixBytes = scalarHeaderBytes(shape)
                builder.addStatement(
                    "return %T.Exact(%L + %L)",
                    WIRE_SIZE_CN,
                    prefixBytes,
                    terminal.source.encodeAccessor(),
                )
            }
            is FieldSpec.RemainingBytesScalarList -> {
                // Slice 7b — body byte count = list.size * element width.
                // Both factors are knowable at encode time, so Exact.
                val prefixBytes = scalarHeaderBytes(shape)
                val elementWidth = terminal.elementKind.width
                builder.addStatement(
                    "return %T.Exact(%L + value.%L.size * %L)",
                    WIRE_SIZE_CN,
                    prefixBytes,
                    terminal.name,
                    elementWidth,
                )
            }
            is FieldSpec.RemainingBytesPayload ->
                error(
                    "RemainingBytesPayload terminal shape should be handled by the BackPatch " +
                        "early-return at the top of buildWireSizeFun; reaching this branch " +
                        "indicates a missed early return.",
                )
            is FieldSpec.RemainingLength ->
                error(
                    "RemainingLength terminal shape should be handled by the early-return at the " +
                        "top of buildWireSizeFun; reaching this branch indicates a missed early " +
                        "return.",
                )
            else -> {
                val total = shape.fields.sumOfFixedWireBytes()
                builder.addStatement("return %T.Exact(%L)", WIRE_SIZE_CN, total)
            }
        }
        return builder.build()
    }

    /**
     * Stage E slice 3 — sum the `wireBytes` of every `FixedSize` field
     * in the list. Variable-length fields (`LengthPrefixed*`,
     * `Conditional`) contribute 0 and are filtered out by the
     * `filterIsInstance` step. Callers that require the result to
     * cover every field gate on terminal shape before calling.
     */
    private fun List<FieldSpec>.sumOfFixedWireBytes(): Int =
        filterIsInstance<FieldSpec.FixedSize>().sumOf { it.wireBytes }

    private fun buildPeekFrameFun(shape: CodecShape): FunSpec {
        val builder =
            FunSpec
                .builder("peekFrameSize")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("stream", STREAM_PROCESSOR_CN)
                .addParameter("baseOffset", INT)
                .returns(PEEK_RESULT_CN)
        // Slice 8 — `@RemainingLength` carries the message's full
        // remaining byte count via a leading var-int. peek can compute
        // the total exactly: leading-fixed bytes + var-int bytes +
        // var-int value. This OVERRIDES the RemainingBytesScalarList
        // NoFraming below — once a RemainingLength field is in scope,
        // the bound is known statically and the trailing
        // RemainingBytesScalarList is naturally bounded.
        val rlField =
            shape.fields.firstOrNull { it is FieldSpec.RemainingLength } as? FieldSpec.RemainingLength
        if (rlField != null) {
            appendPeekRemainingLength(builder, shape, rlField)
            return builder.build()
        }
        // Slice 7b — any RemainingBytesScalarList field collapses peek to
        // NoFraming. The body's byte count comes from the caller-set
        // buffer limit, which the stream-side peek can't see; consumers
        // must use outer-protocol framing (e.g., MQTT's fixed-header
        // remaining-length) to determine the bounded read.
        if (shape.fields.any { it is FieldSpec.RemainingBytesScalarList }) {
            builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
            return builder.build()
        }
        // Slice 10a — same NoFraming collapse for `@RemainingBytes
        // @UseCodec val: P`. Body byte count is whatever the user codec
        // reads against the caller-set limit; slice 10d's outer
        // dispatcher will own peek by reading the fixed header's
        // remaining-length first.
        if (shape.fields.any { it is FieldSpec.RemainingBytesPayload }) {
            builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
            return builder.build()
        }
        // All-FixedSize messages collapse to a single arithmetic check —
        // no walk needed, and the generated code is significantly tighter
        // than the sequential path (which would emit a per-field
        // availability check + offset advance).
        if (shape.fields.all { it is FieldSpec.FixedSize }) {
            val total = shape.fields.sumOfFixedWireBytes()
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
     * Slice 5a — general sequential peek walk.
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
     * Replaces the slice 2/3/3.5/4 specialized peek paths
     * (single-LPS-terminal, single-Conditional-terminal,
     * single-LengthFromString-terminal). Equivalent results for those
     * shapes; previously-skipped shapes (multiple sequential
     * variable-length fields, non-terminal Conditional, non-terminal
     * LengthPrefixedString) become emitable here.
     */
    /**
     * Slice 8 — peek for messages containing a `@RemainingLength`
     * field. The leading-fixed bytes' availability is checked first,
     * then the var-int is peek-decoded byte-by-byte (1–4 bytes,
     * yielding `NeedsMoreData` mid-stream). Once the var-int value
     * is known, total = leading-fixed bytes + var-int byte count +
     * value.
     */
    private fun appendPeekRemainingLength(
        builder: FunSpec.Builder,
        shape: CodecShape,
        rlField: FieldSpec.RemainingLength,
    ) {
        // Bytes before the @RemainingLength field. Slice 8 narrow:
        // these MUST be FixedSize. Non-fixed leading fields would
        // require routing through the sequential walk first; defer.
        val priorFields = shape.fields.takeWhile { it !is FieldSpec.RemainingLength }
        val nonFixed = priorFields.filterNot { it is FieldSpec.FixedSize }
        if (nonFixed.isNotEmpty()) {
            // Fall back to the sequential walk + NoFraming-via-RemainingBytes
            // pattern; here we just mark NoFraming defensively. analyzeField
            // doesn't currently produce this shape.
            builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
            return
        }
        val priorBytes = priorFields.filterIsInstance<FieldSpec.FixedSize>().sumOf { it.wireBytes }
        val body = CodeBlock.builder()
        body.addStatement(
            "if (stream.available() - baseOffset < %L) return %T.NeedsMoreData",
            priorBytes + 1,
            PEEK_RESULT_CN,
        )
        // Peek-decode var-int starting at baseOffset + priorBytes.
        // 1–4 byte loop with continuation-bit check; per-byte
        // availability check yields NeedsMoreData if a continuation
        // byte indicates more data than the stream has.
        val multiplierVar = "__${rlField.name}Multiplier"
        val byteVar = "__${rlField.name}Byte"
        val bytesReadVar = "__${rlField.name}BytesRead"
        body.addStatement("var %L = 1u", multiplierVar)
        body.addStatement("var %L = 0u", rlField.name)
        body.addStatement("var %L = 0", bytesReadVar)
        body.addStatement("var %L = 0", byteVar)
        body.beginControlFlow("do")
        body.beginControlFlow("if (%L >= 4)", bytesReadVar)
        body.addStatement(
            "throw %T(fieldPath = %S, bufferPosition = baseOffset + %L + %L, expected = %S, actual = %S)",
            DECODE_EXCEPTION_CN,
            "${rlField.ownerSimpleName}.${rlField.name}",
            priorBytes,
            bytesReadVar,
            "variable-byte-integer at most 4 bytes (MQTT §2.2.3)",
            "5+ bytes with continuation bit set",
        )
        body.endControlFlow()
        body.addStatement(
            "if (stream.available() - baseOffset < %L + %L + 1) return %T.NeedsMoreData",
            priorBytes,
            bytesReadVar,
            PEEK_RESULT_CN,
        )
        body.addStatement(
            "%L = stream.peekByte(baseOffset + %L + %L).toInt() and 0xFF",
            byteVar,
            priorBytes,
            bytesReadVar,
        )
        body.addStatement("%L += (%L and 0x7F).toUInt() * %L", rlField.name, byteVar, multiplierVar)
        body.addStatement("%L *= 128u", multiplierVar)
        body.addStatement("%L += 1", bytesReadVar)
        body.endControlFlow() // do
        body.addStatement("while ((%L and 0x80) != 0)", byteVar)
        body.addStatement(
            "val total = %L + %L + %L.toInt()",
            priorBytes,
            bytesReadVar,
            rlField.name,
        )
        body.addStatement(
            "return if (stream.available() - baseOffset >= total) %T.Complete(total) else %T.NeedsMoreData",
            PEEK_RESULT_CN,
            PEEK_RESULT_CN,
        )
        builder.addCode(body.build())
    }

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
                        // Slice 9 follow-up: pass the value class's wireOrder
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
                is FieldSpec.RemainingBytesScalarList ->
                    error(
                        "RemainingBytesScalarList should be handled by buildPeekFrameFun's " +
                            "upfront NoFraming short-circuit before reaching the sequential walk.",
                    )
                is FieldSpec.RemainingBytesPayload ->
                    error(
                        "RemainingBytesPayload should be handled by buildPeekFrameFun's " +
                            "upfront NoFraming short-circuit before reaching the sequential walk.",
                    )
                is FieldSpec.RemainingLength ->
                    error(
                        "RemainingLength should be handled by buildPeekFrameFun's " +
                            "appendPeekRemainingLength short-circuit before reaching the sequential walk.",
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
                    sources +=
                        when (val c = field.condition) {
                            is ConditionRef.Sibling -> c.name
                            is ConditionRef.ValueClassProperty -> c.siblingName
                        }
                is FieldSpec.LengthFromString -> sources += field.source.siblingName
                is FieldSpec.LengthFromList -> sources += field.source.siblingName
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
     * Slice 5a — peek a length-prefixed body (`@LengthPrefixed val:
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
     * Slice 5a / 7a / 9 — peek a `@LengthFrom`-style slot (terminal
     * `LengthFromString` or `LengthFromList`) inside the sequential
     * walk. The sibling local was peek-stashed earlier (slice 4
     * Scalar-sibling case) or the sibling value-class instance was
     * peek-stashed and reconstructed (slice 9 dotted case); the
     * `LengthSource.decodeAccessor()` produces the right Int
     * expression for either form. The Int.MAX_VALUE guard only
     * applies to the simple form (the dotted property returns Int).
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
     * Slice 5a — peek a `@WhenTrue` slot inside the sequential walk.
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
        val narrow =
            when (field.kind) {
                ScalarKind.UByte -> ".toUByte()"
                ScalarKind.UShort -> ".toUShort()"
                ScalarKind.UInt, ScalarKind.ULong -> ""
                ScalarKind.Byte, ScalarKind.Short, ScalarKind.Int, ScalarKind.Long ->
                    error("manual scalar decode is unsigned-only; analyzeField rejects signed manual-path fields")
                ScalarKind.Boolean ->
                    error("Boolean is pinned to the natural-read path; analyzeField rejects manual-path Boolean")
            }
        body.addStatement("val %L = %L%L", field.name, combined, narrow)
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
                ScalarKind.Byte, ScalarKind.Short, ScalarKind.Int, ScalarKind.Long -> return // signed kinds skip the manual path entirely
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
        val widePromote =
            when (field.kind) {
                ScalarKind.UByte, ScalarKind.UShort -> ".toUInt()"
                ScalarKind.UInt, ScalarKind.ULong -> ""
                ScalarKind.Byte, ScalarKind.Short, ScalarKind.Int, ScalarKind.Long ->
                    error("manual scalar encode is unsigned-only; analyzeField rejects signed manual-path fields")
                ScalarKind.Boolean ->
                    error("Boolean is pinned to the natural-write path; analyzeField rejects manual-path Boolean")
            }
        val wide = "$accessor$widePromote"
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
     * Stage E slice 3 — emit decode for a `@JvmInline value class` field
     * with a single supported-scalar inner. Reads the inner scalar at
     * natural width and constructs the value class via its primary
     * constructor. The local is named after the outer parameter so
     * dotted-form `@WhenTrue` resolvers can address it as `<name>.<property>`.
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
     * Stage E slice 3 — emit encode for a value-class field. Unwraps
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
     * Stage E slice 2 — emit a `@WhenTrue` decode block.
     *
     * Generated shape:
     * ```
     * val <name>: <NullableType> = if (<source>) <readExpr> else null
     * ```
     *
     * The source is a sibling `Boolean` local already in scope (decode visits
     * fields in constructor order, and analyzeConditionalField has verified
     * the source is declared before this field). `readExpr` is the natural-
     * width scalar read for the inner kind (slice 2 restricts inner to a
     * natural-width Scalar; slice 5 widens to LengthPrefixedString).
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
        }
    }

    /**
     * Stage E slice 2/3 — emit a `@WhenTrue` encode block.
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
     * `ConditionalInner.LengthPrefixedString` (slice 3.5).
     *
     * Predicate-false branch writes nothing (zero bytes for the slot, per
     * Locked Decision row 19). Predicate-true with `value.<name> == null`
     * throws `EncodeException` with field-path attribution (row 20).
     */
    private fun appendEncodeConditional(
        body: CodeBlock.Builder,
        field: FieldSpec.Conditional,
    ) {
        body.beginControlFlow("if (%L)", encodeConditionAccessor(field.condition))
        val localName = "${field.name}Value"
        body.addStatement(
            "val %L = value.%L ?: throw %T(fieldPath = %S, reason = %S)",
            localName,
            field.name,
            ENCODE_EXCEPTION_CN,
            "${field.ownerSimpleName}.${field.name}",
            "@WhenTrue(\"${conditionExpressionLiteral(field.condition)}\") predicate is true but field is null",
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
        }
        body.endControlFlow()
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
        }

    /**
     * Encode-side predicate accessor. Encode reads from the message
     * value, so all paths start at `value.`. Simple form is
     * `value.<sibling>`; dotted form is `value.<sibling>.<property>`.
     */
    private fun encodeConditionAccessor(condition: ConditionRef): String =
        when (condition) {
            is ConditionRef.Sibling -> "value.${condition.name}"
            is ConditionRef.ValueClassProperty -> "value.${condition.siblingName}.${condition.propertyName}"
        }

    /**
     * Reconstruct the original `@WhenTrue("...")` expression literal
     * for use in `EncodeException` field-path messages (row 20).
     */
    private fun conditionExpressionLiteral(condition: ConditionRef): String =
        when (condition) {
            is ConditionRef.Sibling -> condition.name
            is ConditionRef.ValueClassProperty -> "${condition.siblingName}.${condition.propertyName}"
        }

    /**
     * Slice 3 / 5a / 6.5 — value-class inner-scalar peek. Used for
     * predicate-source reconstruction in `@WhenTrue` (slice 3) and
     * for discriminator reconstruction in `@DispatchOn` (slice 6.5).
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
            ScalarKind.ULong, ScalarKind.Short, ScalarKind.Int, ScalarKind.Long ->
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
     * Slice 3.5 — emit the prefix read + Int.MAX_VALUE guard + length
     * Int conversion shared by length-prefixed-string field decode
     * and the conditional `@LengthPrefixed @WhenTrue` decode path.
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
     * Slice 3.5 — shared BackPatch encoder for length-prefixed-string
     * fields and the conditional `@LengthPrefixed @WhenTrue` encode
     * path (Locked Decision row 15).
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
        // BackPatch pattern (Locked Decision row 15): reserve prefix slot, write
        // the body via the runtime's UTF-8 path, measure byte count from the
        // position delta, patch the prefix in place, restore position past the
        // body. The runtime's `writeString(text, Charset.UTF8)` is zero-`ByteArray`
        // on JVM / Apple / JS; the WASM and nonJvm `writeString` paths still
        // allocate one ByteArray per call (Locked Decision row 16, deferred to a
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
     * Stage E slice 4 — emit decode for `@LengthFrom("siblingField")
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
     * Stage E slice 4 — emit encode for `@LengthFrom("siblingField")
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
     * Stage G slice 7a — emit decode for `@LengthFrom("siblingField")
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
     * Stage G slice 7a — emit encode for `@LengthFrom("siblingField")
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
     * Stage H slice 10a — emit decode for
     * `@RemainingBytes @UseCodec(C::class) val: P`. Delegates to the
     * user-supplied `C.decode(buffer, context)` against whatever
     * `buffer.limit()` already says — same caller-bounds-buffer contract
     * as `RemainingBytesScalarList`. The outer dispatcher (slice 10d
     * for MQTT) sets the limit before calling this codec.
     */
    private fun appendDecodeRemainingBytesPayload(
        body: CodeBlock.Builder,
        field: FieldSpec.RemainingBytesPayload,
    ) {
        body.addStatement(
            "val %L = %L.decode(buffer, context)",
            field.name,
            field.source.codecReceiver(),
        )
    }

    /**
     * Stage H slice 10a — emit encode for
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
     * Stage G slice 7b — emit decode for `@RemainingBytes val: List<S>`
     * where `S` is a single-byte scalar. Loops `while (buffer.position()
     * < buffer.limit())` reading the per-element scalar; the caller is
     * responsible for setting `buffer.limit()` to bound the read region
     * (typical: outer protocol carries a remaining-length field that the
     * dispatcher uses to set the limit before delegating).
     *
     * Generated shape:
     * ```
     * val <name> = mutableListOf<S>()
     * while (buffer.position() < buffer.limit()) {
     *     <name> += buffer.readUByte()  // or readByte()
     * }
     * ```
     */
    private fun appendDecodeRemainingBytesScalarList(
        body: CodeBlock.Builder,
        field: FieldSpec.RemainingBytesScalarList,
    ) {
        val elementTypeName = scalarTypeName(field.elementKind)
        body.addStatement("val %L = mutableListOf<%T>()", field.name, elementTypeName)
        body.beginControlFlow("while (buffer.position() < buffer.limit())")
        body.addStatement("%L += %L", field.name, naturalScalarReadExpr(field.elementKind))
        body.endControlFlow()
    }

    /**
     * Stage G slice 7b — emit encode for `@RemainingBytes val: List<S>`.
     * Iterates the list and writes each element via the natural-width
     * scalar write expression. The encoded byte count equals
     * `list.size * elementWidth`; the caller is expected to know this
     * via the wireSize Exact value (slice 7b's variant codec emits
     * `Exact(priorBytes + value.<name>.size * elementWidth)`).
     */
    private fun appendEncodeRemainingBytesScalarList(
        body: CodeBlock.Builder,
        field: FieldSpec.RemainingBytesScalarList,
    ) {
        body.beginControlFlow("for (__elem in value.%L)", field.name)
        body.addStatement(naturalScalarWriteStatement(field.elementKind, "__elem"))
        body.endControlFlow()
    }

    /**
     * Stage G slice 8 — emit decode for `@RemainingLength val: UInt`.
     * Reads the MQTT v3.1.1 §2.2.3 variable-byte-integer (1–4 bytes,
     * continuation bit `0x80`, malformed if > 4 bytes), saves the
     * outer buffer limit (so the surrounding decode body can restore
     * it via `try`/`finally`), and sets `buffer.setLimit(position +
     * value.toInt())` so subsequent fields decode within the bounded
     * region. Throws `DecodeException` for malformed var-ints (5th
     * byte still has continuation bit set).
     */
    private fun appendDecodeRemainingLength(
        body: CodeBlock.Builder,
        field: FieldSpec.RemainingLength,
    ) {
        val outerLimitVar = "__${field.name}OuterLimit"
        body.addStatement("val %L = buffer.limit()", outerLimitVar)
        // Inline var-int read so each codec is self-contained (no shared
        // runtime helper). The MQTT spec rejects 5th continuation byte;
        // we surface as DecodeException with field-path attribution.
        val multiplierVar = "__${field.name}Multiplier"
        val byteVar = "__${field.name}Byte"
        val bytesReadVar = "__${field.name}BytesRead"
        body.addStatement("var %L = 1u", multiplierVar)
        body.addStatement("var %L = 0u", field.name)
        body.addStatement("var %L = 0", bytesReadVar)
        body.addStatement("var %L = 0", byteVar)
        body.beginControlFlow("do")
        body.beginControlFlow("if (%L >= 4)", bytesReadVar)
        body.addStatement(
            "throw %T(fieldPath = %S, bufferPosition = -1, expected = %S, actual = %S)",
            DECODE_EXCEPTION_CN,
            "${field.ownerSimpleName}.${field.name}",
            "variable-byte-integer at most 4 bytes (MQTT §2.2.3)",
            "5+ bytes with continuation bit set",
        )
        body.endControlFlow()
        body.addStatement("%L = buffer.readUByte().toInt()", byteVar)
        body.addStatement("%L += (%L and 0x7F).toUInt() * %L", field.name, byteVar, multiplierVar)
        body.addStatement("%L *= 128u", multiplierVar)
        body.addStatement("%L += 1", bytesReadVar)
        body.endControlFlow() // do
        body.addStatement("while ((%L and 0x80) != 0)", byteVar)
        // The bounded limit applies to subsequent field decodes (the
        // post-RemainingLength block runs inside try/finally that
        // restores `outerLimitVar`).
        body.addStatement("buffer.setLimit(buffer.position() + %L.toInt())", field.name)
    }

    /**
     * Stage G slice 8 — emit encode for `@RemainingLength val: UInt`.
     * Writes the value as a MQTT v3 variable-byte-integer (1–4 bytes,
     * continuation bit on every byte except the last). User is
     * responsible for the value matching the trailing wire byte
     * count (row 16 trust contract — measuring would require
     * encoding all subsequent fields twice).
     */
    private fun appendEncodeRemainingLength(
        body: CodeBlock.Builder,
        field: FieldSpec.RemainingLength,
    ) {
        val remainingVar = "__${field.name}Remaining"
        val byteVar = "__${field.name}Byte"
        body.addStatement("var %L = value.%L", remainingVar, field.name)
        body.beginControlFlow("do")
        body.addStatement("var %L = (%L and 0x7Fu).toInt()", byteVar, remainingVar)
        body.addStatement("%L = %L shr 7", remainingVar, remainingVar)
        body.beginControlFlow("if (%L > 0u)", remainingVar)
        body.addStatement("%L = %L or 0x80", byteVar, byteVar)
        body.endControlFlow()
        body.addStatement("buffer.writeUByte(%L.toUByte())", byteVar)
        body.endControlFlow() // do
        body.addStatement("while (%L > 0u)", remainingVar)
    }

    /**
     * Stage G slice 8 — Kotlin sub-expression for the var-int byte
     * count of a `UInt` value, used by `wireSize` and the dispatcher
     * peek to compute total message bytes without a runtime call.
     */
    private fun varIntByteCountExpr(valueExpr: String): String =
        "if ($valueExpr <= 127u) 1 " +
            "else if ($valueExpr <= 16383u) 2 " +
            "else if ($valueExpr <= 2097151u) 3 " +
            "else 4"

    /**
     * Slice 4 / 5a — order-aware single-scalar peek for the prefix
     * walk. Single-byte kinds (`UByte` / `Byte`) read directly;
     * unsigned multi-byte kinds (`UShort` / `UInt`) assemble bytes
     * BE/LE per the field's resolvedWireOrder. Wider and signed
     * multi-byte kinds aren't required by any in-scope vector; they
     * would need parallel peek paths (signed sign-extension, ULong
     * promotion).
     *
     * `offsetExpr` is the Kotlin sub-expression interpolated into
     * `stream.peekByte(baseOffset + <offsetExpr>)`. Callers with a
     * fixed offset pass `"0"` / `"7"`; the slice 5a sequential walk
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
            ScalarKind.ULong, ScalarKind.Short, ScalarKind.Int, ScalarKind.Long ->
                error(
                    "peek-side reconstruction for sibling kind ${field.kind} not implemented; " +
                        "the analyzer should have rejected this shape until the wider peek path lands.",
                )
        }
    }

    /**
     * Slice 4 — Int.MAX_VALUE guard for `@LengthFrom` siblings whose
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
     * Stage C / 5a — peek-assemble a length-prefix as a `UInt`.
     * `prefixOffsetExpr` is interpolated into
     * `stream.peekByte(baseOffset + <expr>)`; callers with a fixed
     * offset pass `"0"` / `"$N"`, the slice 5a sequential walk
     * passes the running-offset variable.
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
     * Stage D — analyze a `@ProtocolMessage sealed interface` parent.
     *
     * Returns null (silently skip) when the parent carries
     * `@DispatchOn` (Stage F surface), when the parent has zero
     * sealed subclasses, or when any direct subclass fails to fit
     * Stage A/B/C/D's variant shape (missing `@PacketType`,
     * out-of-range value, not a `data class`, or its own field shape
     * is not analyzable). The validator in `ProtocolMessageProcessor`
     * surfaces user-facing diagnostics for the missing-`@PacketType`
     * and duplicate-value cases; this method's silence keeps the
     * emitter consistent with Stage A/B/C "out of shape, no codec".
     */
    private fun analyzeSealedDispatcher(symbol: KSClassDeclaration): DispatcherShape? {
        if (symbol.annotations.any { it.shortName.asString() == "DispatchOn" }) return null
        val subclasses = symbol.getSealedSubclasses().toList()
        if (subclasses.isEmpty()) return null
        val variants = mutableListOf<VariantSpec>()
        val seenValues = mutableSetOf<Int>()
        for (sub in subclasses) {
            if (Modifier.DATA !in sub.modifiers) return null
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
     * Stage F slice 6 — analyze a `@DispatchOn`-annotated sealed
     * parent into a `DispatchOnDispatcherShape`.
     *
     * Returns null (silent skip) when the parent doesn't carry
     * `@DispatchOn`, when the discriminator type isn't a value class
     * with a single supported-scalar inner, when the discriminator
     * has zero or multiple `@DispatchValue` properties (the
     * validator names this case), or when any variant fails to fit
     * the slice 6 shape (data class, has `@PacketType(value = N)`,
     * first parameter is the discriminator type). The validator
     * surfaces user-facing diagnostics; the emitter's silence keeps
     * the "out of shape, no codec" pattern intact.
     */
    private fun analyzeDispatchOnSealedDispatcher(
        symbol: KSClassDeclaration,
    ): DispatchOnDispatcherShape? {
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
        // Slice 6.5: peek-side reconstruction supports single-byte kinds
        // (slice 6) plus 2/4-byte unsigned kinds. ULong / signed multi-byte
        // discriminators aren't required by any in-scope vector and would
        // need parallel peek paths.
        if (innerKind !in PEEKABLE_DISPATCHER_INNER_KINDS) return null
        // Slice 6.5: read the discriminator value class's `@ProtocolMessage(
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
        if (returnType.declaration.qualifiedName?.asString() != "kotlin.Int") return null
        val dispatchValuePropertyName = dispatchProp.simpleName.asString()

        // Stage H slice 10d — detect whether the sealed parent declares
        // `<P : Payload>` / `<out P : Payload>`. When present, the
        // dispatcher emits as a generic class binding `P` and threads a
        // constructor-injected `payloadCodec: Codec<P>` to any variant
        // that itself carries `<P : Payload>` (slice 10b shape).
        // `<Nothing>`-typed variants don't need the codec — they keep
        // static-object references.
        val payloadTypeParameter = detectPayloadTypeParameter(symbol)

        val subclasses = symbol.getSealedSubclasses().toList()
        if (subclasses.isEmpty()) return null
        val variants = mutableListOf<DispatchOnVariantSpec>()
        val seenValues = mutableSetOf<Int>()
        for (sub in subclasses) {
            if (Modifier.DATA !in sub.modifiers) return null
            val packetType =
                sub.annotations.firstOrNull { it.shortName.asString() == "PacketType" } ?: return null
            val rawValue =
                packetType.arguments
                    .firstOrNull { it.name?.asString() == "value" }
                    ?.value as? Int ?: return null
            if (rawValue !in 0..255) return null
            if (!seenValues.add(rawValue)) return null
            val ctor = sub.primaryConstructor ?: return null
            val firstParam = ctor.parameters.firstOrNull() ?: return null
            val firstParamQname = firstParam.type.resolve().declaration.qualifiedName?.asString()
            if (firstParamQname != discriminatorDecl.qualifiedName?.asString()) return null
            // Variant must analyze cleanly via the existing data-class path.
            // The header field will be a FieldSpec.ValueClassScalar (slice 3).
            analyze(sub) ?: return null
            // Slice 10d: detect whether the variant itself is generic
            // (slice 10b shape — `<P : Payload>` type parameter on the
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
            variants = variants,
            payloadTypeParameter = payloadTypeParameter,
        )
    }

    private fun classifyVariantWireSize(shape: CodecShape): VariantWireSize {
        // Locked Decision row 19: any `@WhenTrue` field collapses wireSize to
        // BackPatch — including inside a sealed variant.
        if (shape.fields.any { it is FieldSpec.Conditional }) return VariantWireSize.BackPatch
        // Slice 5a: any `@LengthPrefixed val: String` (terminal or otherwise)
        // collapses wireSize to BackPatch per row 15. The variant codec's
        // own wireSize already produces BackPatch in this case (see
        // buildWireSizeFun); the dispatcher size table needs to know not to
        // attempt a literal sum.
        if (shape.fields.any { it is FieldSpec.LengthPrefixedString }) return VariantWireSize.BackPatch
        // Slice 10a: same BackPatch classification — variant codec's own
        // wireSize is BackPatch (see buildWireSizeFun's RemainingBytesPayload
        // early-return); the dispatcher must skip the runtime-Exact cast.
        if (shape.fields.any { it is FieldSpec.RemainingBytesPayload }) return VariantWireSize.BackPatch
        return when (shape.fields.lastOrNull()) {
            is FieldSpec.LengthPrefixedMessage -> VariantWireSize.RuntimeExact
            // Slice 4: a LengthFromString variant's body byte count is the
            // sibling value at encode time — same shape as a runtime-Exact
            // length-prefixed-message body. Dispatcher size emission walks
            // the variant codec's wireSize, which is already Exact for this
            // shape.
            is FieldSpec.LengthFromString -> VariantWireSize.RuntimeExact
            // Slice 7a: same Exact-via-sibling shape as LengthFromString.
            is FieldSpec.LengthFromList -> VariantWireSize.RuntimeExact
            // Slice 7b: variant codec's own wireSize is Exact (priorBytes +
            // list.size * elementWidth); RuntimeExact lets the dispatcher
            // forward without re-deriving.
            is FieldSpec.RemainingBytesScalarList -> VariantWireSize.RuntimeExact
            // Slice 8: variant codec's wireSize is Exact (prior + var-int
            // bytes + value.toInt()); RuntimeExact lets the dispatcher
            // forward.
            is FieldSpec.RemainingLength -> VariantWireSize.RuntimeExact
            is FieldSpec.Scalar, is FieldSpec.ValueClassScalar, null ->
                VariantWireSize.LiteralExact(shape.fields.sumOfFixedWireBytes())
            is FieldSpec.LengthPrefixedString, is FieldSpec.Conditional -> VariantWireSize.BackPatch
            // Slice 10a: handled by the upfront BackPatch short-circuit; this
            // branch is unreachable because the early return collapses any
            // shape carrying a RemainingBytesPayload field before the
            // terminal-shape `when` runs.
            is FieldSpec.RemainingBytesPayload -> VariantWireSize.BackPatch
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
     * Slice 6 — emit the bit-packed dispatcher codec. Uses the
     * peek-without-consume model on decode (save position, decode
     * discriminator via its codec, restore position, dispatch);
     * the variant codec then reads from the original position with
     * its first field being the discriminator value class.
     *
     * Stage H slice 10d — when the sealed parent is generic
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
     * Stage H slice 10d — `class FooCodec<P : Payload>(payloadCodec:
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
                ).addSuperinterface(CODEC_CN.parameterizedBy(parentTypeRef))
        for (variant in shape.variants) {
            val fieldName = variant.genericInstanceFieldName ?: continue
            // Variant codec is `class <VariantName>Codec<P : Payload>(
            //   payloadCodec: Codec<P>)` per slice 10b. Construct it
            // once with the dispatcher's codec.
            val fieldType = variant.codecClassName.parameterizedBy(typeVar)
            builder.addProperty(
                com.squareup.kotlinpoet.PropertySpec
                    .builder(fieldName, fieldType, KModifier.PRIVATE)
                    .initializer("%T(%L)", variant.codecClassName, binding.codecParameterName)
                    .build(),
            )
        }
        return builder
            .addFunction(buildDispatchOnDecodeFun(shape, parentTypeRef))
            .addFunction(buildDispatchOnEncodeFun(shape, parentTypeRef))
            .addFunction(buildDispatchOnWireSizeFun(shape, parentTypeRef))
            .addFunction(buildDispatchOnPeekFun(shape))
            .build()
    }

    /**
     * Stage H slice 10d — return the parent's TypeName as it should
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
     * Stage H slice 10d — return the Kotlin code-block that yields
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
     * Stage H slice 10d — encode-side `is` check for a variant. For
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
     * Stage H slice 10d — typed cast TypeName for the variant codec
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
        body.addStatement("val __dispatchValue = __discriminator.%L", shape.dispatchValuePropertyName)
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
        return FunSpec
            .builder("decode")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("buffer", READ_BUFFER_CN)
            .addParameter("context", DECODE_CONTEXT_CN)
            .returns(parentTypeRef)
            .addCode(body.build())
            .build()
    }

    private fun buildDispatchOnEncodeFun(
        shape: DispatchOnDispatcherShape,
        parentTypeRef: TypeName,
    ): FunSpec {
        val body = CodeBlock.builder()
        // Slice 10d — generic variants are smart-cast to their
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
        // reconstruct the value class. Slice 6 supports natural-width
        // single-byte kinds via appendPeekFixedScalar — the same path the
        // slice 3 value-class @WhenTrue source uses. Wider discriminators
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
        body.addStatement("val __dispatchValue = __discriminator.%L", shape.dispatchValuePropertyName)
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
     * Stage F slice 6 — bit-packed dispatcher shape.
     *
     * The discriminator is a `@JvmInline value class` whose
     * `@DispatchValue`-annotated property produces an `Int` to match
     * against `@PacketType.value`. Variants are data classes whose
     * first constructor parameter is the discriminator type, so the
     * variant codec naturally reads/writes the discriminator byte
     * via the slice 3 `FieldSpec.ValueClassScalar` path.
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
        val variants: List<DispatchOnVariantSpec>,
        /**
         * Stage H slice 10d — present when the sealed parent declares
         * `<out P : Payload>` (or `<P : Payload>`). Causes the
         * dispatcher to emit as a generic class
         * `class FooCodec<P : Payload>(payloadCodec: Codec<P>) :
         * Codec<Foo<P>>` instead of `object FooCodec : Codec<Foo>`.
         * Generic variants thread through `payloadCodec`;
         * `Nothing`-typed variants use static codec refs as before.
         */
        val payloadTypeParameter: PayloadTypeParameter? = null,
    )

    /**
     * Variant spec for `@DispatchOn` dispatch. Differs from
     * `VariantSpec` in carrying only the dispatch value (no
     * `wireSize` — the variant codec's own `wireSize` is the source
     * of truth, since the variant's bytes are exactly its body).
     *
     * Stage H slice 10d adds `genericInstanceFieldName`: when the
     * variant data class declares `<P : Payload>` (slice 10b shape),
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
         * Stage H slice 10b — when the @ProtocolMessage data class
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
    )

    /**
     * Stage H slice 10b — type-parameter binding for a generic-bounded
     * codec class. `typeVariableName` is the Kotlin type variable
     * (e.g., `P`); `codecParameterName` is the constructor parameter
     * holding the user-supplied codec (e.g., `payloadCodec`); `bound`
     * is the upper bound (always `Payload` for slice 10b).
     */
    private data class PayloadTypeParameter(
        val typeVariableName: String,
        val codecParameterName: String,
        val bound: ClassName,
    )

    private sealed interface FieldSpec {
        val name: String

        /**
         * Stage A onward — fields whose wire byte count is fixed at
         * compile time. The `peekFrameSize` prefix walk and the
         * fixed-size variant `wireSize` summation type-narrow to this
         * shape so they no longer need runtime casts to read
         * `wireBytes`.
         *
         * Slice 5 keeps this interface unchanged: the variable-length
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

        data class LengthPrefixedString(
            override val name: String,
            val ownerSimpleName: String,
            val prefixWidth: Int,
            val prefixWireOrder: Endianness,
        ) : FieldSpec

        /**
         * Stage G slice 8 — `@RemainingLength val: UInt` reads/writes
         * the MQTT v3.1.1 §2.2.3 variable-byte-integer (1–4 bytes,
         * continuation bit `0x80`, range `0..268,435,455`) AND sets
         * the buffer's limit on decode to `position + value` so
         * subsequent fields (including `@RemainingBytes` lists) are
         * bounded by the field's value. The buffer's outer limit is
         * restored before decode returns via try/finally.
         *
         * The MQTT spec calls this exact field "Remaining Length";
         * the annotation matches the spec terminology and semantics
         * one-to-one. Reusable beyond MQTT for any protocol that
         * carries a leading variable-length size header (Protobuf
         * varints differ in encoding — they're zig-zag and unbounded
         * — so a separate `@VarInt` annotation is the right path
         * for those).
         */
        data class RemainingLength(
            override val name: String,
            val ownerSimpleName: String,
        ) : FieldSpec

        /**
         * Stage G slice 7b — `@RemainingBytes val: List<S>` where
         * `S` is a single-byte scalar (`UByte` / `Byte`). The
         * decoder reads elements while `buffer.position() <
         * buffer.limit()`; the caller is responsible for setting
         * the limit externally (typical for protocols whose framing
         * carries the body byte count outside the codec's view, e.g.,
         * MQTT's fixed-header remaining-length variable-length
         * integer, parsed by an outer dispatcher).
         *
         * Slice 7b narrow: element kind is single-byte. Multi-byte
         * unsigned scalars (UShort/UInt) and `@ProtocolMessage`
         * elements compose with `@RemainingBytes` by extension but
         * defer until a vector requires them.
         */
        data class RemainingBytesScalarList(
            override val name: String,
            val ownerSimpleName: String,
            val elementKind: ScalarKind,
        ) : FieldSpec

        /**
         * Stage H slice 10a — `@RemainingBytes @UseCodec(C::class) val:
         * P` where `P` extends `com.ditchoom.buffer.codec.Payload` and
         * `C` is a Kotlin `object` implementing `Codec<P>`. Decode
         * delegates to `C.decode(buffer, context)` against the bounded
         * buffer; encode delegates to `C.encode(buffer, value.<name>,
         * context)`. Same caller-bounds-buffer contract as
         * `RemainingBytesScalarList`: an outer dispatcher (slice 10d
         * for MQTT) sets `buffer.limit()` to bound the payload region
         * before this codec runs.
         *
         * Slice 10a narrow: terminal-only (no fields after the
         * payload), no generics on the outer message (slice 10b),
         * concrete `Payload`-typed field (no `<P : Payload>`
         * type parameter), no `Partial` decode pattern (slice 10c),
         * no aggregator (slice 10d), no `expect`/`actual` resolution
         * across platforms (slice 10e — single-platform `object`
         * declaration only).
         */
        data class RemainingBytesPayload(
            override val name: String,
            val ownerSimpleName: String,
            val payloadType: TypeName,
            val source: PayloadCodecSource,
        ) : FieldSpec

        /**
         * Stage G slice 7a — `@LengthFrom("siblingField") val: List<T>`
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
         * Slice 7a narrow: element must be a `@ProtocolMessage data
         * class`. List of scalar (`List<UByte>` / `List<Int>` etc.)
         * is the slice 7b shape with `@RemainingBytes`.
         *
         * `source` carries the resolved length carrier: slice 4's
         * sibling-Scalar form (`LengthSource.Sibling`) or slice 9's
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
         * Stage E slice 4 / Stage G slice 9 —
         * `@LengthFrom("ref") val: String`. The body wire bytes are
         * determined by a non-adjacent length carrier decoded
         * earlier. Decode reads `source.localAccessor` UTF-8 bytes;
         * encode writes the body without a prefix slot — the user
         * is responsible for setting the carrier to the correct
         * UTF-8 byte count (row 16 trust contract).
         *
         * `source` carries the resolved length carrier. See
         * `LengthSource` — slice 4's sibling-Scalar form and slice
         * 9's dotted value-class-property form share this field
         * type.
         */
        data class LengthFromString(
            override val name: String,
            val ownerSimpleName: String,
            val source: LengthSource,
        ) : FieldSpec

        /**
         * Stage E slice 3 — a `@JvmInline value class` field whose primary
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
         * Stage E — `@WhenTrue` conditional wrapper. Slice 2/3 support
         * `ConditionalInner.Scalar` at natural width (no `@WireBytes`,
         * no `@WireOrder`); slice 3.5 widens `inner` to
         * `ConditionalInner.LengthPrefixedString` for the MQTT v3
         * CONNECT optional-field shape (`@LengthPrefixed @WhenTrue
         * val: String?`).
         *
         * `condition` carries the resolved source: slice 2's sibling-
         * Boolean form (`ConditionRef.Sibling`) and slice 3's dotted
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
     * Stage E — typed shape of a `@WhenTrue` field's bound (inner)
     * type. Doctrine row 19 lists the slot's underlying type universe
     * as anything Stages A/B/C/D already emit; the emitter implements
     * that universe one shape at a time:
     *   - `Scalar`: any natural-width supported scalar (slices 2/3).
     *   - `LengthPrefixedString`: `@LengthPrefixed val: String?`
     *     (slice 3.5).
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
    }

    /**
     * Stage E — resolved source of a `@WhenTrue` predicate.
     *
     * Slice 2's `Sibling` form names a sibling `Boolean` constructor
     * parameter declared before the bound field. Slice 3's
     * `ValueClassProperty` form names a sibling parameter (a value
     * class with a single supported-scalar inner) plus a `Boolean`-
     * returning `val` property declared on that value class.
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
    }

    /**
     * Stage G slice 9 — typed source of a `@LengthFrom` byte count.
     *
     * Closed sealed: row 18 lists the simple-name sibling form
     * (slice 4) and the dotted `<sibling>.<property>` form (slice 9)
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
         * Slice 4 simple form: sibling is a `Scalar` field. Body
         * byte count = `<sibling>.toInt()`. Decode applies an
         * `Int.MAX_VALUE` guard for kinds whose range exceeds Int
         * (UInt / ULong / Long).
         */
        data class Sibling(
            override val siblingName: String,
            val siblingKind: ScalarKind,
        ) : LengthSource

        /**
         * Slice 9 dotted form: sibling is a `value class` wrapping
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
         * for HTTP/2 SETTINGS, the slice 9 vector); little-endian
         * value-class siblings would need additional plumbing —
         * tracked alongside the @RemainingLength followup in
         * PHASE_9_RESET's deferred-decisions table.
         */
        data class ValueClassProperty(
            override val siblingName: String,
            val propertyName: String,
            val valueClassInnerKind: ScalarKind,
        ) : LengthSource
    }

    /**
     * Stage H — typed source of a `Codec<T>` instance for a
     * `RemainingBytesPayload` field. Mirrors the slice 9 `LengthSource`
     * pattern (doctrine #2: no nullable fields representing form
     * distinction; the type system enforces exhaustive `when`).
     *
     * Two forms:
     *   - `UserCodecObject` (slice 10a): the field carries
     *     `@UseCodec(Foo::class)` referencing a Kotlin `object`
     *     declaration. Emit calls `Foo.decode(...)` /
     *     `Foo.encode(...)` directly.
     *   - `ConstructorInjected` (slice 10b): the message has a
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
     * Slice 10a/10b — emit-side accessor for the user codec. Returns
     * the receiver Kotlin sub-expression for `<receiver>.decode(...)`
     * / `<receiver>.encode(...)` / `<receiver>.wireSize(...)` calls.
     */
    private fun PayloadCodecSource.codecReceiver(): CodeBlock =
        when (this) {
            is PayloadCodecSource.UserCodecObject -> CodeBlock.of("%T", codecType)
            is PayloadCodecSource.ConstructorInjected -> CodeBlock.of("%L", parameterName)
        }

    /**
     * Slice 9 — encode-side accessor for a LengthSource. Returns the
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
     * Slice 9 — decode-side accessor for a LengthSource. Returns the
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

    private enum class ScalarKind(
        val width: Int,
        val isSigned: Boolean,
    ) {
        // Boolean is a 1-byte scalar with no byte order and no `@WireBytes` narrowing.
        // Stage E precondition for `@WhenTrue` (Locked Decision row 19 mandates a
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
            )

        private val READ_BUFFER_CN = ClassName("com.ditchoom.buffer", "ReadBuffer")
        private val WRITE_BUFFER_CN = ClassName("com.ditchoom.buffer", "WriteBuffer")
        private val CODEC_CN = ClassName("com.ditchoom.buffer.codec", "Codec")
        private val DECODER_CN = ClassName("com.ditchoom.buffer.codec", "Decoder")
        private val DECODE_CONTEXT_CN = ClassName("com.ditchoom.buffer.codec", "DecodeContext")
        private val ENCODE_CONTEXT_CN = ClassName("com.ditchoom.buffer.codec", "EncodeContext")
        private val WIRE_SIZE_CN = ClassName("com.ditchoom.buffer.codec", "WireSize")
        private val PEEK_RESULT_CN = ClassName("com.ditchoom.buffer.codec", "PeekResult")
        private val STREAM_PROCESSOR_CN = ClassName("com.ditchoom.buffer.stream", "StreamProcessor")
        private val DECODE_EXCEPTION_CN = ClassName("com.ditchoom.buffer.codec", "DecodeException")
        private val ENCODE_EXCEPTION_CN = ClassName("com.ditchoom.buffer.codec", "EncodeException")
        private val CHARSET_CN = ClassName("com.ditchoom.buffer", "Charset")
        private val STRING_NULLABLE_TN = ClassName("kotlin", "String").copy(nullable = true)
    }
}
