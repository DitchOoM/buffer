package com.ditchoom.buffer.codec.processor.planbuilder

import com.ditchoom.buffer.codec.processor.discovery.DiscoveryResult
import com.ditchoom.buffer.codec.processor.discovery.RawAnnotationValue
import com.ditchoom.buffer.codec.processor.discovery.RawClassMetadata
import com.ditchoom.buffer.codec.processor.discovery.RawSymbol
import com.ditchoom.buffer.codec.processor.ir.Conditionality
import com.ditchoom.buffer.codec.processor.ir.Direction
import com.ditchoom.buffer.codec.processor.ir.DiscriminatorShape
import com.ditchoom.buffer.codec.processor.ir.DispatchShape
import com.ditchoom.buffer.codec.processor.ir.Endianness
import com.ditchoom.buffer.codec.processor.ir.FieldPlan
import com.ditchoom.buffer.codec.processor.ir.FramingMode
import com.ditchoom.buffer.codec.processor.ir.PayloadFieldRef
import com.ditchoom.buffer.codec.processor.ir.PayloadTypeParam
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
            is RawSymbol.ObjectSymbol -> {
                // Cap 6: @DispatchOn is only valid on sealed roots. Reject it on
                // a singleton object — the legacy emitter's diagnostic was
                // "@DispatchOn is not valid on an object".
                val errors = mutableListOf<KspError>()
                if (symbol.annotations.find(AnnotationFqns.DispatchOn) != null) {
                    errors +=
                        KspError(
                            message =
                                "@DispatchOn is not valid on an object — '${symbol.fqn}' is a singleton " +
                                    "with no variants to dispatch to. Move the annotation to a sealed root.",
                            sourceFqn = symbol.fqn,
                        )
                }
                if (errors.isNotEmpty()) {
                    Nel.fromList(errors).left()
                } else {
                    direction.map { dir -> Plan.Object_(decl = TypeFqn(symbol.fqn), dir = dir) }
                }
            }
            is RawSymbol.DataLike ->
                buildDataLike(
                    symbol = symbol,
                    direction = direction,
                    classWireOrder = classWireOrder,
                    scope = effectiveScope,
                    parentDispatchType = parentDispatchTypeFor(symbol, effectiveScope),
                    externalClasses = externalClasses,
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
        externalClasses: Map<String, RawClassMetadata>,
    ): Either<Nel<KspError>, Plan> {
        val payloadTypeParams =
            symbol.typeParameters
                .filter { tp -> tp.annotations.any { it.fqn == AnnotationFqns.Payload } }
                .map { PayloadTypeParam(name = it.name, upperBound = it.upperBoundFqn?.let(::TypeFqn)) }
        val payloadTypeParamNames = payloadTypeParams.map { it.name }.toSet()
        val payloadFieldRefs = mutableListOf<PayloadFieldRef>()
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
                    payloadTypeParams = payloadTypeParamNames,
                    parentDispatchType = parentDispatchType,
                    protocolMessageScope = protocolMessageScope,
                    externalClasses = externalClasses,
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
        val explicitDir =
            when (direction) {
                is Either.Left -> {
                    errors += direction.value.all
                    Direction.Bidirectional
                }
                is Either.Right -> direction.value
            }
        // Slice 5b: refine direction from field strategies. When the class has no
        // explicit `@Decode`/`@Encode`/`direction = ...` marker (RawDirection.Default
        // mapped to Bidirectional), narrow the direction by inspecting field-level
        // codec interfaces: a field whose `@UseCodec` references a `Decoder`-only
        // class makes the whole class decode-only; same for encode-only. Mirrors
        // legacy `inferDirection` + `validateDirection`.
        val classExplicit = DirectionResolver.classDirection(symbol)
        val dirOrErrors =
            FieldDirectionInference.refine(
                fields = accumulated,
                explicit = classExplicit,
                fallback = explicitDir,
                scope = scope,
                externalClasses = externalClasses,
                ownerFqn = symbol.fqn,
                errors = errors,
            )
        // Cap 5: WhenRemaining fields must be contiguous and at the constructor
        // tail. Mirrors legacy `ConditionalValidator.validateWhenRemaining` —
        // the cascade-on-encode logic depends on this invariant.
        validateWhenRemainingTailContiguity(symbol.fqn, accumulated, errors)
        if (errors.isNotEmpty()) {
            return Nel.fromList(errors).left()
        }
        return Plan
            .Leaf(
                decl = TypeFqn(symbol.fqn),
                fields = accumulated.toList(),
                batches = emptyList(),
                dir = dirOrErrors,
                payloadTypeParams = payloadTypeParams,
                payloadFields = payloadFieldRefs.toList(),
            ).right()
    }

    /**
     * Cap 5 — `@WhenRemaining` fields must form a contiguous suffix at the
     * tail of the constructor. The encode cascade (each field guards the
     * remainder via a null check) depends on this invariant; out-of-order
     * @WhenRemaining fields would generate impossible wire shapes.
     *
     * Detects the first `WhenRemaining`-style conditional (lowered to
     * `BooleanExpression.RemainingGte` in the IR) and ensures every field
     * from that index onwards also carries a `RemainingGte` conditionality.
     */
    private fun validateWhenRemainingTailContiguity(
        ownerFqn: String,
        fields: List<FieldPlan>,
        errors: MutableList<KspError>,
    ) {
        val firstIdx =
            fields.indexOfFirst { f ->
                val c = f.conditionality
                c is Conditionality.WhenExpr &&
                    c.expr is com.ditchoom.buffer.codec.processor.ir.BooleanExpression.RemainingGte
            }
        if (firstIdx < 0) return
        for (i in firstIdx until fields.size) {
            val c = fields[i].conditionality
            val isWhenRemaining =
                c is Conditionality.WhenExpr &&
                    c.expr is com.ditchoom.buffer.codec.processor.ir.BooleanExpression.RemainingGte
            if (!isWhenRemaining) {
                errors +=
                    KspError(
                        message =
                            "@WhenRemaining fields must be contiguous and at the tail of the constructor. " +
                                "Non-@WhenRemaining field '${fields[i].name}' on '$ownerFqn' appears after " +
                                "@WhenRemaining field '${fields[firstIdx].name}'.",
                        sourceFqn = "$ownerFqn.${fields[i].name}",
                    )
                return
            }
        }
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
                    when (val disc = DiscriminatorBuilder.build(symbol.fqn, typeArg.fqn, scope, externalClasses)) {
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
            when (val v = VariantPlanBuilder.build(child, symbol, dispatch, scope, classWireOrder, externalClasses)) {
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

        // Slice 5.5: reconcile the sealed root's explicit direction (if any) against the
        // variants' inferred directions. Mirrors legacy `computeSealedDirection` in
        // ProtocolMessageProcessor:
        //  * Conflict (some DecodeOnly + some EncodeOnly): error.
        //  * Sealed-explicit `Codec` (Bidirectional) but any variant is unidirectional:
        //    error — a bidirectional dispatcher cannot reference variants that lack
        //    `encode` / `wireSize` (Decoder-only) or `decode` (Encoder-only) overloads.
        //  * Sealed-explicit `DecodeOnly` and any variant is `EncodeOnly`: error.
        //  * Sealed-explicit `EncodeOnly` and any variant is `DecodeOnly`: error.
        //  * Otherwise: when sealed is `Default`, infer the most-restrictive direction
        //    from variants; when sealed is explicit, use it as-is.
        val rawDir = symbol.direction
        val refinedDir = computeSealedDirection(symbol, variants, rawDir, dirOrErrors, errors)
        if (errors.isNotEmpty()) {
            return Nel.fromList(errors).left()
        }
        return Plan
            .Sealed_(
                decl = TypeFqn(symbol.fqn),
                variants = variants.toList(),
                dispatch = dispatch,
                dir = refinedDir,
                onUnknown = TypeFqn(onUnknownFqn),
            ).right()
    }

    /**
     * Mirror of legacy `ProtocolMessageProcessor.computeSealedDirection`. Reconciles the
     * sealed root's explicit direction against the variants' inferred directions.
     *
     * Without this refinement: a sealed root with all-DecodeOnly variants would emit
     * `Codec<T>` superinterface while the variants' codecs only implement `Decoder<T>` —
     * the dispatcher would reference `Variant.encode` and `Variant.wireSize` overloads
     * the variants don't expose, producing a hard compile error.
     *
     * The legacy resolver runs against `KSClassDeclaration` and walks fields to compute
     * variant direction. PhaseB pre-runs the same per-variant inference inside
     * [VariantPlanBuilder] so by the time we reach this method, every variant carries
     * its inferred [Direction] in `VariantPlan.dir`.
     */
    private fun computeSealedDirection(
        symbol: RawSymbol.SealedRoot,
        variants: List<com.ditchoom.buffer.codec.processor.ir.VariantPlan>,
        rawDir: com.ditchoom.buffer.codec.processor.discovery.RawDirection,
        defaultDir: Direction,
        errors: MutableList<KspError>,
    ): Direction {
        val decodeOnly = variants.filter { it.dir == Direction.DecodeOnly }
        val encodeOnly = variants.filter { it.dir == Direction.EncodeOnly }
        if (decodeOnly.isNotEmpty() && encodeOnly.isNotEmpty()) {
            errors +=
                KspError(
                    message =
                        "Sealed root '${symbol.fqn}' has both decode-only variants " +
                            "[${decodeOnly.joinToString(", ") { "'${it.decl.canonical}'" }}] " +
                            "and encode-only variants " +
                            "[${encodeOnly.joinToString(", ") { "'${it.decl.canonical}'" }}]. " +
                            "These are incompatible. Either make all variant codecs bidirectional " +
                            "or split into separate sealed roots per direction.",
                    sourceFqn = symbol.fqn,
                )
            return defaultDir
        }
        val inferred =
            when {
                decodeOnly.isNotEmpty() -> Direction.DecodeOnly
                encodeOnly.isNotEmpty() -> Direction.EncodeOnly
                else -> Direction.Bidirectional
            }
        return when (rawDir) {
            com.ditchoom.buffer.codec.processor.discovery.RawDirection.Default -> {
                // No explicit class-level signal — use whatever the variants imply.
                if (defaultDir == Direction.Bidirectional) inferred else defaultDir
            }
            com.ditchoom.buffer.codec.processor.discovery.RawDirection.Codec -> {
                val unidirectional = decodeOnly + encodeOnly
                if (unidirectional.isNotEmpty()) {
                    errors +=
                        KspError(
                            message =
                                "@ProtocolMessage(direction = Codec) on sealed root '${symbol.fqn}' " +
                                    "requires all variants to be bidirectional, but these are not: " +
                                    unidirectional.joinToString(", ") { "'${it.decl.canonical}' (${it.dir})" } +
                                    ". Make those variants bidirectional or change the sealed direction " +
                                    "to DecodeOnly / EncodeOnly.",
                            sourceFqn = symbol.fqn,
                        )
                }
                Direction.Bidirectional
            }
            com.ditchoom.buffer.codec.processor.discovery.RawDirection.DecodeOnly -> {
                if (encodeOnly.isNotEmpty()) {
                    errors +=
                        KspError(
                            message =
                                "@Decode (or @ProtocolMessage(direction = DecodeOnly)) on sealed root " +
                                    "'${symbol.fqn}' conflicts with encode-only variants: " +
                                    encodeOnly.joinToString(", ") { "'${it.decl.canonical}'" } +
                                    ". Make those variants bidirectional or remove the encode-only constraint.",
                            sourceFqn = symbol.fqn,
                        )
                }
                Direction.DecodeOnly
            }
            com.ditchoom.buffer.codec.processor.discovery.RawDirection.EncodeOnly -> {
                if (decodeOnly.isNotEmpty()) {
                    errors +=
                        KspError(
                            message =
                                "@Encode (or @ProtocolMessage(direction = EncodeOnly)) on sealed root " +
                                    "'${symbol.fqn}' conflicts with decode-only variants: " +
                                    decodeOnly.joinToString(", ") { "'${it.decl.canonical}'" } +
                                    ". Make those variants bidirectional or remove the decode-only constraint.",
                            sourceFqn = symbol.fqn,
                        )
                }
                Direction.EncodeOnly
            }
            com.ditchoom.buffer.codec.processor.discovery.RawDirection.Conflict -> defaultDir
        }
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
        // When the framer is the discriminator's companion object, emit calls via
        // the enclosing class name (Kotlin auto-routes to the companion) so generated
        // source reads `MyTag.readBodyLength(...)` rather than the longer
        // `MyTag.Companion.readBodyLength(...)`. Mirrors legacy
        // `ResolvedFramer(fqn = discriminatorClass.qualifiedName)`. Slice 5.5: pass
        // the companion FQN as the **lookup key** for FramerTypeMatcher (so the
        // validator inspects the companion's supertypes, not the discriminator's),
        // but emit the **discriminator class name** so the generated source matches
        // legacy.
        return frameModeFromMetadata(
            framerFqn = companionFqn,
            metadata = metadata,
            discriminator = discriminator,
            emitClassNameOverride = fqnToClassName(discriminator.discriminatorType.canonical),
        )
    }

    private fun frameModeFromMetadata(
        framerFqn: String,
        metadata: RawClassMetadata?,
        discriminator: DiscriminatorShape,
        emitClassNameOverride: ClassName? = null,
    ): FramingMode {
        // `framerClassName` is what the dispatcher emits at decode/encode call sites.
        // For the inherit-companion path Slice 5.5 overrides this to the **discriminator
        // class name** so generated source reads `MyTag.readBodyLength(...)` (Kotlin
        // auto-routes to the companion). FramerTypeMatcher continues to look up by
        // FQN, which here is the companion FQN — that is what the metadata was
        // captured for.
        val framerClassName = emitClassNameOverride ?: fqnToClassName(framerFqn)
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
