package com.ditchoom.buffer.codec.processor

import com.ditchoom.buffer.codec.processor.discovery.Discovery
import com.ditchoom.buffer.codec.processor.discovery.DiscoveryResult
import com.ditchoom.buffer.codec.processor.emitter.CodecEmitter
import com.ditchoom.buffer.codec.processor.emitter.TypeRegistry
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.planbuilder.Either
import com.ditchoom.buffer.codec.processor.planbuilder.PlanBuilder
import com.ditchoom.buffer.codec.processor.spi.CodecFieldProvider
import com.ditchoom.buffer.codec.processor.validator.Validator
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.ksp.writeTo

class ProtocolMessageProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    @Suppress("UNUSED_PARAMETER", "unused")
    customProviders: Map<String, CodecFieldProvider> = emptyMap(),
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

            // Phase 9 Slice 6: every `@ProtocolMessage` symbol flows through the new pipeline
            // unconditionally. Basic constructor sanity is still surfaced here so the user
            // sees the diagnostic adjacent to their declaration; the pipeline owns everything
            // else.
            when {
                Modifier.SEALED in symbol.modifiers -> {
                    val sealedSubclasses = symbol.getSealedSubclasses().toList()
                    if (sealedSubclasses.isEmpty()) {
                        logger.error(
                            "Sealed interface '${symbol.simpleName.asString()}' has no subclasses. " +
                                "Add at least one data class that implements this sealed interface.",
                            symbol,
                        )
                        continue
                    }
                    for (sub in sealedSubclasses) {
                        sub.qualifiedName?.asString()?.let { processed.add(it) }
                    }
                    runPipeline(symbol, resolver)
                }
                symbol.classKind == ClassKind.OBJECT -> {
                    runPipeline(symbol, resolver)
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
                        runPipeline(symbol, resolver)
                    }
                }
            }
        }
        return emptyList()
    }

    /**
     * Phase 9 Slice 6 pipeline driver.
     *
     * Routes a single [classDecl] through Discovery → PlanBuilder → Validator →
     * [CodecEmitter]. The legacy emitter is gone: every `@ProtocolMessage` symbol
     * must succeed through the pipeline. Returns:
     *  * `true` — the file was emitted successfully or a Validator error was logged.
     *  * `false` — the symbol could not be discovered or PlanBuilder failed; the
     *    PhaseB diagnostic (already logged) is the user-visible source.
     */
    private fun runPipeline(
        classDecl: KSClassDeclaration,
        resolver: Resolver,
    ): Boolean {
        val fqn = classDecl.qualifiedName?.asString() ?: return false
        val discovery = cachedDiscovery ?: Discovery.run(resolver).also { cachedDiscovery = it }
        val symbol = discovery.symbols.firstOrNull { it.fqn == fqn } ?: return false
        val scope = discovery.symbols.associateBy { it.fqn }

        val planResult =
            PlanBuilder.build(
                symbol = symbol,
                scope = scope,
                externalClasses = discovery.externalClasses,
            )
        val plan =
            when (planResult) {
                is Either.Left -> {
                    for (err in planResult.value.all) {
                        logger.error(err.message, classDecl)
                    }
                    return true
                }
                is Either.Right -> planResult.value
            }

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
        val fileSpec =
            try {
                CodecEmitter(registry).emit(plan, classType)
            } catch (t: Throwable) {
                logger.error(
                    "Pipeline failed to emit '${classDecl.simpleName.asString()}': ${t.message}",
                    classDecl,
                )
                return true
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
}
