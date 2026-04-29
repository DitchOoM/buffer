package com.ditchoom.buffer.codec.processor

import com.ditchoom.buffer.codec.processor.discovery.Discovery
import com.ditchoom.buffer.codec.processor.discovery.DiscoveryResult
import com.ditchoom.buffer.codec.processor.emitter.CodecEmitter
import com.ditchoom.buffer.codec.processor.emitter.TypeRegistry
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.planbuilder.Either
import com.ditchoom.buffer.codec.processor.planbuilder.PlanBuilder
import com.ditchoom.buffer.codec.processor.validator.Validator
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.ksp.writeTo

class ProtocolMessageProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private val processed = mutableSetOf<String>()

    /**
     * Discovery output cached for the lifetime of one [process] round. Discovery walks the
     * entire resolver, so doing it once per `@ProtocolMessage` symbol would multiply KSP
     * work by N. Cleared at the start of [process] so subsequent rounds see fresh
     * `KSClassDeclaration`s.
     */
    private var cachedDiscovery: DiscoveryResult? = null

    override fun process(resolver: Resolver): List<KSAnnotated> {
        cachedDiscovery = null

        val annotationName = "com.ditchoom.buffer.codec.annotations.ProtocolMessage"
        val symbols = resolver.getSymbolsWithAnnotation(annotationName).toList()

        for (symbol in symbols) {
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
                symbol.classKind == ClassKind.OBJECT -> processObject(symbol, resolver)
                else -> processDataClass(symbol, resolver)
            }
        }
        return emptyList()
    }

    private fun processObject(
        classDeclaration: KSClassDeclaration,
        resolver: Resolver,
    ) {
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
        tryPipeline(classDeclaration, resolver)
    }

    private fun processDataClass(
        classDeclaration: KSClassDeclaration,
        resolver: Resolver,
    ) {
        val constructor = classDeclaration.primaryConstructor
        if (constructor == null) {
            logger.error(
                "@ProtocolMessage class '${classDeclaration.simpleName.asString()}' must have a primary constructor " +
                    "with val parameters. " +
                    "Fix: add a primary constructor (e.g., 'class ${classDeclaration.simpleName.asString()}(val id: Int)').",
                classDeclaration,
            )
            return
        }
        if (constructor.parameters.isEmpty()) {
            logger.error(
                "@ProtocolMessage class '${classDeclaration.simpleName.asString()}' must have at least one val parameter " +
                    "in its primary constructor. For a type-only message (no wire bytes), declare it as " +
                    "'object ${classDeclaration.simpleName.asString()}' or 'data object ${classDeclaration.simpleName.asString()}' instead.",
                classDeclaration,
            )
            return
        }
        tryPipeline(classDeclaration, resolver)
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
            tryPipeline(subclass, resolver)
        }
        tryPipeline(classDeclaration, resolver)
    }

    /**
     * Routes a single [classDecl] through Discovery → PlanBuilder → Validator → [CodecEmitter].
     * PlanBuilder and Validator errors surface via [KSPLogger.error]; emitter exceptions
     * propagate so the build fails loudly rather than silently producing no codec.
     */
    private fun tryPipeline(
        classDecl: KSClassDeclaration,
        resolver: Resolver,
    ) {
        val fqn = classDecl.qualifiedName?.asString() ?: return
        val discovery = cachedDiscovery ?: Discovery.run(resolver).also { cachedDiscovery = it }
        val symbol = discovery.symbols.firstOrNull { it.fqn == fqn } ?: return
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
                    return
                }
                is Either.Right -> planResult.value
            }

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
                return
            }
            is Either.Right -> Unit
        }

        val registry = buildRegistry(discovery)
        val classType = registry.resolve(TypeFqn(symbol.fqn))
        val emitter = CodecEmitter(registry)
        val fileSpec = emitter.emit(plan, classType)
        val supplemental = emitter.emitSupplemental(plan, classType)

        // Match the legacy file-header conventions (4-space indent + `@file:Suppress("ktlint")`)
        // so generated source remains byte-for-byte stable across the cutover.
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

private fun KSAnnotation.qualifiedName(): String? =
    annotationType.resolve().declaration.qualifiedName?.asString()
