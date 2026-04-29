package com.ditchoom.buffer.codec.processor.planbuilder

import com.ditchoom.buffer.codec.processor.discovery.DataLikeKind
import com.ditchoom.buffer.codec.processor.discovery.RawAnnotationValue
import com.ditchoom.buffer.codec.processor.discovery.RawClassMetadata
import com.ditchoom.buffer.codec.processor.discovery.RawSymbol
import com.ditchoom.buffer.codec.processor.ir.DiscParam
import com.ditchoom.buffer.codec.processor.ir.DiscriminatorShape
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
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
        externalClasses: Map<String, RawClassMetadata> = emptyMap(),
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
        val externalDispatchValue = externalClasses[discriminatorFqn]?.dispatchValueProperty
        return when (data.classKind) {
            DataLikeKind.ValueClass -> buildValueClass(rootFqn, data, externalDispatchValue)
            DataLikeKind.DataClass, DataLikeKind.RegularClass ->
                buildDataClass(rootFqn, data, externalDispatchValue, externalClasses)
        }
    }

    private fun buildValueClass(
        rootFqn: String,
        data: RawSymbol.DataLike,
        dispatchValueProperty: com.ditchoom.buffer.codec.processor.discovery.RawDispatchValueProperty?,
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
        val innerProp = inner?.name ?: ""
        // Slice 5.5: prefer a `@DispatchValue`-annotated property captured by PhaseA off
        // the discriminator class declarations. Real-world MQTT uses
        // `@JvmInline value class MqttFixedHeader(val raw: UByte)` with
        // `@DispatchValue val packetType: Int get() = (raw.toInt() shr 4) and 0x0F` —
        // the new pipeline used to synthesise `dispatchProp = "raw"` (a UByte) which
        // mismatched the variant arms (Int literals) and produced compile errors.
        // Legacy `resolveDispatchOn` enforces the property's type is `Int`; mirror that
        // check here. When the captured property is non-Int we fall back to `innerProp`
        // and let PhaseC's `WireRangeCheck` surface the type-domain mismatch.
        val dispatchProp =
            if (dispatchValueProperty != null && dispatchValueProperty.returnTypeFqn == "kotlin.Int") {
                dispatchValueProperty.name
            } else {
                if (dispatchValueProperty != null) {
                    errors +=
                        KspError(
                            message =
                                "@DispatchValue property '${dispatchValueProperty.name}' on " +
                                    "'${data.fqn}' must return Int, but returns " +
                                    "${dispatchValueProperty.returnTypeFqn}. Mirror legacy " +
                                    "`resolveDispatchOn` requires Int because variant @PacketType / " +
                                    "@PacketTypeRange arms are Int literals.",
                            sourceFqn = data.fqn,
                        )
                }
                innerProp
            }
        if (errors.isNotEmpty()) {
            return Nel.fromList(errors).left()
        }
        // Wire range: when the dispatch property is a derived getter producing Int,
        // the value range is no longer constrained by the inner primitive's natural
        // range. Use the full Int domain (legacy doesn't bound-check against the
        // inner type when @DispatchValue overrides the dispatch). When the dispatch
        // property is the inner property itself, retain the unsigned-max-value bound.
        val isDerivedDispatch = dispatchValueProperty != null && dispatchProp != innerProp
        val wireRange =
            if (isDerivedDispatch) {
                Int.MIN_VALUE..Int.MAX_VALUE
            } else {
                0..PrimitiveTypes.maxUnsignedValue(innerKind!!).toInt().let { it.coerceAtMost(0xFFFF) }
            }
        return DiscriminatorShape
            .ValueClass(
                discriminatorType = TypeFqn(data.fqn),
                inner = innerKind!!,
                innerProp = innerProp,
                codec = CodecNaming.forSymbol(data),
                dispatchProp = dispatchProp,
                wireRange = wireRange,
            ).right()
    }

    private fun buildDataClass(
        rootFqn: String,
        data: RawSymbol.DataLike,
        dispatchValueProperty: com.ditchoom.buffer.codec.processor.discovery.RawDispatchValueProperty?,
        externalClasses: Map<String, RawClassMetadata>,
    ): Either<Nel<KspError>, DiscriminatorShape.DataClass> {
        val errors = mutableListOf<KspError>()
        val params = mutableListOf<DiscParam>()
        for (p in data.constructorParameters) {
            // Phase 9 Step 7: auto-unwrap single-primitive value-class fields. Mirrors the
            // value-class auto-detection that Step 3 added to `FieldStrategyBuilder` — a
            // discriminator like `WsFrameHeader(val byte1: FrameHeaderByte1, ...)` should
            // be treated as carrying a `UByte` here, so the primitive-only rule below
            // applies after unwrapping.
            val directKind = PrimitiveTypes.classify(p.typeRef)
            val kind =
                directKind ?: run {
                    val info = externalClasses[p.typeRef.fqn]?.valueClassInfo ?: return@run null
                    PrimitiveTypes.classify(
                        com.ditchoom.buffer.codec.processor.discovery.RawTypeRef(
                            fqn = info.innerTypeFqn,
                            name = info.innerTypeFqn.substringAfterLast('.'),
                            typeArguments = emptyList(),
                            isNullable = false,
                            isTypeParameter = false,
                            resolved = true,
                        ),
                    )
                }
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
        // Slice 5.5: prefer the captured property-declaration `@DispatchValue` (works for
        // derived getters that aren't constructor parameters). Mirrors legacy
        // `resolveDispatchOn`. When the property's return type is not Int, fall back to
        // the constructor-param dispatch so the existing legacy diagnostic surface stays
        // intact (PhaseC's wire-range check does the type-domain enforcement separately).
        val dispatchProp =
            when {
                dispatchValueProperty != null && dispatchValueProperty.returnTypeFqn == "kotlin.Int" ->
                    dispatchValueProperty.name
                dispatchValueProperty != null -> {
                    errors +=
                        KspError(
                            message =
                                "@DispatchValue property '${dispatchValueProperty.name}' on " +
                                    "'${data.fqn}' must return Int, but returns " +
                                    "${dispatchValueProperty.returnTypeFqn}. Mirror legacy " +
                                    "`resolveDispatchOn` requires Int because variant @PacketType / " +
                                    "@PacketTypeRange arms are Int literals.",
                            sourceFqn = data.fqn,
                        )
                    dispatchProps.firstOrNull()?.name ?: data.constructorParameters.first().name
                }
                else -> dispatchProps.firstOrNull()?.name ?: data.constructorParameters.first().name
            }
        if (errors.isNotEmpty()) {
            return Nel.fromList(errors).left()
        }
        return DiscriminatorShape
            .DataClass(
                discriminatorType = TypeFqn(data.fqn),
                params = params,
                codec = CodecNaming.forSymbol(data),
                dispatchProp = dispatchProp,
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
