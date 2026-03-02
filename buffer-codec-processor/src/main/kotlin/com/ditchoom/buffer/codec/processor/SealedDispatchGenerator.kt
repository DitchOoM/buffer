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
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

private val READ_BUFFER = ClassName("com.ditchoom.buffer", "ReadBuffer")
private val WRITE_BUFFER = ClassName("com.ditchoom.buffer", "WriteBuffer")
private val CODEC = ClassName("com.ditchoom.buffer.codec", "Codec")

class SealedDispatchGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {
    fun generate(
        sealedInterface: KSClassDeclaration,
        subclasses: List<KSClassDeclaration>,
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

        val containingFile = sealedInterface.containingFile
        val dependencies =
            if (containingFile != null) {
                Dependencies(true, containingFile)
            } else {
                Dependencies(true)
            }

        // Build decode function
        val decodeBody =
            CodeBlock
                .builder()
                .addStatement("val type = buffer.readByte().toInt() and 0xFF")
                .beginControlFlow("return when (type)")
        for ((value, subclass) in variants) {
            val subName = subclass.simpleName.asString()
            decodeBody.addStatement("$value -> ${subName}Codec.decode(buffer)")
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

        // Build encode function
        val encodeBody = CodeBlock.builder().beginControlFlow("when (value)")
        for ((value, subclass) in variants) {
            val subName = subclass.simpleName.asString()
            encodeBody.beginControlFlow("is $subName ->")
            encodeBody.addStatement("buffer.writeByte($value.toByte())")
            encodeBody.addStatement("${subName}Codec.encode(buffer, value)")
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

        val objectSpec =
            TypeSpec
                .objectBuilder(codecName)
                .addSuperinterface(CODEC.parameterizedBy(interfaceTypeName))
                .addFunction(decodeFun)
                .addFunction(encodeFun)
                .build()

        val fileSpec =
            FileSpec
                .builder(packageName, codecName)
                .addType(objectSpec)
                .build()

        fileSpec.writeTo(codeGenerator, dependencies)
    }
}
