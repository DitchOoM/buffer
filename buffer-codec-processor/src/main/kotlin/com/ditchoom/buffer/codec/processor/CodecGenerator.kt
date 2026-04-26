package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
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
        direction: CodecDirection = CodecDirection.Bidirectional,
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

        val isObject = classDeclaration.classKind == ClassKind.OBJECT
        val fileSpec =
            if (hasPayload) {
                buildPayloadCodecFile(packageName, classTypeName, codecName, fields, batches, direction)
            } else {
                buildCodecFile(packageName, classTypeName, codecName, fields, batches, isObject, direction)
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
        isObject: Boolean = false,
        direction: CodecDirection = CodecDirection.Bidirectional,
    ): FileSpec {
        val isBidirectional = direction == CodecDirection.Bidirectional
        val canDecode = direction != CodecDirection.EncodeOnly
        val canEncode = direction != CodecDirection.DecodeOnly

        val objectBuilder = TypeSpec.objectBuilder(codecName)

        // Superinterface depends on direction
        when (direction) {
            CodecDirection.Bidirectional ->
                objectBuilder.addSuperinterface(CODEC.parameterizedBy(classTypeName))
            CodecDirection.DecodeOnly ->
                objectBuilder.addSuperinterface(DECODER.parameterizedBy(classTypeName))
            CodecDirection.EncodeOnly ->
                objectBuilder.addSuperinterface(ENCODER.parameterizedBy(classTypeName))
        }

        // decode(buffer, context) — context-aware decode
        if (canDecode) {
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
            if (isObject) {
                decodeBody.addStatement("return %T", classTypeName)
            } else {
                val fieldNames = fields.joinToString(", ") { it.name }
                decodeBody.addStatement("return %T(%L)", classTypeName, fieldNames)
            }

            val decodeCtxBuilder =
                FunSpec
                    .builder("decode")
                    .addParameter("buffer", READ_BUFFER)
                    .addParameter("context", DECODE_CONTEXT)
                    .returns(classTypeName)
                    .addCode(decodeBody.build())
            if (isBidirectional) decodeCtxBuilder.addModifiers(KModifier.OVERRIDE)
            objectBuilder.addFunction(decodeCtxBuilder.build())

            // For DecodeOnly: override Decoder<T>.decode(buffer) to delegate to context version
            if (direction == CodecDirection.DecodeOnly) {
                objectBuilder.addFunction(
                    FunSpec
                        .builder("decode")
                        .addModifiers(KModifier.OVERRIDE)
                        .addParameter("buffer", READ_BUFFER)
                        .returns(classTypeName)
                        .addStatement("return decode(buffer, %T.Empty)", DECODE_CONTEXT)
                        .build(),
                )
            }
        }

        // encode(buffer, value, context) — context-aware encode
        if (canEncode) {
            val encodeBody = CodeBlock.builder()
            val whenRemainingTail = mutableListOf<FieldInfo>()
            for (field in fields) {
                if (field.condition is FieldCondition.WhenRemaining) {
                    whenRemainingTail.add(field)
                } else {
                    addFieldWrite(encodeBody, field, "value.${field.name}", withContext = true)
                }
            }
            if (whenRemainingTail.isNotEmpty()) {
                emitWhenRemainingEncode(encodeBody, whenRemainingTail, withContext = true)
            }

            val encodeCtxBuilder =
                FunSpec
                    .builder("encode")
                    .addParameter("buffer", WRITE_BUFFER)
                    .addParameter("value", classTypeName)
                    .addParameter("context", ENCODE_CONTEXT)
                    .addCode(encodeBody.build())
            if (isBidirectional) encodeCtxBuilder.addModifiers(KModifier.OVERRIDE)
            objectBuilder.addFunction(encodeCtxBuilder.build())

            // For EncodeOnly: override Encoder<T>.encode(buffer, value) to delegate to context version
            if (direction == CodecDirection.EncodeOnly) {
                objectBuilder.addFunction(
                    FunSpec
                        .builder("encode")
                        .addModifiers(KModifier.OVERRIDE)
                        .addParameter("buffer", WRITE_BUFFER)
                        .addParameter("value", classTypeName)
                        .addStatement("encode(buffer, value, %T.Empty)", ENCODE_CONTEXT)
                        .build(),
                )
            }

            // When the variant carries its discriminator as a normal field
            // (e.g. ControlPacketV5.Publish has `header: MqttFixedHeader` matching
            // its @DispatchOn type), emit a sibling `encodeBody(...)` that writes
            // every field EXCEPT the discriminator. Lets callers write byte1 +
            // length-prefix themselves and delegate the body to the codec, instead
            // of hand-rolling the body to avoid double-writing the discriminator.
            val hasDiscriminatorField = fields.any { it.strategy is FieldReadStrategy.DiscriminatorField }
            if (hasDiscriminatorField) {
                val nonDiscriminatorFields = fields.filter { it.strategy !is FieldReadStrategy.DiscriminatorField }
                val bodyEncodeCode = CodeBlock.builder()
                val bodyWhenRemainingTail = mutableListOf<FieldInfo>()
                for (field in nonDiscriminatorFields) {
                    if (field.condition is FieldCondition.WhenRemaining) {
                        bodyWhenRemainingTail.add(field)
                    } else {
                        addFieldWrite(bodyEncodeCode, field, "value.${field.name}", withContext = true)
                    }
                }
                if (bodyWhenRemainingTail.isNotEmpty()) {
                    emitWhenRemainingEncode(bodyEncodeCode, bodyWhenRemainingTail, withContext = true)
                }
                objectBuilder.addFunction(
                    FunSpec
                        .builder("encodeBody")
                        .addParameter("buffer", WRITE_BUFFER)
                        .addParameter("value", classTypeName)
                        .addParameter(
                            com.squareup.kotlinpoet.ParameterSpec
                                .builder("context", ENCODE_CONTEXT)
                                .defaultValue("%T.Empty", ENCODE_CONTEXT)
                                .build(),
                        ).addCode(bodyEncodeCode.build())
                        .build(),
                )
            }
        }

        // Generate wireSize(value, context) + wireSize(value) delegate. The context-aware
        // overload is the work function so nested `Codec.wireSize(nested, context)` calls
        // can flow context through to payload-bearing sealed dispatchers (e.g. MqttPropertyCodec
        // with CorrelationData<D> variants reading SizeKey lambdas from the context).
        if (canEncode) {
            val wireSizeBody = CodeBlock.builder()
            wireSizeBody.addStatement("var _size = 0")
            val wireSizeWhenRemainingTail = mutableListOf<FieldInfo>()
            for (field in fields) {
                if (field.condition is FieldCondition.WhenRemaining) {
                    wireSizeWhenRemainingTail.add(field)
                } else {
                    addFieldWireSize(wireSizeBody, field, "value.${field.name}", withContext = true)
                }
            }
            if (wireSizeWhenRemainingTail.isNotEmpty()) {
                emitWhenRemainingWireSize(wireSizeBody, wireSizeWhenRemainingTail, withContext = true)
            }
            wireSizeBody.addStatement("return _size")

            // Sibling `wireSizeBody(value, context)` excludes the discriminator from the sum.
            // Pairs with encodeBody — both skip the discriminator field for callers that
            // write the discriminator (and any framing) themselves. `context` defaults to
            // EncodeContext.Empty so callers without payload-bearing nested codecs need not
            // construct a context.
            val hasDiscriminatorField = fields.any { it.strategy is FieldReadStrategy.DiscriminatorField }
            if (hasDiscriminatorField) {
                val nonDiscriminatorFields = fields.filter { it.strategy !is FieldReadStrategy.DiscriminatorField }
                val bodyWireSizeCode = CodeBlock.builder()
                bodyWireSizeCode.addStatement("var _size = 0")
                val bodyWireSizeWhenRemainingTail = mutableListOf<FieldInfo>()
                for (field in nonDiscriminatorFields) {
                    if (field.condition is FieldCondition.WhenRemaining) {
                        bodyWireSizeWhenRemainingTail.add(field)
                    } else {
                        addFieldWireSize(bodyWireSizeCode, field, "value.${field.name}", withContext = true)
                    }
                }
                if (bodyWireSizeWhenRemainingTail.isNotEmpty()) {
                    emitWhenRemainingWireSize(bodyWireSizeCode, bodyWireSizeWhenRemainingTail, withContext = true)
                }
                bodyWireSizeCode.addStatement("return _size")
                objectBuilder.addFunction(
                    FunSpec
                        .builder("wireSizeBody")
                        .addParameter("value", classTypeName)
                        .addParameter(
                            com.squareup.kotlinpoet.ParameterSpec
                                .builder("context", ENCODE_CONTEXT)
                                .defaultValue("%T.Empty", ENCODE_CONTEXT)
                                .build(),
                        ).returns(INT)
                        .addCode(bodyWireSizeCode.build())
                        .build(),
                )
            }

            objectBuilder.addFunction(
                FunSpec
                    .builder("wireSize")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("value", classTypeName)
                    .addParameter("context", ENCODE_CONTEXT)
                    .returns(Int::class)
                    .addCode(wireSizeBody.build())
                    .build(),
            )
            // Delegate the context-free Encoder.wireSize(value) to the context-aware variant
            // with EncodeContext.Empty so encodeToBuffer (which has no context) still works.
            objectBuilder.addFunction(
                FunSpec
                    .builder("wireSize")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("value", classTypeName)
                    .returns(Int::class)
                    .addStatement("return wireSize(value, %T.Empty)", ENCODE_CONTEXT)
                    .build(),
            )
        }

        // Generate peekFrameSize if possible (only for decode-capable codecs)
        if (canDecode) {
            val peekResult = PeekFrameSizeEmitter.generate(fields)
            if (peekResult != null) {
                objectBuilder.addProperty(PeekFrameSizeEmitter.buildMinHeaderProperty(peekResult))
                for (fn in PeekFrameSizeEmitter.buildFunctions(peekResult, implementsCodec = isBidirectional)) {
                    objectBuilder.addFunction(fn)
                }
            }
        }

        val fileBuilder =
            fileSpecBuilder(packageName, codecName)
                .addType(objectBuilder.build())
        addExtensionImports(fileBuilder, fields, packageName)
        return fileBuilder.build()
    }

    // ──────────────────────── Payload codec ────────────────────────

    private fun buildPayloadCodecFile(
        packageName: String,
        classTypeName: ClassName,
        codecName: String,
        fields: List<FieldInfo>,
        batches: List<CodegenItem>,
        direction: CodecDirection = CodecDirection.Bidirectional,
    ): FileSpec {
        val className = classTypeName.simpleName
        val payloadFields = fields.filter { it.strategy is FieldReadStrategy.PayloadField }
        val payloadTypeParams =
            payloadFields.map { (it.strategy as FieldReadStrategy.PayloadField).typeParamName }.distinct()
        val typeParamList = payloadTypeParams.joinToString(", ")
        val contextName = "${classTypeName.simpleNames.joinToString("")}Context"
        val typeVariables = payloadTypeParams.map { TypeVariableName(it) }

        // Build decode function. `context` is always accepted (default `DecodeContext.Empty`)
        // so non-payload field reads can thread context through nested codec calls — the
        // dispatcher relies on this for sealed @Payload variants whose properties carry
        // their own context-keyed lambdas (e.g. CorrelationData).
        val decodeBuilder =
            FunSpec
                .builder("decode")
                .addParameter("buffer", READ_BUFFER)
                .addParameter(
                    com.squareup.kotlinpoet.ParameterSpec
                        .builder("context", DECODE_CONTEXT)
                        .defaultValue("%T.Empty", DECODE_CONTEXT)
                        .build(),
                )

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
                                .unnamed(READ_BUFFER),
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
                        addFieldRead(decodeBody, item.field, withContext = true)
                    }
                }
            }
        }

        // Phase 2: create context from non-payload fields
        val nonPayloadFields = fields.filter { it.strategy !is FieldReadStrategy.PayloadField }
        if (nonPayloadFields.isEmpty()) {
            // Context is an object singleton — reference without parentheses
            decodeBody.addStatement("val _ctx = %L", contextName)
        } else {
            decodeBody.addStatement(
                "val _ctx = %L(%L)",
                contextName,
                nonPayloadFields.joinToString(", ") { it.name },
            )
        }

        // Phase 3: decode payload fields using stored slice + lambdas. The lambda receives
        // the slice directly — no `PayloadReader` wrapping or release ceremony. Slice
        // lifetime is the user's callback scope; for sources backed by a `BufferPool`
        // (StreamProcessor), that scope is locked by `readBufferScoped`.
        for (pf in payloadFields) {
            val rawVar = "_raw_${pf.name}"
            val condition = pf.condition
            if (condition != null || pf.isNullable) {
                decodeBody.beginControlFlow("val %L = if (%L != null)", pf.name, rawVar)
                decodeBody.addStatement(
                    "_ctx.decode%L(%L)",
                    capitalizeFirst(pf.name),
                    rawVar,
                )
                decodeBody.nextControlFlow("else")
                decodeBody.addStatement("null")
                decodeBody.endControlFlow()
            } else {
                decodeBody.addStatement(
                    "val %L = _ctx.decode%L(%L)",
                    pf.name,
                    capitalizeFirst(pf.name),
                    rawVar,
                )
            }
        }

        // Phase 4: return constructor call
        val fieldNames = fields.joinToString(", ") { it.name }
        decodeBody.addStatement("return %T(%L)", classTypeName, fieldNames)

        decodeBuilder.addCode(decodeBody.build())

        // Build encode function. `context` is always accepted (default `EncodeContext.Empty`)
        // so non-payload field writes can thread context into nested codec calls.
        val encodeBuilder =
            FunSpec
                .builder("encode")
                .addParameter("buffer", WRITE_BUFFER)
                .addParameter("value", returnType)
                .addParameter(
                    com.squareup.kotlinpoet.ParameterSpec
                        .builder("context", ENCODE_CONTEXT)
                        .defaultValue("%T.Empty", ENCODE_CONTEXT)
                        .build(),
                )

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
        val payloadWhenRemainingTail = mutableListOf<FieldInfo>()
        for (field in fields) {
            if (field.condition is FieldCondition.WhenRemaining) {
                payloadWhenRemainingTail.add(field)
            } else {
                val strategy = field.strategy
                if (strategy is FieldReadStrategy.PayloadField) {
                    addPayloadWrite(encodeBody, field)
                } else {
                    addFieldWrite(encodeBody, field, "value.${field.name}", withContext = true)
                }
            }
        }
        if (payloadWhenRemainingTail.isNotEmpty()) {
            emitWhenRemainingEncode(encodeBody, payloadWhenRemainingTail, withContext = true)
        }
        encodeBuilder.addCode(encodeBody.build())

        // Build wireSize<P>(value, context, payloadSize: (P) -> Int) — Convention 1 sizing.
        // Mirrors encode field-by-field; payload field contributes via the payloadSize
        // lambda the caller supplies (and the matching length-prefix overhead). Context
        // defaults to EncodeContext.Empty and is threaded into nested codec calls so
        // payload-bearing sealed dispatchers (e.g. MqttPropertyCodec) can read their
        // SizeKey lambdas from the same context the caller built for encode.
        val wireSizeBuilder =
            FunSpec
                .builder("wireSize")
                .addParameter("value", returnType)
                .addParameter(
                    com.squareup.kotlinpoet.ParameterSpec
                        .builder("context", ENCODE_CONTEXT)
                        .defaultValue("%T.Empty", ENCODE_CONTEXT)
                        .build(),
                ).returns(Int::class)

        for (tv in typeVariables) {
            wireSizeBuilder.addTypeVariable(tv)
        }

        for (pf in payloadFields) {
            val strategy = pf.strategy as FieldReadStrategy.PayloadField
            val tpName = TypeVariableName(strategy.typeParamName)
            val sizeLambdaType =
                LambdaTypeName.get(
                    parameters =
                        listOf(
                            com.squareup.kotlinpoet.ParameterSpec
                                .unnamed(tpName),
                        ),
                    returnType = INT,
                )
            wireSizeBuilder.addParameter("size${capitalizeFirst(pf.name)}", sizeLambdaType)
        }

        val wireSizeBody = CodeBlock.builder()
        wireSizeBody.addStatement("var _size = 0")
        val wireSizePayloadWhenRemainingTail = mutableListOf<FieldInfo>()
        for (field in fields) {
            if (field.condition is FieldCondition.WhenRemaining) {
                wireSizePayloadWhenRemainingTail.add(field)
            } else if (field.strategy is FieldReadStrategy.PayloadField) {
                addPayloadFieldWireSize(wireSizeBody, field)
            } else {
                addFieldWireSize(wireSizeBody, field, "value.${field.name}", withContext = true)
            }
        }
        if (wireSizePayloadWhenRemainingTail.isNotEmpty()) {
            // For now, payload @WhenRemaining fields aren't expected — fall back to
            // standard cascading null check using the non-payload helper, which assumes
            // each tail field has a wireSizeExpression. Payload fields in the tail would
            // need their own cascading helper; not exercised today.
            emitWhenRemainingWireSize(wireSizeBody, wireSizePayloadWhenRemainingTail, withContext = true)
        }
        wireSizeBody.addStatement("return _size")
        wireSizeBuilder.addCode(wireSizeBody.build())

        // When the variant carries its discriminator as a normal field, also emit
        // encodeBody / wireSizeBody that skip the discriminator. Same rationale as
        // the standard-codec path — lets callers (e.g. ControlPacketV5.Publish)
        // delegate body encoding to the codec while writing byte1 + length-prefix
        // themselves, instead of hand-rolling the body.
        val payloadCodecHasDiscriminator =
            fields.any { it.strategy is FieldReadStrategy.DiscriminatorField }
        val objectBuilder =
            TypeSpec
                .objectBuilder(codecName)
                .addFunction(decodeBuilder.build())
                .addFunction(encodeBuilder.build())
                .addFunction(wireSizeBuilder.build())

        if (payloadCodecHasDiscriminator) {
            val nonDiscriminatorFields = fields.filter { it.strategy !is FieldReadStrategy.DiscriminatorField }

            val bodyEncodeBuilder =
                FunSpec
                    .builder("encodeBody")
                    .addParameter("buffer", WRITE_BUFFER)
                    .addParameter("value", returnType)
                    .addParameter(
                        com.squareup.kotlinpoet.ParameterSpec
                            .builder("context", ENCODE_CONTEXT)
                            .defaultValue("%T.Empty", ENCODE_CONTEXT)
                            .build(),
                    )
            for (tv in typeVariables) bodyEncodeBuilder.addTypeVariable(tv)
            for (pf in payloadFields) {
                val strategy = pf.strategy as FieldReadStrategy.PayloadField
                val tpName = TypeVariableName(strategy.typeParamName)
                val encodeLambdaType =
                    LambdaTypeName.get(
                        parameters =
                            listOf(
                                com.squareup.kotlinpoet.ParameterSpec.unnamed(WRITE_BUFFER),
                                com.squareup.kotlinpoet.ParameterSpec.unnamed(tpName),
                            ),
                        returnType = UNIT,
                    )
                bodyEncodeBuilder.addParameter("encode${capitalizeFirst(pf.name)}", encodeLambdaType)
            }
            val bodyEncodeCode = CodeBlock.builder()
            val bodyEncodeWhenRemainingTail = mutableListOf<FieldInfo>()
            for (field in nonDiscriminatorFields) {
                if (field.condition is FieldCondition.WhenRemaining) {
                    bodyEncodeWhenRemainingTail.add(field)
                } else if (field.strategy is FieldReadStrategy.PayloadField) {
                    addPayloadWrite(bodyEncodeCode, field)
                } else {
                    addFieldWrite(bodyEncodeCode, field, "value.${field.name}", withContext = true)
                }
            }
            if (bodyEncodeWhenRemainingTail.isNotEmpty()) {
                emitWhenRemainingEncode(bodyEncodeCode, bodyEncodeWhenRemainingTail, withContext = true)
            }
            bodyEncodeBuilder.addCode(bodyEncodeCode.build())
            objectBuilder.addFunction(bodyEncodeBuilder.build())

            val bodyWireSizeBuilder =
                FunSpec
                    .builder("wireSizeBody")
                    .addParameter("value", returnType)
                    .addParameter(
                        com.squareup.kotlinpoet.ParameterSpec
                            .builder("context", ENCODE_CONTEXT)
                            .defaultValue("%T.Empty", ENCODE_CONTEXT)
                            .build(),
                    ).returns(INT)
            for (tv in typeVariables) bodyWireSizeBuilder.addTypeVariable(tv)
            for (pf in payloadFields) {
                val strategy = pf.strategy as FieldReadStrategy.PayloadField
                val tpName = TypeVariableName(strategy.typeParamName)
                val sizeLambdaType =
                    LambdaTypeName.get(
                        parameters = listOf(com.squareup.kotlinpoet.ParameterSpec.unnamed(tpName)),
                        returnType = INT,
                    )
                bodyWireSizeBuilder.addParameter("size${capitalizeFirst(pf.name)}", sizeLambdaType)
            }
            val bodyWireSizeCode = CodeBlock.builder()
            bodyWireSizeCode.addStatement("var _size = 0")
            val bodyWireSizeWhenRemainingTail = mutableListOf<FieldInfo>()
            for (field in nonDiscriminatorFields) {
                if (field.condition is FieldCondition.WhenRemaining) {
                    bodyWireSizeWhenRemainingTail.add(field)
                } else if (field.strategy is FieldReadStrategy.PayloadField) {
                    addPayloadFieldWireSize(bodyWireSizeCode, field)
                } else {
                    addFieldWireSize(bodyWireSizeCode, field, "value.${field.name}", withContext = true)
                }
            }
            if (bodyWireSizeWhenRemainingTail.isNotEmpty()) {
                emitWhenRemainingWireSize(bodyWireSizeCode, bodyWireSizeWhenRemainingTail, withContext = true)
            }
            bodyWireSizeCode.addStatement("return _size")
            bodyWireSizeBuilder.addCode(bodyWireSizeCode.build())
            objectBuilder.addFunction(bodyWireSizeBuilder.build())
        }

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
                                .unnamed(READ_BUFFER),
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

            // Size key: a (P) -> Int lambda for the wireSizeFromContext path. Pairs with
            // EncodeKey so callers that register an encode lambda can also register a
            // matching size lambda, letting nested-dispatch sites compute exact wire size
            // without a scratch-buffer measure-and-copy.
            val sizeLambdaType =
                LambdaTypeName.get(
                    parameters =
                        listOf(
                            com.squareup.kotlinpoet.ParameterSpec
                                .unnamed(ClassName("kotlin", "Any").copy(nullable = true)),
                        ),
                    returnType = INT,
                )
            objectBuilder.addType(
                TypeSpec
                    .objectBuilder("${fieldCapitalized}SizeKey")
                    .addModifiers(KModifier.DATA)
                    .superclass(CODEC_CONTEXT_KEY.parameterizedBy(sizeLambdaType))
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
            "return decode(buffer, context, %L)",
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
            "encode(buffer, value, context, %L)",
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

        // Context-based wireSize: reads size lambdas from EncodeContext, delegates to wireSize
        val ctxWireSizeBody = CodeBlock.builder()
        val sizeLambdaArgs = mutableListOf<String>()
        for (pf in payloadFields) {
            val fieldCapitalized = capitalizeFirst(pf.name)
            val localVar = "_size$fieldCapitalized"
            ctxWireSizeBody.addStatement(
                "val %L = context[%L] ?: error(%S)",
                localVar,
                "${fieldCapitalized}SizeKey",
                "EncodeContext missing $codecName.${fieldCapitalized}SizeKey. " +
                    "Register: ctx.with($codecName.${fieldCapitalized}SizeKey) { v -> ... }",
            )
            sizeLambdaArgs.add(localVar)
        }
        ctxWireSizeBody.addStatement(
            "return wireSize(value, context, %L)",
            sizeLambdaArgs.joinToString(", "),
        )

        objectBuilder.addFunction(
            FunSpec
                .builder("wireSizeFromContext")
                .addParameter("value", starReturnType)
                .addParameter("context", ENCODE_CONTEXT)
                .returns(INT)
                .addAnnotation(
                    com.squareup.kotlinpoet.AnnotationSpec
                        .builder(Suppress::class)
                        .addMember("%S", "UNCHECKED_CAST")
                        .build(),
                ).addCode(ctxWireSizeBody.build())
                .build(),
        )

        // Generate peekFrameSize for payload codecs too
        val payloadPeekResult = PeekFrameSizeEmitter.generate(fields)
        if (payloadPeekResult != null) {
            objectBuilder.addProperty(PeekFrameSizeEmitter.buildMinHeaderProperty(payloadPeekResult))
            for (fn in PeekFrameSizeEmitter.buildFunctions(payloadPeekResult, implementsCodec = false)) {
                objectBuilder.addFunction(fn)
            }
        }

        val fileBuilder =
            fileSpecBuilder(packageName, codecName)
                .addType(objectBuilder.build())
        addExtensionImports(fileBuilder, fields, packageName)
        return fileBuilder.build()
    }

    // ──────────────────────── Utility ────────────────────────

    private fun needsLengthPrefixedImports(fields: List<FieldInfo>): Boolean = fields.any { needsLengthPrefixedImport(it.strategy) }

    private fun needsLengthPrefixedImport(strategy: FieldReadStrategy): Boolean =
        when (strategy) {
            is FieldReadStrategy.LengthPrefixedStringField -> strategy.kind is LengthPrefixKind.Short
            is FieldReadStrategy.ValueClassField -> needsLengthPrefixedImport(strategy.innerStrategy)
            else -> false
        }

    private fun addExtensionImports(
        fileBuilder: FileSpec.Builder,
        fields: List<FieldInfo>,
        currentPackage: String,
    ) {
        if (needsLengthPrefixedImports(fields)) {
            fileBuilder.addImport("com.ditchoom.buffer", "readLengthPrefixedUtf8String")
            fileBuilder.addImport("com.ditchoom.buffer", "writeLengthPrefixedUtf8String")
        }
        if (needsVarintImports(fields)) {
            fileBuilder.addImport("com.ditchoom.buffer", "readVariableByteInteger")
            fileBuilder.addImport("com.ditchoom.buffer", "writeVariableByteInteger")
            fileBuilder.addImport("com.ditchoom.buffer", "writeVariableByteIntegerLengthPrefixed")
            fileBuilder.addImport("com.ditchoom.buffer", "variableByteSizeInt")
        }
        if (needsUtf8LengthImport(fields)) {
            fileBuilder.addImport("com.ditchoom.buffer", "utf8Length")
        }
        if (fields.any { it.byteOrderOverride != null }) {
            fileBuilder.addImport("com.ditchoom.buffer", "reverseBytes")
        }
        // De-duplicate cross-package codec imports so the same symbol is only added once.
        val crossPackageCodecImports = mutableSetOf<Pair<String, String>>()
        for (field in fields) {
            when (val strategy = field.strategy) {
                is FieldReadStrategy.Custom -> {
                    val d = strategy.descriptor
                    fileBuilder.addImport(d.readFunction.packageName, d.readFunction.functionName)
                    fileBuilder.addImport(d.writeFunction.packageName, d.writeFunction.functionName)
                    d.wireSizeFunction?.let { fileBuilder.addImport(it.packageName, it.functionName) }
                }
                is FieldReadStrategy.CollectionField -> {
                    if (strategy.elementCodecPackage.isNotEmpty() && strategy.elementCodecPackage != currentPackage) {
                        crossPackageCodecImports += strategy.elementCodecPackage to strategy.elementCodecName
                        // Element type is in the same package as its generated codec; the
                        // generated `buildList<T>` references the element type directly,
                        // so it needs its own import. Use the outermost enclosing simple
                        // name so a `Foo.Bar` nested type imports `Foo`.
                        val outerSimple = strategy.elementTypeSimpleName.substringBefore('.')
                        crossPackageCodecImports += strategy.elementCodecPackage to outerSimple
                    }
                }
                else -> {}
            }
        }
        for ((pkg, name) in crossPackageCodecImports) {
            fileBuilder.addImport(pkg, name)
        }
    }

    private fun needsUtf8LengthImport(fields: List<FieldInfo>): Boolean = fields.any { needsUtf8LengthImport(it.strategy) }

    private fun needsUtf8LengthImport(strategy: FieldReadStrategy): Boolean =
        when (strategy) {
            is FieldReadStrategy.LengthPrefixedStringField -> true
            is FieldReadStrategy.RemainingBytesStringField -> true
            is FieldReadStrategy.LengthFromStringField -> true
            is FieldReadStrategy.ValueClassField -> needsUtf8LengthImport(strategy.innerStrategy)
            else -> false
        }

    private fun needsVarintImports(fields: List<FieldInfo>): Boolean = fields.any { needsVarintImport(it.strategy) }

    private fun needsVarintImport(strategy: FieldReadStrategy): Boolean =
        when (strategy) {
            is FieldReadStrategy.LengthPrefixedStringField -> strategy.kind is LengthPrefixKind.Varint
            is FieldReadStrategy.NestedMessageWithLengthField ->
                (strategy.lengthKind as? LengthKind.Prefixed)?.kind is LengthPrefixKind.Varint
            is FieldReadStrategy.UseCodecField ->
                (strategy.lengthKind as? LengthKind.Prefixed)?.kind is LengthPrefixKind.Varint
            is FieldReadStrategy.CollectionField ->
                (strategy.lengthKind as? LengthKind.Prefixed)?.kind is LengthPrefixKind.Varint
            is FieldReadStrategy.PayloadField ->
                (strategy.lengthKind as? LengthKind.Prefixed)?.kind is LengthPrefixKind.Varint
            is FieldReadStrategy.ValueClassField -> needsVarintImport(strategy.innerStrategy)
            else -> false
        }
}
