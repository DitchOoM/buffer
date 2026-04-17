package com.ditchoom.buffer.codec.processor

import com.ditchoom.buffer.codec.processor.spi.CodecFieldProvider
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier

class ProtocolMessageProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val customProviders: Map<String, CodecFieldProvider> = emptyMap(),
) : SymbolProcessor {
    private val processed = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
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
                symbol.classKind == ClassKind.OBJECT -> processObject(symbol)
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
                        processDataClass(symbol, resolver)
                    }
                }
            }
        }
        return emptyList()
    }

    private fun processDataClass(
        classDeclaration: KSClassDeclaration,
        resolver: Resolver,
    ) {
        val fieldAnalyzer = FieldAnalyzer(logger, customProviders)
        val fields = fieldAnalyzer.analyze(classDeclaration)
        if (fields == null) return // errors already reported

        val batchOptimizer = BatchOptimizer()
        val batches = batchOptimizer.optimize(fields)

        val hasPayload = fields.any { it.strategy is FieldReadStrategy.PayloadField }

        val generator = CodecGenerator(codeGenerator, logger)
        generator.generate(classDeclaration, fields, batches, hasPayload)

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

        // Phase 1: Analyze and generate sub-codecs, collecting payload metadata
        val variantPayloadInfos = mutableListOf<SealedVariantPayloadInfo>()
        var anyVariantHasDiscriminatorField = false
        val variantsSupportingPeek = mutableSetOf<String>()
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

            if (fields.any { it.strategy is FieldReadStrategy.DiscriminatorField }) {
                anyVariantHasDiscriminatorField = true
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

            // Generate the sub-codec
            val batches = BatchOptimizer().optimize(fields)
            val hasPayload = payloadFields.isNotEmpty()
            CodecGenerator(codeGenerator, logger).generate(subclass, fields, batches, hasPayload)
            if (hasPayload) {
                PayloadContextGenerator(codeGenerator, logger).generate(subclass, fields)
            }
        }

        // Phase 2: Generate the dispatch codec with payload awareness
        val generator = SealedDispatchGenerator(codeGenerator, logger)
        generator.generate(
            classDeclaration,
            sealedSubclasses,
            variantPayloadInfos,
            dispatchOnInfo,
            variantsHandleDiscriminator = anyVariantHasDiscriminatorField,
            variantsSupportingPeek = variantsSupportingPeek,
        )
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
                discriminatorClass.modifiers.contains(com.google.devtools.ksp.symbol.Modifier.VALUE)

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

        return DispatchOnInfo(
            typeName = discriminatorClass.qualifiedName?.asString() ?: discriminatorClass.simpleName.asString(),
            codecName = codecName,
            dispatchProperty = dispatchProp.simpleName.asString(),
            poetClassName = poetClassName,
            innerTypeName = innerTypeName,
            isValueClass = isValueClass,
            constructorParams = discriminatorParams,
        )
    }
}
