package com.ditchoom.buffer.codec.processor

import com.squareup.kotlinpoet.CodeBlock

// No wrapper needed — byte order is handled inline via swappedReadExpr/swappedWriteExpr on Primitive

internal fun addFieldRead(
    code: CodeBlock.Builder,
    field: FieldInfo,
    withContext: Boolean = false,
) {
    val condition = field.condition
    val expr = readExpression(field.strategy, withContext, field.byteOrderOverride)
    when (condition) {
        is FieldCondition.WhenTrue -> {
            code.beginControlFlow("val %L = if (%L)", field.name, condition.expression)
            code.addStatement("%L", expr)
            code.nextControlFlow("else")
            code.addStatement("null")
            code.endControlFlow()
        }
        is FieldCondition.WhenRemaining -> {
            code.beginControlFlow("val %L = if (buffer.remaining() >= %L)", field.name, condition.minBytes)
            code.addStatement("%L", expr)
            code.nextControlFlow("else")
            code.addStatement("null")
            code.endControlFlow()
        }
        null -> {
            code.addStatement("val %L = %L", field.name, expr)
        }
    }
}

internal fun readExpression(
    strategy: FieldReadStrategy,
    withContext: Boolean = false,
    byteOrderOverride: WireOrderOverride? = null,
): String =
    when (strategy) {
        is FieldReadStrategy.PrimitiveField -> {
            if (strategy.wireBytes == strategy.primitive.defaultWireBytes) {
                val swapped = if (byteOrderOverride != null) strategy.primitive.swappedReadExpr else null
                swapped ?: strategy.primitive.readExpr
            } else {
                customWidthReadExpr(strategy, byteOrderOverride)
            }
        }
        is FieldReadStrategy.LengthPrefixedStringField ->
            when (strategy.kind) {
                is LengthPrefixKind.Short -> "buffer.readLengthPrefixedUtf8String().second"
                else -> "run { val _len = ${readLengthExpr(strategy.kind)}; buffer.readString(_len) }"
            }
        is FieldReadStrategy.RemainingBytesStringField ->
            if (strategy.trailingBytes > 0) {
                "buffer.readString(buffer.remaining() - ${strategy.trailingBytes})"
            } else {
                "buffer.readString(buffer.remaining())"
            }
        is FieldReadStrategy.LengthFromStringField -> "buffer.readString(${strategy.field}.toInt())"
        is FieldReadStrategy.ValueClassField -> {
            val inner = readExpression(strategy.innerStrategy, withContext, byteOrderOverride)
            "${strategy.wrapperType}($inner)"
        }
        is FieldReadStrategy.NestedMessageField -> {
            val ctxArg = if (withContext) ", context" else ""
            "${strategy.codecName}.decode(buffer$ctxArg)"
        }
        is FieldReadStrategy.NestedMessageWithLengthField -> readNestedWithLengthExpression(strategy, withContext)
        is FieldReadStrategy.UseCodecField -> readUseCodecExpression(strategy, withContext)
        is FieldReadStrategy.CollectionField -> readCollectionExpression(strategy, withContext)
        is FieldReadStrategy.DiscriminatorField -> {
            // Prefer the dispatcher's pre-parsed discriminator from context (which the dispatcher
            // populates after consuming the byte); fall back to reading from the buffer when no
            // context is supplied. The fall-back makes standalone sub-codec decode round-trip
            // cleanly with sub-codec encode (which writes the discriminator byte). The dispatcher
            // codec lives in the same package as every variant codec, so an unqualified reference
            // is unambiguous and stays correct even when the package is empty.
            "${strategy.dispatchCodecSimpleName}.DiscriminatorKey" +
                ".let { key -> context[key] ?: ${strategy.codecName}.decode(buffer) }"
        }
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
    when (val condition = field.condition) {
        is FieldCondition.WhenTrue -> {
            val condExpr = condition.expression.replace(Regex("^([^.]+)"), "value.$1")
            code.beginControlFlow("if (%L)", condExpr)
            code.addStatement(
                "%L",
                writeExpression(field.strategy, "$valueExpr!!", withContext, field.byteOrderOverride, field.name),
            )
            code.endControlFlow()
        }
        is FieldCondition.WhenRemaining -> {
            // Individual null check — cascading is handled by CodecGenerator
            code.addStatement(
                "%L",
                writeExpression(field.strategy, "$valueExpr!!", withContext, field.byteOrderOverride, field.name),
            )
        }
        null -> {
            code.addStatement(
                "%L",
                writeExpression(field.strategy, valueExpr, withContext, field.byteOrderOverride, field.name),
            )
        }
    }
}

/**
 * Emits cascading null-check encode blocks for @WhenRemaining fields.
 * If field N is null, all subsequent fields are skipped — preventing impossible wire states.
 *
 * Binds each property to a local `val` before the null check so the compiler's smart-cast
 * applies to the use site — avoids `value.name!!` (which triggers
 * `UNNECESSARY_NOT_NULL_ASSERTION` on final-class properties since smart-cast already
 * narrowed `value.name` inside the `if`) while still compiling correctly for interface
 * properties (where smart-cast on `value.name` wouldn't apply, but smart-cast on a local
 * `val` always does).
 */
internal fun emitWhenRemainingEncode(
    code: CodeBlock.Builder,
    fields: List<FieldInfo>,
    withContext: Boolean = false,
) {
    for (field in fields) {
        code.addStatement("val %L = value.%L", field.name, field.name)
        code.beginControlFlow("if (%L != null)", field.name)
        code.addStatement(
            "%L",
            writeExpression(field.strategy, field.name, withContext, field.byteOrderOverride),
        )
    }
    repeat(fields.size) { code.endControlFlow() }
}

internal fun writeExpression(
    strategy: FieldReadStrategy,
    valueExpr: String,
    withContext: Boolean = false,
    byteOrderOverride: WireOrderOverride? = null,
    fieldName: String = "",
): String =
    when (strategy) {
        is FieldReadStrategy.PrimitiveField -> {
            if (strategy.wireBytes == strategy.primitive.defaultWireBytes) {
                val swapped = if (byteOrderOverride != null) strategy.primitive.swappedWriteExpr else null
                swapped?.invoke(valueExpr) ?: strategy.primitive.writeExpr(valueExpr)
            } else {
                customWidthWriteExpr(strategy, valueExpr, byteOrderOverride)
            }
        }
        is FieldReadStrategy.LengthPrefixedStringField ->
            when (val kind = strategy.kind) {
                is LengthPrefixKind.Short -> "buffer.writeLengthPrefixedUtf8String($valueExpr)"
                is LengthPrefixKind.Varint ->
                    emitInlineVarintLengthPrefixed(
                        fieldName = fieldName,
                        bodySizeExpr = "$valueExpr.utf8Length()",
                        encodeBody = "buffer.writeString($valueExpr)",
                        maxBytes = kind.maxBytes,
                    )
                else -> {
                    val cfg = fixedPrefixConfigOrError(kind)
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
            writeExpression(strategy.innerStrategy, inner, withContext, byteOrderOverride)
        }
        is FieldReadStrategy.NestedMessageField -> {
            val ctxArg = if (withContext) ", context" else ""
            "${strategy.codecName}.encode(buffer, $valueExpr$ctxArg)"
        }
        is FieldReadStrategy.NestedMessageWithLengthField ->
            writeNestedWithLengthExpression(strategy, valueExpr, withContext, fieldName)
        is FieldReadStrategy.UseCodecField -> writeUseCodecExpression(strategy, valueExpr, withContext, fieldName)
        is FieldReadStrategy.CollectionField -> writeCollectionExpression(strategy, valueExpr, withContext, fieldName)
        is FieldReadStrategy.DiscriminatorField -> {
            // Write normally via the discriminator codec during encode
            val ctxArg = if (withContext) ", context" else ""
            "${strategy.codecName}.encode(buffer, $valueExpr$ctxArg)"
        }
        is FieldReadStrategy.PayloadField -> error("PayloadField uses writePayloadCodec path")
        is FieldReadStrategy.Custom -> {
            val d = strategy.descriptor
            val contextArgs = d.contextFields.joinToString(", ") { "value.$it" }
            val allArgs = if (contextArgs.isEmpty()) valueExpr else "$valueExpr, $contextArgs"
            "buffer.${d.writeFunction.functionName}($allArgs)"
        }
    }

private fun readCollectionExpression(
    strategy: FieldReadStrategy.CollectionField,
    withContext: Boolean = false,
): String {
    val codecName = strategy.elementCodecName
    val typeName = strategy.elementTypeSimpleName
    val ctxArg = if (withContext) ", context" else ""
    return when (val lk = strategy.lengthKind) {
        is LengthKind.FromField ->
            "buildList<$typeName> { repeat(${lk.field}.toInt()) { add($codecName.decode(buffer$ctxArg)) } }"
        is LengthKind.Remaining -> {
            val threshold = lk.trailingBytes
            "buildList<$typeName> { while (buffer.remaining() > $threshold) { add($codecName.decode(buffer$ctxArg)) } }"
        }
        is LengthKind.Prefixed ->
            // Byte-size + slice: prefix is the encoded byte length of the collection body;
            // decode loops elementCodec.decode until the slice is empty. Symmetric across
            // Byte/Short/Int/Varint widths. Count-prefix semantics use @LengthFrom instead.
            "run { val _len = ${readLengthExpr(lk.kind)}; " +
                "val _slice = buffer.readBytes(_len); " +
                "buildList<$typeName> { while (_slice.remaining() > 0) { add($codecName.decode(_slice$ctxArg)) } } }"
    }
}

private fun writeCollectionExpression(
    strategy: FieldReadStrategy.CollectionField,
    valueExpr: String,
    withContext: Boolean = false,
    fieldName: String = "",
): String {
    val codecName = strategy.elementCodecName
    val ctxArg = if (withContext) ", context" else ""
    return when (val lk = strategy.lengthKind) {
        is LengthKind.FromField ->
            "$valueExpr.forEach { $codecName.encode(buffer, it$ctxArg) }"
        is LengthKind.Remaining ->
            "$valueExpr.forEach { $codecName.encode(buffer, it$ctxArg) }"
        is LengthKind.Prefixed -> {
            // Byte-size patch-up: reserve placeholder, encode body, measure byte length,
            // patch the prefix. Mirrors the nested-message length-prefix path so the
            // "@LengthPrefixed Collection<X>" semantic is unified with @LengthPrefixed
            // on a wrapper @ProtocolMessage struct.
            val encodeBody = "$valueExpr.forEach { $codecName.encode(buffer, it$ctxArg) }"
            when (val kind = lk.kind) {
                is LengthPrefixKind.Varint ->
                    emitInlineVarintLengthPrefixed(
                        fieldName = fieldName,
                        bodySizeExpr = "$valueExpr.sumOf { $codecName.wireSize(it$ctxArg) }",
                        encodeBody = encodeBody,
                        maxBytes = kind.maxBytes,
                    )
                else ->
                    fixedLengthPrefixedEncodeRun(
                        cfg = fixedPrefixConfigOrError(kind),
                        encodeStmt = encodeBody,
                    )
            }
        }
    }
}

private fun readUseCodecExpression(
    strategy: FieldReadStrategy.UseCodecField,
    withContext: Boolean = false,
): String {
    val codec = strategy.codecName
    // Only pass context to codecs with context overloads (Codec<T> has them; Decoder<T>/Encoder<T> do not)
    val ctxArg = if (withContext && strategy.hasContextOverloads) ", context" else ""
    val lk = strategy.lengthKind ?: return "$codec.decode(buffer$ctxArg)"
    return when (lk) {
        is LengthKind.Prefixed ->
            "run { val _len = ${readLengthExpr(lk.kind)}; $codec.decode(buffer.readBytes(_len)$ctxArg) }"
        is LengthKind.Remaining -> {
            val bound = if (lk.trailingBytes > 0) "buffer.remaining() - ${lk.trailingBytes}" else "buffer.remaining()"
            "$codec.decode(buffer.readBytes($bound)$ctxArg)"
        }
        is LengthKind.FromField ->
            "$codec.decode(buffer.readBytes(${lk.field}.toInt())$ctxArg)"
    }
}

private fun readNestedWithLengthExpression(
    strategy: FieldReadStrategy.NestedMessageWithLengthField,
    withContext: Boolean = false,
): String {
    val codec = strategy.codecName
    val ctxArg = if (withContext) ", context" else ""
    return when (val lk = strategy.lengthKind) {
        is LengthKind.Prefixed ->
            "run { val _len = ${readLengthExpr(lk.kind)}; $codec.decode(buffer.readBytes(_len)$ctxArg) }"
        is LengthKind.Remaining -> {
            val bound = if (lk.trailingBytes > 0) "buffer.remaining() - ${lk.trailingBytes}" else "buffer.remaining()"
            "$codec.decode(buffer.readBytes($bound)$ctxArg)"
        }
        is LengthKind.FromField ->
            "$codec.decode(buffer.readBytes(${lk.field}.toInt())$ctxArg)"
    }
}

/** Wraps a fixed-width length-prefixed encode call: position + placeholder + encode + patch. */
private fun fixedLengthPrefixedEncodeRun(
    cfg: PrefixConfig,
    encodeStmt: String,
): String =
    "run { val _pos = buffer.position(); ${cfg.writePlaceholder}; " +
        "$encodeStmt; " +
        "val _end = buffer.position(); val _len = _end - _pos - ${cfg.byteCount}; " +
        "buffer.position(_pos); ${cfg.writeExpr("_len")}; buffer.position(_end) }"

private fun writeNestedWithLengthExpression(
    strategy: FieldReadStrategy.NestedMessageWithLengthField,
    valueExpr: String,
    withContext: Boolean = false,
    fieldName: String = "",
): String {
    val codec = strategy.codecName
    val ctxArg = if (withContext) ", context" else ""
    return when (val lk = strategy.lengthKind) {
        is LengthKind.Prefixed ->
            when (val kind = lk.kind) {
                is LengthPrefixKind.Varint ->
                    emitInlineVarintLengthPrefixed(
                        fieldName = fieldName,
                        bodySizeExpr = "$codec.wireSize($valueExpr$ctxArg)",
                        encodeBody = "$codec.encode(buffer, $valueExpr$ctxArg)",
                        maxBytes = kind.maxBytes,
                    )
                else ->
                    fixedLengthPrefixedEncodeRun(
                        cfg = fixedPrefixConfigOrError(kind),
                        encodeStmt = "$codec.encode(buffer, $valueExpr$ctxArg)",
                    )
            }
        is LengthKind.Remaining,
        is LengthKind.FromField,
        ->
            "$codec.encode(buffer, $valueExpr$ctxArg)"
    }
}

private fun writeUseCodecExpression(
    strategy: FieldReadStrategy.UseCodecField,
    valueExpr: String,
    withContext: Boolean = false,
    fieldName: String = "",
): String {
    val codec = strategy.codecName
    // Only pass context to codecs with context overloads (Codec<T> has them; Decoder<T>/Encoder<T> do not)
    val ctxArg = if (withContext && strategy.hasContextOverloads) ", context" else ""
    val lk = strategy.lengthKind ?: return "$codec.encode(buffer, $valueExpr$ctxArg)"
    return when (lk) {
        is LengthKind.Prefixed ->
            when (val kind = lk.kind) {
                is LengthPrefixKind.Varint ->
                    emitInlineVarintLengthPrefixed(
                        fieldName = fieldName,
                        bodySizeExpr = "$codec.wireSize($valueExpr$ctxArg)",
                        encodeBody = "$codec.encode(buffer, $valueExpr$ctxArg)",
                        maxBytes = kind.maxBytes,
                    )
                else ->
                    fixedLengthPrefixedEncodeRun(
                        cfg = fixedPrefixConfigOrError(kind),
                        encodeStmt = "$codec.encode(buffer, $valueExpr$ctxArg)",
                    )
            }
        is LengthKind.Remaining ->
            "$codec.encode(buffer, $valueExpr$ctxArg)"
        is LengthKind.FromField ->
            "$codec.encode(buffer, $valueExpr$ctxArg)"
    }
}
