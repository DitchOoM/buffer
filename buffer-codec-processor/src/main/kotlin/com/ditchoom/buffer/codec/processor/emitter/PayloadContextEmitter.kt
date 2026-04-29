package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.codec.processor.ir.Conditionality
import com.ditchoom.buffer.codec.processor.ir.FieldPlan
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.VariantPlan
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec

/**
 * Phase 9 Step 4-redo C2 — port of the legacy
 * `com.ditchoom.buffer.codec.processor.PayloadContextGenerator` to the new
 * Plan-IR pipeline.
 *
 * Synthesises the companion `*Context` class that becomes the receiver of
 * the typed-lambda decode overload (`*Context.(ReadBuffer) -> P`). The class
 * carries every non-payload constructor parameter so the user-supplied
 * payload-decoder lambda can read sibling fields off `this` to make
 * payload-shape decisions (e.g. MQTT v5 reading parsed properties before
 * deciding how to decode the application payload).
 *
 * Naming convention mirrors legacy:
 *
 * ```
 * "${packageName}.${enclosingSimpleNames.joinToString("")}Context"
 * ```
 *
 * Resolved per [PayloadFieldRef.contextClassFqn] (populated in C1 by
 * [com.ditchoom.buffer.codec.processor.planbuilder.PlanBuilder.payloadContextFqnFor]).
 *
 * Returns:
 *  * a `data class` with one property per non-payload field (the typical case);
 *  * an `object` when every constructor parameter is `@Payload` (rare; legacy
 *    fall-through case kept for source-diff parity).
 *
 * Wired into the new pipeline via [CodecEmitter.emitSupplemental] which
 * `ProtocolMessageProcessor.tryPipeline` writes alongside the codec file.
 * For sealed roots whose variants are still emitted by legacy
 * `CodecGenerator`, the legacy `PayloadContextGenerator` continues to
 * synthesise the variant context — the new emitter only fires for plans
 * the new pipeline owns end-to-end (top-level `@Payload` data classes after
 * Step C7's defer drop, sealed variants after Step C6's tryPipeline route).
 */
internal class PayloadContextEmitter(
    private val registry: TypeRegistry,
) {
    fun emitForLeaf(plan: Plan.Leaf): FileSpec? {
        val first = plan.payloadFields.firstOrNull() ?: return null
        return emit(
            contextClassFqn = first.contextClassFqn,
            allFields = plan.fields,
        )
    }

    fun emitForVariant(variant: VariantPlan.WithPayload): FileSpec? {
        val first = variant.payloadFields.firstOrNull() ?: return null
        return emit(
            contextClassFqn = first.contextClassFqn,
            allFields = variant.fields,
        )
    }

    private fun emit(
        contextClassFqn: String,
        allFields: List<FieldPlan>,
    ): FileSpec {
        val pkg = contextClassFqn.substringBeforeLast('.', missingDelimiterValue = "")
        val simpleName = contextClassFqn.substringAfterLast('.')
        val nonPayload = allFields.filter { it.strategy !is FieldStrategy.PayloadSlot }
        val typeSpec =
            if (nonPayload.isEmpty()) {
                TypeSpec.objectBuilder(simpleName).build()
            } else {
                val ctor = FunSpec.constructorBuilder()
                val builder = TypeSpec.classBuilder(simpleName).addModifiers(KModifier.DATA)
                for (f in nonPayload) {
                    val typeName = nonPayloadFieldTypeName(f)
                    ctor.addParameter(ParameterSpec.builder(f.name, typeName).build())
                    builder.addProperty(
                        PropertySpec.builder(f.name, typeName).initializer(f.name).build(),
                    )
                }
                builder.primaryConstructor(ctor.build()).build()
            }
        return FileSpec.builder(pkg, simpleName).addType(typeSpec).build()
    }

    /**
     * Derive the KotlinPoet [TypeName] for a non-payload field on a
     * payload-bearing variant. The new IR is KSP-free so we can't reach for
     * `KSType.toTypeName()`; instead we route through the field strategy:
     *
     *  * Primitives use [TypeRegistry.primitiveTypeName] so `kotlin.UByte`
     *    becomes the `U_BYTE` constant rather than `ClassName("kotlin", "UByte")`.
     *  * `VarInt` is always Kotlin `Int`.
     *  * `StringField` is `kotlin.String`.
     *  * `Collection_` resolves the container ([FieldPlan.type]) plus the
     *    element type ([FieldStrategy.Collection_.elementCodec.elementType]).
     *  * `NestedMessage` / `External` / `DiscriminatorOwned` / `Spi` /
     *    `ValueClass` resolve [FieldPlan.type] directly.
     *  * `PayloadSlot` is filtered out by the caller.
     *
     * Nullability mirrors [LeafEmitter]'s `isNullableConditional` — a
     * `Conditionality.WhenExpr` field maps to `T?`, otherwise `T`.
     */
    private fun nonPayloadFieldTypeName(field: FieldPlan): TypeName {
        val base: TypeName =
            when (val s = field.strategy) {
                is FieldStrategy.Primitive -> TypeRegistry.primitiveTypeName(s.kind)
                is FieldStrategy.VarInt -> INT
                is FieldStrategy.StringField -> STRING
                is FieldStrategy.Collection_ -> {
                    val container = registry.resolve(field.type)
                    val element = registry.resolve(s.elementCodec.elementType)
                    container.parameterizedBy(element)
                }
                is FieldStrategy.NestedMessage -> registry.resolve(field.type)
                is FieldStrategy.External -> {
                    val container = registry.resolve(field.type)
                    if (s.typeArguments.isEmpty()) {
                        container
                    } else {
                        container.parameterizedBy(*s.typeArguments.map { registry.resolve(it) }.toTypedArray())
                    }
                }
                is FieldStrategy.DiscriminatorOwned -> registry.resolve(field.type)
                is FieldStrategy.Spi -> registry.resolve(field.type)
                is FieldStrategy.ValueClass -> registry.resolve(field.type)
                is FieldStrategy.PayloadSlot ->
                    error(
                        "PayloadContextEmitter received a PayloadSlot field; payload fields must " +
                            "be filtered out before reaching the type-name resolver.",
                    )
            }
        return if (field.conditionality is Conditionality.WhenExpr) base.copy(nullable = true) else base
    }
}
