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

/**
 * Byte order override for a field. When non-null, the generated codec
 * emits inline reverseBytes() calls for this field's read/write.
 */
enum class WireOrderOverride {
    Big,
    Little,
}

data class FieldInfo(
    val name: String,
    val typeName: String,
    val strategy: FieldReadStrategy,
    val isNullable: Boolean,
    val condition: FieldCondition?,
    val parameter: KSValueParameter?,
    val hasDefault: Boolean = true,
    val byteOrderOverride: WireOrderOverride? = null,
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

    /**
     * Consume remaining bytes for this field, reserving [trailingBytes] at the end for fields
     * that follow. The processor sets [trailingBytes] automatically when fixed-size trailing
     * fields follow a @RemainingBytes field; users don't specify it directly.
     */
    data class Remaining(
        val trailingBytes: Int = 0,
    ) : LengthKind()

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
    /** Read expression with byte-order swap applied. Null means swap not applicable (1-byte types). */
    val swappedReadExpr: String? = null,
    /** Write expression with byte-order swap applied. Null means swap not applicable. */
    val swappedWriteExpr: ((String) -> String)? = null,
) {
    BYTE("kotlin.Byte", 1, true, "buffer.readByte()", { v -> "buffer.writeByte($v)" }),
    UBYTE("kotlin.UByte", 1, false, "buffer.readUnsignedByte()", { v -> "buffer.writeUByte($v)" }),
    SHORT(
        "kotlin.Short",
        2,
        true,
        "buffer.readShort()",
        { v -> "buffer.writeShort($v)" },
        swappedReadExpr = "buffer.readShort().reverseBytes()",
        swappedWriteExpr = { v -> "buffer.writeShort($v.reverseBytes())" },
    ),
    USHORT(
        "kotlin.UShort",
        2,
        false,
        "buffer.readUnsignedShort()",
        { v -> "buffer.writeUShort($v)" },
        swappedReadExpr = "buffer.readShort().reverseBytes().toUShort()",
        swappedWriteExpr = { v -> "buffer.writeShort($v.toShort().reverseBytes())" },
    ),
    INT(
        "kotlin.Int",
        4,
        true,
        "buffer.readInt()",
        { v -> "buffer.writeInt($v)" },
        swappedReadExpr = "buffer.readInt().reverseBytes()",
        swappedWriteExpr = { v -> "buffer.writeInt($v.reverseBytes())" },
    ),
    UINT(
        "kotlin.UInt",
        4,
        false,
        "buffer.readUnsignedInt()",
        { v -> "buffer.writeUInt($v)" },
        swappedReadExpr = "buffer.readInt().reverseBytes().toUInt()",
        swappedWriteExpr = { v -> "buffer.writeInt($v.toInt().reverseBytes())" },
    ),
    LONG(
        "kotlin.Long",
        8,
        true,
        "buffer.readLong()",
        { v -> "buffer.writeLong($v)" },
        swappedReadExpr = "buffer.readLong().reverseBytes()",
        swappedWriteExpr = { v -> "buffer.writeLong($v.reverseBytes())" },
    ),
    ULONG(
        "kotlin.ULong",
        8,
        false,
        "buffer.readUnsignedLong()",
        { v -> "buffer.writeULong($v)" },
        swappedReadExpr = "buffer.readLong().reverseBytes().toULong()",
        swappedWriteExpr = { v -> "buffer.writeLong($v.toLong().reverseBytes())" },
    ),
    FLOAT(
        "kotlin.Float",
        4,
        true,
        "buffer.readFloat()",
        { v -> "buffer.writeFloat($v)" },
        isNumeric = false,
        swappedReadExpr = "Float.fromBits(buffer.readInt().reverseBytes())",
        swappedWriteExpr = { v -> "buffer.writeInt($v.toRawBits().reverseBytes())" },
    ),
    DOUBLE(
        "kotlin.Double",
        8,
        true,
        "buffer.readDouble()",
        { v -> "buffer.writeDouble($v)" },
        isNumeric = false,
        swappedReadExpr = "Double.fromBits(buffer.readLong().reverseBytes())",
        swappedWriteExpr = { v -> "buffer.writeLong($v.toRawBits().reverseBytes())" },
    ),
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

    data class RemainingBytesStringField(
        val trailingBytes: Int = 0,
    ) : FieldReadStrategy()

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

    /**
     * Nested @ProtocolMessage field bounded by a length annotation. Decoding slices the buffer
     * to the length before invoking the nested codec, so a variable-size payload can be wrapped
     * inside a framed region that also carries trailing fields (e.g., checksum) in a separate
     * @ProtocolMessage wrapper.
     */
    data class NestedMessageWithLengthField(
        val codecName: String,
        val lengthKind: LengthKind,
    ) : FieldReadStrategy()

    data class PayloadField(
        val lengthKind: LengthKind,
        val typeParamName: String,
    ) : FieldReadStrategy()

    data class CollectionField(
        val lengthKind: LengthKind,
        val elementCodecName: String,
    ) : FieldReadStrategy()

    data class UseCodecField(
        val codecName: String,
        val lengthKind: LengthKind?,
    ) : FieldReadStrategy()

    data class Custom(
        val descriptor: CustomFieldDescriptor,
    ) : FieldReadStrategy()

    /**
     * Field populated from @DispatchOn context during decode, written normally during encode.
     * @param codecName the generated codec name for the discriminator type (e.g., "PngChunkHeaderCodec")
     * @param dispatchPackage the package of the sealed dispatch codec
     * @param dispatchCodecSimpleName the simple name of the dispatch codec (e.g., "PngChunkCodec")
     */
    data class DiscriminatorField(
        val codecName: String,
        val dispatchPackage: String,
        val dispatchCodecSimpleName: String,
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
    private var currentDispatchOnInfo: DispatchOnInfo? = null

    fun analyze(
        classDeclaration: KSClassDeclaration,
        dispatchOnInfo: DispatchOnInfo? = null,
        parentWireOrder: WireOrderOverride? = null,
    ): List<FieldInfo>? {
        currentDispatchOnInfo = dispatchOnInfo
        val classWireOrder = extractClassWireOrder(classDeclaration) ?: parentWireOrder
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

            val byteOrderOverride = extractWireOrder(param) ?: classWireOrder

            fields.add(
                FieldInfo(
                    name = name,
                    typeName = typeName,
                    strategy = strategy,
                    isNullable = type.isMarkedNullable,
                    condition = condition,
                    parameter = param,
                    hasDefault = param.hasDefault,
                    byteOrderOverride = byteOrderOverride,
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
                    (it.strategy is FieldReadStrategy.CollectionField && it.strategy.lengthKind is LengthKind.Remaining) ||
                    (it.strategy is FieldReadStrategy.NestedMessageWithLengthField && it.strategy.lengthKind is LengthKind.Remaining)
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
                // Allow trailing fields if each has a fixed wire size. Sum the trailer and
                // rewrite the @RemainingBytes strategy to reserve those bytes on decode.
                val rbIndex = fields.indexOf(rbField)
                val trailingFields = fields.subList(rbIndex + 1, fields.size)
                var trailingSize = 0
                for (t in trailingFields) {
                    if (t.condition != null) {
                        logger.error(
                            "@RemainingBytes on '${rbField.name}' is followed by conditional field '${t.name}'. " +
                                "Conditional fields have variable wire size — they cannot be reserved automatically. " +
                                "Move '${t.name}' before the @RemainingBytes field, " +
                                "or wrap the payload in a length-prefixed @ProtocolMessage.",
                            rbField.parameter,
                        )
                        return null
                    }
                    val size = fixedWireSize(t.strategy)
                    if (size == null) {
                        logger.error(
                            "@RemainingBytes on '${rbField.name}' is followed by '${t.name}', which has a variable " +
                                "wire size. Only fields with a fixed wire size (primitives, value classes wrapping " +
                                "primitives, fixed custom fields) may follow @RemainingBytes. " +
                                "Move '${t.name}' before the @RemainingBytes field, " +
                                "or wrap the payload in a length-prefixed @ProtocolMessage.",
                            rbField.parameter,
                        )
                        return null
                    }
                    trailingSize += size
                }
                val updated = rewriteRemainingStrategy(rbField.strategy, trailingSize)
                val idx = fields.indexOf(rbField)
                fields[idx] = rbField.copy(strategy = updated)
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
                    is FieldReadStrategy.NestedMessageWithLengthField -> {
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

    /**
     * Returns the fixed wire size in bytes, or null if the strategy's size is not statically
     * known. Used to validate trailing fields after a @RemainingBytes field and sum the
     * reservation.
     */
    private fun fixedWireSize(strategy: FieldReadStrategy): Int? =
        when (strategy) {
            is FieldReadStrategy.PrimitiveField -> strategy.wireBytes
            is FieldReadStrategy.ValueClassField -> fixedWireSize(strategy.innerStrategy)
            is FieldReadStrategy.Custom ->
                strategy.descriptor.fixedSize.takeIf { it >= 0 }
            else -> null
        }

    private fun rewriteRemainingStrategy(
        strategy: FieldReadStrategy,
        trailingBytes: Int,
    ): FieldReadStrategy =
        when (strategy) {
            is FieldReadStrategy.RemainingBytesStringField ->
                FieldReadStrategy.RemainingBytesStringField(trailingBytes)
            is FieldReadStrategy.PayloadField ->
                strategy.copy(lengthKind = LengthKind.Remaining(trailingBytes))
            is FieldReadStrategy.CollectionField ->
                strategy.copy(lengthKind = LengthKind.Remaining(trailingBytes))
            is FieldReadStrategy.NestedMessageWithLengthField ->
                strategy.copy(lengthKind = LengthKind.Remaining(trailingBytes))
            is FieldReadStrategy.UseCodecField ->
                strategy.copy(lengthKind = LengthKind.Remaining(trailingBytes))
            else -> strategy
        }

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

        // Check @UseCodec (built-in custom codec reference)
        val annotations = param.annotations.toList()
        val decodeWith =
            annotations.find {
                it.qualifiedName() == "com.ditchoom.buffer.codec.annotations.UseCodec"
            }
        if (decodeWith != null) {
            return resolveUseCodec(decodeWith, param, annotations, fieldName)
        }

        // Check SPI-registered custom annotations
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

    private fun resolveUseCodec(
        decodeWith: KSAnnotation,
        param: KSValueParameter,
        annotations: List<KSAnnotation>,
        fieldName: String,
    ): FieldReadStrategy? {
        // Extract the KClass argument and resolve to a qualified codec name
        val codecArg = decodeWith.arguments.find { it.name?.asString() == "codec" }?.value
        val codecType = codecArg as? KSType
        if (codecType == null) {
            logger.error(
                "@UseCodec on field '$fieldName' requires a codec KClass argument.",
                param,
            )
            return null
        }
        val codecDecl = codecType.declaration as? KSClassDeclaration
        if (codecDecl == null) {
            logger.error(
                "@UseCodec on field '$fieldName': codec must reference a class or object.",
                param,
            )
            return null
        }
        val codecName = codecDecl.qualifiedName?.asString()
        if (codecName == null) {
            logger.error("@UseCodec on field '$fieldName': cannot resolve codec class name.", param)
            return null
        }

        // Optionally combine with a length annotation (not required)
        val hasLengthAnnotation =
            annotations.any {
                val fqn = it.qualifiedName()
                fqn == "com.ditchoom.buffer.codec.annotations.LengthPrefixed" ||
                    fqn == "com.ditchoom.buffer.codec.annotations.RemainingBytes" ||
                    fqn == "com.ditchoom.buffer.codec.annotations.LengthFrom"
            }
        val lengthKind =
            if (hasLengthAnnotation) {
                resolveLengthKind(param, annotations, fieldName, "@UseCodec") ?: return null
            } else {
                null // No length annotation: codec reads directly from buffer
            }

        return FieldReadStrategy.UseCodecField(codecName, lengthKind)
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
            is LengthKind.Remaining -> FieldReadStrategy.RemainingBytesStringField()
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

        // Check if it has @ProtocolMessage (nested message or discriminator)
        val hasProtocolMessage =
            typeDecl.annotations.any {
                it.qualifiedName() == "com.ditchoom.buffer.codec.annotations.ProtocolMessage"
            }
        if (hasProtocolMessage) {
            val codecName = typeDecl.codecName()
            // If this field's type matches the @DispatchOn discriminator type,
            // it should be populated from context during decode (not read from buffer)
            val qualifiedName = typeDecl.qualifiedName?.asString()
            val dispatchInfo = currentDispatchOnInfo
            if (dispatchInfo != null && qualifiedName != null && qualifiedName == dispatchInfo.typeName) {
                // Reject length annotations on discriminator fields — they don't consume wire bytes here.
                val annotations = param.annotations.toList()
                val lengthAnn =
                    annotations.find {
                        val fqn = it.qualifiedName()
                        fqn == "com.ditchoom.buffer.codec.annotations.LengthFrom" ||
                            fqn == "com.ditchoom.buffer.codec.annotations.LengthPrefixed" ||
                            fqn == "com.ditchoom.buffer.codec.annotations.RemainingBytes"
                    }
                if (lengthAnn != null) {
                    val annShort = lengthAnn.qualifiedName()?.substringAfterLast(".") ?: "length annotation"
                    logger.error(
                        "@$annShort is not valid on field '$fieldName': this field is the @DispatchOn " +
                            "discriminator and is populated from decode context, not read from the wire.",
                        param,
                    )
                    return null
                }
                return FieldReadStrategy.DiscriminatorField(
                    codecName,
                    dispatchInfo.sealedPackage,
                    dispatchInfo.sealedCodecSimpleName,
                )
            }

            // If a length annotation is present, bound the nested decode to that byte count.
            val annotations = param.annotations.toList()
            val hasLengthAnnotation =
                annotations.any {
                    val fqn = it.qualifiedName()
                    fqn == "com.ditchoom.buffer.codec.annotations.LengthFrom" ||
                        fqn == "com.ditchoom.buffer.codec.annotations.LengthPrefixed" ||
                        fqn == "com.ditchoom.buffer.codec.annotations.RemainingBytes"
                }
            if (hasLengthAnnotation) {
                val lk = resolveLengthKind(param, annotations, fieldName, "Nested @ProtocolMessage") ?: return null
                return FieldReadStrategy.NestedMessageWithLengthField(codecName, lk)
            }
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
            return LengthKind.Remaining()
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

    private fun extractWireOrder(param: KSValueParameter): WireOrderOverride? {
        val ann =
            param.annotations.toList().find { matchAnnotation(it, "WireOrder") }
                ?: return null
        return resolveEndianness(ann)
    }

    fun extractClassWireOrderPublic(classDeclaration: KSClassDeclaration): WireOrderOverride? = extractClassWireOrder(classDeclaration)

    private fun extractClassWireOrder(classDeclaration: KSClassDeclaration): WireOrderOverride? {
        val ann =
            classDeclaration.annotations.toList().find { matchAnnotation(it, "ProtocolMessage") }
                ?: return null
        val wireOrderArg = ann.arguments.find { it.name?.asString() == "wireOrder" }?.value ?: return null
        val enumName = wireOrderArg.toString().substringAfterLast(".")
        return when (enumName) {
            "Big" -> WireOrderOverride.Big
            "Little" -> WireOrderOverride.Little
            else -> null // Default = no override
        }
    }

    private fun matchAnnotation(
        ann: KSAnnotation,
        simpleName: String,
    ): Boolean {
        val qn = ann.qualifiedName()
        if (qn == "com.ditchoom.buffer.codec.annotations.$simpleName") return true
        val sn =
            try {
                ann.shortName.asString()
            } catch (_: Exception) {
                ""
            }
        if (sn == simpleName) return true
        val typeName =
            try {
                ann.annotationType
                    .resolve()
                    .declaration.simpleName
                    .asString()
            } catch (_: Exception) {
                ""
            }
        return typeName == simpleName
    }

    private fun resolveEndianness(ann: KSAnnotation): WireOrderOverride? {
        val orderArg = ann.arguments.firstOrNull()?.value ?: return null
        val enumName = orderArg.toString().substringAfterLast(".")
        return when (enumName) {
            "Big" -> WireOrderOverride.Big
            "Little" -> WireOrderOverride.Little
            else -> null
        }
    }
}
