package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier

internal fun KSAnnotation.qualifiedName(): String? =
    annotationType
        .resolve()
        .declaration
        .qualifiedName
        ?.asString()

data class FieldInfo(
    val name: String,
    val typeName: String,
    val strategy: FieldReadStrategy,
    val isNullable: Boolean,
    val condition: FieldCondition?,
    val parameter: KSValueParameter?,
    val hasDefault: Boolean = true,
)

sealed class FieldCondition {
    data class WhenTrue(
        val expression: String,
    ) : FieldCondition()
}

sealed class LengthKind {
    data class Prefixed(
        val prefix: String,
    ) : LengthKind()

    data object Remaining : LengthKind()

    data class FromField(
        val field: String,
    ) : LengthKind()
}

enum class Primitive(
    val typeName: String,
    val defaultWireBytes: Int,
    val signed: Boolean,
    val readExpr: String,
    val writeExpr: (String) -> String,
    val isNumeric: Boolean = true,
) {
    BYTE("kotlin.Byte", 1, true, "buffer.readByte()", { v -> "buffer.writeByte($v)" }),
    UBYTE("kotlin.UByte", 1, false, "buffer.readUnsignedByte()", { v -> "buffer.writeUByte($v)" }),
    SHORT("kotlin.Short", 2, true, "buffer.readShort()", { v -> "buffer.writeShort($v)" }),
    USHORT("kotlin.UShort", 2, false, "buffer.readUnsignedShort()", { v -> "buffer.writeUShort($v)" }),
    INT("kotlin.Int", 4, true, "buffer.readInt()", { v -> "buffer.writeInt($v)" }),
    UINT("kotlin.UInt", 4, false, "buffer.readUnsignedInt()", { v -> "buffer.writeUInt($v)" }),
    LONG("kotlin.Long", 8, true, "buffer.readLong()", { v -> "buffer.writeLong($v)" }),
    ULONG("kotlin.ULong", 8, false, "buffer.readUnsignedLong()", { v -> "buffer.writeULong($v)" }),
    FLOAT("kotlin.Float", 4, true, "buffer.readFloat()", { v -> "buffer.writeFloat($v)" }, isNumeric = false),
    DOUBLE("kotlin.Double", 8, true, "buffer.readDouble()", { v -> "buffer.writeDouble($v)" }, isNumeric = false),
    BOOLEAN(
        "kotlin.Boolean",
        1,
        false,
        "buffer.readByte() != 0.toByte()",
        { v -> "buffer.writeByte(if ($v) 1.toByte() else 0.toByte())" },
        isNumeric = false,
    ),
    ;

    companion object {
        private val BY_TYPE_NAME = entries.associateBy { it.typeName }

        fun fromTypeName(name: String): Primitive? = BY_TYPE_NAME[name]
    }
}

sealed class FieldReadStrategy {
    data class PrimitiveField(
        val primitive: Primitive,
        val wireBytes: Int = primitive.defaultWireBytes,
    ) : FieldReadStrategy()

    data class LengthPrefixedStringField(
        val prefix: String,
    ) : FieldReadStrategy()

    data object RemainingBytesStringField : FieldReadStrategy()

    data class LengthFromStringField(
        val field: String,
    ) : FieldReadStrategy()

    data class ValueClassField(
        val innerStrategy: FieldReadStrategy,
        val wrapperType: String,
        val innerPropertyName: String = "value",
    ) : FieldReadStrategy()

    data class NestedMessageField(
        val codecName: String,
    ) : FieldReadStrategy()

    data class PayloadField(
        val lengthKind: LengthKind,
        val typeParamName: String,
    ) : FieldReadStrategy()

    val fixedSize: Int
        get() =
            when (this) {
                is PrimitiveField -> wireBytes
                is ValueClassField -> innerStrategy.fixedSize
                else -> -1
            }

    companion object {
        val ByteField = PrimitiveField(Primitive.BYTE)
        val UByteField = PrimitiveField(Primitive.UBYTE)
        val ShortField = PrimitiveField(Primitive.SHORT)
        val UShortField = PrimitiveField(Primitive.USHORT)
        val IntField = PrimitiveField(Primitive.INT)
        val UIntField = PrimitiveField(Primitive.UINT)
        val LongField = PrimitiveField(Primitive.LONG)
        val ULongField = PrimitiveField(Primitive.ULONG)
        val FloatField = PrimitiveField(Primitive.FLOAT)
        val DoubleField = PrimitiveField(Primitive.DOUBLE)
        val BooleanField = PrimitiveField(Primitive.BOOLEAN)
    }
}

class FieldAnalyzer(
    private val logger: KSPLogger,
) {
    private val forbiddenTypes =
        setOf(
            "com.ditchoom.buffer.ReadBuffer",
            "com.ditchoom.buffer.WriteBuffer",
            "com.ditchoom.buffer.ReadWriteBuffer",
            "com.ditchoom.buffer.PlatformBuffer",
            "kotlin.ByteArray",
        )

    private var payloadTypeParamNames: Set<String> = emptySet()

    fun analyze(classDeclaration: KSClassDeclaration): List<FieldInfo>? {
        val constructor =
            classDeclaration.primaryConstructor ?: run {
                logger.error("@ProtocolMessage class must have a primary constructor", classDeclaration)
                return null
            }

        // Extract type parameters annotated with @Payload
        payloadTypeParamNames =
            classDeclaration.typeParameters
                .filter { tp ->
                    tp.annotations.any {
                        it.qualifiedName() == "com.ditchoom.buffer.codec.annotations.Payload"
                    }
                }.map { it.name.asString() }
                .toSet()

        val fields = mutableListOf<FieldInfo>()
        var hasError = false

        for (param in constructor.parameters) {
            val name = param.name?.asString() ?: continue
            val type = param.type.resolve()
            val typeName = type.declaration.qualifiedName?.asString() ?: type.toString()

            // Check forbidden types
            if (typeName in forbiddenTypes) {
                logger.error(
                    "Field '$name' has forbidden type '$typeName'. " +
                        "Protocol messages cannot contain buffer or byte array fields.",
                    param,
                )
                hasError = true
                continue
            }

            // Check for condition annotations
            val condition = extractCondition(param)

            // Determine read strategy
            val strategy = resolveStrategy(param, typeName, name, type.isMarkedNullable)
            if (strategy == null) {
                hasError = true
                continue
            }

            fields.add(
                FieldInfo(
                    name = name,
                    typeName = typeName,
                    strategy = strategy,
                    isNullable = type.isMarkedNullable,
                    condition = condition,
                    parameter = param,
                    hasDefault = param.hasDefault,
                ),
            )
        }

        if (hasError) return null

        // Validate conditional fields
        val validator = ConditionalValidator(logger)
        if (!validator.validate(fields)) return null

        // Validate @RemainingBytes is last non-conditional field
        val nonConditionalFields = fields.filter { it.condition == null }
        val remainingBytesFields =
            fields.filter {
                it.strategy is FieldReadStrategy.RemainingBytesStringField ||
                    (it.strategy is FieldReadStrategy.PayloadField && it.strategy.lengthKind is LengthKind.Remaining)
            }
        if (remainingBytesFields.size > 1) {
            val names = remainingBytesFields.joinToString(", ") { "'${it.name}'" }
            logger.error(
                "Only one field can use @RemainingBytes, but found ${remainingBytesFields.size}: $names. " +
                    "The codec reads all remaining bytes for this field, so only the last field can use it.",
                classDeclaration,
            )
            return null
        }
        if (remainingBytesFields.size == 1) {
            val rbField = remainingBytesFields.first()
            if (rbField != nonConditionalFields.lastOrNull()) {
                logger.error("@RemainingBytes can only be used on the last non-conditional field", rbField.parameter)
                return null
            }
        }

        // Validate @LengthFrom references
        for (field in fields) {
            val strategy = field.strategy
            val refFieldName =
                when (strategy) {
                    is FieldReadStrategy.LengthFromStringField -> strategy.field
                    is FieldReadStrategy.PayloadField -> {
                        val lk = strategy.lengthKind
                        if (lk is LengthKind.FromField) lk.field else null
                    }
                    else -> null
                }
            if (refFieldName != null) {
                val referencedField = fields.find { it.name == refFieldName }
                if (referencedField == null) {
                    logger.error("@LengthFrom references non-existent field '$refFieldName'", field.parameter)
                    return null
                }
                val fieldIndex = fields.indexOf(field)
                val refIndex = fields.indexOf(referencedField)
                if (refIndex >= fieldIndex) {
                    logger.error("@LengthFrom field '$refFieldName' must come before '${field.name}'", field.parameter)
                    return null
                }
                val refStrategy = referencedField.strategy
                val isNumeric =
                    isNumericStrategy(refStrategy) ||
                        (refStrategy is FieldReadStrategy.ValueClassField && isNumericStrategy(refStrategy.innerStrategy))
                if (!isNumeric) {
                    logger.error(
                        "@LengthFrom field '$refFieldName' must be a numeric type, " +
                            "but has type '${referencedField.typeName}'",
                        field.parameter,
                    )
                    return null
                }
            }
        }

        return fields
    }

    private fun extractEnumName(
        value: Any?,
        fieldName: String,
    ): String? {
        val validNames = setOf("Byte", "Short", "Int")
        if (value == null) return "Short"
        // KSP represents enum values as KSType; extract the simple name from the declaration
        if (value is KSType) {
            val name = value.declaration.simpleName.asString()
            // KSErrorType returns "<ERROR TYPE>" or similar
            if (!name.startsWith("<")) {
                if (name in validNames) return name
                logger.error(
                    "Unrecognized LengthPrefix value '$name' on field '$fieldName'. " +
                        "Valid values: LengthPrefix.Byte (1-byte prefix, max 255), " +
                        "LengthPrefix.Short (2-byte prefix, max 65535, default), " +
                        "LengthPrefix.Int (4-byte prefix, max ~2 billion).",
                    null,
                )
                return null
            }
        }
        // Fallback: try string representation
        val str = value.toString()
        val afterDot = str.substringAfterLast(".")
        if (afterDot in validNames) return afterDot
        logger.error(
            "Unrecognized LengthPrefix value '$afterDot' on field '$fieldName'. " +
                "Valid values: LengthPrefix.Byte (1-byte prefix, max 255), " +
                "LengthPrefix.Short (2-byte prefix, max 65535, default), " +
                "LengthPrefix.Int (4-byte prefix, max ~2 billion).",
            null,
        )
        return null
    }

    private fun isNumericStrategy(strategy: FieldReadStrategy): Boolean =
        strategy is FieldReadStrategy.PrimitiveField && strategy.primitive.isNumeric

    private fun extractCondition(param: KSValueParameter): FieldCondition? {
        for (annotation in param.annotations) {
            val annotName = annotation.qualifiedName()
            if (annotName == "com.ditchoom.buffer.codec.annotations.WhenTrue") {
                val expr = annotation.arguments.first().value as String
                return FieldCondition.WhenTrue(expr)
            }
        }
        return null
    }

    private fun resolveStrategy(
        param: KSValueParameter,
        typeName: String,
        fieldName: String,
        isNullable: Boolean,
    ): FieldReadStrategy? {
        // Check if this field's type is a @Payload type parameter
        val typeDecl = param.type.resolve().declaration
        if (typeDecl is KSTypeParameter && typeDecl.name.asString() in payloadTypeParamNames) {
            return resolvePayloadLengthKind(param, fieldName, typeDecl.name.asString())
        }

        // String field annotations
        val annotations = param.annotations.toList()
        if (typeName == "kotlin.String") {
            return resolveStringStrategy(param, annotations, fieldName)
        }

        // Primitive types
        val primitive = Primitive.fromTypeName(typeName)
        if (primitive != null) {
            val wireBytes = extractWireBytes(param, primitive)
            return FieldReadStrategy.PrimitiveField(primitive, wireBytes ?: primitive.defaultWireBytes)
        }

        return resolveComplexType(param, typeName, fieldName)
    }

    private fun resolvePayloadLengthKind(
        param: KSValueParameter,
        fieldName: String,
        typeParamName: String,
    ): FieldReadStrategy? {
        val lk =
            resolveLengthKind(param, param.annotations.toList(), fieldName, "Payload")
                ?: return null
        return FieldReadStrategy.PayloadField(lk, typeParamName)
    }

    private fun resolveStringStrategy(
        param: KSValueParameter,
        annotations: List<KSAnnotation>,
        fieldName: String,
    ): FieldReadStrategy? {
        val lk =
            resolveLengthKind(param, annotations, fieldName, "String")
                ?: return null
        return when (lk) {
            is LengthKind.Prefixed -> FieldReadStrategy.LengthPrefixedStringField(lk.prefix)
            is LengthKind.Remaining -> FieldReadStrategy.RemainingBytesStringField
            is LengthKind.FromField -> FieldReadStrategy.LengthFromStringField(lk.field)
        }
    }

    private fun resolveComplexType(
        param: KSValueParameter,
        typeName: String,
        fieldName: String,
    ): FieldReadStrategy? {
        val typeDecl =
            param.type.resolve().declaration as? KSClassDeclaration ?: run {
                logger.error(
                    "Unsupported type '$typeName' for field '$fieldName'. " +
                        "Supported types: primitives (Byte, Short, Int, Long, Float, Double, Boolean and unsigned variants), " +
                        "String (with @LengthPrefixed, @RemainingBytes, or @LengthFrom), " +
                        "value classes wrapping a primitive, or nested @ProtocolMessage classes.",
                    param,
                )
                return null
            }

        // Check if it's a value class
        if (Modifier.VALUE in typeDecl.modifiers || Modifier.INLINE in typeDecl.modifiers) {
            val innerParam =
                typeDecl.primaryConstructor?.parameters?.firstOrNull() ?: run {
                    logger.error("Value class '$typeName' must have a primary constructor parameter", param)
                    return null
                }
            val innerTypeName =
                innerParam.type
                    .resolve()
                    .declaration.qualifiedName
                    ?.asString() ?: ""
            val innerPropertyName = innerParam.name?.asString() ?: "value"
            val innerPrimitive =
                Primitive.fromTypeName(innerTypeName) ?: run {
                    logger.error(
                        "Unsupported inner type '$innerTypeName' for value class '$typeName'. " +
                            "Value classes must wrap a primitive type: " +
                            "Byte, UByte, Short, UShort, Int, UInt, Long, ULong, Float, Double, or Boolean.",
                        param,
                    )
                    return null
                }
            val wireBytes = extractWireBytes(param, innerPrimitive)
            val innerStrategy = FieldReadStrategy.PrimitiveField(innerPrimitive, wireBytes ?: innerPrimitive.defaultWireBytes)
            return FieldReadStrategy.ValueClassField(innerStrategy, typeName, innerPropertyName)
        }

        // Check if it has @ProtocolMessage (nested message)
        val hasProtocolMessage =
            typeDecl.annotations.any {
                it.qualifiedName() == "com.ditchoom.buffer.codec.annotations.ProtocolMessage"
            }
        if (hasProtocolMessage) {
            val codecName = "${typeDecl.simpleName.asString()}Codec"
            return FieldReadStrategy.NestedMessageField(codecName)
        }

        logger.error(
            "Unsupported type '$typeName' for field '$fieldName'. " +
                "Supported types: primitives (Byte, Short, Int, Long, Float, Double, Boolean and unsigned variants), " +
                "String (with @LengthPrefixed, @RemainingBytes, or @LengthFrom), " +
                "value classes wrapping a primitive, or nested @ProtocolMessage classes. " +
                "If '$typeName' is a @ProtocolMessage, make sure it is annotated.",
            param,
        )
        return null
    }

    private fun extractWireBytes(
        param: KSValueParameter,
        primitive: Primitive,
    ): Int? {
        val ann =
            param.annotations.toList().find {
                it.qualifiedName() == "com.ditchoom.buffer.codec.annotations.WireBytes"
            } ?: return null
        val wireBytes = ann.arguments.first().value as Int
        if (wireBytes < 1 || wireBytes > 8) {
            logger.error("@WireBytes value must be between 1 and 8, got $wireBytes", param)
            return null
        }
        if (!primitive.isNumeric) {
            logger.error("@WireBytes cannot be used on ${primitive.typeName} (only numeric types)", param)
            return null
        }
        if (wireBytes > primitive.defaultWireBytes) {
            logger.error(
                "@WireBytes($wireBytes) exceeds ${primitive.typeName} size of ${primitive.defaultWireBytes} bytes",
                param,
            )
            return null
        }
        return wireBytes
    }

    private fun resolveLengthKind(
        param: KSValueParameter,
        annotations: List<KSAnnotation>,
        fieldName: String,
        fieldKind: String,
    ): LengthKind? {
        val lengthPrefixed =
            annotations.find {
                it.qualifiedName() == "com.ditchoom.buffer.codec.annotations.LengthPrefixed"
            }
        val remainingBytes =
            annotations.find {
                it.qualifiedName() == "com.ditchoom.buffer.codec.annotations.RemainingBytes"
            }
        val lengthFrom =
            annotations.find {
                it.qualifiedName() == "com.ditchoom.buffer.codec.annotations.LengthFrom"
            }

        val present =
            listOfNotNull(
                if (lengthPrefixed != null) "@LengthPrefixed" else null,
                if (remainingBytes != null) "@RemainingBytes" else null,
                if (lengthFrom != null) "@LengthFrom" else null,
            )

        if (present.size > 1) {
            logger.error(
                "Field '$fieldName' has conflicting length annotations: ${present.joinToString(", ")}. " +
                    "Each annotation specifies a different length strategy: " +
                    "@LengthPrefixed reads a prefix then that many bytes, " +
                    "@RemainingBytes reads all bytes until end of buffer, " +
                    "@LengthFrom reads the length from another field. " +
                    "These are mutually exclusive — use exactly one.",
                param,
            )
            return null
        }

        if (lengthPrefixed != null) {
            val prefixArg = lengthPrefixed.arguments.find { it.name?.asString() == "prefix" }?.value
            val prefix = extractEnumName(prefixArg, fieldName) ?: return null
            return LengthKind.Prefixed(prefix)
        }

        if (remainingBytes != null) {
            return LengthKind.Remaining
        }

        if (lengthFrom != null) {
            val field = lengthFrom.arguments.find { it.name?.asString() == "field" }?.value as? String ?: ""
            return LengthKind.FromField(field)
        }

        logger.error(
            "$fieldKind field '$fieldName' requires a length annotation. " +
                "Use @LengthPrefixed, @RemainingBytes, or @LengthFrom.",
            param,
        )
        return null
    }
}
