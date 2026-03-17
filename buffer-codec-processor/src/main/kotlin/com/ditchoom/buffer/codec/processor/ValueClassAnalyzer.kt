package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier

object ValueClassAnalyzer {
    fun isValueClass(declaration: KSClassDeclaration): Boolean =
        Modifier.VALUE in declaration.modifiers || Modifier.INLINE in declaration.modifiers

    fun getInnerTypeName(declaration: KSClassDeclaration): String? {
        val innerParam = declaration.primaryConstructor?.parameters?.firstOrNull() ?: return null
        return innerParam.type
            .resolve()
            .declaration.qualifiedName
            ?.asString()
    }

    fun getFixedSize(innerTypeName: String): Int =
        when (innerTypeName) {
            "kotlin.Byte", "kotlin.UByte" -> 1
            "kotlin.Short", "kotlin.UShort" -> 2
            "kotlin.Int", "kotlin.UInt", "kotlin.Float" -> 4
            "kotlin.Long", "kotlin.ULong", "kotlin.Double" -> 8
            else -> -1
        }
}
