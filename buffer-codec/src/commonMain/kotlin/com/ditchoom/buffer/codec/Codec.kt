package com.ditchoom.buffer.codec

import com.ditchoom.buffer.stream.FrameDetector
import com.ditchoom.buffer.stream.PeekResult
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * Complete codec: encodes, decodes, and detects frame boundaries.
 *
 * Codec is just the union of [Encoder], [Decoder], and [FrameDetector] — all three
 * methods (`encode`, `decode`, `wireSize`) are inherited as context-aware. Implementors
 * override the inherited methods directly; Codec itself adds no method declarations.
 *
 * Callers that don't hold a context pass [DecodeContext.Empty] / [EncodeContext.Empty]
 * explicitly. Codecs that ignore context just accept the parameter and read nothing
 * from it.
 */
interface Codec<T> :
    Encoder<T>,
    Decoder<T>,
    FrameDetector {
    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult = PeekResult.NeedsMoreData
}
