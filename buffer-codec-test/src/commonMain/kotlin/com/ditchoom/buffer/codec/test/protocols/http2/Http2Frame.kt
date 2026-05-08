package com.ditchoom.buffer.codec.test.protocols.http2

import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import kotlin.jvm.JvmInline

/**
 * HTTP/2 frame header per RFC 7540 §4.1, packed as a single
 * `UInt`: top 24 bits are the frame `length`, bottom 8 bits are
 * the frame `type`. Wire format is the first 4 bytes of an HTTP/2
 * frame, big-endian:
 *
 * ```text
 *   +--------+--------+--------+--------+
 *   | length (24 bits, BE)     |  type  |
 *   +--------+--------+--------+--------+
 *      byte 0    byte 1    byte 2  byte 3
 * ```
 *
 * Type values are RFC 7540 §11.2: 0=DATA, 1=HEADERS, 2=PRIORITY,
 * 3=RST_STREAM, 4=SETTINGS, 5=PUSH_PROMISE, 6=PING, 7=GOAWAY,
 * 8=WINDOW_UPDATE, 9=CONTINUATION.
 *
 * Doctrine vector — exercises the `@DispatchOn`
 * dispatcher with a multi-byte (UInt) discriminator, big-endian
 * peek-side reconstruction of the value class.
 */
@JvmInline
@ProtocolMessage(wireOrder = Endianness.Big)
value class Http2LengthAndType(
    val raw: UInt,
) {
    /** Top 24 bits — payload byte length per RFC 7540 §4.1. */
    val length: Int get() = (raw shr 8).toInt()

    @DispatchValue
    val type: Int get() = (raw and 0xFFu).toInt()

    companion object {
        fun of(
            length: Int,
            type: Int,
        ): Http2LengthAndType {
            require(length in 0..0xFFFFFF) { "HTTP/2 frame length is 24-bit; got $length" }
            require(type in 0..0xFF) { "HTTP/2 frame type is 8-bit; got $type" }
            return Http2LengthAndType((length.toUInt() shl 8) or type.toUInt())
        }
    }
}

/**
 * HTTP/2 stream identifier per RFC 7540 §4.1: a 31-bit unsigned
 * integer. The wire field is 32 bits; the high bit is the
 * reserved `R` bit, which the spec mandates senders MUST set to
 * `0x0`. Construction validates this — the type itself can only
 * represent legal stream IDs, so the codec writes spec-compliant
 * bytes by virtue of round-tripping the value class's `raw`.
 *
 * Receivers per spec MUST ignore the R bit. This codec instead
 * fails loudly: a peer sending a frame with the R bit set
 * surfaces an `IllegalArgumentException` from the value class's
 * `init` during decode. Strict-failure is preferred for debugging
 * over silent masking; a connection layer that wants lenient
 * masking can mask before constructing this value class.
 */
@JvmInline
value class Http2StreamId(
    val raw: UInt,
) {
    init {
        require((raw and 0x80000000u) == 0u) {
            "HTTP/2 stream id is 31-bit per RFC 7540 §4.1; the high bit (reserved `R`) must be 0, got 0x${
                raw.toString(16).padStart(8, '0')
            }"
        }
    }
}

/**
 * Doctrine vector — sealed
 * dispatcher over the HTTP/2 frame header (RFC 7540 §4.1).
 *
 * Lifts this sealed parent to `<out P: Payload>` so a
 * payload-bearing variant (`Data<P : Payload>`, RFC §6.1) can
 * coexist with payload-free variants (`Settings`, `Ping`,
 * `WindowUpdate`, all `: Http2Frame<Nothing>`). The generated
 * dispatcher becomes a generic class
 * `Http2FrameCodec<P : Payload>(payloadCodec: Codec<P>) :
 * Codec<Http2Frame<P>>` — payload-free variants are reachable
 * through any `<P>` instantiation thanks to `Nothing` being a
 * subtype of `P` (covariance via `out P`).
 *
 * Wire layout per RFC 7540 §4.1:
 *
 * ```text
 *   +-----------------------------------------------+
 *   |                 Length (24)                   |
 *   +---------------+---------------+---------------+
 *   |   Type (8)    |   Flags (8)   |
 *   +-+-------------+---------------+-------------------------------+
 *   |R|                 Stream Identifier (31)                      |
 *   +=+=============================================================+
 *   |                   Frame Payload (0...)                        |
 *   +---------------------------------------------------------------+
 * ```
 *
 * `header` carries the first 4 bytes (length + type packed BE);
 * `flags` is the 5th byte; `streamId` is the next 4 bytes BE,
 * modeled as `Http2StreamId` whose `init` enforces the reserved
 * `R` bit (RFC §4.1) is `0`. The payload follows.
 */
@DispatchOn(Http2LengthAndType::class)
@ProtocolMessage(wireOrder = Endianness.Big)
sealed interface Http2Frame<out P : Payload> {
    /**
     * DATA frame per RFC 7540 §6.1 — generic-bounded payload slot.
     *
     * Narrow:
     *   - PADDED flag (bit 0x08) is ignored; the optional padding
     * length byte + trailing pad bytes are deferred.
     *     assumes no padding (matches the simplest §6.1 wire shape).
     *   - END_STREAM flag (bit 0x01) is preserved on the wire (it's
     *     just a flag bit in the existing `flags: UByte` field) but
     *     carries no codec semantics.
     *   - The body's byte count is determined by the buffer's outer
     * limit (/10b shape) — NOT by `header.length`. A
     *     consumer wraps the codec with a `setLimit(header.length)`
     *     before delegating; a future slice will lift this so the
     *     dispatcher reads `header.length` and bounds the buffer
     *     before delegating to the variant codec.
     *
     * Absorbed from the standalone `Http2DataFrame<P>`
     * fixture per the doctrine answer locked while landing 10b
     * (sealed-parent generic-aware integration is 's job).
     */
    @PacketType(value = 0)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Data<P : Payload>(
        val header: Http2LengthAndType,
        val flags: UByte,
        val streamId: Http2StreamId,
        @RemainingBytes val payload: P,
    ) : Http2Frame<P>

    /**
     * PING frame per RFC 7540 §6.7 — always carries an 8-byte
     * opaque payload that the receiver echoes back. The spec
     * mandates `length = 8` and `streamId = 0`; codec doesn't
     * enforce these cross-field constraints (those belong to a
     * connection layer, not the wire codec) but the
     * `Http2StreamId` type guarantees the reserved `R` bit is `0`.
     */
    @PacketType(value = 6)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Ping(
        val header: Http2LengthAndType = Http2LengthAndType.of(length = 8, type = 6),
        val flags: UByte,
        val streamId: Http2StreamId,
        val opaqueData: ULong,
    ) : Http2Frame<Nothing>

    /**
     * WINDOW_UPDATE frame per RFC 7540 §6.9 — always 4-byte
     * payload carrying the flow-control window increment. The
     * high bit of the increment is reserved per spec (codec
     * preserves the raw `UInt` here; range constraints
     * `[1, 2^31-1]` belong to the connection layer).
     */
    @PacketType(value = 8)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class WindowUpdate(
        val header: Http2LengthAndType = Http2LengthAndType.of(length = 4, type = 8),
        val flags: UByte,
        val streamId: Http2StreamId,
        val windowSizeIncrement: UInt,
    ) : Http2Frame<Nothing>

    /**
     * SETTINGS frame per RFC 7540 §6.5 — payload is a sequence of
     * 6-byte settings entries, count derived from `header.length / 6`.
     *
     * Doctrine vector — exercises the dotted-form
     * `@LengthFrom("header.length")` against the value-class header
     * field. The dispatcher integration is what 's standalone
     * `Http2SettingsFrame` couldn't deliver (simple-name `@LengthFrom`
     * couldn't reach into the value class). Both fixtures coexist:
     * `Http2SettingsFrame` covers simple-name `@LengthFrom("length")`
     * on a top-level scalar, this variant covers the dotted form.
     */
    @PacketType(value = 4)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Settings(
        val header: Http2LengthAndType,
        val flags: UByte,
        val streamId: Http2StreamId,
        @LengthFrom("header.length") val entries: List<Http2Setting>,
    ) : Http2Frame<Nothing>
}
