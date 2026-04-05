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
     * Estimates the encoded size of [value] for buffer pre-allocation.
     */
    fun sizeOf(value: T): SizeEstimate = SizeEstimate.UnableToPrecalculate
}
