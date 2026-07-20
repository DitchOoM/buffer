package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.BufferOverflowException
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.stream.StreamProcessor

private const val MIN_ESTIMATE = 64
private const val MAX_ENCODE_CAPACITY = 1 shl 30 // 1 GiB safety ceiling

/**
 * Encodes [value] with this [Encoder] into a fresh [PlatformBuffer] allocated by [factory],
 * positioned for reading (`position = 0`, `limit = encoded size`).
 *
 * When the encoder reports a [WireSize.Exact] size the buffer is allocated exactly once. For
 * [WireSize.BackPatch] encoders the capacity starts from an estimate and doubles on
 * [BufferOverflowException] until the value fits (capped at 1 GiB). [IllegalArgumentException] is
 * treated the same way as a backstop: a position seek past the current limit signals out-of-bounds
 * differently per platform (java.nio throws [IllegalArgumentException] rather than
 * [BufferOverflowException]), and that signal must grow-and-retry rather than escape. Generated
 * codecs reserve prefix slots with placeholder writes (which overflow retryably), so the backstop
 * only matters for hand-written encoders.
 *
 * Frees the allocated buffer on any exception from [Encoder.encode] — not just the overflow-retry
 * path — so a failing encode never leaks native memory.
 *
 * This is the canonical home for codec→buffer encoding; the transport bridges (buffer-ktor,
 * buffer-flow) build their frame helpers on top of it so the overflow-retry policy lives in one
 * place.
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
            capacity = grownCapacityOrRethrow(capacity, overflow)
        } catch (positionOverflow: IllegalArgumentException) {
            freeOnExit = false
            buffer.freeNativeMemory()
            capacity = grownCapacityOrRethrow(capacity, positionOverflow)
        } finally {
            if (freeOnExit) buffer.freeNativeMemory()
        }
    }
}

/** Double [capacity] toward [MAX_ENCODE_CAPACITY]; rethrow [cause] once the ceiling is reached. */
private fun grownCapacityOrRethrow(
    capacity: Int,
    cause: Exception,
): Int {
    if (capacity >= MAX_ENCODE_CAPACITY) throw cause
    return minOf(capacity * 2, MAX_ENCODE_CAPACITY)
}

/**
 * Reads the next complete frame from [stream], or returns `null` when the stream does not yet hold
 * a whole frame (either the framing header hasn't arrived, or the header is present but the body is
 * still incomplete). A `null` return is the streaming loop's signal to pull more transport bytes,
 * [StreamProcessor.append] them, and retry.
 *
 * Uses this codec's generated [FrameDetector.peekFrameSize] to size the frame without consuming
 * bytes, then decodes exactly that many via [StreamProcessor.readBufferScoped] so the sliced frame
 * is released back to the pool after [Decoder.decode] returns.
 *
 * @throws IllegalStateException if this codec does not participate in frame detection
 *   ([PeekResult.NoFraming]) — i.e. its wire format has no determinable frame size. Such a codec
 *   cannot drive a streaming loop; frame it explicitly (e.g. a length prefix) instead.
 */
public fun <T> Codec<T>.readFrame(
    stream: StreamProcessor,
    context: DecodeContext = DecodeContext.Empty,
): T? =
    when (val peek = peekFrameSize(stream)) {
        is PeekResult.Complete ->
            if (stream.available() < peek.bytes) {
                null
            } else {
                stream.readBufferScoped(peek.bytes) { decode(this, context) }
            }
        PeekResult.NeedsMoreData -> null
        PeekResult.NoFraming ->
            throw IllegalStateException(
                "Codec ${this::class.simpleName} does not support frame detection " +
                    "(peekFrameSize returned NoFraming); its wire format has no determinable frame " +
                    "size. Streaming requires a self-delimiting format (e.g. a length prefix).",
            )
    }
