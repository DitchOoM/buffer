package com.ditchoom.buffer.codec.processor

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock

internal val READ_BUFFER = ClassName("com.ditchoom.buffer", "ReadBuffer")
internal val WRITE_BUFFER = ClassName("com.ditchoom.buffer", "WriteBuffer")
internal val CODEC = ClassName("com.ditchoom.buffer.codec", "Codec")
internal val DECODE_CONTEXT = ClassName("com.ditchoom.buffer.codec", "DecodeContext")
internal val ENCODE_CONTEXT = ClassName("com.ditchoom.buffer.codec", "EncodeContext")
internal val CODEC_CONTEXT_KEY = ClassName("com.ditchoom.buffer.codec", "CodecContext", "Key")
internal val SIZE_ESTIMATE = ClassName("com.ditchoom.buffer.codec", "SizeEstimate")
internal val PAYLOAD_READER = ClassName("com.ditchoom.buffer.codec.payload", "PayloadReader")
internal val READ_BUFFER_PAYLOAD_READER = ClassName("com.ditchoom.buffer.codec.payload", "ReadBufferPayloadReader")
internal val UNIT = ClassName("kotlin", "Unit")

internal data class PrefixConfig(
    val byteCount: Int,
    val readExpr: String,
    val writePlaceholder: String,
    val writeExpr: (String) -> String,
)

internal val prefixConfigs =
    mapOf(
        "Byte" to
            PrefixConfig(
                1,
                "buffer.readByte().toInt() and 0xFF",
                "buffer.writeByte(0.toByte())",
            ) { "buffer.writeByte($it.toByte())" },
        "Short" to
            PrefixConfig(
                2,
                "buffer.readUnsignedShort().toInt()",
                "buffer.writeShort(0.toShort())",
            ) { "buffer.writeShort($it.toShort())" },
        "Int" to
            PrefixConfig(
                4,
                "buffer.readInt()",
                "buffer.writeInt(0)",
            ) { "buffer.writeInt($it)" },
    )

internal fun prefixConfig(prefix: String): PrefixConfig = prefixConfigs[prefix] ?: error("Unknown LengthPrefix: $prefix")

internal fun addPayloadRawRead(
    code: CodeBlock.Builder,
    field: FieldInfo,
) {
    val strategy = field.strategy as FieldReadStrategy.PayloadField
    val rawVar = "_raw_${field.name}"
    val condition = field.condition

    if (condition != null) {
        val condExpr = (condition as FieldCondition.WhenTrue).expression
        code.beginControlFlow("val %L: %T? = if (%L)", rawVar, READ_BUFFER, condExpr)
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
            val lenExpr = prefixConfig(lk.prefix).readExpr
            code.addStatement("val _len = %L", lenExpr)
            code.addStatement("buffer.readBytes(_len)")
        }
        is LengthKind.Remaining -> {
            code.addStatement("buffer.readBytes(buffer.remaining())")
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

    if (condition != null) {
        val condExpr = (condition as FieldCondition.WhenTrue).expression.replace(Regex("^([^.]+)"), "value.$1")
        code.beginControlFlow("if (%L)", condExpr)
        addPayloadEncodeBody(code, strategy, field)
        code.endControlFlow()
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
        is LengthKind.Prefixed -> {
            val cfg = prefixConfig(lk.prefix)
            code.addStatement("val _pos%L = buffer.position()", suffix)
            code.addStatement("%L", cfg.writePlaceholder)
            code.addStatement("%L(buffer, %L)", encodeFn, valueExpr)
            code.addStatement("val _end%L = buffer.position()", suffix)
            code.addStatement("val _len%L = _end%L - _pos%L - %L", suffix, suffix, suffix, cfg.byteCount)
            code.addStatement("buffer.position(_pos%L)", suffix)
            code.addStatement("%L", cfg.writeExpr("_len$suffix"))
            code.addStatement("buffer.position(_end%L)", suffix)
        }
        is LengthKind.Remaining -> {
            code.addStatement("%L(buffer, %L)", encodeFn, valueExpr)
        }
        is LengthKind.FromField -> {
            code.addStatement("%L(buffer, %L)", encodeFn, valueExpr)
        }
    }
}
