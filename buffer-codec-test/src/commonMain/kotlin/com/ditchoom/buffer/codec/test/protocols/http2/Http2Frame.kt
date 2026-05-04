package com.ditchoom.buffer.codec.test.protocols.http2

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
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
 * Stage F slice 6.5 doctrine vector — exercises the `@DispatchOn`
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
 * Stage F slice 6.5 doctrine vector — sealed dispatcher over the
 * HTTP/2 frame header (RFC 7540 §4.1).
 *
 * Slice 6.5 covers HTTP/2 frame types whose payload size is fixed
 * by the spec: PING (RFC §6.7, length=8 always) and WINDOW_UPDATE
 * (RFC §6.9, length=4 always). Variable-payload frame types
 * (DATA, HEADERS, SETTINGS, GOAWAY) need either Stage G's
 * variable-length list payload or a `@LengthFrom("header.length")`
 * dotted-form widening — both deferred.
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
sealed interface Http2Frame {
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
    ) : Http2Frame

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
    ) : Http2Frame
}
