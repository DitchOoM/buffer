package com.ditchoom.buffer.codec.test.protocols.forwardcompat

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.ForwardCompatible
import com.ditchoom.buffer.codec.annotations.FramedBy
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.UnknownVariant
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import kotlin.jvm.JvmInline

/**
 * `@ForwardCompatible` fixture — a length-delimited op stream modeled on the
 * driving terminal §5 protocol: each op is `opcode(1) + varint(len) + payload`,
 * where the opcode is a single-byte discriminator and the payload is framed by
 * a full-width variable-byte-integer length ([MqttRemainingLengthCodec], a
 * `BoundingLengthCodec<UInt>` reused as the framing length).
 *
 * An old decoder that meets an opcode it doesn't recognize must **skip** the
 * framed payload and **preserve** it into [ForwardCompatibleOp.Unknown], so
 * newer ops survive a round-trip through it (relay) or an on-disk frame
 * (persistence). The wire layout each op pins down:
 *
 * ```text
 * Scroll(header = 0x12, delta = 0x0102):
 *   12          opcode (after-field discriminator, 1 byte)
 *   02          varint length prefix (= 2 body bytes)
 *   01 02       body (delta, Short BE)
 *
 * SetTitle(header = 0x34, title = "hi"):
 *   34          opcode
 *   04          varint length prefix (= 2 length-prefix + 2 body)
 *   00 02       LengthPrefixed string length
 *   68 69       "hi"
 *
 * Unknown op on the wire (opcode 0x99, payload AA BB CC):
 *   99          opcode (unrecognized → skip + preserve)
 *   03          varint length prefix (= 3 payload bytes)
 *   AA BB CC    opaque payload (preserved verbatim into Unknown.raw)
 * ```
 */
@JvmInline
@ProtocolMessage
value class OpCode(
    val raw: UByte,
) {
    @DispatchValue
    val code: Int get() = raw.toInt()
}

@ProtocolMessage
@DispatchOn(OpCode::class)
@FramedBy(MqttRemainingLengthCodec::class, after = "header")
@ForwardCompatible(unknown = ForwardCompatibleOp.Unknown::class)
sealed interface ForwardCompatibleOp {
    @ProtocolMessage
    @PacketType(value = 0x12)
    data class Scroll(
        val header: OpCode,
        val delta: Short,
    ) : ForwardCompatibleOp

    @ProtocolMessage
    @PacketType(value = 0x34)
    data class SetTitle(
        val header: OpCode,
        @LengthPrefixed val title: String,
    ) : ForwardCompatibleOp

    /**
     * The skip-and-preserve sink. [opcode] carries the unrecognized
     * discriminator byte (the only place it can survive — [raw] is the
     * payload only), and [raw] holds the opaque framed payload, excluding
     * the opcode and length prefix. Equality is content-wise: data-class
     * `equals` over `Int` + `PlatformBuffer` (buffers compare by content).
     */
    @UnknownVariant
    data class Unknown(
        val opcode: Int,
        val raw: PlatformBuffer,
    ) : ForwardCompatibleOp
}
