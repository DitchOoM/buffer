package com.ditchoom.buffer.codec.processor

import com.squareup.kotlinpoet.CodeBlock

internal fun addFieldRead(
    code: CodeBlock.Builder,
    field: FieldInfo,
    withContext: Boolean = false,
) {
    val condition = field.condition
    if (condition != null) {
        val condExpr = (condition as FieldCondition.WhenTrue).expression
        code.beginControlFlow("val %L = if (%L)", field.name, condExpr)
        code.addStatement("%L", readExpression(field.strategy, withContext))
        code.nextControlFlow("else")
        code.addStatement("null")
        code.endControlFlow()
    } else {
        code.addStatement("val %L = %L", field.name, readExpression(field.strategy, withContext))
    }
}

internal fun readExpression(
    strategy: FieldReadStrategy,
    withContext: Boolean = false,
): String =
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
            val inner = readExpression(strategy.innerStrategy, withContext)
            "${strategy.wrapperType}($inner)"
        }
        is FieldReadStrategy.NestedMessageField -> {
            val ctxArg = if (withContext) ", context" else ""
            "${strategy.codecName}.decode(buffer$ctxArg)"
        }
        is FieldReadStrategy.UseCodecField -> readUseCodecExpression(strategy, withContext)
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
    withContext: Boolean = false,
) {
    val condition = field.condition
    if (condition != null) {
        val condExpr = (condition as FieldCondition.WhenTrue).expression.replace(Regex("^([^.]+)"), "value.$1")
        code.beginControlFlow("if (%L)", condExpr)
        code.addStatement("%L", writeExpression(field.strategy, "$valueExpr!!", withContext))
        code.endControlFlow()
    } else {
        code.addStatement("%L", writeExpression(field.strategy, valueExpr, withContext))
    }
}

internal fun writeExpression(
    strategy: FieldReadStrategy,
    valueExpr: String,
    withContext: Boolean = false,
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
            writeExpression(strategy.innerStrategy, inner, withContext)
        }
        is FieldReadStrategy.NestedMessageField -> {
            val ctxArg = if (withContext) ", context" else ""
            "${strategy.codecName}.encode(buffer, $valueExpr$ctxArg)"
        }
        is FieldReadStrategy.UseCodecField -> writeUseCodecExpression(strategy, valueExpr, withContext)
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

private fun readUseCodecExpression(
    strategy: FieldReadStrategy.UseCodecField,
    withContext: Boolean = false,
): String {
    val codec = strategy.codecName
    val ctxArg = if (withContext) ", context" else ""
    val lk = strategy.lengthKind ?: return "$codec.decode(buffer$ctxArg)"
    return when (lk) {
        is LengthKind.Prefixed -> {
            val lenExpr = prefixConfig(lk.prefix).readExpr
            "run { val _len = $lenExpr; $codec.decode(buffer.readBytes(_len)$ctxArg) }"
        }
        is LengthKind.Remaining ->
            "$codec.decode(buffer.readBytes(buffer.remaining())$ctxArg)"
        is LengthKind.FromField ->
            "$codec.decode(buffer.readBytes(${lk.field}.toInt())$ctxArg)"
    }
}

private fun writeUseCodecExpression(
    strategy: FieldReadStrategy.UseCodecField,
    valueExpr: String,
    withContext: Boolean = false,
): String {
    val codec = strategy.codecName
    val ctxArg = if (withContext) ", context" else ""
    val lk = strategy.lengthKind ?: return "$codec.encode(buffer, $valueExpr$ctxArg)"
    // With a length prefix: write placeholder, encode, then fill in the length
    return when (lk) {
        is LengthKind.Prefixed -> {
            val cfg = prefixConfig(lk.prefix)
            "run { val _pos = buffer.position(); ${cfg.writePlaceholder}; " +
                "$codec.encode(buffer, $valueExpr$ctxArg); " +
                "val _end = buffer.position(); val _len = _end - _pos - ${cfg.byteCount}; " +
                "buffer.position(_pos); ${cfg.writeExpr("_len")}; buffer.position(_end) }"
        }
        is LengthKind.Remaining ->
            "$codec.encode(buffer, $valueExpr$ctxArg)"
        is LengthKind.FromField ->
            "$codec.encode(buffer, $valueExpr$ctxArg)"
    }
}
