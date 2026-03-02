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
                logger.error(
                    "@WhenTrue expression on field '${field.name}' is empty. " +
                        "Provide a field reference (e.g., @WhenTrue(\"hasExtra\")) " +
                        "or a dotted property access (e.g., @WhenTrue(\"flags.willFlag\")).",
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
                    "Conditional expression '$expression' references field '$referencedFieldName' " +
                        "which must come before field '${field.name}'",
                    field.parameter,
                )
                valid = false
                continue
            }

            // For simple field reference, check it's Boolean
            if (parts.size == 1) {
                if (referencedField.typeName != "kotlin.Boolean") {
                    logger.error(
                        "Conditional expression '$expression' references field '$referencedFieldName' " +
                            "which is not a Boolean (type: ${referencedField.typeName})",
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
}
