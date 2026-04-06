package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.CodecContext
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.SizeEstimate
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
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): Rgb = Rgb(buffer.readUnsignedByte(), buffer.readUnsignedByte(), buffer.readUnsignedByte())

    override fun encode(
        buffer: WriteBuffer,
        value: Rgb,
        context: EncodeContext,
    ) {
        buffer.writeUByte(value.r)
        buffer.writeUByte(value.g)
        buffer.writeUByte(value.b)
    }

    override fun sizeOf(value: Rgb): SizeEstimate = SizeEstimate.Exact(3)
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

// ========== Context-aware @UseCodec ==========

/** Key: offset added to each RGB channel on decode (subtracted on encode). */
data object RgbOffsetKey : CodecContext.Key<Int>()

/**
 * A context-aware RGB codec that reads [RgbOffsetKey] from context.
 * When the key is present, it shifts channel values by the offset.
 * This proves that context actually flows through @UseCodec fields.
 */
object ContextAwareRgbCodec : Codec<Rgb> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): Rgb {
        val offset = context[RgbOffsetKey] ?: 0
        return Rgb(
            (buffer.readUnsignedByte().toInt() + offset).toUByte(),
            (buffer.readUnsignedByte().toInt() + offset).toUByte(),
            (buffer.readUnsignedByte().toInt() + offset).toUByte(),
        )
    }

    override fun encode(
        buffer: WriteBuffer,
        value: Rgb,
        context: EncodeContext,
    ) {
        val offset = context[RgbOffsetKey] ?: 0
        buffer.writeUByte((value.r.toInt() - offset).toUByte())
        buffer.writeUByte((value.g.toInt() - offset).toUByte())
        buffer.writeUByte((value.b.toInt() - offset).toUByte())
    }

    override fun sizeOf(value: Rgb): SizeEstimate = SizeEstimate.Exact(3)
}

/** Uses the context-aware codec — context must flow through for correct round-trip. */
@ProtocolMessage
data class ContextColoredPoint(
    val x: Int,
    val y: Int,
    @UseCodec(ContextAwareRgbCodec::class) val color: Rgb,
)

// ========== Directional @UseCodec ==========

/** Decode-only codec — only implements Decoder<T>. */
object RgbDecoder : com.ditchoom.buffer.codec.Decoder<Rgb> {
    override fun decode(buffer: ReadBuffer): Rgb = Rgb(buffer.readUnsignedByte(), buffer.readUnsignedByte(), buffer.readUnsignedByte())
}

/** Encode-only codec — only implements Encoder<T>. */
object RgbEncoder : com.ditchoom.buffer.codec.Encoder<Rgb> {
    override fun encode(
        buffer: WriteBuffer,
        value: Rgb,
    ) {
        buffer.writeUByte(value.r)
        buffer.writeUByte(value.g)
        buffer.writeUByte(value.b)
    }
}

/** Inferred decode-only: RgbDecoder only implements Decoder. */
@ProtocolMessage
data class DecodeOnlyColoredPoint(
    val x: Int,
    val y: Int,
    @UseCodec(RgbDecoder::class) val color: Rgb,
)

/** Explicit encode-only. */
@ProtocolMessage(direction = com.ditchoom.buffer.codec.annotations.Direction.EncodeOnly)
data class EncodeOnlyColoredPoint(
    val x: Int,
    val y: Int,
    @UseCodec(RgbEncoder::class) val color: Rgb,
)

/** Bidirectional codec forced to decode-only by annotation. */
@ProtocolMessage(direction = com.ditchoom.buffer.codec.annotations.Direction.DecodeOnly)
data class ForcedDecodeOnlyPoint(
    val x: Int,
    val y: Int,
    @UseCodec(RgbCodec::class) val color: Rgb,
)

/** Decode-only with @LengthPrefixed. */
@ProtocolMessage
data class DecodeOnlyPrefixedColor(
    val id: UByte,
    @UseCodec(RgbDecoder::class) @LengthPrefixed val color: Rgb,
)
