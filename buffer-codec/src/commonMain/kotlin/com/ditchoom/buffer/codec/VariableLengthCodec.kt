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
 * [peekValue], and should override [frameSize]:
 *  - [encodedLength] returns a plain [Int]: the size of a value already in hand
 *    is total, so [wireBytes] is always exact and enclosing messages stay on the
 *    precompute path.
 *  - [frameSize] reports the width of the value starting at `baseOffset` as
 *    `FrameSize.declared(headerBytes = 0, bodyBytes = width)`, or
 *    [FrameSize.NeedsMoreData] while the prefix is too short to tell. Its default
 *    derives the width from [peekValue], which allocates; an override that scans
 *    the width only allocates nothing.
 *  - [peekValue] stays abstract until the next major release, so a codec written
 *    against it keeps compiling and [frameSize]'s default always has an answer.
 *
 * One correctness invariant the type system can't express: [Encoder.encode]
 * must write exactly [encodedLength] bytes, and [frameSize] must report that
 * same width for the encoded value. A round-trip test is the right place to pin
 * this down.
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
    @Suppress("DEPRECATION")
    @Deprecated(
        "Callers: use frameSize for the width (it does not allocate) and decode for the value. " +
            "Implementers still provide it until the next major release, which removes it; " +
            "frameSize's default derives from it.",
        ReplaceWith("frameSize(stream, baseOffset)"),
    )
    fun peekValue(
        stream: StreamProcessor,
        baseOffset: Int = 0,
    ): VarLenPeek<T>

    override fun wireBytes(
        value: T,
        context: EncodeContext,
    ): WireByteCount = WireByteCount.exact(encodedLength(value).toLong())

    @Suppress("DEPRECATION")
    @Deprecated(
        "Call wireBytes, which does not box. Removed in the next major release.",
        ReplaceWith("wireBytes(value, context)"),
    )
    override fun wireSize(
        value: T,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(encodedLength(value))

    /**
     * The width of the encoded value at [baseOffset]; see the interface KDoc. The default
     * asks [peekValue] and allocates its result; override it with a width-only scan.
     */
    @Suppress("DEPRECATION")
    override fun frameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): FrameSize =
        when (val peeked = peekValue(stream, baseOffset)) {
            is VarLenPeek.Decoded -> FrameSize.declared(0, peeked.byteCount.toLong())
            VarLenPeek.NeedsMoreData -> FrameSize.NeedsMoreData
        }

    @Suppress("DEPRECATION")
    @Deprecated(
        "Call frameSize, which does not box. Removed in the next major release.",
        ReplaceWith("frameSize(stream, baseOffset)"),
    )
    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
