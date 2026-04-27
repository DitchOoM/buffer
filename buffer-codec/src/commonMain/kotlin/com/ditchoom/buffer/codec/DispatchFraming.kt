package com.ditchoom.buffer.codec

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.stream.PeekResult
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * Peek-only frame-size strategy for a `@DispatchOn` sealed root.
 *
 * The processor auto-discovers framing by checking whether the discriminator class's
 * companion object implements this interface (or its [BodyLengthFraming] subtype).
 * When found, the generated dispatcher's `peekFrameSize` delegates here so callers
 * can size frames on a [StreamProcessor] without consuming bytes.
 *
 * **Use this base interface** when the wire format is `[header-with-length-inside][body]`
 * and the variant codec reads the body in place — the dispatcher does not slice. This
 * fits HTTP/2 frames, TLS records, AMQP 1.0, RIFF/PNG/MP4 containers, and most
 * variable-header protocols where the full frame size is computable from the header
 * but no separate body-length field exists for a generic dispatcher to read or write.
 *
 * **Use [BodyLengthFraming]** when the wire shape is `[discriminator][bodyLength][body]`
 * with an explicit body-length prefix the dispatcher actively reads (slicing the body)
 * and writes (computing it from variant `wireSize`). MQTT's `[byte1][VBI][body]` is the
 * canonical example.
 *
 * To override companion-object discovery, pass an explicit framer:
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
     * Peek the full frame size (header + body) without consuming bytes.
     *
     * @return [PeekResult.Size] with the total frame size in bytes, or
     *   [PeekResult.NeedsMoreData] if not enough bytes are buffered to determine the
     *   frame boundary.
     */
    fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult

    /**
     * Sentinel framer used as the default for `@DispatchOn.framing`. Means "use the
     * discriminator's companion-object framer if it implements [DispatchFraming] (or
     * [BodyLengthFraming]), else dispatch unframed."
     *
     * Methods are never invoked — the processor inspects [Inherit] by class identity at
     * KSP time and routes accordingly.
     */
    object Inherit : DispatchFraming<Any> {
        override fun peekFrameSize(
            stream: StreamProcessor,
            baseOffset: Int,
        ): PeekResult = error("DispatchFraming.Inherit is a sentinel; never invoked at runtime")
    }
}

/**
 * Body-length-prefixed framing: `[discriminator][bodyLength][body]`. The generated
 * dispatcher reads [readBodyLength] after the discriminator, slices the body to that
 * length, runs the variant codec on the slice, and asserts the variant consumed every
 * byte (body-overrun guard throws the configured `onUnknownDiscriminator` exception).
 *
 * On encode, the dispatcher computes the variant body's `wireSize`, calls
 * [writeBodyLength] to write the prefix, then encodes the body. [bodyLengthSize] feeds
 * the generated `wireSize` so frames report exact wire byte counts.
 *
 * Use [DispatchFraming] (peek-only) for protocols whose framing is more complex than a
 * single length-prefix field — variable headers with embedded sizes, container formats,
 * or anywhere the dispatcher does not actively maintain a body-length field.
 */
interface BodyLengthFraming<D : Any> : DispatchFraming<D> {
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
}
