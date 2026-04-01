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
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
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

    fun generate(
        sealedInterface: KSClassDeclaration,
        subclasses: List<KSClassDeclaration>,
        variantPayloadInfos: List<SealedVariantPayloadInfo> = emptyList(),
        dispatchOnInfo: DispatchOnInfo? = null,
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
            if (value < 0 || value > 255) {
                logger.error(
                    "@PacketType($value) on '${subclass.simpleName.asString()}' is out of range. " +
                        "The type discriminator is encoded as a single byte, so valid values are 0-255.",
                    subclass,
                )
                return
            }
            if (wire < 0 || wire > 255) {
                logger.error(
                    "@PacketType(wire=$wire) on '${subclass.simpleName.asString()}' is out of range. " +
                        "The wire byte is encoded as a single byte, so valid values are 0-255.",
                    subclass,
                )
                return
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

        val result =
            if (hasAnyPayload) {
                buildPayloadDispatch(packageName, interfaceTypeName, variants, variantPayloadInfos, dispatchOnInfo)
            } else {
                buildSimpleDispatch(interfaceTypeName, variants, dispatchOnInfo)
            }

        val objectBuilder = TypeSpec.objectBuilder(codecName)
        if (result.implementsCodec) {
            objectBuilder.addSuperinterface(CODEC.parameterizedBy(interfaceTypeName))
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

        val fileSpec =
            FileSpec
                .builder(packageName, codecName)
                .addType(objectBuilder.build())
                .build()

        fileSpec.writeTo(codeGenerator, dependencies)
    }

    /** No payload variants — generates a standard Codec<T> implementation with context forwarding. */
    private fun buildSimpleDispatch(
        interfaceTypeName: ClassName,
        variants: List<PacketTypeInfo>,
        dispatchOnInfo: DispatchOnInfo? = null,
    ): DispatchResult {
        // Context-free decode delegates to context overload
        val decodeFun =
            FunSpec
                .builder("decode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", READ_BUFFER)
                .returns(interfaceTypeName)
                .addStatement("return decode(buffer, %T.Empty)", DECODE_CONTEXT)
                .build()

        // Context-aware decode forwards to sub-codecs
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
        val ctxVar = if (dispatchOnInfo != null) "_ctx" else "context"
        decodeCtxBody.beginControlFlow("return when (type)")
        for (v in variants) {
            decodeCtxBody.addStatement("${v.value} -> ${v.subclass.codecName()}.decode(buffer, $ctxVar)")
        }
        decodeCtxBody
            .addStatement("else -> throw IllegalArgumentException(%P)", "Unknown packet type: \$type")
            .endControlFlow()

        val decodeCtxFun =
            FunSpec
                .builder("decode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", READ_BUFFER)
                .addParameter("context", DECODE_CONTEXT)
                .returns(interfaceTypeName)
                .addCode(decodeCtxBody.build())
                .build()

        // Context-free encode delegates to context overload
        val encodeFun =
            FunSpec
                .builder("encode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", WRITE_BUFFER)
                .addParameter("value", interfaceTypeName)
                .addStatement("encode(buffer, value, %T.Empty)", ENCODE_CONTEXT)
                .build()

        // Context-aware encode forwards to sub-codecs
        val encodeCtxBody = CodeBlock.builder().beginControlFlow("when (value)")
        for (v in variants) {
            encodeCtxBody.beginControlFlow("is %T ->", v.subclass.toPoetClassName())
            encodeCtxBody.addStatement("buffer.writeByte(${v.wire}.toByte())")
            encodeCtxBody.addStatement("${v.subclass.codecName()}.encode(buffer, value, context)")
            encodeCtxBody.endControlFlow()
        }
        encodeCtxBody.endControlFlow()

        val encodeCtxFun =
            FunSpec
                .builder("encode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", WRITE_BUFFER)
                .addParameter("value", interfaceTypeName)
                .addParameter("context", ENCODE_CONTEXT)
                .addCode(encodeCtxBody.build())
                .build()

        return DispatchResult(listOf(decodeFun, decodeCtxFun, encodeFun, encodeCtxFun), true)
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
        } else {
            decodeBody.addStatement("val type = buffer.readByte().toInt() and 0xFF")
        }
        decodeBody.beginControlFlow("return when (type)")

        for (v in variants) {
            val info = payloadBySubclass[v.subclass.qualifiedName?.asString()]
            val subCodecName = v.subclass.codecName()
            if (info != null && info.payloadFields.isNotEmpty()) {
                val lambdaArgs =
                    info.payloadFields.joinToString(", ") { pf ->
                        "decode${v.subclass.simpleName.asString()}${capitalizeFirst(pf.fieldName)}"
                    }
                decodeBody.addStatement("${v.value} -> $subCodecName.decode(buffer, $lambdaArgs)")
            } else {
                decodeBody.addStatement("${v.value} -> $subCodecName.decode(buffer)")
            }
        }
        decodeBody
            .addStatement("else -> throw IllegalArgumentException(%P)", "Unknown packet type: \$type")
            .endControlFlow()

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

            if (info != null && info.payloadFields.isNotEmpty()) {
                // Star-projected match for generic variant
                val starType = subTypeName.parameterizedBy(info.payloadFields.map { STAR })
                encodeBody.beginControlFlow("is %T ->", starType)
                encodeBody.addStatement("buffer.writeByte(${v.wire}.toByte())")
                // Unchecked cast to typed variant
                val castTypeParams = info.payloadFields.map { TypeVariableName(it.typeParamName) }
                val castType = subTypeName.parameterizedBy(castTypeParams)
                val lambdaArgs =
                    info.payloadFields.joinToString(", ") { pf ->
                        "encode${v.subclass.simpleName.asString()}${capitalizeFirst(pf.fieldName)}"
                    }
                encodeBody.addStatement(
                    "@Suppress(\"UNCHECKED_CAST\") $subCodecName.encode(buffer, value as %T, $lambdaArgs)",
                    castType,
                )
                encodeBody.endControlFlow()
            } else {
                // Non-payload variant: simple dispatch
                encodeBody.beginControlFlow("is %T ->", subTypeName)
                encodeBody.addStatement("buffer.writeByte(${v.wire}.toByte())")
                encodeBody.addStatement("$subCodecName.encode(buffer, value)")
                encodeBody.endControlFlow()
            }
        }
        encodeBody.endControlFlow()

        encodeBuilder.addCode(encodeBody.build())

        // ── Context-based Codec<T> overloads (Convention 2: enables nesting) ──

        // decode(buffer) delegates to decode(buffer, context) with Empty
        val decodeNoArgFun =
            FunSpec
                .builder("decode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", READ_BUFFER)
                .returns(interfaceTypeName)
                .addStatement("return decode(buffer, %T.Empty)", DECODE_CONTEXT)
                .build()

        // decode(buffer, context) reads lambdas from context for payload variants
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
        val payloadCtxVar = if (dispatchOnInfo != null) "_ctx" else "context"
        decodeCtxBody.beginControlFlow("return when (type)")

        for (v in variants) {
            val info = payloadBySubclass[v.subclass.qualifiedName?.asString()]
            val subCodecName = v.subclass.codecName()
            if (info != null && info.payloadFields.isNotEmpty()) {
                decodeCtxBody.addStatement("${v.value} -> $subCodecName.decodeFromContext(buffer, $payloadCtxVar)")
            } else {
                decodeCtxBody.addStatement("${v.value} -> $subCodecName.decode(buffer, $payloadCtxVar)")
            }
        }
        decodeCtxBody
            .addStatement("else -> throw IllegalArgumentException(%P)", "Unknown packet type: \$type")
            .endControlFlow()

        val decodeCtxFun =
            FunSpec
                .builder("decode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", READ_BUFFER)
                .addParameter("context", DECODE_CONTEXT)
                .returns(interfaceTypeName)
                .addCode(decodeCtxBody.build())
                .build()

        // encode(buffer, value) delegates to encode(buffer, value, context) with Empty
        val encodeNoArgFun =
            FunSpec
                .builder("encode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", WRITE_BUFFER)
                .addParameter("value", interfaceTypeName)
                .addStatement("encode(buffer, value, %T.Empty)", ENCODE_CONTEXT)
                .build()

        // encode(buffer, value, context) reads lambdas from context for payload variants
        val encodeCtxBody = CodeBlock.builder().beginControlFlow("when (value)")
        for (v in variants) {
            val info = payloadBySubclass[v.subclass.qualifiedName?.asString()]
            val subTypeName = v.subclass.toPoetClassName()
            val subCodecName = v.subclass.codecName()

            if (info != null && info.payloadFields.isNotEmpty()) {
                val starType = subTypeName.parameterizedBy(info.payloadFields.map { STAR })
                encodeCtxBody.beginControlFlow("is %T ->", starType)
                encodeCtxBody.addStatement("buffer.writeByte(${v.wire}.toByte())")
                encodeCtxBody.addStatement("$subCodecName.encodeFromContext(buffer, value, context)")
                encodeCtxBody.endControlFlow()
            } else {
                encodeCtxBody.beginControlFlow("is %T ->", subTypeName)
                encodeCtxBody.addStatement("buffer.writeByte(${v.wire}.toByte())")
                encodeCtxBody.addStatement("$subCodecName.encode(buffer, value, context)")
                encodeCtxBody.endControlFlow()
            }
        }
        encodeCtxBody.endControlFlow()

        val encodeCtxFun =
            FunSpec
                .builder("encode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", WRITE_BUFFER)
                .addParameter("value", interfaceTypeName)
                .addParameter("context", ENCODE_CONTEXT)
                .addCode(encodeCtxBody.build())
                .build()

        return DispatchResult(
            listOf(
                decodeBuilder.build(), // Convention 1: explicit lambdas
                encodeBuilder.build(),
                decodeNoArgFun, // Codec<T> interface
                decodeCtxFun, // Codec<T> context overload
                encodeNoArgFun,
                encodeCtxFun,
            ),
            true, // NOW implements Codec<T>
        )
    }
}
