package com.ditchoom.buffer.codec

import kotlin.jvm.JvmInline

/**
 * What [FrameDetector.frameSize] knows about the next frame without consuming it: its
 * declared total size in bytes, that more bytes are needed before the size is known, or
 * that the codec is not self-framing.
 *
 * A declared size says nothing about arrival: the frame is complete once
 * `stream.available() - baseOffset` reaches it. A value class over one `Long`, returned
 * directly: on the JVM it is a bare `long`, so a peek allocates nothing.
 *
 * ```kotlin
 * codec.frameSize(stream).fold(
 *     declared = { bytes -> if (stream.available() >= bytes) decode(bytes.toInt()) else readMore() },
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
        /** Branches on the three states; inline, so the lambdas cost nothing and may `return`. */
        public inline fun <R> fold(
            declared: (bytes: Long) -> R,
            needsMoreData: () -> R,
            unframed: () -> R,
        ): R =
            when {
                bits >= 0L -> declared(bits)
                bits == NEEDS_MORE_DATA_BITS -> needsMoreData()
                else -> unframed()
            }

        /**
         * This answer for a caller of the deprecated [FrameDetector.peekFrameSize], given the
         * bytes [available] from the peeked offset.
         *
         * @throws FrameTooLargeException for a declared size an `Int` cannot express.
         */
        @Suppress("DEPRECATION")
        @Deprecated("Bridge for implementing the deprecated FrameDetector.peekFrameSize; call frameSize instead.")
        public fun toPeekResult(available: Int): PeekResult =
            fold(
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

        override fun toString(): String =
            fold(
                declared = { "FrameSize.declared($it)" },
                needsMoreData = { "FrameSize.NeedsMoreData" },
                unframed = { "FrameSize.Unframed" },
            )

        public companion object {
            @PublishedApi internal const val NEEDS_MORE_DATA_BITS: Long = -1L

            @PublishedApi internal const val UNFRAMED_BITS: Long = -2L

            /** More bytes are needed before the frame size is known. */
            public val NeedsMoreData: FrameSize get() = FrameSize(NEEDS_MORE_DATA_BITS)

            /** The codec does not participate in frame detection. */
            public val Unframed: FrameSize get() = FrameSize(UNFRAMED_BITS)

            /**
             * The next frame is [bytes] long in total.
             *
             * @throws FrameTooLargeException when [bytes] is negative: no frame has that size.
             */
            public fun declared(bytes: Long): FrameSize {
                if (bytes < 0L) throw FrameTooLargeException(declaredBytes = bytes, maxBytes = Int.MAX_VALUE)
                return FrameSize(bytes)
            }

            /**
             * A frame of [headerBytes] followed by a body the wire declares as [bodyBytes].
             * The sum saturates at `Long.MAX_VALUE` instead of wrapping.
             *
             * @throws FrameTooLargeException when either part is negative.
             */
            public fun declared(
                headerBytes: Int,
                bodyBytes: Long,
            ): FrameSize {
                if (headerBytes < 0 || bodyBytes < 0L) {
                    val negative = if (bodyBytes < 0L) bodyBytes else headerBytes.toLong()
                    throw FrameTooLargeException(declaredBytes = negative, maxBytes = Int.MAX_VALUE)
                }
                val saturated = bodyBytes > Long.MAX_VALUE - headerBytes
                return FrameSize(if (saturated) Long.MAX_VALUE else bodyBytes + headerBytes)
            }

            /** [declared] for an unsigned 64-bit body length (a QUIC varint, for one). */
            public fun declared(
                headerBytes: Int,
                bodyBytes: ULong,
            ): FrameSize =
                declared(
                    headerBytes,
                    if (bodyBytes > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else bodyBytes.toLong(),
                )

            /** The same answer from a codec still on the deprecated [PeekResult]. */
            @Suppress("DEPRECATION")
            @Deprecated("Bridge from the deprecated PeekResult; implement frameSize instead.")
            public fun from(old: PeekResult): FrameSize =
                when (old) {
                    is PeekResult.Complete -> declared(old.bytes.toLong())
                    PeekResult.NeedsMoreData -> NeedsMoreData
                    PeekResult.NoFraming -> Unframed
                }
        }
    }
