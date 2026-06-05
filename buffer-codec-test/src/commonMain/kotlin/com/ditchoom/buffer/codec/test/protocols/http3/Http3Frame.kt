package com.ditchoom.buffer.codec.test.protocols.http3

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.test.protocols.quic.QuicVarintCodec
import kotlin.jvm.JvmInline

/**
 * Stage 3/4a — the variable-width **dispatcher** vector: a `@DispatchOn`
 * discriminator whose wire width is itself variable.
 *
 * The HTTP/3 frame layout (RFC 9114 §7.1) is `Type (varint)` + `Length
 * (varint)` + `Frame Payload`, where `Type` is a QUIC variable-length integer
 * (RFC 9000 §16). [Http3FrameType] models that discriminator: its inner scalar
 * carries `@UseCodec(QuicVarintCodec)`, so the dispatcher is a
 * `Discriminator.Varint` — it can't pre-compute a fixed discriminator width and
 * instead measures it at runtime via the value class's own codec.
 *
 * The buffer library ships no QUIC encoding; [QuicVarintCodec] is test-support
 * (RFC 9000 §16) and the processor stays encoding-agnostic. Frame *bodies* are
 * fixed-size scalars here (not the real HTTP/3 length-prefixed payloads) so the
 * generated `peekFrameSize` is exactly testable: total = varint-type-width +
 * fixed-suffix.
 *
 * [Extension] uses frame type `0x40` (64), which needs a 2-byte QUIC varint, so
 * the suite exercises a discriminator wider than one byte on both decode and
 * peek — the property that distinguishes this from the fixed-width
 * `Discriminator.ValueClass` path.
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
    /** DATA — RFC 9114 §7.2.1, frame type 0x00. */
    @PacketType(value = 0x00)
    @ProtocolMessage
    data class Data(
        val frameType: Http3FrameType = Http3FrameType(0x00uL),
        val firstByte: UByte,
    ) : Http3Frame

    /** HEADERS — RFC 9114 §7.2.2, frame type 0x01. */
    @PacketType(value = 0x01)
    @ProtocolMessage
    data class Headers(
        val frameType: Http3FrameType = Http3FrameType(0x01uL),
        val fieldSectionTag: UShort,
    ) : Http3Frame

    /** SETTINGS — RFC 9114 §7.2.4, frame type 0x04. */
    @PacketType(value = 0x04)
    @ProtocolMessage
    data class Settings(
        val frameType: Http3FrameType = Http3FrameType(0x04uL),
        val identifier: UInt,
    ) : Http3Frame

    /** A reserved/greased extension frame whose type needs a 2-byte varint. */
    @PacketType(value = 0x40)
    @ProtocolMessage
    data class Extension(
        val frameType: Http3FrameType = Http3FrameType(0x40uL),
        val payload: UByte,
    ) : Http3Frame
}
