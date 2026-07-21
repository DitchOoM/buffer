package com.ditchoom.buffer.codec.test.protocols.deferredpayload

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayload
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayloadCodec

/**
 * Issue #293 vector — a deferred payload sized by a **sibling length
 * field** rather than by the caller's buffer limit.
 *
 * Modelled on the SMP / mcumgr-over-BLE header that motivated the issue,
 * because it is the shape the old taxonomy could not express: the length
 * carrier is non-adjacent (four fixed scalars sit between `payloadLength`
 * and the body), so `@FramedBy` — which requires the prefix immediately
 * after the discriminator — does not fit, and `@RemainingBytes` would
 * throw away the byte count the protocol already states.
 *
 * Structurally this is `RemoteHeader` with `String` swapped for a
 * codec-decoded `Payload`. That swap used to change the *framing* answer
 * (`peekFrameSize` collapsed to `NoFraming`) even though it changes no
 * wire byte — the bug #293 is about.
 *
 * Wire layout (all multi-byte fields big-endian):
 * ```text
 * SmpFrame(op=0, flags=0, payloadLength=2, group=9, sequence=1,
 *          commandId=3, payload=TextPayload("hi")):
 *   00            op
 *   00            flags
 *   00 02         payloadLength
 *   00 09         group
 *   01            sequence
 *   03            commandId
 *   68 69         payload, exactly `payloadLength` bytes
 * ```
 */
@ProtocolMessage
data class SmpFrame(
    val op: UByte,
    val flags: UByte,
    val payloadLength: UShort,
    val group: UShort,
    val sequence: UByte,
    val commandId: UByte,
    @LengthFrom("payloadLength")
    @UseCodec(TextPayloadCodec::class)
    val payload: TextPayload,
)

/**
 * The same shape with the payload left generic, so the codec is supplied
 * by the caller as a constructor-injected `Codec<P>` rather than pinned
 * by `@UseCodec`. Proves the two axes are independent: the extent
 * (`@LengthFrom`) composes with either [com.ditchoom.buffer.codec.Payload]
 * codec source.
 */
@ProtocolMessage
data class SmpGenericFrame<P : Payload>(
    val op: UByte,
    val flags: UByte,
    val payloadLength: UShort,
    val group: UShort,
    val sequence: UByte,
    val commandId: UByte,
    @LengthFrom("payloadLength") val payload: P,
)

/**
 * A sibling-sized payload with fields *after* it, including a
 * variable-size one.
 *
 * `@RemainingBytes` payloads are terminal-or-fixed-size-trailer only:
 * their end is `limit - <reserved trailing bytes>`, which is computable
 * only when every trailer has a known width. A sibling states the byte
 * count outright, so that restriction does not apply and ordinary fields
 * — of any width — may follow.
 */
@ProtocolMessage
data class SmpFrameWithTrailer(
    val payloadLength: UShort,
    @LengthFrom("payloadLength")
    @UseCodec(TextPayloadCodec::class)
    val payload: TextPayload,
    val checksum: UShort,
    @LengthPrefixed val note: String,
)

/**
 * A deliberately misbehaving `Codec<TextPayload>`: it leaves the last
 * byte of its bounded region unread.
 *
 * Under `@RemainingBytes` this is undetectable — the payload's extent is
 * whatever the codec chooses to read. Under `@LengthFrom` the region is
 * stated on the wire, so stopping short means every subsequent field
 * reads from the wrong offset. The generated decode rejects it rather
 * than silently desynchronising; [ShortReadFrame] is the vector.
 */
object ShortReadPayloadCodec : Codec<TextPayload> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): TextPayload {
        val short = (buffer.remaining() - 1).coerceAtLeast(0)
        return TextPayload(buffer.readString(short, Charset.UTF8))
    }

    override fun encode(
        buffer: WriteBuffer,
        value: TextPayload,
        context: EncodeContext,
    ) {
        buffer.writeString(value.text, Charset.UTF8)
    }

    override fun wireSize(
        value: TextPayload,
        context: EncodeContext,
    ): WireSize = WireSize.BackPatch
}

/** Carrier for [ShortReadPayloadCodec] — see its docs. */
@ProtocolMessage
data class ShortReadFrame(
    val payloadLength: UShort,
    @LengthFrom("payloadLength")
    @UseCodec(ShortReadPayloadCodec::class)
    val payload: TextPayload,
)

/**
 * The reporter's *second* shape from issue #168, migrated to `@LengthFrom`.
 *
 * #168 asked why `Partial` was generated for `SmpPacket` but not for a sealed
 * `Frame` whose variants carry `counter` / `length` / payload / `checksum`;
 * #171 answered it by allowing a non-terminal payload with fixed-size
 * trailers. That shape is the other half of what #293 migrates, so it gets a
 * fixture: a sealed variant — not a top-level data class — with a
 * sibling-sized generic payload and a trailer after it.
 *
 * Declared with a generic parent (`<out P : Payload>`), which is the form the
 * processor supports; the raw non-generic parent in #168's original snippet is
 * rejected by `validateGenericPayloadVariantShape` (issue #176).
 *
 * ```text
 * Command<TextPayload>(counter=1, payloadLength=2, payload="hi", checksum=0xBEEF):
 *   0A            @PacketType discriminator (consumed by the dispatcher)
 *   00 01         counter
 *   00 02         payloadLength
 *   68 69         payload, exactly payloadLength bytes
 *   BE EF         checksum
 * ```
 */
@ProtocolMessage
sealed interface DeferredDispatchFrame<out P : Payload> {
    @ProtocolMessage
    @PacketType(0x0A)
    data class Command<P : Payload>(
        val counter: UShort,
        val payloadLength: UShort,
        @LengthFrom("payloadLength") val payload: P,
        val checksum: UShort,
    ) : DeferredDispatchFrame<P>

    @ProtocolMessage
    @PacketType(0xA0)
    data class Status(
        val code: UByte,
    ) : DeferredDispatchFrame<Nothing>
}
