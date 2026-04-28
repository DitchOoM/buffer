package com.ditchoom.buffer.codec

import com.ditchoom.buffer.ReadBuffer

/**
 * Decodes typed messages from bytes.
 *
 * Separated from [Encoder] so that receive-only streams can require only decoding capability.
 * `fun interface` enables SAM lambda:
 * `Decoder<Int> { buffer, _ -> buffer.readInt() }`
 *
 * Callers that don't hold a context pass [DecodeContext.Empty] explicitly. Decoders that
 * ignore context just accept the parameter and read nothing from it.
 */
fun interface Decoder<out T> {
    /**
     * Decodes a value from [buffer] at the current position with runtime [context].
     */
    fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): T
}
