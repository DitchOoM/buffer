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
     * grow-and-copy cost of a [GrowableWriteBuffer].
     *
     * Hand-written encoders that don't need [encodeToBuffer]'s exact-size path
     * may leave the throwing default; the throw fires only if a caller asks
     * for a size the encoder doesn't know how to compute.
     */
    fun wireSize(value: T): Int =
        throw NotImplementedError(
            "wireSize(value) not implemented for ${this::class.simpleName}. " +
                "Generated codecs override this; hand-written encoders must as well to use encodeToBuffer.",
        )

    /**
     * Hint for initial buffer allocation in [encodeToBuffer].
     *
     * Generated codecs override this with the sum of their fixed-size fields,
     * so the growable buffer starts close to the right size and avoids copies.
     * Defaults to 16 for hand-written codecs.
     */
    val wireSizeHint: Int get() = 16
}
