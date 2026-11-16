package com.ditchoom.buffer.codec

/**
 * Default ceiling on a single frame read by [readFrame], applied when
 * [MaxFrameBytesKey] is absent from the [DecodeContext].
 *
 * 4 MiB — deliberately far below the encode side's 1 GiB
 * `MAX_ENCODE_CAPACITY`, and the asymmetry is the point: an encode
 * capacity is driven by data the process already holds, while a frame
 * size is a number a remote peer sent. A ceiling high enough to never
 * inconvenience anyone is not a limit, so this one is set where real
 * protocol frames do not reach but runaway buffering does. Consumers
 * that legitimately stream larger frames raise it explicitly via
 * [MaxFrameBytesKey]; the resulting [FrameTooLargeException] names both
 * the limit and the key, so the first oversized frame says how to fix
 * itself.
 */
public const val DEFAULT_MAX_FRAME_BYTES: Int = 4 * 1024 * 1024

/**
 * [DecodeContext] key overriding [DEFAULT_MAX_FRAME_BYTES] for
 * [readFrame].
 *
 * Set it to the largest frame the protocol can legitimately produce.
 *
 * **Scope of the guarantee**: a frame whose size the codec reports,
 * whether complete ([FramePeek.complete]) or still arriving
 * ([FramePeek.declaredIncomplete]), is checked against the ceiling before
 * anything is sliced, so a peer that declares a large frame and never
 * sends it is rejected as soon as the declaration is buffered (issue #308).
 * A codec that only implements the older `peekFrameSize` cannot express a
 * known-but-unsatisfied size, so for it the check still happens only once
 * the whole frame has arrived. Generated codecs report the size.
 *
 * ```kotlin
 * val ctx = DecodeContext.Empty.with(MaxFrameBytesKey, 64 * 1024)
 * val packet = PacketCodec.readFrame(stream, ctx)
 * ```
 *
 * Decode-only: framing has no encode-side counterpart, so this is a
 * [DecodeKey] rather than a bidirectional [CodecKey].
 */
public object MaxFrameBytesKey : DecodeKey<Int>

/**
 * A frame's declared size is unusable: either larger than the ceiling in
 * effect ([MaxFrameBytesKey], defaulting to [DEFAULT_MAX_FRAME_BYTES]),
 * or not a positive byte count at all.
 *
 * Thrown by [readFrame] before any buffer is sliced, so an oversized or
 * malformed declaration costs nothing beyond the bytes already buffered.
 * Callers branch on the type — a hostile or desynchronized peer is not
 * the same condition as a decode failure inside a well-formed frame, and
 * typically warrants dropping the connection rather than resyncing.
 */
public class FrameTooLargeException(
    public val declaredBytes: Int,
    public val maxBytes: Int,
) : DecodeException(
        fieldPath = "<frame>",
        bufferPosition = 0,
        expected = "frame size in 1..$maxBytes (raise via MaxFrameBytesKey)",
        actual = declaredBytes.toString(),
    )
