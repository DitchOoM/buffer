package com.ditchoom.buffer.codec.processor.planbuilder

import com.ditchoom.buffer.codec.processor.discovery.RawAnnotation
import com.ditchoom.buffer.codec.processor.discovery.RawCtorParameter
import com.ditchoom.buffer.codec.processor.discovery.RawSymbol
import com.ditchoom.buffer.codec.processor.discovery.RawTypeRef
import com.ditchoom.buffer.codec.processor.ir.Conditionality
import com.ditchoom.buffer.codec.processor.ir.ElementCodecRef
import com.ditchoom.buffer.codec.processor.ir.Endianness
import com.ditchoom.buffer.codec.processor.ir.FieldPlan
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.ditchoom.buffer.codec.processor.ir.LengthEncoding
import com.ditchoom.buffer.codec.processor.ir.LengthSource
import com.ditchoom.buffer.codec.processor.ir.PrimitiveKind
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.planbuilder.Annotations.classRefArg
import com.ditchoom.buffer.codec.processor.planbuilder.Annotations.enumArg
import com.ditchoom.buffer.codec.processor.planbuilder.Annotations.find
import com.ditchoom.buffer.codec.processor.planbuilder.Annotations.has
import com.ditchoom.buffer.codec.processor.planbuilder.Annotations.intArg
import com.ditchoom.buffer.codec.processor.planbuilder.Annotations.stringArg
import com.squareup.kotlinpoet.ClassName

/**
 * Per-field IR construction.
 *
 * Public entry: [build] takes a single constructor parameter from a `RawSymbol.DataLike`
 * plus the fields decoded so far + supporting program scope, and returns the field's
 * [FieldPlan] or an accumulated set of [KspError]s.
 *
 * The builder owns every single-field validation rule listed in the validator-rules table
 * for PhaseB:
 *  - `@LengthPrefixed` + `@LengthFrom` mutex
 *  - `@LengthPrefixed` / `@LengthFrom` / `@RemainingBytes` / `@VariableByteInteger` mutex matrix
 *  - `@LengthFrom("name")` field exists, precedes, is numeric (single-symbol scope)
 *  - `@RemainingBytes` only at variable-size tail
 *  - `@DiscriminatorField` field type matches parent's `@DispatchOn.discriminator`
 *  - `@When` parses + condition records
 *  - `@WireBytes` / `@WireOrder` width + order rules
 */
internal class FieldStrategyBuilder(
    private val ownerFqn: String,
    private val classWireOrder: Endianness,
    private val precedingFields: List<FieldPlan>,
    private val totalFieldCount: Int,
    private val isLastField: Boolean,
    private val payloadTypeParams: Set<String>,
    private val parentDispatchType: TypeFqn?,
    private val protocolMessageScope: Set<String>,
) {
    fun build(param: RawCtorParameter): Either<Nel<KspError>, FieldPlan> {
        val errors = mutableListOf<KspError>()
        val site = param.qualifiedSiteFqn(ownerFqn)
        val ann = param.annotations
        val hasLengthPrefixed = ann.has(AnnotationFqns.LengthPrefixed)
        val hasLengthFrom = ann.has(AnnotationFqns.LengthFrom)
        val hasRemainingBytes = ann.has(AnnotationFqns.RemainingBytes)
        val hasVbi = ann.has(AnnotationFqns.VariableByteInteger)
        val hasUseCodec = ann.has(AnnotationFqns.UseCodec)
        val hasDiscriminatorField = ann.has(AnnotationFqns.DiscriminatorField)
        val whenAnnotations = ann.filter { it.fqn == AnnotationFqns.When_ }
        val whenTrueAnnotations = ann.filter { it.fqn == AnnotationFqns.WhenTrue }
        val whenRemainingAnnotations = ann.filter { it.fqn == AnnotationFqns.WhenRemaining }

        // Mutex: at most one of the length-shape annotations
        val lengthShapeNames = mutableListOf<String>()
        if (hasLengthPrefixed) lengthShapeNames += "@LengthPrefixed"
        if (hasLengthFrom) lengthShapeNames += "@LengthFrom"
        if (hasRemainingBytes) lengthShapeNames += "@RemainingBytes"
        if (hasVbi) lengthShapeNames += "@VariableByteInteger"
        if (lengthShapeNames.size > 1) {
            errors +=
                KspError(
                    message =
                        "Field '${param.name}' on '$ownerFqn' carries mutually exclusive annotations " +
                            "${lengthShapeNames.joinToString(", ")}. Pick exactly one length / framing strategy " +
                            "per field.",
                    sourceFqn = site,
                )
        }

        // Mutex: at most one conditionality annotation across @When / @WhenTrue / @WhenRemaining
        val condCount = whenAnnotations.size + whenTrueAnnotations.size + whenRemainingAnnotations.size
        if (condCount > 1) {
            val names =
                buildList {
                    if (whenAnnotations.isNotEmpty()) add("@When")
                    if (whenTrueAnnotations.isNotEmpty()) add("@WhenTrue")
                    if (whenRemainingAnnotations.isNotEmpty()) add("@WhenRemaining")
                }
            errors +=
                KspError(
                    message =
                        "Field '${param.name}' on '$ownerFqn' carries multiple conditional annotations " +
                            "(${names.joinToString(", ")}). Use exactly one `@When` per field.",
                    sourceFqn = site,
                )
        }

        // VBI cannot combine with explicit width / order overrides
        if (hasVbi) {
            if (ann.has(AnnotationFqns.WireBytes)) {
                errors +=
                    KspError(
                        message =
                            "Field '${param.name}' on '$ownerFqn' has @VariableByteInteger and @WireBytes. " +
                                "VBI is self-delimiting; remove @WireBytes.",
                        sourceFqn = site,
                    )
            }
            if (ann.has(AnnotationFqns.WireOrder)) {
                errors +=
                    KspError(
                        message =
                            "Field '${param.name}' on '$ownerFqn' has @VariableByteInteger and @WireOrder. " +
                                "VBI bytes are not subject to byte-order swap; remove @WireOrder.",
                        sourceFqn = site,
                    )
            }
        }

        // @PacketType / @PacketTypeRange are class-level, not field-level — guard against misuse
        if (ann.has(AnnotationFqns.PacketType) || ann.has(AnnotationFqns.PacketTypeRange)) {
            errors +=
                KspError(
                    message =
                        "Field '${param.name}' on '$ownerFqn' carries @PacketType or @PacketTypeRange. " +
                            "These annotations target sealed-variant classes, not fields.",
                    sourceFqn = site,
                )
        }

        val conditionality =
            buildConditionality(
                site = site,
                paramName = param.name,
                whenAnnotations = whenAnnotations,
                whenTrueAnnotations = whenTrueAnnotations,
                whenRemainingAnnotations = whenRemainingAnnotations,
                errors = errors,
            )

        if (param.typeRef.isTypeParameter) {
            return buildPayloadSlot(param, conditionality, errors, site)
        }

        // Slice 5a: auto-detect DiscriminatorField. When the enclosing class is a
        // sealed-root variant whose parent declares `@DispatchOn(D::class)`, any
        // constructor parameter declared with type `D` is treated as
        // `DiscriminatorOwned` even without an explicit `@DiscriminatorField`
        // annotation. Mirrors legacy `FieldAnalyzer` lines 922-965 — variants like
        // `MqttControlPacketV5.Publish(val header: MqttFixedHeader, ...)` get the
        // discriminator from decode context rather than reading wire bytes.
        val parentDispFqn = parentDispatchType?.canonical
        val isAutoDiscriminator =
            !hasDiscriminatorField &&
                parentDispFqn != null &&
                param.typeRef.fqn == parentDispFqn
        if (hasDiscriminatorField || isAutoDiscriminator) {
            return buildDiscriminatorOwned(param, conditionality, errors, site)
        }

        if (hasRemainingBytes && !isLastField) {
            errors +=
                KspError(
                    message =
                        "@RemainingBytes field '${param.name}' on '$ownerFqn' is not the last constructor parameter. " +
                            "@RemainingBytes consumes everything left in the buffer and must appear at the tail.",
                    sourceFqn = site,
                )
        }

        if (hasUseCodec) {
            return buildExternal(param, conditionality, errors, site)
        }

        // Length-source resolution — must run before classification so the strategy
        // builders can see the resolved LengthSource (or None for fixed-width primitives).
        val lengthSourceOrError = resolveLengthSource(param, errors, site)

        if (hasVbi) {
            return buildVbi(param, conditionality, errors, site)
        }

        if (param.typeRef.fqn == "kotlin.String") {
            return buildString(param, conditionality, lengthSourceOrError, errors, site)
        }

        if (param.typeRef.fqn in COLLECTION_FQNS) {
            return buildCollection(param, conditionality, lengthSourceOrError, errors, site)
        }

        val primitive = PrimitiveTypes.classify(param.typeRef)
        if (primitive != null) {
            return buildPrimitive(param, conditionality, primitive, errors, site)
        }

        if (param.typeRef.fqn in protocolMessageScope) {
            return buildNestedMessage(param, conditionality, lengthSourceOrError, errors, site)
        }

        errors +=
            KspError(
                message =
                    "Field '${param.name}: ${param.typeRef.fqn.ifBlank { param.typeRef.name }}' on '$ownerFqn' " +
                        "is not a recognized primitive, collection, String, type-parameter, " +
                        "or @ProtocolMessage type. Mark it with @UseCodec(SomeCodec::class) to delegate " +
                        "encoding/decoding to a custom codec.",
                sourceFqn = site,
            )
        return errorsToLeft(errors)!!
    }

    private fun buildConditionality(
        site: String,
        paramName: String,
        whenAnnotations: List<RawAnnotation>,
        whenTrueAnnotations: List<RawAnnotation>,
        whenRemainingAnnotations: List<RawAnnotation>,
        errors: MutableList<KspError>,
    ): Conditionality {
        val whenAnn = whenAnnotations.firstOrNull() ?: whenTrueAnnotations.firstOrNull()
        if (whenAnn != null) {
            val expression = whenAnn.stringArg("expression")
            if (expression == null) {
                errors +=
                    KspError(
                        message = "@When on field '$paramName' is missing the required `expression` argument.",
                        sourceFqn = site,
                    )
                return Conditionality.Always
            }
            return when (val parsed = BooleanExpressionParser.parse(expression)) {
                is BooleanParseResult.Success -> Conditionality.WhenExpr(parsed.expression)
                is BooleanParseResult.Failure -> {
                    errors +=
                        KspError(
                            message = "@When expression for field '$paramName': ${parsed.message}",
                            sourceFqn = site,
                        )
                    Conditionality.Always
                }
            }
        }
        val whenRem = whenRemainingAnnotations.firstOrNull()
        if (whenRem != null) {
            val minBytes = whenRem.intArg("minBytes")
            if (minBytes == null) {
                errors +=
                    KspError(
                        message =
                            "@WhenRemaining on field '$paramName' is missing the required `minBytes` argument.",
                        sourceFqn = site,
                    )
                return Conditionality.Always
            }
            if (minBytes < 0) {
                errors +=
                    KspError(
                        message = "@WhenRemaining($minBytes) on field '$paramName' must be non-negative.",
                        sourceFqn = site,
                    )
                return Conditionality.Always
            }
            return Conditionality.WhenExpr(
                com.ditchoom.buffer.codec.processor.ir.BooleanExpression
                    .RemainingGte(minBytes),
            )
        }
        return Conditionality.Always
    }

    private fun buildPayloadSlot(
        param: RawCtorParameter,
        conditionality: Conditionality,
        errors: MutableList<KspError>,
        site: String,
    ): Either<Nel<KspError>, FieldPlan> {
        val tpName = param.typeRef.name
        if (tpName !in payloadTypeParams) {
            errors +=
                KspError(
                    message =
                        "Field '${param.name}: $tpName' on '$ownerFqn' references type parameter '$tpName' that " +
                            "is not marked @Payload. Add @Payload to the class type parameter or change the field's type.",
                    sourceFqn = site,
                )
        }
        val length =
            resolveLengthSource(param, errors, site) ?: LengthSource.Remaining(trailingBytes = 0)
        errorsToLeft(errors)?.let { return it }
        return FieldPlan(
            name = param.name,
            type = TypeFqn(if (param.typeRef.fqn.isNotBlank()) param.typeRef.fqn else "kotlin.$tpName"),
            strategy = FieldStrategy.PayloadSlot(typeParam = tpName, length = length),
            conditionality = conditionality,
        ).right()
    }

    private fun buildDiscriminatorOwned(
        param: RawCtorParameter,
        conditionality: Conditionality,
        errors: MutableList<KspError>,
        site: String,
    ): Either<Nel<KspError>, FieldPlan> {
        if (parentDispatchType == null) {
            errors +=
                KspError(
                    message =
                        "Field '${param.name}' on '$ownerFqn' is annotated @DiscriminatorField but the enclosing " +
                            "class is not a variant of a sealed root with @DispatchOn. " +
                            "@DiscriminatorField only applies inside @PacketType / @PacketTypeRange variants.",
                    sourceFqn = site,
                )
        } else if (param.typeRef.fqn != parentDispatchType.canonical) {
            errors +=
                KspError(
                    message =
                        "@DiscriminatorField '${param.name}: ${param.typeRef.fqn}' on '$ownerFqn' must match " +
                            "the parent's @DispatchOn(${parentDispatchType.canonical}::class) discriminator type.",
                    sourceFqn = site,
                )
        }
        errorsToLeft(errors)?.let { return it }
        return FieldPlan(
            name = param.name,
            type = TypeFqn(param.typeRef.fqn.ifBlank { "kotlin.${param.typeRef.name}" }),
            strategy =
                FieldStrategy.DiscriminatorOwned(
                    parentDispatchOn = parentDispatchType ?: TypeFqn(param.typeRef.fqn),
                ),
            conditionality = conditionality,
        ).right()
    }

    private fun buildExternal(
        param: RawCtorParameter,
        conditionality: Conditionality,
        errors: MutableList<KspError>,
        site: String,
    ): Either<Nel<KspError>, FieldPlan> {
        val useCodecAnn = param.annotations.find(AnnotationFqns.UseCodec)
        val classRef = useCodecAnn?.classRefArg("codec")
        if (classRef == null) {
            errors +=
                KspError(
                    message = "@UseCodec on field '${param.name}' is missing the required `codec` argument.",
                    sourceFqn = site,
                )
            return errorsToLeft(errors)!!
        }
        if (!classRef.resolved || classRef.fqn.isBlank()) {
            errors +=
                KspError(
                    message =
                        "@UseCodec(...) on field '${param.name}' references a class that did not resolve. " +
                            "Check imports / classpath: '${classRef.fqn.ifBlank { "<unresolved>" }}'.",
                    sourceFqn = site,
                )
            return errorsToLeft(errors)!!
        }
        // resolve length-source if present (composes with @UseCodec)
        // Cap 3: thread the resolved length-source so the emitter wraps the
        // external codec call with length-framed read/write.
        val length = resolveLengthSource(param, errors, site)
        errorsToLeft(errors)?.let { return it }
        val codecClassName = classRefToClassName(classRef.fqn)
        return FieldPlan(
            name = param.name,
            type = TypeFqn(param.typeRef.fqn.ifBlank { param.typeRef.name }),
            strategy =
                FieldStrategy.External(
                    codec = codecClassName,
                    contextualOverloads = false,
                    length = length,
                ),
            conditionality = conditionality,
        ).right()
    }

    private fun buildVbi(
        param: RawCtorParameter,
        conditionality: Conditionality,
        errors: MutableList<KspError>,
        site: String,
    ): Either<Nel<KspError>, FieldPlan> {
        if (param.typeRef.fqn != "kotlin.Int" && param.typeRef.fqn != "kotlin.UInt") {
            errors +=
                KspError(
                    message =
                        "@VariableByteInteger on '${param.name}' requires Int or UInt; got '${param.typeRef.fqn}'.",
                    sourceFqn = site,
                )
        }
        errorsToLeft(errors)?.let { return it }
        return FieldPlan(
            name = param.name,
            type = TypeFqn(param.typeRef.fqn),
            strategy = FieldStrategy.VarInt(maxBytes = 4),
            conditionality = conditionality,
        ).right()
    }

    private fun buildString(
        param: RawCtorParameter,
        conditionality: Conditionality,
        lengthSourceOrError: LengthSource?,
        errors: MutableList<KspError>,
        site: String,
    ): Either<Nel<KspError>, FieldPlan> {
        if (lengthSourceOrError == null && errors.none { it.sourceFqn == site }) {
            errors +=
                KspError(
                    message =
                        "Field '${param.name}: String' on '$ownerFqn' has no length source. Add one of " +
                            "@LengthPrefixed, @LengthFrom(\"otherField\"), or @RemainingBytes.",
                    sourceFqn = site,
                )
        }
        errorsToLeft(errors)?.let { return it }
        return FieldPlan(
            name = param.name,
            type = TypeFqn("kotlin.String"),
            strategy = FieldStrategy.StringField(length = lengthSourceOrError ?: LengthSource.Remaining(0)),
            conditionality = conditionality,
        ).right()
    }

    private fun buildCollection(
        param: RawCtorParameter,
        conditionality: Conditionality,
        lengthSourceOrError: LengthSource?,
        errors: MutableList<KspError>,
        site: String,
    ): Either<Nel<KspError>, FieldPlan> {
        val elementType = param.typeRef.typeArguments.firstOrNull()
        if (elementType == null || !elementType.resolved || elementType.fqn.isBlank()) {
            errors +=
                KspError(
                    message =
                        "Field '${param.name}: ${param.typeRef.fqn}<?>' on '$ownerFqn' has an unresolved element type. " +
                            "Annotate with @UseCodec to provide an element codec or fix the import.",
                    sourceFqn = site,
                )
            return errorsToLeft(errors)!!
        }
        if (lengthSourceOrError == null && errors.none { it.sourceFqn == site }) {
            errors +=
                KspError(
                    message =
                        "Field '${param.name}' on '$ownerFqn' is a Collection without a length source. " +
                            "Add @LengthPrefixed, @LengthFrom, or @RemainingBytes.",
                    sourceFqn = site,
                )
        }
        errorsToLeft(errors)?.let { return it }
        val elementCodec =
            ClassName(
                packageNameOf(elementType.fqn),
                simpleNameOf(elementType.fqn) + "Codec",
            )
        return FieldPlan(
            name = param.name,
            type = TypeFqn(param.typeRef.fqn),
            strategy =
                FieldStrategy.Collection_(
                    elementCodec = ElementCodecRef(codec = elementCodec, elementType = TypeFqn(elementType.fqn)),
                    length = lengthSourceOrError ?: LengthSource.Remaining(0),
                ),
            conditionality = conditionality,
        ).right()
    }

    private fun buildPrimitive(
        param: RawCtorParameter,
        conditionality: Conditionality,
        primitive: PrimitiveKind,
        errors: MutableList<KspError>,
        site: String,
    ): Either<Nel<KspError>, FieldPlan> {
        val wireBytesAnn = param.annotations.find(AnnotationFqns.WireBytes)
        val natural = PrimitiveTypes.naturalWireBytes(primitive)
        val wireBytes =
            if (wireBytesAnn == null) {
                natural
            } else {
                val v = wireBytesAnn.intArg("value")
                when {
                    v == null -> {
                        errors +=
                            KspError(
                                message = "@WireBytes on '${param.name}' is missing required `value` argument.",
                                sourceFqn = site,
                            )
                        natural
                    }
                    v !in 1..8 -> {
                        errors +=
                            KspError(
                                message =
                                    "@WireBytes($v) on '${param.name}' must be in 1..8.",
                                sourceFqn = site,
                            )
                        natural
                    }
                    primitive == PrimitiveKind.Bool ||
                        primitive == PrimitiveKind.Float ||
                        primitive == PrimitiveKind.Double -> {
                        errors +=
                            KspError(
                                message =
                                    "@WireBytes is not allowed on field '${param.name}: $primitive'. " +
                                        "Bool/Float/Double have fixed wire widths.",
                                sourceFqn = site,
                            )
                        natural
                    }
                    v > natural -> {
                        errors +=
                            KspError(
                                message =
                                    "@WireBytes($v) on '${param.name}: $primitive' exceeds the type's natural " +
                                        "width ($natural bytes).",
                                sourceFqn = site,
                            )
                        natural
                    }
                    else -> v
                }
            }
        val orderAnn = param.annotations.find(AnnotationFqns.WireOrder)
        val order =
            if (orderAnn == null) {
                classWireOrder
            } else {
                val name = orderAnn.enumArg("order")?.name
                EndiannessMapping.fromAnnotationEnum(name) ?: classWireOrder
            }
        if (wireBytes == 1 && orderAnn != null) {
            errors +=
                KspError(
                    message =
                        "@WireOrder on single-byte field '${param.name}' has no effect — byte order applies " +
                            "only to multi-byte fields.",
                    sourceFqn = site,
                )
        }
        errorsToLeft(errors)?.let { return it }
        return FieldPlan(
            name = param.name,
            type = TypeFqn(param.typeRef.fqn),
            strategy = FieldStrategy.Primitive(kind = primitive, wireBytes = wireBytes, order = order),
            conditionality = conditionality,
        ).right()
    }

    private fun buildNestedMessage(
        param: RawCtorParameter,
        conditionality: Conditionality,
        lengthSourceOrError: LengthSource?,
        errors: MutableList<KspError>,
        @Suppress("UNUSED_PARAMETER") site: String,
    ): Either<Nel<KspError>, FieldPlan> {
        errorsToLeft(errors)?.let { return it }
        val codec =
            ClassName(
                packageNameOf(param.typeRef.fqn),
                simpleNameOf(param.typeRef.fqn) + "Codec",
            )
        return FieldPlan(
            name = param.name,
            type = TypeFqn(param.typeRef.fqn),
            // Cap 3: thread @LengthPrefixed / @LengthFrom / @RemainingBytes resolution through
            // to the emitter so it can emit length-framed read/write around the nested decode.
            strategy = FieldStrategy.NestedMessage(codec = codec, length = lengthSourceOrError),
            conditionality = conditionality,
        ).right()
    }

    private fun resolveLengthSource(
        param: RawCtorParameter,
        errors: MutableList<KspError>,
        site: String,
    ): LengthSource? {
        val lengthPrefixed = param.annotations.find(AnnotationFqns.LengthPrefixed)
        if (lengthPrefixed != null) {
            val prefixEnum = lengthPrefixed.enumArg("prefix")?.name
            val encoding =
                when (prefixEnum) {
                    null, "Short" -> LengthEncoding.Short
                    "Byte" -> LengthEncoding.Byte
                    "Int" -> LengthEncoding.Int
                    "Varint" -> LengthEncoding.Varint
                    else -> {
                        errors +=
                            KspError(
                                message =
                                    "@LengthPrefixed on '${param.name}' has unknown prefix '$prefixEnum'.",
                                sourceFqn = site,
                            )
                        LengthEncoding.Short
                    }
                }
            val maxBytesArg = lengthPrefixed.intArg("maxBytes") ?: 0
            val effectiveMax =
                when (encoding) {
                    LengthEncoding.Varint ->
                        when {
                            maxBytesArg == 0 -> 4
                            maxBytesArg in 1..4 -> maxBytesArg
                            else -> {
                                errors +=
                                    KspError(
                                        message =
                                            "@LengthPrefixed(Varint, maxBytes = $maxBytesArg) on '${param.name}' " +
                                                "must be in 1..4.",
                                        sourceFqn = site,
                                    )
                                4
                            }
                        }
                    else -> 0
                }
            return LengthSource.Inline(encoding = encoding, maxBytes = effectiveMax)
        }
        val lengthFrom = param.annotations.find(AnnotationFqns.LengthFrom)
        if (lengthFrom != null) {
            val refName = lengthFrom.stringArg("field")
            if (refName.isNullOrBlank()) {
                errors +=
                    KspError(
                        message =
                            "@LengthFrom on '${param.name}' is missing the required `field` argument.",
                        sourceFqn = site,
                    )
                return null
            }
            val match = precedingFields.firstOrNull { it.name == refName }
            if (match == null) {
                val available = precedingFields.joinToString(", ") { it.name }
                errors +=
                    KspError(
                        message =
                            "@LengthFrom(\"$refName\") on field '${param.name}' references unknown field. " +
                                "Available preceding fields: [${available.ifBlank { "<none>" }}].",
                        sourceFqn = site,
                    )
                return null
            }
            val kind =
                (match.strategy as? FieldStrategy.Primitive)?.kind
                    ?: (match.strategy as? FieldStrategy.VarInt)?.let { PrimitiveKind.Int }
            if (kind == null || !PrimitiveTypes.isNumeric(kind)) {
                errors +=
                    KspError(
                        message =
                            "@LengthFrom(\"$refName\") on field '${param.name}' references field '$refName' " +
                                "which is not a numeric primitive.",
                        sourceFqn = site,
                    )
                return null
            }
            return LengthSource.FromField(name = refName, kind = kind)
        }
        if (param.annotations.has(AnnotationFqns.RemainingBytes)) {
            return LengthSource.Remaining(trailingBytes = 0)
        }
        return null
    }

    private fun classRefToClassName(fqn: String): ClassName = ClassName(packageNameOf(fqn), simpleNameOf(fqn))

    companion object {
        private val COLLECTION_FQNS =
            setOf(
                "kotlin.collections.List",
                "kotlin.collections.Collection",
                "kotlin.collections.Set",
                "kotlin.collections.MutableList",
                "kotlin.collections.MutableCollection",
                "kotlin.collections.MutableSet",
            )

        fun packageNameOf(fqn: String): String {
            val idx = fqn.lastIndexOf('.')
            return if (idx <= 0) "" else fqn.substring(0, idx)
        }

        fun simpleNameOf(fqn: String): String {
            val idx = fqn.lastIndexOf('.')
            return if (idx < 0) fqn else fqn.substring(idx + 1)
        }
    }

    private fun errorsToLeft(errors: MutableList<KspError>): Either.Left<Nel<KspError>>? =
        if (errors.isEmpty()) {
            null
        } else {
            Either.Left(Nel.fromList(errors.toList()))
        }
}

/** Mirror of [FieldStrategyBuilder.packageNameOf] for use outside the builder. */
internal fun packageNameOf(fqn: String): String = FieldStrategyBuilder.packageNameOf(fqn)

/** Mirror of [FieldStrategyBuilder.simpleNameOf]. */
internal fun simpleNameOf(fqn: String): String = FieldStrategyBuilder.simpleNameOf(fqn)

/** Returns the FQN of a SealedRoot whose @DispatchOn references this discriminator type, if any. */
internal fun RawSymbol.dispatchTypeFqn(): String? {
    val ann = annotations.find { it.fqn == AnnotationFqns.DispatchOn } ?: return null
    val ref = ann.arguments["type"] as? com.ditchoom.buffer.codec.processor.discovery.RawAnnotationValue.ClassRef
    return ref?.fqn
}

/** Checked-cast convenience: pull a typed annotation value off a [RawTypeRef] reference. */
@Suppress("unused")
internal fun RawTypeRef.asPrimitiveOrNull(): PrimitiveKind? = PrimitiveTypes.classify(this)
