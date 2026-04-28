package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.ir.FieldPlan
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.planbuilder.KspError

/**
 * Rejects ambiguous SPI descriptors emitted by `CodecFieldProvider` implementations.
 *
 * A descriptor is ambiguous when PhaseD has no way to compute the field's wire size:
 * the descriptor's [com.ditchoom.buffer.codec.processor.ir.SpiDescriptor.fixedSize] is
 * `-1` (variable-size), [decodeRaw] AND [encodeRaw] are both blank, AND [wireSizeRaw]
 * is blank — there is neither a fixed-size literal to embed nor a runtime size
 * expression for the emitter to substitute.
 *
 * Slice 5.5 splits the validation against `decodeRaw` / `encodeRaw` rather than the
 * legacy single-string `raw`: asymmetric SPI providers (legacy
 * `CustomFieldDescriptor` with separate `readFunction` / `writeFunction`) only set
 * one side per direction, and the validator must accept that. Mirrors legacy
 * `WireSizeEmitter`'s asymmetric path which consults `readFunction` for decode size
 * estimation and `writeFunction` for encode size estimation independently.
 *
 * This is the PhaseC-time replacement for the legacy generator's deferred runtime
 * crash ("processor bug: descriptor wireSize unknown"). Catching it at validation time
 * fails the build with a clear, actionable diagnostic instead.
 */
internal object SpiDescriptorChecker {
    fun check(plans: Map<TypeFqn, Plan>): List<KspError> {
        val errors = mutableListOf<KspError>()
        for (plan in plans.values) {
            when (plan) {
                is Plan.Object_ -> Unit
                is Plan.Leaf -> walk(plan.decl, plan.fields, errors)
                is Plan.Sealed_ ->
                    for (v in plan.variants) walk(v.decl, v.fields, errors)
            }
        }
        return errors
    }

    private fun walk(
        owner: TypeFqn,
        fields: List<FieldPlan>,
        errors: MutableList<KspError>,
    ) {
        for (f in fields) {
            val spi = f.strategy as? FieldStrategy.Spi ?: continue
            val descriptor = spi.descriptor
            // The descriptor needs at least one inline payload (decodeRaw OR encodeRaw)
            // before we can complain — the legacy validator allowed providers that only
            // set one side. The wire-size leg is independent: the descriptor must carry
            // either a fixedSize >= 0 OR a non-blank wireSizeRaw OR a non-blank inline
            // payload (the legacy emitter falls back to `0` when fixedSize == -1 and no
            // wireSizeRaw — that is the path Slice 5a's checker rejects).
            val anyInline =
                descriptor.decodeRaw.isNotBlank() || descriptor.encodeRaw.isNotBlank()
            if (descriptor.fixedSize == -1 && !anyInline && descriptor.wireSizeRaw.isBlank()) {
                errors +=
                    KspError(
                        message =
                            "SPI provider '${spi.provider.id}' returned an ambiguous descriptor for " +
                                "'${owner.canonical}.${f.name}': fixedSize = -1 and the descriptor carries " +
                                "no inline payload (neither decodeRaw nor encodeRaw is set) and no " +
                                "wireSizeRaw. PhaseD cannot compute the field's wire size. " +
                                "Update the provider to set a fixedSize, supply a wireSize hint, or emit a " +
                                "concrete inline payload.",
                        sourceFqn = "${owner.canonical}.${f.name}",
                    )
            }
        }
    }
}
