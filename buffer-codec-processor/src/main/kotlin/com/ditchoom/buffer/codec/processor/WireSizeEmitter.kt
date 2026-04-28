// Emits compile-time-known wire-size formulas, mirroring writeExpression in
// FieldCodeEmitter. Used to generate `Encoder.wireSize(value)` overrides so
// `encodeToBuffer` can allocate exact-size buffers without the grow-and-copy
// cost of GrowableWriteBuffer.

package com.ditchoom.buffer.codec.processor

import com.squareup.kotlinpoet.CodeBlock

/**
 * Appends `_size += <expr>` for [field], honoring the field's [FieldCondition].
 *
 * Mirrors [addFieldWrite]: skips fields under @WhenRemaining (caller handles the
 * cascade via [emitWhenRemainingWireSize]), wraps @WhenTrue fields in `if (cond)`.
 *
 * When [withContext] is true, nested `wireSize(...)` calls are emitted as
 * `wireSize(value, context)` so a `context: EncodeContext` in scope flows into
 * payload-aware sealed dispatchers (mirrors the [withContext] plumbing in
 * [addFieldWrite]).
 */
internal fun addFieldWireSize(
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
                "_size += %L",
                wireSizeExpression(field.strategy, "$valueExpr!!", field.byteOrderOverride, field.name, withContext),
            )
            code.endControlFlow()
        }
        is FieldCondition.WhenRemaining -> {
            // Cascading null check is emitted by emitWhenRemainingWireSize, mirroring
            // emitWhenRemainingEncode. This branch is only hit for the addFieldWireSize
            // path when caller ignores condition; in normal use, CodecGenerator routes
            // WhenRemaining tail fields through emitWhenRemainingWireSize instead.
            code.addStatement(
                "_size += %L",
                wireSizeExpression(field.strategy, "$valueExpr!!", field.byteOrderOverride, field.name, withContext),
            )
        }
        null -> {
            code.addStatement(
                "_size += %L",
                wireSizeExpression(field.strategy, valueExpr, field.byteOrderOverride, field.name, withContext),
            )
        }
    }
}

/**
 * Cascading null-check size accumulator for @WhenRemaining tail fields. Mirrors
 * [emitWhenRemainingEncode] exactly so size and encode produce a consistent view
 * of which fields are present. Locals are bound before the null check so the
 * smart-cast applies to the use site.
 */
internal fun emitWhenRemainingWireSize(
    code: CodeBlock.Builder,
    fields: List<FieldInfo>,
    withContext: Boolean = false,
) {
    for (field in fields) {
        code.addStatement("val %L = value.%L", field.name, field.name)
        code.beginControlFlow("if (%L != null)", field.name)
        code.addStatement(
            "_size += %L",
            wireSizeExpression(field.strategy, field.name, field.byteOrderOverride, withContext = withContext),
        )
    }
    repeat(fields.size) { code.endControlFlow() }
}

/**
 * Returns a Kotlin expression that evaluates to the wire byte count for [strategy]
 * applied to [valueExpr]. Mirrors [writeExpression] strategy by strategy.
 *
 * Per-strategy formulas:
 *  - Primitive: literal `wireBytes` (compile-time constant)
 *  - LengthPrefixedString: prefix bytes + UTF-8 byte count of value
 *  - RemainingBytes/LengthFromString: UTF-8 byte count (no prefix)
 *  - ValueClass: recurse with `value.<innerProperty>`
 *  - NestedMessage / DiscriminatorField / UseCodec: `Codec.wireSize(value[, context])`
 *  - NestedMessageWithLength / Collection / UseCodec(prefixed): prefix + body
 *  - Custom: `descriptor.fixedSize` literal, or `buffer.<wireSizeFn>(value, ...)`
 *  - Payload: error — payload codecs use a separate wireSize<P>(value, payloadSize) signature
 *
 * When [withContext] is true, nested codec calls receive a trailing `, context`
 * argument so a caller-scope `context: EncodeContext` flows through. Caller MUST
 * have `context` in scope when passing `withContext = true`.
 */
internal fun wireSizeExpression(
    strategy: FieldReadStrategy,
    valueExpr: String,
    byteOrderOverride: WireOrderOverride? = null,
    fieldName: String = "",
    withContext: Boolean = false,
): String {
    val ctxArg = if (withContext) ", context" else ""
    return when (strategy) {
        is FieldReadStrategy.PrimitiveField -> "${strategy.wireBytes}"

        is FieldReadStrategy.LengthPrefixedStringField ->
            when (val kind = strategy.kind) {
                is LengthPrefixKind.Varint ->
                    "run { val _l = $valueExpr.utf8Length(); variableByteSizeInt(_l) + _l }"
                else -> "(${kind.maxWireBytes} + $valueExpr.utf8Length())"
            }

        is FieldReadStrategy.RemainingBytesStringField -> "$valueExpr.utf8Length()"
        is FieldReadStrategy.LengthFromStringField -> "$valueExpr.utf8Length()"

        is FieldReadStrategy.ValueClassField ->
            wireSizeExpression(
                strategy.innerStrategy,
                "$valueExpr.${strategy.innerPropertyName}",
                byteOrderOverride,
                fieldName,
                withContext,
            )

        is FieldReadStrategy.NestedMessageField -> "${strategy.codecName}.wireSize($valueExpr$ctxArg)"

        is FieldReadStrategy.NestedMessageWithLengthField ->
            wireSizeWithLength(
                bodyExpr = "${strategy.codecName}.wireSize($valueExpr$ctxArg)",
                lengthKind = strategy.lengthKind,
            )

        is FieldReadStrategy.UseCodecField ->
            wireSizeWithLength(
                bodyExpr = "${strategy.codecName}.wireSize($valueExpr$ctxArg)",
                lengthKind = strategy.lengthKind,
            )

        is FieldReadStrategy.CollectionField ->
            wireSizeWithLength(
                bodyExpr = "$valueExpr.sumOf { ${strategy.elementCodecName}.wireSize(it$ctxArg) }",
                lengthKind = strategy.lengthKind,
            )

        is FieldReadStrategy.DiscriminatorField -> "${strategy.codecName}.wireSize($valueExpr$ctxArg)"

        is FieldReadStrategy.PayloadField ->
            error("PayloadField wireSize is emitted via the payload codec entry-point path")

        is FieldReadStrategy.Custom -> {
            val d = strategy.descriptor
            val sizeFn = d.wireSizeFunction
            when {
                sizeFn != null -> {
                    val contextArgs = d.contextFields.joinToString(", ") { "value.$it" }
                    val allArgs = if (contextArgs.isEmpty()) valueExpr else "$valueExpr, $contextArgs"
                    "${sizeFn.functionName}($allArgs)"
                }
                d.fixedSize >= 0 -> "${d.fixedSize}"
                // Unreachable: FieldAnalyzer rejects this combination via KSPLogger.error
                // before code generation runs. The branch exists so wireSizeExpression's
                // when remains exhaustive for future strategy additions.
                else -> "0 /* unreachable: variable Custom requires wireSizeFunction */"
            }
        }
    }
}

/**
 * Size formula for a body framed by an optional length prefix. Mirrors the
 * length-handling branches in [writeNestedWithLengthExpression],
 * [writeCollectionExpression], and [writeUseCodecExpression] so encode/wireSize
 * stay byte-perfect.
 *
 * - [LengthKind.Prefixed] (Varint): `variableByteSizeInt(body) + body`, with
 *   one evaluation of [bodyExpr] via a `run { val _b = ...; ... }` block.
 * - [LengthKind.Prefixed] (Byte/Short/Int): `prefix.maxWireBytes + body`,
 *   evaluated inline.
 * - [LengthKind.Remaining] / [LengthKind.FromField]: just [bodyExpr] — the
 *   length lives in another field or is implied by buffer remaining.
 */
private fun wireSizeWithLength(
    bodyExpr: String,
    lengthKind: LengthKind?,
): String {
    if (lengthKind == null) return bodyExpr
    return when (lengthKind) {
        is LengthKind.Prefixed ->
            when (val kind = lengthKind.kind) {
                is LengthPrefixKind.Varint ->
                    "run { val _b = $bodyExpr; variableByteSizeInt(_b) + _b }"
                else -> "(${kind.maxWireBytes} + $bodyExpr)"
            }
        is LengthKind.Remaining -> bodyExpr
        is LengthKind.FromField -> bodyExpr
    }
}
