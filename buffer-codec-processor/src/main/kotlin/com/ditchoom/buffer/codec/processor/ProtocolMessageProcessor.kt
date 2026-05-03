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
 * Stage 0 stub + compile-time validator.
 *
 * The KSP entry point is preserved so KSP wiring stays valid; every
 * code emitter has been removed. Capability returns one stage at a
 * time per the Stages A–H plan in `PHASE_9_RESET.md`.
 *
 * Until Stage A lands, this processor enforces the rules that are
 * load-bearing right now:
 *
 *   - **§8 raw-bytes ban.** `@ProtocolMessage` data classes cannot
 *     carry raw-bytes types in their fields (`ReadBuffer`,
 *     `WriteBuffer`, `PlatformBuffer`, `ByteArray`, `ByteBuffer`).
 *     The walk includes the field's declared type, the inner type of
 *     `@JvmInline value class` wrappers, and the type arguments of
 *     generic types. Walks short-circuit when a node is, or extends,
 *     `com.ditchoom.buffer.codec.Payload` — the Payload marker is the
 *     documented escape hatch and the consumer takes responsibility
 *     for the bytes it holds.
 *
 *   - **R1: adjacent-`@LengthFrom` rejection.** `@LengthFrom("X")` on
 *     field F where X is the field immediately preceding F in the
 *     same `@ProtocolMessage` data class is a compile error **when F
 *     has a viable `@LengthPrefixed` migration target**. Bound fields
 *     whose type extends the [com.ditchoom.buffer.codec.Payload]
 *     marker interface are skipped — `@LengthPrefixed` does not yet
 *     widen to cover Payload slots (Stage H deferral); R1 expands to
 *     cover them once that widening lands. `@LengthFrom` is otherwise
 *     reserved for genuine remote-prefix uses (length carried in a
 *     non-adjacent field, parsed elsewhere, or parent-passed via
 *     `@DispatchOn`).
 */
class ProtocolMessageProcessor(
    @Suppress("unused") private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        // Payload lives in buffer-codec, the same artifact that defines
        // @ProtocolMessage. If we have any @ProtocolMessage symbols to
        // process, buffer-codec is on the classpath and Payload resolves.
        // A null here means the processor's environment is broken (e.g.,
        // misconfigured classpath); fail loud rather than silently degrading
        // §8 / R1 behavior on a missing marker.
        val payloadType =
            resolver
                .getClassDeclarationByName(resolver.getKSNameFromString(PAYLOAD_QNAME))
                ?.asStarProjectedType()
                ?: run {
                    logger.error(
                        "Cannot resolve $PAYLOAD_QNAME — buffer-codec must be on the " +
                            "classpath when ProtocolMessageProcessor runs.",
                    )
                    return emptyList()
                }

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
            validateAdjacentLengthFrom(symbol, ctor.parameters, payloadType)
        }
        return deferred
    }

    private fun validateAdjacentLengthFrom(
        owner: KSClassDeclaration,
        parameters: List<KSValueParameter>,
        payloadType: KSType,
    ) {
        for ((index, param) in parameters.withIndex()) {
            val lengthFrom =
                param.annotations.firstOrNull { ann ->
                    ann.shortName.asString() == LENGTH_FROM_SHORT &&
                        ann.annotationType
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString() == LENGTH_FROM_QNAME
                } ?: continue
            val referencedField =
                lengthFrom.arguments
                    .firstOrNull { it.name?.asString() == "field" }
                    ?.value as? String ?: continue
            if (index == 0) continue
            val previous = parameters[index - 1]
            if (previous.name?.asString() != referencedField) continue

            // Path B carve-out: skip when the bound field's type extends the
            // Payload marker interface. @LengthPrefixed does not yet widen to
            // cover Payload slots (Stage H), so forbidding the adjacent shape
            // today would leave those fields with no migration target. R1
            // expands to cover this case once @LengthPrefixed widens.
            val boundFieldType = param.type.resolve()
            if (payloadType.isAssignableFrom(boundFieldType)) continue

            val ownerName = owner.qualifiedName?.asString() ?: owner.simpleName.asString()
            val boundFieldName = param.name?.asString() ?: "<unknown>"
            logger.error(
                "@LengthFrom(\"$referencedField\") on $ownerName.$boundFieldName references " +
                    "$ownerName.$referencedField, the immediately preceding constructor " +
                    "parameter. Adjacent length carriers must use @LengthPrefixed on the " +
                    "bounded field instead — replace with `@LengthPrefixed val " +
                    "$boundFieldName: ...` and remove $referencedField from the constructor. " +
                    "@LengthFrom is reserved for remote-prefix uses (length carried in a " +
                    "non-adjacent field).",
                param,
            )
        }
    }

    private fun walkType(
        type: KSType,
        owner: KSClassDeclaration,
        param: KSValueParameter,
        payloadType: KSType,
        depth: Int,
        visited: MutableSet<String>,
    ) {
        if (depth > MAX_DEPTH) return
        if (type.isError) return
        if (payloadType.isAssignableFrom(type)) return

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
        private const val LENGTH_FROM_QNAME = "com.ditchoom.buffer.codec.annotations.LengthFrom"
        private const val LENGTH_FROM_SHORT = "LengthFrom"
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
