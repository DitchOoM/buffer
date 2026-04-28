package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.TypeFqn

/**
 * The output of a successful PhaseC validation pass.
 *
 * Wrapper marker type: only [Validator] constructs values of this type, and PhaseD
 * (Emit) takes [ValidatedProgram] as its sole input. The constructor is package-private
 * so downstream code cannot fabricate a "validated" program by hand — every instance
 * has provably gone through the validator.
 *
 * Carries the same plan map PhaseB produced; PhaseC's contribution is the proof that
 * every cross-program invariant holds, not new data on the plan side. Future PhaseC
 * additions (e.g. a precomputed cycle-free traversal order) can extend this type
 * without changing the PhaseD contract.
 */
class ValidatedProgram internal constructor(
    val plans: Map<TypeFqn, Plan>,
) {
    operator fun get(fqn: TypeFqn): Plan? = plans[fqn]

    val size: Int = plans.size
}
