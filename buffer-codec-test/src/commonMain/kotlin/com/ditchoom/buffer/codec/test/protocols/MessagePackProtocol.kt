package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.PacketTypeRange
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * Custom dispatcher exception used by [MessagePackFormatByte] when an incoming wire byte does not
 * map to any registered variant. KSP only needs the FQN to resolve at compile time — it is not
 * itself codec'd, just instantiated with the dispatcher's diagnostic message.
 */
class MessagePackMalformedException(
    message: String,
) : RuntimeException(message)

/**
 * Identity discriminator over the raw MessagePack format byte. Both `@PacketType` (single value)
 * and `@PacketTypeRange` (contiguous span) variants are matched against [rawValue], which simply
 * returns the raw byte as an `Int`.
 */
@JvmInline
@ProtocolMessage
value class MessagePackByte(
    val raw: UByte,
) {
    @DispatchValue
    val rawValue: Int get() = raw.toInt() and 0xFF

    /** Low 7 bits — payload of a PositiveFixInt (0..127). */
    val positiveFixIntValue: Int get() = raw.toInt() and 0x7F

    /** Low 4 bits — entry count of a FixMap (0..15). */
    val fixMapEntryCount: Int get() = raw.toInt() and 0x0F

    /** Low 5 bits sign-extended over the high 3 — value of a NegativeFixInt (-32..-1). */
    val negativeFixIntValue: Int get() = (raw.toInt() and 0x1F) - 32
}

/**
 * Sealed dispatcher modeling the MessagePack format byte (see RFC 7159 §5 layout).
 *
 * - PositiveFixInt: 0x00..0x7F — low 7 bits encode a value in 0..127.
 * - FixMap:         0x80..0x8F — low 4 bits encode the entry count in 0..15.
 * - NegativeFixInt: 0xE0..0xFF — low 5 bits encode a signed value in -32..-1.
 * - Nil / False / True singletons at 0xC0 / 0xC2 / 0xC3.
 *
 * Byte 0xC1 is intentionally left unclaimed by the spec — and the gaps 0x90..0xBF and
 * 0xC4..0xDF would be claimed by a complete implementation but are deliberately omitted
 * here so we can verify the dispatcher routes them to [MessagePackMalformedException].
 */
@DispatchOn(MessagePackByte::class)
@ProtocolMessage(
    onUnknownDiscriminator = "com.ditchoom.buffer.codec.test.protocols.MessagePackMalformedException",
)
sealed interface MessagePackFormatByte {
    @PacketTypeRange(0x00, 0x7F)
    @ProtocolMessage
    data class PositiveFixInt(
        val header: MessagePackByte,
    ) : MessagePackFormatByte

    @PacketTypeRange(0x80, 0x8F)
    @ProtocolMessage
    data class FixMap(
        val header: MessagePackByte,
    ) : MessagePackFormatByte

    @PacketTypeRange(0xE0, 0xFF)
    @ProtocolMessage
    data class NegativeFixInt(
        val header: MessagePackByte,
    ) : MessagePackFormatByte

    @PacketType(wire = 0xC0)
    @ProtocolMessage
    data class Nil(
        val header: MessagePackByte = MessagePackByte(0xC0u),
    ) : MessagePackFormatByte

    @PacketType(wire = 0xC2)
    @ProtocolMessage
    data class False(
        val header: MessagePackByte = MessagePackByte(0xC2u),
    ) : MessagePackFormatByte

    @PacketType(wire = 0xC3)
    @ProtocolMessage
    data class True(
        val header: MessagePackByte = MessagePackByte(0xC3u),
    ) : MessagePackFormatByte
}
