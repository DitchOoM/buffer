package com.ditchoom.buffer.codec

import kotlin.jvm.JvmInline

/**
 * What [VariableLengthCodec.peekInteger] read at an offset: the encoded value as a
 * non-negative `Long`, [NeedsMoreData] while the encoding has not fully arrived, or
 * [NotRepresentable] when the value is not a non-negative integer within `Long` range (a
 * negative or non-integer value, or an unsigned one past [Long.MAX_VALUE]). A caller that
 * gets [NotRepresentable] decodes the value instead.
 *
 * A value class over one `Long`: `>= 0` is the value, and two negative values are the
 * other states. No value maps to them: [of] answers [NotRepresentable] for a negative one.
 */
@JvmInline
public value class PeekedInteger
    @PublishedApi
    internal constructor(
        @PublishedApi internal val bits: Long,
    ) {
        /** Branches on the three states; inline, so the lambdas cost nothing and may `return`. */
        public inline fun <R> fold(
            value: (value: Long) -> R,
            needsMoreData: () -> R,
            notRepresentable: () -> R,
        ): R =
            when {
                bits >= 0L -> value(bits)
                bits == NEEDS_MORE_DATA_BITS -> needsMoreData()
                else -> notRepresentable()
            }

        override fun toString(): String =
            fold({ "PeekedInteger.of($it)" }, { "PeekedInteger.NeedsMoreData" }, { "PeekedInteger.NotRepresentable" })

        public companion object {
            @PublishedApi internal const val NEEDS_MORE_DATA_BITS: Long = -1L

            @PublishedApi internal const val NOT_REPRESENTABLE_BITS: Long = -2L

            /** The encoding has not fully arrived. */
            public val NeedsMoreData: PeekedInteger get() = PeekedInteger(NEEDS_MORE_DATA_BITS)

            /** The value is not a non-negative integer within `Long` range; decode it instead. */
            public val NotRepresentable: PeekedInteger get() = PeekedInteger(NOT_REPRESENTABLE_BITS)

            /** [value], or [NotRepresentable] when it is negative. */
            public fun of(value: Long): PeekedInteger = if (value >= 0L) PeekedInteger(value) else NotRepresentable

            /** [value], or [NotRepresentable] when it is past [Long.MAX_VALUE]. */
            public fun of(value: ULong): PeekedInteger =
                if (value <= Long.MAX_VALUE.toULong()) PeekedInteger(value.toLong()) else NotRepresentable
        }
    }
