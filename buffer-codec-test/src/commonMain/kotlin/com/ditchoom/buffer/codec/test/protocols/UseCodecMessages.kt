package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.UseCodec

/** A custom type that is NOT a @ProtocolMessage — decoded via a Codec object. */
data class Rgb(
    val r: UByte,
    val g: UByte,
    val b: UByte,
)

/** Codec object for Rgb — 3 bytes on the wire. */
object RgbCodec : Codec<Rgb> {
    override fun decode(buffer: ReadBuffer): Rgb = Rgb(buffer.readUnsignedByte(), buffer.readUnsignedByte(), buffer.readUnsignedByte())

    override fun encode(
        buffer: WriteBuffer,
        value: Rgb,
    ) {
        buffer.writeUByte(value.r)
        buffer.writeUByte(value.g)
        buffer.writeUByte(value.b)
    }

    override fun sizeOf(value: Rgb): Int = 3
}

/** @UseCodec without a length annotation — codec reads directly from buffer. */
@ProtocolMessage
data class ColoredPoint(
    val x: Int,
    val y: Int,
    @UseCodec(RgbCodec::class) val color: Rgb,
)

/** @UseCodec with @LengthFrom — codec receives a size-limited slice. */
@ProtocolMessage
data class ColorBlock(
    val colorBytes: UShort,
    @UseCodec(RgbCodec::class) @LengthFrom("colorBytes") val color: Rgb,
    val label: UByte,
)

/** @UseCodec with @LengthPrefixed — length prefix + codec. */
@ProtocolMessage
data class PrefixedColor(
    val id: UByte,
    @UseCodec(RgbCodec::class) @LengthPrefixed val color: Rgb,
)

/** @UseCodec with @RemainingBytes — codec gets all remaining bytes. */
@ProtocolMessage
data class TrailingColor(
    val id: UByte,
    @UseCodec(RgbCodec::class) @RemainingBytes val color: Rgb,
)
