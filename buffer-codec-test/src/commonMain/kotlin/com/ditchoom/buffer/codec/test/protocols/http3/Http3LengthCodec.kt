package com.ditchoom.buffer.codec.test.protocols.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.BoundingLengthCodec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.quic.QuicVarintCodec

/**
 * The HTTP/3 frame **Length** field (RFC 9114 §7.1) as a [BoundingLengthCodec].
 *
 * Every HTTP/3 frame is `Type (varint)` + `Length (varint)` + `Frame Payload`,
 * where `Length` is a QUIC variable-length integer (RFC 9000 §16) giving the
 * byte count of the payload that follows. That is the textbook bounding-length
 * shape: [decode] reads the varint, [applyBound] narrows `buffer.limit()` to
 * `position + value`, and a trailing `@RemainingBytes` payload field reads
 * exactly that many bytes.
 *
 * Distinct from [QuicVarintCodec] used as the frame-*type* discriminator
 * ([Http3FrameType]): there the varint is self-delimiting (its width is the
 * whole contribution); here the varint *bounds* the body, so the frame total is
 * `typeWidth + lengthWidth + value`. Both roles wrap the same QUIC varint wire
 * format — this codec delegates the encoding to [QuicVarintCodec] so the two
 * share one source of truth; the buffer library itself ships no QUIC encoding.
 */
object Http3LengthCodec : BoundingLengthCodec<ULong> {
    /** A QUIC varint is at most 8 bytes (the 62-bit length class). */
    override val maxWireSize: Int = 8

    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): ULong = QuicVarintCodec.decode(buffer, context)

    override fun encode(
        buffer: WriteBuffer,
        value: ULong,
        context: EncodeContext,
    ) = QuicVarintCodec.encode(buffer, value, context)

    override fun wireSize(
        value: ULong,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(QuicVarintCodec.encodedLength(value))

    override fun applyBound(
        buffer: ReadBuffer,
        decodedValue: ULong,
    ) {
        buffer.setLimit(buffer.position() + decodedValue.toInt())
    }
}
