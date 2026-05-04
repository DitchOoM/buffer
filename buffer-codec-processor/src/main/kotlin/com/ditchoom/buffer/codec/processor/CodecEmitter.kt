package com.ditchoom.buffer.codec.processor

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

        val fields = mutableListOf<FieldSpec>()
        val params = ctor.parameters
        for ((index, param) in params.withIndex()) {
            val isTerminal = index == params.lastIndex
            val field =
                analyzeField(param, messageWireOrder, ownerSimpleName, isTerminal, params, index) ?: return null
            fields += field
        }
        // Slice 2 restriction: a Conditional field can appear only at the terminal
        // position. Non-terminal Conditional needs the more general peekFrameSize
        // walk that interleaves prefix-chase + conditional-byte addition; that lands
        // alongside the slice 5 MQTT v3 CONNECT vector. Silently skip emit for the
        // non-terminal case for now (the validator's `validateWhenTrue` still
        // surfaces shape diagnostics).
        for ((index, field) in fields.withIndex()) {
            if (field is FieldSpec.Conditional && index != fields.lastIndex) return null
        }

        val pkg = symbol.packageName.asString()
        return CodecShape(
            packageName = pkg,
            messageClassName = classNameOf(symbol),
            ownerSimpleName = ownerSimpleName,
            codecSimpleName = "${ownerSimpleName}Codec",
            fields = fields,
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
        var wireBytesAnn: KSAnnotation? = null
        for (ann in param.annotations) {
            when (ann.shortName.asString()) {
                "WireOrder" -> { /* allowed on scalars */ }
                "LengthPrefixed" -> lengthPrefixed = ann
                "WireBytes" -> wireBytesAnn = ann
                else -> return null
            }
        }
        val name = param.name?.asString() ?: return null
        val type = param.type.resolve()
        if (type.isError) return null
        if (type.isMarkedNullable) return null

        if (lengthPrefixed != null) {
            // `@LengthPrefixed` and `@WireBytes` together is meaningless and
            // out of scope for this emitter; bail rather than try to interpret.
            if (wireBytesAnn != null) return null
            if (!isTerminal) return null
            val prefixWidth = readLengthPrefix(lengthPrefixed)
            val qualified = type.declaration.qualifiedName?.asString()
            if (qualified == "kotlin.String") {
                return FieldSpec.LengthPrefixedString(
                    name = name,
                    ownerSimpleName = ownerSimpleName,
                    prefixWidth = prefixWidth,
                    prefixWireOrder = messageWireOrder,
                )
            }
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
        )
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
        @Suppress("UNUSED_PARAMETER") messageWireOrder: Endianness,
        ownerSimpleName: String,
        params: List<KSValueParameter>,
        index: Int,
    ): FieldSpec? {
        if (!boundParameterIsConditionalShape(param)) return null
        val name = param.name?.asString() ?: return null

        val expression = parseWhenTrueExpression(whenTrueAnn) ?: return null
        val condition = resolveCondition(expression, params, index) ?: return null
        val inner = analyzeConditionalInner(param, name) ?: return null

        return FieldSpec.Conditional(
            name = name,
            ownerSimpleName = ownerSimpleName,
            condition = condition,
            nullableTypeName = scalarTypeName(inner.kind).copy(nullable = true),
            inner = inner,
        )
    }

    /**
     * Bound parameter must be nullable (so absence is representable
     * when the predicate is false) and may carry no annotations beyond
     * `@WhenTrue`. Composition with `@LengthPrefixed` / `@WireBytes`
     * widens the shape and lands in slice 5; today's emitter rejects
     * the combination silently.
     */
    private fun boundParameterIsConditionalShape(param: KSValueParameter): Boolean {
        val type = param.type.resolve()
        if (type.isError) return false
        if (!type.isMarkedNullable) return false
        return param.annotations.all { it.shortName.asString() == "WhenTrue" }
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
     * Analyze the bound parameter's inner shape. Slices 2/3 support
     * any natural-width supported scalar at `Endianness.Default`;
     * slice 5 widens this to `@LengthPrefixed val: String` for the
     * MQTT v3 CONNECT optional fields. When that lands, this function
     * branches on the bound parameter's annotations and returns a
     * sealed-typed inner; for now there is only one inner kind so a
     * sealed `ConditionalInner` would be premature.
     */
    private fun analyzeConditionalInner(
        param: KSValueParameter,
        name: String,
    ): FieldSpec.Scalar? {
        val innerType = param.type.resolve().makeNotNullable()
        val qualified = innerType.declaration.qualifiedName?.asString() ?: return null
        val kind = SUPPORTED_SCALARS[qualified] ?: return null
        return FieldSpec.Scalar(
            name = name,
            kind = kind,
            resolvedWireOrder = Endianness.Default,
            wireBytes = kind.width,
        )
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
            TypeSpec
                .objectBuilder(shape.codecSimpleName)
                .addSuperinterface(CODEC_CN.parameterizedBy(shape.messageClassName))
                .addFunction(buildDecodeFun(shape))
                .addFunction(buildEncodeFun(shape))
                .addFunction(buildWireSizeFun(shape))
                .addFunction(buildPeekFrameFun(shape))
                .build()
        return FileSpec
            .builder(shape.packageName, shape.codecSimpleName)
            .addType(codecType)
            .build()
    }

    private fun buildDecodeFun(shape: CodecShape): FunSpec {
        val body = CodeBlock.builder()
        for (field in shape.fields) {
            when (field) {
                is FieldSpec.Scalar -> appendDecodeScalar(body, field)
                is FieldSpec.LengthPrefixedMessage -> appendDecodeLengthPrefixed(body, field)
                is FieldSpec.LengthPrefixedString -> appendDecodeLengthPrefixedString(body, field)
                is FieldSpec.ValueClassScalar -> appendDecodeValueClassScalar(body, field)
                is FieldSpec.Conditional -> appendDecodeConditional(body, field)
            }
        }
        val ctorArgs = shape.fields.joinToString(", ") { "${it.name} = ${it.name}" }
        body.addStatement("return %T(%L)", shape.messageClassName, ctorArgs)
        return FunSpec
            .builder("decode")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("buffer", READ_BUFFER_CN)
            .addParameter("context", DECODE_CONTEXT_CN)
            .returns(shape.messageClassName)
            .addCode(body.build())
            .build()
    }

    private fun buildEncodeFun(shape: CodecShape): FunSpec {
        val body = CodeBlock.builder()
        for (field in shape.fields) {
            when (field) {
                is FieldSpec.Scalar -> appendEncodeScalar(body, field, shape.ownerSimpleName)
                is FieldSpec.LengthPrefixedMessage -> appendEncodeLengthPrefixed(body, field)
                is FieldSpec.LengthPrefixedString -> appendEncodeLengthPrefixedString(body, field)
                is FieldSpec.ValueClassScalar -> appendEncodeValueClassScalar(body, field)
                is FieldSpec.Conditional -> appendEncodeConditional(body, field)
            }
        }
        return FunSpec
            .builder("encode")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("buffer", WRITE_BUFFER_CN)
            .addParameter("value", shape.messageClassName)
            .addParameter("context", ENCODE_CONTEXT_CN)
            .addCode(body.build())
            .build()
    }

    private fun buildWireSizeFun(shape: CodecShape): FunSpec {
        val builder =
            FunSpec
                .builder("wireSize")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("value", shape.messageClassName)
                .addParameter("context", ENCODE_CONTEXT_CN)
                .returns(WIRE_SIZE_CN)
        // Locked Decision row 19: any `@WhenTrue` field collapses the message
        // wireSize to BackPatch — we don't attempt conditional-Exact arithmetic.
        if (shape.fields.any { it is FieldSpec.Conditional }) {
            builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
            return builder.build()
        }
        when (val terminal = shape.fields.lastOrNull()) {
            is FieldSpec.LengthPrefixedString -> {
                // Locked Decision row 15: @LengthPrefixed val: String defaults to BackPatch.
                // Pre-measuring the UTF-8 byte length would require a second walk that the
                // back-patch path collapses into the single writeString call.
                builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
            }
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
        val terminal = shape.fields.lastOrNull()
        if (terminal is FieldSpec.Conditional) {
            appendPeekConditional(builder, shape, terminal)
            return builder.build()
        }
        val (name, ownerSimpleName, prefixWidth, prefixWireOrder) =
            when (terminal) {
                is FieldSpec.LengthPrefixedMessage ->
                    PrefixPeek(terminal.name, terminal.ownerSimpleName, terminal.prefixWidth, terminal.prefixWireOrder)
                is FieldSpec.LengthPrefixedString ->
                    PrefixPeek(terminal.name, terminal.ownerSimpleName, terminal.prefixWidth, terminal.prefixWireOrder)
                else -> null
            } ?: run {
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
        val prefixOffset = scalarHeaderBytes(shape)
        val headerBytes = prefixOffset + prefixWidth
        val body = CodeBlock.builder()
        body.addStatement(
            "if (stream.available() - baseOffset < %L) return %T.NeedsMoreData",
            headerBytes,
            PEEK_RESULT_CN,
        )
        appendPeekPrefixAssembly(body, name, prefixWidth, prefixWireOrder, prefixOffset)
        body.beginControlFlow(
            "if (%L > (Int.MAX_VALUE - %L).toUInt())",
            "${name}Prefix",
            headerBytes,
        )
        body.addStatement(
            "throw %T(fieldPath = %S, bufferPosition = baseOffset + %L, expected = %S, actual = %P)",
            DECODE_EXCEPTION_CN,
            "$ownerSimpleName.$name",
            prefixOffset,
            "$headerBytes + length prefix <= \${Int.MAX_VALUE}",
            "$headerBytes + \${${name}Prefix}",
        )
        body.endControlFlow()
        body.addStatement("val total = %L + %L.toInt()", headerBytes, "${name}Prefix")
        body.addStatement(
            "return if (stream.available() - baseOffset >= total) %T.Complete(total) else %T.NeedsMoreData",
            PEEK_RESULT_CN,
            PEEK_RESULT_CN,
        )
        builder.addCode(body.build())
        return builder.build()
    }

    private data class PrefixPeek(
        val name: String,
        val ownerSimpleName: String,
        val prefixWidth: Int,
        val prefixWireOrder: Endianness,
    )

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
        body.addStatement(
            "val %L: %T = if (%L) %L else null",
            field.name,
            field.nullableTypeName,
            decodeConditionExpr(field.condition),
            naturalScalarReadExpr(field.inner.kind),
        )
    }

    /**
     * Stage E slice 2/3 — emit a `@WhenTrue` encode block.
     *
     * Generated shape:
     * ```
     * if (value.<source>) {
     *     val <name>Value = value.<name> ?: throw EncodeException(...)
     *     <writeStatement using `<name>Value`>
     * }
     * ```
     *
     * `<source>` is `value.<sibling>` for the simple form and
     * `value.<sibling>.<property>` for the dotted form.
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
        body.addStatement(naturalScalarWriteStatement(field.inner.kind, localName))
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
     * Stage E slice 2 — emit `peekFrameSize` for a message whose terminal
     * field is a `FieldSpec.Conditional` and whose preceding fields are all
     * `FieldSpec.Scalar` (the slice 2 vector shape).
     *
     * Walks the prefix scalars statically, peeks the boolean source at its
     * fixed offset, and adds the inner field's wire bytes only when the
     * predicate is true. Returns `Complete(total)` or `NeedsMoreData`.
     *
     * Slice 5 generalises this for variable-length prefix fields (MQTT v3
     * CONNECT has a leading `@LengthPrefixed` before its flags byte).
     */
    private fun appendPeekConditional(
        builder: FunSpec.Builder,
        shape: CodecShape,
        terminal: FieldSpec.Conditional,
    ) {
        val prefixFields = shape.fields.dropLast(1)
        // All preceding fields must be FixedSize for the slice 2/3 peek
        // walk; if any is variable-length, slice 5's more general walk lands.
        val fixedPrefix = prefixFields.filterIsInstance<FieldSpec.FixedSize>()
        if (fixedPrefix.size != prefixFields.size) {
            builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
            return
        }
        val sibling = resolvePeekSibling(fixedPrefix, terminal.condition)
        if (sibling == null) {
            // analyzeConditionalField guarantees the source is a sibling declared
            // before this field, so this branch is defensive — it would mean the
            // emitter and analyzer disagree.
            builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
            return
        }
        val prefixBytes = fixedPrefix.sumOf { it.wireBytes }
        val body = CodeBlock.builder()
        body.addStatement(
            "if (stream.available() - baseOffset < %L) return %T.NeedsMoreData",
            prefixBytes,
            PEEK_RESULT_CN,
        )
        appendPeekConditionResolution(body, sibling, terminal.condition)
        body.addStatement(
            "val total = %L + if (%L) %L else 0",
            prefixBytes,
            peekConditionLocalName(terminal.condition),
            terminal.inner.wireBytes,
        )
        body.addStatement(
            "return if (stream.available() - baseOffset >= total) %T.Complete(total) else %T.NeedsMoreData",
            PEEK_RESULT_CN,
            PEEK_RESULT_CN,
        )
        builder.addCode(body.build())
    }

    /**
     * Find the prefix field referenced by a `ConditionRef` and return
     * it together with its byte offset in the prefix walk. Returns
     * `null` if the sibling is missing or has the wrong type — that
     * mismatch indicates `analyzeConditionalField` and the prefix
     * walk disagree, so the peek falls back to `NoFraming`.
     */
    private fun resolvePeekSibling(
        fixedPrefix: List<FieldSpec.FixedSize>,
        condition: ConditionRef,
    ): PeekSibling? {
        val (siblingName, expectKind) =
            when (condition) {
                is ConditionRef.Sibling -> condition.name to PeekSiblingKind.Scalar
                is ConditionRef.ValueClassProperty -> condition.siblingName to PeekSiblingKind.ValueClassScalar
            }
        var offset = 0
        for (field in fixedPrefix) {
            if (field.name == siblingName) {
                val matches =
                    when (expectKind) {
                        PeekSiblingKind.Scalar -> field is FieldSpec.Scalar
                        PeekSiblingKind.ValueClassScalar -> field is FieldSpec.ValueClassScalar
                    }
                if (!matches) return null
                return PeekSibling(field, offset)
            }
            offset += field.wireBytes
        }
        return null
    }

    /**
     * Emit the peek-side resolution of the boolean predicate. For a
     * sibling-`Boolean` source, peek the byte directly. For a value-
     * class property, peek the inner scalar bytes, reconstruct the
     * value class, and call the predicate property.
     */
    private fun appendPeekConditionResolution(
        body: CodeBlock.Builder,
        sibling: PeekSibling,
        condition: ConditionRef,
    ) {
        when (condition) {
            is ConditionRef.Sibling -> {
                body.addStatement(
                    "val %L = stream.peekByte(baseOffset + %L) != 0.toByte()",
                    condition.name,
                    sibling.offset,
                )
            }
            is ConditionRef.ValueClassProperty -> {
                val field = sibling.field as FieldSpec.ValueClassScalar
                val rawVar = "${condition.siblingName}Raw"
                appendPeekFixedScalar(body, field.innerKind, rawVar, sibling.offset)
                body.addStatement(
                    "val %L = %T(%L).%L",
                    peekConditionLocalName(condition),
                    field.valueClassType,
                    rawVar,
                    condition.propertyName,
                )
            }
        }
    }

    private fun peekConditionLocalName(condition: ConditionRef): String =
        when (condition) {
            is ConditionRef.Sibling -> condition.name
            is ConditionRef.ValueClassProperty -> "${condition.siblingName}${condition.propertyName.replaceFirstChar { it.uppercase() }}"
        }

    private fun appendPeekFixedScalar(
        body: CodeBlock.Builder,
        kind: ScalarKind,
        targetVar: String,
        offset: Int,
    ) {
        // Slice 3 peek covers the natural-width single-byte scalars used by
        // the SmallFlags vector (UByte). Wider scalars and manual-byte-
        // assembly inner kinds compose with slice 5's variable-prefix walk
        // and aren't required by any in-scope @WhenTrue source today.
        when (kind) {
            ScalarKind.UByte ->
                body.addStatement(
                    "val %L = stream.peekByte(baseOffset + %L).toUByte()",
                    targetVar,
                    offset,
                )
            ScalarKind.Byte ->
                body.addStatement(
                    "val %L = stream.peekByte(baseOffset + %L)",
                    targetVar,
                    offset,
                )
            ScalarKind.Boolean ->
                body.addStatement(
                    "val %L = stream.peekByte(baseOffset + %L) != 0.toByte()",
                    targetVar,
                    offset,
                )
            ScalarKind.UShort, ScalarKind.UInt, ScalarKind.ULong,
            ScalarKind.Short, ScalarKind.Int, ScalarKind.Long,
            ->
                error(
                    "peek-side reconstruction for value-class inner kind $kind not implemented; " +
                        "analyzeConditionalField should have rejected this shape until the wider " +
                        "peek path lands.",
                )
        }
    }

    private data class PeekSibling(
        val field: FieldSpec.FixedSize,
        val offset: Int,
    )

    private enum class PeekSiblingKind { Scalar, ValueClassScalar }

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
        val lengthVar = "${field.name}Length"
        body.addStatement("val %L = %L.toInt()", lengthVar, prefixVar)
        body.addStatement(
            "val %L = buffer.readString(%L, %T.UTF8)",
            field.name,
            lengthVar,
            CHARSET_CN,
        )
    }

    private fun appendEncodeLengthPrefixedString(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthPrefixedString,
    ) {
        // BackPatch pattern (Locked Decision row 15): reserve prefix slot, write
        // the body via the runtime's UTF-8 path, measure byte count from the
        // position delta, patch the prefix in place, restore position past the
        // body. The runtime's `writeString(text, Charset.UTF8)` is zero-`ByteArray`
        // on JVM / Apple / JS; the WASM and nonJvm `writeString` paths still
        // allocate one ByteArray per call (Locked Decision row 16, deferred to a
        // separate runtime task).
        val sizePosVar = "${field.name}SizePosition"
        val bodyStartVar = "${field.name}BodyStart"
        val endPosVar = "${field.name}EndPosition"
        val byteCountVar = "${field.name}ByteCount"
        body.addStatement("val %L = buffer.position()", sizePosVar)
        body.addStatement("buffer.position(%L + %L)", sizePosVar, field.prefixWidth)
        body.addStatement("val %L = buffer.position()", bodyStartVar)
        body.addStatement(
            "buffer.writeString(value.%L, %T.UTF8)",
            field.name,
            CHARSET_CN,
        )
        body.addStatement("val %L = buffer.position()", endPosVar)
        body.addStatement("val %L = %L - %L", byteCountVar, endPosVar, bodyStartVar)
        // Runtime overflow guard. For 4-byte prefixes the max (UInt.MAX_VALUE =
        // 2^32-1) exceeds Int.MAX_VALUE, so a position-delta byte count can never
        // overflow it — the check would be dead code.
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
                "UTF-8 byte length \${$byteCountVar} exceeds @LengthPrefixed(LengthPrefix.$widthName) max $maxValue",
            )
            body.endControlFlow()
        }
        body.addStatement("buffer.position(%L)", sizePosVar)
        val prefixVar = "${field.name}Prefix"
        body.addStatement("val %L = %L.toUInt()", prefixVar, byteCountVar)
        appendBufferPrefixEncode(body, prefixVar, field.prefixWidth, field.prefixWireOrder)
        body.addStatement("buffer.position(%L)", endPosVar)
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

    private fun appendPeekPrefixAssembly(
        body: CodeBlock.Builder,
        fieldName: String,
        width: Int,
        wireOrder: Endianness,
        prefixOffset: Int,
    ) {
        val prefixVar = "${fieldName}Prefix"
        if (width == 1) {
            body.addStatement(
                "val %L = (stream.peekByte(baseOffset + %L).toInt() and 0xFF).toUInt()",
                prefixVar,
                prefixOffset,
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
            body.addStatement(
                "val %L = stream.peekByte(baseOffset + %L).toInt() and 0xFF",
                "${prefixVar}B$i",
                prefixOffset + i,
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

    private fun classifyVariantWireSize(shape: CodecShape): VariantWireSize {
        // Locked Decision row 19: any `@WhenTrue` field collapses wireSize to
        // BackPatch — including inside a sealed variant.
        if (shape.fields.any { it is FieldSpec.Conditional }) return VariantWireSize.BackPatch
        return when (shape.fields.lastOrNull()) {
            is FieldSpec.LengthPrefixedString -> VariantWireSize.BackPatch
            is FieldSpec.LengthPrefixedMessage -> VariantWireSize.RuntimeExact
            is FieldSpec.Scalar, is FieldSpec.ValueClassScalar, null ->
                VariantWireSize.LiteralExact(shape.fields.sumOfFixedWireBytes())
            is FieldSpec.Conditional -> VariantWireSize.BackPatch
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

    private data class DispatcherShape(
        val packageName: String,
        val parentClassName: ClassName,
        val parentSimpleName: String,
        val codecSimpleName: String,
        val variants: List<VariantSpec>,
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
         * Stage E slice 3 — a `@JvmInline value class` field whose primary
         * constructor takes a single supported scalar. Wire form is the
         * inner scalar at its natural width and `Endianness.Default`;
         * `@WireBytes` / `@WireOrder` on the outer parameter are out of
         * scope for slice 3 and silently rejected (caught by the
         * non-conditional analyzeField path). Decode reads the inner
         * scalar and constructs the value class; encode writes
         * `value.<outer>.<innerProperty>`. Top-level value classes
         * already had a path via `analyze`; slice 3 lifts the same
         * shape to a first-class field type so the dotted-form
         * `@WhenTrue` can resolve `sibling.property` against an
         * in-scope value-class local. Stage F's `@DispatchOn` value-
         * class discriminator will reuse this entry too.
         */
        data class ValueClassScalar(
            override val name: String,
            val ownerSimpleName: String,
            val valueClassType: ClassName,
            val innerKind: ScalarKind,
            val innerPropertyName: String,
            override val wireBytes: Int,
        ) : FixedSize

        /**
         * Stage E — `@WhenTrue` conditional wrapper. Slice 2/3 support
         * `inner: FieldSpec.Scalar` at natural width (no `@WireBytes`,
         * no `@WireOrder`); slice 5 widens `inner` to `LengthPrefixedString`
         * for the MQTT v3 CONNECT optional-field shape.
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
            val inner: Scalar,
        ) : FieldSpec
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
        private val DECODE_CONTEXT_CN = ClassName("com.ditchoom.buffer.codec", "DecodeContext")
        private val ENCODE_CONTEXT_CN = ClassName("com.ditchoom.buffer.codec", "EncodeContext")
        private val WIRE_SIZE_CN = ClassName("com.ditchoom.buffer.codec", "WireSize")
        private val PEEK_RESULT_CN = ClassName("com.ditchoom.buffer.codec", "PeekResult")
        private val STREAM_PROCESSOR_CN = ClassName("com.ditchoom.buffer.stream", "StreamProcessor")
        private val DECODE_EXCEPTION_CN = ClassName("com.ditchoom.buffer.codec", "DecodeException")
        private val ENCODE_EXCEPTION_CN = ClassName("com.ditchoom.buffer.codec", "EncodeException")
        private val CHARSET_CN = ClassName("com.ditchoom.buffer", "Charset")
    }
}
