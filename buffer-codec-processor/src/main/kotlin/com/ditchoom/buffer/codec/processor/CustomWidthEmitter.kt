package com.ditchoom.buffer.codec.processor

internal fun customWidthReadExpr(
    strategy: FieldReadStrategy.PrimitiveField,
    byteOrderOverride: WireOrderOverride? = null,
): String {
    val p = strategy.primitive
    val wireBytes = strategy.wireBytes
    val chunks = decomposeWireBytes(wireBytes)
    val h = if (p.defaultWireBytes <= 4) "Int" else "Long"
    val isLE = byteOrderOverride == WireOrderOverride.Little

    val stmts = mutableListOf<String>()
    var remainingBytes = wireBytes
    val chunkVars = mutableListOf<Pair<String, Int>>()
    var varIndex = 0
    var consumedBytes = 0

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
        // BE: first chunk is most significant (shift = remainingBytes * 8)
        // LE: first chunk is least significant (shift = consumedBytes * 8)
        val shift = if (isLE) consumedBytes * 8 else remainingBytes * 8
        chunkVars.add(varName to shift)
        consumedBytes += cs
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
    byteOrderOverride: WireOrderOverride? = null,
): String {
    val p = strategy.primitive
    val wireBytes = strategy.wireBytes
    val chunks = decomposeWireBytes(wireBytes)
    val h = if (p.defaultWireBytes <= 4) "Int" else "Long"
    val isLE = byteOrderOverride == WireOrderOverride.Little

    val stmts = mutableListOf<String>()
    val vExpr = if (p == Primitive.INT || p == Primitive.LONG) valueExpr else "$valueExpr.to$h()"
    stmts.add("val _v = $vExpr")

    var remainingBytes = wireBytes
    var consumedBytes = 0
    for (cs in chunks) {
        remainingBytes -= cs
        // BE: write most significant chunk first (shift = remainingBytes * 8)
        // LE: write least significant chunk first (shift = consumedBytes * 8)
        val shift = if (isLE) consumedBytes * 8 else remainingBytes * 8
        val shifted = if (shift > 0) "(_v ushr $shift)" else "_v"
        stmts.add(
            when (cs) {
                4 -> {
                    val intExpr = "$shifted${if (h == "Long") ".toInt()" else ""}"
                    if (isLE) "buffer.writeInt($intExpr.reverseBytes())" else "buffer.writeInt($intExpr)"
                }
                2 -> {
                    if (isLE) "buffer.writeShort($shifted.toShort().reverseBytes())" else "buffer.writeShort($shifted.toShort())"
                }
                else -> "buffer.writeByte($shifted.toByte())"
            },
        )
        consumedBytes += cs
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
