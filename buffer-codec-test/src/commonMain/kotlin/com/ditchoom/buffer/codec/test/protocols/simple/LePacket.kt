package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * Follow-up doctrine vector — exercises the value-class
 * field `wireOrder` propagation through the sequential peek walk.
 *
 * `LeHeader` declares `@ProtocolMessage(wireOrder = Endianness.Little)`
 * and packs `length` (low byte) + `tag` (high byte) into a single
 * `UShort` raw. When `LeHeader(0x0501u)` is encoded little-endian
 * the wire bytes are `01 05` (length=1 first, then tag=5).
 *
 * `LePacket.payload` is `@LengthFrom("header.length")` — the
 * dotted-form reference forces the peek path to peek-stash the
 * value-class instance and call `.length` on it. Without
 * propagating the value class's `wireOrder = Little`, the peek
 * would assemble bytes big-endian and read `length` from the high
 * byte (5) instead of the low byte (1). The drip-feed peek test
 * is what surfaces the bug.
 *
 * Decode and encode of `header` itself rely on the buffer's
 * runtime `ByteOrder` (consistent with how the codec treats
 * un-`@WireOrder`'d Scalar fields). Tests set the buffer to
 * LITTLE_ENDIAN explicitly.
 */
@JvmInline
@ProtocolMessage(wireOrder = Endianness.Little)
value class LeHeader(
    val raw: UShort,
) {
    /** Low byte = body length, 0..255. */
    val length: Int get() = raw.toInt() and 0x00FF

    /** High byte = freeform tag, 0..255. */
    val tag: Int get() = (raw.toInt() shr 8) and 0x00FF

    companion object {
        fun of(
            length: Int,
            tag: Int,
        ): LeHeader {
            require(length in 0..255 && tag in 0..255)
            return LeHeader(((tag shl 8) or length).toUShort())
        }
    }
}

@ProtocolMessage(wireOrder = Endianness.Little)
data class LePacket(
    val header: LeHeader,
    @LengthFrom("header.length") val payload: String,
)
