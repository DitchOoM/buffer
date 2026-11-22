package com.ditchoom.buffer.codec

import com.ditchoom.buffer.stream.StreamProcessor

/**
 * A [Codec] for a self-delimiting, variable-width value — one whose encoded
 * length is not a compile-time constant but is always recoverable from the
 * value (for sizing) and from the leading wire bytes (for framing). QUIC
 * varints (RFC 9000 §16), LEB128, protobuf varints, and similar schemes fit;
 * the buffer library ships none of them — a consumer supplies the encoding and
 * the KSP processor provides encoding-agnostic variable-width plumbing.
 *
 * A consumer implements [Decoder.decode], [Encoder.encode], [encodedLength] and
 * [frameSize]:
 *  - [encodedLength] returns a plain [Int]: the size of a value already in hand
 *    is total, so [wireBytes] is always exact and enclosing messages stay on the
 *    precompute path.
 *  - [frameSize] reports the width of the value starting at `baseOffset` as
 *    `FrameSize.declared(headerBytes = 0, bodyBytes = width)`, or
 *    [FrameSize.NeedsMoreData] while the prefix is too short to tell. Scan the width
 *    only: framing never needs the decoded value, so there is nothing to allocate.
 *
 * One correctness invariant the type system can't express: [Encoder.encode]
 * must write exactly [encodedLength] bytes, and [frameSize] must report that
 * same width for the encoded value. A round-trip test is the right place to pin
 * this down.
 */
interface VariableLengthCodec<T> : Codec<T> {
    /** Bytes [value] encodes to — always known from the value alone. */
    fun encodedLength(value: T): Int

    override fun wireBytes(
        value: T,
        context: EncodeContext,
    ): WireByteCount = WireByteCount.exact(encodedLength(value).toLong())

    /** The width of the encoded value at [baseOffset]; see the interface KDoc. */
    override fun frameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): FrameSize
}
