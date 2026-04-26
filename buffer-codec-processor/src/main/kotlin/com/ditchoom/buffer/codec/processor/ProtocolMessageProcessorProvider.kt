package com.ditchoom.buffer.codec.processor

import com.ditchoom.buffer.codec.processor.builtin.VariableByteIntegerProvider
import com.ditchoom.buffer.codec.processor.spi.CodecFieldProvider
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import java.util.ServiceLoader

class ProtocolMessageProcessorProvider(
    private val additionalProviders: List<CodecFieldProvider> = emptyList(),
) : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        val builtInProviders: List<CodecFieldProvider> = listOf(VariableByteIntegerProvider())
        val spiProviders =
            ServiceLoader
                .load(CodecFieldProvider::class.java, this::class.java.classLoader)
                .toList()

        // Validate SPI/additional providers don't override built-in annotation namespace.
        val builtInPrefix = "com.ditchoom.buffer.codec.annotations."
        for (provider in spiProviders + additionalProviders) {
            require(!provider.annotationFqn.startsWith(builtInPrefix)) {
                "SPI provider cannot override built-in annotation: ${provider.annotationFqn}"
            }
        }

        // Validate no duplicates across built-ins, SPI, and additional providers.
        val seen = mutableMapOf<String, CodecFieldProvider>()
        for (provider in builtInProviders + spiProviders + additionalProviders) {
            val existing = seen.put(provider.annotationFqn, provider)
            require(existing == null) {
                "Duplicate CodecFieldProvider for annotation '${provider.annotationFqn}': " +
                    "${existing!!::class.qualifiedName} and ${provider::class.qualifiedName}"
            }
        }

        return ProtocolMessageProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
            customProviders = seen,
        )
    }
}
