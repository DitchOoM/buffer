package com.ditchoom.buffer.codec

import com.ditchoom.buffer.ReadBuffer

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
     * `buffer.setLimit(buffer.position() + decodedValue.toInt())`.
     */
    fun applyBound(
        buffer: ReadBuffer,
        decodedValue: T,
    )

    /**
     * The maximum number of bytes this codec ever writes for a single
     * encoded value. Used by the `@FramedBy` slicing-scheme emitter to
     * size the slack region at the front of the encode buffer so the
     * prefix can be right-flushed against the body without shifting body
     * bytes. For variable-width codecs (e.g. MQTT remaining-length: 1..4
     * bytes), this is the upper bound; for fixed-width codecs it equals
     * the wire width. Implementations should pick the smallest value
     * that satisfies `wireSize(maxValueOnWire).asExact == maxWireSize`.
     */
    val maxWireSize: Int
}
