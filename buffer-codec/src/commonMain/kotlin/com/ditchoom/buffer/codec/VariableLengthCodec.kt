package com.ditchoom.buffer.codec

import com.ditchoom.buffer.stream.StreamProcessor

/**
 * Result of peeking a self-delimiting value from a [StreamProcessor] prefix
 * without consuming bytes. Modeled as a sum type (not a nullable) so the two
 * states are explicit and exhaustive.
 */
sealed interface VarLenPeek<out T> {
    /** Not enough bytes are buffered yet to determine the value or its length. */
    data object NeedsMoreData : VarLenPeek<Nothing>

    /** The decoded value and the exact number of bytes it occupies on the wire. */
    data class Decoded<out T>(
        val value: T,
        val byteCount: Int,
    ) : VarLenPeek<T>
}

/**
 * A [Codec] for a self-delimiting, variable-width value — one whose encoded
 * length is not a compile-time constant but is always recoverable from the
 * value (for sizing) and from the leading wire bytes (for framing). QUIC
 * varints (RFC 9000 §16), LEB128, protobuf varints, and similar schemes fit;
 * the buffer library ships none of them — a consumer supplies the encoding and
 * the KSP processor provides encoding-agnostic variable-width plumbing.
 *
 * The contract is narrower than [Codec] on purpose, so illegal states are
 * unrepresentable:
 *  - [encodedLength] returns a plain [Int]: the size of a value already in hand
 *    is *total*, so there is no `BackPatch` to reach for. [wireSize] is derived
 *    as [WireSize.Exact], keeping enclosing messages on the precompute path
 *    rather than degrading the whole chain to back-patching.
 *  - [peekValue] returns [VarLenPeek]: a stream prefix genuinely *can* be
 *    incomplete, so `NeedsMoreData` is a real state. [peekFrameSize] is derived
 *    from it.
 *
 * A consumer therefore implements exactly four methods — [Decoder.decode],
 * [Encoder.encode], [encodedLength], and [peekValue] — and the two wider [Codec]
 * return types ([WireSize], [PeekResult]) are filled in here so they cannot
 * carry a value this contract forbids.
 *
 * One correctness invariant the type system can't express: [Encoder.encode]
 * must write exactly [encodedLength] bytes, and that count must equal the
 * [VarLenPeek.Decoded.byteCount] that [peekValue] reports for the same value. A
 * round-trip test is the right place to pin this down.
 */
interface VariableLengthCodec<T> : Codec<T> {
    /** Bytes [value] encodes to — always known from the value alone. */
    fun encodedLength(value: T): Int

    /**
     * Decode the value and its byte length from a [stream] prefix beginning at
     * [baseOffset], without consuming any bytes. Returns
     * [VarLenPeek.NeedsMoreData] when the prefix is too short to determine
     * either.
     */
    fun peekValue(
        stream: StreamProcessor,
        baseOffset: Int = 0,
    ): VarLenPeek<T>

    override fun wireSize(
        value: T,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(encodedLength(value))

    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult =
        when (val peeked = peekValue(stream, baseOffset)) {
            is VarLenPeek.Decoded -> PeekResult.Complete(peeked.byteCount)
            VarLenPeek.NeedsMoreData -> PeekResult.NeedsMoreData
        }
}
