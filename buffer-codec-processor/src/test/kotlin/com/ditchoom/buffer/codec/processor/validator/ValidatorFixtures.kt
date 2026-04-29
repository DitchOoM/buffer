@file:Suppress("ktlint:standard:filename")

package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.discovery.RawClassMetadata
import com.ditchoom.buffer.codec.processor.discovery.RawTypeRef
import com.ditchoom.buffer.codec.processor.ir.Direction
import com.ditchoom.buffer.codec.processor.ir.DiscParam
import com.ditchoom.buffer.codec.processor.ir.DiscriminatorShape
import com.ditchoom.buffer.codec.processor.ir.DispatchShape
import com.ditchoom.buffer.codec.processor.ir.FieldPlan
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.ditchoom.buffer.codec.processor.ir.FramingMode
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.PrimitiveKind
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.ir.VariantPlan
import com.ditchoom.buffer.codec.processor.ir.WireMatch
import com.ditchoom.buffer.codec.processor.planbuilder.Either
import com.squareup.kotlinpoet.ClassName

/**
 * Tiny IR-level DSL for Validator (PhaseC) tests — builds [Plan] values directly so
 * each test pins the rule it exercises without dragging the full PhaseB pipeline in.
 */
internal object PhaseCFixtures {
    fun leaf(
        fqn: String,
        fields: List<FieldPlan> = emptyList(),
        dir: Direction = Direction.Bidirectional,
    ): Plan.Leaf =
        Plan.Leaf(
            decl = TypeFqn(fqn),
            fields = fields,
            batches = emptyList(),
            dir = dir,
        )

    fun obj(
        fqn: String,
        dir: Direction = Direction.Bidirectional,
    ): Plan.Object_ = Plan.Object_(decl = TypeFqn(fqn), dir = dir)

    fun sealed(
        fqn: String,
        variants: List<VariantPlan>,
        dispatch: DispatchShape = DispatchShape.RawByte,
        dir: Direction = Direction.Bidirectional,
        onUnknown: String = "java.lang.IllegalArgumentException",
    ): Plan.Sealed_ =
        Plan.Sealed_(
            decl = TypeFqn(fqn),
            variants = variants,
            dispatch = dispatch,
            dir = dir,
            onUnknown = TypeFqn(onUnknown),
        )

    fun valueClassDispatch(
        discriminatorFqn: String,
        framing: FramingMode = FramingMode.Unframed,
        codec: ClassName = ClassName(packageOf(discriminatorFqn), simpleOf(discriminatorFqn) + "Codec"),
    ): DispatchShape.TypedDiscriminator =
        DispatchShape.TypedDiscriminator(
            disc =
                DiscriminatorShape.ValueClass(
                    discriminatorType = TypeFqn(discriminatorFqn),
                    inner = PrimitiveKind.UByte,
                    innerProp = "raw",
                    codec = codec,
                    dispatchProp = "raw",
                    wireRange = 0..0xFF,
                ),
            framing = framing,
        )

    fun dataClassDispatch(
        discriminatorFqn: String,
        framing: FramingMode = FramingMode.Unframed,
        codec: ClassName = ClassName(packageOf(discriminatorFqn), simpleOf(discriminatorFqn) + "Codec"),
    ): DispatchShape.TypedDiscriminator =
        DispatchShape.TypedDiscriminator(
            disc =
                DiscriminatorShape.DataClass(
                    discriminatorType = TypeFqn(discriminatorFqn),
                    params = listOf(DiscParam("first", PrimitiveKind.UByte, 1)),
                    codec = codec,
                    dispatchProp = "first",
                ),
            framing = framing,
        )

    fun variant(
        fqn: String,
        wire: Int,
        dir: Direction = Direction.Bidirectional,
        fields: List<FieldPlan> = emptyList(),
        selfEncodes: Boolean = false,
    ): VariantPlan.NoPayload =
        VariantPlan.NoPayload(
            decl = TypeFqn(fqn),
            codec = ClassName(packageOf(fqn), simpleOf(fqn) + "Codec"),
            wire = WireMatch.Point(TypeFqn(fqn), wire),
            selfEncodes = selfEncodes,
            dir = dir,
            fields = fields,
        )

    fun rangeVariant(
        fqn: String,
        from: Int,
        to: Int,
        dir: Direction = Direction.Bidirectional,
        fields: List<FieldPlan> = emptyList(),
    ): VariantPlan.NoPayload =
        VariantPlan.NoPayload(
            decl = TypeFqn(fqn),
            codec = ClassName(packageOf(fqn), simpleOf(fqn) + "Codec"),
            wire = WireMatch.Range(TypeFqn(fqn), from, to),
            selfEncodes = true,
            dir = dir,
            fields = fields,
        )

    fun primitiveField(
        name: String,
        typeFqn: String = "kotlin.UByte",
        kind: PrimitiveKind = PrimitiveKind.UByte,
    ): FieldPlan =
        FieldPlan(
            name = name,
            type = TypeFqn(typeFqn),
            strategy =
                FieldStrategy.Primitive(
                    kind = kind,
                    wireBytes = 1,
                    order = com.ditchoom.buffer.codec.processor.ir.Endianness.Big,
                ),
        )

    fun nestedField(
        name: String,
        typeFqn: String,
    ): FieldPlan =
        FieldPlan(
            name = name,
            type = TypeFqn(typeFqn),
            strategy = FieldStrategy.NestedMessage(codec = ClassName(packageOf(typeFqn), simpleOf(typeFqn) + "Codec")),
        )

    fun externalField(
        name: String,
        typeFqn: String,
        codecFqn: String,
        contextualOverloads: Boolean = false,
    ): FieldPlan =
        FieldPlan(
            name = name,
            type = TypeFqn(typeFqn),
            strategy =
                FieldStrategy.External(
                    codec = ClassName(packageOf(codecFqn), simpleOf(codecFqn)),
                    contextualOverloads = contextualOverloads,
                ),
        )

    fun discriminatorOwnedField(
        name: String,
        parentDispatchFqn: String,
        sealedRootFqn: String = parentDispatchFqn,
    ): FieldPlan =
        FieldPlan(
            name = name,
            type = TypeFqn(parentDispatchFqn),
            strategy =
                FieldStrategy.DiscriminatorOwned(
                    parentDispatchOn = TypeFqn(parentDispatchFqn),
                    sealedRootFqn = TypeFqn(sealedRootFqn),
                ),
        )

    fun whenField(
        name: String,
        typeFqn: String,
        expr: com.ditchoom.buffer.codec.processor.ir.BooleanExpression,
    ): FieldPlan =
        FieldPlan(
            name = name,
            type = TypeFqn(typeFqn),
            strategy =
                FieldStrategy.Primitive(
                    kind = PrimitiveKind.UByte,
                    wireBytes = 1,
                    order = com.ditchoom.buffer.codec.processor.ir.Endianness.Big,
                ),
            conditionality =
                com.ditchoom.buffer.codec.processor.ir.Conditionality
                    .WhenExpr(expr),
        )

    fun spiField(
        name: String,
        typeFqn: String,
        providerId: String,
        fixedSize: Int = -1,
        raw: String = "",
    ): FieldPlan =
        FieldPlan(
            name = name,
            type = TypeFqn(typeFqn),
            strategy =
                FieldStrategy.Spi(
                    provider =
                        com.ditchoom.buffer.codec.processor.ir
                            .ProviderId(providerId),
                    descriptor =
                        com.ditchoom.buffer.codec.processor.ir.SpiDescriptor(
                            raw = raw,
                            fixedSize = fixedSize,
                        ),
                ),
        )

    fun dispatchFramingMetadata(
        framerFqn: String,
        discriminatorFqn: String,
        bodyLength: Boolean = false,
    ): RawClassMetadata {
        val parentFqn =
            if (bodyLength) {
                "com.ditchoom.buffer.codec.BodyLengthFraming"
            } else {
                "com.ditchoom.buffer.codec.DispatchFraming"
            }
        return RawClassMetadata(
            fqn = framerFqn,
            directlyDeclaredSupertypes =
                listOf(
                    RawTypeRef(
                        fqn = parentFqn,
                        name = parentFqn.substringAfterLast('.'),
                        typeArguments =
                            listOf(
                                RawTypeRef(
                                    fqn = discriminatorFqn,
                                    name = discriminatorFqn.substringAfterLast('.'),
                                    typeArguments = emptyList(),
                                    isNullable = false,
                                    isTypeParameter = false,
                                    resolved = true,
                                ),
                            ),
                        isNullable = false,
                        isTypeParameter = false,
                        resolved = true,
                    ),
                ),
        )
    }

    fun unrelatedClassMetadata(framerFqn: String): RawClassMetadata =
        RawClassMetadata(
            fqn = framerFqn,
            directlyDeclaredSupertypes =
                listOf(
                    RawTypeRef(
                        fqn = "kotlin.Any",
                        name = "Any",
                        typeArguments = emptyList(),
                        isNullable = false,
                        isTypeParameter = false,
                        resolved = true,
                    ),
                ),
        )

    fun codecMetadata(
        codecFqn: String,
        elementFqn: String,
        codecInterfaceFqn: String = "com.ditchoom.buffer.codec.Codec",
    ): RawClassMetadata =
        RawClassMetadata(
            fqn = codecFqn,
            directlyDeclaredSupertypes =
                listOf(
                    RawTypeRef(
                        fqn = codecInterfaceFqn,
                        name = codecInterfaceFqn.substringAfterLast('.'),
                        typeArguments =
                            listOf(
                                RawTypeRef(
                                    fqn = elementFqn,
                                    name = elementFqn.substringAfterLast('.'),
                                    typeArguments = emptyList(),
                                    isNullable = false,
                                    isTypeParameter = false,
                                    resolved = true,
                                ),
                            ),
                        isNullable = false,
                        isTypeParameter = false,
                        resolved = true,
                    ),
                ),
        )

    fun toMap(vararg plans: Plan): Map<TypeFqn, Plan> = plans.associateBy { it.decl }

    private fun packageOf(fqn: String): String {
        val idx = fqn.lastIndexOf('.')
        return if (idx <= 0) "" else fqn.substring(0, idx)
    }

    private fun simpleOf(fqn: String): String {
        val idx = fqn.lastIndexOf('.')
        return if (idx < 0) fqn else fqn.substring(idx + 1)
    }
}

internal fun <L, R> Either<L, R>.asLeftOrFail(): L =
    when (this) {
        is Either.Left -> value
        is Either.Right -> error("expected Left; got Right=$value")
    }

internal fun <L, R> Either<L, R>.asRightOrFail(): R =
    when (this) {
        is Either.Left -> error("expected Right; got Left=$value")
        is Either.Right -> value
    }
