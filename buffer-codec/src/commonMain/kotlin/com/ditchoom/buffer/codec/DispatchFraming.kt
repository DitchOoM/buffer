package com.ditchoom.buffer.codec

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.stream.PeekResult
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * Strategy for framing the wire bytes that follow a `@DispatchOn` discriminator.
 *
 * Two shapes are supported:
 *
 *  * **Peek-only** — the wire shape is `[discriminator][body]` and the discriminator
 *    (or its prefix) carries enough information to compute the full frame size.
 *    Implementations need only [peekFrameSize]; the variant body consumes the rest of
 *    the (pre-sliced) buffer. WebSocket frames are this shape: the payload length lives
 *    inside the header bytes.
 *  * **Body-length-prefixed** — the wire shape is `[discriminator][bodyLength][body]`.
 *    Implementations extend [BodyLengthFraming] and provide `readBodyLength` /
 *    `writeBodyLength` / `bodyLengthSize` so the dispatcher can slice the body.
 *    MQTT, AMQP, HTTP/2 and most TLV protocols are this shape.
 *
 * The processor auto-discovers framing by checking whether the discriminator class's
 * companion object implements this interface (or [BodyLengthFraming]). To override
 * discovery (e.g. when the discriminator is shared across multiple sealed roots that
 * need different framings), pass an explicit framer:
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
    }
}

/**
 * Body-length-prefixed framing for `@DispatchOn` discriminators. Extends [DispatchFraming]
 * with the read/write/size triple the dispatcher uses to slice the body off the wire.
 *
 * ```kotlin
 * @JvmInline
 * value class MqttFixedHeader(val raw: UByte) {
 *     @DispatchValue val packetType: Int get() = (raw.toInt() shr 4) and 0x0F
 *     companion object : BodyLengthFraming<MqttFixedHeader> {
 *         override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult { ... }
 *         override fun readBodyLength(buffer: ReadBuffer): Int =
 *             buffer.readVariableByteInteger()
 *         override fun writeBodyLength(buffer: WriteBuffer, n: Int) {
 *             buffer.writeVariableByteInteger(n)
 *         }
 *         override fun bodyLengthSize(n: Int): Int = variableByteSizeInt(n)
 *     }
 * }
 * ```
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
