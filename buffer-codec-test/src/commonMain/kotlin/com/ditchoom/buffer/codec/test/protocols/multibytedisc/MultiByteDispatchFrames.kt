package com.ditchoom.buffer.codec.test.protocols.multibytedisc

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/*
 * Multi-byte `@DispatchOn` discriminator vectors covering the four inner
 * scalar kinds whose `peekFrameSize` byte-reconstruction was the last
 * missing piece: signed `Short` / `Int` / `Long` and unsigned `ULong`.
 *
 * Decode and encode already worked for these inners (the discriminator's
 * own value-class codec reads/writes the scalar, and the variant re-reads
 * it as its first field). The only gap was the dispatcher's `peekFrameSize`,
 * which reconstructs the discriminator value class from raw peeked bytes
 * without consuming the stream — it previously supported only 1/2/4-byte
 * unsigned kinds and rejected everything here via the stage-9
 * "non-peekable @DispatchOn discriminator" diagnostic.
 *
 * Each frame deliberately picks a byte order (mixing big- and little-endian
 * across the widths) so the order-aware reconstruction is exercised in both
 * directions, and each uses a discriminator value that stresses the kind:
 * negative values for the signed inners (proving sign-preserving narrowing),
 * an 8-byte value for the 64-bit inners (proving `Long`-domain assembly).
 */

/** Signed 16-bit opcode, big-endian (2-byte discriminator). */
@JvmInline
@ProtocolMessage(wireOrder = Endianness.Big)
value class SignedOpcode(
    val raw: Short,
) {
    @DispatchValue
    val code: Int get() = raw.toInt()
}

/**
 * `Short` inner. `Negative` uses opcode `-2` (wire `0xFFFE`), exercising
 * sign extension through `.toShort()` on the peek-reconstructed bytes.
 */
@DispatchOn(SignedOpcode::class)
@ProtocolMessage(wireOrder = Endianness.Big)
sealed interface SignedOpcodeFrame {
    @ProtocolMessage
    @PacketType(-2)
    data class Negative(
        val opcode: SignedOpcode = SignedOpcode((-2).toShort()),
        val payload: Int,
    ) : SignedOpcodeFrame

    @ProtocolMessage
    @PacketType(1)
    data class Positive(
        val opcode: SignedOpcode = SignedOpcode(1),
        val payload: Int,
    ) : SignedOpcodeFrame
}

/** Signed 32-bit tag, little-endian (4-byte discriminator). */
@JvmInline
@ProtocolMessage(wireOrder = Endianness.Little)
value class SignedTag(
    val raw: Int,
) {
    @DispatchValue
    val tag: Int get() = raw
}

/**
 * `Int` inner with little-endian assembly. `Alpha` uses tag `-1`
 * (wire `FF FF FF FF`), proving the LE shift path narrows to `Int`.
 */
@DispatchOn(SignedTag::class)
@ProtocolMessage(wireOrder = Endianness.Little)
sealed interface SignedTagFrame {
    @ProtocolMessage
    @PacketType(-1)
    data class Alpha(
        val tag: SignedTag = SignedTag(-1),
        val value: Short,
    ) : SignedTagFrame

    @ProtocolMessage
    @PacketType(7)
    data class Beta(
        val tag: SignedTag = SignedTag(7),
        val value: Short,
    ) : SignedTagFrame
}

/** Signed 64-bit selector, big-endian (8-byte discriminator). */
@JvmInline
@ProtocolMessage(wireOrder = Endianness.Big)
value class SignedSelector(
    val raw: Long,
) {
    @DispatchValue
    val kind: Int get() = raw.toInt()
}

/**
 * `Long` inner. The 8-byte big-endian assembly overflows `Int`, so peek
 * lifts each byte to `Long` before shifting (up to 56 bits).
 */
@DispatchOn(SignedSelector::class)
@ProtocolMessage(wireOrder = Endianness.Big)
sealed interface SignedSelectorFrame {
    @ProtocolMessage
    @PacketType(2)
    data class Two(
        val selector: SignedSelector = SignedSelector(2L),
        val payload: Int,
    ) : SignedSelectorFrame

    @ProtocolMessage
    @PacketType(9)
    data class Nine(
        val selector: SignedSelector = SignedSelector(9L),
        val payload: Int,
    ) : SignedSelectorFrame
}

/** Unsigned 64-bit magic, little-endian (8-byte discriminator). */
@JvmInline
@ProtocolMessage(wireOrder = Endianness.Little)
value class UnsignedMagic(
    val raw: ULong,
) {
    @DispatchValue
    val tag: Int get() = (raw and 0xFFuL).toInt()
}

/**
 * `ULong` inner with little-endian assembly. Proves the `Long`-domain
 * path narrows to `ULong` via `.toULong()` and that the low-byte
 * `@DispatchValue` coercion routes correctly.
 */
@DispatchOn(UnsignedMagic::class)
@ProtocolMessage(wireOrder = Endianness.Little)
sealed interface UnsignedMagicFrame {
    @ProtocolMessage
    @PacketType(0x11)
    data class First(
        val magic: UnsignedMagic = UnsignedMagic(0x11uL),
        val payload: Int,
    ) : UnsignedMagicFrame

    @ProtocolMessage
    @PacketType(0x22)
    data class Second(
        val magic: UnsignedMagic = UnsignedMagic(0x22uL),
        val payload: Int,
    ) : UnsignedMagicFrame
}
