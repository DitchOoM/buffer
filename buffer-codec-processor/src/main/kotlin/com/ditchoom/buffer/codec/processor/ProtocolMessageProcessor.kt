package com.ditchoom.buffer.codec.processor

import com.ditchoom.buffer.codec.processor.discovery.Discovery
import com.ditchoom.buffer.codec.processor.discovery.DiscoveryResult
import com.ditchoom.buffer.codec.processor.emitter.CodecEmitter
import com.ditchoom.buffer.codec.processor.emitter.TypeRegistry
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.planbuilder.Either
import com.ditchoom.buffer.codec.processor.planbuilder.PlanBuilder
import com.ditchoom.buffer.codec.processor.spi.CodecFieldProvider
import com.ditchoom.buffer.codec.processor.validator.Validator
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.ksp.writeTo

class ProtocolMessageProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val customProviders: Map<String, CodecFieldProvider> = emptyMap(),
) : SymbolProcessor {
    private val processed = mutableSetOf<String>()

    /**
     * Phase 9 Slice 1: Discovery output cached for the lifetime of one [process] round.
     * Discovery walks the entire resolver, so doing it once per `@ProtocolMessage` symbol
     * would multiply KSP work by N. Cleared at the start of [process] so subsequent rounds
     * see fresh `KSClassDeclaration`s.
     */
    private var cachedDiscovery: DiscoveryResult? = null

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // Slice 1: clear the per-round cache so Discovery re-runs against the fresh resolver.
        cachedDiscovery = null

        val annotationName = "com.ditchoom.buffer.codec.annotations.ProtocolMessage"
        val symbols = resolver.getSymbolsWithAnnotation(annotationName)
        val symbolList = symbols.toList()

        for (symbol in symbolList) {
            if (symbol !is KSClassDeclaration) {
                logger.error(
                    "@ProtocolMessage can only be applied to classes or sealed interfaces, " +
                        "but was applied to a ${symbol::class.simpleName ?: "non-class element"}.",
                    symbol,
                )
                continue
            }
            val qualifiedName = symbol.qualifiedName?.asString() ?: continue
            if (qualifiedName in processed) continue
            processed.add(qualifiedName)

            when {
                Modifier.SEALED in symbol.modifiers -> processSealedInterface(symbol, resolver)
                symbol.classKind == ClassKind.OBJECT -> {
                    if (tryPipeline(symbol, resolver)) continue
                    processObject(symbol)
                }
                else -> {
                    val constructor = symbol.primaryConstructor
                    if (constructor == null) {
                        logger.error(
                            "@ProtocolMessage class '${symbol.simpleName.asString()}' must have a primary constructor " +
                                "with val parameters. " +
                                "Fix: add a primary constructor (e.g., 'class ${symbol.simpleName.asString()}(val id: Int)').",
                            symbol,
                        )
                    } else if (constructor.parameters.isEmpty()) {
                        logger.error(
                            "@ProtocolMessage class '${symbol.simpleName.asString()}' must have at least one val parameter " +
                                "in its primary constructor. For a type-only message (no wire bytes), declare it as " +
                                "'object ${symbol.simpleName.asString()}' or 'data object ${symbol.simpleName.asString()}' instead.",
                            symbol,
                        )
                    } else {
                        if (tryPipeline(symbol, resolver)) continue
                        processDataClass(symbol, resolver)
                    }
                }
            }
        }
        return emptyList()
    }

    /**
     * Phase 9 Slice 5b: gate flipped — every Plan flows through the new pipeline.
     *
     * The Discovery → PlanBuilder → Validator → CodecEmitter pipeline is now the
     * sole emitter. The previous per-strategy whitelist (Slices 1-5a) accumulated
     * each branch as the pipeline learned to emit it; with Slice 5.5's PhaseA/B
     * port (`@DispatchValue` derived-getter resolution, sealed-root direction
     * inference, asymmetric SPI), every Plan variant is fully coverable.
     *
     * The function is preserved (rather than inlined into `tryPipeline`) so the
     * codepath stays uniform with prior slices and any regression bisect can
     * temporarily revert the body. Slice 6 deletes the legacy emitter entirely.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun coverable(plan: Plan): Boolean = true

    /**
     * Phase 9 Slice 5b: residual eligibility filter for legacy-only diagnostics.
     *
     * The `coverable()` gate is now unconditionally true — every coverable Plan
     * flows through the new pipeline. This filter remains because three classes
     * of input still rely on legacy diagnostics that PhaseB / Validator do not
     * yet emit:
     *
     *  * `@DispatchOn` on a non-sealed symbol (object / data class) — legacy
     *    emits a tailored "@DispatchOn is not valid on an object" error that
     *    references the annotation by name. PhaseB does not look at `@DispatchOn`
     *    on non-sealed symbols at all (silently treated as a no-op zero-byte
     *    codec). Slice 6+ would port the diagnostic; until then defer to legacy.
     *
     *  * Constructor parameters carrying a custom-provider annotation FQN
     *    (`CodecFieldProvider` SPI) — the new [PlanBuilder] does not consume
     *    `customProviders` and would silently misclassify the field. Production
     *    consumers (mqtt, websocket) do not register any custom providers, so
     *    this branch is a defensive guard for downstream users who do.
     *
     *  * Malformed conditional fields — legacy `ConditionalValidator` enforces
     *    nullability, default-value presence, tail-position, and `minBytes > 0`
     *    on conditional fields. The new pipeline does not yet enforce these
     *    (PhaseB happily accepts a non-nullable conditional field). Defer to
     *    legacy via [conditionalShapeOk] preflight so the user sees the legacy
     *    diagnostic rather than a downstream NPE in the generated codec.
     */
    private fun pipelineEligible(symbol: com.ditchoom.buffer.codec.processor.discovery.RawSymbol): Boolean {
        // Slice 5.5: `@DispatchOn` on a sealed root is now eligible to cross the
        // pipeline. PhaseA captures `@DispatchValue` properties off the discriminator
        // class (including derived getters such as `MqttFixedHeader.packetType`),
        // and PhaseB's `DiscriminatorBuilder` consumes the captured property name
        // when building `DiscriminatorShape.ValueClass.dispatchProp`. The remaining
        // `coverable()` whitelist is what gates routing — non-coverable shapes still
        // fall through to legacy.
        //
        // `@DispatchOn` on a non-sealed symbol (object / data class) still falls
        // through here: legacy emits the tailored "@DispatchOn is not valid on an
        // object" error and PhaseB has no analog, so the legacy diagnostic remains
        // the user-visible source. The check below applies only to non-sealed
        // symbols.
        val isSealedRoot = symbol is com.ditchoom.buffer.codec.processor.discovery.RawSymbol.SealedRoot
        if (!isSealedRoot &&
            symbol.annotations.any { it.fqn == "com.ditchoom.buffer.codec.annotations.DispatchOn" }
        ) {
            return false
        }
        if (symbol is com.ditchoom.buffer.codec.processor.discovery.RawSymbol.DataLike) {
            for (p in symbol.constructorParameters) {
                for (ann in p.annotations) {
                    if (ann.fqn in customProviders.keys) return false
                }
            }
            // Slice 5b: top-level data classes carrying `@Payload` type parameters
            // need the typed-lambda fan-out (`decode<P>(buf, lambda)` /
            // `encode<P>(buf, value, lambda)` / `wireSize<P>(value, sizeOf)`) plus
            // a generic `Codec<T<*>>` superinterface. Phase 9 Step 4 Cap 2 ported
            // the typed-lambda emit (bare `(ReadBuffer) -> P` lambdas, no
            // synthesized `*Context` receiver), but the new PlanBuilder does not
            // yet enforce the legacy rule that `@Payload` fields require a length
            // annotation — `FieldStrategyBuilder.buildPayloadSlot` silently falls
            // back to `LengthSource.Remaining`. Until that rule is ported in
            // Step 5 (alongside variant-emission migration that lets sealed
            // payload variants speak the same bare-lambda calling convention),
            // defer top-level `@Payload` data classes to legacy so its
            // diagnostics surface.
            val hasPayloadTypeParam =
                symbol.typeParameters.any { tp ->
                    tp.annotations.any { it.fqn == "com.ditchoom.buffer.codec.annotations.Payload" }
                }
            if (hasPayloadTypeParam) return false
            // Slice 5b: defer to legacy when a constructor parameter combines
            // `@LengthPrefixed` / `@LengthFrom` with a NestedMessage or External
            // (UseCodec) field type. The new pipeline's `FieldStrategyBuilder.buildNestedMessage`
            // and `buildExternal` ignore the resolved length source — the codec
            // should emit a length prefix (and slice the body for decode) before
            // delegating, but currently it just calls `NestedCodec.encode/decode`.
            // The legacy `FieldCodeEmitter` handles this correctly for varint /
            // short / int / byte prefixes. Defer until the IR + emitter port lands.
            //
            // Mqtt + websocket consumer suites do not exercise this combination
            // (their `@LengthPrefixed` fields are all String / Collection / @Payload-typed).
            //
            // Detection: any param with `@LengthPrefixed` whose declared type is not
            // a built-in primitive / String / Collection — i.e. references another
            // `@ProtocolMessage` class (NestedMessage) or has `@UseCodec` (External).
            val protocolMessageScope = cachedDiscovery?.symbols?.map { it.fqn }?.toSet().orEmpty()
            for (p in symbol.constructorParameters) {
                val hasLengthPrefixed =
                    p.annotations.any { it.fqn == "com.ditchoom.buffer.codec.annotations.LengthPrefixed" }
                if (!hasLengthPrefixed) continue
                val typeFqn = p.typeRef.fqn
                val isString = typeFqn == "kotlin.String"
                val isCollection =
                    typeFqn == "kotlin.collections.List" ||
                        typeFqn == "kotlin.collections.Collection" ||
                        typeFqn == "kotlin.collections.Set"
                val hasUseCodec =
                    p.annotations.any { it.fqn == "com.ditchoom.buffer.codec.annotations.UseCodec" }
                val isNested = typeFqn in protocolMessageScope
                if ((hasUseCodec || isNested) && !isString && !isCollection) return false
            }
            // Slice 5b: defer to legacy when any param carries `@WireBytes` with a
            // non-natural width (e.g. `@WireBytes(3)` on a `UInt`). The new
            // `FieldStrategyBuilder.buildPrimitive` accepts the field but `LeafEmitter`
            // emits the natural width unconditionally. Legacy `CustomWidthEmitter`
            // handles 3-byte and similar widths via shift-and-mask sequences.
            for (p in symbol.constructorParameters) {
                val wb =
                    p.annotations.find { it.fqn == "com.ditchoom.buffer.codec.annotations.WireBytes" }
                if (wb == null) continue
                // Any explicit @WireBytes is a custom-width annotation; bail.
                return false
            }
            // Slice 3: conditional-field rule enforcement is owned by the legacy
            // [com.ditchoom.buffer.codec.processor.ConditionalValidator] (default-value
            // requirement, contiguous-tail constraint, minBytes > 0). Surface those
            // diagnostics through the legacy path until the rules are ported into
            // PhaseB/C — defer to legacy when any conditional annotation is present
            // AND the class violates a preflight check the legacy emitter relies on.
            if (!conditionalShapeOk(symbol)) return false
        }
        // Phase 9 Step 4 — Cap 1 escalation: sealed roots with `@Payload` variants
        // continue to defer to legacy. The new SealedEmitter Cap 1 typed-lambda
        // dispatcher fan-out is in place (bare-lambda shape matching Cap 2's
        // top-level emit), but variant codecs are still emitted by legacy
        // `CodecGenerator` (called from `processSealedInterface`) with the
        // receiver-shape lambda `*Context.(ReadBuffer) -> P`. Routing the
        // dispatcher through the new pipeline while variants stay on legacy
        // produces a signature mismatch at compile time:
        //
        //     dispatcher: callee `(ReadBuffer) -> P`     (Cap 1 bare)
        //     variant:    declared `*Context.(ReadBuffer) -> P` (legacy receiver)
        //
        // Step 5/6 ports sealed variant codec emission to the new pipeline
        // (bringing `processSealedInterface` to call `tryPipeline` for each
        // variant). Until then this defer keeps both dispatcher and variants
        // on legacy together, matching the receiver-shape calling convention
        // end-to-end.
        if (symbol is com.ditchoom.buffer.codec.processor.discovery.RawSymbol.SealedRoot) {
            val discovery = cachedDiscovery
            if (discovery != null) {
                val anyVariantHasPayloadTp =
                    symbol.subclassFqns.any { childFqn ->
                        val child =
                            discovery.symbols.firstOrNull { it.fqn == childFqn }
                                as? com.ditchoom.buffer.codec.processor.discovery.RawSymbol.DataLike
                                ?: return@any false
                        child.typeParameters.any { tp ->
                            tp.annotations.any { it.fqn == "com.ditchoom.buffer.codec.annotations.Payload" }
                        }
                    }
                if (anyVariantHasPayloadTp) return false
            }
        }
        // Phase 9 Step 3 conservative defer: classes that depend on the new
        // value-class auto-detect path stay on legacy until Step 5 reconciles
        // sealed-dispatcher peek emission with legacy's `allVariantsSupportPeek`
        // gating. Without this defer, Step 3 flips sealed roots like `MqttPacket`
        // (whose variants are themselves `@JvmInline value class`) from legacy
        // to the new pipeline, but the new SealedEmitter's `variantSupportsPeek`
        // is stricter than legacy's gate — `MIN_HEADER_BYTES` and `peekFrameSize`
        // are silently omitted, breaking consumers that stream-decode via the
        // sealed dispatcher. The detection itself (FieldStrategy.ValueClass +
        // RawClassMetadata.valueClassInfo) is in place and verified by
        // `ValueClassFieldTest`; this gate keeps routing identical to pre-Step 3.
        val externals = cachedDiscovery?.externalClasses.orEmpty()
        fun hasExternalValueClassField(
            s: com.ditchoom.buffer.codec.processor.discovery.RawSymbol.DataLike,
        ): Boolean = s.constructorParameters.any { p -> externals[p.typeRef.fqn]?.valueClassInfo != null }
        if (symbol is com.ditchoom.buffer.codec.processor.discovery.RawSymbol.DataLike) {
            if (symbol.classKind ==
                com.ditchoom.buffer.codec.processor.discovery.DataLikeKind.ValueClass
            ) {
                return false
            }
            if (hasExternalValueClassField(symbol)) return false
        }
        if (symbol is com.ditchoom.buffer.codec.processor.discovery.RawSymbol.SealedRoot) {
            val discovery = cachedDiscovery
            if (discovery != null) {
                for (childFqn in symbol.subclassFqns) {
                    val child = discovery.symbols.firstOrNull { it.fqn == childFqn } ?: continue
                    if (child is com.ditchoom.buffer.codec.processor.discovery.RawSymbol.DataLike) {
                        if (child.classKind ==
                            com.ditchoom.buffer.codec.processor.discovery.DataLikeKind.ValueClass
                        ) {
                            return false
                        }
                        if (hasExternalValueClassField(child)) return false
                    }
                }
            }
        }
        return true
    }

    /**
     * Cheap pre-flight that mirrors a subset of legacy `ConditionalValidator`'s rules
     * without re-running PhaseB / FieldAnalyzer. Returns `false` when the class would
     * trip one of the legacy errors below; the caller then routes the symbol through
     * the legacy emitter so its diagnostic surfaces unchanged:
     *
     *  * Conditional field is non-nullable (`@WhenTrue`/`@WhenRemaining`/`@When` requires `T?`).
     *  * Conditional field has no default value (forces a `null` default for cascade null-checks).
     *  * `@WhenRemaining(minBytes <= 0)` (legacy requires strictly positive).
     *  * `@WhenRemaining` field followed by a non-conditional field (must be a tail group).
     */
    private fun conditionalShapeOk(symbol: com.ditchoom.buffer.codec.processor.discovery.RawSymbol.DataLike): Boolean {
        val whenAnnotations =
            setOf(
                "com.ditchoom.buffer.codec.annotations.When",
                "com.ditchoom.buffer.codec.annotations.WhenTrue",
                "com.ditchoom.buffer.codec.annotations.WhenRemaining",
            )
        var seenWhenRemaining = false
        for (p in symbol.constructorParameters) {
            val condAnns = p.annotations.filter { it.fqn in whenAnnotations }
            val isWhenRemaining = condAnns.any { it.fqn.endsWith("WhenRemaining") }
            if (condAnns.isEmpty()) {
                if (seenWhenRemaining) return false // tail-position violation
                continue
            }
            // Conditional field must be nullable
            if (!p.typeRef.isNullable) return false
            // Conditional field must have a default value
            if (!p.hasDefault) return false
            if (isWhenRemaining) {
                seenWhenRemaining = true
                val ann = condAnns.first { it.fqn.endsWith("WhenRemaining") }
                val minVal =
                    ann.arguments["minBytes"] as?
                        com.ditchoom.buffer.codec.processor.discovery.RawAnnotationValue.IntVal
                val v = minVal?.value ?: 0
                if (v <= 0) return false
            }
        }
        return true
    }

    /**
     * Phase 9 Slice 1 pipeline driver.
     *
     * Routes a single [classDecl] through Discovery → PlanBuilder → Validator →
     * [CodecEmitter] when the resulting [Plan] is `coverable`. Returns:
     *  * `true` — the new pipeline owns this symbol; caller MUST skip the legacy path.
     *    Either the file was emitted successfully, OR the plan was coverable but a
     *    Validator error was surfaced (legacy would emit the same error).
     *  * `false` — fall through to the legacy emitter. Either the symbol isn't
     *    coverable, the symbol wasn't found in Discovery output, the registry could
     *    not resolve a referenced type, or PhaseB failed (the user-facing diagnostic
     *    is already on the legacy path; we let it speak).
     */
    private fun tryPipeline(
        classDecl: KSClassDeclaration,
        resolver: Resolver,
    ): Boolean {
        val fqn = classDecl.qualifiedName?.asString() ?: return false
        val discovery = cachedDiscovery ?: Discovery.run(resolver).also { cachedDiscovery = it }
        val symbol = discovery.symbols.firstOrNull { it.fqn == fqn } ?: return false
        if (!pipelineEligible(symbol)) return false
        val scope = discovery.symbols.associateBy { it.fqn }

        val planResult =
            PlanBuilder.build(
                symbol = symbol,
                scope = scope,
                externalClasses = discovery.externalClasses,
            )
        val plan =
            when (planResult) {
                is Either.Left ->
                    // Plan-builder errors. The legacy emitter will surface the same conditions
                    // through its own diagnostics; let it speak so we don't silently mask
                    // problems. Diagnostic duplication is acceptable in Slice 1; deduped in Slice 5.
                    return false
                is Either.Right -> planResult.value
            }

        if (!coverable(plan)) return false

        // Whole-program validation — Slice 1 runs it on the single-plan map. Validator
        // accumulates errors instead of failing fast, so duplicate diagnostics during the
        // gradual cutover are bounded.
        val validation =
            Validator.validate(
                plans = mapOf(TypeFqn(symbol.fqn) to plan),
                externalClasses = discovery.externalClasses,
            )
        when (validation) {
            is Either.Left -> {
                for (err in validation.value.all) {
                    logger.error(err.message, classDecl)
                }
                // The pipeline owns this symbol; legacy would re-run the same checks. We
                // signal "skip legacy" via `true` so the build fails on a single error path
                // rather than two.
                return true
            }
            is Either.Right -> Unit
        }

        // Build a TypeRegistry from the discovered symbols + external metadata so the
        // emitter can resolve every TypeFqn it references. Slice 1's whitelist (Object_
        // + Primitive Leaf) only references the user-declared class itself plus
        // `kotlin.*` primitives, all of which the default fallback resolves correctly.
        val registry = buildRegistry(discovery)
        val classType = registry.resolve(TypeFqn(symbol.fqn))
        val emitter = CodecEmitter(registry)
        val fileSpec =
            try {
                emitter.emit(plan, classType)
            } catch (t: Throwable) {
                // Defensive guard: if the emitter throws (e.g. Slice 1 hits a strategy
                // path that raises), fall through to legacy rather than crash the round.
                logger.warn(
                    "Slice 1 pipeline failed to emit '${classDecl.simpleName.asString()}' (${t.message}); " +
                        "falling back to legacy emitter.",
                    classDecl,
                )
                return false
            }
        val supplemental =
            try {
                emitter.emitSupplemental(plan, classType)
            } catch (t: Throwable) {
                logger.warn(
                    "Phase 9 supplemental emission failed for '${classDecl.simpleName.asString()}' " +
                        "(${t.message}); falling back to legacy emitter.",
                    classDecl,
                )
                return false
            }

        // Match the legacy file-header conventions (4-space indent + `@file:Suppress("ktlint")`)
        // so generated source remains byte-for-byte stable through the cutover.
        val rebuiltFileSpec = withLegacyFileHeader(fileSpec)

        val containingFile = classDecl.containingFile
        val deps =
            if (containingFile != null) {
                Dependencies(aggregating = false, sources = arrayOf(containingFile))
            } else {
                Dependencies(aggregating = false)
            }
        rebuiltFileSpec.writeTo(codeGenerator, deps)
        for (extra in supplemental) {
            withLegacyFileHeader(extra).writeTo(codeGenerator, deps)
        }
        return true
    }

    private fun buildRegistry(discovery: DiscoveryResult): TypeRegistry {
        val explicit = mutableMapOf<TypeFqn, com.squareup.kotlinpoet.ClassName>()
        for (s in discovery.symbols) {
            val pkg = s.packageName
            val parts = s.enclosingNames
            val cn =
                if (parts.isEmpty()) {
                    com.squareup.kotlinpoet.ClassName(pkg, s.simpleName)
                } else {
                    com.squareup.kotlinpoet.ClassName(pkg, *parts.toTypedArray())
                }
            explicit[TypeFqn(s.fqn)] = cn
        }
        return TypeRegistry(explicit)
    }

    /**
     * Re-emit [original] with the legacy emitter's file-header conventions:
     * `@file:Suppress("ktlint")` and 4-space indent. KotlinPoet `FileSpec` is immutable
     * so we drop down to the toBuilder() API and re-apply.
     */
    private fun withLegacyFileHeader(original: FileSpec): FileSpec =
        original
            .toBuilder()
            .indent("    ")
            .also { b ->
                val alreadyHasSuppress =
                    b.annotations.any { ann ->
                        ann.typeName.toString().endsWith("kotlin.Suppress")
                    }
                if (!alreadyHasSuppress) {
                    b.addAnnotation(
                        AnnotationSpec
                            .builder(Suppress::class)
                            .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                            .addMember("%S", "ktlint")
                            .build(),
                    )
                }
            }.build()

    private fun processDataClass(
        classDeclaration: KSClassDeclaration,
        resolver: Resolver,
    ) {
        val fieldAnalyzer = FieldAnalyzer(logger, customProviders)
        val fields = fieldAnalyzer.analyze(classDeclaration)
        if (fields == null) return // errors already reported

        // Resolve direction: extract explicit annotation, validate against fields
        val explicit = extractExplicitDirection(classDeclaration)
        val direction = validateDirection(classDeclaration, fields, explicit) ?: return

        val batchOptimizer = BatchOptimizer()
        val batches = batchOptimizer.optimize(fields)

        val hasPayload = fields.any { it.strategy is FieldReadStrategy.PayloadField }

        val generator = CodecGenerator(codeGenerator, logger)
        generator.generate(classDeclaration, fields, batches, hasPayload, direction)

        if (hasPayload) {
            PayloadContextGenerator(codeGenerator, logger).generate(classDeclaration, fields)
        }
    }

    /**
     * Generates a codec for a `@ProtocolMessage` `object` (or `data object`).
     * Wire format is zero bytes — decode returns the singleton, encode writes nothing,
     * sizeOf is 0. Type-only sealed variants (e.g., protocol commands with no payload)
     * are the primary use case.
     */
    private fun processObject(classDeclaration: KSClassDeclaration) {
        if (classDeclaration.typeParameters.isNotEmpty()) {
            logger.error(
                "@ProtocolMessage object '${classDeclaration.simpleName.asString()}' cannot have type parameters. " +
                    "If you need @Payload, use a data class instead.",
                classDeclaration,
            )
            return
        }
        val dispatchOnAnnotation =
            classDeclaration.annotations.find {
                it.qualifiedName() == "com.ditchoom.buffer.codec.annotations.DispatchOn"
            }
        if (dispatchOnAnnotation != null) {
            logger.error(
                "@DispatchOn is not valid on an object. @DispatchOn annotates a sealed interface that holds " +
                    "variants distinguished by a discriminator.",
                classDeclaration,
            )
            return
        }
        val generator = CodecGenerator(codeGenerator, logger)
        generator.generate(classDeclaration, fields = emptyList(), batches = emptyList(), hasPayload = false)
    }

    private fun processSealedInterface(
        classDeclaration: KSClassDeclaration,
        resolver: Resolver,
    ) {
        val sealedSubclasses = classDeclaration.getSealedSubclasses().toList()
        if (sealedSubclasses.isEmpty()) {
            logger.error(
                "Sealed interface '${classDeclaration.simpleName.asString()}' has no subclasses. " +
                    "Add at least one data class that implements this sealed interface.",
                classDeclaration,
            )
            return
        }

        // Check for @DispatchOn annotation
        val dispatchOnInfoRaw = resolveDispatchOn(classDeclaration)
        val sealedName = classDeclaration.simpleName.asString()
        val sealedPackage = classDeclaration.packageName.asString()
        val dispatchOnInfo =
            dispatchOnInfoRaw?.copy(
                sealedCodecSimpleName = "${sealedName}Codec",
                sealedPackage = sealedPackage,
            )

        // Extract class-level wireOrder from the sealed interface for inheritance
        val sealedFieldAnalyzer = FieldAnalyzer(logger, customProviders)
        val sealedWireOrder = sealedFieldAnalyzer.extractClassWireOrderPublic(classDeclaration)

        // Phase 1: Analyze and generate sub-codecs, collecting payload and direction metadata
        val variantPayloadInfos = mutableListOf<SealedVariantPayloadInfo>()
        val variantsHandlingDiscriminator = mutableSetOf<String>()
        val variantsSupportingPeek = mutableSetOf<String>()
        val variantDirections = mutableListOf<Pair<KSClassDeclaration, CodecDirection>>()
        for (subclass in sealedSubclasses) {
            val qualifiedName = subclass.qualifiedName?.asString() ?: continue
            if (qualifiedName in processed) continue
            processed.add(qualifiedName)

            val isObjectVariant = subclass.classKind == ClassKind.OBJECT
            if (!isObjectVariant) {
                val constructor = subclass.primaryConstructor
                if (constructor == null) {
                    logger.error(
                        "Sealed variant '${subclass.simpleName.asString()}' of " +
                            "'${classDeclaration.simpleName.asString()}' must have a primary constructor " +
                            "with val parameters.",
                        subclass,
                    )
                    continue
                }
                if (constructor.parameters.isEmpty()) {
                    logger.error(
                        "Sealed variant '${subclass.simpleName.asString()}' of " +
                            "'${classDeclaration.simpleName.asString()}' must have at least one val parameter " +
                            "in its primary constructor. For a type-only variant (no payload), " +
                            "declare it as 'object ${subclass.simpleName.asString()}' or " +
                            "'data object ${subclass.simpleName.asString()}'.",
                        subclass,
                    )
                    continue
                }
            }

            // Analyze fields to detect @Payload and discriminator fields.
            // Object variants skip analysis (no constructor params) and produce an empty field list.
            val fields =
                if (isObjectVariant) {
                    emptyList()
                } else {
                    val fieldAnalyzer = FieldAnalyzer(logger, customProviders)
                    fieldAnalyzer.analyze(subclass, dispatchOnInfo, sealedWireOrder) ?: continue
                }

            // Resolve variant direction
            val variantExplicit = extractExplicitDirection(subclass)
            val variantDirection = validateDirection(subclass, fields, variantExplicit) ?: continue
            variantDirections.add(subclass to variantDirection)

            if (fields.any { it.strategy is FieldReadStrategy.DiscriminatorField }) {
                variantsHandlingDiscriminator.add(qualifiedName)
            }
            val payloadFields = fields.filter { it.strategy is FieldReadStrategy.PayloadField }
            val payloadInfos =
                payloadFields.map { field ->
                    val strategy = field.strategy as FieldReadStrategy.PayloadField
                    PayloadFieldInfo(
                        fieldName = field.name,
                        typeParamName = strategy.typeParamName,
                        contextClassName = "${subclass.enclosingSimpleNames().joinToString("")}Context",
                    )
                }
            variantPayloadInfos.add(SealedVariantPayloadInfo(subclass, payloadInfos))

            // Track whether this variant supports peekFrameSize
            if (PeekFrameSizeEmitter.generate(fields) != null) {
                val name = subclass.qualifiedName?.asString() ?: subclass.simpleName.asString()
                variantsSupportingPeek.add(name)
            }

            // Phase 9 Step 4-redo C6 — try the new pipeline for the variant
            // codec. When `tryPipeline` succeeds the new emitters write both
            // the codec and (for `WithPayload` variants) the synthesised
            // `*Context` companion class via CodecEmitter.emitSupplemental,
            // matching the receiver-style calling convention legacy already
            // used. The legacy state tracking above (variantPayloadInfos,
            // variantsHandlingDiscriminator, variantsSupportingPeek) stays
            // populated unconditionally so the SealedDispatchGenerator
            // fallback below still has the data it needs when the parent
            // dispatcher itself falls back to legacy.
            //
            // While the Cap 1 / Cap 2 defers in [pipelineEligible] remain
            // (dropped in C7), `tryPipeline` returns false for payload
            // variants and we fall through to the legacy codec emit. After
            // C7 drops the defers, payload variants flow through the new
            // pipeline and the legacy variant emit is skipped — at which
            // point the dispatcher (whether new-pipeline or legacy fallback)
            // calls into a new-pipeline-emitted variant codec whose
            // signature matches receiver-style end-to-end.
            if (tryPipeline(subclass, resolver)) continue

            // Generate the sub-codec
            val batches = BatchOptimizer().optimize(fields)
            val hasPayload = payloadFields.isNotEmpty()
            CodecGenerator(codeGenerator, logger).generate(subclass, fields, batches, hasPayload, variantDirection)
            if (hasPayload) {
                PayloadContextGenerator(codeGenerator, logger).generate(subclass, fields)
            }
        }

        // Compute sealed interface direction from variants
        val sealedDirection = computeSealedDirection(classDeclaration, variantDirections) ?: return

        // Resolve the configured unknown-discriminator exception (FQN must exist + have (String) ctor).
        val onUnknownFqn = extractOnUnknownDiscriminator(classDeclaration)
        val resolvedUnknownException =
            if (onUnknownFqn.isEmpty()) {
                "kotlin.IllegalArgumentException"
            } else {
                val cls = resolver.getClassDeclarationByName(resolver.getKSNameFromString(onUnknownFqn))
                if (cls == null) {
                    logger.error(
                        "@ProtocolMessage(onUnknownDiscriminator = \"$onUnknownFqn\") on " +
                            "'${classDeclaration.simpleName.asString()}' did not resolve to a class on the " +
                            "compilation classpath. Provide a fully-qualified name visible to this module.",
                        classDeclaration,
                    )
                    return
                }
                val hasStringCtor =
                    cls.getConstructors().any { ctor ->
                        ctor.parameters.size == 1 &&
                            ctor.parameters
                                .first()
                                .type
                                .resolve()
                                .declaration.qualifiedName
                                ?.asString() == "kotlin.String"
                    }
                if (!hasStringCtor) {
                    logger.error(
                        "@ProtocolMessage(onUnknownDiscriminator = \"$onUnknownFqn\") on " +
                            "'${classDeclaration.simpleName.asString()}' resolves but the class does not declare " +
                            "a single-`String` constructor. The generated dispatcher passes a message string, so " +
                            "the exception class must accept it: `class ${cls.simpleName.asString()}(message: String)`.",
                        classDeclaration,
                    )
                    return
                }
                onUnknownFqn
            }

        // Slice 4: when the sealed root's plan is coverable, route the dispatcher
        // through the new pipeline. Variant codecs always continue to come from
        // the legacy emitter (they're already emitted above) — this slice only
        // moves the dispatcher itself, with variant codec signatures held stable
        // by both emitters.
        if (tryPipeline(classDeclaration, resolver)) return

        // Phase 2: Generate the dispatch codec with payload awareness
        val generator = SealedDispatchGenerator(codeGenerator, logger)
        generator.generate(
            classDeclaration,
            sealedSubclasses,
            variantPayloadInfos,
            dispatchOnInfo,
            variantsHandlingDiscriminator = variantsHandlingDiscriminator,
            variantsSupportingPeek = variantsSupportingPeek,
            direction = sealedDirection,
            onUnknownDiscriminator = resolvedUnknownException,
        )
    }

    /** Reads the `onUnknownDiscriminator` FQN from `@ProtocolMessage`, or `""` if unset/default. */
    private fun extractOnUnknownDiscriminator(classDecl: KSClassDeclaration): String {
        val ann =
            classDecl.annotations.find {
                it.qualifiedName() == "com.ditchoom.buffer.codec.annotations.ProtocolMessage"
            } ?: return ""
        return ann.arguments.find { it.name?.asString() == "onUnknownDiscriminator" }?.value as? String ?: ""
    }

    /**
     * Resolves @DispatchOn annotation on a sealed interface.
     * Finds the discriminator type, validates it has exactly one @DispatchValue property,
     * and returns the dispatch info.
     */
    private fun resolveDispatchOn(classDeclaration: KSClassDeclaration): DispatchOnInfo? {
        val dispatchOnAnnotation =
            classDeclaration.annotations.find {
                it.qualifiedName() == "com.ditchoom.buffer.codec.annotations.DispatchOn"
            } ?: return null

        val typeArg = dispatchOnAnnotation.arguments.first().value as? KSType
        if (typeArg == null) {
            logger.error("@DispatchOn requires a type argument.", classDeclaration)
            return null
        }

        val discriminatorClass = typeArg.declaration as? KSClassDeclaration
        if (discriminatorClass == null) {
            logger.error(
                "@DispatchOn type must reference a class, got '${typeArg.declaration.simpleName.asString()}'.",
                classDeclaration,
            )
            return null
        }

        // Find @DispatchValue property
        val dispatchValueProps =
            discriminatorClass
                .getAllProperties()
                .filter { prop ->
                    prop.annotations.any {
                        it.qualifiedName() == "com.ditchoom.buffer.codec.annotations.DispatchValue"
                    }
                }.toList()

        if (dispatchValueProps.isEmpty()) {
            logger.error(
                "@DispatchOn(${discriminatorClass.simpleName.asString()}::class) requires exactly one " +
                    "@DispatchValue property in '${discriminatorClass.simpleName.asString()}', but none was found.",
                classDeclaration,
            )
            return null
        }
        if (dispatchValueProps.size > 1) {
            logger.error(
                "@DispatchOn(${discriminatorClass.simpleName.asString()}::class) requires exactly one " +
                    "@DispatchValue property, but found ${dispatchValueProps.size}: " +
                    dispatchValueProps.joinToString { it.simpleName.asString() },
                classDeclaration,
            )
            return null
        }

        val dispatchProp = dispatchValueProps.first()
        val dispatchPropType =
            dispatchProp.type
                .resolve()
                .declaration.simpleName
                .asString()
        if (dispatchPropType != "Int") {
            logger.error(
                "@DispatchValue property '${dispatchProp.simpleName.asString()}' must return Int, " +
                    "but returns $dispatchPropType.",
                dispatchProp,
            )
            return null
        }
        val codecName = discriminatorClass.codecName()
        val poetClassName = discriminatorClass.toPoetClassName()

        // Determine the inner type of the value class for constructing during encode
        val constructorKsParams = discriminatorClass.primaryConstructor?.parameters ?: emptyList()
        val innerType = constructorKsParams.firstOrNull()
        val innerTypeName =
            innerType
                ?.type
                ?.resolve()
                ?.declaration
                ?.simpleName
                ?.asString() ?: "UByte"
        val isValueClass =
            constructorKsParams.size == 1 &&
                ValueClassAnalyzer.isValueClass(discriminatorClass)
        // innerPropertyName is only meaningful for value-class discriminators; for data-class
        // discriminators it stays null and range emission (which would need it) is rejected by
        // KSP earlier.
        val innerPropertyName = if (isValueClass) innerType?.name?.asString() else null

        // Build constructor parameter metadata for data class discriminator peeking
        val discriminatorParams =
            constructorKsParams.mapNotNull { param ->
                val paramTypeName =
                    param.type
                        .resolve()
                        .declaration
                        .qualifiedName
                        ?.asString() ?: return@mapNotNull null
                val primitive = Primitive.fromTypeName(paramTypeName) ?: return@mapNotNull null
                DiscriminatorParam(
                    name = param.name?.asString() ?: return@mapNotNull null,
                    typeName = primitive.typeName,
                    wireBytes = primitive.defaultWireBytes,
                )
            }

        // Resolve the framing strategy:
        //   - `framing = DispatchFraming.Inherit::class` (default) → check the discriminator's
        //     companion object; if it implements DispatchFraming<D>, capture its FQN.
        //   - explicit framer class → require it to be a Kotlin `object` implementing
        //     DispatchFraming<D>; capture its FQN.
        //   - no framer discovered (sentinel + no companion framer) → unframed dispatch.
        val framingTypeArg =
            dispatchOnAnnotation.arguments.find { it.name?.asString() == "framing" }?.value as? KSType
        val framingClass = framingTypeArg?.declaration as? KSClassDeclaration
        val framingClassFqn = framingClass?.qualifiedName?.asString()
        val isInheritSentinel =
            framingClass == null ||
                framingClassFqn == "com.ditchoom.buffer.codec.DispatchFraming.Inherit"

        val resolvedFramer: ResolvedFramer? =
            if (isInheritSentinel) {
                // Walk the discriminator's companion (if any) and check if it implements
                // DispatchFraming<DiscriminatorType>. When found, emit calls via the
                // enclosing class name (Kotlin auto-routes to the companion) so generated
                // code reads `MyTag.readBodyLength(...)` rather than `MyTag.Companion.readBodyLength(...)`.
                val companion =
                    discriminatorClass.declarations
                        .filterIsInstance<KSClassDeclaration>()
                        .firstOrNull { it.isCompanionObject }
                val kind = companion?.let { detectFramingKind(it, discriminatorClass) }
                if (companion != null && kind != null) {
                    ResolvedFramer(
                        fqn = discriminatorClass.qualifiedName?.asString() ?: return null,
                        isBodyLength = kind.isBodyLength,
                    )
                } else {
                    null
                }
            } else {
                // Explicit framer must be a Kotlin `object` implementing DispatchFraming<D>.
                // `isInheritSentinel == false` proves `framingClass != null`. The class's FQN
                // can still be null if KSP couldn't resolve the reference (user-side compile
                // error); bail rather than NPE.
                val explicit = framingClass
                val explicitFqn = explicit.qualifiedName?.asString() ?: return null
                if (explicit.classKind != ClassKind.OBJECT) {
                    logger.error(
                        "@DispatchOn(framing = ${explicit.simpleName.asString()}::class) on " +
                            "'${classDeclaration.simpleName.asString()}' must reference a Kotlin `object` " +
                            "(companion or named) implementing " +
                            "DispatchFraming<${discriminatorClass.simpleName.asString()}>. " +
                            "${explicit.simpleName.asString()} is not an object.",
                        classDeclaration,
                    )
                    return null
                }
                val kind = detectFramingKind(explicit, discriminatorClass)
                if (kind == null) {
                    logger.error(
                        "@DispatchOn(framing = ${explicit.simpleName.asString()}::class) on " +
                            "'${classDeclaration.simpleName.asString()}' must implement " +
                            "DispatchFraming<${discriminatorClass.simpleName.asString()}> or " +
                            "BodyLengthFraming<${discriminatorClass.simpleName.asString()}>. " +
                            "Found framer that does not match the discriminator type.",
                        classDeclaration,
                    )
                    return null
                }
                ResolvedFramer(fqn = explicitFqn, isBodyLength = kind.isBodyLength)
            }

        // Compute discriminator wire bytes (used for peek minimum-bytes math in framed paths).
        val discriminatorBytes =
            if (discriminatorParams.isNotEmpty()) {
                discriminatorParams.sumOf { it.wireBytes }
            } else {
                Primitive.fromTypeName("kotlin.$innerTypeName")?.defaultWireBytes ?: 1
            }
        val framingInfo =
            resolvedFramer?.let {
                DispatchFramingInfo(
                    framerFqn = it.fqn,
                    discriminatorBytes = discriminatorBytes,
                    isBodyLength = it.isBodyLength,
                )
            }

        return DispatchOnInfo(
            typeName = discriminatorClass.qualifiedName?.asString() ?: discriminatorClass.simpleName.asString(),
            codecName = codecName,
            dispatchProperty = dispatchProp.simpleName.asString(),
            poetClassName = poetClassName,
            innerTypeName = innerTypeName,
            innerPropertyName = innerPropertyName,
            isValueClass = isValueClass,
            constructorParams = discriminatorParams,
            framing = framingInfo,
        )
    }

    /**
     * Inspects [candidate]'s **directly-declared** supertypes for a framer interface
     * parameterized over [discriminator]. Returns:
     *
     *  * [FramingKind.BodyLength] when `BodyLengthFraming<D>` is in the directly-declared list.
     *  * [FramingKind.PeekOnly] when only `DispatchFraming<D>` is in the list (no body slicing).
     *  * `null` when neither matches the discriminator type.
     *
     * Walks only directly-declared supertypes — never `getAllSuperTypes()` — because KSP's
     * transitive walker returns unresolved type variables on inherited generic supertypes
     * (the bug that broke commits `ada9796` on buffer + `cd245818` on mqtt, both reverted).
     */
    private fun detectFramingKind(
        candidate: KSClassDeclaration,
        discriminator: KSClassDeclaration,
    ): FramingKind? {
        val discriminatorFqn = discriminator.qualifiedName?.asString() ?: return null
        var sawBodyLength = false
        var sawPeekOnly = false
        for (superTypeRef in candidate.superTypes) {
            val superType = superTypeRef.resolve()
            val decl = superType.declaration as? KSClassDeclaration ?: continue
            val superFqn = decl.qualifiedName?.asString() ?: continue
            val firstArg =
                superType.arguments
                    .firstOrNull()
                    ?.type
                    ?.resolve() ?: continue
            val argFqn = (firstArg.declaration as? KSClassDeclaration)?.qualifiedName?.asString() ?: continue
            if (argFqn != discriminatorFqn) continue
            when (superFqn) {
                "com.ditchoom.buffer.codec.BodyLengthFraming" -> sawBodyLength = true
                "com.ditchoom.buffer.codec.DispatchFraming" -> sawPeekOnly = true
            }
        }
        return when {
            sawBodyLength -> FramingKind.BodyLength
            sawPeekOnly -> FramingKind.PeekOnly
            else -> null
        }
    }

    private enum class FramingKind(
        val isBodyLength: Boolean,
    ) {
        PeekOnly(false),
        BodyLength(true),
    }

    private data class ResolvedFramer(
        val fqn: String,
        val isBodyLength: Boolean,
    )

    // ──────────────────────── Sealed direction cascading ────────────────────────

    /**
     * Computes the direction for a sealed dispatch codec by combining variant directions
     * with the sealed interface's own explicit direction annotation.
     */
    private fun computeSealedDirection(
        sealedDecl: KSClassDeclaration,
        variantDirections: List<Pair<KSClassDeclaration, CodecDirection>>,
    ): CodecDirection? {
        val sealedName = sealedDecl.simpleName.asString()
        val explicit = extractExplicitDirection(sealedDecl)

        // Infer from variants
        val hasDecodeOnly = variantDirections.any { it.second == CodecDirection.DecodeOnly }
        val hasEncodeOnly = variantDirections.any { it.second == CodecDirection.EncodeOnly }

        if (hasDecodeOnly && hasEncodeOnly) {
            val dVariants =
                variantDirections
                    .filter { it.second == CodecDirection.DecodeOnly }
                    .joinToString(", ") { "'${it.first.simpleName.asString()}'" }
            val eVariants =
                variantDirections
                    .filter { it.second == CodecDirection.EncodeOnly }
                    .joinToString(", ") { "'${it.first.simpleName.asString()}'" }
            logger.error(
                "Sealed interface '$sealedName' has both decode-only variants [$dVariants] " +
                    "and encode-only variants [$eVariants]. These are incompatible.\n" +
                    "To fix, either:\n" +
                    "  • Make all variant codecs bidirectional\n" +
                    "  • Split into separate sealed interfaces for each direction",
                sealedDecl,
            )
            return null
        }

        val inferred =
            when {
                hasDecodeOnly -> CodecDirection.DecodeOnly
                hasEncodeOnly -> CodecDirection.EncodeOnly
                else -> CodecDirection.Bidirectional
            }

        // Validate explicit direction against inferred
        return when (explicit) {
            "Infer" -> inferred
            "Codec" -> {
                val unidirectional = variantDirections.filter { it.second != CodecDirection.Bidirectional }
                if (unidirectional.isNotEmpty()) {
                    val details =
                        unidirectional.joinToString("\n  • ") {
                            val dir = if (it.second == CodecDirection.DecodeOnly) "decode-only" else "encode-only"
                            "'${it.first.simpleName.asString()}' ($dir)"
                        }
                    logger.error(
                        "@ProtocolMessage(direction = Codec) on sealed interface '$sealedName' requires all " +
                            "variants to be bidirectional, but these are not:\n  • $details\n" +
                            "To fix, either:\n" +
                            "  • Make those variants bidirectional\n" +
                            "  • Change direction to DecodeOnly or EncodeOnly",
                        sealedDecl,
                    )
                    null
                } else {
                    CodecDirection.Bidirectional
                }
            }
            "DecodeOnly" -> {
                val eVariants = variantDirections.filter { it.second == CodecDirection.EncodeOnly }
                if (eVariants.isNotEmpty()) {
                    val details = eVariants.joinToString(", ") { "'${it.first.simpleName.asString()}'" }
                    logger.error(
                        "@ProtocolMessage(direction = DecodeOnly) on sealed interface '$sealedName' " +
                            "conflicts with encode-only variants: $details.\n" +
                            "To fix: make those variants bidirectional or remove the encode-only constraint",
                        sealedDecl,
                    )
                    null
                } else {
                    CodecDirection.DecodeOnly
                }
            }
            "EncodeOnly" -> {
                val dVariants = variantDirections.filter { it.second == CodecDirection.DecodeOnly }
                if (dVariants.isNotEmpty()) {
                    val details = dVariants.joinToString(", ") { "'${it.first.simpleName.asString()}'" }
                    logger.error(
                        "@ProtocolMessage(direction = EncodeOnly) on sealed interface '$sealedName' " +
                            "conflicts with decode-only variants: $details.\n" +
                            "To fix: make those variants bidirectional or remove the decode-only constraint",
                        sealedDecl,
                    )
                    null
                } else {
                    CodecDirection.EncodeOnly
                }
            }
            else -> inferred
        }
    }

    // ──────────────────────── Direction inference & validation ────────────────────────

    /**
     * Returns the explicit direction signal as an enum name string ("Codec", "DecodeOnly",
     * "EncodeOnly") or "Infer" when no class-level signal is present. Class-level
     * `@Decode` / `@Encode` markers take precedence over the legacy
     * `@ProtocolMessage(direction = ...)` parameter.
     */
    private fun extractExplicitDirection(classDecl: KSClassDeclaration): String {
        val hasDecodeMarker =
            classDecl.annotations.any {
                it.qualifiedName() == "com.ditchoom.buffer.codec.annotations.Decode"
            }
        val hasEncodeMarker =
            classDecl.annotations.any {
                it.qualifiedName() == "com.ditchoom.buffer.codec.annotations.Encode"
            }
        if (hasDecodeMarker && hasEncodeMarker) return "Infer" // PhaseB validator surfaces the conflict
        if (hasDecodeMarker) return "DecodeOnly"
        if (hasEncodeMarker) return "EncodeOnly"

        val ann =
            classDecl.annotations.find {
                it.qualifiedName() == "com.ditchoom.buffer.codec.annotations.ProtocolMessage"
            } ?: return "Infer"
        val dirArg = ann.arguments.find { it.name?.asString() == "direction" }?.value ?: return "Infer"
        // KSP represents enum annotation values in different ways depending on the KSP version.
        // Use toString() and extract the enum name, matching the pattern used for wireOrder.
        // The `Default` enum value (replacing the dropped `Infer` value) is the "no explicit
        // signal" sentinel and maps back to "Infer" for the validator's existing branches.
        val name = dirArg.toString().substringAfterLast(".")
        return if (name == "Default") "Infer" else name
    }

    /** Computes the direction implied by the fields (ignoring explicit annotation). */
    private fun inferDirection(fields: List<FieldInfo>): CodecDirection {
        var hasDecodeOnly = false
        var hasEncodeOnly = false
        for (f in fields) {
            when (f.strategy.direction()) {
                CodecDirection.DecodeOnly -> hasDecodeOnly = true
                CodecDirection.EncodeOnly -> hasEncodeOnly = true
                CodecDirection.Bidirectional -> {}
            }
        }
        return when {
            hasDecodeOnly && hasEncodeOnly -> CodecDirection.Bidirectional // conflict — caught in validate
            hasDecodeOnly -> CodecDirection.DecodeOnly
            hasEncodeOnly -> CodecDirection.EncodeOnly
            else -> CodecDirection.Bidirectional
        }
    }

    /**
     * Validates the explicit direction against the fields' inferred direction.
     * Returns the resolved [CodecDirection] or null if validation fails (errors already reported).
     */
    private fun validateDirection(
        classDecl: KSClassDeclaration,
        fields: List<FieldInfo>,
        explicit: String,
    ): CodecDirection? {
        val className = classDecl.simpleName.asString()

        fun fieldsWithDir(dir: CodecDirection) = fields.filter { it.strategy.direction() == dir }

        fun describeField(f: FieldInfo): String {
            val s = f.strategy
            val dir = if (s.direction() == CodecDirection.DecodeOnly) "decode-only" else "encode-only"
            val iface = if (s.direction() == CodecDirection.DecodeOnly) "Decoder" else "Encoder"
            val missing = if (s.direction() == CodecDirection.DecodeOnly) "Encoder" else "Decoder"
            return when (s) {
                is FieldReadStrategy.UseCodecField -> {
                    val short = s.codecName.substringAfterLast('.')
                    "'${f.name}' ($dir — $short implements $iface but not $missing)"
                }
                is FieldReadStrategy.NestedMessageField -> {
                    val short = s.codecName.removeSuffix("Codec")
                    "'${f.name}' ($dir — nested message $short is annotated $dir)"
                }
                is FieldReadStrategy.CollectionField -> {
                    val short = s.elementCodecName.removeSuffix("Codec")
                    "'${f.name}' ($dir — list element $short is $dir)"
                }
                else -> "'${f.name}' ($dir)"
            }
        }

        fun suggestFix(f: FieldInfo): String {
            val s = f.strategy
            val missingIface = if (s.direction() == CodecDirection.DecodeOnly) "Encoder" else "Decoder"
            return when (s) {
                is FieldReadStrategy.UseCodecField -> {
                    val short = s.codecName.substringAfterLast('.')
                    "make $short implement Codec<T> (or add $missingIface<T>)"
                }
                is FieldReadStrategy.NestedMessageField -> {
                    val short = s.codecName.removeSuffix("Codec")
                    "change $short to @ProtocolMessage(direction = Codec) or Infer"
                }
                is FieldReadStrategy.CollectionField -> {
                    val short = s.elementCodecName.removeSuffix("Codec")
                    "change $short to bidirectional"
                }
                else -> "add bidirectional support"
            }
        }

        return when (explicit) {
            "Infer" -> {
                val dFields = fieldsWithDir(CodecDirection.DecodeOnly)
                val eFields = fieldsWithDir(CodecDirection.EncodeOnly)
                if (dFields.isNotEmpty() && eFields.isNotEmpty()) {
                    val dDesc = dFields.joinToString(", ") { describeField(it) }
                    val eDesc = eFields.joinToString(", ") { describeField(it) }
                    logger.error(
                        "Cannot infer direction for '$className': it has both decode-only " +
                            "fields [$dDesc] and encode-only fields [$eDesc]. These are incompatible.\n" +
                            "To fix, either:\n" +
                            "  • Make all codecs bidirectional: " +
                            (dFields + eFields).joinToString("; ") { suggestFix(it) } +
                            "\n  • Split into separate decode-only and encode-only messages" +
                            "\n  • Explicitly annotate: @ProtocolMessage(direction = DecodeOnly) or EncodeOnly",
                        classDecl,
                    )
                    null
                } else {
                    inferDirection(fields)
                }
            }
            "Codec" -> {
                val unidirectional = fieldsWithDir(CodecDirection.DecodeOnly) + fieldsWithDir(CodecDirection.EncodeOnly)
                if (unidirectional.isNotEmpty()) {
                    val details = unidirectional.joinToString("\n  • ") { describeField(it) }
                    val fixes = unidirectional.joinToString("\n  • ") { suggestFix(it) }
                    logger.error(
                        "@ProtocolMessage(direction = Codec) on '$className' requires all fields to be " +
                            "bidirectional, but these are not:\n  • $details\n" +
                            "To fix, either:\n" +
                            "  • $fixes\n" +
                            "  • Change direction to DecodeOnly or EncodeOnly if bidirectional is not needed",
                        classDecl,
                    )
                    null
                } else {
                    CodecDirection.Bidirectional
                }
            }
            "DecodeOnly" -> {
                val eFields = fieldsWithDir(CodecDirection.EncodeOnly)
                if (eFields.isNotEmpty()) {
                    val details = eFields.joinToString("\n  • ") { describeField(it) }
                    val fixes = eFields.joinToString("\n  • ") { suggestFix(it) }
                    logger.error(
                        "@ProtocolMessage(direction = DecodeOnly) on '$className' conflicts with " +
                            "encode-only fields:\n  • $details\n" +
                            "A decode-only message cannot contain encode-only fields.\n" +
                            "To fix, either:\n" +
                            "  • $fixes\n" +
                            "  • Remove the encode-only fields from this message",
                        classDecl,
                    )
                    null
                } else {
                    CodecDirection.DecodeOnly
                }
            }
            "EncodeOnly" -> {
                val dFields = fieldsWithDir(CodecDirection.DecodeOnly)
                if (dFields.isNotEmpty()) {
                    val details = dFields.joinToString("\n  • ") { describeField(it) }
                    val fixes = dFields.joinToString("\n  • ") { suggestFix(it) }
                    logger.error(
                        "@ProtocolMessage(direction = EncodeOnly) on '$className' conflicts with " +
                            "decode-only fields:\n  • $details\n" +
                            "An encode-only message cannot contain decode-only fields.\n" +
                            "To fix, either:\n" +
                            "  • $fixes\n" +
                            "  • Remove the decode-only fields from this message",
                        classDecl,
                    )
                    null
                } else {
                    CodecDirection.EncodeOnly
                }
            }
            else -> CodecDirection.Bidirectional
        }
    }
}
