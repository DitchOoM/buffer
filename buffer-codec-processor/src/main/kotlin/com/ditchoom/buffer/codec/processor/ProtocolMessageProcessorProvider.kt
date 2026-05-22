package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

class ProtocolMessageProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        ProtocolMessageProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
        )
}
