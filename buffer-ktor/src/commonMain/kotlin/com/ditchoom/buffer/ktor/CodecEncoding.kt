package com.ditchoom.buffer.ktor

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Encoder
import com.ditchoom.buffer.codec.encodeToPlatformBuffer as codecEncodeToPlatformBuffer

/**
 * Encodes [value] with this [Encoder] into a fresh [PlatformBuffer] positioned for reading
 * (`position = 0`, `limit = encoded size`).
 *
 * The overflow-retry policy now lives in buffer-codec as
 * [com.ditchoom.buffer.codec.encodeToPlatformBuffer] — the canonical home shared by every transport
 * bridge (buffer-ktor, buffer-flow). This overload re-exports it into the ktor namespace so existing
 * callers keep resolving it here; it forwards unchanged.
 *
 * @param value the value to encode.
 * @param factory allocator for the destination buffer (default [BufferFactory.Default]).
 * @param context encode context threaded to the encoder (default [EncodeContext.Empty]).
 */
public fun <T> Encoder<T>.encodeToPlatformBuffer(
    value: T,
    factory: BufferFactory = BufferFactory.Default,
    context: EncodeContext = EncodeContext.Empty,
): PlatformBuffer = codecEncodeToPlatformBuffer(value, factory, context)
