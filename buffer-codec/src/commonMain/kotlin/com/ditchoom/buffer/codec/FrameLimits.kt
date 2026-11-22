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
 * **Scope of the guarantee**: a size the codec declares through
 * [FrameDetector.frameSize] is checked against the ceiling as soon as it is
 * declared, before the frame has arrived, so a peer that declares a large
 * frame and never sends it is rejected once the declaration is buffered.
 * Generated codecs declare the size as soon as their length field arrives.
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
 * larger than an [Int] byte count can express, or not a positive byte
 * count at all.
 *
 * Thrown by [readFrame] before any buffer is sliced, and by generated
 * peeks and decodes through [declaredFrameBytes] before a wire-supplied
 * length is added, sliced or allocated with, so an oversized or
 * malformed declaration costs nothing beyond the bytes already buffered.
 * Callers branch on the type — a hostile or desynchronized peer is not
 * the same condition as a decode failure inside a well-formed frame, and
 * typically warrants dropping the connection rather than resyncing.
 *
 * [declaredBytes] is what the wire said, saturated at [Long.MAX_VALUE].
 */
public class FrameTooLargeException(
    public val declaredBytes: Long,
    public val maxBytes: Int,
) : DecodeException(
        fieldPath = "<frame>",
        bufferPosition = 0,
        expected =
            if (maxBytes == Int.MAX_VALUE) {
                "frame size in 0..$maxBytes"
            } else {
                "frame size in 1..$maxBytes (raise via MaxFrameBytesKey)"
            },
        actual = declaredBytes.toString(),
    ) {
    public constructor(declaredBytes: Int, maxBytes: Int) : this(declaredBytes.toLong(), maxBytes)
}

/**
 * [headerBytes] followed by a body a peer declared as [declaredBodyBytes]
 * bytes, as an [Int] frame size.
 *
 * Generated codecs pass every [BoundingLengthCodec] value through this
 * before adding, slicing or allocating with it, so a declaration past
 * [Int] range is rejected instead of wrapping into a small, plausible
 * size. Hand-written [BoundingLengthCodec.applyBound] implementations can
 * use it the same way, with `headerBytes = 0`.
 *
 * @throws FrameTooLargeException when [declaredBodyBytes] is negative or
 *   the total exceeds [Int.MAX_VALUE].
 */
public fun declaredFrameBytes(
    headerBytes: Int,
    declaredBodyBytes: Long,
): Int {
    val total =
        if (headerBytes > 0 && declaredBodyBytes > Long.MAX_VALUE - headerBytes) {
            Long.MAX_VALUE
        } else {
            declaredBodyBytes + headerBytes
        }
    if (declaredBodyBytes < 0 || total !in 0L..Int.MAX_VALUE.toLong()) {
        throw FrameTooLargeException(
            declaredBytes = if (declaredBodyBytes < 0) declaredBodyBytes else total,
            maxBytes = Int.MAX_VALUE,
        )
    }
    return total.toInt()
}

/** [declaredFrameBytes] for an unsigned 64-bit declaration (a QUIC varint, for one). */
public fun declaredFrameBytes(
    headerBytes: Int,
    declaredBodyBytes: ULong,
): Int =
    declaredFrameBytes(
        headerBytes,
        if (declaredBodyBytes > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else declaredBodyBytes.toLong(),
    )
