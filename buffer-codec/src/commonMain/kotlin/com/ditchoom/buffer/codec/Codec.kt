package com.ditchoom.buffer.codec

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.stream.FrameDetector
import com.ditchoom.buffer.stream.PeekResult
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * Complete codec: encodes, decodes, and detects frame boundaries.
 *
 * Implementors override exactly two methods — the context-aware versions:
 * - [decode] with [DecodeContext]
 * - [encode] with [EncodeContext]
 *
 * The context-free overloads inherited from [Encoder] and [Decoder] delegate
 * to the context versions with [DecodeContext.Empty] / [EncodeContext.Empty],
 * so callers that don't need context work transparently.
 *
 * Simple codecs that don't use context just ignore the context parameter.
 */
interface Codec<T> : Encoder<T>, Decoder<T>, FrameDetector {
    /**
     * Decodes a value from [buffer] with runtime [context].
     */
    fun decode(buffer: ReadBuffer, context: DecodeContext): T

    /**
     * Encodes [value] to [buffer] with runtime [context].
     */
    fun encode(buffer: WriteBuffer, value: T, context: EncodeContext)

    // Context-free overloads delegate to context versions
    override fun decode(buffer: ReadBuffer): T = decode(buffer, DecodeContext.Empty)

    override fun encode(buffer: WriteBuffer, value: T) = encode(buffer, value, EncodeContext.Empty)

    override fun sizeOf(value: T): SizeEstimate = SizeEstimate.UnableToPrecalculate

    override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NeedsMoreData
}
