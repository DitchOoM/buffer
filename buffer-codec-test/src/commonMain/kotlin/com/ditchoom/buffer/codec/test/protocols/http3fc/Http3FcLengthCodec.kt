package com.ditchoom.buffer.codec.test.protocols.http3fc

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.BoundingLengthCodec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.quic.QuicVarintCodec

/**
 * The HTTP/3 frame **Length** field (RFC 9114 §7.1) as the `@FramedBy`
 * framing codec of the forward-compatible fixture: a QUIC varint
 * (RFC 9000 §16) carrying the byte count of the payload that follows.
 *
 * Unlike the stored-length fixture's `Http3LengthCodec`
 * (`BoundingLengthCodec<ULong>`, a constructor field the consumer must
 * keep consistent), this one is `BoundingLengthCodec<UInt>` so it can
 * drive [com.ditchoom.buffer.codec.FramedEncoder] — the framework
 * *computes* the prefix from the encoded body, keeping the model
 * length-free. The UInt domain caps a frame body at 4 GiB; a peer-sent
 * length beyond that throws rather than truncating (the wire allows
 * 62-bit lengths, but a >4 GiB frame is not decodable into a single
 * bounded buffer anyway).
 */
object Http3FcLengthCodec : BoundingLengthCodec<UInt> {
    /** A QUIC varint is at most 8 bytes (the 62-bit length class). */
    override val maxWireSize: Int = 8

    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): UInt {
        val position = buffer.position()
        val value = QuicVarintCodec.decode(buffer, context)
        if (value > UInt.MAX_VALUE.toULong()) {
            throw DecodeException(
                fieldPath = "Http3FcFrame.length",
                bufferPosition = position,
                expected = "frame length <= ${UInt.MAX_VALUE}",
                actual = value.toString(),
            )
        }
        return value.toUInt()
    }

    override fun encode(
        buffer: WriteBuffer,
        value: UInt,
        context: EncodeContext,
    ) = QuicVarintCodec.encode(buffer, value.toULong(), context)

    override fun wireSize(
        value: UInt,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(QuicVarintCodec.encodedLength(value.toULong()))

    override fun applyBound(
        buffer: ReadBuffer,
        decodedValue: UInt,
    ) {
        buffer.setLimit(buffer.position() + decodedValue.toInt())
    }
}
