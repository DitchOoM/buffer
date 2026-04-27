package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketTypeRange
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * Synthetic discriminator for [RangeFrame]: the high nibble carries the type id and the low
 * nibble carries per-instance flag bits, so a single variant claiming type id `0x3` occupies
 * the contiguous wire-byte span 0x30..0x3F.
 */
@JvmInline
@ProtocolMessage
value class FlagByte(
    val raw: UByte,
) {
    @DispatchValue
    val typeId: Int get() = (raw.toInt() shr 4) and 0x0F

    val lowNibble: Int get() = raw.toInt() and 0x0F
}

/**
 * Sealed dispatcher with a single variant, exercised for each wire byte in 0x30..0x3F. The
 * variant must own its discriminator byte (the [FlagByte] field placed first so the variant
 * codec emits it before the payload).
 */
@DispatchOn(FlagByte::class)
@ProtocolMessage
sealed interface RangeFrame {
    @PacketTypeRange(0x30, 0x3F)
    @ProtocolMessage
    data class Framed(
        val flags: FlagByte,
        val payload: UShort,
    ) : RangeFrame
}
