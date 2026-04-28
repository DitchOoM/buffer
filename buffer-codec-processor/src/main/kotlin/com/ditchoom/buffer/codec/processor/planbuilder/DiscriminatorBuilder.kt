package com.ditchoom.buffer.codec.processor.planbuilder

import com.ditchoom.buffer.codec.processor.discovery.DataLikeKind
import com.ditchoom.buffer.codec.processor.discovery.RawAnnotationValue
import com.ditchoom.buffer.codec.processor.discovery.RawSymbol
import com.ditchoom.buffer.codec.processor.ir.DiscParam
import com.ditchoom.buffer.codec.processor.ir.DiscriminatorShape
import com.ditchoom.buffer.codec.processor.planbuilder.Annotations.find
import com.ditchoom.buffer.codec.processor.planbuilder.Annotations.has

/**
 * Builds a [DiscriminatorShape] from the discriminator class FQN looked up in scope.
 *
 * Handles the two structural shapes the IR distinguishes — a value class wrapping a
 * primitive (MQTT `MqttFixedHeader`, MessagePack `MessagePackByte`, TLS `ContentType`),
 * and a data class with multiple fields (rare; reserved for protocols where dispatch
 * needs more than one wire byte). Validates the @DispatchValue contract: exactly one
 * dispatch property per discriminator class.
 */
internal object DiscriminatorBuilder {
    fun build(
        rootFqn: String,
        discriminatorFqn: String,
        scope: Map<String, RawSymbol>,
    ): Either<Nel<KspError>, DiscriminatorShape> {
        val symbol = scope[discriminatorFqn]
        if (symbol == null) {
            return Nel
                .of(
                    KspError(
                        message =
                            "@DispatchOn($discriminatorFqn::class) on '$rootFqn' references a class that is " +
                                "not a discovered @ProtocolMessage symbol.",
                        sourceFqn = rootFqn,
                    ),
                ).left()
        }
        val data =
            symbol as? RawSymbol.DataLike
                ?: return Nel
                    .of(
                        KspError(
                            message =
                                "@DispatchOn($discriminatorFqn::class) on '$rootFqn' must reference a data or " +
                                    "value class with a primary constructor; got ${symbol::class.simpleName}.",
                            sourceFqn = rootFqn,
                        ),
                    ).left()
        return when (data.classKind) {
            DataLikeKind.ValueClass -> buildValueClass(rootFqn, data)
            DataLikeKind.DataClass, DataLikeKind.RegularClass -> buildDataClass(rootFqn, data)
        }
    }

    private fun buildValueClass(
        rootFqn: String,
        data: RawSymbol.DataLike,
    ): Either<Nel<KspError>, DiscriminatorShape.ValueClass> {
        val errors = mutableListOf<KspError>()
        if (data.constructorParameters.size != 1) {
            errors +=
                KspError(
                    message =
                        "@DispatchOn discriminator value class '${data.fqn}' must have exactly one constructor " +
                            "parameter; got ${data.constructorParameters.size}.",
                    sourceFqn = data.fqn,
                )
        }
        val inner = data.constructorParameters.firstOrNull()
        val innerKind = inner?.let { PrimitiveTypes.classify(it.typeRef) }
        if (inner != null && innerKind == null) {
            errors +=
                KspError(
                    message =
                        "@DispatchOn discriminator value class '${data.fqn}' must wrap a primitive " +
                            "(UByte, UShort, Int, ...); got '${inner.typeRef.fqn}'.",
                    sourceFqn = data.fqn,
                )
        }
        // dispatch property — there is no per-property RawSymbol model in PhaseA, so we
        // synthesize a "dispatchProp = innerProp" entry. PhaseC-level @DispatchValue
        // resolution can refine when accessing the source declaration directly.
        val innerProp = inner?.name ?: ""
        if (errors.isNotEmpty()) {
            return Nel.fromList(errors).left()
        }
        return DiscriminatorShape
            .ValueClass(
                inner = innerKind!!,
                innerProp = innerProp,
                codec = CodecNaming.forSymbol(data),
                dispatchProp = innerProp,
                wireRange = 0..PrimitiveTypes.maxUnsignedValue(innerKind).toInt().let { it.coerceAtMost(0xFFFF) },
            ).right()
    }

    private fun buildDataClass(
        rootFqn: String,
        data: RawSymbol.DataLike,
    ): Either<Nel<KspError>, DiscriminatorShape.DataClass> {
        val errors = mutableListOf<KspError>()
        val params = mutableListOf<DiscParam>()
        for (p in data.constructorParameters) {
            val kind = PrimitiveTypes.classify(p.typeRef)
            if (kind == null) {
                errors +=
                    KspError(
                        message =
                            "@DispatchOn data-class discriminator '${data.fqn}' field '${p.name}' must be a " +
                                "primitive; got '${p.typeRef.fqn}'.",
                        sourceFqn = "${data.fqn}.${p.name}",
                    )
            } else {
                params += DiscParam(name = p.name, kind = kind, wireBytes = PrimitiveTypes.naturalWireBytes(kind))
            }
        }
        // Verify exactly one @DispatchValue across constructor params (best-effort: PhaseA captures
        // VALUE_PARAMETER annotations; @DispatchValue targets PROPERTY which PhaseA doesn't yet capture
        // separately, so we accept the case where it's absent and let PhaseC re-check).
        val dispatchProps =
            data.constructorParameters.filter { it.annotations.has(AnnotationFqns.DispatchValue) }
        if (dispatchProps.size > 1) {
            errors +=
                KspError(
                    message =
                        "@DispatchOn data-class discriminator '${data.fqn}' has ${dispatchProps.size} " +
                            "@DispatchValue-marked params; exactly one is required.",
                    sourceFqn = data.fqn,
                )
        }
        if (errors.isNotEmpty()) {
            return Nel.fromList(errors).left()
        }
        return DiscriminatorShape
            .DataClass(
                params = params,
                codec = CodecNaming.forSymbol(data),
                dispatchProp = dispatchProps.firstOrNull()?.name ?: data.constructorParameters.first().name,
            ).right()
    }
}

/** Read the `type` ClassRef argument off a `@DispatchOn` annotation. */
internal fun RawSymbol.SealedRoot.dispatchOnType(): RawAnnotationValue.ClassRef? =
    annotations
        .find(AnnotationFqns.DispatchOn)
        ?.arguments
        ?.get("type") as? RawAnnotationValue.ClassRef

/** Read the `framing` ClassRef argument off a `@DispatchOn` annotation, when present. */
internal fun RawSymbol.SealedRoot.dispatchOnFraming(): RawAnnotationValue.ClassRef? =
    annotations
        .find(AnnotationFqns.DispatchOn)
        ?.arguments
        ?.get("framing") as? RawAnnotationValue.ClassRef
