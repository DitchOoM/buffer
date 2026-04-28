package com.ditchoom.buffer.codec.processor.planbuilder

import com.ditchoom.buffer.codec.processor.discovery.RawAnnotationValue
import com.ditchoom.buffer.codec.processor.discovery.RawSymbol
import com.ditchoom.buffer.codec.processor.ir.Direction
import com.ditchoom.buffer.codec.processor.ir.DispatchShape
import com.ditchoom.buffer.codec.processor.ir.Endianness
import com.ditchoom.buffer.codec.processor.ir.FieldPlan
import com.ditchoom.buffer.codec.processor.ir.FramingMode
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.planbuilder.Annotations.classRefArg
import com.ditchoom.buffer.codec.processor.planbuilder.Annotations.enumArg
import com.ditchoom.buffer.codec.processor.planbuilder.Annotations.find
import com.ditchoom.buffer.codec.processor.planbuilder.Annotations.stringArg
import com.squareup.kotlinpoet.ClassName

/**
 * Phase B (PlanBuilder) — pure transform of one [RawSymbol] into a [Plan].
 *
 * Errors accumulate into a [Nel] of [KspError]s on the `Either.Left` branch, never bail
 * on the first failure: a fixture violating two rules simultaneously yields a single
 * [Either.Left] containing both error entries.
 *
 * Cross-symbol validations (range disjointness across siblings, framer-type matching,
 * cycles, `@When` field-ref resolution against the field map of the enclosing class)
 * remain PhaseC's responsibility and are intentionally absent here. Single-symbol checks
 * (mutex annotations, type matching for `@DiscriminatorField`, `@LengthFrom` reference
 * to a preceding field, `@When` parsing) live entirely in this module.
 */
object PlanBuilder {
    fun build(
        symbol: RawSymbol,
        scope: Map<String, RawSymbol> = mapOf(symbol.fqn to symbol),
    ): Either<Nel<KspError>, Plan> {
        val effectiveScope = if (symbol.fqn in scope) scope else scope + (symbol.fqn to symbol)
        val classWireOrder = readWireOrder(symbol)
        val direction = DirectionResolver.resolve(symbol)
        return when (symbol) {
            is RawSymbol.ObjectSymbol -> direction.map { dir -> Plan.Object_(decl = TypeFqn(symbol.fqn), dir = dir) }
            is RawSymbol.DataLike ->
                buildDataLike(
                    symbol = symbol,
                    direction = direction,
                    classWireOrder = classWireOrder,
                    scope = effectiveScope,
                    parentDispatchType = parentDispatchTypeFor(symbol, effectiveScope),
                )
            is RawSymbol.SealedRoot ->
                buildSealedRoot(
                    symbol = symbol,
                    direction = direction,
                    classWireOrder = classWireOrder,
                    scope = effectiveScope,
                )
        }
    }

    private fun readWireOrder(symbol: RawSymbol): Endianness {
        val pm = symbol.annotations.find(AnnotationFqns.ProtocolMessage) ?: return Endianness.Big
        val name = pm.enumArg("wireOrder")?.name
        return EndiannessMapping.fromAnnotationEnum(name) ?: Endianness.Big
    }

    private fun parentDispatchTypeFor(
        variant: RawSymbol.DataLike,
        scope: Map<String, RawSymbol>,
    ): TypeFqn? {
        for (s in scope.values) {
            if (s !is RawSymbol.SealedRoot) continue
            if (variant.fqn !in s.subclassFqns) continue
            val ref = s.dispatchOnType() ?: continue
            if (ref.resolved && ref.fqn.isNotBlank()) return TypeFqn(ref.fqn)
        }
        return null
    }

    private fun buildDataLike(
        symbol: RawSymbol.DataLike,
        direction: Either<Nel<KspError>, Direction>,
        classWireOrder: Endianness,
        scope: Map<String, RawSymbol>,
        parentDispatchType: TypeFqn?,
    ): Either<Nel<KspError>, Plan> {
        val payloadTypeParams =
            symbol.typeParameters
                .filter { tp -> tp.annotations.any { it.fqn == AnnotationFqns.Payload } }
                .map { it.name }
                .toSet()
        val protocolMessageScope = scope.values.map { it.fqn }.toSet()
        val accumulated = mutableListOf<FieldPlan>()
        val errors = mutableListOf<KspError>()
        symbol.constructorParameters.forEachIndexed { idx, p ->
            val builder =
                FieldStrategyBuilder(
                    ownerFqn = symbol.fqn,
                    classWireOrder = classWireOrder,
                    precedingFields = accumulated.toList(),
                    totalFieldCount = symbol.constructorParameters.size,
                    isLastField = idx == symbol.constructorParameters.size - 1,
                    payloadTypeParams = payloadTypeParams,
                    parentDispatchType = parentDispatchType,
                    protocolMessageScope = protocolMessageScope,
                )
            when (val res = builder.build(p)) {
                is Either.Left -> errors += res.value.all
                is Either.Right -> accumulated += res.value
            }
        }
        val dirOrErrors =
            when (direction) {
                is Either.Left -> {
                    errors += direction.value.all
                    Direction.Bidirectional
                }
                is Either.Right -> direction.value
            }
        if (errors.isNotEmpty()) {
            return Nel.fromList(errors).left()
        }
        return Plan
            .Leaf(
                decl = TypeFqn(symbol.fqn),
                fields = accumulated.toList(),
                batches = emptyList(),
                dir = dirOrErrors,
            ).right()
    }

    private fun buildSealedRoot(
        symbol: RawSymbol.SealedRoot,
        direction: Either<Nel<KspError>, Direction>,
        classWireOrder: Endianness,
        scope: Map<String, RawSymbol>,
    ): Either<Nel<KspError>, Plan> {
        val errors = mutableListOf<KspError>()
        val dirOrErrors =
            when (direction) {
                is Either.Left -> {
                    errors += direction.value.all
                    Direction.Bidirectional
                }
                is Either.Right -> direction.value
            }

        val dispatchOn = symbol.annotations.find(AnnotationFqns.DispatchOn)
        val dispatch =
            if (dispatchOn == null) {
                DispatchShape.RawByte
            } else {
                val typeArg = dispatchOn.classRefArg("type")
                if (typeArg == null || !typeArg.resolved || typeArg.fqn.isBlank()) {
                    errors +=
                        KspError(
                            message =
                                "@DispatchOn(...) on '${symbol.fqn}' is missing a resolvable `type` argument.",
                            sourceFqn = symbol.fqn,
                        )
                    DispatchShape.RawByte
                } else {
                    when (val disc = DiscriminatorBuilder.build(symbol.fqn, typeArg.fqn, scope)) {
                        is Either.Left -> {
                            errors += disc.value.all
                            DispatchShape.RawByte
                        }
                        is Either.Right -> {
                            val framingRef = dispatchOn.classRefArg("framing")
                            val framing = resolveFramingMode(framingRef)
                            DispatchShape.TypedDiscriminator(disc = disc.value, framing = framing)
                        }
                    }
                }
            }

        val variants = mutableListOf<com.ditchoom.buffer.codec.processor.ir.VariantPlan>()
        for (childFqn in symbol.subclassFqns) {
            val child = scope[childFqn]
            if (child == null) {
                errors +=
                    KspError(
                        message =
                            "Sealed root '${symbol.fqn}' lists subclass '$childFqn' but it was not " +
                                "discovered as a @ProtocolMessage. Annotate it or remove it from the hierarchy.",
                        sourceFqn = symbol.fqn,
                    )
                continue
            }
            when (val v = VariantPlanBuilder.build(child, symbol, dispatch, scope, classWireOrder)) {
                is Either.Left -> errors += v.value.all
                is Either.Right -> variants += v.value
            }
        }

        val onUnknownFqn =
            symbol.annotations
                .find(AnnotationFqns.ProtocolMessage)
                ?.stringArg("onUnknownDiscriminator")
                ?.takeIf { it.isNotBlank() }
                ?: "java.lang.IllegalArgumentException"

        if (errors.isNotEmpty()) {
            return Nel.fromList(errors).left()
        }
        return Plan
            .Sealed_(
                decl = TypeFqn(symbol.fqn),
                variants = variants.toList(),
                dispatch = dispatch,
                dir = dirOrErrors,
                onUnknown = TypeFqn(onUnknownFqn),
            ).right()
    }

    private fun resolveFramingMode(ref: RawAnnotationValue.ClassRef?): FramingMode {
        if (ref == null) return FramingMode.Unframed
        val fqn = ref.fqn
        if (fqn.isBlank() || !ref.resolved) return FramingMode.Unframed
        // The annotation default is `DispatchFraming.Inherit` — no framer chosen explicitly.
        if (fqn == "com.ditchoom.buffer.codec.DispatchFraming.Inherit" ||
            fqn.endsWith(".Inherit")
        ) {
            return FramingMode.Unframed
        }
        if (fqn == "com.ditchoom.buffer.codec.NoFraming" || fqn.endsWith(".NoFraming")) {
            return FramingMode.Unframed
        }
        return FramingMode.PeekOnly(framerFqn = ClassName(packageNameOf(fqn), simpleNameOf(fqn)))
    }
}
