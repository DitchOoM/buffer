package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.ksp.writeTo

private val READ_BUFFER = ClassName("com.ditchoom.buffer", "ReadBuffer")
private val WRITE_BUFFER = ClassName("com.ditchoom.buffer", "WriteBuffer")
private val CODEC = ClassName("com.ditchoom.buffer.codec", "Codec")
private val PAYLOAD_READER = ClassName("com.ditchoom.buffer.codec.payload", "PayloadReader")
private val READ_BUFFER_PAYLOAD_READER = ClassName("com.ditchoom.buffer.codec.payload", "ReadBufferPayloadReader")
private val UNIT = ClassName("kotlin", "Unit")

class CodecGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {
    fun generate(
        classDeclaration: KSClassDeclaration,
        fields: List<FieldInfo>,
        batches: List<CodegenItem>,
        hasPayload: Boolean,
    ) {
        val className = classDeclaration.simpleName.asString()
        val packageName = classDeclaration.packageName.asString()
        val codecName = "${className}Codec"
        val containingFile = classDeclaration.containingFile

        val dependencies =
            if (containingFile != null) {
                Dependencies(true, containingFile)
            } else {
                Dependencies(true)
            }

        val fileSpec =
            if (hasPayload) {
                buildPayloadCodecFile(packageName, className, codecName, fields, batches)
            } else {
                buildCodecFile(packageName, className, codecName, fields, batches)
            }

        fileSpec.writeTo(codeGenerator, dependencies)
    }

    // ──────────────────────── Standard (non-payload) codec ────────────────────────

    private fun buildCodecFile(
        packageName: String,
        className: String,
        codecName: String,
        fields: List<FieldInfo>,
        batches: List<CodegenItem>,
    ): FileSpec {
        val classTypeName = ClassName(packageName, className)

        // Build decode function body
        val decodeBody = CodeBlock.builder()
        var batchIndex = 0
        for (item in batches) {
            when (item) {
                is CodegenItem.Batched -> {
                    addBatchRead(decodeBody, item.group, batchIndex)
                    batchIndex++
                }
                is CodegenItem.Single -> {
                    addFieldRead(decodeBody, item.field)
                }
            }
        }
        val fieldNames = fields.joinToString(", ") { it.name }
        decodeBody.addStatement("return %L(%L)", className, fieldNames)

        val decodeFun =
            FunSpec
                .builder("decode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", READ_BUFFER)
                .returns(classTypeName)
                .addCode(decodeBody.build())
                .build()

        // Build encode function body
        val encodeBody = CodeBlock.builder()
        for (field in fields) {
            addFieldWrite(encodeBody, field, "value.${field.name}")
        }

        val encodeFun =
            FunSpec
                .builder("encode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", WRITE_BUFFER)
                .addParameter("value", classTypeName)
                .addCode(encodeBody.build())
                .build()

        val objectBuilder =
            TypeSpec
                .objectBuilder(codecName)
                .addSuperinterface(CODEC.parameterizedBy(classTypeName))
                .addFunction(decodeFun)
                .addFunction(encodeFun)

        // Generate sizeOf if possible
        val sizeOfFun = generateSizeOf(fields, classTypeName)
        if (sizeOfFun != null) {
            objectBuilder.addFunction(sizeOfFun)
        }

        val fileBuilder =
            FileSpec
                .builder(packageName, codecName)
                .addType(objectBuilder.build())
        addExtensionImports(fileBuilder, fields)
        return fileBuilder.build()
    }

    // ──────────────────────── Payload codec ────────────────────────

    private fun buildPayloadCodecFile(
        packageName: String,
        className: String,
        codecName: String,
        fields: List<FieldInfo>,
        batches: List<CodegenItem>,
    ): FileSpec {
        val payloadFields = fields.filter { it.strategy is FieldReadStrategy.PayloadField }
        val payloadTypeParams =
            payloadFields.map { (it.strategy as FieldReadStrategy.PayloadField).typeParamName }.distinct()
        val typeParamList = payloadTypeParams.joinToString(", ")
        val contextName = "${className}Context"
        val typeVariables = payloadTypeParams.map { TypeVariableName(it) }

        // Build decode function
        val decodeBuilder =
            FunSpec
                .builder("decode")
                .addParameter("buffer", READ_BUFFER)

        for (tv in typeVariables) {
            decodeBuilder.addTypeVariable(tv)
        }

        for (pf in payloadFields) {
            val strategy = pf.strategy as FieldReadStrategy.PayloadField
            val tpName = TypeVariableName(strategy.typeParamName)
            val contextType = ClassName(packageName, contextName)
            val lambdaType = LambdaTypeName.get(receiver = contextType, parameters = listOf(), returnType = tpName)
            // The lambda takes PayloadReader as a parameter
            val paramLambdaType =
                LambdaTypeName.get(
                    receiver = contextType,
                    parameters =
                        listOf(
                            com.squareup.kotlinpoet.ParameterSpec
                                .unnamed(PAYLOAD_READER),
                        ),
                    returnType = tpName,
                )
            decodeBuilder.addParameter("decode${capitalizeFirst(pf.name)}", paramLambdaType)
        }

        val classWithTypeParams = "$className<$typeParamList>"
        val returnType = ClassName(packageName, className).parameterizedBy(typeVariables)
        decodeBuilder.returns(returnType)

        val decodeBody = CodeBlock.builder()

        // Phase 1: read all wire data in order
        var batchIndex = 0
        for (item in batches) {
            when (item) {
                is CodegenItem.Batched -> {
                    addBatchRead(decodeBody, item.group, batchIndex)
                    batchIndex++
                }
                is CodegenItem.Single -> {
                    val strategy = item.field.strategy
                    if (strategy is FieldReadStrategy.PayloadField) {
                        addPayloadRawRead(decodeBody, item.field)
                    } else {
                        addFieldRead(decodeBody, item.field)
                    }
                }
            }
        }

        // Phase 2: create context from non-payload fields
        val nonPayloadFields = fields.filter { it.strategy !is FieldReadStrategy.PayloadField }
        decodeBody.addStatement(
            "val _ctx = %L(%L)",
            contextName,
            nonPayloadFields.joinToString(", ") { it.name },
        )

        // Phase 3: decode payload fields using stored bytes + lambdas
        for (pf in payloadFields) {
            val rawVar = "_raw_${pf.name}"
            val condition = pf.condition
            if (condition != null || pf.isNullable) {
                decodeBody.beginControlFlow("val %L = if (%L != null)", pf.name, rawVar)
                decodeBody.addStatement("val _pr = %T(%L)", READ_BUFFER_PAYLOAD_READER, rawVar)
                decodeBody.addStatement(
                    "try { _ctx.decode%L(_pr) } finally { _pr.release() }",
                    capitalizeFirst(pf.name),
                )
                decodeBody.nextControlFlow("else")
                decodeBody.addStatement("null")
                decodeBody.endControlFlow()
            } else {
                decodeBody.beginControlFlow("val %L = run", pf.name)
                decodeBody.addStatement("val _pr = %T(%L)", READ_BUFFER_PAYLOAD_READER, rawVar)
                decodeBody.addStatement(
                    "try { _ctx.decode%L(_pr) } finally { _pr.release() }",
                    capitalizeFirst(pf.name),
                )
                decodeBody.endControlFlow()
            }
        }

        // Phase 4: return constructor call
        val fieldNames = fields.joinToString(", ") { it.name }
        decodeBody.addStatement("return %L(%L)", className, fieldNames)

        decodeBuilder.addCode(decodeBody.build())

        // Build encode function
        val encodeBuilder =
            FunSpec
                .builder("encode")
                .addParameter("buffer", WRITE_BUFFER)
                .addParameter("value", returnType)

        for (tv in typeVariables) {
            encodeBuilder.addTypeVariable(tv)
        }

        for (pf in payloadFields) {
            val strategy = pf.strategy as FieldReadStrategy.PayloadField
            val tpName = TypeVariableName(strategy.typeParamName)
            val encodeLambdaType =
                LambdaTypeName.get(
                    parameters =
                        listOf(
                            com.squareup.kotlinpoet.ParameterSpec
                                .unnamed(WRITE_BUFFER),
                            com.squareup.kotlinpoet.ParameterSpec
                                .unnamed(tpName),
                        ),
                    returnType = UNIT,
                )
            encodeBuilder.addParameter("encode${capitalizeFirst(pf.name)}", encodeLambdaType)
        }

        val encodeBody = CodeBlock.builder()
        for (field in fields) {
            val strategy = field.strategy
            if (strategy is FieldReadStrategy.PayloadField) {
                addPayloadWrite(encodeBody, field)
            } else {
                addFieldWrite(encodeBody, field, "value.${field.name}")
            }
        }
        encodeBuilder.addCode(encodeBody.build())

        val objectSpec =
            TypeSpec
                .objectBuilder(codecName)
                .addFunction(decodeBuilder.build())
                .addFunction(encodeBuilder.build())
                .build()

        val fileBuilder =
            FileSpec
                .builder(packageName, codecName)
                .addType(objectSpec)
        addExtensionImports(fileBuilder, fields)
        return fileBuilder.build()
    }

    // ──────────────────────── Payload raw read (Phase 1) ────────────────────────

    private fun addPayloadRawRead(
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

    private fun addPayloadRawReadBody(
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

    // ──────────────────────── Payload write (encode) ────────────────────────

    private fun addPayloadWrite(
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

    private fun addPayloadEncodeBody(
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

    // ──────────────────────── Prefix helpers keyed by LengthPrefix enum name ────────────────────────

    private data class PrefixConfig(
        val byteCount: Int,
        val readExpr: String,
        val writePlaceholder: String,
        val writeExpr: (String) -> String,
    )

    private val prefixConfigs =
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

    private fun prefixConfig(prefix: String): PrefixConfig = prefixConfigs[prefix] ?: error("Unknown LengthPrefix: $prefix")

    // ──────────────────────── Batch read ────────────────────────

    private fun addBatchRead(
        code: CodeBlock.Builder,
        item: BatchGroup,
        batchIndex: Int,
    ) {
        val batchVar = "_batch$batchIndex"
        val readType =
            when (item.readMethod) {
                "readLong" -> "Long"
                "readInt" -> "Int"
                "readShort" -> "Short"
                else -> "Byte"
            }
        code.addStatement("val %L = buffer.%L()", batchVar, item.readMethod)
        if (readType == "Short" || readType == "Byte") {
            code.addStatement(
                "val %LBits = %L.toInt() and %L",
                batchVar,
                batchVar,
                if (readType == "Short") "0xFFFF" else "0xFF",
            )
        }

        var bitOffset = item.totalBytes * 8
        for (field in item.fields) {
            val fieldSize = field.strategy.fixedSize
            val fieldBits = fieldSize * 8
            bitOffset -= fieldBits
            val extractExpr =
                generateExtractExpression(
                    batchVar = if (readType == "Short" || readType == "Byte") "${batchVar}Bits" else batchVar,
                    bitOffset = bitOffset,
                    fieldBits = fieldBits,
                    readType = readType,
                    strategy = field.strategy,
                )
            code.addStatement("val %L = %L", field.name, extractExpr)
        }
    }

    private fun generateExtractExpression(
        batchVar: String,
        bitOffset: Int,
        fieldBits: Int,
        readType: String,
        strategy: FieldReadStrategy,
    ): String {
        val batchBits =
            when (readType) {
                "Long" -> 64
                "Int" -> 32
                "Short" -> 16
                else -> 8
            }
        val mask = if (fieldBits >= batchBits) "" else hexMask(fieldBits)

        val shift = if (bitOffset > 0) " ushr $bitOffset" else ""
        val maskApply = if (mask.isNotEmpty()) " and $mask" else ""

        val rawExpr = "($batchVar$shift$maskApply)"

        return when (strategy) {
            is FieldReadStrategy.PrimitiveField -> {
                if (strategy.wireBytes == strategy.primitive.defaultWireBytes) {
                    standardBatchCast(strategy.primitive, rawExpr, readType)
                } else {
                    customWidthBatchCast(strategy, rawExpr, readType)
                }
            }
            is FieldReadStrategy.ValueClassField -> {
                val innerExpr = generateExtractExpression(batchVar, bitOffset, fieldBits, readType, strategy.innerStrategy)
                "${strategy.wrapperType}($innerExpr)"
            }
            is FieldReadStrategy.Custom -> error("Custom fields cannot participate in batch reads")
            else -> rawExpr
        }
    }

    private fun hexMask(bits: Int): String {
        val hex = "FF".repeat(bits / 8)
        return if (bits >= 32) "0x${hex}L" else "0x$hex"
    }

    private fun standardBatchCast(
        primitive: Primitive,
        rawExpr: String,
        readType: String,
    ): String =
        when (primitive) {
            Primitive.BYTE -> "$rawExpr.toByte()"
            Primitive.UBYTE -> "$rawExpr.toUByte()"
            Primitive.SHORT -> "$rawExpr.toShort()"
            Primitive.USHORT -> "$rawExpr.toUShort()"
            Primitive.INT -> if (readType == "Long") "$rawExpr.toInt()" else rawExpr
            Primitive.UINT -> "$rawExpr.toUInt()"
            Primitive.LONG -> rawExpr
            Primitive.ULONG -> "$rawExpr.toULong()"
            Primitive.FLOAT -> "Float.fromBits($rawExpr.toInt())"
            Primitive.DOUBLE -> "Double.fromBits($rawExpr)"
            Primitive.BOOLEAN -> "$rawExpr != 0"
        }

    private fun customWidthBatchCast(
        strategy: FieldReadStrategy.PrimitiveField,
        rawExpr: String,
        readType: String,
    ): String {
        val p = strategy.primitive
        val wireBits = strategy.wireBytes * 8
        val holdingType = if (p.defaultWireBytes <= 4) "Int" else "Long"

        // Convert from batch read type to target's holding type
        val converted =
            when {
                readType == "Long" && holdingType == "Int" -> "$rawExpr.toInt()"
                readType != "Long" && holdingType == "Long" -> "$rawExpr.toLong()"
                else -> rawExpr
            }

        return if (p.signed) {
            val totalBits = if (holdingType == "Long") 64 else 32
            val shiftAmount = totalBits - wireBits
            val extended = "($converted shl $shiftAmount) shr $shiftAmount"
            typeCast(p, extended, holdingType)
        } else {
            typeCast(p, converted, holdingType)
        }
    }

    // ──────────────────────── Individual field read/write ────────────────────────

    private fun addFieldRead(
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

    private fun readExpression(strategy: FieldReadStrategy): String =
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
            is FieldReadStrategy.PayloadField -> error("PayloadField uses writePayloadCodec path")
            is FieldReadStrategy.Custom -> {
                val d = strategy.descriptor
                val args = d.contextFields.joinToString(", ")
                if (args.isEmpty()) "buffer.${d.readFunction.functionName}()" else "buffer.${d.readFunction.functionName}($args)"
            }
        }

    private fun addFieldWrite(
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

    private fun writeExpression(
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
            is FieldReadStrategy.PayloadField -> error("PayloadField uses writePayloadCodec path")
            is FieldReadStrategy.Custom -> {
                val d = strategy.descriptor
                val contextArgs = d.contextFields.joinToString(", ") { "value.$it" }
                val allArgs = if (contextArgs.isEmpty()) valueExpr else "$valueExpr, $contextArgs"
                "buffer.${d.writeFunction.functionName}($allArgs)"
            }
        }

    // ──────────────────────── Custom-width read/write codegen ────────────────────────

    private fun customWidthReadExpr(strategy: FieldReadStrategy.PrimitiveField): String {
        val p = strategy.primitive
        val wireBytes = strategy.wireBytes
        val chunks = decomposeWireBytes(wireBytes)
        val h = if (p.defaultWireBytes <= 4) "Int" else "Long"

        val stmts = mutableListOf<String>()
        var remainingBytes = wireBytes
        val chunkVars = mutableListOf<Pair<String, Int>>()
        var varIndex = 0

        for (cs in chunks) {
            remainingBytes -= cs
            val varName = "_c${varIndex++}"
            val read =
                when (cs) {
                    4 -> "buffer.readInt()" + if (h == "Long") ".toLong() and 0xFFFFFFFFL" else ""
                    2 -> "buffer.readShort().to$h() and ${if (h == "Long") "0xFFFFL" else "0xFFFF"}"
                    else -> "buffer.readByte().to$h() and ${if (h == "Long") "0xFFL" else "0xFF"}"
                }
            stmts.add("val $varName = $read")
            chunkVars.add(varName to remainingBytes * 8)
        }

        val rawExpr = chunkVars.joinToString(" or ") { (name, shift) -> if (shift > 0) "($name shl $shift)" else name }

        if (p.signed && wireBytes < p.defaultWireBytes) {
            val shiftAmount = (if (h == "Long") 64 else 32) - wireBytes * 8
            stmts.add("val _raw = $rawExpr")
            stmts.add(typeCast(p, "(_raw shl $shiftAmount) shr $shiftAmount", h))
        } else {
            stmts.add(typeCast(p, rawExpr, h))
        }

        return "run { ${stmts.joinToString("; ")} }"
    }

    private fun customWidthWriteExpr(
        strategy: FieldReadStrategy.PrimitiveField,
        valueExpr: String,
    ): String {
        val p = strategy.primitive
        val wireBytes = strategy.wireBytes
        val chunks = decomposeWireBytes(wireBytes)
        val h = if (p.defaultWireBytes <= 4) "Int" else "Long"

        val stmts = mutableListOf<String>()
        val vExpr = if (p == Primitive.INT || p == Primitive.LONG) valueExpr else "$valueExpr.to$h()"
        stmts.add("val _v = $vExpr")

        var remainingBytes = wireBytes
        for (cs in chunks) {
            remainingBytes -= cs
            val shift = remainingBytes * 8
            val shifted = if (shift > 0) "(_v ushr $shift)" else "_v"
            stmts.add(
                when (cs) {
                    4 -> "buffer.writeInt($shifted${if (h == "Long") ".toInt()" else ""})"
                    2 -> "buffer.writeShort($shifted.toShort())"
                    else -> "buffer.writeByte($shifted.toByte())"
                },
            )
        }

        return "run { ${stmts.joinToString("; ")} }"
    }

    // ──────────────────────── Custom-width helpers ────────────────────────

    private fun decomposeWireBytes(wireBytes: Int): List<Int> =
        buildList {
            var r = wireBytes
            for (s in intArrayOf(4, 2, 1)) {
                if (r >= s) {
                    add(s)
                    r -= s
                }
            }
        }

    private fun typeCast(
        primitive: Primitive,
        expr: String,
        holdingType: String,
    ): String =
        when (primitive) {
            Primitive.BYTE -> "($expr).toByte()"
            Primitive.UBYTE -> "($expr).toUByte()"
            Primitive.SHORT -> "($expr).toShort()"
            Primitive.USHORT -> "($expr).toUShort()"
            Primitive.INT -> expr
            Primitive.UINT -> "($expr).toUInt()"
            Primitive.LONG -> if (holdingType == "Int") "($expr).toLong()" else expr
            Primitive.ULONG -> "($expr).toULong()"
            else -> expr
        }

    // ──────────────────────── sizeOf generation ────────────────────────

    private fun generateSizeOf(
        fields: List<FieldInfo>,
        classTypeName: ClassName,
    ): FunSpec? {
        // Skip if any field has a condition (can't compute size statically)
        if (fields.any { it.condition != null }) return null

        var fixedTotal = 0
        val runtimeExprs = mutableListOf<String>()
        var canGenerate = true

        for (field in fields) {
            val strategy = field.strategy
            val size = strategy.fixedSize
            if (size >= 0) {
                fixedTotal += size
            } else if (strategy is FieldReadStrategy.Custom) {
                val sizeOfFn = strategy.descriptor.sizeOfFunction
                if (sizeOfFn != null) {
                    runtimeExprs.add("${sizeOfFn.functionName}(value.${field.name})")
                } else {
                    // Variable-length custom with no sizeOf — can't generate
                    canGenerate = false
                    break
                }
            } else {
                // Variable-length built-in (String, etc.) — can't generate
                canGenerate = false
                break
            }
        }

        if (!canGenerate) return null

        val builder =
            FunSpec
                .builder("sizeOf")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("value", classTypeName)
                .returns(ClassName("kotlin", "Int").copy(nullable = true))

        if (runtimeExprs.isEmpty()) {
            // All fixed-size — constant return
            builder.addStatement("return %L", fixedTotal)
        } else {
            // Mixed fixed + runtime
            builder.addStatement("var size = %L", fixedTotal)
            for (expr in runtimeExprs) {
                builder.addStatement("size += %L", expr)
            }
            builder.addStatement("return size")
        }

        return builder.build()
    }

    // ──────────────────────── Utility ────────────────────────

    private fun needsLengthPrefixedImports(fields: List<FieldInfo>): Boolean = fields.any { needsLengthPrefixedImport(it.strategy) }

    private fun needsLengthPrefixedImport(strategy: FieldReadStrategy): Boolean =
        when (strategy) {
            is FieldReadStrategy.LengthPrefixedStringField -> strategy.prefix == "Short"
            is FieldReadStrategy.ValueClassField -> needsLengthPrefixedImport(strategy.innerStrategy)
            else -> false
        }

    private fun addExtensionImports(
        fileBuilder: FileSpec.Builder,
        fields: List<FieldInfo>,
    ) {
        if (needsLengthPrefixedImports(fields)) {
            fileBuilder.addImport("com.ditchoom.buffer", "readLengthPrefixedUtf8String")
            fileBuilder.addImport("com.ditchoom.buffer", "writeLengthPrefixedUtf8String")
        }
        for (field in fields) {
            val strategy = field.strategy
            if (strategy is FieldReadStrategy.Custom) {
                val d = strategy.descriptor
                fileBuilder.addImport(d.readFunction.packageName, d.readFunction.functionName)
                fileBuilder.addImport(d.writeFunction.packageName, d.writeFunction.functionName)
                d.sizeOfFunction?.let { fileBuilder.addImport(it.packageName, it.functionName) }
            }
        }
    }

    private fun capitalizeFirst(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
