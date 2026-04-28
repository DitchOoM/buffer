package com.ditchoom.buffer.codec.processor.ir

/**
 * Boolean AST produced by the `@When` expression parser (PhaseB).
 *
 * Composable; future expression forms land as new sealed children — the IR
 * extends without rewriting existing emitter branches.
 */
sealed interface BooleanExpression {
    /** Dotted identifier path, e.g. `flags.willFlag`. */
    data class FieldRef(
        val path: List<String>,
    ) : BooleanExpression

    /** Predicate `remaining >= min`. */
    data class RemainingGte(
        val min: Int,
    ) : BooleanExpression

    /** Equality check `lhs == rhs`. */
    data class Eq(
        val lhs: BooleanExpression,
        val rhs: Int,
    ) : BooleanExpression

    /** Greater-than check `lhs > rhs`. */
    data class Gt(
        val lhs: BooleanExpression,
        val rhs: Int,
    ) : BooleanExpression
}
