package com.ditchoom.buffer.codec

import kotlin.jvm.JvmInline

/**
 * What [FrameDetector.frameSize] knows about the next frame without consuming it: its
 * declared size, that more bytes are needed before the size is known, or that the codec
 * is not self-framing.
 *
 * A declared size has two parts: [headerBytes][fold] — the framing prefix the codec read
 * to learn the size (type and length fields, say) — and `bodyBytes`, the rest of the
 * frame. A reader that will not decode a frame (it exceeds a cap, or its type is
 * unknown) can then stream or skip the body without parsing the header again. A codec
 * that only knows the total declares a header of 0.
 *
 * A declared size says nothing about arrival: the frame is complete once
 * `stream.available() - baseOffset` reaches `headerBytes + bodyBytes`.
 *
 * A value class over one `Long`: header in bits 47..62, body in bits 0..46, and two
 * negative values for the other states.
 *
 * ```kotlin
 * codec.frameSize(stream).fold(
 *     declared = { header, body ->
 *         if (header + body > cap) skip(header, body) else decodeWhenComplete(header + body)
 *     },
 *     needsMoreData = { readMore() },
 *     unframed = { frameItExplicitly() },
 * )
 * ```
 */
@JvmInline
public value class FrameSize
    @PublishedApi
    internal constructor(
        @PublishedApi internal val bits: Long,
    ) {
        /**
         * Branches on the three states; inline, so the lambdas cost nothing and may `return`.
         * `headerBytes + bodyBytes` cannot overflow: the header is at most [MAX_HEADER_BYTES]
         * and the body at most [MAX_BODY_BYTES].
         */
        public inline fun <R> fold(
            declared: (headerBytes: Int, bodyBytes: Long) -> R,
            needsMoreData: () -> R,
            unframed: () -> R,
        ): R =
            when {
                bits >= 0L -> declared((bits ushr HEADER_SHIFT).toInt(), bits and BODY_MASK)
                bits == NEEDS_MORE_DATA_BITS -> needsMoreData()
                else -> unframed()
            }

        /** [fold] with the declared size as one total, `headerBytes + bodyBytes`. */
        public inline fun <R> foldTotal(
            declared: (totalBytes: Long) -> R,
            needsMoreData: () -> R,
            unframed: () -> R,
        ): R = fold({ header, body -> declared(header + body) }, needsMoreData, unframed)

        override fun toString(): String =
            fold(
                declared = { header, body -> "FrameSize.declared(headerBytes=$header, bodyBytes=$body)" },
                needsMoreData = { "FrameSize.NeedsMoreData" },
                unframed = { "FrameSize.Unframed" },
            )

        public companion object {
            /** The largest framing header a declared size can carry: 65535 bytes. */
            public const val MAX_HEADER_BYTES: Int = 0xFFFF

            /**
             * The largest body a declared size carries exactly (2^47 - 1 bytes, 128 TiB);
             * a larger declaration saturates here, which no frame cap admits.
             */
            public const val MAX_BODY_BYTES: Long = (1L shl 47) - 1

            @PublishedApi internal const val HEADER_SHIFT: Int = 47

            @PublishedApi internal const val BODY_MASK: Long = MAX_BODY_BYTES

            @PublishedApi internal const val NEEDS_MORE_DATA_BITS: Long = -1L

            @PublishedApi internal const val UNFRAMED_BITS: Long = -2L

            /** More bytes are needed before the frame size is known. */
            public val NeedsMoreData: FrameSize get() = FrameSize(NEEDS_MORE_DATA_BITS)

            /** The codec does not participate in frame detection. */
            public val Unframed: FrameSize get() = FrameSize(UNFRAMED_BITS)

            /**
             * A frame of a [headerBytes]-byte framing header followed by a body the wire
             * declares as [bodyBytes]. A body past [MAX_BODY_BYTES] saturates there.
             *
             * @throws FrameTooLargeException when [bodyBytes] is negative, or [headerBytes]
             *   is negative or past [MAX_HEADER_BYTES]: no frame has that shape.
             */
            public fun declared(
                headerBytes: Int,
                bodyBytes: Long,
            ): FrameSize {
                if (bodyBytes < 0L) throw FrameTooLargeException(bodyBytes, maxBytes = Int.MAX_VALUE)
                if (headerBytes !in 0..MAX_HEADER_BYTES) {
                    throw FrameTooLargeException(headerBytes.toLong(), maxBytes = MAX_HEADER_BYTES)
                }
                val body = if (bodyBytes > MAX_BODY_BYTES) MAX_BODY_BYTES else bodyBytes
                return FrameSize((headerBytes.toLong() shl HEADER_SHIFT) or body)
            }

            /** [declared] for an unsigned 64-bit body length (a QUIC varint, for one). */
            public fun declared(
                headerBytes: Int,
                bodyBytes: ULong,
            ): FrameSize =
                declared(
                    headerBytes,
                    if (bodyBytes > MAX_BODY_BYTES.toULong()) MAX_BODY_BYTES else bodyBytes.toLong(),
                )
        }
    }
