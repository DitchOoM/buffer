package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.BufferOverflowException
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.stream.StreamProcessor

private const val MAX_ENCODE_CAPACITY = 1 shl 30 // 1 GiB safety ceiling

/**
 * Encodes [value] with this [Encoder] into a fresh [PlatformBuffer] allocated by [factory],
 * positioned for reading (`position = 0`, `limit = encoded size`).
 *
 * When the encoder reports an exact [WireByteCount] size the buffer is allocated exactly once. For
 * [WireByteCount.BackPatch] encoders (and exact sizes past `Int` range) the capacity starts from an
 * estimate and doubles on
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
    // BackPatch starts from the encoder's lower-bound guess (generated codecs sum fixed
    // widths + String.length per string field); clamped ≥ 1 so a zero size still
    // allocates. Under-hints grow-and-retry.
    var capacity =
        wireBytes(value, context).fold(
            exact = { if (it > Int.MAX_VALUE) maxOf(sizeHint(value, context), 1) else maxOf(it.toInt(), 1) },
            backPatch = { maxOf(sizeHint(value, context), 1) },
        )
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
 * a whole frame. A `null` return is the streaming loop's signal to pull more transport bytes,
 * [StreamProcessor.append] them, and retry.
 *
 * Sizes the frame with [FrameDetector.frameSize] without consuming bytes, then decodes exactly
 * that many via [StreamProcessor.readBufferScoped], so the sliced frame is released back to the
 * pool after [Decoder.decode] returns.
 *
 * A declared size is checked against [MaxFrameBytesKey] (defaulting to [DEFAULT_MAX_FRAME_BYTES])
 * as soon as the codec reports it, before the frame has arrived and before anything is sliced: a
 * peer that declares a frame far larger than it sends cannot make the caller buffer toward it.
 *
 * @throws UnframedCodecException if this codec reports [FrameSize.Unframed]: its wire format has
 *   no determinable frame size, so frame it explicitly (e.g. a length prefix).
 * @throws FrameTooLargeException if the declared frame size exceeds the ceiling in effect or is
 *   not a positive byte count.
 */
public fun <T> Codec<T>.readFrame(
    stream: StreamProcessor,
    context: DecodeContext = DecodeContext.Empty,
): T? =
    frameSize(stream).fold(
        declared = { bytes ->
            val maxFrameBytes = context[MaxFrameBytesKey] ?: DEFAULT_MAX_FRAME_BYTES
            if (bytes < 1L || bytes > maxFrameBytes) {
                throw FrameTooLargeException(declaredBytes = bytes, maxBytes = maxFrameBytes)
            }
            val size = bytes.toInt()
            if (stream.available() < size) null else stream.readBufferScoped(size) { decode(this, context) }
        },
        needsMoreData = { null },
        unframed = { throw UnframedCodecException(this) },
    )

/**
 * [detector] reports [FrameSize.Unframed], so a stream of its frames cannot be split. Extends
 * [IllegalStateException], which `readFrame` threw for this case before.
 */
public class UnframedCodecException(
    public val detector: FrameDetector,
) : IllegalStateException(
        "${detector::class.simpleName} reports FrameSize.Unframed; " +
            "streaming needs a self-delimiting format such as a length prefix",
    )
