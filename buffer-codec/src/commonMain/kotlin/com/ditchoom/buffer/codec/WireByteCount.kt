package com.ditchoom.buffer.codec

import kotlin.jvm.JvmInline

/**
 * What [Encoder.wireBytes] knows about a value's encoded size: exactly how many bytes it
 * takes, or that it is only known after encoding (the framework then writes into a
 * growable buffer and back-patches any length prefix).
 *
 * A value class over one `Long`, returned directly: on the JVM it is a bare `long`, so
 * sizing a value allocates nothing.
 */
@JvmInline
public value class WireByteCount
    @PublishedApi
    internal constructor(
        @PublishedApi internal val bits: Long,
    ) {
        /** Branches on the two states; inline, so the lambdas cost nothing and may `return`. */
        public inline fun <R> fold(
            exact: (bytes: Long) -> R,
            backPatch: () -> R,
        ): R = if (bits >= 0L) exact(bits) else backPatch()

        /**
         * This answer for a caller of the deprecated [Encoder.wireSize]. An exact size past
         * `Int` range cannot be pre-allocated, so it becomes [WireSize.BackPatch].
         */
        @Suppress("DEPRECATION")
        @Deprecated("Bridge for implementing the deprecated Encoder.wireSize; call wireBytes instead.")
        public fun toWireSize(): WireSize =
            fold(
                exact = { if (it > Int.MAX_VALUE) WireSize.BackPatch else WireSize.Exact(it.toInt()) },
                backPatch = { WireSize.BackPatch },
            )

        override fun toString(): String = fold({ "WireByteCount.exact($it)" }, { "WireByteCount.BackPatch" })

        public companion object {
            @PublishedApi internal const val BACK_PATCH_BITS: Long = -1L

            /** The size is only known after encoding. */
            public val BackPatch: WireByteCount get() = WireByteCount(BACK_PATCH_BITS)

            /**
             * The value encodes to exactly [bytes] bytes.
             *
             * @throws NegativeWireByteCountException when [bytes] is negative.
             */
            public fun exact(bytes: Long): WireByteCount {
                if (bytes < 0L) throw NegativeWireByteCountException(bytes)
                return WireByteCount(bytes)
            }

            /** The same answer from an encoder still on the deprecated [WireSize]. */
            @Suppress("DEPRECATION")
            @Deprecated("Bridge from the deprecated WireSize; implement wireBytes instead.")
            public fun from(old: WireSize): WireByteCount =
                when (old) {
                    is WireSize.Exact -> exact(old.bytes.toLong())
                    WireSize.BackPatch -> BackPatch
                }
        }
    }

/** An encoder reported a negative exact size, which no encoding has. */
public class NegativeWireByteCountException(
    public val bytes: Long,
) : EncodeException(fieldPath = "<wireBytes>", reason = "exact size $bytes is negative")
