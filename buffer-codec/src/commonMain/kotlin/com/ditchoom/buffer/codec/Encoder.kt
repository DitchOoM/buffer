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
     * Hint for initial buffer allocation in [encodeToBuffer].
     *
     * Generated codecs override this with the sum of their fixed-size fields,
     * so the growable buffer starts close to the right size and avoids copies.
     * Defaults to 16 for hand-written codecs.
     */
    val wireSizeHint: Int get() = 16
}
