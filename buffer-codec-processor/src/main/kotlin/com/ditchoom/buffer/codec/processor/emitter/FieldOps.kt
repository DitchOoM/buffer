package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.codec.processor.ir.Endianness
import com.ditchoom.buffer.codec.processor.ir.PrimitiveKind
import com.squareup.kotlinpoet.CodeBlock

/**
 * Lookup tables that map a [PrimitiveKind] to the buffer read / write method name.
 *
 * Kept as a small object so the emitter doesn't grow a `when (kind)` per call
 * site. Phase 7 covers the kinds the canonical-codec fixtures need; new
 * primitives extend the maps here.
 */
internal object FieldOps {
    /**
     * Big-endian read call name (no `buffer.` prefix). Default: BE — matches the
     * legacy emitter's `Primitive.readExpr`. The new pipeline still emits LE-side
     * via [readExpr] which lowers `Endianness.Little` to a `.reverseBytes()` swap
     * (identical to the legacy `swappedReadExpr`).
     */
    fun readCall(kind: PrimitiveKind): String =
        when (kind) {
            PrimitiveKind.Bool -> "readByte" // not pinned to a fixture
            PrimitiveKind.Byte -> "readByte"
            PrimitiveKind.UByte -> "readUnsignedByte"
            PrimitiveKind.Short -> "readShort"
            PrimitiveKind.UShort -> "readUnsignedShort"
            PrimitiveKind.Int -> "readInt"
            PrimitiveKind.UInt -> "readUnsignedInt"
            PrimitiveKind.Long -> "readLong"
            PrimitiveKind.ULong -> "readUnsignedLong"
            PrimitiveKind.Float -> "readFloat"
            PrimitiveKind.Double -> "readDouble"
        }

    fun writeCall(kind: PrimitiveKind): String =
        when (kind) {
            PrimitiveKind.Bool -> "writeByte"
            PrimitiveKind.Byte -> "writeByte"
            PrimitiveKind.UByte -> "writeUByte"
            PrimitiveKind.Short -> "writeShort"
            PrimitiveKind.UShort -> "writeUShort"
            PrimitiveKind.Int -> "writeInt"
            PrimitiveKind.UInt -> "writeUInt"
            PrimitiveKind.Long -> "writeLong"
            PrimitiveKind.ULong -> "writeULong"
            PrimitiveKind.Float -> "writeFloat"
            PrimitiveKind.Double -> "writeDouble"
        }

    /**
     * Endianness-aware read expression. Mirrors legacy `Primitive.swappedReadExpr`
     * for `Endianness.Little`. Single-byte kinds ignore the order parameter (no
     * byte-order swap is meaningful on a single byte).
     */
    fun readExpr(
        kind: PrimitiveKind,
        order: Endianness,
    ): String {
        val be = "buffer.${readCall(kind)}()"
        if (order == Endianness.Big || isSingleByte(kind)) return be
        return when (kind) {
            PrimitiveKind.Short -> "buffer.readShort().reverseBytes()"
            PrimitiveKind.UShort -> "buffer.readShort().reverseBytes().toUShort()"
            PrimitiveKind.Int -> "buffer.readInt().reverseBytes()"
            PrimitiveKind.UInt -> "buffer.readInt().reverseBytes().toUInt()"
            PrimitiveKind.Long -> "buffer.readLong().reverseBytes()"
            PrimitiveKind.ULong -> "buffer.readLong().reverseBytes().toULong()"
            PrimitiveKind.Float -> "Float.fromBits(buffer.readInt().reverseBytes())"
            PrimitiveKind.Double -> "Double.fromBits(buffer.readLong().reverseBytes())"
            // Single-byte kinds already handled by the early-return above.
            PrimitiveKind.Bool, PrimitiveKind.Byte, PrimitiveKind.UByte -> be
        }
    }

    /**
     * Endianness-aware write statement (no leading `buffer.`). Mirrors legacy
     * `Primitive.swappedWriteExpr` for `Endianness.Little`.
     */
    fun writeExpr(
        kind: PrimitiveKind,
        order: Endianness,
        valueExpr: String,
    ): String {
        val be = "buffer.${writeCall(kind)}($valueExpr)"
        if (order == Endianness.Big || isSingleByte(kind)) return be
        return when (kind) {
            PrimitiveKind.Short -> "buffer.writeShort($valueExpr.reverseBytes())"
            PrimitiveKind.UShort -> "buffer.writeShort($valueExpr.toShort().reverseBytes())"
            PrimitiveKind.Int -> "buffer.writeInt($valueExpr.reverseBytes())"
            PrimitiveKind.UInt -> "buffer.writeInt($valueExpr.toInt().reverseBytes())"
            PrimitiveKind.Long -> "buffer.writeLong($valueExpr.reverseBytes())"
            PrimitiveKind.ULong -> "buffer.writeLong($valueExpr.toLong().reverseBytes())"
            PrimitiveKind.Float -> "buffer.writeInt($valueExpr.toRawBits().reverseBytes())"
            PrimitiveKind.Double -> "buffer.writeLong($valueExpr.toRawBits().reverseBytes())"
            PrimitiveKind.Bool, PrimitiveKind.Byte, PrimitiveKind.UByte -> be
        }
    }

    /** A primitive whose wire width is a single byte — byte order is meaningless. */
    fun isSingleByte(kind: PrimitiveKind): Boolean = kind == PrimitiveKind.Bool || kind == PrimitiveKind.Byte || kind == PrimitiveKind.UByte

    /** Render `value.field` for an encode / wireSize site. */
    fun fieldExpr(fieldName: String): CodeBlock = CodeBlock.of("value.%L", fieldName)
}
