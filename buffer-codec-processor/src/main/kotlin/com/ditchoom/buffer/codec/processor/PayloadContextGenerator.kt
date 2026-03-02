package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration

class PayloadContextGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {
    fun generate(
        classDeclaration: KSClassDeclaration,
        fields: List<FieldInfo>,
    ) {
        val className = classDeclaration.simpleName.asString()
        val packageName = classDeclaration.packageName.asString()
        val contextName = "${className}Context"

        val nonPayloadFields = fields.filter { it.strategy !is FieldReadStrategy.PayloadField }
        if (nonPayloadFields.isEmpty()) return

        val containingFile = classDeclaration.containingFile
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
                fileName = contextName,
            )

        file.write(
            buildString {
                appendLine("package $packageName")
                appendLine()
                appendLine("data class $contextName(")
                nonPayloadFields.forEachIndexed { index, field ->
                    val comma = if (index < nonPayloadFields.size - 1) "," else ""
                    val nullableMarker = if (field.isNullable) "?" else ""
                    appendLine("    val ${field.name}: ${field.typeName}$nullableMarker$comma")
                }
                appendLine(")")
            }.toByteArray(),
        )

        file.close()
    }
}
