package com.ditchoom.buffer.codec

import com.ditchoom.buffer.WriteBuffer

/**
 * Encodes typed messages to bytes.
 *
 * Separated from [Decoder] so that send-only streams can require only encoding capability.
 * The type system prevents using an encoder where decoding is needed, and vice versa.
 *
 * ## Contract: payload-only
 *
 * `encode` writes payload bytes only — never an outer length prefix. After the call,
 * `buffer.position()` advances by exactly `wireSize(value, context)`. Framing (length
 * prefixes, length-from-other-field, dispatch headers) is added by the framework on
 * the consumer side via field annotations such as `@LengthPrefixed`, `@LengthFrom`,
 * `@RemainingBytes`, and `@DispatchOn`. To get a framed buffer, encode through the
 * dispatcher / annotated message that owns the framing — not through this codec
 * directly.
 *
 * Implementors override exactly two methods — both context-aware. Callers that don't hold
 * a context pass [EncodeContext.Empty] explicitly. Encoders that ignore context just
 * accept the parameter and read nothing from it.
 */
interface Encoder<in T> {
    /**
     * Encodes [value]'s payload bytes to [buffer] at the current position.
     *
     * Writes exactly [wireSize] bytes. Does **not** write a length prefix or any
     * outer framing — the framework handles that via field annotations.
     */
    fun encode(
        buffer: WriteBuffer,
        value: T,
        context: EncodeContext,
    )

    /**
     * Returns the exact number of payload bytes [encode] would write for [value] under [context].
     *
     * The returned size is the payload-byte count only — it never includes an outer
     * length prefix or framing bytes. Framing is added on top by the caller (the
     * generated dispatcher, or the framework's `@LengthPrefixed`/`@LengthFrom` machinery).
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
