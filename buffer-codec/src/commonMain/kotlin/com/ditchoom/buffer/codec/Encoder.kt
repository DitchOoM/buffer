package com.ditchoom.buffer.codec

import com.ditchoom.buffer.WriteBuffer

/**
 * Encodes typed messages to bytes.
 *
 * Separated from [Decoder] so that send-only streams can require only encoding capability.
 * The type system prevents using an encoder where decoding is needed, and vice versa.
 */
interface Encoder<in T> {
    /**
     * Encodes [value] to [buffer] at the current position.
     */
    fun encode(
        buffer: WriteBuffer,
        value: T,
    )

    /**
     * Returns the exact number of bytes [encode] would write for [value].
     *
     * Generated codecs override this with a sum of per-field size formulas, so
     * [encodeToBuffer] can allocate an exact-size buffer up front and avoid the
     * grow-and-copy cost of a growable fallback buffer.
     *
     * Hand-written encoders that don't need [encodeToBuffer]'s exact-size path
     * may leave the throwing default; the throw is caught by [encodeToBuffer]
     * which falls back to a growable buffer for size-unknown encoders (e.g.
     * mqtt's lambda-wrapping `Encoder<P>` adapters in `eagerEncode`).
     */
    fun wireSize(value: T): Int =
        throw NotImplementedError(
            "wireSize(value) not implemented for ${this::class.simpleName}. " +
                "Generated codecs override this; hand-written encoders may override " +
                "to enable exact-size allocation in encodeToBuffer.",
        )
}
