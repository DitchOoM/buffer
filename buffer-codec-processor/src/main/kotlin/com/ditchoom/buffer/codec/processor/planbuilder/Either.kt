package com.ditchoom.buffer.codec.processor.planbuilder

/**
 * Minimal monadic carrier for PhaseB / PhaseC results — pure Kotlin, no Arrow dependency.
 *
 * Errors accumulate into a [Nel] on the [Left] branch; successful values land on [Right].
 * Helpers ([map], [flatMap], [zip], [sequence]) are intentionally narrow — only the shapes
 * the planbuilder pipeline actually composes.
 */
sealed interface Either<out L, out R> {
    data class Left<out L>(
        val value: L,
    ) : Either<L, Nothing>

    data class Right<out R>(
        val value: R,
    ) : Either<Nothing, R>
}

fun <R> R.right(): Either<Nothing, R> = Either.Right(this)

fun <L> L.left(): Either<L, Nothing> = Either.Left(this)

inline fun <L, R, R2> Either<L, R>.map(f: (R) -> R2): Either<L, R2> =
    when (this) {
        is Either.Left -> this
        is Either.Right -> Either.Right(f(value))
    }

inline fun <L, R, R2> Either<L, R>.flatMap(f: (R) -> Either<L, R2>): Either<L, R2> =
    when (this) {
        is Either.Left -> this
        is Either.Right -> f(value)
    }

inline fun <L, R> Either<L, R>.getOrElse(f: (L) -> R): R =
    when (this) {
        is Either.Left -> f(value)
        is Either.Right -> value
    }

/**
 * Combine two independent [Either]s — when both succeed the combiner runs; when either or
 * both fail, the failure lists are concatenated so the caller sees every accumulated error.
 */
inline fun <L, A, B, R> zipErrors(
    a: Either<Nel<L>, A>,
    b: Either<Nel<L>, B>,
    combine: (A, B) -> R,
): Either<Nel<L>, R> =
    when (a) {
        is Either.Right ->
            when (b) {
                is Either.Right -> combine(a.value, b.value).right()
                is Either.Left -> b
            }
        is Either.Left ->
            when (b) {
                is Either.Right -> a
                is Either.Left -> Either.Left(a.value + b.value)
            }
    }

/**
 * Sequence a list of [Either]s, accumulating every error into a single [Nel]. Returns
 * `Right(emptyList())` when the input is empty.
 */
fun <L, R> List<Either<Nel<L>, R>>.sequenceAccumulating(): Either<Nel<L>, List<R>> {
    val errors = mutableListOf<L>()
    val rights = mutableListOf<R>()
    for (e in this) {
        when (e) {
            is Either.Left -> errors.addAll(e.value.all)
            is Either.Right -> rights.add(e.value)
        }
    }
    return if (errors.isEmpty()) {
        rights.right()
    } else {
        Nel.fromList(errors).left()
    }
}
