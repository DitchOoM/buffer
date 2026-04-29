package com.ditchoom.buffer.codec

import com.ditchoom.buffer.ReadBuffer

/**
 * Decodes typed messages from bytes.
 *
 * Separated from [Encoder] so that receive-only streams can require only decoding capability.
 * `fun interface` enables SAM lambda:
 * `Decoder<Int> { buffer, _ -> buffer.readInt() }`
 *
 * ## Contract: pre-bounded buffer
 *
 * `decode` is called with a buffer pre-bounded by the caller — the framework, an outer
 * codec, or a generated dispatcher has already sliced or limited the buffer to the
 * payload window. Implementations read to the codec's natural delimiter (fixed width,
 * terminator) or to `buffer.remaining()` for variable-length payloads. Decoders do not
 * read their own length prefix; framing is the caller's job, expressed via field
 * annotations such as `@LengthPrefixed`, `@LengthFrom`, `@RemainingBytes`, and `@DispatchOn`.
 *
 * Callers that don't hold a context pass [DecodeContext.Empty] explicitly. Decoders that
 * ignore context just accept the parameter and read nothing from it.
 */
fun interface Decoder<out T> {
    /**
     * Decodes a value from [buffer] at the current position with runtime [context].
     *
     * The buffer is pre-bounded by the caller. Read to the codec's natural delimiter
     * (fixed width, terminator) or to `buffer.remaining()` for variable-length
     * payloads — do not read a length prefix.
     */
    fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): T
}
