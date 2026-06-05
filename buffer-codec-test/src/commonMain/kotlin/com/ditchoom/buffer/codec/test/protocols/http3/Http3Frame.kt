package com.ditchoom.buffer.codec.test.protocols.http3

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryData
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec
import com.ditchoom.buffer.codec.test.protocols.quic.QuicVarintCodec
import kotlin.jvm.JvmInline

/**
 * The real HTTP/3 frame (RFC 9114 §7.1): `Type (varint)` + `Length (varint)` +
 * `Frame Payload`. This fixture exercises two variable-width mechanisms at once,
 * both keyed off the QUIC variable-length integer (RFC 9000 §16):
 *
 *  - **A variable-width dispatcher.** [Http3FrameType] is the `@DispatchOn`
 *    discriminator; its inner scalar carries `@UseCodec(QuicVarintCodec)`, so
 *    the dispatcher is a `Discriminator.Varint` — it measures the discriminator
 *    width at runtime rather than assuming one byte. Frame types `0x00`/`0x01`/
 *    `0x04` are 1-byte varints; [Extension] (type `0x40` = 64) needs 2 bytes.
 *
 *  - **A variable-width bounding length.** Each variant's `length` field is the
 *    real RFC 9114 §7.1 Length, modeled as [Http3LengthCodec] (a
 *    `BoundingLengthCodec`). It narrows `buffer.limit()` to the payload extent so
 *    the trailing `@RemainingBytes payload` reads exactly `length` bytes — and
 *    the generated `peekFrameSize` sizes the whole frame as
 *    `typeWidth + lengthWidth + length`.
 *
 * The length sits immediately before the payload (no field folds between them),
 * which is the common bounding shape; the WebSocket frame is the contrasting
 * case where a masking key folds between the length and the body.
 *
 * The buffer library ships no QUIC encoding; [QuicVarintCodec] / [Http3LengthCodec]
 * are test-support and the processor stays encoding-agnostic. Frame payloads are
 * opaque [BinaryData] here — RFC 9114 §7.1 defines the generic frame as
 * Type + Length + opaque Frame Payload; per-type payload structure (QPACK field
 * sections, SETTINGS id/value pairs) is a nested concern above the framing layer.
 */
@JvmInline
@ProtocolMessage
value class Http3FrameType(
    @UseCodec(QuicVarintCodec::class) val raw: ULong,
) {
    @DispatchValue
    val type: Int get() = raw.toInt()
}

@DispatchOn(Http3FrameType::class)
@ProtocolMessage
sealed interface Http3Frame {
    /** DATA — RFC 9114 §7.2.1, frame type 0x00. Payload is opaque application data. */
    @PacketType(value = 0x00)
    @ProtocolMessage
    data class Data(
        val frameType: Http3FrameType = Http3FrameType(0x00uL),
        @UseCodec(Http3LengthCodec::class) val length: ULong,
        @RemainingBytes @UseCodec(BinaryDataCodec::class) val payload: BinaryData,
    ) : Http3Frame

    /** HEADERS — RFC 9114 §7.2.2, frame type 0x01. Payload is a QPACK field section. */
    @PacketType(value = 0x01)
    @ProtocolMessage
    data class Headers(
        val frameType: Http3FrameType = Http3FrameType(0x01uL),
        @UseCodec(Http3LengthCodec::class) val length: ULong,
        @RemainingBytes @UseCodec(BinaryDataCodec::class) val fieldSection: BinaryData,
    ) : Http3Frame

    /** SETTINGS — RFC 9114 §7.2.4, frame type 0x04. Payload is a sequence of id/value pairs. */
    @PacketType(value = 0x04)
    @ProtocolMessage
    data class Settings(
        val frameType: Http3FrameType = Http3FrameType(0x04uL),
        @UseCodec(Http3LengthCodec::class) val length: ULong,
        @RemainingBytes @UseCodec(BinaryDataCodec::class) val parameters: BinaryData,
    ) : Http3Frame

    /** A reserved/greased extension frame whose type needs a 2-byte varint. */
    @PacketType(value = 0x40)
    @ProtocolMessage
    data class Extension(
        val frameType: Http3FrameType = Http3FrameType(0x40uL),
        @UseCodec(Http3LengthCodec::class) val length: ULong,
        @RemainingBytes @UseCodec(BinaryDataCodec::class) val payload: BinaryData,
    ) : Http3Frame
}
