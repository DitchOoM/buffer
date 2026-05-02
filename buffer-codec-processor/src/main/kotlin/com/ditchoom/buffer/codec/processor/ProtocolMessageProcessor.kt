package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate

/**
 * Stage 0 stub + Section 8 validator.
 *
 * The KSP entry point is preserved so KSP wiring stays valid; every
 * code emitter has been removed. Capability returns one stage at a
 * time per the Stages A–H plan in `PHASE_9_RESET.md`.
 *
 * Until Stage A lands, this processor only enforces the one rule that
 * is load-bearing right now: `@ProtocolMessage` data classes cannot
 * carry raw-bytes types in their fields (`ReadBuffer`, `WriteBuffer`,
 * `PlatformBuffer`, `ByteArray`, `ByteBuffer`). Section 8 of
 * PHASE_10_DESIGN_NOTES.md specifies that opaque-bytes payloads must
 * be wrapped in a [Payload]-tagged type and copied explicitly inside
 * the consumer's decoder lambda — the framework never sneaks in a
 * memcpy at the boundary.
 *
 * The walk includes:
 *
 *   - the field's declared type;
 *   - the inner type of `@JvmInline value class` wrappers (so
 *     `value class ChunkBody(val raw: ReadBuffer)` is caught);
 *   - the type arguments of generic types (so `List<ByteArray>` is
 *     caught at the element type).
 *
 * Walks short-circuit when a node is, or extends,
 * `com.ditchoom.buffer.codec.Payload` — the Payload marker is the
 * documented escape hatch from §8, and the consumer takes
 * responsibility for the bytes it holds.
 */
class ProtocolMessageProcessor(
    @Suppress("unused") private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val payloadType =
            resolver
                .getClassDeclarationByName(resolver.getKSNameFromString(PAYLOAD_QNAME))
                ?.asStarProjectedType()

        val deferred = mutableListOf<KSAnnotated>()
        val symbols = resolver.getSymbolsWithAnnotation(PROTOCOL_MESSAGE_QNAME)
        for (symbol in symbols) {
            if (symbol !is KSClassDeclaration) continue
            if (!symbol.validate()) {
                deferred += symbol
                continue
            }
            val ctor = symbol.primaryConstructor ?: continue
            for (param in ctor.parameters) {
                walkType(
                    type = param.type.resolve(),
                    owner = symbol,
                    param = param,
                    payloadType = payloadType,
                    depth = 0,
                    visited = mutableSetOf(),
                )
            }
        }
        return deferred
    }

    private fun walkType(
        type: KSType,
        owner: KSClassDeclaration,
        param: KSValueParameter,
        payloadType: KSType?,
        depth: Int,
        visited: MutableSet<String>,
    ) {
        if (depth > MAX_DEPTH) return
        if (type.isError) return
        if (payloadType != null && payloadType.isAssignableFrom(type)) return

        val qualified = type.declaration.qualifiedName?.asString()
        if (qualified != null && qualified in FORBIDDEN_TYPES) {
            val ownerName = owner.qualifiedName?.asString() ?: owner.simpleName.asString()
            val fieldName = param.name?.asString() ?: "<unknown>"
            logger.error(
                "@ProtocolMessage field $ownerName.$fieldName has raw-bytes type $qualified. " +
                    "Section 8 of PHASE_10_DESIGN_NOTES.md forbids ReadBuffer / WriteBuffer / " +
                    "PlatformBuffer / ByteArray / ByteBuffer in @ProtocolMessage data classes. " +
                    "Wrap inside a `com.ditchoom.buffer.codec.Payload`-tagged type and copy " +
                    "explicitly inside the consumer's decoder lambda, or model the field with " +
                    "a typed shape and (if needed) `@UseCodec`.",
                param,
            )
            return
        }

        // Cycle guard for self-referential generic types.
        val fingerprint = type.toFingerprint()
        if (!visited.add(fingerprint)) return

        val decl = type.declaration as? KSClassDeclaration
        if (decl != null && Modifier.VALUE in decl.modifiers) {
            val inner =
                decl.primaryConstructor
                    ?.parameters
                    ?.firstOrNull()
                    ?.type
                    ?.resolve()
            if (inner != null) {
                walkType(inner, owner, param, payloadType, depth + 1, visited)
            }
        }

        for (arg in type.arguments) {
            val argType = arg.type?.resolve() ?: continue
            walkType(argType, owner, param, payloadType, depth + 1, visited)
        }
    }

    private fun KSType.toFingerprint(): String {
        val name = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
        if (arguments.isEmpty()) return name
        val args =
            arguments.joinToString(",") { arg ->
                arg.type
                    ?.resolve()
                    ?.declaration
                    ?.qualifiedName
                    ?.asString() ?: "*"
            }
        return "$name<$args>"
    }

    private companion object {
        private const val PROTOCOL_MESSAGE_QNAME = "com.ditchoom.buffer.codec.annotations.ProtocolMessage"
        private const val PAYLOAD_QNAME = "com.ditchoom.buffer.codec.Payload"
        private const val MAX_DEPTH = 16

        private val FORBIDDEN_TYPES =
            setOf(
                "com.ditchoom.buffer.ReadBuffer",
                "com.ditchoom.buffer.WriteBuffer",
                "com.ditchoom.buffer.PlatformBuffer",
                "kotlin.ByteArray",
                "java.nio.ByteBuffer",
            )
    }
}
