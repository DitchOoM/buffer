package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

class PayloadContextGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {
    fun generate(
        classDeclaration: KSClassDeclaration,
        fields: List<FieldInfo>,
    ) {
        val packageName = classDeclaration.packageName.asString()
        val contextName = "${classDeclaration.enclosingSimpleNames().joinToString("")}Context"

        val nonPayloadFields = fields.filter { it.strategy !is FieldReadStrategy.PayloadField }

        val containingFile = classDeclaration.containingFile
        val dependencies =
            if (containingFile != null) {
                Dependencies(aggregating = false, sources = arrayOf(containingFile))
            } else {
                Dependencies(aggregating = false)
            }

        val typeSpecBuilder =
            if (nonPayloadFields.isEmpty()) {
                // All fields are @Payload — generate an empty object so the codec reference resolves
                TypeSpec.objectBuilder(contextName)
            } else {
                val constructorBuilder = FunSpec.constructorBuilder()
                val classBuilder = TypeSpec.classBuilder(contextName).addModifiers(KModifier.DATA)
                for (field in nonPayloadFields) {
                    val typeName =
                        field.parameter?.type?.resolve()?.let { ksType ->
                            ksType.toTypeName()
                        } ?: ClassName.bestGuess(field.typeName).copy(nullable = field.isNullable)
                    constructorBuilder.addParameter(ParameterSpec.builder(field.name, typeName).build())
                    classBuilder.addProperty(
                        PropertySpec.builder(field.name, typeName).initializer(field.name).build(),
                    )
                }
                classBuilder.primaryConstructor(constructorBuilder.build())
            }

        val fileSpec =
            fileSpecBuilder(packageName, contextName)
                .addType(typeSpecBuilder.build())
                .build()

        fileSpec.writeTo(codeGenerator, dependencies)
    }
}
