@file:Suppress("ktlint:standard:filename")

package com.ditchoom.buffer.codec.processor.planbuilder

import com.ditchoom.buffer.codec.processor.discovery.DataLikeKind
import com.ditchoom.buffer.codec.processor.discovery.RawAnnotation
import com.ditchoom.buffer.codec.processor.discovery.RawAnnotationValue
import com.ditchoom.buffer.codec.processor.discovery.RawCtorParameter
import com.ditchoom.buffer.codec.processor.discovery.RawDirection
import com.ditchoom.buffer.codec.processor.discovery.RawSymbol
import com.ditchoom.buffer.codec.processor.discovery.RawTypeParameter
import com.ditchoom.buffer.codec.processor.discovery.RawTypeRef

/**
 * Tiny DSL for building [RawSymbol] fixtures inline in unit tests — keeps PhaseB tests
 * pure-Kotlin (no KSP / kctfork compilation), so they execute in milliseconds and pin
 * exactly the inputs each rule's diagnostic depends on.
 */
internal object Fixtures {
    fun protocolMessageAnnotation(
        wireOrder: String? = null,
        direction: String? = null,
        onUnknownDiscriminator: String? = null,
    ): RawAnnotation {
        val args = mutableMapOf<String, RawAnnotationValue>()
        if (wireOrder != null) {
            args["wireOrder"] =
                RawAnnotationValue.EnumVal(
                    typeFqn = "com.ditchoom.buffer.codec.annotations.Endianness",
                    name = wireOrder,
                )
        }
        if (direction != null) {
            args["direction"] =
                RawAnnotationValue.EnumVal(
                    typeFqn = "com.ditchoom.buffer.codec.annotations.Direction",
                    name = direction,
                )
        }
        if (onUnknownDiscriminator != null) {
            args["onUnknownDiscriminator"] = RawAnnotationValue.StringVal(onUnknownDiscriminator)
        }
        return RawAnnotation(AnnotationFqns.ProtocolMessage, args)
    }

    fun lengthPrefixed(
        prefix: String? = null,
        maxBytes: Int? = null,
    ): RawAnnotation {
        val args = mutableMapOf<String, RawAnnotationValue>()
        if (prefix != null) {
            args["prefix"] =
                RawAnnotationValue.EnumVal(
                    typeFqn = "com.ditchoom.buffer.codec.annotations.LengthPrefix",
                    name = prefix,
                )
        }
        if (maxBytes != null) args["maxBytes"] = RawAnnotationValue.IntVal(maxBytes)
        return RawAnnotation(AnnotationFqns.LengthPrefixed, args)
    }

    fun lengthFrom(field: String): RawAnnotation =
        RawAnnotation(AnnotationFqns.LengthFrom, mapOf("field" to RawAnnotationValue.StringVal(field)))

    fun remainingBytes(): RawAnnotation = RawAnnotation(AnnotationFqns.RemainingBytes, emptyMap())

    fun variableByteInteger(): RawAnnotation = RawAnnotation(AnnotationFqns.VariableByteInteger, emptyMap())

    fun whenAnnotation(expression: String): RawAnnotation =
        RawAnnotation(AnnotationFqns.When_, mapOf("expression" to RawAnnotationValue.StringVal(expression)))

    fun whenTrueAnnotation(expression: String): RawAnnotation =
        RawAnnotation(AnnotationFqns.WhenTrue, mapOf("expression" to RawAnnotationValue.StringVal(expression)))

    fun whenRemaining(minBytes: Int): RawAnnotation =
        RawAnnotation(AnnotationFqns.WhenRemaining, mapOf("minBytes" to RawAnnotationValue.IntVal(minBytes)))

    fun useCodec(codecFqn: String): RawAnnotation =
        RawAnnotation(
            AnnotationFqns.UseCodec,
            mapOf("codec" to RawAnnotationValue.ClassRef(fqn = codecFqn, resolved = codecFqn.isNotBlank())),
        )

    fun discriminatorField(): RawAnnotation = RawAnnotation(AnnotationFqns.DiscriminatorField, emptyMap())

    fun packetType(wire: Int): RawAnnotation = RawAnnotation(AnnotationFqns.PacketType, mapOf("wire" to RawAnnotationValue.IntVal(wire)))

    fun packetTypeRange(
        from: Int,
        to: Int,
    ): RawAnnotation =
        RawAnnotation(
            AnnotationFqns.PacketTypeRange,
            mapOf(
                "from" to RawAnnotationValue.IntVal(from),
                "to" to RawAnnotationValue.IntVal(to),
            ),
        )

    fun dispatchOn(
        type: String,
        framing: String? = null,
    ): RawAnnotation {
        val args = mutableMapOf<String, RawAnnotationValue>()
        args["type"] = RawAnnotationValue.ClassRef(fqn = type, resolved = type.isNotBlank())
        if (framing != null) {
            args["framing"] = RawAnnotationValue.ClassRef(fqn = framing, resolved = true)
        }
        return RawAnnotation(AnnotationFqns.DispatchOn, args)
    }

    fun decode(): RawAnnotation = RawAnnotation(AnnotationFqns.Decode, emptyMap())

    fun encode(): RawAnnotation = RawAnnotation(AnnotationFqns.Encode, emptyMap())

    fun wireBytes(value: Int): RawAnnotation = RawAnnotation(AnnotationFqns.WireBytes, mapOf("value" to RawAnnotationValue.IntVal(value)))

    fun wireOrder(order: String): RawAnnotation =
        RawAnnotation(
            AnnotationFqns.WireOrder,
            mapOf(
                "order" to
                    RawAnnotationValue.EnumVal(
                        typeFqn = "com.ditchoom.buffer.codec.annotations.Endianness",
                        name = order,
                    ),
            ),
        )

    fun primitiveTypeRef(fqn: String): RawTypeRef =
        RawTypeRef(
            fqn = fqn,
            name = fqn.substringAfterLast('.'),
            typeArguments = emptyList(),
            isNullable = false,
            isTypeParameter = false,
            resolved = true,
        )

    fun nullablePrimitiveTypeRef(fqn: String): RawTypeRef = primitiveTypeRef(fqn).copy(isNullable = true)

    fun listTypeRef(elementFqn: String): RawTypeRef =
        RawTypeRef(
            fqn = "kotlin.collections.List",
            name = "List",
            typeArguments = listOf(primitiveTypeRef(elementFqn)),
            isNullable = false,
            isTypeParameter = false,
            resolved = true,
        )

    fun typeParameterRef(name: String): RawTypeRef =
        RawTypeRef(
            fqn = "kotlin.$name",
            name = name,
            typeArguments = emptyList(),
            isNullable = false,
            isTypeParameter = true,
            resolved = true,
        )

    fun nestedMessageRef(fqn: String): RawTypeRef =
        RawTypeRef(
            fqn = fqn,
            name = fqn.substringAfterLast('.'),
            typeArguments = emptyList(),
            isNullable = false,
            isTypeParameter = false,
            resolved = true,
        )

    fun param(
        name: String,
        typeRef: RawTypeRef,
        annotations: List<RawAnnotation> = emptyList(),
        hasDefault: Boolean = false,
    ): RawCtorParameter =
        RawCtorParameter(
            name = name,
            typeRef = typeRef,
            annotations = annotations,
            hasDefault = hasDefault,
        )

    fun dataLike(
        fqn: String,
        ctorParameters: List<RawCtorParameter>,
        kind: DataLikeKind = DataLikeKind.DataClass,
        annotations: List<RawAnnotation> = listOf(protocolMessageAnnotation()),
        direction: RawDirection = RawDirection.Default,
        typeParameters: List<RawTypeParameter> = emptyList(),
    ): RawSymbol.DataLike {
        val pkg = fqn.substringBeforeLast('.', missingDelimiterValue = "")
        val simple = fqn.substringAfterLast('.')
        return RawSymbol.DataLike(
            fqn = fqn,
            simpleName = simple,
            packageName = pkg,
            enclosingNames = listOf(simple),
            annotations = annotations,
            direction = direction,
            classKind = kind,
            typeParameters = typeParameters,
            constructorParameters = ctorParameters,
        )
    }

    fun objectSymbol(
        fqn: String,
        annotations: List<RawAnnotation> = listOf(protocolMessageAnnotation()),
        direction: RawDirection = RawDirection.Default,
    ): RawSymbol.ObjectSymbol {
        val pkg = fqn.substringBeforeLast('.', missingDelimiterValue = "")
        val simple = fqn.substringAfterLast('.')
        return RawSymbol.ObjectSymbol(
            fqn = fqn,
            simpleName = simple,
            packageName = pkg,
            enclosingNames = listOf(simple),
            annotations = annotations,
            direction = direction,
        )
    }

    fun sealedRoot(
        fqn: String,
        subclassFqns: List<String>,
        annotations: List<RawAnnotation> = listOf(protocolMessageAnnotation()),
        direction: RawDirection = RawDirection.Default,
        typeParameters: List<RawTypeParameter> = emptyList(),
    ): RawSymbol.SealedRoot {
        val pkg = fqn.substringBeforeLast('.', missingDelimiterValue = "")
        val simple = fqn.substringAfterLast('.')
        return RawSymbol.SealedRoot(
            fqn = fqn,
            simpleName = simple,
            packageName = pkg,
            enclosingNames = listOf(simple),
            annotations = annotations,
            direction = direction,
            subclassFqns = subclassFqns,
            typeParameters = typeParameters,
        )
    }

    fun payloadTypeParam(name: String): RawTypeParameter =
        RawTypeParameter(
            name = name,
            upperBoundFqn = null,
            annotations = listOf(RawAnnotation(AnnotationFqns.Payload, emptyMap())),
        )
}

internal fun <L, R> Either<L, R>.expectRight(): R =
    when (this) {
        is Either.Left -> throw AssertionError("expected Right, got Left: ${this.value}")
        is Either.Right -> value
    }

internal fun <L, R> Either<L, R>.expectLeft(): L =
    when (this) {
        is Either.Left -> value
        is Either.Right -> throw AssertionError("expected Left, got Right: ${this.value}")
    }
