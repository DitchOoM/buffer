package com.ditchoom.buffer.codec.processor

import com.ditchoom.buffer.codec.processor.spi.CodecFieldProvider
import com.ditchoom.buffer.codec.processor.spi.CustomFieldDescriptor
import com.ditchoom.buffer.codec.processor.spi.FieldContext
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

    data class CollectionField(
        val lengthKind: LengthKind,
        val elementCodecName: String,
    ) : FieldReadStrategy()

    data class Custom(
        val descriptor: CustomFieldDescriptor,
    ) : FieldReadStrategy()

    val fixedSize: Int
        get() =
            when (this) {
                is PrimitiveField -> wireBytes
                is ValueClassField -> innerStrategy.fixedSize
                is Custom -> descriptor.fixedSize
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
    private val customProviders: Map<String, CodecFieldProvider> = emptyMap(),
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
                val shortType = typeName.substringAfterLast(".")
                logger.error(
                    "Field '$name' has type $shortType, which cannot be used in @ProtocolMessage classes. " +
                        "Use a primitive type (Int, Short, etc.), String with a length annotation, " +
                        "or a nested @ProtocolMessage class instead.",
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
                    (it.strategy is FieldReadStrategy.PayloadField && it.strategy.lengthKind is LengthKind.Remaining) ||
                    (it.strategy is FieldReadStrategy.CollectionField && it.strategy.lengthKind is LengthKind.Remaining)
            }
        if (remainingBytesFields.size > 1) {
            val names = remainingBytesFields.joinToString(", ") { "'${it.name}'" }
            logger.error(
                "Only one field can use @RemainingBytes, but found ${remainingBytesFields.size}: $names. " +
                    "@RemainingBytes consumes all bytes until end of buffer, " +
                    "so only one field (the last) can use it. " +
                    "Consider using @LengthPrefixed or @LengthFrom on the others.",
                classDeclaration,
            )
            return null
        }
        if (remainingBytesFields.size == 1) {
            val rbField = remainingBytesFields.first()
            if (rbField != nonConditionalFields.lastOrNull()) {
                val lastField = nonConditionalFields.lastOrNull()?.name ?: "the last field"
                logger.error(
                    "@RemainingBytes on '${rbField.name}' is invalid — it must be the last non-conditional field. " +
                        "Currently '$lastField' comes after it. " +
                        "Move '${rbField.name}' to the end, or use @LengthPrefixed instead.",
                    rbField.parameter,
                )
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
                    is FieldReadStrategy.CollectionField -> {
                        val lk = strategy.lengthKind
                        if (lk is LengthKind.FromField) lk.field else null
                    }
                    else -> null
                }
            if (refFieldName != null) {
                val referencedField = fields.find { it.name == refFieldName }
                if (referencedField == null) {
                    val available = fields.map { it.name }.joinToString(", ")
                    logger.error(
                        "@LengthFrom(\"$refFieldName\") on '${field.name}': no field named '$refFieldName' exists. " +
                            "Available fields: $available",
                        field.parameter,
                    )
                    return null
                }
                val fieldIndex = fields.indexOf(field)
                val refIndex = fields.indexOf(referencedField)
                if (refIndex >= fieldIndex) {
                    logger.error(
                        "@LengthFrom(\"$refFieldName\") on '${field.name}': " +
                            "field '$refFieldName' must be declared before '${field.name}' in the constructor. " +
                            "The codec reads fields in order, so the length must already be decoded.",
                        field.parameter,
                    )
                    return null
                }
                val refStrategy = referencedField.strategy
                val isNumeric =
                    isNumericStrategy(refStrategy) ||
                        (refStrategy is FieldReadStrategy.ValueClassField && isNumericStrategy(refStrategy.innerStrategy))
                if (!isNumeric) {
                    val shortType = referencedField.typeName.substringAfterLast(".")
                    logger.error(
                        "@LengthFrom(\"$refFieldName\") on '${field.name}': " +
                            "field '$refFieldName' is $shortType, but must be a numeric type " +
                            "(Byte, UByte, Short, UShort, Int, UInt, Long, or ULong) " +
                            "so the codec can use its value as a byte count.",
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

        // Check SPI-registered custom annotations
        val annotations = param.annotations.toList()
        for (annotation in annotations) {
            val fqn = annotation.qualifiedName() ?: continue
            val provider = customProviders[fqn]
            if (provider != null) {
                val args = annotation.arguments.associate { it.name!!.asString() to it.value }
                val ctx = FieldContext(fieldName, typeName, args)
                return try {
                    FieldReadStrategy.Custom(provider.describe(ctx))
                } catch (e: Exception) {
                    logger.error(
                        "SPI provider for @${fqn.substringAfterLast('.')} failed on field '$fieldName': ${e.message}",
                        param,
                    )
                    null
                }
            }
        }

        // String field annotations
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
        // Check for List<T> where T is a @ProtocolMessage
        if (typeName == "kotlin.collections.List") {
            return resolveCollectionType(param, fieldName)
        }

        val typeDecl =
            param.type.resolve().declaration as? KSClassDeclaration ?: run {
                val shortType = typeName.substringAfterLast(".")
                logger.error(
                    "Field '$fieldName' has unsupported type $shortType. @ProtocolMessage fields must be one of: " +
                        "a primitive (Byte, Short, Int, Long, Float, Double, Boolean, or unsigned variants), " +
                        "a String (annotated with @LengthPrefixed, @RemainingBytes, or @LengthFrom), " +
                        "a value class wrapping a primitive, a nested @ProtocolMessage class, " +
                        "or a List<T> where T is a @ProtocolMessage class.",
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
                    val shortInner = innerTypeName.substringAfterLast(".")
                    val shortWrapper = typeName.substringAfterLast(".")
                    logger.error(
                        "Value class $shortWrapper wraps $shortInner, which is not a supported primitive. " +
                            "The inner type must be one of: " +
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
            val codecName = typeDecl.codecName()
            return FieldReadStrategy.NestedMessageField(codecName)
        }

        val shortType = typeName.substringAfterLast(".")
        logger.error(
            "Field '$fieldName' has unsupported type $shortType. @ProtocolMessage fields must be one of: " +
                "a primitive (Byte, Short, Int, Long, Float, Double, Boolean, or unsigned variants), " +
                "a String (annotated with @LengthPrefixed, @RemainingBytes, or @LengthFrom), " +
                "a value class wrapping a primitive, a nested @ProtocolMessage class, " +
                "or a List<T> where T is a @ProtocolMessage class. " +
                "If $shortType is a protocol message, add @ProtocolMessage to its declaration.",
            param,
        )
        return null
    }

    private fun resolveCollectionType(
        param: KSValueParameter,
        fieldName: String,
    ): FieldReadStrategy? {
        val resolvedType = param.type.resolve()
        val typeArgs = resolvedType.arguments
        if (typeArgs.size != 1) {
            logger.error(
                "List field '$fieldName' must have exactly one type argument.",
                param,
            )
            return null
        }
        val elementType =
            typeArgs.first().type?.resolve() ?: run {
                logger.error(
                    "List field '$fieldName': cannot resolve element type.",
                    param,
                )
                return null
            }
        val elementDecl =
            elementType.declaration as? KSClassDeclaration ?: run {
                logger.error(
                    "List field '$fieldName': element type must be a @ProtocolMessage class.",
                    param,
                )
                return null
            }
        val elementTypeName = elementDecl.qualifiedName?.asString() ?: ""

        // Reject primitive wrappers, strings, collections, arrays
        val rejectedTypes =
            setOf(
                "kotlin.Byte",
                "kotlin.UByte",
                "kotlin.Short",
                "kotlin.UShort",
                "kotlin.Int",
                "kotlin.UInt",
                "kotlin.Long",
                "kotlin.ULong",
                "kotlin.Float",
                "kotlin.Double",
                "kotlin.Boolean",
                "kotlin.String",
                "kotlin.ByteArray",
                "kotlin.ShortArray",
                "kotlin.IntArray",
                "kotlin.LongArray",
                "kotlin.collections.List",
            )
        if (elementTypeName in rejectedTypes) {
            val shortType = elementTypeName.substringAfterLast(".")
            logger.error(
                "List<$shortType> on field '$fieldName' is not supported. " +
                    "List elements must be @ProtocolMessage classes. " +
                    "For primitive sequences, use @Payload with a buffer instead.",
                param,
            )
            return null
        }

        // Require @ProtocolMessage on element type
        val hasProtocolMessage =
            elementDecl.annotations.any {
                it.qualifiedName() == "com.ditchoom.buffer.codec.annotations.ProtocolMessage"
            }
        if (!hasProtocolMessage) {
            val shortType = elementTypeName.substringAfterLast(".")
            logger.error(
                "List<$shortType> on field '$fieldName': element type $shortType must be annotated with @ProtocolMessage. " +
                    "Add @ProtocolMessage to the $shortType class declaration.",
                param,
            )
            return null
        }

        // Require a length annotation
        val annotations = param.annotations.toList()
        val lk = resolveLengthKind(param, annotations, fieldName, "List") ?: return null

        val codecName = elementDecl.codecName()
        return FieldReadStrategy.CollectionField(lk, codecName)
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
            logger.error(
                "@WireBytes($wireBytes) is out of range. Value must be between 1 and 8.",
                param,
            )
            return null
        }
        if (!primitive.isNumeric) {
            val shortType = primitive.typeName.substringAfterLast(".")
            logger.error(
                "@WireBytes cannot be used on $shortType — only numeric types " +
                    "(Byte, Short, Int, Long, and unsigned variants) support custom wire sizes.",
                param,
            )
            return null
        }
        if (wireBytes > primitive.defaultWireBytes) {
            val shortType = primitive.typeName.substringAfterLast(".")
            logger.error(
                "@WireBytes($wireBytes) exceeds $shortType's native size of ${primitive.defaultWireBytes} byte(s). " +
                    "Use a larger type (e.g., Int for 4 bytes, Long for 8 bytes).",
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
            "$fieldKind field '$fieldName' requires a length annotation so the codec knows how many bytes to read. " +
                "Add one of: @LengthPrefixed (writes/reads a length prefix before the data), " +
                "@RemainingBytes (reads all remaining bytes — only valid on the last field), " +
                "or @LengthFrom(\"fieldName\") (reads length from a previously decoded field).",
            param,
        )
        return null
    }
}
