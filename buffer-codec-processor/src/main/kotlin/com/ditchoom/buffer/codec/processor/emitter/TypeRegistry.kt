package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.codec.processor.ir.PrimitiveKind
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.squareup.kotlinpoet.ClassName

/**
 * Resolves a Plan-IR [TypeFqn] to a KotlinPoet [ClassName].
 *
 * The Plan IR is KSP-free: every reference to a user type lives as a [TypeFqn]
 * canonical string. The emitter needs `ClassName` instances at the boundaries
 * where it hands code to KotlinPoet. This registry is the single conversion
 * point and is constructed once per emit pass.
 *
 * In production the registry is built from the discovery phase output (where the
 * KSP `Resolver` knows the package + simple-name split for each FQN). In tests
 * it is built directly from a fixture map so the emitter can be exercised
 * without KSP.
 *
 * For an FQN not in the explicit map the registry falls back to the standard
 * "split on the last `.`" rule. That is safe for top-level types where the
 * package and simple name are unambiguous; nested-type references are added to
 * the map explicitly.
 */
class TypeRegistry(
    private val explicit: Map<TypeFqn, ClassName> = emptyMap(),
) {
    fun resolve(fqn: TypeFqn): ClassName {
        explicit[fqn]?.let { return it }
        return splitFqn(fqn.canonical)
    }

    fun codecOf(fqn: TypeFqn): ClassName {
        val resolved = resolve(fqn)
        return ClassName(resolved.packageName, resolved.simpleNames.joinToString("") + "Codec")
    }

    companion object {
        /** Standard resolver for a primary FQN string — splits on the last `.`. */
        fun splitFqn(canonical: String): ClassName {
            val lastDot = canonical.lastIndexOf('.')
            return if (lastDot < 0) {
                ClassName("", canonical)
            } else {
                ClassName(canonical.substring(0, lastDot), canonical.substring(lastDot + 1))
            }
        }

        fun primitiveTypeName(kind: PrimitiveKind): com.squareup.kotlinpoet.TypeName =
            when (kind) {
                PrimitiveKind.Bool -> com.squareup.kotlinpoet.BOOLEAN
                PrimitiveKind.Byte -> com.squareup.kotlinpoet.BYTE
                PrimitiveKind.UByte -> com.squareup.kotlinpoet.U_BYTE
                PrimitiveKind.Short -> com.squareup.kotlinpoet.SHORT
                PrimitiveKind.UShort -> com.squareup.kotlinpoet.U_SHORT
                PrimitiveKind.Int -> com.squareup.kotlinpoet.INT
                PrimitiveKind.UInt -> com.squareup.kotlinpoet.U_INT
                PrimitiveKind.Long -> com.squareup.kotlinpoet.LONG
                PrimitiveKind.ULong -> com.squareup.kotlinpoet.U_LONG
                PrimitiveKind.Float -> com.squareup.kotlinpoet.FLOAT
                PrimitiveKind.Double -> com.squareup.kotlinpoet.DOUBLE
            }
    }
}
