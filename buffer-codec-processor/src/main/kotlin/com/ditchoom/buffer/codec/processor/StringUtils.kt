package com.ditchoom.buffer.codec.processor

internal fun capitalizeFirst(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

/**
 * Prepends "value." to the first path segment of a condition expression,
 * so that generated code references the constructor parameter.
 * E.g. "flags.willFlag" → "value.flags.willFlag", "enabled" → "value.enabled"
 */
internal fun conditionToValueExpr(expression: String): String = expression.replace(Regex("^([^.]+)"), "value.$1")
