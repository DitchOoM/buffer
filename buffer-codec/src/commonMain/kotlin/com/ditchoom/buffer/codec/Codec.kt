package com.ditchoom.buffer.codec

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

interface Codec<T> {
    /**
     * Decodes a value from [buffer] at the current position.
     */
    fun decode(buffer: ReadBuffer): T

    /**
     * Decodes a value from [buffer] with runtime [context].
     *
     * Override this to read typed keys from the context (allocator hints, config, etc.).
     * The default implementation ignores the context and delegates to [decode].
     *
     * Context is forwarded automatically through generated sealed dispatch codecs,
     * `@UseCodec` fields, and nested `@ProtocolMessage` fields.
     */
    fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): T = decode(buffer)

    /**
     * Encodes [value] to [buffer] at the current position.
     */
    fun encode(
        buffer: WriteBuffer,
        value: T,
    )

    /**
     * Encodes [value] to [buffer] with runtime [context].
     *
     * Override this to read typed keys from the context (compression level, format version, etc.).
     * The default implementation ignores the context and delegates to [encode].
     */
    fun encode(
        buffer: WriteBuffer,
        value: T,
        context: EncodeContext,
    ) = encode(buffer, value)

    /**
     * Returns the encoded size in bytes, or null if not known statically.
     */
    fun sizeOf(value: T): Int? = null
}
