package com.ditchoom.buffer.stream

import kotlin.jvm.JvmInline

/**
 * Result of peeking at a byte stream to determine frame boundaries.
 *
 * Sealed interface forces exhaustive `when` — caller must handle both cases.
 * [Size] inlines to Int at runtime — zero allocation overhead.
 */
sealed interface PeekResult {
    /** Frame boundary found. [bytes] is the total frame size including any headers. */
    @JvmInline
    value class Size(
        val bytes: Int,
    ) : PeekResult

    /** Not enough data buffered to determine the frame boundary. Try again after more data arrives. */
    data object NeedsMoreData : PeekResult
}

/**
 * Determines message frame boundaries in a buffered byte stream.
 *
 * Implementations peek at the stream without consuming bytes. This is used by
 * framing layers (e.g., CodecConnection) to know when a complete message is
 * available for decoding.
 */
fun interface FrameDetector {
    /**
     * Peeks at the stream starting at [baseOffset] to determine the total frame size.
     *
     * @param stream the buffered byte stream to peek into
     * @param baseOffset byte offset from the stream's current position (usually 0)
     * @return [PeekResult.Size] with the total frame size, or [PeekResult.NeedsMoreData]
     *         if not enough bytes are buffered to determine the boundary
     */
    fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult
}
