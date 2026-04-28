package com.ditchoom.buffer.codec.processor.planbuilder

import com.ditchoom.buffer.codec.processor.discovery.DiscoveryResult
import com.ditchoom.buffer.codec.processor.discovery.RawAnnotationValue
import com.ditchoom.buffer.codec.processor.discovery.RawClassMetadata
import com.ditchoom.buffer.codec.processor.discovery.RawSymbol
import com.ditchoom.buffer.codec.processor.ir.Direction
import com.ditchoom.buffer.codec.processor.ir.DiscriminatorShape
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
    private const val DISPATCH_FRAMING_FQN = "com.ditchoom.buffer.codec.DispatchFraming"
    private const val BODY_LENGTH_FRAMING_FQN = "com.ditchoom.buffer.codec.BodyLengthFraming"

    fun build(
        symbol: RawSymbol,
        scope: Map<String, RawSymbol> = mapOf(symbol.fqn to symbol),
        externalClasses: Map<String, RawClassMetadata> = emptyMap(),
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
                    externalClasses = externalClasses,
                )
        }
    }

    /**
     * Whole-program orchestration. Builds plans for every successfully-discovered symbol,
     * forwarding PhaseA's external-class metadata so `@DispatchOn(framing = ...)` can
     * be resolved with framer-type awareness (peek-only vs body-length) and the
     * `Inherit` default can auto-discover companion-object framers.
     *
     * Errors accumulate across symbols: a discovery diagnostic on symbol A and a plan
     * error on symbol B both surface in a single [Either.Left]. If [DiscoveryResult.diagnostics]
     * contains any [com.ditchoom.buffer.codec.processor.discovery.DiscoveryDiagnostic.Severity.Error]
     * entries, those are turned into [KspError] entries up front.
     */
    fun build(rawSymbols: DiscoveryResult): Either<Nel<KspError>, Map<TypeFqn, Plan>> {
        val errors = mutableListOf<KspError>()
        for (diag in rawSymbols.diagnostics) {
            if (diag.severity == com.ditchoom.buffer.codec.processor.discovery.DiscoveryDiagnostic.Severity.Error) {
                errors += KspError(message = diag.message, sourceFqn = diag.sourceFqn)
            }
        }
        val scope = rawSymbols.symbols.associateBy { it.fqn }
        val plans = mutableMapOf<TypeFqn, Plan>()
        for (symbol in rawSymbols.symbols) {
            when (val result = build(symbol, scope, rawSymbols.externalClasses)) {
                is Either.Left -> errors += result.value.all
                is Either.Right -> plans[TypeFqn(symbol.fqn)] = result.value
            }
        }
        return if (errors.isEmpty()) {
            plans.toMap().right()
        } else {
            Nel.fromList(errors).left()
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
        externalClasses: Map<String, RawClassMetadata>,
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
                            val framing = resolveFramingMode(framingRef, disc.value, externalClasses)
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

    /**
     * Resolve the `@DispatchOn(framing = ...)` argument to a concrete [FramingMode] by
     * inspecting the named framer's directly-declared supertypes.
     *
     *  * Explicit framer declared as `BodyLengthFraming<D>` → [FramingMode.BodyLength].
     *  * Explicit framer declared as `DispatchFraming<D>` (no body-length subtype) →
     *    [FramingMode.PeekOnly].
     *  * Explicit framer that resolves but does not implement either → [FramingMode.Unframed]
     *    (PhaseC's `FramerTypeMatcher` then surfaces a dedicated error against the same
     *    framer FQN, so we don't double-report here).
     *  * `Inherit` (the annotation default) — auto-discover by inspecting the discriminator's
     *    `Companion` object metadata, captured as `${discriminatorFqn}.Companion` by PhaseA.
     *  * No framer at all → [FramingMode.Unframed].
     *
     * Walks **only** [RawClassMetadata.directlyDeclaredSupertypes]. Walking transitive
     * parents via KSP's all-supertypes walker returns unresolved type variables on
     * inherited generic supertypes, the bug that broke the previous BodyLengthFraming
     * attempt (commits `ada9796` on buffer, `cd245818` on mqtt, both reverted).
     */
    private fun resolveFramingMode(
        ref: RawAnnotationValue.ClassRef?,
        discriminator: DiscriminatorShape,
        externalClasses: Map<String, RawClassMetadata>,
    ): FramingMode {
        if (ref == null) {
            // Annotation default not surfaced by KSP — treat as Inherit and try
            // companion auto-discovery anyway.
            return resolveInherit(discriminator, externalClasses)
        }
        val fqn = ref.fqn
        if (fqn.isBlank() || !ref.resolved) return FramingMode.Unframed
        if (fqn == "com.ditchoom.buffer.codec.DispatchFraming.Inherit" ||
            fqn.endsWith(".Inherit")
        ) {
            return resolveInherit(discriminator, externalClasses)
        }
        if (fqn == "com.ditchoom.buffer.codec.NoFraming" || fqn.endsWith(".NoFraming")) {
            return FramingMode.Unframed
        }
        return frameModeFromMetadata(
            framerFqn = fqn,
            metadata = externalClasses[fqn],
            discriminator = discriminator,
        )
    }

    private fun resolveInherit(
        discriminator: DiscriminatorShape,
        externalClasses: Map<String, RawClassMetadata>,
    ): FramingMode {
        val companionFqn = "${discriminator.discriminatorType.canonical}.Companion"
        val metadata = externalClasses[companionFqn] ?: return FramingMode.Unframed
        return frameModeFromMetadata(
            framerFqn = companionFqn,
            metadata = metadata,
            discriminator = discriminator,
        )
    }

    private fun frameModeFromMetadata(
        framerFqn: String,
        metadata: RawClassMetadata?,
        discriminator: DiscriminatorShape,
    ): FramingMode {
        val framerClassName = fqnToClassName(framerFqn)
        // Without metadata (framer FQN was named explicitly but didn't resolve at PhaseA)
        // we still emit PeekOnly so PhaseC's FramerTypeMatcher gets a chance to surface
        // the resolution error against the same framerFqn.
        if (metadata == null) return FramingMode.PeekOnly(framerFqn = framerClassName)
        val supertypeFqns = metadata.directlyDeclaredSupertypes.map { it.fqn }
        return when {
            BODY_LENGTH_FRAMING_FQN in supertypeFqns ->
                FramingMode.BodyLength(
                    framerFqn = framerClassName,
                    discriminatorBytes = discriminatorBytes(discriminator),
                )
            DISPATCH_FRAMING_FQN in supertypeFqns ->
                FramingMode.PeekOnly(framerFqn = framerClassName)
            else -> FramingMode.Unframed
        }
    }

    private fun discriminatorBytes(discriminator: DiscriminatorShape): Int =
        when (discriminator) {
            is DiscriminatorShape.ValueClass -> PrimitiveTypes.naturalWireBytes(discriminator.inner)
            is DiscriminatorShape.DataClass -> discriminator.params.sumOf { it.wireBytes }.coerceAtLeast(1)
        }

    /**
     * Split a FQN into a KotlinPoet [ClassName], treating each capitalized terminal
     * segment as a nested-class component. Mirrors `ClassName.bestGuess` without
     * pulling in its surface area.
     */
    private fun fqnToClassName(fqn: String): ClassName {
        val parts = fqn.split('.')
        val firstUpper = parts.indexOfFirst { it.isNotEmpty() && it[0].isUpperCase() }
        return if (firstUpper < 0) {
            ClassName("", parts)
        } else {
            val pkg = parts.subList(0, firstUpper).joinToString(".")
            val simples = parts.subList(firstUpper, parts.size)
            ClassName(pkg, simples)
        }
    }
}
