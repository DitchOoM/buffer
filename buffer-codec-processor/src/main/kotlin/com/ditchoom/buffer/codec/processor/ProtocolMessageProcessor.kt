package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate

/**
 * Stage 0 stub + compile-time validator.
 *
 * The KSP entry point is preserved so KSP wiring stays valid; every
 * code emitter has been removed. Capability returns one stage at a
 * time per the Stages A–H plan in.
 *
 * Until lands, this processor enforces the rules that are
 * load-bearing right now:
 *
 *   - **§8 raw-bytes ban.** `@ProtocolMessage` data classes cannot
 *     carry raw-bytes types in their fields (`ReadBuffer`,
 *     `WriteBuffer`, `PlatformBuffer`, `ByteArray`, `ByteBuffer`).
 *     The walk includes the field's declared type, the inner type of
 *     `@JvmInline value class` wrappers, and the type arguments of
 *     generic types. Walks short-circuit when a node is, or extends,
 *     `com.ditchoom.buffer.codec.Payload` — the Payload marker is the
 *     documented escape hatch and the consumer takes responsibility
 *     for the bytes it holds.
 *
 *   - **R1: adjacent-`@LengthFrom` rejection.** `@LengthFrom("X")` on
 *     field F where X is the field immediately preceding F in the
 *     same `@ProtocolMessage` data class is a compile error **when F
 *     has a viable `@LengthPrefixed` migration target**. Bound fields
 *     whose type extends the [com.ditchoom.buffer.codec.Payload]
 *     marker interface are skipped — `@LengthPrefixed` does not yet
 * widen to cover Payload slots ( deferral); R1 expands to
 *     cover them once that widening lands. `@LengthFrom` is otherwise
 *     reserved for genuine remote-prefix uses (length carried in a
 *     non-adjacent field, parsed elsewhere, or parent-passed via
 *     `@DispatchOn`).
 *
 * ** dispatcher rules.** A `@ProtocolMessage sealed
 *     interface` without `@DispatchOn` is a simple-dispatch parent.
 *     Every direct sealed subclass must carry `@PacketType(value =
 *     N)` with `N in 0..255`, and `value` must be unique within a
 *     parent. Missing/out-of-range/duplicate values are compile
 *     errors. Sealed parents carrying `@DispatchOn` are skipped
 * ( surface).
 *
 * ** `@DispatchOn` value-class discriminator.**
 *     A `@ProtocolMessage sealed interface` parent carrying
 *     `@DispatchOn(<DiscriminatorType>::class)` is a bit-packed
 *     dispatch parent. The discriminator must be a
 *     `@JvmInline value class` with a single non-nullable numeric-
 *     scalar inner and exactly one `@DispatchValue`-annotated `val`
 *     property returning non-nullable `Int`. Each variant must be
 *     a `data class` with `@PacketType(value = N)` (`N in 0..255`,
 *     unique within the parent), and must declare the
 *     DiscriminatorType as its first constructor parameter so the
 * variant codec reads/writes the byte naturally (
 *     narrow — `object` and discriminator-from-context shapes are
 *     deferred).
 *
 * ** `@LengthFrom` shape (, slice
 * 4 + ).** For `@LengthFrom("siblingField") val
 *     payload: T`: the bound field type must be either `String`
 *  or `List<E>` where `E` is a `@ProtocolMessage data
 * class`. The referenced parameter must exist as a
 *     sibling declared before the bound parameter, and must resolve
 *     to a numeric type (`Byte` / `Short` / `Int` / `Long` /
 *     `UByte` / `UShort` / `UInt` / `ULong`). Diagnostics list the
 *     numeric `val` siblings declared before the bound field. The
 *     adjacent-`@LengthFrom` migration suggestion (R1) is independent
 *     and continues to fire when the referenced sibling is the
 *     immediately-preceding parameter.
 *
 * ** `@When` shape (, slices 2–3).**
 *     The bound parameter type must be nullable. Two source-expression
 *     forms are supported:
 *       - Simple-name form `@When("siblingField")`: the referenced
 *         constructor parameter must exist, must be declared before the
 *         bound parameter, and must be a non-nullable `Boolean`.
 *       - Dotted form `@When("sibling.property")`: the sibling
 *         must be a constructor parameter declared before the bound
 *         parameter; its type must be a `value class`; the property
 *         must be a `val` declared on that value class with no extra
 *         value parameters and a non-nullable `Boolean` return type.
 *         Deeper-than-one-level paths are rejected. Diagnostics for the
 *         dotted form name the available `Boolean`-returning `val`
 *         properties on the resolved sibling type.
 *     No constraint on the constructor default expression — KSP cannot
 *     inspect default expression trees.
 */
class ProtocolMessageProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private val emitter = CodecEmitter(codeGenerator, logger)

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // Payload lives in buffer-codec, the same artifact that defines
        // @ProtocolMessage. If we have any @ProtocolMessage symbols to
        // process, buffer-codec is on the classpath and Payload resolves.
        // A null here means the processor's environment is broken (e.g.,
        // misconfigured classpath); fail loud rather than silently degrading
        // §8 / R1 behavior on a missing marker.
        val payloadType =
            resolver
                .getClassDeclarationByName(resolver.getKSNameFromString(PAYLOAD_QNAME))
                ?.asStarProjectedType()
                ?: run {
                    logger.error(
                        "Cannot resolve $PAYLOAD_QNAME — buffer-codec must be on the " +
                            "classpath when ProtocolMessageProcessor runs.",
                    )
                    return emptyList()
                }

        val deferred = mutableListOf<KSAnnotated>()
        val symbols = resolver.getSymbolsWithAnnotation(PROTOCOL_MESSAGE_QNAME)
        for (symbol in symbols) {
            if (symbol !is KSClassDeclaration) continue
            if (!symbol.validate()) {
                deferred += symbol
                continue
            }
            if (Modifier.SEALED in symbol.modifiers && symbol.classKind == ClassKind.INTERFACE) {
                validateSealedDispatcher(symbol)
                validateDispatchOnSealed(symbol)
                validateFramedBy(symbol)
                validateForwardCompatible(symbol)
                emitter.tryEmit(symbol)
                continue
            }
            // Issue #150 — `@ProtocolMessage data object` / `object`. No
            // primary constructor, so the field-level validators below
            // don't apply. `@DispatchOn` belongs on a sealed parent (it
            // selects between variants), so an object carrying it is a
            // shape error — emit a focused diagnostic that the codegen
            // test pins on (PR #153 contract).
            if (symbol.classKind == ClassKind.OBJECT) {
                val hasDispatchOn =
                    symbol.annotations.any { ann ->
                        ann.shortName.asString() == DISPATCH_ON_SHORT &&
                            ann.annotationType
                                .resolve()
                                .declaration.qualifiedName
                                ?.asString() == DISPATCH_ON_QNAME
                    }
                if (hasDispatchOn) {
                    val name = symbol.qualifiedName?.asString() ?: symbol.simpleName.asString()
                    logger.error(
                        "@DispatchOn is not valid on an object — it must annotate a sealed " +
                            "interface parent that selects between variants. $name carries " +
                            "@DispatchOn directly.",
                        symbol,
                    )
                    continue
                }
                validateFramedBy(symbol)
                emitter.tryEmit(symbol)
                continue
            }
            val ctor = symbol.primaryConstructor ?: continue
            for (param in ctor.parameters) {
                // `@RemainingBytes @UseCodec(C)` where `C : ViewCodec` is the
                // sanctioned zero-copy escape from the raw-bytes prohibition:
                // the codec's ViewCodec contract documents the borrowed-view
                // ownership the prohibition exists to demand. The field's
                // declared type (including `ReadBuffer`) is the codec's
                // business — skip the walker for this parameter.
                val isViewPayload =
                    param.annotations.any { it.shortName.asString() == "RemainingBytes" } &&
                        param.hasViewUseCodec()
                if (isViewPayload) continue
                walkType(
                    type = param.type.resolve(),
                    owner = symbol,
                    param = param,
                    payloadType = payloadType,
                    depth = 0,
                    visited = mutableSetOf(),
                )
            }
            validateAdjacentLengthFrom(symbol, ctor.parameters, payloadType)
            validateLengthFrom(symbol, ctor.parameters)
            validateWireBytes(symbol, ctor.parameters)
            validateWhen(symbol, ctor.parameters)
            validateUseCodec(symbol, ctor.parameters, payloadType)
            validateFramedBy(symbol)
            validatePayloadTypeParameter(symbol, ctor.parameters)
            validateRemainingBytesTrailers(symbol, ctor.parameters)
            validateRemainingBytesElementType(symbol, ctor.parameters)
            emitter.tryEmit(symbol)
        }
        return deferred
    }

    private fun validateSealedDispatcher(parent: KSClassDeclaration) {
        // @DispatchOn parents go through 's value-class discriminator
        // path; 's @PacketType-uniqueness rules don't model the bit
        // packed shape and would produce false positives.
        if (parent.annotations.any { ann ->
                ann.shortName.asString() == DISPATCH_ON_SHORT &&
                    ann.annotationType
                        .resolve()
                        .declaration.qualifiedName
                        ?.asString() == DISPATCH_ON_QNAME
            }
        ) {
            return
        }
        val parentName = parent.qualifiedName?.asString() ?: parent.simpleName.asString()
        if (validateGenericPayloadVariantShape(parent, parentName)) return
        val seen = mutableMapOf<Int, String>()
        for (sub in parent.getSealedSubclasses()) {
            val subName = sub.qualifiedName?.asString() ?: sub.simpleName.asString()
            val packetType =
                sub.annotations.firstOrNull { ann ->
                    ann.shortName.asString() == PACKET_TYPE_SHORT &&
                        ann.annotationType
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString() == PACKET_TYPE_QNAME
                }
            if (packetType == null) {
                logger.error(
                    "@ProtocolMessage sealed parent $parentName has subclass $subName " +
                        "without @PacketType. Every variant of a simple sealed dispatch " +
                        "parent must carry @PacketType(value = N) where N in 0..255.",
                    sub,
                )
                continue
            }
            val rawValue =
                packetType.arguments
                    .firstOrNull { it.name?.asString() == "value" }
                    ?.value as? Int
            if (rawValue == null) {
                logger.error(
                    "@PacketType on $subName is missing a `value` argument.",
                    sub,
                )
                continue
            }
            if (rawValue !in 0..255) {
                logger.error(
                    "@PacketType($rawValue) on $subName is out of range — the simple " +
                        "dispatch discriminator is one byte, so value must be in 0..255.",
                    sub,
                )
                continue
            }
            val prior = seen.put(rawValue, subName)
            if (prior != null) {
                logger.error(
                    "@PacketType($rawValue) on $subName duplicates the value already " +
                        "declared by $prior under the same sealed parent $parentName. " +
                        "Discriminator values must be unique within a parent.",
                    sub,
                )
            }
        }
    }

    /**
     * `@DispatchOn` value-class discriminator dispatcher.
     *
     * Validates the bit-packed dispatch shape:
     *   - Parent is a `@ProtocolMessage sealed interface` carrying
     *     `@DispatchOn(<DiscriminatorType>::class)`.
     *   - DiscriminatorType is a `@JvmInline value class` whose
     *     primary constructor takes a single non-nullable supported
     *     scalar parameter.
     *   - DiscriminatorType has exactly one declared `val` property
     *     annotated with `@DispatchValue`, returning a non-nullable
     *     `Int`, with no extension receiver.
     *   - Each variant is a `data class` carrying `@PacketType(value
     *     = N)` with `N in 0..255`. `value` uniqueness within the
     *     parent is enforced.
     *   - Each variant's first constructor parameter has the
     * `DiscriminatorType` ( narrow — variants without the
     *     header field would need the consume + forward-via-context
     *     model, deferred until a vector requires it).
     *
     * `@PacketType.wire` is permitted (the annotation declares it)
     * but unused at emit time: the variant's header field carries
     * the full byte on encode, so a separate `wire` is redundant.
     * Doesn't validate consistency between `value` and
     * `wire`; users who set a non-default `wire` should know the
     * variant's header default value matches.
     */
    private fun validateDispatchOnSealed(parent: KSClassDeclaration) {
        val dispatchOn =
            parent.annotations.firstOrNull { ann ->
                ann.shortName.asString() == DISPATCH_ON_SHORT &&
                    ann.annotationType
                        .resolve()
                        .declaration.qualifiedName
                        ?.asString() == DISPATCH_ON_QNAME
            } ?: return
        val parentName = parent.qualifiedName?.asString() ?: parent.simpleName.asString()

        val discriminatorType =
            dispatchOn.arguments
                .firstOrNull { it.name?.asString() == "type" }
                ?.value as? KSType
                ?: return
        val discriminatorDecl = discriminatorType.declaration as? KSClassDeclaration ?: return
        val discriminatorName = discriminatorDecl.qualifiedName?.asString() ?: discriminatorDecl.simpleName.asString()

        if (!discriminatorDecl.isValueClassDecl()) {
            logger.error(
                "@DispatchOn($discriminatorName::class) on $parentName references a type that is " +
                    "not a `value class`. The discriminator must be a `@JvmInline value class` " +
                    "whose primary constructor takes a single supported scalar " +
                    "(UByte/Byte/UShort/UInt/etc.).",
                parent,
            )
            return
        }
        val discriminatorCtor = discriminatorDecl.primaryConstructor
        if (discriminatorCtor == null || discriminatorCtor.parameters.size != 1) {
            logger.error(
                "@DispatchOn($discriminatorName::class) on $parentName references a value class " +
                    "whose primary constructor does not take exactly one parameter.",
                parent,
            )
            return
        }
        val discriminatorInner = discriminatorCtor.parameters[0].type.resolve()
        if (discriminatorInner.isError || discriminatorInner.isMarkedNullable) {
            logger.error(
                "@DispatchOn($discriminatorName::class) on $parentName: discriminator's inner " +
                    "parameter must be a non-nullable supported scalar.",
                parent,
            )
            return
        }
        val innerQname = discriminatorInner.declaration.qualifiedName?.asString()
        if (innerQname !in NUMERIC_SCALAR_QNAMES) {
            val displayed = innerQname ?: "<unresolved>"
            logger.error(
                "@DispatchOn($discriminatorName::class) on $parentName: discriminator's inner " +
                    "parameter type is `$displayed`, but it must be a numeric scalar " +
                    "(Byte / Short / Int / Long / UByte / UShort / UInt / ULong).",
                parent,
            )
            return
        }

        val dispatchValueProperties =
            discriminatorDecl
                .getDeclaredProperties()
                .filter { prop ->
                    prop.annotations.any {
                        it.shortName.asString() == DISPATCH_VALUE_SHORT &&
                            it.annotationType
                                .resolve()
                                .declaration.qualifiedName
                                ?.asString() == DISPATCH_VALUE_QNAME
                    }
                }.toList()
        if (dispatchValueProperties.size != 1) {
            logger.error(
                "@DispatchOn($discriminatorName::class) on $parentName: $discriminatorName must " +
                    "declare exactly one property annotated with `@DispatchValue`. Found ${dispatchValueProperties.size}.",
                parent,
            )
            return
        }
        val dispatchProp = dispatchValueProperties[0]
        if (dispatchProp.isMutable || dispatchProp.extensionReceiver != null) {
            logger.error(
                "@DispatchValue property `${dispatchProp.simpleName.asString()}` on $discriminatorName " +
                    "must be a `val` declared on the value class itself (no extension receiver).",
                dispatchProp,
            )
            return
        }
        val dispatchReturn = dispatchProp.type.resolve()
        val dispatchReturnQname = dispatchReturn.declaration.qualifiedName?.asString()
        // Slice — widen accepted return types from
        // Int-only to {Boolean, Byte, UByte, Short, UShort, Int, UInt}.
        // Long/ULong are excluded — `@PacketType.value` is `Int` so
        // values beyond `Int.MAX_VALUE` can't be expressed in the
        // annotation. Each kind gets its own valid `@PacketType.value`
        // range (Boolean: 0..1, signed kinds: signed range, unsigned
        // kinds: unsigned range).
        if (dispatchReturn.isMarkedNullable || dispatchReturnQname !in DISPATCH_VALUE_RETURN_RANGES) {
            val displayed = dispatchReturnQname ?: "<unresolved>"
            val nullableSuffix = if (dispatchReturn.isMarkedNullable) "?" else ""
            logger.error(
                "@DispatchValue property `${dispatchProp.simpleName.asString()}` on $discriminatorName " +
                    "must return one of {Boolean, Byte, UByte, Short, UShort, Int, UInt} (non-nullable), " +
                    "but returns `$displayed$nullableSuffix`.",
                dispatchProp,
            )
            return
        }
        val dispatchValueRange = DISPATCH_VALUE_RETURN_RANGES.getValue(dispatchReturnQname!!)

        if (validateGenericPayloadVariantShape(parent, parentName)) return

        val seen = mutableMapOf<Int, String>()
        for (sub in parent.getSealedSubclasses()) {
            val subName = sub.qualifiedName?.asString() ?: sub.simpleName.asString()
            // The `@ForwardCompatible` unknown-variant sink is the `else`
            // arm of dispatch — it carries no `@PacketType` and its first
            // constructor parameter is `opcode: Int`, not the
            // discriminator. The dispatch-shape rules below don't apply
            // to it; `validateForwardCompatible` checks its shape instead.
            if (sub.hasAnnotation(UNKNOWN_VARIANT_SHORT, UNKNOWN_VARIANT_QNAME)) continue
            // Issue #150 — `data object` / `object` variants are valid
            // here (they emit empty-fields singleton codecs). Reject only
            // non-data, non-object subclasses.
            val isObjectVariant = sub.classKind == ClassKind.OBJECT
            if (!isObjectVariant && Modifier.DATA !in sub.modifiers) {
                logger.error(
                    "@DispatchOn variant $subName must be a `data class` or `data object` / " +
                        "`object`. Non-data class variants are not supported.",
                    sub,
                )
                continue
            }
            val packetType =
                sub.annotations.firstOrNull { ann ->
                    ann.shortName.asString() == PACKET_TYPE_SHORT &&
                        ann.annotationType
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString() == PACKET_TYPE_QNAME
                }
            if (packetType == null) {
                logger.error(
                    "@DispatchOn variant $subName is missing `@PacketType(value = N)`. Every " +
                        "variant of a bit-packed dispatch parent must carry an extracted dispatch " +
                        "value to match against the parent's `@DispatchValue` property.",
                    sub,
                )
                continue
            }
            val rawValue =
                packetType.arguments
                    .firstOrNull { it.name?.asString() == "value" }
                    ?.value as? Int
            if (rawValue == null) {
                logger.error(
                    "@PacketType on $subName is missing a `value` argument.",
                    sub,
                )
                continue
            }
            if (rawValue !in dispatchValueRange) {
                logger.error(
                    "@PacketType($rawValue) on $subName is out of range for the parent's " +
                        "`@DispatchValue` return type `$dispatchReturnQname` — `value` must be in " +
                        "${dispatchValueRange.first}..${dispatchValueRange.last}.",
                    sub,
                )
                continue
            }
            val prior = seen.put(rawValue, subName)
            if (prior != null) {
                logger.error(
                    "@PacketType($rawValue) on $subName duplicates the value already declared by " +
                        "$prior under the same sealed parent $parentName.",
                    sub,
                )
                continue
            }
            // Issue #150 — `data object` / `object` variants have no
            // primary constructor and cannot carry the discriminator
            // field. PR #153's DataObjectCodegenTest only asserts
            // compilation success on this shape; the dispatcher emit
            // delegates to the variant's empty-fields codec without
            // re-reading the discriminator.
            if (isObjectVariant) continue
            val variantCtor = sub.primaryConstructor
            val firstParam = variantCtor?.parameters?.firstOrNull()
            val firstParamType = firstParam?.type?.resolve()
            val firstParamQname = firstParamType?.declaration?.qualifiedName?.asString()
            if (firstParam == null || firstParamQname != discriminatorName) {
                val displayed = firstParamQname ?: "<missing>"
                logger.error(
                    "@DispatchOn variant $subName must declare its first constructor parameter " +
                        "as the discriminator type `$discriminatorName`, but it is `$displayed`. " +
                        "The variant must carry the discriminator field so its codec reads / " +
                        "writes the byte naturally.",
                    sub,
                )
            }
        }
    }

    /**
     * (issue #151 part 2) — non-terminal `@RemainingBytes` is
     * allowed iff every trailing field is fixed-size on the wire (per
     * the analyzer's `FieldSpec.FixedSize` predicate, today: plain
     * scalars and value-class scalars). Variable-size trailers
     * (`@LengthPrefixed`, `@LengthFrom`, another `@RemainingBytes`,
     * `@When`, `@UseCodec`) leave the body decode with no way to
     * compute its end without re-encoding, so the analyzer silently
     * drops the codec — this validator surfaces a focused diagnostic
     * naming both the body field and the offending trailer.
     */
    private fun validateRemainingBytesTrailers(
        owner: KSClassDeclaration,
        parameters: List<KSValueParameter>,
    ) {
        val ownerName = owner.qualifiedName?.asString() ?: owner.simpleName.asString()
        for ((index, param) in parameters.withIndex()) {
            val hasRemainingBytes =
                param.annotations.any { ann ->
                    ann.shortName.asString() == REMAINING_BYTES_SHORT &&
                        ann.annotationType
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString() == REMAINING_BYTES_QNAME
                }
            if (!hasRemainingBytes) continue
            if (index == parameters.lastIndex) continue
            val bodyName = param.name?.asString() ?: "<unknown>"
            for (trailingIdx in (index + 1) until parameters.size) {
                val trailing = parameters[trailingIdx]
                val trailingName = trailing.name?.asString() ?: "<unknown>"
                val variableAnn =
                    trailing.annotations.firstOrNull { ann ->
                        val short = ann.shortName.asString()
                        short == LENGTH_PREFIXED_SHORT ||
                            short == LENGTH_FROM_SHORT ||
                            short == REMAINING_BYTES_SHORT ||
                            short == WHEN_SHORT ||
                            short == USE_CODEC_SHORT
                    }
                if (variableAnn != null) {
                    val annShortName = variableAnn.shortName.asString()
                    logger.error(
                        "@RemainingBytes on $ownerName.$bodyName is non-terminal but is " +
                            "followed by $ownerName.$trailingName which carries " +
                            "@$annShortName — non-terminal @RemainingBytes requires every " +
                            "trailing field to be fixed-size on the wire (a plain scalar or a " +
                            "value-class scalar) so the body decode can subtract a known byte " +
                            "count from buffer.limit(). Move the @RemainingBytes field to the " +
                            "end of the constructor parameter list, or remove the " +
                            "@$annShortName trailer.",
                        trailing,
                    )
                }
            }
        }
    }

    /**
     * Reject `@RemainingBytes List<scalar>` and
     * `@RemainingBytes <primitive-array>` (`ByteArray`, `UByteArray`,
     * `ShortArray`, `UShortArray`, `IntArray`, `UIntArray`, `LongArray`,
     * `ULongArray`).
     *
     * These shapes promote a copy-by-default model: the framework
     * decode loop reads bytes one at a time and accumulates them into
     * the boxed list / typed array, hiding the copy from the user
     * codec author. On Kotlin/JS the per-element boxing dominates
     * decode time for any non-trivial list size; on JVM the bulk
     * allocation is cheaper but still a forced copy.
     *
     * The right shapes — both spec-faithful and explicit about memory
     * ownership — are:
     *
     *   - For value-spaces with discrete spec-defined values
     *     (e.g. MQTT v3 SUBACK return codes per §3.9.3):
     *     `@RemainingBytes val xs: List<SealedParent>` where
     *     `SealedParent` is a `@DispatchOn @ProtocolMessage`-annotated
     * sealed interface with one variant per legal byte.
     *     emits per-element dispatch through the sealed parent's
     *     generated codec; each list slot holds a singleton-equivalent
     *     reference rather than a boxed scalar.
     *
     *   - For genuinely opaque bulk bytes (e.g. PNG chunk data, TLS
     *     handshake tail, MQTT v5 binary properties):
     *     `@RemainingBytes @UseCodec(YourCodec::class) val: YourPayload`
     *     where `YourPayload` is a `@JvmInline value class` over
     *     `ByteArray` (and implements `Payload`) with a hand-written
     *     `Codec<YourPayload>` that owns the copy decision. The
     *     `BinaryData` / `BinaryDataCodec` fixture pair in
     *     `protocols.payload` is the canonical example.
     */
    private fun validateRemainingBytesElementType(
        owner: KSClassDeclaration,
        parameters: List<KSValueParameter>,
    ) {
        val ownerName = owner.qualifiedName?.asString() ?: owner.simpleName.asString()
        for (param in parameters) {
            val hasRemainingBytes =
                param.annotations.any { ann ->
                    ann.shortName.asString() == REMAINING_BYTES_SHORT &&
                        ann.annotationType
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString() == REMAINING_BYTES_QNAME
                }
            if (!hasRemainingBytes) continue
            val type = param.type.resolve()
            if (type.isError) continue
            val typeQname = type.declaration.qualifiedName?.asString() ?: continue
            val fieldName = param.name?.asString() ?: "<unknown>"

            // Primitive arrays — never supported, but a fresh user reaching
            // for `ByteArray` (etc.) deserves the spec-faithful pointer.
            if (typeQname in PRIMITIVE_ARRAY_QNAMES) {
                logger.error(
                    "@RemainingBytes on $ownerName.$fieldName has type `$typeQname`. Primitive " +
                        "array element types are intentionally not supported — they hide a forced " +
                        "buffer-to-array copy from the codec author and provide no spec-meaningful " +
                        "structure. Wrap the bytes in a `@JvmInline value class T(val bytes: " +
                        "ByteArray) : Payload` with a hand-written `Codec<T>` and use " +
                        "`@RemainingBytes @UseCodec(<YourCodec>::class) val: T`. The " +
                        "`BinaryData` / `BinaryDataCodec` fixture pair is the canonical example.",
                    param,
                )
                continue
            }

            // List<scalar> — retired in.
            if (typeQname == "kotlin.collections.List") {
                val elementType =
                    type.arguments
                        .firstOrNull()
                        ?.type
                        ?.resolve() ?: continue
                if (elementType.isError) continue
                val elementQname = elementType.declaration.qualifiedName?.asString() ?: continue
                if (elementQname in SCALAR_QNAMES) {
                    logger.error(
                        "@RemainingBytes on $ownerName.$fieldName has type `List<$elementQname>`. " +
                            "Scalar-element list shapes are retired — they decode by reading one " +
                            "scalar at a time and boxing each into a `List` slot, which is " +
                            "expensive on Kotlin/JS (every value-class scalar becomes a JS heap " +
                            "object) and forces a copy regardless of platform. Use one of the " +
                            "two explicit shapes instead:\n" +
                            "  (1) For value-spaces with discrete spec-defined bytes " +
                            "(e.g. MQTT SUBACK return codes per §3.9.3), define a " +
                            "`@DispatchOn @ProtocolMessage` sealed parent with one variant per " +
                            "legal byte and use `@RemainingBytes val: List<SealedParent>`. " +
                            "See `MqttV3SubAckReturnCode` for the template.\n" +
                            "  (2) For genuinely opaque bulk bytes (e.g. PNG chunk data, TLS " +
                            "handshake tail), wrap in a `@JvmInline value class T(val bytes: " +
                            "ByteArray) : Payload` with a hand-written `Codec<T>` and use " +
                            "`@RemainingBytes @UseCodec(<YourCodec>::class) val: T`. " +
                            "See `BinaryData` / `BinaryDataCodec`.",
                        param,
                    )
                }
            }
        }
    }

    private fun validateWireBytes(
        owner: KSClassDeclaration,
        parameters: List<KSValueParameter>,
    ) {
        for (param in parameters) {
            val ann =
                param.annotations.firstOrNull { a ->
                    a.shortName.asString() == WIRE_BYTES_SHORT &&
                        a.annotationType
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString() == WIRE_BYTES_QNAME
                } ?: continue
            val n =
                ann.arguments
                    .firstOrNull { it.name?.asString() == "value" }
                    ?.value as? Int ?: continue
            val ownerName = owner.qualifiedName?.asString() ?: owner.simpleName.asString()
            val fieldName = param.name?.asString() ?: "<unknown>"
            if (n < 1 || n > 8) {
                logger.error(
                    "@WireBytes($n) on $ownerName.$fieldName is out of range — width must be 1..8 bytes.",
                    param,
                )
                continue
            }
            val typeQname =
                param.type
                    .resolve()
                    .declaration.qualifiedName
                    ?.asString()
            val natural = NATURAL_WIDTHS[typeQname]
            if (natural != null && n > natural) {
                logger.error(
                    "@WireBytes($n) on $ownerName.$fieldName exceeds the natural width of " +
                        "$typeQname ($natural bytes). @WireBytes narrows the wire encoding; " +
                        "it cannot widen past the Kotlin type's range.",
                    param,
                )
            }
        }
    }

    /**
     * `@When` shape validation.
     *
     * The bound parameter type must always be nullable (`T?`); when the
     * predicate is false the decoder writes `null`, so absence has to be
     * representable for every supported inner type.
     *
     * Two source-expression forms:
     *   - Simple-name `"siblingField"`: source must exist as a sibling
     *     declared before the bound parameter and resolve to a non-
     *     nullable `Boolean`.
     *   - Dotted `"sibling.property"`: sibling must exist as a sibling
     *     declared before the bound parameter and resolve to a `value
     *     class`; the property must be a `val` declared on that value
     *     class with no additional value parameters and a non-nullable
     *     `Boolean` return type. Deeper-than-one-level paths are
     *     rejected. Diagnostics list the value-class properties that
     *     would have satisfied the contract.
     */
    private fun validateWhen(
        owner: KSClassDeclaration,
        parameters: List<KSValueParameter>,
    ) {
        val ownerName = owner.qualifiedName?.asString() ?: owner.simpleName.asString()
        for ((index, param) in parameters.withIndex()) {
            val ann =
                param.annotations.firstOrNull { a ->
                    a.shortName.asString() == WHEN_SHORT &&
                        a.annotationType
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString() == WHEN_QNAME
                } ?: continue
            val expression =
                ann.arguments
                    .firstOrNull { it.name?.asString() == "predicate" }
                    ?.value as? String ?: continue

            val fieldName = param.name?.asString() ?: "<unknown>"
            val type = param.type.resolve()
            if (!type.isMarkedNullable) {
                logger.error(
                    "@When(\"$expression\") on $ownerName.$fieldName requires the field type " +
                        "to be nullable (e.g., `Int?` not `Int`). When the predicate is false, the " +
                        "decoder needs to assign `null` to the slot — that is the only way to " +
                        "represent absence uniformly across types.",
                    param,
                )
                continue
            }

            // Grammar 2 — `remaining <op> <int>`. Magic
            // identifier `remaining` is reserved by the grammar; reject
            // any malformed shape here so the analyzer never sees an
            // ill-formed predicate. Caller-side error messages are
            // focused on the grammar 2 shape.
            val trimmed = expression.trim()
            if (
                trimmed == "remaining" ||
                trimmed.startsWith("remaining ") ||
                trimmed.startsWith("remaining\t")
            ) {
                val tokens = trimmed.split(Regex("\\s+"))
                val op = tokens.getOrNull(1)
                val threshold = tokens.getOrNull(2)?.toIntOrNull()
                val opOk = op == ">=" || op == ">" || op == "=="
                val tokensOk = tokens.size == 3 && opOk && threshold != null && threshold >= 0
                if (!tokensOk) {
                    logger.error(
                        "@When(\"$expression\") on $ownerName.$fieldName uses the reserved " +
                            "identifier `remaining` but is not a valid grammar-2 predicate. " +
                            "Expected `\"remaining <op> <int>\"` where <op> ∈ {>=, >, ==} and " +
                            "<int> is a non-negative integer literal (e.g., \"remaining >= 1\"). " +
                            "Used for cascading optional trailing fields gated on the bounded " +
                            "decode buffer's `remaining()`.",
                        param,
                    )
                }
                continue
            }

            val parts = expression.split('.')
            if (parts.size > 2) {
                logger.error(
                    "@When(\"$expression\") on $ownerName.$fieldName uses a deeper-than-one-level " +
                        "path. The dotted form is limited to `<sibling>.<property>` " +
                        "where `<sibling>` is a sibling constructor parameter and `<property>` is a " +
                        "`Boolean`-returning `val` on the sibling's `value class` type.",
                    param,
                )
                continue
            }
            val siblingName = parts[0]
            val propertyName = parts.getOrNull(1)

            val sourceIndex = parameters.indexOfFirst { it.name?.asString() == siblingName }
            if (sourceIndex < 0) {
                val available =
                    parameters
                        .take(index)
                        .mapNotNull { p ->
                            val n = p.name?.asString() ?: return@mapNotNull null
                            val resolved = p.type.resolve()
                            val q =
                                resolved
                                    .declaration
                                    .qualifiedName
                                    ?.asString()
                            if (q == BOOLEAN_QNAME && !resolved.isMarkedNullable) n else null
                        }
                logger.error(
                    "@When(\"$expression\") on $ownerName.$fieldName references " +
                        "`$siblingName`, which is not a constructor parameter of $ownerName. " +
                        if (available.isEmpty()) {
                            "$ownerName has no `Boolean` siblings declared before $fieldName."
                        } else {
                            "Available `Boolean` siblings declared before $fieldName: ${available.joinToString()}."
                        },
                    param,
                )
                continue
            }
            if (sourceIndex >= index) {
                logger.error(
                    "@When(\"$expression\") on $ownerName.$fieldName references " +
                        "$ownerName.$siblingName, which is declared at-or-after $fieldName in the " +
                        "constructor parameter list. The source field must be declared before " +
                        "the conditional field so its value is available at decode time.",
                    param,
                )
                continue
            }

            val sourceParam = parameters[sourceIndex]
            val sourceType = sourceParam.type.resolve()
            if (propertyName == null) {
                val sourceQname = sourceType.declaration.qualifiedName?.asString()
                if (sourceQname != BOOLEAN_QNAME || sourceType.isMarkedNullable) {
                    val displayed = sourceQname ?: "<unresolved>"
                    val nullableSuffix = if (sourceType.isMarkedNullable) "?" else ""
                    logger.error(
                        "@When(\"$expression\") on $ownerName.$fieldName requires source " +
                            "`$ownerName.$siblingName` to be a non-nullable `Boolean`, but it is " +
                            "`$displayed$nullableSuffix`. The simple expression form requires " +
                            "a sibling `Boolean` field.",
                        param,
                    )
                    continue
                }
            } else {
                val siblingDecl = sourceType.declaration as? KSClassDeclaration
                if (siblingDecl == null || !siblingDecl.isValueClassDecl()) {
                    val displayed =
                        sourceType.declaration.qualifiedName?.asString() ?: "<unresolved>"
                    val nullableSuffix = if (sourceType.isMarkedNullable) "?" else ""
                    logger.error(
                        "@When(\"$expression\") on $ownerName.$fieldName uses a dotted source " +
                            "but `$ownerName.$siblingName` resolves to `$displayed$nullableSuffix`, which " +
                            "is not a `value class`. The dotted form requires " +
                            "siblings whose type is a `@JvmInline value class` exposing a " +
                            "`Boolean`-returning `val` property.",
                        param,
                    )
                    continue
                }
                val booleanProperties = booleanReturningValProperties(siblingDecl)
                val candidate =
                    siblingDecl
                        .getDeclaredProperties()
                        .firstOrNull { it.simpleName.asString() == propertyName }
                if (candidate == null || !isBooleanReturningValProperty(candidate)) {
                    val available =
                        if (booleanProperties.isEmpty()) {
                            "${siblingDecl.qualifiedName?.asString() ?: siblingDecl.simpleName.asString()} " +
                                "has no `Boolean`-returning `val` properties."
                        } else {
                            "Available `Boolean`-returning `val` properties on " +
                                "${siblingDecl.qualifiedName?.asString() ?: siblingDecl.simpleName.asString()}: " +
                                "${booleanProperties.joinToString()}."
                        }
                    logger.error(
                        "@When(\"$expression\") on $ownerName.$fieldName references property " +
                            "`$propertyName`, which is not a `Boolean`-returning `val` declared on " +
                            "${siblingDecl.qualifiedName?.asString() ?: siblingDecl.simpleName.asString()}. " +
                            available,
                        param,
                    )
                    continue
                }
            }
        }
    }

    private fun isBooleanReturningValProperty(prop: KSPropertyDeclaration): Boolean {
        if (prop.isMutable) return false
        if (prop.extensionReceiver != null) return false
        val returnType = prop.type.resolve()
        if (returnType.isMarkedNullable) return false
        return returnType.declaration.qualifiedName?.asString() == BOOLEAN_QNAME
    }

    /**
     * `@LengthFrom` on `List<T>` requires `T` to be a
     * `@ProtocolMessage data class`. Returns true when `listType`'s
     * single type argument resolves to such a declaration. Other
     * element shapes (scalar, value class, non-data class) are
     * deferred — 's `@RemainingBytes` is the path for
     * scalar element lists.
     */
    private fun isListOfProtocolMessageDataClass(listType: KSType): Boolean {
        val typeArgs = listType.arguments
        if (typeArgs.size != 1) return false
        val elementType = typeArgs[0].type?.resolve() ?: return false
        if (elementType.isError || elementType.isMarkedNullable) return false
        val elementDecl = elementType.declaration as? KSClassDeclaration ?: return false
        if (Modifier.DATA !in elementDecl.modifiers) return false
        return elementDecl.annotations.any { ann ->
            ann.shortName.asString() == "ProtocolMessage" &&
                ann.annotationType
                    .resolve()
                    .declaration.qualifiedName
                    ?.asString() == PROTOCOL_MESSAGE_QNAME
        }
    }

    /**
     * `@LengthFrom val: T` requires `T` to be a `@ProtocolMessage`
     * data class or sealed parent (no type parameters — payload-generic
     * shapes have a constructor-injected codec, not a singleton object,
     * and the by-name `<T>Codec.decode(...)` form fails to resolve).
     */
    private fun isNestedProtocolMessageType(decl: KSClassDeclaration): Boolean {
        val isProtocolMessage =
            decl.annotations.any { ann ->
                ann.shortName.asString() == "ProtocolMessage" &&
                    ann.annotationType
                        .resolve()
                        .declaration.qualifiedName
                        ?.asString() == PROTOCOL_MESSAGE_QNAME
            }
        if (!isProtocolMessage) return false
        if (decl.typeParameters.isNotEmpty()) return false
        val isDataClass = Modifier.DATA in decl.modifiers
        val isSealed = Modifier.SEALED in decl.modifiers
        return isDataClass || isSealed
    }

    private fun booleanReturningValProperties(decl: KSClassDeclaration): List<String> =
        decl
            .getDeclaredProperties()
            .filter { isBooleanReturningValProperty(it) }
            .mapNotNull { it.simpleName.asString() }
            .toList()

    private fun validateAdjacentLengthFrom(
        owner: KSClassDeclaration,
        parameters: List<KSValueParameter>,
        payloadType: KSType,
    ) {
        for ((index, param) in parameters.withIndex()) {
            val lengthFrom =
                param.annotations.firstOrNull { ann ->
                    ann.shortName.asString() == LENGTH_FROM_SHORT &&
                        ann.annotationType
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString() == LENGTH_FROM_QNAME
                } ?: continue
            val referencedField =
                lengthFrom.arguments
                    .firstOrNull { it.name?.asString() == "field" }
                    ?.value as? String ?: continue
            if (index == 0) continue
            val previous = parameters[index - 1]
            if (previous.name?.asString() != referencedField) continue

            // Path B carve-out: skip when the bound field's type extends the
            // Payload marker interface. @LengthPrefixed does not yet widen to
            // cover Payload slots, so forbidding the adjacent shape
            // today would leave those fields with no migration target. R1
            // expands to cover this case once @LengthPrefixed widens.
            val boundFieldType = param.type.resolve()
            if (payloadType.isAssignableFrom(boundFieldType)) continue

            // Carve-out: skip when the bound field's type is a nested
            // `@ProtocolMessage` data class or sealed parent. The
            // `@LengthPrefixed` migration target (LengthPrefixedMessage) only
            // supports 1 / 2 / 4-byte prefixes (LengthPrefix.Byte / Short /
            // Int) — protocols with non-standard prefix widths (e.g. TLS
            // uint24) cannot express their wire shape via @LengthPrefixed and
            // genuinely need @LengthFrom even when the length sibling is
            // adjacent. The user signals intent by carrying the length as a
            // typed sibling (often with @WireBytes(N)) and pairing
            // @LengthFrom on the body field.
            val boundDecl = boundFieldType.declaration as? KSClassDeclaration
            if (boundDecl != null && isNestedProtocolMessageType(boundDecl)) continue

            val ownerName = owner.qualifiedName?.asString() ?: owner.simpleName.asString()
            val boundFieldName = param.name?.asString() ?: "<unknown>"
            logger.error(
                "@LengthFrom(\"$referencedField\") on $ownerName.$boundFieldName references " +
                    "$ownerName.$referencedField, the immediately preceding constructor " +
                    "parameter. Adjacent length carriers must use @LengthPrefixed on the " +
                    "bounded field instead — replace with `@LengthPrefixed val " +
                    "$boundFieldName: ...` and remove $referencedField from the constructor. " +
                    "@LengthFrom is reserved for remote-prefix uses (length carried in a " +
                    "non-adjacent field).",
                param,
            )
        }
    }

    /**
     * `@LengthFrom` shape validation.
     *
     * Independent from R1 (adjacent-`@LengthFrom` migration suggestion):
     * Bound field type must be `String` — 's field-type
     *     universe (row 18). `ByteArray`, nested `@ProtocolMessage`,
     *     and `@Payload` slots widen this in later stages.
     *   - Referenced sibling must exist as a sibling of the bound
     *     parameter; diagnostics list available numeric siblings
     *     declared before the bound field.
     *   - Referenced sibling must be declared *before* the bound
     *     parameter so the length is in scope at decode time.
     *   - Referenced sibling type must be a non-nullable numeric
     *     scalar (`Byte` / `Short` / `Int` / `Long` / `UByte` /
     *     `UShort` / `UInt` / `ULong`). Numeric value classes are
     *     deferred until a vector requires them.
     */
    private fun validateLengthFrom(
        owner: KSClassDeclaration,
        parameters: List<KSValueParameter>,
    ) {
        val ownerName = owner.qualifiedName?.asString() ?: owner.simpleName.asString()
        for ((index, param) in parameters.withIndex()) {
            val ann =
                param.annotations.firstOrNull { a ->
                    a.shortName.asString() == LENGTH_FROM_SHORT &&
                        a.annotationType
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString() == LENGTH_FROM_QNAME
                } ?: continue
            val referenced =
                ann.arguments
                    .firstOrNull { it.name?.asString() == "field" }
                    ?.value as? String ?: continue
            val fieldName = param.name?.asString() ?: "<unknown>"

            val type = param.type.resolve()
            val typeQname = type.declaration.qualifiedName?.asString()
            val isStringType = typeQname == STRING_QNAME && !type.isMarkedNullable
            val isListOfProtocolMessage =
                !type.isMarkedNullable &&
                    typeQname == LIST_QNAME &&
                    isListOfProtocolMessageDataClass(type)
            // Accept nested `@ProtocolMessage` data class or
            // sealed parent (issue #151 part 1). The body's bytes are
            // bounded by the sibling-derived length; decode delegates to
            // `<TCodec>.decode` against the narrowed buffer.
            val nestedProtocolMessageDecl =
                if (!type.isMarkedNullable) type.declaration as? KSClassDeclaration else null
            val isNestedProtocolMessage =
                nestedProtocolMessageDecl != null &&
                    isNestedProtocolMessageType(nestedProtocolMessageDecl)
            if (!isStringType && !isListOfProtocolMessage && !isNestedProtocolMessage) {
                val displayed = typeQname ?: "<unresolved>"
                val nullableSuffix = if (type.isMarkedNullable) "?" else ""
                logger.error(
                    "@LengthFrom(\"$referenced\") on $ownerName.$fieldName requires the bound " +
                        "field to be a non-nullable `String`, a non-nullable `List<T>` where " +
                        "`T` is a `@ProtocolMessage data class`, or a non-nullable nested " +
                        "`@ProtocolMessage` data class / sealed parent, but it is " +
                        "`$displayed$nullableSuffix`. `ByteArray` and `@Payload` slots are " +
                        "deferred to later stages.",
                    param,
                )
                continue
            }

            val parts = referenced.split('.')
            if (parts.size > 2) {
                logger.error(
                    "@LengthFrom(\"$referenced\") on $ownerName.$fieldName uses a deeper-than-one-level " +
                        "path. Only `<sibling>` and `<sibling>.<property>` forms are accepted.",
                    param,
                )
                continue
            }
            val siblingName = parts[0]
            val propertyName = parts.getOrNull(1)

            val sourceIndex = parameters.indexOfFirst { it.name?.asString() == siblingName }
            if (sourceIndex < 0) {
                val available =
                    parameters
                        .take(index)
                        .mapNotNull { p ->
                            val n = p.name?.asString() ?: return@mapNotNull null
                            val resolved = p.type.resolve()
                            val q =
                                resolved
                                    .declaration
                                    .qualifiedName
                                    ?.asString()
                            if (q in NUMERIC_SCALAR_QNAMES && !resolved.isMarkedNullable) n else null
                        }
                logger.error(
                    "@LengthFrom(\"$referenced\") on $ownerName.$fieldName references " +
                        "`$siblingName`, which is not a constructor parameter of $ownerName. " +
                        if (available.isEmpty()) {
                            "$ownerName has no numeric siblings declared before $fieldName."
                        } else {
                            "Available numeric siblings declared before $fieldName: ${available.joinToString()}."
                        },
                    param,
                )
                continue
            }
            if (sourceIndex >= index) {
                logger.error(
                    "@LengthFrom(\"$referenced\") on $ownerName.$fieldName references " +
                        "$ownerName.$siblingName, which is declared at-or-after $fieldName in the " +
                        "constructor parameter list. The length-carrier sibling must be declared " +
                        "before the bound field so its value is available at decode time.",
                    param,
                )
                continue
            }

            val sourceParam = parameters[sourceIndex]
            val sourceType = sourceParam.type.resolve()
            if (propertyName == null) {
                // Simple form: sibling must be a numeric scalar.
                val sourceQname = sourceType.declaration.qualifiedName?.asString()
                if (sourceQname !in NUMERIC_SCALAR_QNAMES || sourceType.isMarkedNullable) {
                    val displayed = sourceQname ?: "<unresolved>"
                    val nullableSuffix = if (sourceType.isMarkedNullable) "?" else ""
                    logger.error(
                        "@LengthFrom(\"$referenced\") on $ownerName.$fieldName requires source " +
                            "`$ownerName.$siblingName` to be a non-nullable numeric scalar " +
                            "(Byte / Short / Int / Long / UByte / UShort / UInt / ULong), but it " +
                            "is `$displayed$nullableSuffix`. The simple expression form requires " +
                            "a numeric scalar sibling.",
                        param,
                    )
                    continue
                }
            } else {
                // Dotted form: sibling must be a value class with a
                // single supported-scalar inner; property must be a non-extension
                // `val` returning non-nullable `Int`.
                val siblingDecl = sourceType.declaration as? KSClassDeclaration
                if (siblingDecl == null || !siblingDecl.isValueClassDecl()) {
                    val displayed = sourceType.declaration.qualifiedName?.asString() ?: "<unresolved>"
                    logger.error(
                        "@LengthFrom(\"$referenced\") on $ownerName.$fieldName uses a dotted source " +
                            "but `$ownerName.$siblingName` resolves to `$displayed`, which is not a " +
                            "`value class`. The dotted form requires the sibling to be a `@JvmInline " +
                            "value class` exposing an `Int`-returning `val` property.",
                        param,
                    )
                    continue
                }
                val candidate =
                    siblingDecl
                        .getDeclaredProperties()
                        .firstOrNull { it.simpleName.asString() == propertyName }
                if (candidate == null || !isIntReturningValProperty(candidate)) {
                    val available =
                        siblingDecl
                            .getDeclaredProperties()
                            .filter { isIntReturningValProperty(it) }
                            .mapNotNull { it.simpleName.asString() }
                            .toList()
                    val availableMsg =
                        if (available.isEmpty()) {
                            "${siblingDecl.qualifiedName?.asString() ?: siblingDecl.simpleName.asString()} " +
                                "has no `Int`-returning `val` properties."
                        } else {
                            "Available `Int`-returning `val` properties on " +
                                "${siblingDecl.qualifiedName?.asString() ?: siblingDecl.simpleName.asString()}: " +
                                "${available.joinToString()}."
                        }
                    logger.error(
                        "@LengthFrom(\"$referenced\") on $ownerName.$fieldName references property " +
                            "`$propertyName`, which is not an `Int`-returning `val` declared on " +
                            "${siblingDecl.qualifiedName?.asString() ?: siblingDecl.simpleName.asString()}. " +
                            availableMsg,
                        param,
                    )
                    continue
                }
            }
        }
    }

    private fun isIntReturningValProperty(prop: KSPropertyDeclaration): Boolean {
        if (prop.isMutable) return false
        if (prop.extensionReceiver != null) return false
        val returnType = prop.type.resolve()
        if (returnType.isMarkedNullable) return false
        return returnType.declaration.qualifiedName?.asString() == "kotlin.Int"
    }

    /**
     * `@UseCodec` shape validation.
     *
     * Currently supported compositions:
     * `@RemainingBytes @UseCodec(C::class) val: P`
     *     where `P` extends `com.ditchoom.buffer.codec.Payload` and `C` is
     *     a Kotlin `object` implementing `Codec<P>`.
     * bare `@UseCodec(C::class) val: <scalar>` (no framing
     *     annotation), where the field is a non-Payload, non-type-parameter
     *     scalar and `C` is a Kotlin `object` implementing `Codec<T>` for
     *     T matching the field type. Drives pluggable length-encoding via
     *     user-supplied codecs (e.g. `MqttRemainingLengthCodec`).
     *
     * `@LengthFrom @UseCodec` and `@LengthPrefixed @UseCodec` remain
     * deferred to a later slice and produce an explicit "not yet supported"
     * diagnostic so the user isn't left with silent emit failure.
     *
     * Diagnostics:
     *   - `@UseCodec` target is not a Kotlin `object` declaration.
     *   - `@UseCodec` target object does not implement `Codec<T>` where
     *     `T` matches the bound field's type.
     *   - Field type extends `Payload` but the parameter has no
     *     `@UseCodec` annotation (no codec can be emitted).
     *   - Payload-typed field carries `@UseCodec` without `@RemainingBytes`
     * ('s Payload path requires the pair).
     * `@RemainingBytes @UseCodec` on a non-Payload field (
     *     restricts the bounded shape to Payload).
     *   - `@UseCodec` paired with `@LengthFrom` or `@LengthPrefixed`
     *     (deferred to a later slice).
     */
    private fun validateUseCodec(
        owner: KSClassDeclaration,
        parameters: List<KSValueParameter>,
        payloadType: KSType,
    ) {
        val ownerName = owner.qualifiedName?.asString() ?: owner.simpleName.asString()
        // When the data class declares a
        // `<P : Payload>` type parameter, fields of type `P` are
        // resolved through the constructor-injected codec, not via
        // `@UseCodec`. Skip the "Payload field requires @UseCodec"
        // check for those fields. The set of names is captured up
        // front so the per-parameter loop can match without
        // re-resolving each iteration.
        val payloadTypeParameterNames =
            owner.typeParameters
                .filter { tp ->
                    val bounds = tp.bounds.toList()
                    bounds.size == 1 &&
                        bounds[0]
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString() == PAYLOAD_QNAME
                }.map { it.name.asString() }
                .toSet()
        for (param in parameters) {
            val fieldName = param.name?.asString() ?: "<unknown>"
            val fieldType = param.type.resolve()
            if (fieldType.isError) continue
            val useCodec =
                param.annotations.firstOrNull { ann ->
                    ann.shortName.asString() == USE_CODEC_SHORT &&
                        ann.annotationType
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString() == USE_CODEC_QNAME
                }
            val fieldDecl = fieldType.declaration
            val isTypeParameterReference =
                fieldDecl is com.google.devtools.ksp.symbol.KSTypeParameter &&
                    fieldDecl.name.asString() in payloadTypeParameterNames
            val isPayloadField = !fieldType.isMarkedNullable && payloadType.isAssignableFrom(fieldType)
            if (isTypeParameterReference) {
                // Constructor-injected codec resolves the
                // type-parameter-typed field. `@UseCodec` is mutually
                // exclusive with this resolution mechanism.
                if (useCodec != null) {
                    logger.error(
                        "@UseCodec on $ownerName.$fieldName is mutually exclusive with the " +
                            "generic `<P : Payload>` constructor-injected codec resolution. The " +
                            "field's type is the type parameter `${fieldDecl.name.asString()}`, " +
                            "which routes through the generated codec's constructor parameter " +
                            "(`payloadCodec: Codec<${fieldDecl.name.asString()}>`). Remove " +
                            "`@UseCodec` to use the generic path, or change the field's type to a " +
                            "concrete `Payload` subtype to use the `@UseCodec`-driven path.",
                        param,
                    )
                }
                continue
            }
            if (isPayloadField && useCodec == null) {
                logger.error(
                    "@ProtocolMessage field $ownerName.$fieldName has type extending " +
                        "`com.ditchoom.buffer.codec.Payload` but no `@UseCodec`. The codec " +
                        "emitter has no way to read or write a Payload-typed field without a " +
                        "user-supplied `Codec<T>` reference. Add `@UseCodec(SomeCodec::class)` " +
                        "naming a Kotlin `object` that implements `Codec<${fieldType.declaration.simpleName.asString()}>`.",
                    param,
                )
                continue
            }
            if (useCodec == null) continue

            val hasRemainingBytes =
                param.annotations.any { ann ->
                    ann.shortName.asString() == REMAINING_BYTES_SHORT &&
                        ann.annotationType
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString() == REMAINING_BYTES_QNAME
                }
            val hasLengthFrom =
                param.annotations.any { ann ->
                    val n = ann.shortName.asString()
                    val q =
                        ann.annotationType
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString()
                    n == LENGTH_FROM_SHORT && q == LENGTH_FROM_QNAME
                }
            val hasLengthPrefixed =
                param.annotations.any { ann -> ann.shortName.asString() == "LengthPrefixed" }
            if (hasLengthFrom) {
                logger.error(
                    "@UseCodec on $ownerName.$fieldName composed with `@LengthFrom` is not yet " +
                        "supported. Currently emitted compositions are `@RemainingBytes @UseCodec " +
                        "val: P` (P : Payload), bare `@UseCodec val: <scalar>`, and " +
                        "`@LengthPrefixed @UseCodec(BoundingLengthCodec) val: List<E>`. " +
                        "The `@LengthFrom @UseCodec` shape is deferred to a future release.",
                    param,
                )
                continue
            }
            if (hasLengthPrefixed) {
                // `@LengthPrefixed @UseCodec(C::class) val xs: List<E>`
                // where `C : BoundingLengthCodec<UInt>` and `E` is a `@ProtocolMessage data
                // class`. Validate the new shape inline; subsequent generic checks (which
                // expect `Codec<fieldType>`) don't apply because the codec's type arg is
                // `UInt`, not the `List<E>` field type.
                validateLengthPrefixedUseCodec(
                    ownerName = ownerName,
                    fieldName = fieldName,
                    fieldType = fieldType,
                    useCodecAnn = useCodec,
                    param = param,
                    payloadType = payloadType,
                )
                continue
            }
            if (!hasRemainingBytes && isPayloadField) {
                logger.error(
                    "@UseCodec on $ownerName.$fieldName must be paired with `@RemainingBytes` " +
                        "when the field's type extends `com.ditchoom.buffer.codec.Payload`. The " +
                        "current `@UseCodec` shapes are `@RemainingBytes @UseCodec val: P` " +
                        "(P : Payload) and bare `@UseCodec val: <scalar>`.",
                    param,
                )
                continue
            }
            if (hasRemainingBytes && !isPayloadField && !param.hasViewUseCodec()) {
                val displayed = fieldType.declaration.qualifiedName?.asString() ?: "<unresolved>"
                logger.error(
                    "@RemainingBytes @UseCodec on $ownerName.$fieldName requires the bound " +
                        "field's type to extend `com.ditchoom.buffer.codec.Payload`, but it " +
                        "is `$displayed`. Either wrap the value in a `Payload`-tagged type " +
                        "(consumer-owned copy), or — for a zero-copy borrowed view whose " +
                        "lifetime is tied to the source buffer — make the codec implement " +
                        "`com.ditchoom.buffer.codec.ViewCodec` (the explicit ownership " +
                        "opt-in for non-Payload types).",
                    param,
                )
                continue
            }

            val codecKsType =
                useCodec.arguments
                    .firstOrNull { it.name?.asString() == "codec" }
                    ?.value as? KSType
            if (codecKsType == null || codecKsType.isError) continue
            val codecDecl = codecKsType.declaration as? KSClassDeclaration ?: continue
            val codecName = codecDecl.qualifiedName?.asString() ?: codecDecl.simpleName.asString()
            if (codecDecl.classKind != ClassKind.OBJECT) {
                logger.error(
                    "@UseCodec($codecName::class) on $ownerName.$fieldName references " +
                        "`$codecName`, which is not a Kotlin `object` declaration. The codec " +
                        "emitter calls `$codecName.decode(...)` directly, which requires the " +
                        "target to be an object. Convert the declaration to `object $codecName " +
                        ": Codec<${fieldType.declaration.simpleName.asString()}>`.",
                    param,
                )
                continue
            }
            if (!implementsCodecOf(codecDecl, fieldType)) {
                val expectedSimpleName = fieldType.declaration.simpleName.asString()
                logger.error(
                    "@UseCodec($codecName::class) on $ownerName.$fieldName references object " +
                        "`$codecName`, which does not implement `com.ditchoom.buffer.codec.Codec<" +
                        "$expectedSimpleName>`. The emitter calls `$codecName.decode(...)` and " +
                        "`$codecName.encode(...)` against the bound field's declared type; the " +
                        "object must satisfy the `Codec<$expectedSimpleName>` contract.",
                    param,
                )
            }
        }
    }

    /**
     * `@LengthPrefixed @UseCodec(C::class) val xs:
     * List<E>` validation. The codec drives the wire-format prefix
     * (var-byte-int, sentinel-extended length, etc.) and bounds the
     * element-decode region; elements are read element-by-element via
     * `E`'s generated codec until the bounded region is exhausted.
     *
     * Slice scope:
     *   - `C` must be a Kotlin `object` declaration (the emitter calls
     *     `C.decode(...)` / `C.encode(...)` / `C.applyBound(...)`).
     *   - `C` must implement `BoundingLengthCodec<UInt>` — `applyBound`
     *     is what narrows `buffer.limit()` for the element loop, and
     *     `UInt` is the only length-value type the slice models today.
     *   - The field type must be `kotlin.collections.List<E>` where
     *     `E` is a `@ProtocolMessage data class` — the emitter calls
     *     `<E>Codec.decode(...)` / `<E>Codec.encode(...)` per element.
     *
     * Returns no value — diagnostics are emitted directly.
     */
    private fun validateLengthPrefixedUseCodec(
        ownerName: String,
        fieldName: String,
        fieldType: KSType,
        useCodecAnn: KSAnnotation,
        param: KSValueParameter,
        payloadType: KSType,
    ) {
        val codecKsType =
            useCodecAnn.arguments
                .firstOrNull { it.name?.asString() == "codec" }
                ?.value as? KSType
        if (codecKsType == null || codecKsType.isError) return
        val codecDecl = codecKsType.declaration as? KSClassDeclaration ?: return
        val codecName = codecDecl.qualifiedName?.asString() ?: codecDecl.simpleName.asString()
        if (codecDecl.classKind != ClassKind.OBJECT) {
            logger.error(
                "@LengthPrefixed @UseCodec($codecName::class) on $ownerName.$fieldName references " +
                    "`$codecName`, which is not a Kotlin `object` declaration. The emitter calls " +
                    "`$codecName.decode(...)` / `$codecName.encode(...)` / `$codecName.applyBound(" +
                    "...)` directly, which requires the target to be an object.",
                param,
            )
            return
        }
        val fieldTypeQname = fieldType.declaration.qualifiedName?.asString()
        // Scalar `T: Payload` shape. The codec is
        // `Codec<T>` and the framework owns the prefix; the codec is NOT
        // required to implement BoundingLengthCodec. Validates first
        // because a non-List Payload field must not fall through to the
        // list-shape diagnostics.
        //
        // Widening — `kotlin.String` is also accepted here so a
        // user-supplied `Codec<String>` (e.g. AsciiStringCodec, or a
        // consumer's Latin-1 / UTF-16 / Modified-UTF-8 codec) can plug in
        // via `@LengthPrefixed @UseCodec val: String` and override the
        // built-in UTF-8 reader. Same wire shape as the Payload variant
        // (length prefix + body bytes).
        if (fieldTypeQname != LIST_QNAME) {
            // Nullable types arrive here when the field carries `@When`
            // (which gates Connect.willPayload / password on connect-flag
            // bits). Strip the nullability for the Payload-assignability
            // check; the analyzer's conditional path
            // (`analyzeConditionalLengthPrefixedUseCodecPayloadInner`)
            // already runs against the non-null inner type.
            val nonNullableFieldType =
                if (fieldType.isMarkedNullable) fieldType.makeNotNullable() else fieldType
            val isStringField = fieldTypeQname == STRING_QNAME
            val isPayloadField = payloadType.isAssignableFrom(nonNullableFieldType)
            // `com.ditchoom.buffer.codec.OwnedBytesHandle` — the buffer-codec
            // canonical consumer-owned bytes carrier. Recognized alongside
            // Payload + String so `@LengthPrefixed @UseCodec(OwnedBytesHandleCodec)
            // val: OwnedBytesHandle?` works without forcing protocol authors to
            // wrap auth / opaque-bytes slots in a phantom Payload marker. The
            // codec is allocator-aware via `DecodeContext[BufferFactoryKey]`
            // and performs Pattern #2's safe single-copy at the wire boundary.
            val isOwnedBytesHandleField = fieldTypeQname == OWNED_BYTES_HANDLE_QNAME
            if (!isPayloadField && !isStringField && !isOwnedBytesHandleField) {
                logger.error(
                    "@LengthPrefixed @UseCodec($codecName::class) on $ownerName.$fieldName has " +
                        "field type `${fieldTypeQname ?: "<unresolved>"}`, which is none of: " +
                        "`kotlin.collections.List<E>` (list shape), a type implementing " +
                        "`com.ditchoom.buffer.codec.Payload` (scalar Payload shape), " +
                        "`kotlin.String` (user-charset shape), or " +
                        "`com.ditchoom.buffer.codec.OwnedBytesHandle` (canonical owned-bytes " +
                        "shape). Wrap binary data in a `Payload`-marked value class and " +
                        "reference its `Codec<T>` via `@UseCodec`, use the list shape with a " +
                        "`@ProtocolMessage` element type, supply a `Codec<String>` for a " +
                        "String-typed field, or use `OwnedBytesHandle` for opaque consumer-" +
                        "owned bytes.",
                    param,
                )
                return
            }
            if (!implementsCodecOf(codecDecl, fieldType)) {
                val expectedSimpleName = fieldType.declaration.simpleName.asString()
                logger.error(
                    "@LengthPrefixed @UseCodec($codecName::class) on $ownerName.$fieldName " +
                        "references object `$codecName`, which does not implement " +
                        "`com.ditchoom.buffer.codec.Codec<$expectedSimpleName>`. The " +
                        "scalar Payload shape calls `$codecName.decode(...)` / `.encode(...)` " +
                        "against the bound field's declared type.",
                    param,
                )
                return
            }
            return
        }
        if (!implementsBoundingLengthCodecOfUInt(codecDecl)) {
            logger.error(
                "@LengthPrefixed @UseCodec($codecName::class) on $ownerName.$fieldName references " +
                    "`$codecName`, which does not implement " +
                    "`com.ditchoom.buffer.codec.BoundingLengthCodec<UInt>`. The length-prefixed " +
                    "list shape requires a bounding length codec to drive `applyBound` and bound " +
                    "the element-decode region. Implement `BoundingLengthCodec<UInt>` (e.g. " +
                    "`MqttRemainingLengthCodec`).",
                param,
            )
            return
        }
        val elementType =
            fieldType.arguments
                .firstOrNull()
                ?.type
                ?.resolve()
        if (elementType == null || elementType.isError || elementType.isMarkedNullable) return
        val elementDecl = elementType.declaration as? KSClassDeclaration
        val elementQname = elementType.declaration.qualifiedName?.asString() ?: "<unresolved>"
        val isProtocolMessage =
            elementDecl != null &&
                elementDecl.annotations.any { ann ->
                    ann.shortName.asString() == "ProtocolMessage" &&
                        ann.annotationType
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString() == PROTOCOL_MESSAGE_QNAME
                }
        val isDataClass = elementDecl != null && Modifier.DATA in elementDecl.modifiers
        val isSealed = elementDecl != null && Modifier.SEALED in elementDecl.modifiers
        if (!isProtocolMessage || (!isDataClass && !isSealed)) {
            logger.error(
                "@LengthPrefixed @UseCodec($codecName::class) on $ownerName.$fieldName has list " +
                    "element type `$elementQname`, which is not a `@ProtocolMessage data class` " +
                    "or `@ProtocolMessage` sealed parent. The emitter generates " +
                    "`<E>Codec.decode(...)` / `<E>Codec.encode(...)` per element — the element " +
                    "type must carry `@ProtocolMessage` and be either a `data class` or a sealed " +
                    "parent (sealed interface / sealed class) with `@DispatchOn`.",
                param,
            )
            return
        }
        // After the !isProtocolMessage guard returns, elementDecl is non-null.
        elementDecl!!
        if (hasPayloadTypeParameter(elementDecl)) {
            logger.error(
                "@LengthPrefixed @UseCodec($codecName::class) on $ownerName.$fieldName has list " +
                    "element type `$elementQname`, which carries a `<P : Payload>` type " +
                    "parameter. The emitter calls `${elementDecl.simpleName.asString()}Codec." +
                    "decode(...)` / `.encode(...)` directly, which requires the element's " +
                    "generated codec to be a singleton object — but a `<P : Payload>` type " +
                    "parameter forces it to emit as a generic class. Property-bag elements must " +
                    "be non-payload-generic.",
                param,
            )
        }
    }

    /**
     * Does `decl` carry a `<P: Payload>` type parameter?
     * Mirrors the `detectPayloadTypeParameter` rule used in
     * `CodecEmitter.kt` for dispatcher-class detection. Used by the
     * `@LengthPrefixed @UseCodec(C) val: List<E>` validator to reject
     * element types whose generated codec would emit as a generic class
     * (the property-bag emit shape calls `<E>Codec.decode(...)` /
     * `.encode(...)` directly, requiring a singleton object).
     */
    private fun hasPayloadTypeParameter(decl: KSClassDeclaration): Boolean {
        val typeParams = decl.typeParameters
        if (typeParams.size != 1) return false
        val bounds = typeParams[0].bounds.toList()
        if (bounds.size != 1) return false
        val bound = bounds[0].resolve()
        if (bound.isError) return false
        return bound.declaration.qualifiedName?.asString() == PAYLOAD_QNAME
    }

    /**
     * Issue #176 — reject the type-unsafe sealed shape where the
     * sealed PARENT is non-generic but one or more variants declare a
     * `<P : Payload>` type parameter (and therefore extend the raw
     * parent instead of `Parent<P>` / `Parent<Nothing>`).
     *
     * In that shape the dispatcher has nowhere to bind the variant
     * codec's `<P>` at dispatch time, so the generated decode/encode
     * code references an unresolved generic codec and won't compile.
     * The correct shape declares the parent as
     * `sealed interface Parent<out P : Payload>`, with non-generic
     * variants extending `Parent<Nothing>` and generic variants
     * extending `Parent<P>`.
     *
     * Fires on both the simple `@PacketType` dispatch path and the
     * `@DispatchOn` bit-packed path. Returns `true` (and emits a
     * `logger.error`) when the unsound shape is detected so callers can
     * early-return.
     */
    private fun validateGenericPayloadVariantShape(
        parent: KSClassDeclaration,
        parentName: String,
    ): Boolean {
        if (hasPayloadTypeParameter(parent)) return false
        val genericVariants =
            parent
                .getSealedSubclasses()
                .filterNot { it.hasAnnotation(UNKNOWN_VARIANT_SHORT, UNKNOWN_VARIANT_QNAME) }
                .filter { hasPayloadTypeParameter(it) }
                .map { it.qualifiedName?.asString() ?: it.simpleName.asString() }
                .toList()
        if (genericVariants.isEmpty()) return false
        val variantList = genericVariants.joinToString(", ")
        logger.error(
            "@ProtocolMessage sealed parent $parentName has generic-payload variant(s) " +
                "($variantList) that declare a `<P : Payload>` type parameter, but the parent " +
                "itself is not generic. This shape is type-unsafe — the variant codec's `<P>` has " +
                "no binding at dispatch time, so the generated decode/encode code won't compile. " +
                "Declare the parent as `sealed interface $parentName<out P : Payload>` and have " +
                "generic variants extend `$parentName<P>` and non-generic variants extend " +
                "`$parentName<Nothing>`.",
            parent,
        )
        return true
    }

    /**
     * Does `codecDecl` implement
     * `com.ditchoom.buffer.codec.BoundingLengthCodec<UInt>`? Walks the
     * object's full super-type set and compares the single type argument
     * against `kotlin.UInt`. The `applyBound` method is what the emitter
     * needs at decode time; restricting to `UInt` is the slice's locked
     * scope (covers MQTT var-byte-int, LEB128, sentinel-extended
     * lengths up to 4 bytes — enough for every current target protocol).
     */
    private fun implementsBoundingLengthCodecOfUInt(codecDecl: KSClassDeclaration): Boolean {
        for (st in codecDecl.getAllSuperTypes()) {
            if (st.isError) continue
            val q = st.declaration.qualifiedName?.asString()
            if (q != BOUNDING_LENGTH_CODEC_QNAME) continue
            val arg =
                st.arguments
                    .firstOrNull()
                    ?.type
                    ?.resolve() ?: continue
            if (arg.isError) continue
            if (arg.declaration.qualifiedName?.asString() == "kotlin.UInt") return true
        }
        return false
    }

    /**
     * `@FramedBy` validator.
     *
     * `@FramedBy` is the structural replacement for 's
     * `@DerivedLength`: the framework owns framing, computing the prefix
     * from the encoded body's wire size and asserting strict consumption
     * on decode. The annotation is class-level and applies to data
     * classes (standalone framed messages) or sealed parents (every
     * variant inherits the framing rule).
     *
     * Diagnostics:
     *   - **E1** — codec target must implement `BoundingLengthCodec<UInt>`.
     *     The slicing-scheme emit calls the codec's `encode` for the
     *     prefix, `applyBound` on decode, and reads `maxWireSize` to
     *     size the slack region.
     *   - **E2** — `after = "X"` names a field that is not on the class's
     *     primary constructor (or, for sealed parents, on every variant's
     *     primary constructor).
     *   - **E3** — `after = "X"` names a field whose type does not have
     *     Exact wire width. The header field's width must be known at
     *     codegen time so the slack region is sized correctly.
     *   - **E4** — class participates in `@PacketType` dispatch
     *     (sealed parent with `@PacketType` variants, or class itself
     *     carries `@PacketType`) but `after = ""`. The discriminator must
     *     precede the prefix so the dispatcher can route.
     *   - **E6** — class carries both `@FramedBy` and a `@DerivedLength`-
     *     annotated field. Two annotations for the same wire concern is
     *     the muddle the design pass exists to prevent. Transient — only
     *     fires if a stale fixture survives the same-commit removal.
     */
    private fun validateFramedBy(owner: KSClassDeclaration) {
        val ownerName = owner.qualifiedName?.asString() ?: owner.simpleName.asString()
        val framedByAnn =
            owner.annotations.firstOrNull { ann ->
                ann.shortName.asString() == FRAMED_BY_SHORT &&
                    ann.annotationType
                        .resolve()
                        .declaration.qualifiedName
                        ?.asString() == FRAMED_BY_QNAME
            } ?: return

        // E6 — coexistence with @DerivedLength on a constructor parameter.
        val ctor = owner.primaryConstructor
        val derivedField =
            ctor?.parameters?.firstOrNull { p ->
                p.annotations.any { ann ->
                    ann.shortName.asString() == DERIVED_LENGTH_SHORT &&
                        ann.annotationType
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString() == DERIVED_LENGTH_QNAME
                }
            }
        if (derivedField != null) {
            val derivedName = derivedField.name?.asString() ?: "<unknown>"
            logger.error(
                "@FramedBy on $ownerName cannot coexist with @DerivedLength on " +
                    "$ownerName.$derivedName. Both annotations target the same wire concern (the " +
                    "framing prefix). Remove the @DerivedLength field — @FramedBy takes ownership of " +
                    "framing and discards the prefix value on decode.",
                owner,
            )
        }

        // E1 — codec target must be `BoundingLengthCodec<UInt>`.
        val codecKsType =
            framedByAnn.arguments
                .firstOrNull { it.name?.asString() == "codec" }
                ?.value as? KSType
        val codecDecl = codecKsType?.declaration as? KSClassDeclaration
        if (codecDecl != null && !implementsBoundingLengthCodecOfUInt(codecDecl)) {
            val codecName = codecDecl.qualifiedName?.asString() ?: codecDecl.simpleName.asString()
            logger.error(
                "@FramedBy on $ownerName references `$codecName`, which does not implement " +
                    "`com.ditchoom.buffer.codec.BoundingLengthCodec<UInt>`. The slicing-scheme emit " +
                    "needs `applyBound` (decode) and `maxWireSize` (encode); the codec must declare " +
                    "the bounding-length capability with `UInt` as its type argument.",
                owner,
            )
        }

        val afterName =
            (
                framedByAnn.arguments
                    .firstOrNull { it.name?.asString() == "after" }
                    ?.value as? String
            ) ?: ""

        val isSealedParent = Modifier.SEALED in owner.modifiers && owner.classKind == ClassKind.INTERFACE

        if (afterName.isEmpty()) {
            // E4 — @FramedBy with no `after` is incompatible with @PacketType dispatch.
            val variantsWithPacketType =
                if (isSealedParent) {
                    owner
                        .getSealedSubclasses()
                        .filter { sub ->
                            sub.annotations.any { ann ->
                                ann.shortName.asString() == PACKET_TYPE_SHORT &&
                                    ann.annotationType
                                        .resolve()
                                        .declaration.qualifiedName
                                        ?.asString() == PACKET_TYPE_QNAME
                            }
                        }.toList()
                } else {
                    emptyList()
                }
            val ownerHasPacketType =
                owner.annotations.any { ann ->
                    ann.shortName.asString() == PACKET_TYPE_SHORT &&
                        ann.annotationType
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString() == PACKET_TYPE_QNAME
                }
            if (ownerHasPacketType || variantsWithPacketType.isNotEmpty()) {
                logger.error(
                    "@FramedBy on $ownerName has `after = \"\"` but the class participates in " +
                        "@PacketType dispatch. The discriminator byte must precede the framing prefix " +
                        "on the wire so the dispatcher can route — set `after = \"<headerField>\"` " +
                        "naming the constructor field that carries the discriminator.",
                    owner,
                )
            }
            return
        }

        // E2/E3 — `after = "X"`: X exists with Exact wire width on every primary constructor.
        val targets: List<Pair<KSClassDeclaration, List<KSValueParameter>>> =
            if (isSealedParent) {
                // The `@ForwardCompatible` unknown-variant sink carries no
                // discriminator/header field — the dispatcher writes the
                // discriminator itself on the preserve path — so it is
                // exempt from the `after`-field requirement.
                val dispatchVariants =
                    owner.getSealedSubclasses().toList().filterNot {
                        it.hasAnnotation(UNKNOWN_VARIANT_SHORT, UNKNOWN_VARIANT_QNAME)
                    }
                dispatchVariants.mapNotNull { variant -> variant.primaryConstructor?.let { variant to it.parameters } }
            } else {
                listOf(owner to (ctor?.parameters ?: emptyList()))
            }

        for ((variant, parameters) in targets) {
            val variantName = variant.qualifiedName?.asString() ?: variant.simpleName.asString()
            val target = parameters.firstOrNull { it.name?.asString() == afterName }
            if (target == null) {
                val available = parameters.mapNotNull { it.name?.asString() }
                logger.error(
                    "@FramedBy on $ownerName: `after = \"$afterName\"` names a field that is not on " +
                        "$variantName's primary constructor. Available: ${available.joinToString().ifEmpty { "<none>" }}.",
                    variant,
                )
                continue
            }
            // E3 — Exact wire width: only fixed-width scalar types or value classes wrapping them.
            val targetType = target.type.resolve()
            val targetTypeQname = targetType.declaration.qualifiedName?.asString()
            val targetIsValueClass =
                (targetType.declaration as? KSClassDeclaration)?.isValueClassDecl() ?: false
            val isFixedScalar = targetTypeQname in NATURAL_WIDTHS
            if (!isFixedScalar && !targetIsValueClass) {
                logger.error(
                    "@FramedBy on $ownerName: `after = \"$afterName\"` on $variantName has type " +
                        "`${targetTypeQname ?: "<unresolved>"}`, which does not have Exact wire width. " +
                        "Only fixed-width scalars (UByte/Byte/UShort/Short/UInt/Int/ULong/Long) or " +
                        "@JvmInline value classes wrapping them are accepted as the framing header.",
                    target,
                )
                continue
            }
            // Reject annotations that introduce variable wire width on the header field.
            val variableAnns = setOf("LengthPrefixed", "LengthFrom", "RemainingBytes", "When", "WireBytes")
            val targetAnnNames = target.annotations.map { it.shortName.asString() }.toSet()
            val variableHits = targetAnnNames.intersect(variableAnns)
            if (variableHits.isNotEmpty()) {
                logger.error(
                    "@FramedBy on $ownerName: `after = \"$afterName\"` on $variantName carries " +
                        "${variableHits.joinToString { "@$it" }}, which produces variable wire width. " +
                        "The framing header must have Exact wire width.",
                    target,
                )
            }
        }
    }

    private fun KSClassDeclaration.hasAnnotation(
        short: String,
        qname: String,
    ): Boolean =
        annotations.any { ann ->
            ann.shortName.asString() == short &&
                ann.annotationType
                    .resolve()
                    .declaration.qualifiedName
                    ?.asString() == qname
        }

    /**
     * `@ForwardCompatible` validator. Enforces the skip-and-preserve
     * contract's compile-time rules (spec §"Compile-time rules"):
     *
     *   - **F1** — requires `@FramedBy` on the same type. You cannot
     *     skip an unknown variant without a framing length to measure
     *     its payload.
     *   - **F2** — requires `@DispatchOn` with a single-byte
     *     discriminator. Framed sealed dispatch routes exclusively
     *     through `@DispatchOn`; a single-byte discriminator guarantees
     *     the preserved opcode re-encodes byte-identically (the design's
     *     byte-identity requirement).
     *   - **F3** — exactly one `@UnknownVariant` sealed member, and it
     *     must be the type named by `unknown`.
     *   - **F4** — the `@UnknownVariant` member must not carry
     *     `@PacketType` (it is the `else` sink, never value-matched).
     *   - **F5** — the `@UnknownVariant` member's primary constructor
     *     must be shaped `(opcode: Int, raw: PlatformBuffer)` (a
     *     `ReadBuffer`-typed `raw` is also accepted).
     *
     * Only runs on sealed interfaces (the dispatch parents); a
     * `@ForwardCompatible` on any other target is a no-op here (the
     * annotation `@Target(CLASS)` admits it, but it has no meaning
     * without sealed dispatch — caught as F2's missing `@DispatchOn`).
     */
    private fun validateForwardCompatible(owner: KSClassDeclaration) {
        val ann =
            owner.annotations.firstOrNull { a ->
                a.shortName.asString() == FORWARD_COMPATIBLE_SHORT &&
                    a.annotationType
                        .resolve()
                        .declaration.qualifiedName
                        ?.asString() == FORWARD_COMPATIBLE_QNAME
            } ?: return
        val ownerName = owner.qualifiedName?.asString() ?: owner.simpleName.asString()

        // F1 — requires @FramedBy.
        if (!owner.hasAnnotation(FRAMED_BY_SHORT, FRAMED_BY_QNAME)) {
            logger.error(
                "@ForwardCompatible on $ownerName requires @FramedBy on the same type: cannot " +
                    "skip an unknown variant without a framing length. Add " +
                    "@FramedBy(<LengthCodec>::class, after = \"<discriminatorField>\").",
                owner,
            )
        }

        // F2 — requires @DispatchOn with a single-byte or varint discriminator.
        val dispatchOn =
            owner.annotations.firstOrNull { a ->
                a.shortName.asString() == DISPATCH_ON_SHORT &&
                    a.annotationType
                        .resolve()
                        .declaration.qualifiedName
                        ?.asString() == DISPATCH_ON_QNAME
            }
        // Discriminator shape, threaded into F5: the opcode parameter's
        // required type depends on whether the discriminator is the
        // legacy single-byte scalar (opcode: Int carrying the byte) or a
        // varint value class (opcode: Long / ULong carrying the full
        // decoded value, re-encoded through the discriminator's codec).
        var discInnerQname: String? = null
        var discIsVarint = false
        if (dispatchOn == null) {
            logger.error(
                "@ForwardCompatible on $ownerName requires @DispatchOn dispatch with a single-byte " +
                    "or varint discriminator. Framed sealed dispatch routes through @DispatchOn; " +
                    "wrap the opcode in a `@JvmInline value class` carrying a @DispatchValue and " +
                    "annotate the parent with @DispatchOn(<OpCode>::class).",
                owner,
            )
        } else {
            val discriminatorType =
                dispatchOn.arguments
                    .firstOrNull { it.name?.asString() == "type" }
                    ?.value as? KSType
            val discriminatorDecl = discriminatorType?.declaration as? KSClassDeclaration
            val innerParam =
                discriminatorDecl
                    ?.primaryConstructor
                    ?.parameters
                    ?.singleOrNull()
            val innerQname =
                innerParam
                    ?.type
                    ?.resolve()
                    ?.declaration
                    ?.qualifiedName
                    ?.asString()
            discInnerQname = innerQname
            discIsVarint = innerParam?.hasVariableLengthUseCodec() == true
            // Two preservable shapes: a single-byte inner scalar (the
            // opcode byte re-encodes via writeUByte) or a varint inner
            // (`@UseCodec(VariableLengthCodec)` on a Long / ULong inner —
            // the opcode value re-encodes through the discriminator's own
            // codec, so a multi-byte GREASE-style type round-trips).
            // Fixed multi-byte inners (UShort / UInt / …) remain
            // unsupported: their preserve path would need a byte-order-
            // aware re-encode the emit doesn't produce.
            val singleByte = innerQname in SINGLE_BYTE_SCALAR_QNAMES
            val varintWide = discIsVarint && innerQname in VARINT_OPCODE_QNAMES
            if (innerQname != null && !singleByte && !varintWide) {
                logger.error(
                    "@ForwardCompatible on $ownerName requires a single-byte @DispatchOn " +
                        "discriminator (Byte / UByte inner scalar) or a varint discriminator " +
                        "(`@UseCodec(<VariableLengthCodec>) raw: Long | ULong` inner), but the " +
                        "discriminator's inner type is `$innerQname`" +
                        (if (discIsVarint) " (varint)" else "") +
                        ". A fixed multi-byte discriminator cannot round-trip byte-identically " +
                        "through the preserve path.",
                    owner,
                )
            }
        }

        // F3 — exactly one @UnknownVariant sealed member, matching `unknown`.
        val unknownMembers =
            owner.getSealedSubclasses().filter { it.hasAnnotation(UNKNOWN_VARIANT_SHORT, UNKNOWN_VARIANT_QNAME) }.toList()
        if (unknownMembers.size != 1) {
            logger.error(
                "@ForwardCompatible on $ownerName requires exactly one sealed member marked " +
                    "@UnknownVariant, but found ${unknownMembers.size}. The unknown variant is the " +
                    "single sink that unrecognized discriminators are preserved into.",
                owner,
            )
            return
        }
        val sink = unknownMembers[0]
        val sinkName = sink.qualifiedName?.asString() ?: sink.simpleName.asString()

        val declaredUnknown =
            ann.arguments
                .firstOrNull { it.name?.asString() == "unknown" }
                ?.value as? KSType
        val declaredUnknownQname = declaredUnknown?.declaration?.qualifiedName?.asString()
        if (declaredUnknownQname != null && declaredUnknownQname != sink.qualifiedName?.asString()) {
            logger.error(
                "@ForwardCompatible(unknown = ...) on $ownerName names `$declaredUnknownQname`, but " +
                    "the @UnknownVariant member is `$sinkName`. `unknown` must name the " +
                    "@UnknownVariant-marked member.",
                owner,
            )
        }

        // F4 — @UnknownVariant must not carry @PacketType.
        if (sink.hasAnnotation(PACKET_TYPE_SHORT, PACKET_TYPE_QNAME)) {
            logger.error(
                "@UnknownVariant $sinkName must not carry @PacketType — it is the `else` sink of " +
                    "forward-compatible dispatch and is never matched by discriminator value.",
                sink,
            )
        }

        // F5 — (opcode: Int | Long | ULong, raw: PlatformBuffer | ReadBuffer).
        // The opcode kind must match the discriminator shape: a single-
        // byte discriminator preserves the discriminator *byte* as `Int`;
        // a varint discriminator preserves the full decoded value, so the
        // opcode parameter must be the discriminator's own inner type
        // (Long / ULong) for a lossless preserve→re-encode round trip.
        val params = sink.primaryConstructor?.parameters.orEmpty()
        val expectedOpcodeQname = if (discIsVarint) discInnerQname else INT_QNAME
        val hasOpcode =
            params.any {
                it.type
                    .resolve()
                    .declaration.qualifiedName
                    ?.asString() == expectedOpcodeQname
            }
        val hasRawBuffer =
            params.any {
                it.type
                    .resolve()
                    .declaration.qualifiedName
                    ?.asString() in
                    setOf(PLATFORM_BUFFER_QNAME, READ_BUFFER_QNAME)
            }
        if (params.size != 2 || !hasOpcode || !hasRawBuffer) {
            val expectedOpcodeSimple = expectedOpcodeQname?.substringAfterLast('.') ?: "Int"
            logger.error(
                "@UnknownVariant $sinkName must have a primary constructor shaped " +
                    "`(opcode: $expectedOpcodeSimple, raw: PlatformBuffer)` (a ReadBuffer-typed " +
                    "`raw` is also accepted): `opcode` carries the " +
                    (
                        if (discIsVarint) {
                            "discriminator's full decoded value (its inner type, so the preserve" +
                                "→re-encode round trip is lossless)"
                        } else {
                            "discriminator byte"
                        }
                    ) +
                    " and `raw` carries the opaque preserved payload.",
                sink,
            )
        }
    }

    /**
     * `<P: Payload>` type-parameter shape
     * validation.
     *
     * A `@ProtocolMessage` data class with a `<P : Payload>` type
     * parameter must use the parameter as the type of at least one
     * `@RemainingBytes`-annotated field. Without that field, the
     * type parameter is unused — KSP would infer it as `Nothing` at
     * call sites, and the constructor-injected `Codec<P>` parameter
     * the emitter generates would have nothing to drive.
     *
     * Narrow: at most one type parameter per data class,
     * single `Payload` upper bound. Multiple type parameters or
     * non-`Payload` bounds are rejected here so the generic emit
     * path stays type-safe.
     */
    private fun validatePayloadTypeParameter(
        owner: KSClassDeclaration,
        parameters: List<KSValueParameter>,
    ) {
        val ownerName = owner.qualifiedName?.asString() ?: owner.simpleName.asString()
        val typeParams = owner.typeParameters
        if (typeParams.isEmpty()) return
        if (typeParams.size > 1) {
            logger.error(
                "@ProtocolMessage `$ownerName` declares ${typeParams.size} type parameters; " +
                    "at most one (`<P : Payload>`) is supported. Multiple type parameters " +
                    "are not yet supported.",
                owner,
            )
            return
        }
        val tp = typeParams[0]
        val bounds = tp.bounds.toList()
        val tpName = tp.name.asString()
        if (bounds.size != 1) {
            logger.error(
                "@ProtocolMessage `$ownerName` type parameter `$tpName` must have exactly one " +
                    "upper bound (`<$tpName : Payload>`), but has ${bounds.size}. " +
                    "A single `Payload` bound is supported.",
                owner,
            )
            return
        }
        val boundQname =
            bounds[0]
                .resolve()
                .declaration.qualifiedName
                ?.asString()
        if (boundQname != PAYLOAD_QNAME) {
            logger.error(
                "@ProtocolMessage `$ownerName` type parameter `$tpName` must be bounded by " +
                    "`com.ditchoom.buffer.codec.Payload`, but is bounded by " +
                    "`${boundQname ?: "<unresolved>"}`. The generic-bounded payload slot " +
                    "is the only generics path the emitter recognizes today.",
                owner,
            )
            return
        }
        // Bound is Payload — confirm at least one parameter has type
        // P AND `@RemainingBytes`.
        val anyMatching =
            parameters.any { p ->
                val ftype = p.type.resolve()
                if (ftype.isError) return@any false
                val fdecl = ftype.declaration
                if (fdecl !is com.google.devtools.ksp.symbol.KSTypeParameter) return@any false
                if (fdecl.name.asString() != tpName) return@any false
                p.annotations.any { ann ->
                    ann.shortName.asString() == REMAINING_BYTES_SHORT &&
                        ann.annotationType
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString() == REMAINING_BYTES_QNAME
                }
            }
        if (!anyMatching) {
            logger.error(
                "@ProtocolMessage `$ownerName` declares type parameter `<$tpName : Payload>` but " +
                    "no `@RemainingBytes val: $tpName` field uses it. The generic emit path " +
                    "exists to surface a constructor-injected `Codec<$tpName>` parameter — " +
                    "without a field consuming it, the parameter is unused. Either add " +
                    "`@RemainingBytes val payload: $tpName` or drop the " +
                    "type parameter and use `@UseCodec(...)` against a concrete `Payload` " +
                    "subtype.",
                owner,
            )
        }
    }

    /**
     * Does `codecDecl` implement
     * `com.ditchoom.buffer.codec.Codec<T>` where `T == fieldType`?
     * Walks the object's full super-type set and compares the single
     * type argument's qualified name and nullability against the bound
     * field's resolved type.
     *
     * Conservative match: requires fully-resolved type args. Returns
     * `false` for error types (KSP unresolved imports), letting the
     * caller decide whether to skip silently or surface a diagnostic.
     */
    private fun implementsCodecOf(
        codecDecl: KSClassDeclaration,
        fieldType: KSType,
    ): Boolean {
        val expectedQname = fieldType.declaration.qualifiedName?.asString() ?: return false
        // Walk the full supertype chain so a `BoundingLengthCodec<T>` or
        // `VariableLengthCodec<T>` impl (each transitively implements
        // `Codec<T>`) is also recognized. All three are accepted: KSP's
        // `getAllSuperTypes()` doesn't substitute the type variable from the
        // intermediate interface declaration, so the transitive `Codec<T>`
        // entry still carries the unsubstituted T. The concrete type arg is
        // present on whichever interface the codec object directly extends.
        for (st in codecDecl.getAllSuperTypes()) {
            if (st.isError) continue
            val q = st.declaration.qualifiedName?.asString()
            if (q != CODEC_QNAME &&
                q != BOUNDING_LENGTH_CODEC_QNAME &&
                q != VARIABLE_LENGTH_CODEC_QNAME &&
                q != VIEW_CODEC_QNAME
            ) {
                continue
            }
            val arg =
                st.arguments
                    .firstOrNull()
                    ?.type
                    ?.resolve() ?: continue
            if (arg.isError) continue
            if (arg.declaration.qualifiedName?.asString() == expectedQname) return true
        }
        return false
    }

    private fun walkType(
        type: KSType,
        owner: KSClassDeclaration,
        param: KSValueParameter,
        payloadType: KSType,
        depth: Int,
        visited: MutableSet<String>,
    ) {
        if (depth > MAX_DEPTH) return
        if (type.isError) return
        val nonNullableType = if (type.isMarkedNullable) type.makeNotNullable() else type

        val qualified = type.declaration.qualifiedName?.asString()
        if (qualified != null && qualified in FORBIDDEN_TYPES) {
            val ownerName = owner.qualifiedName?.asString() ?: owner.simpleName.asString()
            val fieldName = param.name?.asString() ?: "<unknown>"
            logger.error(
                "@ProtocolMessage field $ownerName.$fieldName has raw-bytes type $qualified. " +
                    "ReadBuffer / WriteBuffer / PlatformBuffer / ByteArray / primitive arrays / " +
                    "ByteBuffer are forbidden in @ProtocolMessage data classes — raw buffer/bytes " +
                    "types leak ownership ambiguity (who frees, when, aliased?). Decode into a " +
                    "self-contained typed value: a `Payload` marker over a domain object, " +
                    "`String`, scalars, or a platform-native handle (e.g. `Bitmap` via " +
                    "`buffer.toNativeData()` → platform decoder), wired via `@UseCodec`. For raw " +
                    "bytes (IPC forwarding, persistence), step outside `Payload`: a non-`Payload` " +
                    "result type decoded by a hand-written `Codec<YourType>` (see " +
                    "`ReadBuffer.copyToByteArray` for heap bytes; " +
                    "`factory.allocate().write(source)` for consumer-owned `PlatformBuffer`).",
                param,
            )
            return
        }

        // Cycle guard for self-referential generic types.
        val fingerprint = type.toFingerprint()
        if (!visited.add(fingerprint)) return

        val decl = type.declaration as? KSClassDeclaration
        if (decl != null) {
            // When the type implements `Payload` (but isn't the bare interface itself),
            // enforce the strict transitive rule: no raw-bytes types anywhere in its
            // declared shape, including through value-class wrappers and sealed trees.
            // This closes the buffer-codec lockdown v1 bug class — a `BufferPayload(val
            // buffer: ReadBuffer) : Payload` slips past the outer FORBIDDEN_TYPES check
            // today because the walk used to short-circuit at Payload.
            val isPayload = qualified != PAYLOAD_QNAME && payloadType.isAssignableFrom(nonNullableType)
            if (isPayload) {
                validatePayloadShape(decl, payloadType, mutableSetOf())
                // validatePayloadShape handles the value-class descent for Payload
                // types itself; skip walkType's own value-class step to avoid
                // double-firing for `value class X(val raw: ReadBuffer) : Payload`.
            } else if (decl.isValueClassDecl()) {
                val inner =
                    decl.primaryConstructor
                        ?.parameters
                        ?.firstOrNull()
                        ?.type
                        ?.resolve()
                if (inner != null) {
                    walkType(inner, owner, param, payloadType, depth + 1, visited)
                }
            }
        }

        for (arg in type.arguments) {
            val argType = arg.type?.resolve() ?: continue
            walkType(argType, owner, param, payloadType, depth + 1, visited)
        }
    }

    /**
     * Walks every declared property of a `Payload`-implementing class
     * (recursively through value-class wrappers, sealed trees, and nested
     * Payload references) and emits a Payload-flavored diagnostic for any
     * raw-bytes type found. Called by [walkType] when it encounters a
     * concrete `Payload` type as a field or type argument.
     *
     * Bug class closed: `data class BytesPayload(val a: Int, val b: ByteArray)
     * : Payload` would compile today — `Payload` is a marker for self-contained
     * typed values, and the type system can't verify that raw bytes inside a
     * Payload are safe to retain past the codec's scope.
     */
    private fun validatePayloadShape(
        payloadClass: KSClassDeclaration,
        payloadType: KSType,
        payloadVisited: MutableSet<String>,
    ) {
        val qname = payloadClass.qualifiedName?.asString() ?: return
        if (!payloadVisited.add(qname)) return

        if (Modifier.SEALED in payloadClass.modifiers) {
            for (sub in payloadClass.getSealedSubclasses()) {
                validatePayloadShape(sub, payloadType, payloadVisited)
            }
            return
        }
        val ctor = payloadClass.primaryConstructor ?: return
        for (property in ctor.parameters) {
            validatePayloadProperty(
                payloadClass = payloadClass,
                property = property,
                type = property.type.resolve(),
                payloadType = payloadType,
                payloadVisited = payloadVisited,
                depth = 0,
            )
        }
    }

    /**
     * Inner recursion for [validatePayloadShape]. Checks a property's resolved
     * [type] against [FORBIDDEN_TYPES]; descends through value-class wrappers,
     * generic type arguments, and nested Payload references.
     *
     * The Payload-framed error always blames `payloadClass.property` — the
     * declared site of the raw-bytes leak — even when discovered via a chain
     * of wrappers. The owner @ProtocolMessage field is unrelated to the rule;
     * the violation lives inside the Payload type itself.
     */
    private fun validatePayloadProperty(
        payloadClass: KSClassDeclaration,
        property: KSValueParameter,
        type: KSType,
        payloadType: KSType,
        payloadVisited: MutableSet<String>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        if (type.isError) return
        val nonNullable = if (type.isMarkedNullable) type.makeNotNullable() else type
        val qname = type.declaration.qualifiedName?.asString()

        if (qname != null && qname in FORBIDDEN_TYPES) {
            emitPayloadViolation(payloadClass, property, qname)
            return
        }

        val decl = type.declaration as? KSClassDeclaration
        if (decl != null) {
            // Value-class wrappers: peel and recurse on the inner.
            if (decl.isValueClassDecl()) {
                val inner =
                    decl.primaryConstructor
                        ?.parameters
                        ?.firstOrNull()
                        ?.type
                        ?.resolve()
                if (inner != null) {
                    validatePayloadProperty(
                        payloadClass,
                        property,
                        inner,
                        payloadType,
                        payloadVisited,
                        depth + 1,
                    )
                }
            }
            // Nested Payload reference: validate that Payload's shape too, via
            // validatePayloadShape (which dedupes via payloadVisited).
            if (qname != PAYLOAD_QNAME && payloadType.isAssignableFrom(nonNullable)) {
                validatePayloadShape(decl, payloadType, payloadVisited)
            }
        }

        // Generic type arguments (e.g. List<Foo>, Map<K, V>).
        for (arg in type.arguments) {
            val argType = arg.type?.resolve() ?: continue
            validatePayloadProperty(
                payloadClass,
                property,
                argType,
                payloadType,
                payloadVisited,
                depth + 1,
            )
        }
    }

    private fun emitPayloadViolation(
        payloadClass: KSClassDeclaration,
        property: KSValueParameter,
        forbiddenQname: String,
    ) {
        val pName = payloadClass.qualifiedName?.asString() ?: payloadClass.simpleName.asString()
        val fName = property.name?.asString() ?: "<unknown>"
        logger.error(
            "Payload types must not embed raw buffer or array types ($pName.$fName: $forbiddenQname).\n\n" +
                "Payload is a marker for self-contained typed values. Raw bytes (ReadBuffer, " +
                "ByteArray, ByteBuffer, primitive arrays) carry implicit ownership/lifetime " +
                "obligations the type system can't verify — a captured buffer outlives the " +
                "codec's scope and reads reclaimed pool memory; a JS-aliased ByteArray inside a " +
                "Payload escapes the same way through a different door.\n\n" +
                "Decode into a self-contained typed value: a value class, String, domain object, " +
                "or platform-native handle (e.g. Bitmap via buffer.toNativeData() → platform " +
                "decoder).\n\n" +
                "For raw bytes (IPC forwarding, persistence), step outside the Payload " +
                "abstraction: define a non-Payload result type and decode through a hand-written " +
                "Codec<YourType>. See ReadBuffer.copyToByteArray for heap bytes; " +
                "factory.allocate().write(source) for consumer-owned PlatformBuffer.",
            property,
        )
    }

    private fun KSType.toFingerprint(): String {
        val name = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
        if (arguments.isEmpty()) return name
        val args =
            arguments.joinToString(",") { arg ->
                arg.type
                    ?.resolve()
                    ?.declaration
                    ?.qualifiedName
                    ?.asString() ?: "*"
            }
        return "$name<$args>"
    }

    private companion object {
        private const val PROTOCOL_MESSAGE_QNAME = "com.ditchoom.buffer.codec.annotations.ProtocolMessage"
        private const val PAYLOAD_QNAME = "com.ditchoom.buffer.codec.Payload"
        private const val LENGTH_FROM_QNAME = "com.ditchoom.buffer.codec.annotations.LengthFrom"
        private const val LENGTH_FROM_SHORT = "LengthFrom"
        private const val LENGTH_PREFIXED_SHORT = "LengthPrefixed"
        private const val WIRE_BYTES_QNAME = "com.ditchoom.buffer.codec.annotations.WireBytes"
        private const val WIRE_BYTES_SHORT = "WireBytes"
        private const val PACKET_TYPE_QNAME = "com.ditchoom.buffer.codec.annotations.PacketType"
        private const val PACKET_TYPE_SHORT = "PacketType"
        private const val DISPATCH_ON_QNAME = "com.ditchoom.buffer.codec.annotations.DispatchOn"
        private const val DISPATCH_ON_SHORT = "DispatchOn"
        private const val DISPATCH_VALUE_QNAME = "com.ditchoom.buffer.codec.annotations.DispatchValue"
        private const val DISPATCH_VALUE_SHORT = "DispatchValue"
        private const val WHEN_QNAME = "com.ditchoom.buffer.codec.annotations.When"
        private const val WHEN_SHORT = "When"
        private const val USE_CODEC_QNAME = "com.ditchoom.buffer.codec.annotations.UseCodec"
        private const val USE_CODEC_SHORT = "UseCodec"
        private const val DERIVED_LENGTH_QNAME = "com.ditchoom.buffer.codec.annotations.DerivedLength"
        private const val DERIVED_LENGTH_SHORT = "DerivedLength"
        private const val FRAMED_BY_QNAME = "com.ditchoom.buffer.codec.annotations.FramedBy"
        private const val FRAMED_BY_SHORT = "FramedBy"
        private const val FORWARD_COMPATIBLE_QNAME = "com.ditchoom.buffer.codec.annotations.ForwardCompatible"
        private const val FORWARD_COMPATIBLE_SHORT = "ForwardCompatible"
        private const val UNKNOWN_VARIANT_QNAME = "com.ditchoom.buffer.codec.annotations.UnknownVariant"
        private const val UNKNOWN_VARIANT_SHORT = "UnknownVariant"
        private const val PLATFORM_BUFFER_QNAME = "com.ditchoom.buffer.PlatformBuffer"
        private const val READ_BUFFER_QNAME = "com.ditchoom.buffer.ReadBuffer"
        private const val INT_QNAME = "kotlin.Int"
        private val SINGLE_BYTE_SCALAR_QNAMES = setOf("kotlin.Byte", "kotlin.UByte")

        // Inner scalar kinds accepted for a *varint* @ForwardCompatible
        // discriminator (QUIC varints decode into a 62-bit domain).
        private val VARINT_OPCODE_QNAMES = setOf("kotlin.Long", "kotlin.ULong")
        private const val REMAINING_BYTES_QNAME = "com.ditchoom.buffer.codec.annotations.RemainingBytes"
        private const val REMAINING_BYTES_SHORT = "RemainingBytes"
        private const val CODEC_QNAME = "com.ditchoom.buffer.codec.Codec"
        private const val BOUNDING_LENGTH_CODEC_QNAME = "com.ditchoom.buffer.codec.BoundingLengthCodec"
        private const val VARIABLE_LENGTH_CODEC_QNAME = "com.ditchoom.buffer.codec.VariableLengthCodec"
        private const val BOOLEAN_QNAME = "kotlin.Boolean"
        private const val STRING_QNAME = "kotlin.String"
        private const val OWNED_BYTES_HANDLE_QNAME = "com.ditchoom.buffer.codec.OwnedBytesHandle"
        private const val LIST_QNAME = "kotlin.collections.List"
        private const val MAX_DEPTH = 16

        // Qnames the [validateRemainingBytesElementType] check
        // rejects. Scalar element types that promote a copy-by-default
        // decode (every read boxes / allocates per element).
        private val SCALAR_QNAMES =
            setOf(
                "kotlin.Byte",
                "kotlin.UByte",
                "kotlin.Short",
                "kotlin.UShort",
                "kotlin.Int",
                "kotlin.UInt",
                "kotlin.Long",
                "kotlin.ULong",
                "kotlin.Float",
                "kotlin.Double",
                "kotlin.Boolean",
            )

        // Primitive-array types — same rejection rationale; never supported
        // as a `@RemainingBytes` field type.
        private val PRIMITIVE_ARRAY_QNAMES =
            setOf(
                "kotlin.ByteArray",
                "kotlin.UByteArray",
                "kotlin.ShortArray",
                "kotlin.UShortArray",
                "kotlin.IntArray",
                "kotlin.UIntArray",
                "kotlin.LongArray",
                "kotlin.ULongArray",
                "kotlin.FloatArray",
                "kotlin.DoubleArray",
                "kotlin.BooleanArray",
            )

        // Slice — accepted return types for an
        // `@DispatchValue`-annotated property, paired with the valid
        // range of `@PacketType.value` literals for each kind. Long
        // and ULong are intentionally absent — `@PacketType.value` is
        // `Int` and can't represent values beyond `Int.MAX_VALUE`.
        // For UInt the upper bound is `Int.MAX_VALUE` for the same
        // reason (a UInt > 2^31-1 isn't expressible as an Int literal).
        private val DISPATCH_VALUE_RETURN_RANGES =
            mapOf(
                "kotlin.Boolean" to 0..1,
                "kotlin.Byte" to Byte.MIN_VALUE.toInt()..Byte.MAX_VALUE.toInt(),
                "kotlin.UByte" to 0..0xFF,
                "kotlin.Short" to Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt(),
                "kotlin.UShort" to 0..0xFFFF,
                "kotlin.Int" to Int.MIN_VALUE..Int.MAX_VALUE,
                "kotlin.UInt" to 0..Int.MAX_VALUE,
            )

        private val NUMERIC_SCALAR_QNAMES =
            setOf(
                "kotlin.Byte",
                "kotlin.Short",
                "kotlin.Int",
                "kotlin.Long",
                "kotlin.UByte",
                "kotlin.UShort",
                "kotlin.UInt",
                "kotlin.ULong",
            )

        private val FORBIDDEN_TYPES =
            setOf(
                "com.ditchoom.buffer.ReadBuffer",
                "com.ditchoom.buffer.WriteBuffer",
                "com.ditchoom.buffer.PlatformBuffer",
                "java.nio.ByteBuffer",
                // Raw arrays — banned alongside ReadBuffer at the @ProtocolMessage
                // boundary and (transitively) inside Payload-implementing types
                // (see buffer-codec lockdown v1).
                "kotlin.ByteArray",
                "kotlin.ShortArray",
                "kotlin.IntArray",
                "kotlin.LongArray",
                "kotlin.FloatArray",
                "kotlin.DoubleArray",
                "kotlin.UByteArray",
                "kotlin.UShortArray",
                "kotlin.UIntArray",
                "kotlin.ULongArray",
            )

        private val NATURAL_WIDTHS =
            mapOf(
                "kotlin.UByte" to 1,
                "kotlin.Byte" to 1,
                "kotlin.UShort" to 2,
                "kotlin.Short" to 2,
                "kotlin.UInt" to 4,
                "kotlin.Int" to 4,
                "kotlin.ULong" to 8,
                "kotlin.Long" to 8,
            )
    }
}
