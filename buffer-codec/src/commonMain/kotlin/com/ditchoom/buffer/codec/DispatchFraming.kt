package com.ditchoom.buffer.codec

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.stream.PeekResult
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * Strategy for framing the wire bytes that follow a `@DispatchOn` discriminator.
 *
 * The processor auto-discovers framing by checking whether the discriminator class's
 * companion object implements this interface. If it does, the generated codec emits
 * calls to [peekFrameSize] / [readBodyLength] / [writeBodyLength] around variant
 * decode/encode. If not, the existing default applies (variant body consumes the
 * rest of the buffer).
 *
 * To override discovery (e.g. when the discriminator is shared across multiple sealed
 * roots that need different framings), pass an explicit framer:
 *
 * ```kotlin
 * @DispatchOn(MyTag::class, framing = MyAltFraming::class)
 * sealed interface MyProtocol { ... }
 * ```
 *
 * Implementations must be Kotlin `object`s (companion or named) so the processor can
 * reference them without instantiation.
 *
 * @param D the discriminator type (the same `KClass` passed as `@DispatchOn`'s first
 *   argument).
 */
interface DispatchFraming<D : Any> {
    /**
     * Peek the full frame size (discriminator + framing + body) without consuming bytes.
     *
     * @return [PeekResult.Size] with the total frame size, or [PeekResult.NeedsMoreData]
     *   if not enough bytes are buffered.
     */
    fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult

    /**
     * Read the body-length field, advancing `buffer.position()` past it. Called after
     * the discriminator has been read; returns the number of body bytes the variant
     * codec will consume.
     */
    fun readBodyLength(buffer: ReadBuffer): Int

    /**
     * Write the body-length field. Called after the discriminator and before the
     * variant body. [n] is the variant body's wire size.
     */
    fun writeBodyLength(
        buffer: WriteBuffer,
        n: Int,
    )

    /**
     * Returns the wire byte count of the body-length prefix when [writeBodyLength] is
     * invoked with [n]. Used by generated `wireSize` to compute the exact dispatch frame
     * length: discriminator + bodyLengthSize(body) + body.
     */
    fun bodyLengthSize(n: Int): Int

    /**
     * Sentinel framer used as the default for `@DispatchOn.framing`. Means "use the
     * discriminator's companion-object framer if it implements [DispatchFraming], else
     * dispatch unframed (variant body consumes the rest of the buffer)."
     *
     * Methods are never invoked — the processor inspects [Inherit] by class identity at
     * KSP time and routes accordingly.
     */
    object Inherit : DispatchFraming<Any> {
        override fun peekFrameSize(
            stream: StreamProcessor,
            baseOffset: Int,
        ): PeekResult = error("DispatchFraming.Inherit is a sentinel; never invoked at runtime")

        override fun readBodyLength(buffer: ReadBuffer): Int =
            error("DispatchFraming.Inherit is a sentinel; never invoked at runtime")

        override fun writeBodyLength(
            buffer: WriteBuffer,
            n: Int,
        ): Unit = error("DispatchFraming.Inherit is a sentinel; never invoked at runtime")

        override fun bodyLengthSize(n: Int): Int =
            error("DispatchFraming.Inherit is a sentinel; never invoked at runtime")
    }
}
