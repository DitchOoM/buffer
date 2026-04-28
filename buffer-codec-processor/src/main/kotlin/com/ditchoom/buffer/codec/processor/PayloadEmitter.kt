package com.ditchoom.buffer.codec.processor

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock

internal val READ_BUFFER = ClassName("com.ditchoom.buffer", "ReadBuffer")
internal val WRITE_BUFFER = ClassName("com.ditchoom.buffer", "WriteBuffer")
internal val CODEC = ClassName("com.ditchoom.buffer.codec", "Codec")
internal val DECODER = ClassName("com.ditchoom.buffer.codec", "Decoder")
internal val ENCODER = ClassName("com.ditchoom.buffer.codec", "Encoder")
internal val DECODE_CONTEXT = ClassName("com.ditchoom.buffer.codec", "DecodeContext")
internal val ENCODE_CONTEXT = ClassName("com.ditchoom.buffer.codec", "EncodeContext")
internal val CODEC_CONTEXT_KEY = ClassName("com.ditchoom.buffer.codec", "CodecContext", "Key")
internal val UNIT = ClassName("kotlin", "Unit")

internal data class PrefixConfig(
    val byteCount: Int,
    val readExpr: String,
    val writePlaceholder: String,
    val writeExpr: (String) -> String,
)

private val FIXED_PREFIX_BYTE =
    PrefixConfig(1, "buffer.readByte().toInt() and 0xFF", "buffer.writeByte(0.toByte())") {
        "buffer.writeByte($it.toByte())"
    }

private val FIXED_PREFIX_SHORT =
    PrefixConfig(2, "buffer.readUnsignedShort().toInt()", "buffer.writeShort(0.toShort())") {
        "buffer.writeShort($it.toShort())"
    }

private val FIXED_PREFIX_INT =
    PrefixConfig(4, "buffer.readInt()", "buffer.writeInt(0)") { "buffer.writeInt($it)" }

/** Per-kind fixed-width prefix configuration. Returns null for [LengthPrefixKind.Varint]
 * because Varint requires the reserve-encode-shift helper rather than a fixed config. */
internal fun fixedPrefixConfig(kind: LengthPrefixKind): PrefixConfig? =
    when (kind) {
        is LengthPrefixKind.Byte -> FIXED_PREFIX_BYTE
        is LengthPrefixKind.Short -> FIXED_PREFIX_SHORT
        is LengthPrefixKind.Int -> FIXED_PREFIX_INT
        is LengthPrefixKind.Varint -> null
    }

/** Strict accessor for fixed-width prefix configuration. Throws on [LengthPrefixKind.Varint]
 * to fail fast if a caller forgets to handle the variable-width path. */
internal fun fixedPrefixConfigOrError(kind: LengthPrefixKind): PrefixConfig =
    fixedPrefixConfig(kind) ?: error(
        "fixedPrefixConfig called with $kind. Varint requires emitVarintLengthPrefixedWrite " +
            "for the encode side and VARINT_READ_EXPR for the decode side.",
    )

/** Read expression for a `LengthPrefix.Varint`-prefixed length value. */
internal const val VARINT_READ_EXPR: String = "buffer.readVariableByteInteger()"

/** Read expression for any [LengthPrefixKind] — uniform regardless of fixed vs variable width. */
internal fun readLengthExpr(kind: LengthPrefixKind): String =
    when (kind) {
        is LengthPrefixKind.Varint -> VARINT_READ_EXPR
        else -> fixedPrefixConfigOrError(kind).readExpr
    }

/**
 * Emits a write expression for a `LengthPrefix.Varint`-prefixed body via the runtime
 * helper [com.ditchoom.buffer.writeVariableByteIntegerLengthPrefixed]. The helper does
 * the reserve-encode-measure-shift dance with zero allocation on the hot path.
 *
 * The lambda parameter is named `buffer` so [encodeBody] expressions can use the same
 * `buffer.foo(...)` shape as encode code emitted at any other call site — the inner
 * `buffer` shadows the outer one and points to the same underlying buffer.
 */
internal fun emitVarintLengthPrefixedWrite(
    kind: LengthPrefixKind.Varint,
    fieldName: String,
    encodeBody: String,
): String =
    "buffer.writeVariableByteIntegerLengthPrefixed(maxBytes = ${kind.maxBytes}, fieldName = \"$fieldName\") " +
        "{ buffer -> $encodeBody }"

/**
 * Emits a Varint-length-prefixed write where the body size is known up front via
 * [bodySizeExpr]. Skips the scratch-buffer dance of [emitVarintLengthPrefixedWrite]:
 * computes the size, validates the maxBytes cap, writes the VBI prefix, then
 * encodes the body directly into the host buffer.
 *
 * Returns a `run { ... }` block as a single expression so call sites can keep
 * using `addStatement("%L", ...)`. The `_l_<fieldName>` local name is suffixed
 * with the field name to avoid collisions when multiple inline-varint blocks
 * appear in the same enclosing scope.
 *
 * The `require` check preserves the maxBytes cap behavior of the scratch-buffer
 * helper (e.g. MQTT v5 property-length sections cap at 4-byte VBIs; tighter caps
 * apply when a protocol restricts the range further). Without this check, a body
 * exceeding the cap would silently encode as a wider VBI on the wire.
 */
internal fun emitInlineVarintLengthPrefixed(
    fieldName: String,
    bodySizeExpr: String,
    encodeBody: String,
    maxBytes: Int = 4,
): String {
    val suffix = if (fieldName.isEmpty()) "" else "_$fieldName"
    val cap = if (maxBytes == 0) 4 else maxBytes
    val capCheck =
        if (cap < 4) {
            "require(_l$suffix in 0..com.ditchoom.buffer.variableByteMax($cap)) { " +
                "\"field '$fieldName' encoded length \$_l$suffix exceeds maxBytes=$cap " +
                "(max value \${com.ditchoom.buffer.variableByteMax($cap)})\" }; "
        } else {
            ""
        }
    return "run { val _l$suffix = $bodySizeExpr; ${capCheck}buffer.writeVariableByteInteger(_l$suffix); $encodeBody }"
}

internal fun addPayloadRawRead(
    code: CodeBlock.Builder,
    field: FieldInfo,
) {
    val strategy = field.strategy as FieldReadStrategy.PayloadField
    val rawVar = "_raw_${field.name}"
    val condition = field.condition

    if (condition is FieldCondition.WhenTrue) {
        code.beginControlFlow("val %L: %T? = if (%L)", rawVar, READ_BUFFER, condition.expression)
        addPayloadRawReadBody(code, strategy)
        code.nextControlFlow("else")
        code.addStatement("null")
        code.endControlFlow()
    } else if (condition is FieldCondition.WhenRemaining) {
        code.beginControlFlow("val %L: %T? = if (buffer.remaining() >= %L)", rawVar, READ_BUFFER, condition.minBytes)
        addPayloadRawReadBody(code, strategy)
        code.nextControlFlow("else")
        code.addStatement("null")
        code.endControlFlow()
    } else if (field.isNullable) {
        code.beginControlFlow("val %L: %T? = run", rawVar, READ_BUFFER)
        addPayloadRawReadBody(code, strategy)
        code.endControlFlow()
    } else {
        code.beginControlFlow("val %L: %T = run", rawVar, READ_BUFFER)
        addPayloadRawReadBody(code, strategy)
        code.endControlFlow()
    }
}

internal fun addPayloadRawReadBody(
    code: CodeBlock.Builder,
    strategy: FieldReadStrategy.PayloadField,
) {
    when (val lk = strategy.lengthKind) {
        is LengthKind.Prefixed -> {
            code.addStatement("val _len = %L", readLengthExpr(lk.kind))
            code.addStatement("buffer.readBytes(_len)")
        }
        is LengthKind.Remaining -> {
            if (lk.trailingBytes > 0) {
                code.addStatement("buffer.readBytes(buffer.remaining() - %L)", lk.trailingBytes)
            } else {
                code.addStatement("buffer.readBytes(buffer.remaining())")
            }
        }
        is LengthKind.FromField -> {
            code.addStatement("buffer.readBytes(%L.toInt())", lk.field)
        }
    }
}

internal fun addPayloadWrite(
    code: CodeBlock.Builder,
    field: FieldInfo,
) {
    val strategy = field.strategy as FieldReadStrategy.PayloadField
    val condition = field.condition

    if (condition is FieldCondition.WhenTrue) {
        val condExpr = condition.expression.replace(Regex("^([^.]+)"), "value.$1")
        code.beginControlFlow("if (%L)", condExpr)
        addPayloadEncodeBody(code, strategy, field)
        code.endControlFlow()
    } else if (condition is FieldCondition.WhenRemaining) {
        // Individual null check — cascading handled by CodecGenerator
        addPayloadEncodeBody(code, strategy, field)
    } else {
        addPayloadEncodeBody(code, strategy, field)
    }
}

/**
 * Appends `_size += <expr>` for a payload [field], using the caller-supplied
 * `size${capitalizeFirst(field.name)}` lambda to size the opaque payload value.
 * Mirrors [addPayloadWrite]'s conditional handling.
 */
internal fun addPayloadFieldWireSize(
    code: CodeBlock.Builder,
    field: FieldInfo,
) {
    val strategy = field.strategy as FieldReadStrategy.PayloadField
    val condition = field.condition
    val valueExpr = if (field.isNullable && condition != null) "value.${field.name}!!" else "value.${field.name}"
    val sizeFn = "size${capitalizeFirst(field.name)}"
    val payloadSizeExpr = payloadFieldWireSizeExpr(strategy, "$sizeFn($valueExpr)")
    if (condition is FieldCondition.WhenTrue) {
        val condExpr = condition.expression.replace(Regex("^([^.]+)"), "value.$1")
        code.beginControlFlow("if (%L)", condExpr)
        code.addStatement("_size += %L", payloadSizeExpr)
        code.endControlFlow()
    } else if (condition is FieldCondition.WhenRemaining) {
        // Cascading null check is the caller's job; we just emit the per-field size add.
        code.addStatement("_size += %L", payloadSizeExpr)
    } else {
        code.addStatement("_size += %L", payloadSizeExpr)
    }
}

/**
 * Returns an Int expression for a payload field's wire size, given an expression
 * [bodySizeExpr] that evaluates to the opaque payload's byte count. Mirrors the
 * length-prefix branches in [addPayloadEncodeBody] so wireSize and encode stay
 * byte-perfect.
 */
private fun payloadFieldWireSizeExpr(
    strategy: FieldReadStrategy.PayloadField,
    bodySizeExpr: String,
): String =
    when (val lk = strategy.lengthKind) {
        is LengthKind.Prefixed ->
            when (val kind = lk.kind) {
                is LengthPrefixKind.Varint ->
                    "run { val _l = $bodySizeExpr; com.ditchoom.buffer.variableByteSizeInt(_l) + _l }"
                else -> "(${kind.maxWireBytes} + $bodySizeExpr)"
            }
        is LengthKind.Remaining -> bodySizeExpr
        is LengthKind.FromField -> bodySizeExpr
    }

internal fun addPayloadEncodeBody(
    code: CodeBlock.Builder,
    strategy: FieldReadStrategy.PayloadField,
    field: FieldInfo,
) {
    val valueExpr = if (field.isNullable && field.condition != null) "value.${field.name}!!" else "value.${field.name}"
    val encodeFn = "encode${capitalizeFirst(field.name)}"
    val sizeFn = "size${capitalizeFirst(field.name)}"
    val suffix = "_${field.name}"

    when (val lk = strategy.lengthKind) {
        is LengthKind.Prefixed ->
            when (val kind = lk.kind) {
                is LengthPrefixKind.Varint -> {
                    code.addStatement("val _l$suffix = %L($valueExpr)", sizeFn)
                    if (kind.maxBytes < 4) {
                        code.addStatement(
                            "require(_l$suffix in 0..com.ditchoom.buffer.variableByteMax(%L)) " +
                                "{ %P }",
                            kind.maxBytes,
                            "field '${field.name}' encoded length \$_l$suffix exceeds " +
                                "maxBytes=${kind.maxBytes} (max value " +
                                "\${com.ditchoom.buffer.variableByteMax(${kind.maxBytes})})",
                        )
                    }
                    code.addStatement("buffer.writeVariableByteInteger(_l$suffix)")
                    code.addStatement("%L(buffer, %L)", encodeFn, valueExpr)
                }
                else -> {
                    val cfg = fixedPrefixConfigOrError(kind)
                    code.addStatement("val _pos%L = buffer.position()", suffix)
                    code.addStatement("%L", cfg.writePlaceholder)
                    code.addStatement("%L(buffer, %L)", encodeFn, valueExpr)
                    code.addStatement("val _end%L = buffer.position()", suffix)
                    code.addStatement("val _len%L = _end%L - _pos%L - %L", suffix, suffix, suffix, cfg.byteCount)
                    code.addStatement("buffer.position(_pos%L)", suffix)
                    code.addStatement("%L", cfg.writeExpr("_len$suffix"))
                    code.addStatement("buffer.position(_end%L)", suffix)
                }
            }
        is LengthKind.Remaining -> {
            code.addStatement("%L(buffer, %L)", encodeFn, valueExpr)
        }
        is LengthKind.FromField -> {
            code.addStatement("%L(buffer, %L)", encodeFn, valueExpr)
        }
    }
}
