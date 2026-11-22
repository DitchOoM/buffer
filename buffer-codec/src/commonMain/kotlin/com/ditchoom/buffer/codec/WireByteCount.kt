package com.ditchoom.buffer.codec

import kotlin.jvm.JvmInline

/**
 * What [Encoder.wireBytes] knows about a value's encoded size: exactly how many bytes it
 * takes, or that it is only known after encoding (the framework then writes into a
 * growable buffer and back-patches any length prefix).
 *
 * A value class over one `Long`: `>= 0` is the exact size, `-1` is back-patch.
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

        override fun toString(): String = fold({ "WireByteCount.exact($it)" }, { "WireByteCount.BackPatch" })

        public companion object {
            @PublishedApi internal const val BACK_PATCH_BITS: Long = -1L

            /** The size is only known after encoding. */
            public val BackPatch: WireByteCount get() = WireByteCount(BACK_PATCH_BITS)

            /**
             * The value encodes to exactly [bytes] bytes.
             *
             * @throws NegativeWireByteCountException when [bytes] is negative: the encoder
             *   reporting it is broken.
             */
            public fun exact(bytes: Long): WireByteCount {
                if (bytes < 0L) throw NegativeWireByteCountException(bytes)
                return WireByteCount(bytes)
            }
        }
    }

/** An encoder reported a negative exact size, [bytes]: no value encodes to that. */
public class NegativeWireByteCountException(
    public val bytes: Long,
) : EncodeException(fieldPath = "<wireBytes>", reason = "exact size $bytes is negative")
