package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.ir.Direction
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.planbuilder.KspError

/**
 * Verifies that variants of one `Plan.Sealed_` agree on direction with the sealed root.
 *
 * Compatibility table:
 *  - Sealed root [Direction.Bidirectional] accepts variants with any direction.
 *  - Sealed root [Direction.DecodeOnly] accepts only [Direction.DecodeOnly] or
 *    [Direction.Bidirectional] variants — an `@Encode` variant nested inside a
 *    `@Decode` sealed root would generate an encoder the parent cannot expose.
 *  - Sealed root [Direction.EncodeOnly] mirrors the above.
 *
 * Errors name the offending variant + the parent sealed root so the user can see
 * which marker to remove.
 */
internal object DirectionReconciler {
    fun check(plans: Map<TypeFqn, Plan>): List<KspError> {
        val errors = mutableListOf<KspError>()
        for (plan in plans.values) {
            if (plan !is Plan.Sealed_) continue
            for (v in plan.variants) {
                if (!compatible(plan.dir, v.dir)) {
                    errors +=
                        KspError(
                            message =
                                "Direction mismatch: variant '${v.decl.canonical}' is ${v.dir} " +
                                    "but its sealed root '${plan.decl.canonical}' is ${plan.dir}. " +
                                    "Pick a matching @Decode/@Encode marker (or remove the variant's " +
                                    "marker so it inherits the parent's direction).",
                            sourceFqn = v.decl.canonical,
                        )
                }
            }
        }
        return errors
    }

    private fun compatible(
        parent: Direction,
        variant: Direction,
    ): Boolean =
        when (parent) {
            Direction.Bidirectional -> true
            Direction.DecodeOnly -> variant == Direction.DecodeOnly || variant == Direction.Bidirectional
            Direction.EncodeOnly -> variant == Direction.EncodeOnly || variant == Direction.Bidirectional
        }
}
