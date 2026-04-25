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
internal val PAYLOAD_READER = ClassName("com.ditchoom.buffer.codec.payload", "PayloadReader")
internal val READ_BUFFER_PAYLOAD_READER = ClassName("com.ditchoom.buffer.codec.payload", "ReadBufferPayloadReader")
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

internal fun addPayloadEncodeBody(
    code: CodeBlock.Builder,
    strategy: FieldReadStrategy.PayloadField,
    field: FieldInfo,
) {
    val valueExpr = if (field.isNullable && field.condition != null) "value.${field.name}!!" else "value.${field.name}"
    val encodeFn = "encode${capitalizeFirst(field.name)}"
    val suffix = "_${field.name}"

    when (val lk = strategy.lengthKind) {
        is LengthKind.Prefixed ->
            when (val kind = lk.kind) {
                is LengthPrefixKind.Varint ->
                    code.addStatement(
                        "%L",
                        emitVarintLengthPrefixedWrite(
                            kind = kind,
                            fieldName = field.name,
                            encodeBody = "$encodeFn(buffer, $valueExpr)",
                        ),
                    )
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
