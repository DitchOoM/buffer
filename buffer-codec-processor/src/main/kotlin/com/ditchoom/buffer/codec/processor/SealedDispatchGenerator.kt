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
    fun generate(
        sealedInterface: KSClassDeclaration,
        subclasses: List<KSClassDeclaration>,
        variantPayloadInfos: List<SealedVariantPayloadInfo> = emptyList(),
    ) {
        val interfaceName = sealedInterface.simpleName.asString()
        val packageName = sealedInterface.packageName.asString()
        val codecName = "${interfaceName}Codec"
        val interfaceTypeName = ClassName(packageName, interfaceName)

        // Collect @PacketType values
        val variants = mutableListOf<Pair<Int, KSClassDeclaration>>()
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
            if (value < 0 || value > 255) {
                logger.error(
                    "@PacketType($value) on '${subclass.simpleName.asString()}' is out of range. " +
                        "The type discriminator is encoded as a single byte, so valid values are 0-255.",
                    subclass,
                )
                return
            }
            val existing = variants.find { it.first == value }
            if (existing != null) {
                logger.error(
                    "@PacketType($value) is used by both '${existing.second.simpleName.asString()}' " +
                        "and '${subclass.simpleName.asString()}'. " +
                        "Each subclass needs a unique discriminator so the codec can identify which type to decode.",
                    subclass,
                )
                return
            }
            variants.add(value to subclass)
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

        val (decodeFun, encodeFun, implementsCodec) =
            if (hasAnyPayload) {
                buildPayloadDispatch(packageName, interfaceTypeName, variants, variantPayloadInfos)
            } else {
                buildSimpleDispatch(interfaceTypeName, variants)
            }

        val objectBuilder = TypeSpec.objectBuilder(codecName)
        if (implementsCodec) {
            objectBuilder.addSuperinterface(CODEC.parameterizedBy(interfaceTypeName))
        }
        objectBuilder.addFunction(decodeFun)
        objectBuilder.addFunction(encodeFun)

        val fileSpec =
            FileSpec
                .builder(packageName, codecName)
                .addType(objectBuilder.build())
                .build()

        fileSpec.writeTo(codeGenerator, dependencies)
    }

    /** No payload variants — generates a standard Codec<T> implementation. */
    private fun buildSimpleDispatch(
        interfaceTypeName: ClassName,
        variants: List<Pair<Int, KSClassDeclaration>>,
    ): Triple<FunSpec, FunSpec, Boolean> {
        // Decode
        val decodeBody =
            CodeBlock
                .builder()
                .addStatement("val type = buffer.readByte().toInt() and 0xFF")
                .beginControlFlow("return when (type)")
        for ((value, subclass) in variants) {
            decodeBody.addStatement("$value -> ${subclass.codecName()}.decode(buffer)")
        }
        decodeBody
            .addStatement("else -> throw IllegalArgumentException(%P)", "Unknown packet type: \$type")
            .endControlFlow()

        val decodeFun =
            FunSpec
                .builder("decode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", READ_BUFFER)
                .returns(interfaceTypeName)
                .addCode(decodeBody.build())
                .build()

        // Encode
        val encodeBody = CodeBlock.builder().beginControlFlow("when (value)")
        for ((value, subclass) in variants) {
            encodeBody.beginControlFlow("is %T ->", subclass.toPoetClassName())
            encodeBody.addStatement("buffer.writeByte($value.toByte())")
            encodeBody.addStatement("${subclass.codecName()}.encode(buffer, value)")
            encodeBody.endControlFlow()
        }
        encodeBody.endControlFlow()

        val encodeFun =
            FunSpec
                .builder("encode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", WRITE_BUFFER)
                .addParameter("value", interfaceTypeName)
                .addCode(encodeBody.build())
                .build()

        return Triple(decodeFun, encodeFun, true)
    }

    /**
     * Some variants have @Payload — generates a dispatch with type params and lambda forwarding.
     * Cannot implement Codec<T> because decode/encode need extra lambda parameters.
     */
    private fun buildPayloadDispatch(
        packageName: String,
        interfaceTypeName: ClassName,
        variants: List<Pair<Int, KSClassDeclaration>>,
        variantPayloadInfos: List<SealedVariantPayloadInfo>,
    ): Triple<FunSpec, FunSpec, Boolean> {
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

        val decodeBody =
            CodeBlock
                .builder()
                .addStatement("val type = buffer.readByte().toInt() and 0xFF")
                .beginControlFlow("return when (type)")

        for ((value, subclass) in variants) {
            val info = payloadBySubclass[subclass.qualifiedName?.asString()]
            val subCodecName = subclass.codecName()
            if (info != null && info.payloadFields.isNotEmpty()) {
                val lambdaArgs =
                    info.payloadFields.joinToString(", ") { pf ->
                        "decode${subclass.simpleName.asString()}${capitalizeFirst(pf.fieldName)}"
                    }
                decodeBody.addStatement("$value -> $subCodecName.decode(buffer, $lambdaArgs)")
            } else {
                decodeBody.addStatement("$value -> $subCodecName.decode(buffer)")
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
        for ((value, subclass) in variants) {
            val info = payloadBySubclass[subclass.qualifiedName?.asString()]
            val subTypeName = subclass.toPoetClassName()
            val subCodecName = subclass.codecName()

            if (info != null && info.payloadFields.isNotEmpty()) {
                // Star-projected match for generic variant
                val starType = subTypeName.parameterizedBy(info.payloadFields.map { STAR })
                encodeBody.beginControlFlow("is %T ->", starType)
                encodeBody.addStatement("buffer.writeByte($value.toByte())")
                // Unchecked cast to typed variant
                val castTypeParams = info.payloadFields.map { TypeVariableName(it.typeParamName) }
                val castType = subTypeName.parameterizedBy(castTypeParams)
                val lambdaArgs =
                    info.payloadFields.joinToString(", ") { pf ->
                        "encode${subclass.simpleName.asString()}${capitalizeFirst(pf.fieldName)}"
                    }
                encodeBody.addStatement(
                    "@Suppress(\"UNCHECKED_CAST\") $subCodecName.encode(buffer, value as %T, $lambdaArgs)",
                    castType,
                )
                encodeBody.endControlFlow()
            } else {
                // Non-payload variant: simple dispatch
                encodeBody.beginControlFlow("is %T ->", subTypeName)
                encodeBody.addStatement("buffer.writeByte($value.toByte())")
                encodeBody.addStatement("$subCodecName.encode(buffer, value)")
                encodeBody.endControlFlow()
            }
        }
        encodeBody.endControlFlow()

        encodeBuilder.addCode(encodeBody.build())

        return Triple(decodeBuilder.build(), encodeBuilder.build(), false)
    }
}
