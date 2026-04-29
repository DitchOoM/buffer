package com.ditchoom.buffer.codec.processor

import com.ditchoom.buffer.codec.processor.spi.CodecFieldProvider
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import java.util.ServiceLoader

class ProtocolMessageProcessorProvider(
    private val additionalProviders: List<CodecFieldProvider> = emptyList(),
) : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        val spiProviders =
            ServiceLoader
                .load(CodecFieldProvider::class.java, this::class.java.classLoader)
                .toList()
        val allProvidersList = spiProviders + additionalProviders

        // Validate no duplicates
        val seen = mutableMapOf<String, CodecFieldProvider>()
        for (provider in allProvidersList) {
            val existing = seen.put(provider.annotationFqn, provider)
            require(existing == null) {
                "Duplicate CodecFieldProvider for annotation '${provider.annotationFqn}': " +
                    "${existing!!::class.qualifiedName} and ${provider::class.qualifiedName}"
            }
        }

        // Validate no built-in annotation overrides
        val builtInPrefix = "com.ditchoom.buffer.codec.annotations."
        for (fqn in seen.keys) {
            require(!fqn.startsWith(builtInPrefix)) {
                "SPI provider cannot override built-in annotation: $fqn"
            }
        }

        return ProtocolMessageProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
            customProviders = seen,
        )
    }
}
