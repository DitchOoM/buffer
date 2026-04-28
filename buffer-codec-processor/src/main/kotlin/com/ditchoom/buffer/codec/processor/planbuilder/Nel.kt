package com.ditchoom.buffer.codec.processor.planbuilder

/**
 * Non-empty list — a list whose `isEmpty()` is statically `false`.
 *
 * The PhaseB error channel uses `Nel<KspError>` so the type itself documents that an
 * `Either.Left` branch carries at least one diagnostic; the previous sentinel-value
 * approach (an empty list standing in for "no error") is impossible here by construction.
 */
data class Nel<T>(
    val head: T,
    val tail: List<T>,
) {
    val all: List<T> = listOf(head) + tail

    val size: Int = 1 + tail.size

    operator fun plus(other: Nel<T>): Nel<T> = Nel(head, tail + other.all)

    operator fun plus(other: T): Nel<T> = Nel(head, tail + other)

    fun <R> map(f: (T) -> R): Nel<R> = Nel(f(head), tail.map(f))

    companion object {
        fun <T> of(
            head: T,
            vararg rest: T,
        ): Nel<T> = Nel(head, rest.toList())

        /**
         * Build a [Nel] from a possibly-empty list. Throws on empty input — caller must
         * check `list.isEmpty()` first or use [fromListOrNull].
         */
        fun <T> fromList(list: List<T>): Nel<T> {
            require(list.isNotEmpty()) { "Nel.fromList requires a non-empty list" }
            return Nel(list.first(), list.drop(1))
        }

        fun <T> fromListOrNull(list: List<T>): Nel<T>? = if (list.isEmpty()) null else fromList(list)
    }
}
