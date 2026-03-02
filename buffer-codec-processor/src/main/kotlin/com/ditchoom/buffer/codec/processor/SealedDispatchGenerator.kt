package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration

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

        // Collect @PacketType values
        val variants = mutableListOf<Pair<Int, KSClassDeclaration>>()
        for (subclass in subclasses) {
            val packetType =
                subclass.annotations.find {
                    it.qualifiedName() == "com.ditchoom.buffer.codec.annotations.PacketType"
                }
            if (packetType == null) {
                logger.error("@PacketType is required on sealed subclass '${subclass.simpleName.asString()}'", subclass)
                return
            }
            val value = packetType.arguments.first().value as Int
            if (value < 0 || value > 255) {
                logger.error(
                    "@PacketType value $value on '${subclass.simpleName.asString()}' is out of range. " +
                        "The discriminator is a single byte on the wire (0-255).",
                    subclass,
                )
                return
            }
            val existing = variants.find { it.first == value }
            if (existing != null) {
                logger.error(
                    "@PacketType($value) is used by both '${existing.second.simpleName.asString()}' " +
                        "and '${subclass.simpleName.asString()}'. Each variant must have a unique discriminator value.",
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

        val file =
            codeGenerator.createNewFile(
                dependencies = dependencies,
                packageName = packageName,
                fileName = codecName,
            )

        file.write(
            buildString {
                appendLine("package $packageName")
                appendLine()
                appendLine("import com.ditchoom.buffer.ReadBuffer")
                appendLine("import com.ditchoom.buffer.WriteBuffer")
                appendLine("import com.ditchoom.buffer.codec.Codec")
                appendLine()
                appendLine("object $codecName : Codec<$interfaceName> {")
                appendLine("    override fun decode(buffer: ReadBuffer): $interfaceName {")
                appendLine("        val type = buffer.readByte().toInt() and 0xFF")
                appendLine("        return when (type) {")
                for ((value, subclass) in variants) {
                    val subName = subclass.simpleName.asString()
                    appendLine("            $value -> ${subName}Codec.decode(buffer)")
                }
                appendLine("            else -> throw IllegalArgumentException(\"Unknown packet type: \$type\")")
                appendLine("        }")
                appendLine("    }")
                appendLine()
                appendLine("    override fun encode(buffer: WriteBuffer, value: $interfaceName) {")
                appendLine("        when (value) {")
                for ((value, subclass) in variants) {
                    val subName = subclass.simpleName.asString()
                    appendLine("            is $subName -> {")
                    appendLine("                buffer.writeByte($value.toByte())")
                    appendLine("                ${subName}Codec.encode(buffer, value)")
                    appendLine("            }")
                }
                appendLine("        }")
                appendLine("    }")
                appendLine("}")
            }.toByteArray(),
        )

        file.close()
    }
}
