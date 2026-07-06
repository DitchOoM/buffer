package com.ditchoom.buffer.ktor

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.BufferOverflowException
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Encoder
import com.ditchoom.buffer.codec.WireSize

private const val MIN_ESTIMATE = 64
private const val MAX_ENCODE_CAPACITY = 1 shl 30 // 1 GiB safety ceiling

/**
 * Encodes [value] with this [Encoder] into a fresh [PlatformBuffer] allocated by [factory],
 * positioned for reading (`position = 0`, `limit = encoded size`).
 *
 * When the encoder reports an [WireSize.Exact] size the buffer is allocated exactly once. For
 * [WireSize.BackPatch] encoders the capacity starts from an estimate and doubles on
 * [BufferOverflowException] until the value fits (capped at 1 GiB).
 *
 * Frees the allocated buffer on any exception from [Encoder.encode] — not just the
 * overflow-retry path — so a failing encode never leaks native memory.
 *
 * @param value the value to encode.
 * @param factory allocator for the destination buffer (default [BufferFactory.Default]).
 * @param context encode context threaded to the encoder (default [EncodeContext.Empty]).
 */
public fun <T> Encoder<T>.encodeToPlatformBuffer(
    value: T,
    factory: BufferFactory = BufferFactory.Default,
    context: EncodeContext = EncodeContext.Empty,
): PlatformBuffer {
    var capacity =
        when (val size = wireSize(value, context)) {
            is WireSize.Exact -> maxOf(size.bytes, 1)
            WireSize.BackPatch -> MIN_ESTIMATE
        }
    while (true) {
        val buffer = factory.allocate(capacity)
        var freeOnExit = true
        try {
            encode(buffer, value, context)
            buffer.resetForRead()
            freeOnExit = false
            return buffer
        } catch (overflow: BufferOverflowException) {
            freeOnExit = false
            buffer.freeNativeMemory()
            if (capacity >= MAX_ENCODE_CAPACITY) throw overflow
            capacity = minOf(capacity * 2, MAX_ENCODE_CAPACITY)
        } finally {
            if (freeOnExit) buffer.freeNativeMemory()
        }
    }
}
