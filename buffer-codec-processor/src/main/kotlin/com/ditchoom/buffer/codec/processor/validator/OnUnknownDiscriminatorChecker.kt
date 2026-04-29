package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.discovery.RawClassMetadata
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.planbuilder.KspError

/**
 * Verifies that every `Plan.Sealed_.onUnknown` exception class:
 *  1. Resolves on the compilation classpath (i.e. Discovery captured metadata for it).
 *  2. Declares at least one single-`String` constructor — the generated dispatcher
 *     instantiates the exception with a message string when no variant matches.
 *
 * Skips the legacy default `java.lang.IllegalArgumentException`, which is wired
 * automatically when the user did not specify `onUnknownDiscriminator` on the sealed
 * root's `@ProtocolMessage` annotation.
 *
 * Mirrors legacy `ProtocolMessageProcessor.extractOnUnknownDiscriminator` (deleted in
 * Phase 9 Step 6) — wording preserved so existing test assertions hold.
 */
internal object OnUnknownDiscriminatorChecker {
    private const val DEFAULT_FQN = "java.lang.IllegalArgumentException"

    fun check(
        plans: Map<TypeFqn, Plan>,
        externalClasses: Map<String, RawClassMetadata>,
    ): List<KspError> {
        val errors = mutableListOf<KspError>()
        for (plan in plans.values) {
            if (plan !is Plan.Sealed_) continue
            val fqn = plan.onUnknown.canonical
            if (fqn == DEFAULT_FQN) continue
            val rootSimpleName = plan.decl.canonical.substringAfterLast('.')
            val metadata = externalClasses[fqn]
            if (metadata == null) {
                errors +=
                    KspError(
                        message =
                            "@ProtocolMessage(onUnknownDiscriminator = \"$fqn\") on '$rootSimpleName' did not " +
                                "resolve to a class on the compilation classpath. Provide a fully-qualified name " +
                                "visible to this module.",
                        sourceFqn = plan.decl.canonical,
                    )
                continue
            }
            val hasStringCtor =
                metadata.constructorParameterTypes.any { params ->
                    params.size == 1 && params.single() == "kotlin.String"
                }
            if (!hasStringCtor) {
                errors +=
                    KspError(
                        message =
                            "@ProtocolMessage(onUnknownDiscriminator = \"$fqn\") on '$rootSimpleName' resolves " +
                                "but the class does not declare a single-`String` constructor. The generated " +
                                "dispatcher passes a message string, so the exception class must accept it: " +
                                "`class ${fqn.substringAfterLast('.')}(message: String)`.",
                        sourceFqn = plan.decl.canonical,
                    )
            }
        }
        return errors
    }
}
