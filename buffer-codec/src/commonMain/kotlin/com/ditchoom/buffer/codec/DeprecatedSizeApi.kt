@file:Suppress("DEPRECATION")

package com.ditchoom.buffer.codec

import kotlin.jvm.JvmInline

/*
 * The boxed size API: PeekResult, WireSize, VarLenPeek and the bridges between them and
 * FrameSize / WireByteCount. Everything in this file is deprecated and is removed in the
 * next major release.
 */

/**
 * Reports the encoded byte size of a value. Boxes [Exact] on every call; [WireByteCount]
 * answers the same question without allocating.
 */
@Deprecated(
    "Use WireByteCount, returned by Encoder.wireBytes. Removed in the next major release.",
    ReplaceWith("WireByteCount", "com.ditchoom.buffer.codec.WireByteCount"),
)
sealed interface WireSize {
    /** Codec knows the exact byte count up front; framework pre-allocates. */
    @JvmInline
    value class Exact(
        val bytes: Int,
    ) : WireSize

    /** Framework uses a growable write buffer + back-patches length-prefixed framing. */
    data object BackPatch : WireSize
}

/**
 * Result of peeking a frame size from a [com.ditchoom.buffer.stream.StreamProcessor]. Boxes
 * [Complete] on every call and cannot report a size whose bytes have not all arrived;
 * [FrameSize] does both.
 */
@Deprecated(
    "Use FrameSize, returned by FrameDetector.frameSize. Removed in the next major release.",
    ReplaceWith("FrameSize", "com.ditchoom.buffer.codec.FrameSize"),
)
sealed interface PeekResult {
    /** A complete frame is available; [bytes] is its total size. */
    @JvmInline
    value class Complete(
        val bytes: Int,
    ) : PeekResult

    /** More bytes are required before a frame size can be determined. */
    data object NeedsMoreData : PeekResult

    /** This codec does not participate in frame detection. */
    data object NoFraming : PeekResult
}

/**
 * Result of [VariableLengthCodec.peekValue]: the decoded value and its width, or that the
 * prefix is too short. Framing needs only the width, which [VariableLengthCodec.frameSize]
 * reports without decoding.
 */
@Deprecated(
    "Use VariableLengthCodec.frameSize for the width. Removed in the next major release.",
    ReplaceWith("FrameSize", "com.ditchoom.buffer.codec.FrameSize"),
)
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
 * This answer for a caller of the deprecated [FrameDetector.peekFrameSize], given the bytes
 * [available] from the peeked offset: a declared size is [PeekResult.Complete] once that
 * many bytes have arrived.
 *
 * @throws FrameTooLargeException for a declared size an `Int` cannot express.
 */
@Deprecated(
    "Bridge for implementing the deprecated FrameDetector.peekFrameSize; call frameSize instead. " +
        "Removed in the next major release.",
)
public fun FrameSize.toPeekResult(available: Int): PeekResult =
    foldTotal(
        declared = { bytes ->
            when {
                bytes > Int.MAX_VALUE -> throw FrameTooLargeException(bytes, maxBytes = Int.MAX_VALUE)
                bytes <= available -> PeekResult.Complete(bytes.toInt())
                else -> PeekResult.NeedsMoreData
            }
        },
        needsMoreData = { PeekResult.NeedsMoreData },
        unframed = { PeekResult.NoFraming },
    )

/**
 * The same answer from a detector still on the deprecated [PeekResult]: a complete frame
 * is a declared size with a header of 0.
 *
 * @throws FrameTooLargeException for a negative [PeekResult.Complete.bytes].
 */
@Deprecated("Bridge from the deprecated PeekResult; implement frameSize instead. Removed in the next major release.")
public fun FrameSize.Companion.from(old: PeekResult): FrameSize =
    when (old) {
        is PeekResult.Complete -> FrameSize.declared(0, old.bytes.toLong())
        PeekResult.NeedsMoreData -> FrameSize.NeedsMoreData
        PeekResult.NoFraming -> FrameSize.Unframed
    }

/**
 * This answer for a caller of the deprecated [Encoder.wireSize]. An exact size past `Int`
 * range cannot be pre-allocated, so it becomes [WireSize.BackPatch].
 */
@Deprecated(
    "Bridge for implementing the deprecated Encoder.wireSize; call wireBytes instead. " +
        "Removed in the next major release.",
)
public fun WireByteCount.toWireSize(): WireSize =
    fold(
        exact = { if (it > Int.MAX_VALUE) WireSize.BackPatch else WireSize.Exact(it.toInt()) },
        backPatch = { WireSize.BackPatch },
    )

/**
 * The same answer from an encoder still on the deprecated [WireSize].
 *
 * @throws EncodeException for a negative [WireSize.Exact.bytes].
 */
@Deprecated("Bridge from the deprecated WireSize; implement wireBytes instead. Removed in the next major release.")
public fun WireByteCount.Companion.from(old: WireSize): WireByteCount =
    when (old) {
        is WireSize.Exact -> WireByteCount.exact(old.bytes.toLong())
        WireSize.BackPatch -> WireByteCount.BackPatch
    }
