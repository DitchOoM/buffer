package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.processing.KSPLogger

class ConditionalValidator(
    private val logger: KSPLogger,
) {
    fun validate(fields: List<FieldInfo>): Boolean {
        var valid = true
        for ((index, field) in fields.withIndex()) {
            val condition = field.condition ?: continue

            val expression = (condition as FieldCondition.WhenTrue).expression

            if (expression.isBlank()) {
                // Build contextual examples from the actual fields in this class
                val booleanFields = fields.filter { it.typeName == "kotlin.Boolean" && it.name != field.name }
                val example =
                    if (booleanFields.isNotEmpty()) {
                        "@WhenTrue(\"${booleanFields.first().name}\")"
                    } else {
                        // No Boolean fields exist yet — suggest adding one
                        "@WhenTrue(\"has${capitalizeFirst(field.name)}\")"
                    }
                logger.error(
                    "@WhenTrue expression on field '${field.name}' is empty. " +
                        "Provide the name of a Boolean field that controls whether '${field.name}' is present " +
                        "(e.g., $example).",
                    field.parameter,
                )
                valid = false
                continue
            }

            // Parse expression: "fieldName" or "fieldName.property"
            val parts = expression.split(".")
            val referencedFieldName = parts[0]

            // Check field exists
            val referencedField = fields.find { it.name == referencedFieldName }
            if (referencedField == null) {
                logger.error(
                    "Conditional expression '$expression' references non-existent field '$referencedFieldName'",
                    field.parameter,
                )
                valid = false
                continue
            }

            // Check referenced field comes before this field
            val refIndex = fields.indexOf(referencedField)
            if (refIndex >= index) {
                logger.error(
                    "@WhenTrue(\"$expression\") on '${field.name}': field '$referencedFieldName' " +
                        "must be declared before '${field.name}' in the constructor. " +
                        "The codec reads fields in order, so the condition must already be decoded.",
                    field.parameter,
                )
                valid = false
                continue
            }

            // For simple field reference, check it's Boolean
            if (parts.size == 1) {
                if (referencedField.typeName != "kotlin.Boolean") {
                    val shortType = referencedField.typeName.substringAfterLast(".")
                    logger.error(
                        "@WhenTrue(\"$expression\") on '${field.name}': field '$referencedFieldName' " +
                            "is $shortType, not Boolean. " +
                            "Either change '$referencedFieldName' to Boolean, " +
                            "or use a dotted property access (e.g., @WhenTrue(\"$referencedFieldName.someFlag\")).",
                        field.parameter,
                    )
                    valid = false
                }
            }
            // For dotted access (e.g., "flags.willFlag"), we allow it on value classes
            // The property access will be validated at compile time of generated code

            // Check conditional field is nullable
            if (!field.isNullable) {
                logger.error(
                    "Conditional field '${field.name}' must be nullable. " +
                        "When the condition is false, the codec returns null for this field. " +
                        "Fix: change the type to nullable (e.g., 'val ${field.name}: ${field.typeName}?').",
                    field.parameter,
                )
                valid = false
            }

            // Check conditional field has a default value
            if (!field.hasDefault) {
                logger.error(
                    "Conditional field '${field.name}' must have a default value of null. " +
                        "When the condition is false, the codec skips this field and uses the default. " +
                        "Without a default, Kotlin requires callers to always provide a value, " +
                        "defeating the purpose of the condition. " +
                        "Fix: add '= null' after the type.",
                    field.parameter,
                )
                valid = false
            }
        }
        return valid
    }

    private fun capitalizeFirst(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
