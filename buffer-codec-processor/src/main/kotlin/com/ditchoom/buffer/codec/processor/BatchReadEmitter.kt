package com.ditchoom.buffer.codec.processor

import com.squareup.kotlinpoet.CodeBlock

internal fun addBatchRead(
    code: CodeBlock.Builder,
    item: BatchGroup,
    batchIndex: Int,
) {
    val batchVar = "_batch$batchIndex"
    val readType =
        when (item.readMethod) {
            "readLong" -> "Long"
            "readInt" -> "Int"
            "readShort" -> "Short"
            else -> "Byte"
        }
    code.addStatement("val %L = buffer.%L()", batchVar, item.readMethod)
    if (readType == "Short" || readType == "Byte") {
        code.addStatement(
            "val %LBits = %L.toInt() and %L",
            batchVar,
            batchVar,
            if (readType == "Short") "0xFFFF" else "0xFF",
        )
    }

    var bitOffset = item.totalBytes * 8
    for (field in item.fields) {
        val fieldSize = field.strategy.fixedSize
        val fieldBits = fieldSize * 8
        bitOffset -= fieldBits
        val extractExpr =
            generateExtractExpression(
                batchVar = if (readType == "Short" || readType == "Byte") "${batchVar}Bits" else batchVar,
                bitOffset = bitOffset,
                fieldBits = fieldBits,
                readType = readType,
                strategy = field.strategy,
            )
        code.addStatement("val %L = %L", field.name, extractExpr)
    }
}

internal fun generateExtractExpression(
    batchVar: String,
    bitOffset: Int,
    fieldBits: Int,
    readType: String,
    strategy: FieldReadStrategy,
): String {
    val batchBits =
        when (readType) {
            "Long" -> 64
            "Int" -> 32
            "Short" -> 16
            else -> 8
        }
    val mask = if (fieldBits >= batchBits) "" else hexMask(fieldBits)

    val shift = if (bitOffset > 0) " ushr $bitOffset" else ""
    val maskApply = if (mask.isNotEmpty()) " and $mask" else ""

    val rawExpr = "($batchVar$shift$maskApply)"

    return when (strategy) {
        is FieldReadStrategy.PrimitiveField -> {
            if (strategy.wireBytes == strategy.primitive.defaultWireBytes) {
                standardBatchCast(strategy.primitive, rawExpr, readType)
            } else {
                customWidthBatchCast(strategy, rawExpr, readType)
            }
        }
        is FieldReadStrategy.ValueClassField -> {
            val innerExpr = generateExtractExpression(batchVar, bitOffset, fieldBits, readType, strategy.innerStrategy)
            "${strategy.wrapperType}($innerExpr)"
        }
        is FieldReadStrategy.Custom -> error("Custom fields cannot participate in batch reads")
        else -> rawExpr
    }
}

internal fun hexMask(bits: Int): String {
    val hex = "FF".repeat(bits / 8)
    return if (bits >= 32) "0x${hex}L" else "0x$hex"
}

internal fun standardBatchCast(
    primitive: Primitive,
    rawExpr: String,
    readType: String,
): String =
    when (primitive) {
        Primitive.BYTE -> "$rawExpr.toByte()"
        Primitive.UBYTE -> "$rawExpr.toUByte()"
        Primitive.SHORT -> "$rawExpr.toShort()"
        Primitive.USHORT -> "$rawExpr.toUShort()"
        Primitive.INT -> if (readType == "Long") "$rawExpr.toInt()" else rawExpr
        Primitive.UINT -> "$rawExpr.toUInt()"
        Primitive.LONG -> rawExpr
        Primitive.ULONG -> "$rawExpr.toULong()"
        Primitive.FLOAT -> "Float.fromBits($rawExpr.toInt())"
        Primitive.DOUBLE -> "Double.fromBits($rawExpr)"
        Primitive.BOOLEAN -> "$rawExpr != 0"
    }

internal fun customWidthBatchCast(
    strategy: FieldReadStrategy.PrimitiveField,
    rawExpr: String,
    readType: String,
): String {
    val p = strategy.primitive
    val wireBits = strategy.wireBytes * 8
    val holdingType = if (p.defaultWireBytes <= 4) "Int" else "Long"

    // Convert from batch read type to target's holding type
    val converted =
        when {
            readType == "Long" && holdingType == "Int" -> "$rawExpr.toInt()"
            readType != "Long" && holdingType == "Long" -> "$rawExpr.toLong()"
            else -> rawExpr
        }

    return if (p.signed) {
        val totalBits = if (holdingType == "Long") 64 else 32
        val shiftAmount = totalBits - wireBits
        val extended = "($converted shl $shiftAmount) shr $shiftAmount"
        typeCast(p, extended, holdingType)
    } else {
        typeCast(p, converted, holdingType)
    }
}
