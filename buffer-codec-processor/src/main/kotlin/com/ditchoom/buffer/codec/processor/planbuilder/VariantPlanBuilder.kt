package com.ditchoom.buffer.codec.processor.planbuilder

import com.ditchoom.buffer.codec.processor.discovery.RawClassMetadata
import com.ditchoom.buffer.codec.processor.discovery.RawSymbol
import com.ditchoom.buffer.codec.processor.ir.Direction
import com.ditchoom.buffer.codec.processor.ir.DiscriminatorShape
import com.ditchoom.buffer.codec.processor.ir.DispatchShape
import com.ditchoom.buffer.codec.processor.ir.Endianness
import com.ditchoom.buffer.codec.processor.ir.FieldPlan
import com.ditchoom.buffer.codec.processor.ir.PayloadFieldRef
import com.ditchoom.buffer.codec.processor.ir.PayloadTypeParam
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.ir.VariantPlan
import com.ditchoom.buffer.codec.processor.ir.WireMatch
import com.ditchoom.buffer.codec.processor.planbuilder.Annotations.find
import com.ditchoom.buffer.codec.processor.planbuilder.Annotations.has
import com.ditchoom.buffer.codec.processor.planbuilder.Annotations.intArg

/**
 * PhaseB's variant builder — turns a single sealed-child [RawSymbol] into a [VariantPlan].
 *
 * Owns these single-symbol rules from the validator-rules table:
 *  - `@PacketType` + `@PacketTypeRange` mutex on the same variant
 *  - `@PacketTypeRange` must carry `@DiscriminatorField` on the variant (selfEncodes constraint)
 *  - `@PacketTypeRange` requires a value-class discriminator (single-byte field)
 *  - `@PacketType.wire` fits the discriminator's natural unsigned range
 *  - direction reconcile per variant (delegates to [DirectionResolver])
 *  - field plan construction via [FieldStrategyBuilder]
 *
 * Cross-symbol checks (range disjointness across siblings, framer type matching, cycle
 * detection) belong to PhaseC and are intentionally absent.
 */
internal object VariantPlanBuilder {
    fun build(
        variantSymbol: RawSymbol,
        parentRoot: RawSymbol.SealedRoot,
        parentDispatch: DispatchShape,
        scope: Map<String, RawSymbol>,
        classWireOrder: Endianness,
        externalClasses: Map<String, RawClassMetadata> = emptyMap(),
    ): Either<Nel<KspError>, VariantPlan> {
        val errors = mutableListOf<KspError>()
        val packetType = variantSymbol.annotations.find(AnnotationFqns.PacketType)
        val packetRange = variantSymbol.annotations.find(AnnotationFqns.PacketTypeRange)
        if (packetType != null && packetRange != null) {
            errors +=
                KspError(
                    message =
                        "Variant '${variantSymbol.fqn}' carries both @PacketType and @PacketTypeRange. " +
                            "Pick exactly one.",
                    sourceFqn = variantSymbol.fqn,
                )
        }
        if (packetType == null && packetRange == null) {
            errors +=
                KspError(
                    message =
                        "Variant '${variantSymbol.fqn}' of sealed root '${parentRoot.fqn}' has neither " +
                            "@PacketType nor @PacketTypeRange. Annotate it so the dispatcher can match it.",
                    sourceFqn = variantSymbol.fqn,
                )
        }

        val typedDisc = (parentDispatch as? DispatchShape.TypedDiscriminator)?.disc
        val parentDispatchType =
            (typedDisc as? DiscriminatorShape.ValueClass)?.let { TypeFqn(typedDiscFqnOf(parentRoot, scope) ?: "") }
                ?: (typedDisc as? DiscriminatorShape.DataClass)?.let { TypeFqn(typedDiscFqnOf(parentRoot, scope) ?: "") }

        val wireMatch =
            buildWireMatch(
                variantSymbol = variantSymbol,
                packetType = packetType,
                packetRange = packetRange,
                typedDisc = typedDisc,
                errors = errors,
            )

        val selfEncodes = packetRange != null

        val baseDirection = resolveVariantDirection(variantSymbol, parentRoot)

        val (fields, payloadTypeParams, payloadFieldRefs, fieldErrors) =
            buildVariantFields(
                variantSymbol = variantSymbol,
                parentDispatchType = parentDispatchType,
                scope = scope,
                classWireOrder = classWireOrder,
            )
        errors += fieldErrors

        // Slice 5b: refine variant direction from field strategies. Mirrors the
        // `buildDataLike` refinement so a variant whose `@UseCodec` field references
        // a `Decoder`-only class (or whose nested `@ProtocolMessage` field is decode-only)
        // surfaces as `Direction.DecodeOnly`. Without this the sealed-root reconciler
        // sees `Bidirectional` and emits a `Codec<T>` dispatcher referencing variant
        // overloads that don't exist.
        val variantExplicit = DirectionResolver.classDirection(variantSymbol)
        val parentExplicit = DirectionResolver.classDirection(parentRoot)
        val explicitForRefine = variantExplicit ?: parentExplicit
        val direction =
            FieldDirectionInference.refine(
                fields = fields,
                explicit = explicitForRefine,
                fallback = baseDirection,
                scope = scope,
                externalClasses = externalClasses,
                ownerFqn = variantSymbol.fqn,
                errors = errors,
            )

        // @PacketTypeRange requires @DiscriminatorField on a variant constructor parameter
        if (packetRange != null) {
            val variantHasDiscField =
                when (variantSymbol) {
                    is RawSymbol.DataLike ->
                        variantSymbol.constructorParameters.any { it.annotations.has(AnnotationFqns.DiscriminatorField) }
                    else -> false
                }
            if (!variantHasDiscField) {
                errors +=
                    KspError(
                        message =
                            "@PacketTypeRange variant '${variantSymbol.fqn}' must carry a @DiscriminatorField " +
                                "constructor parameter — the wire byte depends on per-instance data, so the " +
                                "dispatcher cannot synthesize it.",
                        sourceFqn = variantSymbol.fqn,
                    )
            }
            // @PacketTypeRange requires the parent discriminator to be a single-byte value class
            val isSingleByteValueClass =
                typedDisc is DiscriminatorShape.ValueClass &&
                    PrimitiveTypes.naturalWireBytes(typedDisc.inner) == 1
            if (typedDisc != null && !isSingleByteValueClass) {
                errors +=
                    KspError(
                        message =
                            "@PacketTypeRange variant '${variantSymbol.fqn}' requires its parent's @DispatchOn " +
                                "discriminator to be a value class wrapping a single byte. Got " +
                                "${typedDisc::class.simpleName}.",
                        sourceFqn = variantSymbol.fqn,
                    )
            }
        }

        if (errors.isNotEmpty()) {
            return Nel.fromList(errors).left()
        }
        val codec = CodecNaming.forSymbol(variantSymbol)
        if (payloadTypeParams.isEmpty()) {
            return VariantPlan
                .NoPayload(
                    decl = TypeFqn(variantSymbol.fqn),
                    codec = codec,
                    wire = wireMatch ?: WireMatch.Point(TypeFqn(variantSymbol.fqn), 0),
                    selfEncodes = selfEncodes,
                    dir = direction,
                    fields = fields,
                ).right()
        }
        return VariantPlan
            .WithPayload(
                decl = TypeFqn(variantSymbol.fqn),
                codec = codec,
                wire = wireMatch ?: WireMatch.Point(TypeFqn(variantSymbol.fqn), 0),
                selfEncodes = selfEncodes,
                dir = direction,
                fields = fields,
                typeParams = payloadTypeParams,
                payloadFields = payloadFieldRefs,
            ).right()
    }

    private fun buildWireMatch(
        variantSymbol: RawSymbol,
        packetType: com.ditchoom.buffer.codec.processor.discovery.RawAnnotation?,
        packetRange: com.ditchoom.buffer.codec.processor.discovery.RawAnnotation?,
        typedDisc: DiscriminatorShape?,
        errors: MutableList<KspError>,
    ): WireMatch? {
        if (packetType != null) {
            val wire = packetType.intArg("wire")
            if (wire == null) {
                errors +=
                    KspError(
                        message =
                            "@PacketType on '${variantSymbol.fqn}' is missing the required `wire` argument.",
                        sourceFqn = variantSymbol.fqn,
                    )
                return null
            }
            // Bound check against the discriminator's natural unsigned range, when known
            val maxValue =
                when (typedDisc) {
                    is DiscriminatorShape.ValueClass -> PrimitiveTypes.maxUnsignedValue(typedDisc.inner)
                    is DiscriminatorShape.DataClass -> Long.MAX_VALUE
                    null -> 0xFFL // raw byte dispatch
                }
            if (wire < 0 || wire.toLong() > maxValue) {
                errors +=
                    KspError(
                        message =
                            "@PacketType($wire) on '${variantSymbol.fqn}' is outside the discriminator's range " +
                                "(0..$maxValue).",
                        sourceFqn = variantSymbol.fqn,
                    )
                return null
            }
            return WireMatch.Point(TypeFqn(variantSymbol.fqn), wire)
        }
        if (packetRange != null) {
            val from = packetRange.intArg("from")
            val to = packetRange.intArg("to")
            if (from == null || to == null) {
                errors +=
                    KspError(
                        message =
                            "@PacketTypeRange on '${variantSymbol.fqn}' requires both `from` and `to` arguments.",
                        sourceFqn = variantSymbol.fqn,
                    )
                return null
            }
            if (from > to) {
                errors +=
                    KspError(
                        message =
                            "@PacketTypeRange(from=$from, to=$to) on '${variantSymbol.fqn}' has from > to.",
                        sourceFqn = variantSymbol.fqn,
                    )
                return null
            }
            if (from < 0 || to > 0xFF) {
                errors +=
                    KspError(
                        message =
                            "@PacketTypeRange(from=$from, to=$to) on '${variantSymbol.fqn}' must be within 0..255.",
                        sourceFqn = variantSymbol.fqn,
                    )
                return null
            }
            return WireMatch.Range(TypeFqn(variantSymbol.fqn), from, to)
        }
        return null
    }

    private fun buildVariantFields(
        variantSymbol: RawSymbol,
        parentDispatchType: TypeFqn?,
        scope: Map<String, RawSymbol>,
        classWireOrder: Endianness,
    ): VariantFieldsResult =
        when (variantSymbol) {
            is RawSymbol.ObjectSymbol -> VariantFieldsResult(emptyList(), emptyList(), emptyList(), emptyList())
            is RawSymbol.SealedRoot ->
                VariantFieldsResult(
                    fields = emptyList(),
                    typeParams = emptyList(),
                    payloadFieldRefs = emptyList(),
                    errors =
                        listOf(
                            KspError(
                                message =
                                    "Sealed-root nesting is not supported as a sealed variant: '${variantSymbol.fqn}'. " +
                                        "Use a flat hierarchy.",
                                sourceFqn = variantSymbol.fqn,
                            ),
                        ),
                )
            is RawSymbol.DataLike -> {
                val payloadTypeParams =
                    variantSymbol.typeParameters
                        .filter { tp -> tp.annotations.any { it.fqn == AnnotationFqns.Payload } }
                        .map { PayloadTypeParam(name = it.name, upperBound = it.upperBoundFqn?.let(::TypeFqn)) }
                val payloadTypeParamNames = payloadTypeParams.map { it.name }.toSet()
                val payloadFieldRefs = mutableListOf<PayloadFieldRef>()
                val protocolMessageScope = scope.values.map { it.fqn }.toSet()
                val accumulated = mutableListOf<FieldPlan>()
                val errors = mutableListOf<KspError>()
                val params = variantSymbol.constructorParameters
                params.forEachIndexed { idx, p ->
                    val builder =
                        FieldStrategyBuilder(
                            ownerFqn = variantSymbol.fqn,
                            classWireOrder = classWireOrder,
                            precedingFields = accumulated.toList(),
                            totalFieldCount = params.size,
                            isLastField = idx == params.size - 1,
                            payloadTypeParams = payloadTypeParamNames,
                            parentDispatchType = parentDispatchType,
                            protocolMessageScope = protocolMessageScope,
                        )
                    when (val res = builder.build(p)) {
                        is Either.Left -> errors += res.value.all
                        is Either.Right -> {
                            accumulated += res.value
                            if (p.typeRef.isTypeParameter && p.typeRef.name in payloadTypeParamNames) {
                                payloadFieldRefs +=
                                    PayloadFieldRef(fieldName = p.name, typeParamName = p.typeRef.name)
                            }
                        }
                    }
                }
                VariantFieldsResult(
                    fields = accumulated.toList(),
                    typeParams = payloadTypeParams,
                    payloadFieldRefs = payloadFieldRefs.toList(),
                    errors = errors.toList(),
                )
            }
        }

    private fun resolveVariantDirection(
        variantSymbol: RawSymbol,
        parentRoot: RawSymbol.SealedRoot,
    ): Direction {
        val variantDir = DirectionResolver.classDirection(variantSymbol)
        val parentDir = DirectionResolver.classDirection(parentRoot)
        // PhaseB: a variant's direction is its own marker if present, otherwise the parent's.
        return when {
            variantDir != null -> variantDir
            parentDir != null -> parentDir
            else -> Direction.Bidirectional
        }
    }

    private fun typedDiscFqnOf(
        parentRoot: RawSymbol.SealedRoot,
        @Suppress("UNUSED_PARAMETER") scope: Map<String, RawSymbol>,
    ): String? = parentRoot.dispatchOnType()?.fqn
}

private data class VariantFieldsResult(
    val fields: List<FieldPlan>,
    val typeParams: List<PayloadTypeParam>,
    val payloadFieldRefs: List<PayloadFieldRef>,
    val errors: List<KspError>,
)
