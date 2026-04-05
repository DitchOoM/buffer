package com.ditchoom.buffer.codec

import com.ditchoom.buffer.ReadBuffer

/**
 * Decodes typed messages from bytes.
 *
 * Separated from [Encoder] so that receive-only streams can require only decoding capability.
 * `fun interface` enables SAM lambda: `Decoder<Int> { buffer -> buffer.readInt() }`
 */
fun interface Decoder<out T> {
    /**
     * Decodes a value from [buffer] at the current position.
     */
    fun decode(buffer: ReadBuffer): T
}
