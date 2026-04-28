package com.ditchoom.buffer.codec.processor.planbuilder

import com.ditchoom.buffer.codec.processor.discovery.RawAnnotation
import com.ditchoom.buffer.codec.processor.discovery.RawAnnotationValue
import com.ditchoom.buffer.codec.processor.discovery.RawCtorParameter
import com.ditchoom.buffer.codec.processor.discovery.RawSymbol
import com.ditchoom.buffer.codec.processor.discovery.RawTypeRef
import com.ditchoom.buffer.codec.processor.ir.Endianness
import com.ditchoom.buffer.codec.processor.ir.PrimitiveKind
import com.squareup.kotlinpoet.ClassName

/** Small annotation-lookup helpers PhaseB shares across builders. */
internal object Annotations {
    fun List<RawAnnotation>.find(fqn: String): RawAnnotation? = firstOrNull { it.fqn == fqn }

    fun List<RawAnnotation>.has(fqn: String): Boolean = any { it.fqn == fqn }

    fun RawAnnotation.intArg(name: String): Int? = (arguments[name] as? RawAnnotationValue.IntVal)?.value

    fun RawAnnotation.stringArg(name: String): String? = (arguments[name] as? RawAnnotationValue.StringVal)?.value

    fun RawAnnotation.classRefArg(name: String): RawAnnotationValue.ClassRef? = arguments[name] as? RawAnnotationValue.ClassRef

    fun RawAnnotation.enumArg(name: String): RawAnnotationValue.EnumVal? = arguments[name] as? RawAnnotationValue.EnumVal
}

internal object PrimitiveTypes {
    private val map: Map<String, PrimitiveKind> =
        mapOf(
            "kotlin.Boolean" to PrimitiveKind.Bool,
            "kotlin.Byte" to PrimitiveKind.Byte,
            "kotlin.UByte" to PrimitiveKind.UByte,
            "kotlin.Short" to PrimitiveKind.Short,
            "kotlin.UShort" to PrimitiveKind.UShort,
            "kotlin.Int" to PrimitiveKind.Int,
            "kotlin.UInt" to PrimitiveKind.UInt,
            "kotlin.Long" to PrimitiveKind.Long,
            "kotlin.ULong" to PrimitiveKind.ULong,
            "kotlin.Float" to PrimitiveKind.Float,
            "kotlin.Double" to PrimitiveKind.Double,
        )

    fun classify(typeRef: RawTypeRef): PrimitiveKind? = map[typeRef.fqn]

    fun isNumeric(kind: PrimitiveKind): Boolean =
        when (kind) {
            PrimitiveKind.Byte, PrimitiveKind.UByte,
            PrimitiveKind.Short, PrimitiveKind.UShort,
            PrimitiveKind.Int, PrimitiveKind.UInt,
            PrimitiveKind.Long, PrimitiveKind.ULong,
            -> true
            else -> false
        }

    /** Maximum unsigned value carried on the wire by this primitive's natural width. */
    fun maxUnsignedValue(kind: PrimitiveKind): Long =
        when (kind) {
            PrimitiveKind.Bool, PrimitiveKind.Byte, PrimitiveKind.UByte -> 0xFF
            PrimitiveKind.Short, PrimitiveKind.UShort -> 0xFFFF
            PrimitiveKind.Int, PrimitiveKind.UInt -> 0xFFFF_FFFFL
            PrimitiveKind.Long, PrimitiveKind.ULong -> Long.MAX_VALUE
            PrimitiveKind.Float -> 0xFFFF_FFFFL
            PrimitiveKind.Double -> Long.MAX_VALUE
        }

    fun naturalWireBytes(kind: PrimitiveKind): Int =
        when (kind) {
            PrimitiveKind.Bool, PrimitiveKind.Byte, PrimitiveKind.UByte -> 1
            PrimitiveKind.Short, PrimitiveKind.UShort -> 2
            PrimitiveKind.Int, PrimitiveKind.UInt, PrimitiveKind.Float -> 4
            PrimitiveKind.Long, PrimitiveKind.ULong, PrimitiveKind.Double -> 8
        }
}

internal object EndiannessMapping {
    fun fromAnnotationEnum(name: String?): Endianness? =
        when (name) {
            "Big", "Default" -> Endianness.Big
            "Little" -> Endianness.Little
            else -> null
        }
}

internal object CodecNaming {
    /**
     * Translate a [RawSymbol]'s FQN + enclosingNames chain into the FQN of its generated
     * codec class. Mirrors the legacy `KSClassDeclaration.codecName()` rule:
     * `enclosingSimpleNames().joinToString("") + "Codec"`.
     */
    fun forSymbol(symbol: RawSymbol): ClassName {
        val name = symbol.enclosingNames.joinToString(separator = "") + "Codec"
        return ClassName(symbol.packageName, name)
    }
}

/** Used by builders that need to mention a constructor parameter in an error message. */
internal fun RawCtorParameter.qualifiedSiteFqn(parentFqn: String): String = "$parentFqn.$name"
