package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated

/**
 * Stage 0 stub.
 *
 * The KSP entry point is preserved so KSP wiring stays valid; every emitter
 * has been removed. Capability returns one stage at a time per the Stages
 * A–H plan in `PHASE_9_RESET.md`. Until Stage A lands, this processor
 * discovers `@ProtocolMessage` annotations and does nothing with them.
 */
class ProtocolMessageProcessor(
    @Suppress("unused") private val codeGenerator: CodeGenerator,
    @Suppress("unused") private val logger: KSPLogger,
) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> = emptyList()
}
