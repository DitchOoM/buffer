package com.ditchoom.buffer.codec.processor

import com.squareup.kotlinpoet.CodeBlock

internal fun addFieldRead(
    code: CodeBlock.Builder,
    field: FieldInfo,
) {
    val condition = field.condition
    if (condition != null) {
        val condExpr = (condition as FieldCondition.WhenTrue).expression
        code.beginControlFlow("val %L = if (%L)", field.name, condExpr)
        code.addStatement("%L", readExpression(field.strategy))
        code.nextControlFlow("else")
        code.addStatement("null")
        code.endControlFlow()
    } else {
        code.addStatement("val %L = %L", field.name, readExpression(field.strategy))
    }
}

internal fun readExpression(strategy: FieldReadStrategy): String =
    when (strategy) {
        is FieldReadStrategy.PrimitiveField -> {
            if (strategy.wireBytes == strategy.primitive.defaultWireBytes) {
                strategy.primitive.readExpr
            } else {
                customWidthReadExpr(strategy)
            }
        }
        is FieldReadStrategy.LengthPrefixedStringField -> {
            if (strategy.prefix == "Short") {
                "buffer.readLengthPrefixedUtf8String().second"
            } else {
                val lenExpr = prefixConfig(strategy.prefix).readExpr
                "run { val _len = $lenExpr; buffer.readString(_len) }"
            }
        }
        is FieldReadStrategy.RemainingBytesStringField -> "buffer.readString(buffer.remaining())"
        is FieldReadStrategy.LengthFromStringField -> "buffer.readString(${strategy.field}.toInt())"
        is FieldReadStrategy.ValueClassField -> {
            val inner = readExpression(strategy.innerStrategy)
            "${strategy.wrapperType}($inner)"
        }
        is FieldReadStrategy.NestedMessageField -> "${strategy.codecName}.decode(buffer)"
        is FieldReadStrategy.CollectionField -> readCollectionExpression(strategy)
        is FieldReadStrategy.PayloadField -> error("PayloadField uses writePayloadCodec path")
        is FieldReadStrategy.Custom -> {
            val d = strategy.descriptor
            val args = d.contextFields.joinToString(", ")
            if (args.isEmpty()) "buffer.${d.readFunction.functionName}()" else "buffer.${d.readFunction.functionName}($args)"
        }
    }

internal fun addFieldWrite(
    code: CodeBlock.Builder,
    field: FieldInfo,
    valueExpr: String,
) {
    val condition = field.condition
    if (condition != null) {
        val condExpr = (condition as FieldCondition.WhenTrue).expression.replace(Regex("^([^.]+)"), "value.$1")
        code.beginControlFlow("if (%L)", condExpr)
        code.addStatement("%L", writeExpression(field.strategy, "$valueExpr!!"))
        code.endControlFlow()
    } else {
        code.addStatement("%L", writeExpression(field.strategy, valueExpr))
    }
}

internal fun writeExpression(
    strategy: FieldReadStrategy,
    valueExpr: String,
): String =
    when (strategy) {
        is FieldReadStrategy.PrimitiveField -> {
            if (strategy.wireBytes == strategy.primitive.defaultWireBytes) {
                strategy.primitive.writeExpr(valueExpr)
            } else {
                customWidthWriteExpr(strategy, valueExpr)
            }
        }
        is FieldReadStrategy.LengthPrefixedStringField -> {
            if (strategy.prefix == "Short") {
                "buffer.writeLengthPrefixedUtf8String($valueExpr)"
            } else {
                val cfg = prefixConfig(strategy.prefix)
                val writeLenExpr = cfg.writeExpr("_len")
                "run { val _pos = buffer.position(); ${cfg.writePlaceholder}; buffer.writeString($valueExpr); " +
                    "val _end = buffer.position(); val _len = _end - _pos - ${cfg.byteCount}; " +
                    "buffer.position(_pos); $writeLenExpr; buffer.position(_end) }"
            }
        }
        is FieldReadStrategy.RemainingBytesStringField -> "buffer.writeString($valueExpr)"
        is FieldReadStrategy.LengthFromStringField -> "buffer.writeString($valueExpr)"
        is FieldReadStrategy.ValueClassField -> {
            val inner = "$valueExpr.${strategy.innerPropertyName}"
            writeExpression(strategy.innerStrategy, inner)
        }
        is FieldReadStrategy.NestedMessageField -> "${strategy.codecName}.encode(buffer, $valueExpr)"
        is FieldReadStrategy.CollectionField -> writeCollectionExpression(strategy, valueExpr)
        is FieldReadStrategy.PayloadField -> error("PayloadField uses writePayloadCodec path")
        is FieldReadStrategy.Custom -> {
            val d = strategy.descriptor
            val contextArgs = d.contextFields.joinToString(", ") { "value.$it" }
            val allArgs = if (contextArgs.isEmpty()) valueExpr else "$valueExpr, $contextArgs"
            "buffer.${d.writeFunction.functionName}($allArgs)"
        }
    }

private fun readCollectionExpression(strategy: FieldReadStrategy.CollectionField): String {
    val codecName = strategy.elementCodecName
    return when (val lk = strategy.lengthKind) {
        is LengthKind.FromField ->
            "buildList { repeat(${lk.field}.toInt()) { add($codecName.decode(buffer)) } }"
        is LengthKind.Remaining ->
            "buildList { while (buffer.remaining() > 0) { add($codecName.decode(buffer)) } }"
        is LengthKind.Prefixed -> {
            val cfg = prefixConfig(lk.prefix)
            "run { val _n = ${cfg.readExpr}; buildList { repeat(_n) { add($codecName.decode(buffer)) } } }"
        }
    }
}

private fun writeCollectionExpression(
    strategy: FieldReadStrategy.CollectionField,
    valueExpr: String,
): String {
    val codecName = strategy.elementCodecName
    return when (val lk = strategy.lengthKind) {
        is LengthKind.FromField ->
            "$valueExpr.forEach { $codecName.encode(buffer, it) }"
        is LengthKind.Remaining ->
            "$valueExpr.forEach { $codecName.encode(buffer, it) }"
        is LengthKind.Prefixed -> {
            val cfg = prefixConfig(lk.prefix)
            "run { ${cfg.writeExpr("$valueExpr.size")}; $valueExpr.forEach { $codecName.encode(buffer, it) } }"
        }
    }
}
