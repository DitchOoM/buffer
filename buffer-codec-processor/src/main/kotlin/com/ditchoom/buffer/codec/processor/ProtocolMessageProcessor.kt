package com.ditchoom.buffer.codec.processor

import com.ditchoom.buffer.codec.processor.spi.CodecFieldProvider
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
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
                                "in its primary constructor.",
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

        // Phase 1: Analyze and generate sub-codecs, collecting payload metadata
        val variantPayloadInfos = mutableListOf<SealedVariantPayloadInfo>()
        for (subclass in sealedSubclasses) {
            val qualifiedName = subclass.qualifiedName?.asString() ?: continue
            if (qualifiedName in processed) continue
            processed.add(qualifiedName)

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
                        "in its primary constructor.",
                    subclass,
                )
                continue
            }

            // Analyze fields to detect @Payload
            val fieldAnalyzer = FieldAnalyzer(logger, customProviders)
            val fields = fieldAnalyzer.analyze(subclass) ?: continue

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
        generator.generate(classDeclaration, sealedSubclasses, variantPayloadInfos)
    }
}
