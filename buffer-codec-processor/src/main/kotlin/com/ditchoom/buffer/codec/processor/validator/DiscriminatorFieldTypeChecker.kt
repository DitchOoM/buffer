package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.ir.DispatchShape
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.planbuilder.KspError

/**
 * Whole-program re-assertion that every variant carrying a [FieldStrategy.DiscriminatorOwned]
 * field points back at the same discriminator type the sealed root declared via
 * `@DispatchOn(type = X::class)`.
 *
 * PhaseB already enforces this rule per-symbol (it is the construction precondition for
 * the `DiscriminatorOwned` strategy in `FieldStrategyBuilder`). PhaseC re-checks at
 * whole-program scope so a future refactor or SPI extension that reaches into the IR
 * directly cannot smuggle in a strategy whose `parentDispatchOn` does not match the
 * root's `@DispatchOn` declaration.
 */
internal object DiscriminatorFieldTypeChecker {
    fun check(plans: Map<TypeFqn, Plan>): List<KspError> {
        val errors = mutableListOf<KspError>()
        for (plan in plans.values) {
            if (plan !is Plan.Sealed_) continue
            val typed = plan.dispatch as? DispatchShape.TypedDiscriminator ?: continue
            val expected = typed.disc.discriminatorType
            for (v in plan.variants) {
                for (f in v.fields) {
                    val owned = f.strategy as? FieldStrategy.DiscriminatorOwned ?: continue
                    if (owned.parentDispatchOn != expected) {
                        errors +=
                            KspError(
                                message =
                                    "Variant '${v.decl.canonical}' field '${f.name}' is marked " +
                                        "@DiscriminatorField with type '${owned.parentDispatchOn.canonical}' " +
                                        "but its sealed root '${plan.decl.canonical}' " +
                                        "@DispatchOn declares '${expected.canonical}'.",
                                sourceFqn = "${v.decl.canonical}.${f.name}",
                            )
                    }
                }
            }
        }
        return errors
    }
}
