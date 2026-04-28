package com.ditchoom.buffer.codec.processor.emitter

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

    /** Render `value.field` for an encode / wireSize site. */
    fun fieldExpr(fieldName: String): CodeBlock = CodeBlock.of("value.%L", fieldName)
}
