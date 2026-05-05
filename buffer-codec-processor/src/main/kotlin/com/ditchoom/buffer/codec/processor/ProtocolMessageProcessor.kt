package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
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
 * time per the Stages A–H plan in `PHASE_9_RESET.md`.
 *
 * Until Stage A lands, this processor enforces the rules that are
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
 *     widen to cover Payload slots (Stage H deferral); R1 expands to
 *     cover them once that widening lands. `@LengthFrom` is otherwise
 *     reserved for genuine remote-prefix uses (length carried in a
 *     non-adjacent field, parsed elsewhere, or parent-passed via
 *     `@DispatchOn`).
 *
 *   - **Stage D dispatcher rules.** A `@ProtocolMessage sealed
 *     interface` without `@DispatchOn` is a simple-dispatch parent.
 *     Every direct sealed subclass must carry `@PacketType(value =
 *     N)` with `N in 0..255`, and `value` must be unique within a
 *     parent. Missing/out-of-range/duplicate values are compile
 *     errors. Sealed parents carrying `@DispatchOn` are skipped
 *     (Stage F surface).
 *
 *   - **Stage F `@DispatchOn` value-class discriminator (slice 6).**
 *     A `@ProtocolMessage sealed interface` parent carrying
 *     `@DispatchOn(<DiscriminatorType>::class)` is a bit-packed
 *     dispatch parent. The discriminator must be a
 *     `@JvmInline value class` with a single non-nullable numeric-
 *     scalar inner and exactly one `@DispatchValue`-annotated `val`
 *     property returning non-nullable `Int`. Each variant must be
 *     a `data class` with `@PacketType(value = N)` (`N in 0..255`,
 *     unique within the parent), and must declare the
 *     DiscriminatorType as its first constructor parameter so the
 *     variant codec reads/writes the byte naturally (slice 6
 *     narrow — `object` and discriminator-from-context shapes are
 *     deferred).
 *
 *   - **Stage E `@LengthFrom` shape (Locked Decision row 18, slice
 *     4 + Stage G slice 7a).** For `@LengthFrom("siblingField") val
 *     payload: T`: the bound field type must be either `String`
 *     (slice 4) or `List<E>` where `E` is a `@ProtocolMessage data
 *     class` (slice 7a). The referenced parameter must exist as a
 *     sibling declared before the bound parameter, and must resolve
 *     to a numeric type (`Byte` / `Short` / `Int` / `Long` /
 *     `UByte` / `UShort` / `UInt` / `ULong`). Diagnostics list the
 *     numeric `val` siblings declared before the bound field. The
 *     adjacent-`@LengthFrom` migration suggestion (R1) is independent
 *     and continues to fire when the referenced sibling is the
 *     immediately-preceding parameter.
 *
 *   - **Stage E `@WhenTrue` shape (Locked Decision row 19, slices 2–3).**
 *     The bound parameter type must be nullable. Two source-expression
 *     forms are supported:
 *       - Simple-name form `@WhenTrue("siblingField")`: the referenced
 *         constructor parameter must exist, must be declared before the
 *         bound parameter, and must be a non-nullable `Boolean`.
 *       - Dotted form `@WhenTrue("sibling.property")`: the sibling
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
                emitter.tryEmit(symbol)
                continue
            }
            val ctor = symbol.primaryConstructor ?: continue
            for (param in ctor.parameters) {
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
            validateWhenTrue(symbol, ctor.parameters)
            validateUseCodec(symbol, ctor.parameters, payloadType)
            validatePayloadTypeParameter(symbol, ctor.parameters)
            emitter.tryEmit(symbol)
        }
        return deferred
    }

    private fun validateSealedDispatcher(parent: KSClassDeclaration) {
        // @DispatchOn parents go through Stage F's value-class discriminator
        // path; Stage D's @PacketType-uniqueness rules don't model the bit-
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
     * Stage F slice 6 — `@DispatchOn` value-class discriminator dispatcher
     * (Locked Decision row TBD; doctrine entry to be appended once the
     * vector lands in PHASE_9_RESET).
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
     *     `DiscriminatorType` (slice 6 narrow — variants without the
     *     header field would need the consume + forward-via-context
     *     model, deferred until a vector requires it).
     *
     * `@PacketType.wire` is permitted (the annotation declares it)
     * but unused at emit time: the variant's header field carries
     * the full byte on encode, so a separate `wire` is redundant.
     * Slice 6 doesn't validate consistency between `value` and
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

        if (Modifier.VALUE !in discriminatorDecl.modifiers) {
            logger.error(
                "@DispatchOn($discriminatorName::class) on $parentName references a type that is " +
                    "not a `value class`. Slice 6 requires the discriminator to be a " +
                    "`@JvmInline value class` whose primary constructor takes a single supported " +
                    "scalar (UByte/Byte/UShort/UInt/etc.).",
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
                    "parameter type is `$displayed`, but slice 6 limits it to a numeric scalar " +
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
        if (dispatchReturn.isMarkedNullable ||
            dispatchReturn.declaration.qualifiedName?.asString() != "kotlin.Int"
        ) {
            val displayed = dispatchReturn.declaration.qualifiedName?.asString() ?: "<unresolved>"
            val nullableSuffix = if (dispatchReturn.isMarkedNullable) "?" else ""
            logger.error(
                "@DispatchValue property `${dispatchProp.simpleName.asString()}` on $discriminatorName " +
                    "must return non-nullable `Int`, but returns `$displayed$nullableSuffix`.",
                dispatchProp,
            )
            return
        }

        val seen = mutableMapOf<Int, String>()
        for (sub in parent.getSealedSubclasses()) {
            val subName = sub.qualifiedName?.asString() ?: sub.simpleName.asString()
            if (Modifier.DATA !in sub.modifiers) {
                logger.error(
                    "@DispatchOn variant $subName must be a `data class`. Slice 6 doesn't yet " +
                        "support `object` / non-data variants — those would need the consume + " +
                        "forward-via-context model.",
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
            if (rawValue !in 0..255) {
                logger.error(
                    "@PacketType($rawValue) on $subName is out of range — `value` must be in 0..255.",
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
            val variantCtor = sub.primaryConstructor
            val firstParam = variantCtor?.parameters?.firstOrNull()
            val firstParamType = firstParam?.type?.resolve()
            val firstParamQname = firstParamType?.declaration?.qualifiedName?.asString()
            if (firstParam == null || firstParamQname != discriminatorName) {
                val displayed = firstParamQname ?: "<missing>"
                logger.error(
                    "@DispatchOn variant $subName must declare its first constructor parameter " +
                        "as the discriminator type `$discriminatorName`, but it is `$displayed`. " +
                        "Slice 6 requires the variant to carry the discriminator field so its " +
                        "codec reads/writes the byte naturally; the `object` and " +
                        "discriminator-from-context shapes are deferred.",
                    sub,
                )
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
     * Stage E slices 2–3 — `@WhenTrue` shape validation (Locked Decision row 19).
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
    private fun validateWhenTrue(
        owner: KSClassDeclaration,
        parameters: List<KSValueParameter>,
    ) {
        val ownerName = owner.qualifiedName?.asString() ?: owner.simpleName.asString()
        for ((index, param) in parameters.withIndex()) {
            val ann =
                param.annotations.firstOrNull { a ->
                    a.shortName.asString() == WHEN_TRUE_SHORT &&
                        a.annotationType
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString() == WHEN_TRUE_QNAME
                } ?: continue
            val expression =
                ann.arguments
                    .firstOrNull { it.name?.asString() == "expression" }
                    ?.value as? String ?: continue

            val fieldName = param.name?.asString() ?: "<unknown>"
            val type = param.type.resolve()
            if (!type.isMarkedNullable) {
                logger.error(
                    "@WhenTrue(\"$expression\") on $ownerName.$fieldName requires the field type " +
                        "to be nullable (e.g., `Int?` not `Int`). When the predicate is false, the " +
                        "decoder needs to assign `null` to the slot — that is the only way to " +
                        "represent absence uniformly across types (Locked Decision row 19).",
                    param,
                )
                continue
            }

            val parts = expression.split('.')
            if (parts.size > 2) {
                logger.error(
                    "@WhenTrue(\"$expression\") on $ownerName.$fieldName uses a deeper-than-one-level " +
                        "path. Locked Decision row 19 limits the dotted form to `<sibling>.<property>` " +
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
                    "@WhenTrue(\"$expression\") on $ownerName.$fieldName references " +
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
                    "@WhenTrue(\"$expression\") on $ownerName.$fieldName references " +
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
                        "@WhenTrue(\"$expression\") on $ownerName.$fieldName requires source " +
                            "`$ownerName.$siblingName` to be a non-nullable `Boolean`, but it is " +
                            "`$displayed$nullableSuffix`. Locked Decision row 19 limits the simple " +
                            "expression form to a sibling `Boolean` field.",
                        param,
                    )
                    continue
                }
            } else {
                val siblingDecl = sourceType.declaration as? KSClassDeclaration
                if (siblingDecl == null || Modifier.VALUE !in siblingDecl.modifiers) {
                    val displayed =
                        sourceType.declaration.qualifiedName?.asString() ?: "<unresolved>"
                    val nullableSuffix = if (sourceType.isMarkedNullable) "?" else ""
                    logger.error(
                        "@WhenTrue(\"$expression\") on $ownerName.$fieldName uses a dotted source " +
                            "but `$ownerName.$siblingName` resolves to `$displayed$nullableSuffix`, which " +
                            "is not a `value class`. Locked Decision row 19 limits the dotted form to " +
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
                        "@WhenTrue(\"$expression\") on $ownerName.$fieldName references property " +
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
     * Slice 7a — `@LengthFrom` on `List<T>` requires `T` to be a
     * `@ProtocolMessage data class`. Returns true when `listType`'s
     * single type argument resolves to such a declaration. Other
     * element shapes (scalar, value class, non-data class) are
     * deferred — slice 7b's `@RemainingBytes` is the path for
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
            // cover Payload slots (Stage H), so forbidding the adjacent shape
            // today would leave those fields with no migration target. R1
            // expands to cover this case once @LengthPrefixed widens.
            val boundFieldType = param.type.resolve()
            if (payloadType.isAssignableFrom(boundFieldType)) continue

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
     * Stage E slice 4 — `@LengthFrom` shape validation (Locked Decision row 18).
     *
     * Independent from R1 (adjacent-`@LengthFrom` migration suggestion):
     *   - Bound field type must be `String` — Stage E's field-type
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
            if (!isStringType && !isListOfProtocolMessage) {
                val displayed = typeQname ?: "<unresolved>"
                val nullableSuffix = if (type.isMarkedNullable) "?" else ""
                logger.error(
                    "@LengthFrom(\"$referenced\") on $ownerName.$fieldName requires the bound " +
                        "field to be either a non-nullable `String` or a non-nullable " +
                        "`List<T>` where `T` is a `@ProtocolMessage data class`, but it is " +
                        "`$displayed$nullableSuffix`. `ByteArray`, nested `@ProtocolMessage`, " +
                        "and `@Payload` slots are deferred to later stages.",
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
                            "is `$displayed$nullableSuffix`. Locked Decision row 18 limits the simple " +
                            "expression form to a numeric scalar sibling.",
                        param,
                    )
                    continue
                }
            } else {
                // Dotted form (slice 9): sibling must be a value class with a
                // single supported-scalar inner; property must be a non-extension
                // `val` returning non-nullable `Int`.
                val siblingDecl = sourceType.declaration as? KSClassDeclaration
                if (siblingDecl == null || Modifier.VALUE !in siblingDecl.modifiers) {
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
     *   - Stage H slice 10a — `@RemainingBytes @UseCodec(C::class) val: P`
     *     where `P` extends `com.ditchoom.buffer.codec.Payload` and `C` is
     *     a Kotlin `object` implementing `Codec<P>`.
     *   - Phase I.1 — bare `@UseCodec(C::class) val: <scalar>` (no framing
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
     *     (slice 10a's Payload path requires the pair).
     *   - `@RemainingBytes @UseCodec` on a non-Payload field (slice 10a
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
        // Stage H slice 10b — when the data class declares a
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
                        bounds[0].resolve().declaration.qualifiedName?.asString() == PAYLOAD_QNAME
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
                // Slice 10b: constructor-injected codec resolves the
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
            val hasOtherFraming =
                param.annotations.any { ann ->
                    val n = ann.shortName.asString()
                    val q =
                        ann.annotationType
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString()
                    (n == LENGTH_FROM_SHORT && q == LENGTH_FROM_QNAME) ||
                        n == "LengthPrefixed"
                }
            if (hasOtherFraming) {
                logger.error(
                    "@UseCodec on $ownerName.$fieldName composed with `@LengthFrom` or " +
                        "`@LengthPrefixed` is not yet supported. Currently emitted compositions " +
                        "are `@RemainingBytes @UseCodec val: P` (P : Payload) and bare " +
                        "`@UseCodec val: <scalar>`. The `@LengthFrom @UseCodec` and " +
                        "`@LengthPrefixed @UseCodec` shapes are deferred to a later slice.",
                    param,
                )
                continue
            }
            if (!hasRemainingBytes && isPayloadField) {
                logger.error(
                    "@UseCodec on $ownerName.$fieldName must be paired with `@RemainingBytes` " +
                        "when the field's type extends `com.ditchoom.buffer.codec.Payload`. The " +
                        "current `@UseCodec` shapes are `@RemainingBytes @UseCodec val: P` " +
                        "(P : Payload, slice 10a) and bare `@UseCodec val: <scalar>` (Phase I.1).",
                    param,
                )
                continue
            }
            if (hasRemainingBytes && !isPayloadField) {
                val displayed = fieldType.declaration.qualifiedName?.asString() ?: "<unresolved>"
                logger.error(
                    "@RemainingBytes @UseCodec on $ownerName.$fieldName requires the bound " +
                        "field's type to extend `com.ditchoom.buffer.codec.Payload`, but it " +
                        "is `$displayed`. Slice 10a's typed-payload path is the only " +
                        "`@UseCodec` composition wired up — wrap the value in a `Payload`-" +
                        "tagged type or wait for a later slice that lifts this restriction.",
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
     * Stage H slice 10b — `<P : Payload>` type-parameter shape
     * validation.
     *
     * A `@ProtocolMessage` data class with a `<P : Payload>` type
     * parameter must use the parameter as the type of at least one
     * `@RemainingBytes`-annotated field. Without that field, the
     * type parameter is unused — KSP would infer it as `Nothing` at
     * call sites, and the constructor-injected `Codec<P>` parameter
     * the emitter generates would have nothing to drive.
     *
     * Slice 10b narrow: at most one type parameter per data class,
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
                    "slice 10b supports at most one (`<P : Payload>`). Multiple type parameters " +
                    "are deferred — open a follow-up vector when a real protocol needs them.",
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
                    "upper bound (`<$tpName : Payload>`), but has ${bounds.size}. Slice 10b " +
                    "supports a single `Payload` bound only.",
                owner,
            )
            return
        }
        val boundQname = bounds[0].resolve().declaration.qualifiedName?.asString()
        if (boundQname != PAYLOAD_QNAME) {
            logger.error(
                "@ProtocolMessage `$ownerName` type parameter `$tpName` must be bounded by " +
                    "`com.ditchoom.buffer.codec.Payload`, but is bounded by " +
                    "`${boundQname ?: "<unresolved>"}`. Slice 10b's generic-bounded payload slot " +
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
                    "`@RemainingBytes val payload: $tpName` (slice 10b shape) or drop the " +
                    "type parameter and use `@UseCodec(...)` against a concrete `Payload` " +
                    "subtype (slice 10a shape).",
                owner,
            )
        }
    }

    /**
     * Stage H slice 10a — does `codecDecl` implement
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
        // Walk the full supertype chain so a `BoundingLengthCodec<T>` impl
        // (which transitively implements `Codec<T>`) is also recognized.
        // Both `Codec<T>` and `BoundingLengthCodec<T>` are accepted: KSP's
        // `getAllSuperTypes()` doesn't substitute the type variable from the
        // intermediate interface declaration, so the transitive `Codec<T>`
        // entry still carries the unsubstituted T. The concrete type arg is
        // present on whichever interface the codec object directly extends.
        for (st in codecDecl.getAllSuperTypes()) {
            if (st.isError) continue
            val q = st.declaration.qualifiedName?.asString()
            if (q != CODEC_QNAME && q != BOUNDING_LENGTH_CODEC_QNAME) continue
            val arg = st.arguments.firstOrNull()?.type?.resolve() ?: continue
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
        if (payloadType.isAssignableFrom(type)) return

        val qualified = type.declaration.qualifiedName?.asString()
        if (qualified != null && qualified in FORBIDDEN_TYPES) {
            val ownerName = owner.qualifiedName?.asString() ?: owner.simpleName.asString()
            val fieldName = param.name?.asString() ?: "<unknown>"
            logger.error(
                "@ProtocolMessage field $ownerName.$fieldName has raw-bytes type $qualified. " +
                    "Section 8 of PHASE_10_DESIGN_NOTES.md forbids ReadBuffer / WriteBuffer / " +
                    "PlatformBuffer / ByteArray / ByteBuffer in @ProtocolMessage data classes. " +
                    "Wrap inside a `com.ditchoom.buffer.codec.Payload`-tagged type and copy " +
                    "explicitly inside the consumer's decoder lambda, or model the field with " +
                    "a typed shape and (if needed) `@UseCodec`.",
                param,
            )
            return
        }

        // Cycle guard for self-referential generic types.
        val fingerprint = type.toFingerprint()
        if (!visited.add(fingerprint)) return

        val decl = type.declaration as? KSClassDeclaration
        if (decl != null && Modifier.VALUE in decl.modifiers) {
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

        for (arg in type.arguments) {
            val argType = arg.type?.resolve() ?: continue
            walkType(argType, owner, param, payloadType, depth + 1, visited)
        }
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
        private const val WIRE_BYTES_QNAME = "com.ditchoom.buffer.codec.annotations.WireBytes"
        private const val WIRE_BYTES_SHORT = "WireBytes"
        private const val PACKET_TYPE_QNAME = "com.ditchoom.buffer.codec.annotations.PacketType"
        private const val PACKET_TYPE_SHORT = "PacketType"
        private const val DISPATCH_ON_QNAME = "com.ditchoom.buffer.codec.annotations.DispatchOn"
        private const val DISPATCH_ON_SHORT = "DispatchOn"
        private const val DISPATCH_VALUE_QNAME = "com.ditchoom.buffer.codec.annotations.DispatchValue"
        private const val DISPATCH_VALUE_SHORT = "DispatchValue"
        private const val WHEN_TRUE_QNAME = "com.ditchoom.buffer.codec.annotations.WhenTrue"
        private const val WHEN_TRUE_SHORT = "WhenTrue"
        private const val USE_CODEC_QNAME = "com.ditchoom.buffer.codec.annotations.UseCodec"
        private const val USE_CODEC_SHORT = "UseCodec"
        private const val REMAINING_BYTES_QNAME = "com.ditchoom.buffer.codec.annotations.RemainingBytes"
        private const val REMAINING_BYTES_SHORT = "RemainingBytes"
        private const val CODEC_QNAME = "com.ditchoom.buffer.codec.Codec"
        private const val BOUNDING_LENGTH_CODEC_QNAME = "com.ditchoom.buffer.codec.BoundingLengthCodec"
        private const val BOOLEAN_QNAME = "kotlin.Boolean"
        private const val STRING_QNAME = "kotlin.String"
        private const val LIST_QNAME = "kotlin.collections.List"
        private const val MAX_DEPTH = 16

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
                "kotlin.ByteArray",
                "java.nio.ByteBuffer",
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
