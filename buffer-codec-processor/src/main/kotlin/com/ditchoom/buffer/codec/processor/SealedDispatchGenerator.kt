package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.ksp.writeTo

class SealedDispatchGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {
    private data class DispatchResult(
        val functions: List<FunSpec>,
        val implementsCodec: Boolean,
    )

    /** @PacketType metadata: [value] for decode match, [wire] for encode byte. */
    private data class PacketTypeInfo(
        val value: Int,
        val wire: Int,
        val subclass: KSClassDeclaration,
    )

    /**
     * Generates the encode statement for writing the discriminator byte(s).
     * Without @DispatchOn: writes a single byte.
     * With @DispatchOn: constructs the discriminator value class and encodes via its codec.
     */
    private fun addWireWrite(
        code: CodeBlock.Builder,
        wire: Int,
        dispatchOnInfo: DispatchOnInfo?,
    ) {
        if (dispatchOnInfo != null) {
            val conversion = wireConversion(dispatchOnInfo.innerTypeName, wire)
            code.addStatement(
                "%T.encode(buffer, %T($conversion))",
                ClassName(dispatchOnInfo.poetClassName.packageName, dispatchOnInfo.codecName),
                dispatchOnInfo.poetClassName,
            )
        } else {
            code.addStatement("buffer.writeByte($wire.toByte())")
        }
    }

    private fun wireConversion(
        innerTypeName: String,
        wire: Int,
    ): String =
        when (innerTypeName) {
            "UByte" -> "$wire.toUByte()"
            "Byte" -> "$wire.toByte()"
            "UShort" -> "$wire.toUShort()"
            "Short" -> "$wire.toShort()"
            "UInt" -> "$wire.toUInt()"
            "Int" -> "$wire"
            "ULong" -> "$wire.toULong()"
            "Long" -> "$wire.toLong()"
            else -> "$wire.toUByte()" // fallback
        }

    /** Returns the valid range for a wire value given the discriminator's inner type, or null if any Int fits. */
    private fun wireRange(innerTypeName: String): LongRange? =
        when (innerTypeName) {
            "UByte" -> 0L..255L
            "Byte" -> -128L..127L
            "UShort" -> 0L..65535L
            "Short" -> -32768L..32767L
            "UInt" -> 0L..4294967295L
            // Int, Long, ULong — any @PacketType Int value fits
            else -> null
        }

    fun generate(
        sealedInterface: KSClassDeclaration,
        subclasses: List<KSClassDeclaration>,
        variantPayloadInfos: List<SealedVariantPayloadInfo> = emptyList(),
        dispatchOnInfo: DispatchOnInfo? = null,
        variantsHandlingDiscriminator: Set<String> = emptySet(),
        variantsSupportingPeek: Set<String> = emptySet(),
        direction: CodecDirection = CodecDirection.Bidirectional,
    ) {
        val interfaceName = sealedInterface.simpleName.asString()
        val packageName = sealedInterface.packageName.asString()
        val codecName = "${interfaceName}Codec"
        val interfaceTypeName = ClassName(packageName, interfaceName)

        // Collect @PacketType values
        val variants = mutableListOf<PacketTypeInfo>()
        for (subclass in subclasses) {
            val packetType =
                subclass.annotations.find {
                    it.qualifiedName() == "com.ditchoom.buffer.codec.annotations.PacketType"
                }
            if (packetType == null) {
                logger.error(
                    "Sealed subclass '${subclass.simpleName.asString()}' is missing @PacketType. " +
                        "Each subclass of sealed @ProtocolMessage '$interfaceName' needs " +
                        "@PacketType(N) to specify its 1-byte type discriminator (0-255).",
                    subclass,
                )
                return
            }
            val value = packetType.arguments.first().value as Int
            val wireArg = packetType.arguments.getOrNull(1)?.value as? Int ?: -1
            val wire = if (wireArg == -1) value else wireArg
            // Without @DispatchOn, dispatch reads/writes a single byte (0-255)
            // With @DispatchOn, the discriminator type defines the width — validate wire fits
            if (dispatchOnInfo == null) {
                if (value < 0 || value > 255) {
                    logger.error(
                        "@PacketType($value) on '${subclass.simpleName.asString()}' is out of range. " +
                            "The type discriminator is encoded as a single byte, so valid values are 0-255. " +
                            "Use @DispatchOn for multi-byte discriminators.",
                        subclass,
                    )
                    return
                }
            } else {
                val wireRange = wireRange(dispatchOnInfo.innerTypeName)
                if (wireRange != null && wire.toLong() !in wireRange) {
                    logger.error(
                        "@PacketType(wire=$wire) on '${subclass.simpleName.asString()}' overflows " +
                            "the discriminator's ${dispatchOnInfo.innerTypeName} type (valid range: " +
                            "${wireRange.first}..${wireRange.last}). The wire value is converted to " +
                            "${dispatchOnInfo.innerTypeName} during encode, so values outside this range " +
                            "silently wrap and will not round-trip correctly.",
                        subclass,
                    )
                    return
                }
            }
            val existing = variants.find { it.value == value }
            if (existing != null) {
                logger.error(
                    "@PacketType($value) is used by both '${existing.subclass.simpleName.asString()}' " +
                        "and '${subclass.simpleName.asString()}'. " +
                        "Each subclass needs a unique discriminator so the codec can identify which type to decode.",
                    subclass,
                )
                return
            }
            variants.add(PacketTypeInfo(value, wire, subclass))
        }

        // Sealed dispatch is aggregating: it depends on the sealed interface AND all subclass files
        val sourceFiles =
            buildList {
                sealedInterface.containingFile?.let { add(it) }
                for (sub in subclasses) {
                    sub.containingFile?.let { add(it) }
                }
            }
        val dependencies = Dependencies(aggregating = true, sources = sourceFiles.toTypedArray())

        val hasAnyPayload = variantPayloadInfos.any { it.payloadFields.isNotEmpty() }
        val isBidirectional = direction == CodecDirection.Bidirectional
        val canDecode = direction != CodecDirection.EncodeOnly
        val canEncode = direction != CodecDirection.DecodeOnly

        val result =
            if (hasAnyPayload) {
                buildPayloadDispatch(
                    packageName,
                    interfaceTypeName,
                    variants,
                    variantPayloadInfos,
                    dispatchOnInfo,
                    variantsHandlingDiscriminator,
                    direction,
                )
            } else {
                buildSimpleDispatch(interfaceTypeName, variants, dispatchOnInfo, variantsHandlingDiscriminator, direction)
            }

        val objectBuilder = TypeSpec.objectBuilder(codecName)
        if (result.implementsCodec) {
            when (direction) {
                CodecDirection.Bidirectional ->
                    objectBuilder.addSuperinterface(CODEC.parameterizedBy(interfaceTypeName))
                CodecDirection.DecodeOnly ->
                    objectBuilder.addSuperinterface(DECODER.parameterizedBy(interfaceTypeName))
                CodecDirection.EncodeOnly ->
                    objectBuilder.addSuperinterface(ENCODER.parameterizedBy(interfaceTypeName))
            }
        }

        // If @DispatchOn is used, generate a context key for the discriminator
        if (dispatchOnInfo != null) {
            objectBuilder.addType(
                TypeSpec
                    .objectBuilder("DiscriminatorKey")
                    .addModifiers(KModifier.DATA)
                    .superclass(CODEC_CONTEXT_KEY.parameterizedBy(dispatchOnInfo.poetClassName))
                    .build(),
            )
        }

        for (fn in result.functions) {
            objectBuilder.addFunction(fn)
        }

        // Generate peekFrameSize for sealed dispatch.
        // - With bodyLength framing, total frame size derives from the length prefix alone,
        //   so per-variant peek delegation is not required.
        // - Without bodyLength, ALL variants must support peek — otherwise we can't compute
        //   the full frame size without consuming bytes.
        if (canDecode) {
            val needsVariantPeek = dispatchOnInfo?.hasBodyLength != true
            val allVariantsSupportPeek =
                !needsVariantPeek ||
                    variants.all { v ->
                        val name = v.subclass.qualifiedName?.asString() ?: v.subclass.simpleName.asString()
                        name in variantsSupportingPeek
                    }
            val sealedPeek = if (allVariantsSupportPeek) buildSealedPeekFrameSize(variants, dispatchOnInfo) else null
            if (sealedPeek != null) {
                objectBuilder.addProperty(sealedPeek.minHeaderProperty)
                objectBuilder.addFunction(sealedPeek.syncFun)
                objectBuilder.addFunction(sealedPeek.suspendFun)
            }
        }

        val fileBuilder = fileSpecBuilder(packageName, codecName).addType(objectBuilder.build())
        // Varint body framing emits calls into buffer extensions — import them on the
        // generated dispatcher file so the call sites resolve.
        if (dispatchOnInfo?.bodyFraming is BodyFraming.WithLength) {
            fileBuilder.addImport("com.ditchoom.buffer", "readVariableByteInteger")
            fileBuilder.addImport("com.ditchoom.buffer", "writeVariableByteIntegerLengthPrefixed")
        }
        fileBuilder.build().writeTo(codeGenerator, dependencies)
    }

    private fun PacketTypeInfo.selfEncodesDiscriminator(variantsHandlingDiscriminator: Set<String>): Boolean {
        val qn = subclass.qualifiedName?.asString() ?: return false
        return qn in variantsHandlingDiscriminator
    }

    /** No payload variants — generates a standard Codec<T> implementation with context forwarding. */
    private fun buildSimpleDispatch(
        interfaceTypeName: ClassName,
        variants: List<PacketTypeInfo>,
        dispatchOnInfo: DispatchOnInfo? = null,
        variantsHandlingDiscriminator: Set<String> = emptySet(),
        direction: CodecDirection = CodecDirection.Bidirectional,
    ): DispatchResult {
        val isBidirectional = direction == CodecDirection.Bidirectional
        val canDecode = direction != CodecDirection.EncodeOnly
        val canEncode = direction != CodecDirection.DecodeOnly
        val functions = mutableListOf<FunSpec>()

        // decode(buffer, context) — context-aware decode
        if (canDecode) {
            val decodeCtxBody = CodeBlock.builder()
            if (dispatchOnInfo != null) {
                decodeCtxBody.addStatement(
                    "val _discriminator = %T.decode(buffer)",
                    ClassName(dispatchOnInfo.poetClassName.packageName, dispatchOnInfo.codecName),
                )
                decodeCtxBody.addStatement(
                    "val type = _discriminator.%L",
                    dispatchOnInfo.dispatchProperty,
                )
                decodeCtxBody.addStatement(
                    "val _ctx = context.with(DiscriminatorKey, _discriminator)",
                )
            } else {
                decodeCtxBody.addStatement("val type = buffer.readByte().toInt() and 0xFF")
            }
            emitBodyLengthDecodePrelude(decodeCtxBody, dispatchOnInfo, "context")
            val bodyVar = bodyVarName(dispatchOnInfo)
            val ctxVar = if (dispatchOnInfo != null) "_ctx" else "context"
            val framed = dispatchOnInfo?.hasBodyLength == true
            if (framed) {
                decodeCtxBody.beginControlFlow("val _result = when (type)")
            } else {
                decodeCtxBody.beginControlFlow("return when (type)")
            }
            for (v in variants) {
                decodeCtxBody.addStatement("${v.value} -> ${v.subclass.codecName()}.decode($bodyVar, $ctxVar)")
            }
            decodeCtxBody
                .addStatement("else -> throw IllegalArgumentException(%P)", "Unknown packet type: \$type")
                .endControlFlow()
            if (framed) {
                emitBodyLengthOverrunCheck(decodeCtxBody)
                decodeCtxBody.addStatement("return _result")
            }

            val decodeCtxBuilder =
                FunSpec
                    .builder("decode")
                    .addParameter("buffer", READ_BUFFER)
                    .addParameter("context", DECODE_CONTEXT)
                    .returns(interfaceTypeName)
                    .addCode(decodeCtxBody.build())
            if (isBidirectional) decodeCtxBuilder.addModifiers(KModifier.OVERRIDE)
            functions.add(decodeCtxBuilder.build())

            // For DecodeOnly: override Decoder<T>.decode(buffer)
            if (direction == CodecDirection.DecodeOnly) {
                functions.add(
                    FunSpec
                        .builder("decode")
                        .addModifiers(KModifier.OVERRIDE)
                        .addParameter("buffer", READ_BUFFER)
                        .returns(interfaceTypeName)
                        .addStatement("return decode(buffer, %T.Empty)", DECODE_CONTEXT)
                        .build(),
                )
            }
        }

        // encode(buffer, value, context) — context-aware encode
        if (canEncode) {
            val encodeCtxBody = CodeBlock.builder().beginControlFlow("when (value)")
            for (v in variants) {
                encodeCtxBody.beginControlFlow("is %T ->", v.subclass.toPoetClassName())
                if (!v.selfEncodesDiscriminator(variantsHandlingDiscriminator)) {
                    addWireWrite(encodeCtxBody, v.wire, dispatchOnInfo)
                }
                emitBodyLengthEncodeWrap(
                    code = encodeCtxBody,
                    info = dispatchOnInfo,
                    encodeStmt = "${v.subclass.codecName()}.encode(buffer, value, context)",
                )
                encodeCtxBody.endControlFlow()
            }
            encodeCtxBody.endControlFlow()

            val encodeCtxBuilder =
                FunSpec
                    .builder("encode")
                    .addParameter("buffer", WRITE_BUFFER)
                    .addParameter("value", interfaceTypeName)
                    .addParameter("context", ENCODE_CONTEXT)
                    .addCode(encodeCtxBody.build())
            if (isBidirectional) encodeCtxBuilder.addModifiers(KModifier.OVERRIDE)
            functions.add(encodeCtxBuilder.build())

            // For EncodeOnly: override Encoder<T>.encode(buffer, value)
            if (direction == CodecDirection.EncodeOnly) {
                functions.add(
                    FunSpec
                        .builder("encode")
                        .addModifiers(KModifier.OVERRIDE)
                        .addParameter("buffer", WRITE_BUFFER)
                        .addParameter("value", interfaceTypeName)
                        .addStatement("encode(buffer, value, %T.Empty)", ENCODE_CONTEXT)
                        .build(),
                )
            }
        }

        return DispatchResult(functions, true)
    }

    /**
     * Some variants have @Payload — generates a dispatch with type params and lambda forwarding.
     * Cannot implement Codec<T> because decode/encode need extra lambda parameters.
     */
    private fun buildPayloadDispatch(
        packageName: String,
        interfaceTypeName: ClassName,
        variants: List<PacketTypeInfo>,
        variantPayloadInfos: List<SealedVariantPayloadInfo>,
        dispatchOnInfo: DispatchOnInfo? = null,
        variantsHandlingDiscriminator: Set<String> = emptySet(),
        direction: CodecDirection = CodecDirection.Bidirectional,
    ): DispatchResult {
        // Collect all distinct type params from all payload variants
        val allTypeParams =
            variantPayloadInfos
                .flatMap { it.payloadFields }
                .map { it.typeParamName }
                .distinct()
        val typeVariables = allTypeParams.map { TypeVariableName(it) }

        // Build payload info lookup
        val payloadBySubclass =
            variantPayloadInfos.associateBy { it.subclass.qualifiedName?.asString() }

        // ── Decode ──
        val decodeBuilder =
            FunSpec
                .builder("decode")
                .addParameter("buffer", READ_BUFFER)
                .returns(interfaceTypeName)

        for (tv in typeVariables) {
            decodeBuilder.addTypeVariable(tv)
        }

        // Add lambda params for each payload field in each payload variant
        for (info in variantPayloadInfos) {
            for (pf in info.payloadFields) {
                val variantName = info.subclass.simpleName.asString()
                val paramName = "decode${variantName}${capitalizeFirst(pf.fieldName)}"
                val tpName = TypeVariableName(pf.typeParamName)
                val contextType = ClassName(packageName, pf.contextClassName)
                val lambdaType =
                    LambdaTypeName.get(
                        receiver = contextType,
                        parameters = listOf(ParameterSpec.unnamed(PAYLOAD_READER)),
                        returnType = tpName,
                    )
                decodeBuilder.addParameter(paramName, lambdaType)
            }
        }

        val decodeBody = CodeBlock.builder()
        if (dispatchOnInfo != null) {
            decodeBody.addStatement(
                "val _discriminator = %T.decode(buffer)",
                ClassName(dispatchOnInfo.poetClassName.packageName, dispatchOnInfo.codecName),
            )
            decodeBody.addStatement("val type = _discriminator.%L", dispatchOnInfo.dispatchProperty)
            decodeBody.addStatement("val _ctx = %T.Empty.with(DiscriminatorKey, _discriminator)", DECODE_CONTEXT)
        } else {
            decodeBody.addStatement("val type = buffer.readByte().toInt() and 0xFF")
        }
        // The non-context decode entrypoint cannot publish to a BodyLengthSink because no
        // caller-supplied context is available. Read the body length and slice; the sink
        // only fires through the `decode(buffer, context)` overload.
        (dispatchOnInfo?.bodyFraming as? BodyFraming.WithLength)?.let { framing ->
            decodeBody.addStatement("val _bodyLen = %L", readLengthExpr(framing.kind))
            decodeBody.addStatement("val _bodySlice = buffer.readBytes(_bodyLen)")
        }
        val bodyVar = bodyVarName(dispatchOnInfo)
        val conv1CtxArg = if (dispatchOnInfo != null) ", _ctx" else ""
        val framed = dispatchOnInfo?.hasBodyLength == true
        if (framed) {
            decodeBody.beginControlFlow("val _result = when (type)")
        } else {
            decodeBody.beginControlFlow("return when (type)")
        }

        for (v in variants) {
            val info = payloadBySubclass[v.subclass.qualifiedName?.asString()]
            val subCodecName = v.subclass.codecName()
            if (info != null && info.payloadFields.isNotEmpty()) {
                val lambdaArgs =
                    info.payloadFields.joinToString(", ") { pf ->
                        "decode${v.subclass.simpleName.asString()}${capitalizeFirst(pf.fieldName)}"
                    }
                val variantHasDiscriminatorField =
                    dispatchOnInfo != null &&
                        v.subclass.primaryConstructor?.parameters?.any { param ->
                            param.type
                                .resolve()
                                .declaration.qualifiedName
                                ?.asString() ==
                                dispatchOnInfo.poetClassName.canonicalName
                        } == true
                val payloadCtxArg = if (variantHasDiscriminatorField) ", _ctx" else ""
                decodeBody.addStatement("${v.value} -> $subCodecName.decode($bodyVar$payloadCtxArg, $lambdaArgs)")
            } else {
                decodeBody.addStatement("${v.value} -> $subCodecName.decode($bodyVar$conv1CtxArg)")
            }
        }
        decodeBody
            .addStatement("else -> throw IllegalArgumentException(%P)", "Unknown packet type: \$type")
            .endControlFlow()
        if (framed) {
            emitBodyLengthOverrunCheck(decodeBody)
            decodeBody.addStatement("return _result")
        }

        decodeBuilder.addCode(decodeBody.build())

        // ── Encode ──
        val encodeBuilder =
            FunSpec
                .builder("encode")
                .addParameter("buffer", WRITE_BUFFER)
                .addParameter("value", interfaceTypeName)

        for (tv in typeVariables) {
            encodeBuilder.addTypeVariable(tv)
        }

        // Add encode lambda params
        for (info in variantPayloadInfos) {
            for (pf in info.payloadFields) {
                val variantName = info.subclass.simpleName.asString()
                val paramName = "encode${variantName}${capitalizeFirst(pf.fieldName)}"
                val tpName = TypeVariableName(pf.typeParamName)
                val encodeLambdaType =
                    LambdaTypeName.get(
                        parameters =
                            listOf(
                                ParameterSpec.unnamed(WRITE_BUFFER),
                                ParameterSpec.unnamed(tpName),
                            ),
                        returnType = UNIT,
                    )
                encodeBuilder.addParameter(paramName, encodeLambdaType)
            }
        }

        val encodeBody = CodeBlock.builder().beginControlFlow("when (value)")
        for (v in variants) {
            val info = payloadBySubclass[v.subclass.qualifiedName?.asString()]
            val subTypeName = v.subclass.toPoetClassName()
            val subCodecName = v.subclass.codecName()

            val skipForVariant = v.selfEncodesDiscriminator(variantsHandlingDiscriminator)
            if (info != null && info.payloadFields.isNotEmpty()) {
                // Star-projected match for generic variant
                val starType = subTypeName.parameterizedBy(info.payloadFields.map { STAR })
                encodeBody.beginControlFlow("is %T ->", starType)
                if (!skipForVariant) addWireWrite(encodeBody, v.wire, dispatchOnInfo)
                // Unchecked cast to typed variant
                val castTypeParams = info.payloadFields.map { TypeVariableName(it.typeParamName) }
                val castType = subTypeName.parameterizedBy(castTypeParams)
                val lambdaArgs =
                    info.payloadFields.joinToString(", ") { pf ->
                        "encode${v.subclass.simpleName.asString()}${capitalizeFirst(pf.fieldName)}"
                    }
                val payloadFraming = dispatchOnInfo?.bodyFraming as? BodyFraming.WithLength
                if (payloadFraming != null && payloadFraming.kind is LengthPrefixKind.Varint) {
                    encodeBody.addStatement(
                        "@Suppress(\"UNCHECKED_CAST\") buffer.writeVariableByteIntegerLengthPrefixed(maxBytes = %L, fieldName = %S) " +
                            "{ buffer -> $subCodecName.encode(buffer, value as %T, $lambdaArgs) }",
                        payloadFraming.kind.maxBytes,
                        "body",
                        castType,
                    )
                } else {
                    // Non-Varint framing for payload variants is not currently exercised; if a
                    // future protocol asks for fixed-width body framing on a generic dispatcher,
                    // emit the placeholder/patch pattern here.
                    encodeBody.addStatement(
                        "@Suppress(\"UNCHECKED_CAST\") $subCodecName.encode(buffer, value as %T, $lambdaArgs)",
                        castType,
                    )
                }
                encodeBody.endControlFlow()
            } else {
                // Non-payload variant: simple dispatch
                encodeBody.beginControlFlow("is %T ->", subTypeName)
                if (!skipForVariant) addWireWrite(encodeBody, v.wire, dispatchOnInfo)
                val variantEncodeStmt =
                    if (dispatchOnInfo != null) {
                        "$subCodecName.encode(buffer, value, ${ENCODE_CONTEXT.canonicalName}.Empty)"
                    } else {
                        "$subCodecName.encode(buffer, value)"
                    }
                emitBodyLengthEncodeWrap(
                    code = encodeBody,
                    info = dispatchOnInfo,
                    encodeStmt = variantEncodeStmt,
                )
                encodeBody.endControlFlow()
            }
        }
        encodeBody.endControlFlow()

        encodeBuilder.addCode(encodeBody.build())

        // ── Context-based overloads (Convention 2: enables nesting) ──
        val isBidirectional = direction == CodecDirection.Bidirectional
        val canDecode = direction != CodecDirection.EncodeOnly
        val canEncode = direction != CodecDirection.DecodeOnly
        val contextFunctions = mutableListOf<FunSpec>()

        // decode(buffer, context) reads lambdas from context for payload variants
        if (canDecode) {
            val decodeCtxBody = CodeBlock.builder()
            if (dispatchOnInfo != null) {
                decodeCtxBody.addStatement(
                    "val _discriminator = %T.decode(buffer)",
                    ClassName(dispatchOnInfo.poetClassName.packageName, dispatchOnInfo.codecName),
                )
                decodeCtxBody.addStatement("val type = _discriminator.%L", dispatchOnInfo.dispatchProperty)
                decodeCtxBody.addStatement("val _ctx = context.with(DiscriminatorKey, _discriminator)")
            } else {
                decodeCtxBody.addStatement("val type = buffer.readByte().toInt() and 0xFF")
            }
            emitBodyLengthDecodePrelude(decodeCtxBody, dispatchOnInfo, "context")
            val bodyVar = bodyVarName(dispatchOnInfo)
            val payloadCtxVar = if (dispatchOnInfo != null) "_ctx" else "context"
            val framed = dispatchOnInfo?.hasBodyLength == true
            if (framed) {
                decodeCtxBody.beginControlFlow("val _result = when (type)")
            } else {
                decodeCtxBody.beginControlFlow("return when (type)")
            }

            for (v in variants) {
                val info = payloadBySubclass[v.subclass.qualifiedName?.asString()]
                val subCodecName = v.subclass.codecName()
                if (info != null && info.payloadFields.isNotEmpty()) {
                    decodeCtxBody.addStatement("${v.value} -> $subCodecName.decodeFromContext($bodyVar, $payloadCtxVar)")
                } else {
                    decodeCtxBody.addStatement("${v.value} -> $subCodecName.decode($bodyVar, $payloadCtxVar)")
                }
            }
            decodeCtxBody
                .addStatement("else -> throw IllegalArgumentException(%P)", "Unknown packet type: \$type")
                .endControlFlow()
            if (framed) {
                emitBodyLengthOverrunCheck(decodeCtxBody)
                decodeCtxBody.addStatement("return _result")
            }

            val decodeCtxBuilder =
                FunSpec
                    .builder("decode")
                    .addParameter("buffer", READ_BUFFER)
                    .addParameter("context", DECODE_CONTEXT)
                    .returns(interfaceTypeName)
                    .addCode(decodeCtxBody.build())
            if (isBidirectional) decodeCtxBuilder.addModifiers(KModifier.OVERRIDE)
            contextFunctions.add(decodeCtxBuilder.build())

            // For DecodeOnly: override Decoder<T>.decode(buffer)
            if (direction == CodecDirection.DecodeOnly) {
                contextFunctions.add(
                    FunSpec
                        .builder("decode")
                        .addModifiers(KModifier.OVERRIDE)
                        .addParameter("buffer", READ_BUFFER)
                        .returns(interfaceTypeName)
                        .addStatement("return decode(buffer, %T.Empty)", DECODE_CONTEXT)
                        .build(),
                )
            }
        }

        // encode(buffer, value, context) reads lambdas from context for payload variants
        if (canEncode) {
            val encodeCtxBody = CodeBlock.builder().beginControlFlow("when (value)")
            for (v in variants) {
                val info = payloadBySubclass[v.subclass.qualifiedName?.asString()]
                val subTypeName = v.subclass.toPoetClassName()
                val subCodecName = v.subclass.codecName()

                val skipForVariant = v.selfEncodesDiscriminator(variantsHandlingDiscriminator)
                if (info != null && info.payloadFields.isNotEmpty()) {
                    val starType = subTypeName.parameterizedBy(info.payloadFields.map { STAR })
                    encodeCtxBody.beginControlFlow("is %T ->", starType)
                    if (!skipForVariant) addWireWrite(encodeCtxBody, v.wire, dispatchOnInfo)
                    emitBodyLengthEncodeWrap(
                        code = encodeCtxBody,
                        info = dispatchOnInfo,
                        encodeStmt = "$subCodecName.encodeFromContext(buffer, value, context)",
                    )
                    encodeCtxBody.endControlFlow()
                } else {
                    encodeCtxBody.beginControlFlow("is %T ->", subTypeName)
                    if (!skipForVariant) addWireWrite(encodeCtxBody, v.wire, dispatchOnInfo)
                    emitBodyLengthEncodeWrap(
                        code = encodeCtxBody,
                        info = dispatchOnInfo,
                        encodeStmt = "$subCodecName.encode(buffer, value, context)",
                    )
                    encodeCtxBody.endControlFlow()
                }
            }
            encodeCtxBody.endControlFlow()

            val encodeCtxBuilder =
                FunSpec
                    .builder("encode")
                    .addParameter("buffer", WRITE_BUFFER)
                    .addParameter("value", interfaceTypeName)
                    .addParameter("context", ENCODE_CONTEXT)
                    .addCode(encodeCtxBody.build())
            if (isBidirectional) encodeCtxBuilder.addModifiers(KModifier.OVERRIDE)
            contextFunctions.add(encodeCtxBuilder.build())

            // For EncodeOnly: override Encoder<T>.encode(buffer, value)
            if (direction == CodecDirection.EncodeOnly) {
                contextFunctions.add(
                    FunSpec
                        .builder("encode")
                        .addModifiers(KModifier.OVERRIDE)
                        .addParameter("buffer", WRITE_BUFFER)
                        .addParameter("value", interfaceTypeName)
                        .addStatement("encode(buffer, value, %T.Empty)", ENCODE_CONTEXT)
                        .build(),
                )
            }
        }

        val allFunctions =
            buildList {
                if (canDecode) add(decodeBuilder.build())
                if (canEncode) add(encodeBuilder.build())
                addAll(contextFunctions)
            }

        return DispatchResult(allFunctions, true)
    }

    // ──────────────────────── peekFrameSize for sealed dispatch ────────────────────────

    private data class SealedPeekResult(
        val minHeaderProperty: PropertySpec,
        val syncFun: FunSpec,
        val suspendFun: FunSpec,
    )

    /**
     * Generates peekFrameSize for a sealed dispatch codec.
     * Peeks the discriminator, branches per variant, delegates to each variant's peekFrameSize.
     *
     * When `dispatchOnInfo.hasBodyLength` is true, the framing collapses to
     * `[discriminator][lengthPrefix(bodyLength)][body]` — total frame size is determined
     * by the length prefix alone and we do not need per-variant peek delegation.
     */
    private fun buildSealedPeekFrameSize(
        variants: List<PacketTypeInfo>,
        dispatchOnInfo: DispatchOnInfo?,
    ): SealedPeekResult? {
        val discriminatorSize =
            if (dispatchOnInfo != null) {
                if (dispatchOnInfo.constructorParams.isEmpty()) return null
                dispatchOnInfo.totalWireBytes
            } else {
                1 // default: single byte
            }

        // For body-framed dispatch the minimum header is discriminator + the prefix's
        // minimum-known wire bytes (Varint = 1; Byte/Short/Int = full width).
        val minHeaderBytes =
            (dispatchOnInfo?.bodyFraming as? BodyFraming.WithLength)
                ?.let { discriminatorSize + it.kind.minWireBytes }
                ?: discriminatorSize

        val minHeaderProp =
            PropertySpec
                .builder("MIN_HEADER_BYTES", INT)
                .addModifiers(KModifier.CONST)
                .initializer("%L", minHeaderBytes)
                .build()

        return SealedPeekResult(
            minHeaderProperty = minHeaderProp,
            syncFun = buildSealedPeekFun(variants, dispatchOnInfo, discriminatorSize, suspending = false),
            suspendFun = buildSealedPeekFun(variants, dispatchOnInfo, discriminatorSize, suspending = true),
        )
    }

    private fun buildSealedPeekFun(
        variants: List<PacketTypeInfo>,
        dispatchOnInfo: DispatchOnInfo?,
        discriminatorSize: Int,
        suspending: Boolean,
    ): FunSpec {
        val streamType = if (suspending) SUSPENDING_STREAM_PROCESSOR else STREAM_PROCESSOR
        val builder =
            FunSpec
                .builder("peekFrameSize")
                .addParameter("stream", streamType)

        if (suspending) {
            builder.addParameter(
                com.squareup.kotlinpoet.ParameterSpec
                    .builder("baseOffset", INT)
                    .defaultValue("0")
                    .build(),
            )
            builder.addModifiers(KModifier.SUSPEND)
        } else {
            builder.addParameter("baseOffset", INT)
            builder.addModifiers(KModifier.OVERRIDE)
        }
        builder.returns(PEEK_RESULT)

        val code = CodeBlock.builder()
        code.addStatement("if (stream.available() < baseOffset + %L) return %T.NeedsMoreData", discriminatorSize, PEEK_RESULT)

        val framing = dispatchOnInfo?.bodyFraming as? BodyFraming.WithLength
        if (framing != null) {
            when (val kind = framing.kind) {
                is LengthPrefixKind.Varint -> {
                    // Walk the VBI canonical encoding byte-by-byte: continuation bit set means more bytes.
                    code.addStatement("val _vbiStart = baseOffset + %L", discriminatorSize)
                    code.addStatement("var _vbiWidth = 0")
                    code.addStatement("var _len = 0")
                    code.addStatement("var _multiplier = 1")
                    code.addStatement("var _continue = true")
                    code.beginControlFlow("while (_continue && _vbiWidth < %L)", kind.maxBytes)
                    code.addStatement(
                        "if (stream.available() < _vbiStart + _vbiWidth + 1) return %T.NeedsMoreData",
                        PEEK_RESULT,
                    )
                    code.addStatement("val _byte = stream.peekByte(_vbiStart + _vbiWidth).toInt() and 0xFF")
                    code.addStatement("_len += (_byte and 0x7F) * _multiplier")
                    code.addStatement("_multiplier *= 128")
                    code.addStatement("_vbiWidth += 1")
                    code.addStatement("_continue = (_byte and 0x80) != 0")
                    code.endControlFlow()
                    code.addStatement("if (_continue) return %T.NeedsMoreData", PEEK_RESULT)
                    code.addStatement(
                        "return %T.Size(%L + _vbiWidth + _len)",
                        PEEK_RESULT,
                        discriminatorSize,
                    )
                }
                else -> {
                    val cfg = fixedPrefixConfigOrError(kind)
                    val prefixOffset = "baseOffset + $discriminatorSize"
                    val readExpr =
                        when (kind) {
                            is LengthPrefixKind.Byte -> "stream.peekByte($prefixOffset).toInt() and 0xFF"
                            is LengthPrefixKind.Short -> "stream.peekShort($prefixOffset).toInt() and 0xFFFF"
                            is LengthPrefixKind.Int -> "stream.peekInt($prefixOffset)"
                            is LengthPrefixKind.Varint -> error("unreachable: handled above")
                        }
                    code.addStatement(
                        "if (stream.available() < baseOffset + %L) return %T.NeedsMoreData",
                        discriminatorSize + cfg.byteCount,
                        PEEK_RESULT,
                    )
                    code.addStatement("val _len = %L", readExpr)
                    code.addStatement(
                        "return %T.Size(%L + %L + _len)",
                        PEEK_RESULT,
                        discriminatorSize,
                        cfg.byteCount,
                    )
                }
            }
            builder.addCode(code.build())
            return builder.build()
        }

        // Peek and extract the dispatch value
        if (dispatchOnInfo != null) {
            if (dispatchOnInfo.isValueClass) {
                // Value class: peek inner type, wrap in constructor
                val peekExpr = discriminatorPeekExpr("stream", "baseOffset", dispatchOnInfo.innerTypeName)
                code.addStatement("val _raw = %L", peekExpr)
                code.addStatement(
                    "val type = %T(_raw).%L",
                    dispatchOnInfo.poetClassName,
                    dispatchOnInfo.dispatchProperty,
                )
            } else {
                // Data class: peek each constructor parameter, build the object
                var paramOffset = 0
                val paramExprs = mutableListOf<String>()
                for (param in dispatchOnInfo.constructorParams) {
                    val peekExpr = discriminatorPeekExpr("stream", "baseOffset + $paramOffset", paramTypeName(param.typeName))
                    paramExprs.add(peekExpr)
                    paramOffset += param.wireBytes
                }
                code.addStatement(
                    "val type = %T(%L).%L",
                    dispatchOnInfo.poetClassName,
                    paramExprs.joinToString(", "),
                    dispatchOnInfo.dispatchProperty,
                )
            }
        } else {
            code.addStatement("val type = stream.peekByte(baseOffset).toInt() and 0xFF")
        }

        // Branch per variant, delegate to variant's peekFrameSize
        code.beginControlFlow("return when (type)")
        for (v in variants) {
            val variantCodecName = v.subclass.codecName()
            code.addStatement(
                "%L -> when (val r = %L.peekFrameSize(stream, baseOffset + %L)) { is %T.Size -> %T.Size(r.bytes + %L); else -> r }",
                v.value,
                variantCodecName,
                discriminatorSize,
                PEEK_RESULT,
                PEEK_RESULT,
                discriminatorSize,
            )
        }
        code.addStatement("else -> %T.NeedsMoreData", PEEK_RESULT)
        code.endControlFlow()

        builder.addCode(code.build())
        return builder.build()
    }

    /** Returns the wire byte count for a discriminator's inner type name. */
    private fun innerTypeWireBytes(innerTypeName: String): Int? =
        when (innerTypeName) {
            "UByte", "Byte" -> 1
            "UShort", "Short" -> 2
            "UInt", "Int" -> 4
            "ULong", "Long" -> 8
            else -> null
        }

    /** Extracts the simple type name from a qualified primitive type (e.g., "kotlin.UInt" -> "UInt"). */
    private fun paramTypeName(qualifiedName: String): String = qualifiedName.substringAfterLast('.')

    /** Generates a peek expression for the discriminator raw value, casting to the inner type. */
    private fun discriminatorPeekExpr(
        stream: String,
        offset: String,
        innerTypeName: String,
    ): String =
        when (innerTypeName) {
            "UByte" -> "$stream.peekByte($offset).toUByte()"
            "Byte" -> "$stream.peekByte($offset)"
            "UShort" -> "$stream.peekShort($offset).toUShort()"
            "Short" -> "$stream.peekShort($offset)"
            "UInt" -> "$stream.peekInt($offset).toUInt()"
            "Int" -> "$stream.peekInt($offset)"
            "ULong" -> "$stream.peekLong($offset).toULong()"
            "Long" -> "$stream.peekLong($offset)"
            else -> "$stream.peekByte($offset).toUByte()" // fallback
        }

    /** Variable name used by variant-decode call sites: `"_bodySlice"` when bodyLength
     * framing is in effect, `"buffer"` otherwise. */
    internal fun bodyVarName(info: DispatchOnInfo?): String = if (info?.hasBodyLength == true) "_bodySlice" else "buffer"

    /**
     * Emits a post-dispatch assertion that the body slice was fully consumed by the
     * variant decoder. Catches malformed wires where the VBI claims more bytes than
     * the variant actually reads — without this, those trailing bytes would silently
     * leak into the next frame.
     */
    internal fun emitBodyLengthOverrunCheck(code: CodeBlock.Builder) {
        code.addStatement(
            "require(_bodySlice.remaining() == 0) { %P }",
            "Variant decoder consumed \${_bodyLen - _bodySlice.remaining()} of \$_bodyLen body bytes; " +
                "\${_bodySlice.remaining()} unread. Wire is malformed or variant codec is buggy.",
        )
    }

    /**
     * Emits the decode-side prelude for body-length framing: read the length, optionally
     * publish to a `BodyLengthSink` registered on the context, and slice the body.
     *
     * No-op when [info] is null or has no body framing — preserves existing dispatch
     * behavior unchanged for callers that don't opt in.
     */
    internal fun emitBodyLengthDecodePrelude(
        code: CodeBlock.Builder,
        info: DispatchOnInfo?,
        contextVarName: String,
    ) {
        val framing = info?.bodyFraming as? BodyFraming.WithLength ?: return
        code.addStatement("val _bodyLen = %L", readLengthExpr(framing.kind))
        code.addStatement(
            "%L[%T]?.value = _bodyLen",
            contextVarName,
            ClassName("com.ditchoom.buffer.codec", "BodyLengthKey"),
        )
        code.addStatement("val _bodySlice = buffer.readBytes(_bodyLen)")
    }

    /**
     * Wraps an encode statement in the body-length framing helper when [info] requests it.
     * For non-framed dispatch (or when [info] is null), emits [encodeStmt] verbatim.
     */
    internal fun emitBodyLengthEncodeWrap(
        code: CodeBlock.Builder,
        info: DispatchOnInfo?,
        encodeStmt: String,
    ) {
        val framing = info?.bodyFraming as? BodyFraming.WithLength
        if (framing == null) {
            code.addStatement(encodeStmt)
            return
        }
        when (val kind = framing.kind) {
            is LengthPrefixKind.Varint ->
                code.addStatement(
                    "buffer.writeVariableByteIntegerLengthPrefixed(maxBytes = %L, fieldName = %S) { buffer -> %L }",
                    kind.maxBytes,
                    "body",
                    encodeStmt,
                )
            else -> {
                val cfg = fixedPrefixConfigOrError(kind)
                code.addStatement("val _pos_body = buffer.position()")
                code.addStatement(cfg.writePlaceholder)
                code.addStatement(encodeStmt)
                code.addStatement("val _end_body = buffer.position()")
                code.addStatement("val _len_body = _end_body - _pos_body - %L", cfg.byteCount)
                code.addStatement("buffer.position(_pos_body)")
                code.addStatement(cfg.writeExpr("_len_body"))
                code.addStatement("buffer.position(_end_body)")
            }
        }
    }
}
