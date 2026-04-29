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
 * ## Contract: payload-only
 *
 * `encode` writes payload bytes only — never an outer length prefix. `wireSize` returns
 * that same payload-byte count. `decode` reads from `buffer.position()` to its natural
 * delimiter (fixed width, terminator) or to `buffer.remaining()` for variable-length
 * payloads — implementors trust the caller's buffer-bounding rather than reading their
 * own length prefix. Framing (length prefixes, length-from-other-field, dispatch headers)
 * is the framework's job, expressed via field annotations on the consumer
 * (`@LengthPrefixed`, `@LengthFrom`, `@RemainingBytes`, `@DispatchOn`). Generated
 * dispatchers and `BodyLengthFraming` companions own peek logic; payload codecs leave
 * `peekFrameSize` defaulted to `NeedsMoreData`.
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
