package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.discovery.RawClassMetadata
import com.ditchoom.buffer.codec.processor.discovery.RawTypeRef
import com.ditchoom.buffer.codec.processor.ir.DiscriminatorShape
import com.ditchoom.buffer.codec.processor.ir.DispatchShape
import com.ditchoom.buffer.codec.processor.ir.FramingMode
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.planbuilder.KspError
import com.squareup.kotlinpoet.ClassName

/**
 * Verifies that every framer FQN named via `@DispatchOn(framing = X::class)` actually
 * implements `DispatchFraming<D>` (peek-only) or `BodyLengthFraming<D>` (body-length)
 * with a parameter binding D that matches the sealed root's discriminator.
 *
 * Critically, this matching reads only the framer's directly-declared supertype list
 * — never KSP's all-supertypes walker. Walking transitive parents via KSP returns
 * unresolved type variables on inherited generic supertypes, the bug that broke
 * the previous BodyLengthFraming attempt (commits ada9796 on buffer, cd245818 on
 * mqtt — reverted). The directly-declared supertype list is captured by Discovery
 * (PhaseA) as [RawClassMetadata]; PhaseC pattern-matches the parameter binding at
 * the immediate supertype reference here.
 */
internal object FramerTypeMatcher {
    private const val DISPATCH_FRAMING_FQN = "com.ditchoom.buffer.codec.DispatchFraming"
    private const val BODY_LENGTH_FRAMING_FQN = "com.ditchoom.buffer.codec.BodyLengthFraming"

    fun check(
        plans: Map<TypeFqn, Plan>,
        externalClasses: Map<String, RawClassMetadata>,
    ): List<KspError> {
        val errors = mutableListOf<KspError>()
        for (plan in plans.values) {
            if (plan !is Plan.Sealed_) continue
            val typed = plan.dispatch as? DispatchShape.TypedDiscriminator ?: continue
            val expected = expectedFromFraming(typed.framing) ?: continue
            checkOne(plan, typed, expected, externalClasses, errors)
        }
        return errors
    }

    private fun expectedFromFraming(framing: FramingMode): Expected? =
        when (framing) {
            FramingMode.Unframed -> null
            is FramingMode.PeekOnly -> Expected(framing.framerFqn, peekOnly = true)
            is FramingMode.BodyLength -> Expected(framing.framerFqn, peekOnly = false)
        }

    private fun checkOne(
        sealed: Plan.Sealed_,
        typed: DispatchShape.TypedDiscriminator,
        expected: Expected,
        externalClasses: Map<String, RawClassMetadata>,
        errors: MutableList<KspError>,
    ) {
        val framerFqn = expected.framerFqn.toFqn()
        // Slice 5.5: the inherit-companion path emits the dispatcher's framer
        // ClassName as the discriminator class (Kotlin auto-routes `MyTag.method()`
        // to the companion). PhaseA captures metadata under the companion's FQN —
        // when the named class FQN doesn't have metadata, fall back to its
        // `${fqn}.Companion` entry. This is also the path that fires when the
        // discriminator class itself is captured (Slice 5.5 added that for
        // `@DispatchValue` property walking) — we want to inspect the framer
        // companion's supertypes, not the discriminator's.
        val metadata =
            externalClasses[framerFqn]
                ?.takeIf { md ->
                    // If the metadata describes a class that doesn't extend any framer
                    // interface and a `.Companion` entry exists, prefer the companion.
                    val supers = md.directlyDeclaredSupertypes.map { it.fqn }.toSet()
                    DISPATCH_FRAMING_FQN in supers ||
                        BODY_LENGTH_FRAMING_FQN in supers ||
                        externalClasses["$framerFqn.Companion"] == null
                }
                ?: externalClasses["$framerFqn.Companion"]
        if (metadata == null) {
            errors +=
                KspError(
                    message =
                        "@DispatchOn(framing = $framerFqn::class) on '${sealed.decl.canonical}' " +
                            "references a class that could not be resolved at KSP time. Verify the class " +
                            "exists on the classpath and that its FQN is spelled correctly.",
                    sourceFqn = sealed.decl.canonical,
                )
            return
        }

        val discriminatorFqn = discriminatorFqn(typed.disc)
        val acceptableSupertypeFqns =
            if (expected.peekOnly) {
                setOf(DISPATCH_FRAMING_FQN, BODY_LENGTH_FRAMING_FQN)
            } else {
                setOf(BODY_LENGTH_FRAMING_FQN)
            }
        val match =
            metadata.directlyDeclaredSupertypes.firstNotNullOfOrNull { st ->
                detectFramingSupertype(st, acceptableSupertypeFqns)
            }

        if (match == null) {
            val acceptableNames =
                if (expected.peekOnly) {
                    "$DISPATCH_FRAMING_FQN<D> or $BODY_LENGTH_FRAMING_FQN<D>"
                } else {
                    "$BODY_LENGTH_FRAMING_FQN<D>"
                }
            errors +=
                KspError(
                    message =
                        "@DispatchOn(framing = $framerFqn::class) on '${sealed.decl.canonical}' must " +
                            "directly extend $acceptableNames where D matches the discriminator " +
                            "'$discriminatorFqn'. Got directly-declared supertypes: " +
                            "${describeSupertypes(metadata.directlyDeclaredSupertypes)}.",
                    sourceFqn = sealed.decl.canonical,
                )
            return
        }
        if (discriminatorFqn != null &&
            match.parameterBindingFqn != null &&
            match.parameterBindingFqn != discriminatorFqn
        ) {
            errors +=
                KspError(
                    message =
                        "@DispatchOn(framing = $framerFqn::class) on '${sealed.decl.canonical}' " +
                            "extends ${match.framingFqn}<${match.parameterBindingFqn}> but the " +
                            "discriminator type is '$discriminatorFqn'. The framer's parameter " +
                            "binding must match the @DispatchOn discriminator class.",
                    sourceFqn = sealed.decl.canonical,
                )
        }
    }

    private fun detectFramingSupertype(
        supertype: RawTypeRef,
        acceptableFqns: Set<String>,
    ): Match? =
        if (supertype.fqn !in acceptableFqns) {
            null
        } else {
            Match(
                framingFqn = supertype.fqn,
                parameterBindingFqn =
                    supertype.typeArguments
                        .firstOrNull()
                        ?.fqn
                        ?.takeIf { it.isNotBlank() },
            )
        }

    private fun discriminatorFqn(disc: DiscriminatorShape): String? = disc.discriminatorType.canonical

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

    private data class Expected(
        val framerFqn: ClassName,
        val peekOnly: Boolean,
    )

    private data class Match(
        val framingFqn: String,
        val parameterBindingFqn: String?,
    )
}
