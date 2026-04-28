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

    /**
     * Natural wire byte width for a [PrimitiveKind] — the size of a single
     * `readShort` / `readInt` / `readLong` call. Float = 4, Double = 8. Bool
     * is 1 (byte != 0). Used by Capability 4 (custom-width @WireBytes) to
     * decide whether the requested width matches the natural width or
     * requires shift-and-mask emission.
     */
    fun naturalWireBytes(kind: PrimitiveKind): Int =
        when (kind) {
            PrimitiveKind.Bool -> 1
            PrimitiveKind.Byte -> 1
            PrimitiveKind.UByte -> 1
            PrimitiveKind.Short -> 2
            PrimitiveKind.UShort -> 2
            PrimitiveKind.Int -> 4
            PrimitiveKind.UInt -> 4
            PrimitiveKind.Long -> 8
            PrimitiveKind.ULong -> 8
            PrimitiveKind.Float -> 4
            PrimitiveKind.Double -> 8
        }

    /** True when a primitive kind is signed (sign-extension matters for narrow widths). */
    fun isSigned(kind: PrimitiveKind): Boolean =
        when (kind) {
            PrimitiveKind.Byte, PrimitiveKind.Short, PrimitiveKind.Int, PrimitiveKind.Long -> true
            else -> false
        }

    /**
     * Endianness-aware read expression with a custom wire width. When
     * [wireBytes] equals [naturalWireBytes] for [kind], delegates to the
     * natural-width [readExpr]. Otherwise emits a shift-and-mask sequence
     * mirroring legacy `CustomWidthEmitter.customWidthReadExpr`.
     *
     * Decomposes the requested width into `{4, 2, 1}` chunks (e.g., 3 → 2+1,
     * 5 → 4+1, 6 → 4+2, 7 → 4+2+1). For BE the first chunk is the most-
     * significant; for LE the first chunk is the least-significant.
     */
    fun readExpr(
        kind: PrimitiveKind,
        order: Endianness,
        wireBytes: Int,
    ): String {
        val natural = naturalWireBytes(kind)
        if (wireBytes <= 0 || wireBytes == natural) return readExpr(kind, order)
        return customWidthReadExpr(kind, order, wireBytes)
    }

    /** Endianness-aware write statement with a custom wire width. */
    fun writeExpr(
        kind: PrimitiveKind,
        order: Endianness,
        wireBytes: Int,
        valueExpr: String,
    ): String {
        val natural = naturalWireBytes(kind)
        if (wireBytes <= 0 || wireBytes == natural) return writeExpr(kind, order, valueExpr)
        return customWidthWriteExpr(kind, order, wireBytes, valueExpr)
    }

    /** Decompose a custom wire-byte width into a sequence of {4, 2, 1} chunks (legacy contract). */
    private fun decomposeWireBytes(wireBytes: Int): List<Int> =
        buildList {
            var r = wireBytes
            for (s in intArrayOf(4, 2, 1)) {
                while (r >= s) {
                    add(s)
                    r -= s
                    // Greedy single-pass — legacy accepted only one chunk per
                    // size class, so break out as soon as r < s.
                    if (r < s) break
                }
            }
        }.let { decomposed ->
            // The greedy loop above might double-up; the legacy emitter
            // produced exactly one chunk per width in the order 4,2,1.
            // Build the list strictly that way.
            val result = mutableListOf<Int>()
            var r = wireBytes
            for (s in intArrayOf(4, 2, 1)) {
                if (r >= s) {
                    result.add(s)
                    r -= s
                }
            }
            result
        }

    private fun holdingType(kind: PrimitiveKind): String =
        if (naturalWireBytes(kind) <= 4) "Int" else "Long"

    private fun customWidthReadExpr(
        kind: PrimitiveKind,
        order: Endianness,
        wireBytes: Int,
    ): String {
        val isLE = order == Endianness.Little
        val h = holdingType(kind)
        val chunks = decomposeWireBytes(wireBytes)
        val stmts = mutableListOf<String>()
        var remainingBytes = wireBytes
        var consumedBytes = 0
        val chunkVars = mutableListOf<Pair<String, Int>>()
        var varIndex = 0
        for (cs in chunks) {
            remainingBytes -= cs
            val varName = "_c${varIndex++}"
            val swap = if (isLE && cs > 1) ".reverseBytes()" else ""
            val read =
                when (cs) {
                    4 -> "buffer.readInt()$swap" + if (h == "Long") ".toLong() and 0xFFFFFFFFL" else ""
                    2 -> "buffer.readShort()$swap.to$h() and ${if (h == "Long") "0xFFFFL" else "0xFFFF"}"
                    else -> "buffer.readByte().to$h() and ${if (h == "Long") "0xFFL" else "0xFF"}"
                }
            stmts.add("val $varName = $read")
            val shift = if (isLE) consumedBytes * 8 else remainingBytes * 8
            chunkVars.add(varName to shift)
            consumedBytes += cs
        }
        val rawExpr = chunkVars.joinToString(" or ") { (n, s) -> if (s > 0) "($n shl $s)" else n }
        if (isSigned(kind) && wireBytes < naturalWireBytes(kind)) {
            val shiftAmount = (if (h == "Long") 64 else 32) - wireBytes * 8
            stmts.add("val _raw = $rawExpr")
            stmts.add(typeCast(kind, "(_raw shl $shiftAmount) shr $shiftAmount", h))
        } else {
            stmts.add(typeCast(kind, rawExpr, h))
        }
        return "run { ${stmts.joinToString("; ")} }"
    }

    private fun customWidthWriteExpr(
        kind: PrimitiveKind,
        order: Endianness,
        wireBytes: Int,
        valueExpr: String,
    ): String {
        val isLE = order == Endianness.Little
        val h = holdingType(kind)
        val chunks = decomposeWireBytes(wireBytes)
        val stmts = mutableListOf<String>()
        // Coerce the user value into the holding-type. Int / Long pass through;
        // every other primitive is converted (UByte.toInt() etc).
        val vExpr =
            when (kind) {
                PrimitiveKind.Int, PrimitiveKind.Long -> valueExpr
                else -> "$valueExpr.to$h()"
            }
        stmts.add("val _v = $vExpr")
        var remainingBytes = wireBytes
        var consumedBytes = 0
        for (cs in chunks) {
            remainingBytes -= cs
            val shift = if (isLE) consumedBytes * 8 else remainingBytes * 8
            val shifted = if (shift > 0) "(_v ushr $shift)" else "_v"
            val stmt =
                when (cs) {
                    4 -> {
                        val intExpr = "$shifted${if (h == "Long") ".toInt()" else ""}"
                        if (isLE) "buffer.writeInt($intExpr.reverseBytes())" else "buffer.writeInt($intExpr)"
                    }
                    2 -> {
                        if (isLE) {
                            "buffer.writeShort($shifted.toShort().reverseBytes())"
                        } else {
                            "buffer.writeShort($shifted.toShort())"
                        }
                    }
                    else -> "buffer.writeByte($shifted.toByte())"
                }
            stmts.add(stmt)
            consumedBytes += cs
        }
        return "run { ${stmts.joinToString("; ")} }"
    }

    private fun typeCast(
        kind: PrimitiveKind,
        expr: String,
        holdingType: String,
    ): String =
        when (kind) {
            PrimitiveKind.Byte -> "($expr).toByte()"
            PrimitiveKind.UByte -> "($expr).toUByte()"
            PrimitiveKind.Short -> "($expr).toShort()"
            PrimitiveKind.UShort -> "($expr).toUShort()"
            PrimitiveKind.Int -> expr
            PrimitiveKind.UInt -> "($expr).toUInt()"
            PrimitiveKind.Long -> if (holdingType == "Int") "($expr).toLong()" else expr
            PrimitiveKind.ULong -> "($expr).toULong()"
            PrimitiveKind.Float, PrimitiveKind.Double, PrimitiveKind.Bool -> expr
        }
}
