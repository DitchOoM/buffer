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
        val codecName = classDeclaration.codecName()
        val classTypeName = classDeclaration.toPoetClassName()
        val containingFile = classDeclaration.containingFile

        val dependencies =
            if (containingFile != null) {
                Dependencies(aggregating = false, sources = arrayOf(containingFile))
            } else {
                Dependencies(aggregating = false)
            }

        val fileSpec =
            if (hasPayload) {
                buildPayloadCodecFile(packageName, classTypeName, codecName, fields, batches)
            } else {
                buildCodecFile(packageName, classTypeName, codecName, fields, batches)
            }

        fileSpec.writeTo(codeGenerator, dependencies)
    }

    // ──────────────────────── Standard (non-payload) codec ────────────────────────

    private fun buildCodecFile(
        packageName: String,
        classTypeName: ClassName,
        codecName: String,
        fields: List<FieldInfo>,
        batches: List<CodegenItem>,
    ): FileSpec {
        val objectBuilder =
            TypeSpec
                .objectBuilder(codecName)
                .addSuperinterface(CODEC.parameterizedBy(classTypeName))

        // Context-free decode delegates to context-aware overload
        objectBuilder.addFunction(
            FunSpec
                .builder("decode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", READ_BUFFER)
                .returns(classTypeName)
                .addStatement("return decode(buffer, %T.Empty)", DECODE_CONTEXT)
                .build(),
        )

        // Context-aware decode (the real implementation)
        val decodeBody = CodeBlock.builder()
        var batchIndex = 0
        for (item in batches) {
            when (item) {
                is CodegenItem.Batched -> {
                    addBatchRead(decodeBody, item.group, batchIndex)
                    batchIndex++
                }
                is CodegenItem.Single -> {
                    addFieldRead(decodeBody, item.field, withContext = true)
                }
            }
        }
        val fieldNames = fields.joinToString(", ") { it.name }
        decodeBody.addStatement("return %T(%L)", classTypeName, fieldNames)

        objectBuilder.addFunction(
            FunSpec
                .builder("decode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", READ_BUFFER)
                .addParameter("context", DECODE_CONTEXT)
                .returns(classTypeName)
                .addCode(decodeBody.build())
                .build(),
        )

        // Context-free encode delegates to context-aware overload
        objectBuilder.addFunction(
            FunSpec
                .builder("encode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", WRITE_BUFFER)
                .addParameter("value", classTypeName)
                .addStatement("encode(buffer, value, %T.Empty)", ENCODE_CONTEXT)
                .build(),
        )

        // Context-aware encode (the real implementation)
        val encodeBody = CodeBlock.builder()
        for (field in fields) {
            addFieldWrite(encodeBody, field, "value.${field.name}", withContext = true)
        }

        objectBuilder.addFunction(
            FunSpec
                .builder("encode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", WRITE_BUFFER)
                .addParameter("value", classTypeName)
                .addParameter("context", ENCODE_CONTEXT)
                .addCode(encodeBody.build())
                .build(),
        )

        // Generate sizeOf if possible
        val sizeOfFun = generateSizeOf(fields, classTypeName)
        if (sizeOfFun != null) {
            objectBuilder.addFunction(sizeOfFun)
        }

        // Generate peekFrameSize if possible
        val peekResult = PeekFrameSizeEmitter.generate(fields)
        if (peekResult != null) {
            objectBuilder.addProperty(PeekFrameSizeEmitter.buildMinHeaderProperty(peekResult))
            for (fn in PeekFrameSizeEmitter.buildFunctions(peekResult)) {
                objectBuilder.addFunction(fn)
            }
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
        classTypeName: ClassName,
        codecName: String,
        fields: List<FieldInfo>,
        batches: List<CodegenItem>,
    ): FileSpec {
        val className = classTypeName.simpleName
        val payloadFields = fields.filter { it.strategy is FieldReadStrategy.PayloadField }
        val payloadTypeParams =
            payloadFields.map { (it.strategy as FieldReadStrategy.PayloadField).typeParamName }.distinct()
        val typeParamList = payloadTypeParams.joinToString(", ")
        val contextName = "${classTypeName.simpleNames.joinToString("")}Context"
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

        val returnType = classTypeName.parameterizedBy(typeVariables)
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
        decodeBody.addStatement("return %T(%L)", classTypeName, fieldNames)

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

        val objectBuilder =
            TypeSpec
                .objectBuilder(codecName)
                .addFunction(decodeBuilder.build())
                .addFunction(encodeBuilder.build())

        // Generate context keys and context-based decode/encode for each payload field.
        // These enable the codec to be used as a nested field via Codec.decode(buffer, context).
        for (pf in payloadFields) {
            val fieldCapitalized = capitalizeFirst(pf.name)
            val contextType = ClassName(packageName, contextName)

            // Decode key: data object extending CodecContext.Key
            val decodeLambdaType =
                LambdaTypeName.get(
                    receiver = contextType,
                    parameters =
                        listOf(
                            com.squareup.kotlinpoet.ParameterSpec
                                .unnamed(PAYLOAD_READER),
                        ),
                    returnType = ClassName("kotlin", "Any").copy(nullable = true),
                )
            objectBuilder.addType(
                TypeSpec
                    .objectBuilder("${fieldCapitalized}DecodeKey")
                    .addModifiers(KModifier.DATA)
                    .superclass(CODEC_CONTEXT_KEY.parameterizedBy(decodeLambdaType))
                    .build(),
            )

            // Encode key: data object extending CodecContext.Key
            val encodeLambdaType =
                LambdaTypeName.get(
                    parameters =
                        listOf(
                            com.squareup.kotlinpoet.ParameterSpec
                                .unnamed(WRITE_BUFFER),
                            com.squareup.kotlinpoet.ParameterSpec
                                .unnamed(ClassName("kotlin", "Any").copy(nullable = true)),
                        ),
                    returnType = UNIT,
                )
            objectBuilder.addType(
                TypeSpec
                    .objectBuilder("${fieldCapitalized}EncodeKey")
                    .addModifiers(KModifier.DATA)
                    .superclass(CODEC_CONTEXT_KEY.parameterizedBy(encodeLambdaType))
                    .build(),
            )
        }

        // Context-based decode: reads lambdas from DecodeContext, delegates to Convention 1
        val ctxDecodeBody = CodeBlock.builder()
        val lambdaArgs = mutableListOf<String>()
        for (pf in payloadFields) {
            val fieldCapitalized = capitalizeFirst(pf.name)
            val localVar = "_decode$fieldCapitalized"
            ctxDecodeBody.addStatement(
                "val %L = context[%L] ?: error(%S)",
                localVar,
                "${fieldCapitalized}DecodeKey",
                "DecodeContext missing $codecName.${fieldCapitalized}DecodeKey. " +
                    "Register: ctx.with($codecName.${fieldCapitalized}DecodeKey) { pr -> ... }",
            )
            lambdaArgs.add(localVar)
        }
        ctxDecodeBody.addStatement(
            "return decode(buffer, %L)",
            lambdaArgs.joinToString(", "),
        )

        val starReturnType = classTypeName.parameterizedBy(payloadFields.map { com.squareup.kotlinpoet.STAR })
        objectBuilder.addFunction(
            FunSpec
                .builder("decodeFromContext")
                .addParameter("buffer", READ_BUFFER)
                .addParameter("context", DECODE_CONTEXT)
                .returns(starReturnType)
                .addCode(ctxDecodeBody.build())
                .build(),
        )

        // Context-based encode: reads lambdas from EncodeContext, delegates to Convention 1
        val ctxEncodeBody = CodeBlock.builder()
        val encodeLambdaArgs = mutableListOf<String>()
        for (pf in payloadFields) {
            val fieldCapitalized = capitalizeFirst(pf.name)
            val localVar = "_encode$fieldCapitalized"
            ctxEncodeBody.addStatement(
                "val %L = context[%L] ?: error(%S)",
                localVar,
                "${fieldCapitalized}EncodeKey",
                "EncodeContext missing $codecName.${fieldCapitalized}EncodeKey. " +
                    "Register: ctx.with($codecName.${fieldCapitalized}EncodeKey) { buf, v -> ... }",
            )
            encodeLambdaArgs.add(localVar)
        }
        ctxEncodeBody.addStatement(
            "encode(buffer, value, %L)",
            encodeLambdaArgs.joinToString(", "),
        )

        objectBuilder.addFunction(
            FunSpec
                .builder("encodeFromContext")
                .addParameter("buffer", WRITE_BUFFER)
                .addParameter("value", starReturnType)
                .addParameter("context", ENCODE_CONTEXT)
                .addAnnotation(
                    com.squareup.kotlinpoet.AnnotationSpec
                        .builder(Suppress::class)
                        .addMember("%S", "UNCHECKED_CAST")
                        .build(),
                ).addCode(ctxEncodeBody.build())
                .build(),
        )

        // Generate peekFrameSize for payload codecs too
        val payloadPeekResult = PeekFrameSizeEmitter.generate(fields)
        if (payloadPeekResult != null) {
            objectBuilder.addProperty(PeekFrameSizeEmitter.buildMinHeaderProperty(payloadPeekResult))
            for (fn in PeekFrameSizeEmitter.buildFunctions(payloadPeekResult)) {
                objectBuilder.addFunction(fn)
            }
        }

        val fileBuilder =
            FileSpec
                .builder(packageName, codecName)
                .addType(objectBuilder.build())
        addExtensionImports(fileBuilder, fields)
        return fileBuilder.build()
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
                    canGenerate = false
                    break
                }
            } else {
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
            builder.addStatement("return %L", fixedTotal)
        } else {
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
}
