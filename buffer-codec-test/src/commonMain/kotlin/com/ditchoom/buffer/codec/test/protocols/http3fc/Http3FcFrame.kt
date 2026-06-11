package com.ditchoom.buffer.codec.test.protocols.http3fc

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.ForwardCompatible
import com.ditchoom.buffer.codec.annotations.FramedBy
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.UnknownVariant
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.test.protocols.quic.QuicVarintCodec
import kotlin.jvm.JvmInline

/**
 * The full forward-compatible HTTP/3 frame shape (RFC 9114 §7.1):
 * `Type (varint)` + `Length (varint)` + structured payload — the fixture
 * pairing the two variable-width mechanisms the stored-length
 * `protocols/http3` fixture does *not* exercise:
 *
 *  - **`@FramedBy` after a varint discriminator** — the Length is
 *    *computed* by the framework on encode and *bounds* the body on
 *    decode (strict consumption), so the model is **length-free**: no
 *    `length` constructor field for call sites to keep consistent.
 *  - **`@ForwardCompatible` on a varint union** — an unrecognized frame
 *    type (reserved/GREASE, RFC 9114 §9) is skipped-and-preserved into
 *    [Http3FcFrame.Unknown] carrying the full 62-bit opcode, instead of
 *    throwing. Re-encode goes through [Http3FcFrameType]'s codec, so a
 *    multi-byte type round-trips (minimal varint encoding).
 *
 * Payloads are structured per type, mirroring a real HTTP/3 client's
 * model: opaque [ReadBuffer] views (DATA), a `List` of nested messages
 * (SETTINGS), a bare varint (GOAWAY), and varint + opaque remainder
 * (PUSH_PROMISE).
 */
@JvmInline
@ProtocolMessage
value class Http3FcFrameType(
    @UseCodec(QuicVarintCodec::class) val raw: ULong,
) {
    /**
     * Dispatch projection. Clamped: a varint carries up to 62 bits, and
     * an out-of-`Int`-range type must NOT alias onto a known small type
     * via truncation (`(2^32).toInt() == 0` would dispatch as DATA!) —
     * `-1` never matches a `@PacketType`, so oversized types fall through
     * to the [Http3FcFrame.Unknown] preserve arm.
     */
    @DispatchValue
    val type: Int get() = if (raw <= Int.MAX_VALUE.toULong()) raw.toInt() else -1
}

@ProtocolMessage
@DispatchOn(Http3FcFrameType::class)
@FramedBy(Http3FcLengthCodec::class, after = "frameType")
@ForwardCompatible(unknown = Http3FcFrame.Unknown::class)
sealed interface Http3FcFrame {
    /** DATA — RFC 9114 §7.2.1, type 0x00. Opaque body bytes. */
    @PacketType(value = 0x00)
    @ProtocolMessage
    data class Data(
        val frameType: Http3FcFrameType = Http3FcFrameType(0x00uL),
        @RemainingBytes @UseCodec(RawBytesCodec::class) val payload: ReadBuffer,
    ) : Http3FcFrame

    /** SETTINGS — RFC 9114 §7.2.4, type 0x04. Id/value varint pairs to the frame end. */
    @PacketType(value = 0x04)
    @ProtocolMessage
    data class Settings(
        val frameType: Http3FcFrameType = Http3FcFrameType(0x04uL),
        @RemainingBytes val entries: List<Http3FcSetting>,
    ) : Http3FcFrame

    /** PUSH_PROMISE — RFC 9114 §7.2.5, type 0x05. Push id varint + opaque field section. */
    @PacketType(value = 0x05)
    @ProtocolMessage
    data class PushPromise(
        val frameType: Http3FcFrameType = Http3FcFrameType(0x05uL),
        @UseCodec(QuicVarintCodec::class) val pushId: ULong,
        @RemainingBytes @UseCodec(RawBytesCodec::class) val encodedFieldSection: ReadBuffer,
    ) : Http3FcFrame

    /** GOAWAY — RFC 9114 §7.2.6, type 0x07. The whole payload is one varint. */
    @PacketType(value = 0x07)
    @ProtocolMessage
    data class GoAway(
        val frameType: Http3FcFrameType = Http3FcFrameType(0x07uL),
        @UseCodec(QuicVarintCodec::class) val id: ULong,
    ) : Http3FcFrame

    /** A *known* extension frame whose type needs a 2-byte varint (0x40 = 64). */
    @PacketType(value = 0x40)
    @ProtocolMessage
    data class Extension(
        val frameType: Http3FcFrameType = Http3FcFrameType(0x40uL),
        @RemainingBytes @UseCodec(RawBytesCodec::class) val payload: ReadBuffer,
    ) : Http3FcFrame

    /**
     * Any frame whose type is not modeled above — reserved/GREASE types.
     * [opcode] preserves the full 62-bit type value; [raw] the framed
     * payload, re-emitted verbatim on encode (RFC 9114 §9 ignore-unknown).
     */
    @UnknownVariant
    data class Unknown(
        val opcode: ULong,
        val raw: ReadBuffer,
    ) : Http3FcFrame
}

/** One SETTINGS entry: a varint identifier and its varint value. */
@ProtocolMessage
data class Http3FcSetting(
    @UseCodec(QuicVarintCodec::class) val identifier: ULong,
    @UseCodec(QuicVarintCodec::class) val value: ULong,
)
