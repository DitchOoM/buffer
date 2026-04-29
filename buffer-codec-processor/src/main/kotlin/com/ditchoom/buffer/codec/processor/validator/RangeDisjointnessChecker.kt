package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.ir.WireMatch
import com.ditchoom.buffer.codec.processor.planbuilder.KspError

/**
 * Verifies that, within each `Plan.Sealed_`, no two variants claim the same wire byte.
 *
 * Each [WireMatch.Point] contributes the closed interval `[wire, wire]`; each
 * [WireMatch.Range] contributes `[from, to]`. The checker walks the variants in
 * declaration order, tracks every previously claimed interval, and on overlap emits a
 * single error naming both the existing claimant and the colliding variant — including
 * the specific colliding byte(s) so the diagnostic points at the wire shape directly.
 */
internal object RangeDisjointnessChecker {
    fun check(plans: Map<TypeFqn, Plan>): List<KspError> {
        val errors = mutableListOf<KspError>()
        for (plan in plans.values) {
            if (plan !is Plan.Sealed_) continue
            checkOne(plan, errors)
        }
        return errors
    }

    private fun checkOne(
        sealed: Plan.Sealed_,
        errors: MutableList<KspError>,
    ) {
        val claimed = mutableListOf<Claim>()
        for (v in sealed.variants) {
            val (lo, hi) =
                when (val w = v.wire) {
                    is WireMatch.Point -> w.wire to w.wire
                    is WireMatch.Range -> w.from to w.to
                }
            val overlaps = claimed.filter { it.lo <= hi && lo <= it.hi }
            for (other in overlaps) {
                errors +=
                    KspError(
                        message = overlapMessage(sealed, other, v.decl, lo, hi),
                        sourceFqn = v.decl.canonical,
                    )
            }
            claimed += Claim(lo, hi, v.decl)
        }
    }

    private fun overlapMessage(
        sealed: Plan.Sealed_,
        existing: Claim,
        new: TypeFqn,
        newLo: Int,
        newHi: Int,
    ): String {
        val collision = (maxOf(existing.lo, newLo)..minOf(existing.hi, newHi)).toList()
        val collisionStr =
            when {
                collision.size == 1 -> "byte 0x${collision.single().toString(16).uppercase()}"
                else ->
                    "bytes 0x${collision.first().toString(16).uppercase()}..0x${
                        collision.last().toString(16).uppercase()
                    }"
            }
        return "Sealed root '${sealed.decl.canonical}' variant spans overlap: " +
            "'${existing.subclass.canonical}' (${rangeStr(existing.lo, existing.hi)}) and " +
            "'${new.canonical}' (${rangeStr(newLo, newHi)}) both claim $collisionStr."
    }

    private fun rangeStr(
        lo: Int,
        hi: Int,
    ): String =
        if (lo == hi) {
            "0x${lo.toString(16).uppercase()}"
        } else {
            "0x${lo.toString(16).uppercase()}..0x${hi.toString(16).uppercase()}"
        }

    private data class Claim(
        val lo: Int,
        val hi: Int,
        val subclass: TypeFqn,
    )
}
