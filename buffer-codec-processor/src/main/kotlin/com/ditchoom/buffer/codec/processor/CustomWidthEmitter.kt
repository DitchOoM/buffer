package com.ditchoom.buffer.codec.processor

internal fun customWidthReadExpr(strategy: FieldReadStrategy.PrimitiveField): String {
    val p = strategy.primitive
    val wireBytes = strategy.wireBytes
    val chunks = decomposeWireBytes(wireBytes)
    val h = if (p.defaultWireBytes <= 4) "Int" else "Long"

    val stmts = mutableListOf<String>()
    var remainingBytes = wireBytes
    val chunkVars = mutableListOf<Pair<String, Int>>()
    var varIndex = 0

    for (cs in chunks) {
        remainingBytes -= cs
        val varName = "_c${varIndex++}"
        val read =
            when (cs) {
                4 -> "buffer.readInt()" + if (h == "Long") ".toLong() and 0xFFFFFFFFL" else ""
                2 -> "buffer.readShort().to$h() and ${if (h == "Long") "0xFFFFL" else "0xFFFF"}"
                else -> "buffer.readByte().to$h() and ${if (h == "Long") "0xFFL" else "0xFF"}"
            }
        stmts.add("val $varName = $read")
        chunkVars.add(varName to remainingBytes * 8)
    }

    val rawExpr = chunkVars.joinToString(" or ") { (name, shift) -> if (shift > 0) "($name shl $shift)" else name }

    if (p.signed && wireBytes < p.defaultWireBytes) {
        val shiftAmount = (if (h == "Long") 64 else 32) - wireBytes * 8
        stmts.add("val _raw = $rawExpr")
        stmts.add(typeCast(p, "(_raw shl $shiftAmount) shr $shiftAmount", h))
    } else {
        stmts.add(typeCast(p, rawExpr, h))
    }

    return "run { ${stmts.joinToString("; ")} }"
}

internal fun customWidthWriteExpr(
    strategy: FieldReadStrategy.PrimitiveField,
    valueExpr: String,
): String {
    val p = strategy.primitive
    val wireBytes = strategy.wireBytes
    val chunks = decomposeWireBytes(wireBytes)
    val h = if (p.defaultWireBytes <= 4) "Int" else "Long"

    val stmts = mutableListOf<String>()
    val vExpr = if (p == Primitive.INT || p == Primitive.LONG) valueExpr else "$valueExpr.to$h()"
    stmts.add("val _v = $vExpr")

    var remainingBytes = wireBytes
    for (cs in chunks) {
        remainingBytes -= cs
        val shift = remainingBytes * 8
        val shifted = if (shift > 0) "(_v ushr $shift)" else "_v"
        stmts.add(
            when (cs) {
                4 -> "buffer.writeInt($shifted${if (h == "Long") ".toInt()" else ""})"
                2 -> "buffer.writeShort($shifted.toShort())"
                else -> "buffer.writeByte($shifted.toByte())"
            },
        )
    }

    return "run { ${stmts.joinToString("; ")} }"
}

internal fun decomposeWireBytes(wireBytes: Int): List<Int> =
    buildList {
        var r = wireBytes
        for (s in intArrayOf(4, 2, 1)) {
            if (r >= s) {
                add(s)
                r -= s
            }
        }
    }

internal fun typeCast(
    primitive: Primitive,
    expr: String,
    holdingType: String,
): String =
    when (primitive) {
        Primitive.BYTE -> "($expr).toByte()"
        Primitive.UBYTE -> "($expr).toUByte()"
        Primitive.SHORT -> "($expr).toShort()"
        Primitive.USHORT -> "($expr).toUShort()"
        Primitive.INT -> expr
        Primitive.UINT -> "($expr).toUInt()"
        Primitive.LONG -> if (holdingType == "Int") "($expr).toLong()" else expr
        Primitive.ULONG -> "($expr).toULong()"
        else -> expr
    }
