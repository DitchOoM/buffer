package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferUnderflowException
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * A length [Codec] that narrows [ReadBuffer.limit] to bound the subsequent
 * decode region.
 *
 * Codec authors implement this sub-interface (instead of plain [Codec]) when
 * the length value should constrain the buffer for following fields — e.g.
 * MQTT v3.1.1 §2.2.3 remaining-length, ASN.1 BER short/long-form length, or
 * any other "the next N bytes are payload" framing. The processor detects
 * this sub-interface on a `@UseCodec`-targeted field and wraps the bounded
 * region in:
 *
 * ```
 * val outerLimit = buffer.limit()
 * val value = lengthCodec.decode(buffer, ctx)
 * lengthCodec.applyBound(buffer, value)
 * try {
 *     // ... decode bounded fields ...
 * } finally {
 *     buffer.setLimit(outerLimit)
 * }
 * ```
 *
 * Codecs whose decoded value is consumed by a sibling `@LengthFrom`/`@LengthPrefixed`
 * (rather than narrowing the buffer) implement plain [Codec] and skip this
 * sub-interface.
 */
interface BoundingLengthCodec<T : Any> : Codec<T> {
    /**
     * Narrows [buffer]'s limit so that the bounded region ends after
     * [decodedValue] bytes from the current position. Called after
     * [decode][Decoder.decode] returns. Implementations typically call
     * `buffer.setLimit(buffer.position() + declaredFrameBytes(0, decodedValue.toLong()))`:
     * a bare `decodedValue.toInt()` wraps a declaration past [Int] range
     * into a small bound. Generated callers reject such a value before
     * calling this.
     */
    fun applyBound(
        buffer: ReadBuffer,
        decodedValue: T,
    )

    /**
     * The length field at [baseOffset] of [stream], read without consuming it:
     * `FrameSize.declared(headerBytes = <width of the field>, bodyBytes = <declared length>)`,
     * or [FrameSize.NeedsMoreData] while the field has not fully arrived. Generated peeks
     * call this.
     *
     * Override it with [StreamProcessor.peekByte] reads (or the other fixed-width peeks):
     * that allocates nothing. The default decodes the field from a peeked view of up to
     * [maxWireSize] bytes, which allocates the view and boxes the decoded value; it reads a
     * value of an integer type (`UByte`..`ULong`, `Byte`..`Long`) and answers
     * [FrameSize.Unframed] for any other `T`.
     */
    fun peekLength(
        stream: StreamProcessor,
        baseOffset: Int,
    ): FrameSize = peekLengthByDecoding(stream, baseOffset)

    /**
     * How many bytes this codec writes for a declared length of [bodyBytes], without
     * passing the value through the generic `T` (which boxes a `UInt`). Override it with
     * the exact width.
     *
     * The default is [WireByteCount.BackPatch]: the width is not known without encoding, so
     * callers size the field through [wireBytes] with the length as a `T`, which boxes.
     */
    fun encodedWidth(bodyBytes: Long): WireByteCount = WireByteCount.BackPatch

    /**
     * The maximum number of bytes this codec ever writes for a single
     * encoded value. Used by the `@FramedBy` slicing-scheme emitter to
     * size the slack region at the front of the encode buffer so the
     * prefix can be right-flushed against the body without shifting body
     * bytes. For variable-width codecs (e.g. MQTT remaining-length: 1..4
     * bytes), this is the upper bound; for fixed-width codecs it equals
     * the wire width.
     */
    val maxWireSize: Int
}

/** [BoundingLengthCodec.peekLength]'s default: decode the field from a peeked view. */
private fun <T : Any> BoundingLengthCodec<T>.peekLengthByDecoding(
    stream: StreamProcessor,
    baseOffset: Int,
): FrameSize {
    val budget = maxWireSize
    val view = stream.peekBuffer(baseOffset, budget) ?: return FrameSize.NeedsMoreData
    try {
        val start = view.position()
        val value =
            try {
                decode(view, DecodeContext.Empty)
            } catch (shortView: BufferUnderflowException) {
                if (view.limit() - start < budget) return FrameSize.NeedsMoreData
                throw shortView
            } catch (shortView: IndexOutOfBoundsException) {
                if (view.limit() - start < budget) return FrameSize.NeedsMoreData
                throw shortView
            }
        val width = view.position() - start
        return when (value) {
            is ULong -> FrameSize.declared(width, value)
            is UInt -> FrameSize.declared(width, value.toLong())
            is UShort -> FrameSize.declared(width, value.toLong())
            is UByte -> FrameSize.declared(width, value.toLong())
            is Long -> FrameSize.declared(width, value)
            is Int -> FrameSize.declared(width, value.toLong())
            is Short -> FrameSize.declared(width, value.toLong())
            is Byte -> FrameSize.declared(width, value.toLong())
            else -> FrameSize.Unframed
        }
    } finally {
        (view as? PlatformBuffer)?.freeNativeMemory()
    }
}
