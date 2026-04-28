package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.discovery.RawClassMetadata
import com.ditchoom.buffer.codec.processor.discovery.RawTypeRef
import com.ditchoom.buffer.codec.processor.ir.FieldPlan
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.planbuilder.KspError
import com.squareup.kotlinpoet.ClassName

/**
 * Verifies that every `FieldStrategy.External` (produced by `@UseCodec`) names a class
 * whose directly-declared supertypes include `Codec<T>`, `Encoder<T>`, or `Decoder<T>`.
 *
 * The checker reads only the codec class's directly-declared supertype list — captured
 * by Discovery (PhaseA) into [RawClassMetadata]. KSP's all-supertypes walker returns
 * unresolved type variables on inherited generic supertypes (the previous BodyLengthFraming
 * bug); the directly-declared list is the only resolvable surface we accept here.
 *
 * Type-parameter binding is reported when present but not strictly enforced — the
 * field type is the user's declaration, the codec's parameter is what the codec class
 * declared, and the user can legitimately rely on a `Codec<Animal>` to handle a `Dog`
 * field via the `in T` variance Encoder<T> already provides. The diagnostic surfaces
 * mismatches as warnings via the existing PhaseB rule when KSP can resolve a clear
 * conflict (handled separately); PhaseC's job here is to assert the codec exists and
 * has *some* codec-shaped supertype.
 */
internal object UseCodecConformanceChecker {
    private const val CODEC_FQN = "com.ditchoom.buffer.codec.Codec"
    private const val ENCODER_FQN = "com.ditchoom.buffer.codec.Encoder"
    private const val DECODER_FQN = "com.ditchoom.buffer.codec.Decoder"

    private val CODEC_SHAPED_FQNS = setOf(CODEC_FQN, ENCODER_FQN, DECODER_FQN)

    fun check(
        plans: Map<TypeFqn, Plan>,
        externalClasses: Map<String, RawClassMetadata>,
    ): List<KspError> {
        val errors = mutableListOf<KspError>()
        for (plan in plans.values) {
            when (plan) {
                is Plan.Object_ -> Unit
                is Plan.Leaf -> walkFields(plan.decl, plan.fields, externalClasses, errors)
                is Plan.Sealed_ ->
                    for (v in plan.variants) {
                        walkFields(v.decl, v.fields, externalClasses, errors)
                    }
            }
        }
        return errors
    }

    private fun walkFields(
        owner: TypeFqn,
        fields: List<FieldPlan>,
        externalClasses: Map<String, RawClassMetadata>,
        errors: MutableList<KspError>,
    ) {
        for (f in fields) {
            val ext = f.strategy as? FieldStrategy.External ?: continue
            val codecFqn = ext.codec.toFqn()
            val metadata = externalClasses[codecFqn]
            if (metadata == null) {
                errors +=
                    KspError(
                        message =
                            "@UseCodec(codec = $codecFqn::class) on '${owner.canonical}.${f.name}' " +
                                "references a class that could not be resolved at KSP time. Verify the " +
                                "codec exists on the classpath and that its FQN is spelled correctly.",
                        sourceFqn = "${owner.canonical}.${f.name}",
                    )
                continue
            }
            val match =
                metadata.directlyDeclaredSupertypes.firstOrNull { it.fqn in CODEC_SHAPED_FQNS }
            if (match == null) {
                // Wording mirrors legacy `FieldAnalyzer`'s diagnostic so the existing
                // test assertions (`contains("does not implement")` +
                // `contains("Codec<T>")` etc.) keep matching after the gate flip.
                val codecShort = codecFqn.substringAfterLast('.')
                errors +=
                    KspError(
                        message =
                            "@UseCodec on field '${f.name}': '$codecShort' does not implement " +
                                "Codec<T>, Decoder<T>, or Encoder<T>. To fix, make it implement one of:\n" +
                                "  • Codec<T> — for bidirectional encode/decode\n" +
                                "  • Decoder<T> — for decode-only (e.g., display without re-encoding)\n" +
                                "  • Encoder<T> — for encode-only (e.g., write-only serialization)",
                        sourceFqn = "${owner.canonical}.${f.name}",
                    )
            }
        }
    }

    private fun describeSupertypes(refs: List<RawTypeRef>): String =
        if (refs.isEmpty()) {
            "<none>"
        } else {
            refs.joinToString(", ") { ref ->
                if (ref.typeArguments.isEmpty()) {
                    ref.fqn
                } else {
                    "${ref.fqn}<${ref.typeArguments.joinToString(", ") { it.fqn.ifBlank { it.name } }}>"
                }
            }
        }

    private fun ClassName.toFqn(): String =
        if (packageName.isEmpty()) simpleNames.joinToString(".") else "$packageName.${simpleNames.joinToString(".")}"
}
