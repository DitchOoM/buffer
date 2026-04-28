package com.ditchoom.buffer.codec

import com.ditchoom.buffer.WriteBuffer

/**
 * Encodes typed messages to bytes.
 *
 * Separated from [Decoder] so that send-only streams can require only encoding capability.
 * The type system prevents using an encoder where decoding is needed, and vice versa.
 *
 * Implementors override exactly two methods — both context-aware. Callers that don't hold
 * a context pass [EncodeContext.Empty] explicitly. Encoders that ignore context just
 * accept the parameter and read nothing from it.
 */
interface Encoder<in T> {
    /**
     * Encodes [value] to [buffer] at the current position.
     */
    fun encode(
        buffer: WriteBuffer,
        value: T,
        context: EncodeContext,
    )

    /**
     * Returns the exact number of bytes [encode] would write for [value] under [context].
     *
     * Generated codecs override this with a sum of per-field size formulas, so
     * [encodeToBuffer] can allocate an exact-size buffer up front and avoid the
     * grow-and-copy cost of a growable fallback buffer. [context] flows into nested
     * `wireSize(...)` calls so payload-bearing dispatchers (e.g. MQTT v5 properties)
     * can read registered size lambdas from the context — mirroring how [encode]
     * threads the same context through the encode path.
     *
     * Hand-written encoders that don't need [encodeToBuffer]'s exact-size path
     * may leave the throwing default; the throw is caught by [encodeToBuffer]
     * which falls back to a growable buffer for size-unknown encoders.
     */
    fun wireSize(
        value: T,
        context: EncodeContext,
    ): Int =
        throw NotImplementedError(
            "wireSize(value, context) not implemented for ${this::class.simpleName}. " +
                "Generated codecs override this; hand-written encoders may override " +
                "to enable exact-size allocation in encodeToBuffer.",
        )
}
