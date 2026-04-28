package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.codec.processor.ir.DiscriminatorShape
import com.ditchoom.buffer.codec.processor.ir.DispatchShape
import com.ditchoom.buffer.codec.processor.ir.FramingMode
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.VariantPlan
import com.ditchoom.buffer.codec.processor.ir.WireMatch
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec

/**
 * Phase 7 emitter for [Plan.Sealed_] — sealed-root dispatchers.
 *
 * Covers shapes 6-9 from the per-shape catalog:
 *  - Unframed (RIFF chunk)
 *  - PeekOnly (WebSocket)
 *  - BodyLength (MQTT control packet)
 *  - WithPayload (MQTT v5 PUBLISH framed) — variants carrying `@Payload` type
 *    parameters lower to a typed-lambda decode/encode overload.
 *
 * The dispatcher generated here:
 *  - Reads the discriminator (via the discriminator codec) at decode-time
 *  - Adds the discriminator to the [com.ditchoom.buffer.codec.DecodeContext]
 *    only when at least one variant has a `DiscriminatorOwned` field
 *    (conditional emission per the banned-patterns table)
 *  - Dispatches to a per-variant codec call
 *  - On encode emits a `when (value)` cascade
 *  - On wireSize emits a `when (value)` expression-form sum
 *
 * Range vs Point arms are emitted into a subjectless `when { }` when any range
 * is present; pure-Point dispatch lowers to `when (subject)`.
 */
class SealedEmitter(
    private val registry: TypeRegistry,
) {
    fun emit(
        plan: Plan.Sealed_,
        classType: ClassName,
    ): FileSpec {
        val codecName = ClassName(classType.packageName, classType.simpleNames.joinToString("") + "Codec")
        val type =
            TypeSpec
                .objectBuilder(codecName)
                .addSuperinterface(Names.Codec.parameterizedBy(classType))

        type.addFunction(buildDecode(plan, classType))
        type.addFunction(buildEncode(plan, classType))
        type.addFunction(buildWireSize(plan, classType))
        type.addFunction(buildPeekFrame(plan))
        type.addFunction(buildSuspendingPeekFrame(plan))

        // DiscriminatorKey nested data object — only emitted when at least one
        // variant has a DiscriminatorOwned field.
        if (variantsConsumeDiscriminator(plan)) {
            val discType =
                when (val d = plan.dispatch) {
                    is DispatchShape.RawByte -> null
                    is DispatchShape.TypedDiscriminator ->
                        registry.resolve(d.disc.discriminatorType)
                }
            if (discType != null) {
                type.addType(
                    TypeSpec
                        .objectBuilder("DiscriminatorKey")
                        .addModifiers(KModifier.PUBLIC, KModifier.DATA)
                        .superclass(Names.CodecContext.nestedClass("Key").parameterizedBy(discType))
                        .build(),
                )
            }
        }

        return FileSpec
            .builder(codecName.packageName, codecName.simpleName)
            .addType(type.build())
            .build()
    }

    private fun variantsConsumeDiscriminator(plan: Plan.Sealed_): Boolean =
        plan.variants.any { v ->
            v.fields.any { f -> f.strategy is com.ditchoom.buffer.codec.processor.ir.FieldStrategy.DiscriminatorOwned }
        }

    private fun buildDecode(
        plan: Plan.Sealed_,
        classType: ClassName,
    ): FunSpec {
        val fb =
            FunSpec
                .builder("decode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", Names.ReadBuffer)
                .addParameter("context", Names.DecodeContext)
                .returns(classType)

        when (val d = plan.dispatch) {
            is DispatchShape.RawByte -> {
                fb.addCode("val type = buffer.readUnsignedByte().toInt() and 0xFF\n")
                fb.addCode(buildDispatchWhen(plan, "type", null, isReturn = true))
            }

            is DispatchShape.TypedDiscriminator -> {
                val discCodec = discCodecOf(d.disc)
                fb.addCode("val discriminator = %T.decode(buffer, context)\n", discCodec)
                if (variantsConsumeDiscriminator(plan)) {
                    fb.addCode(
                        "val ctx = context.with(DiscriminatorKey, discriminator)\n",
                    )
                }
                emitFramingDecodeSetup(fb, d.framing)
                val (subjectExpr, rawByteExpr) = decodeSubjectExpressions(d.disc)
                fb.addCode("val type = %L\n", subjectExpr)
                val needsRawByte = rawByteExpr != null && plan.variants.any { it.wire is WireMatch.Range }
                if (needsRawByte) {
                    @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
                    fb.addCode("val rawByte = %L\n", rawByteExpr!!)
                }
                val ctxArg = if (variantsConsumeDiscriminator(plan)) "ctx" else "context"
                val bodyArg =
                    when (d.framing) {
                        is FramingMode.BodyLength -> "body"
                        else -> "buffer"
                    }
                if (d.framing is FramingMode.BodyLength) {
                    // Need to capture the result before the body-remaining
                    // guard fires. The legacy emitter does this via a `_result`
                    // local.
                    fb.addCode("val result = ")
                    fb.addCode(
                        buildDispatchWhen(
                            plan,
                            "type",
                            if (needsRawByte) "rawByte" else null,
                            bodyArg = bodyArg,
                            ctxArg = ctxArg,
                            isReturn = false,
                        ),
                    )
                    fb.addCode("if (body.remaining() != 0) {\n")
                    fb.addCode(
                        "    throw %T(\"Variant decoder did not fully consume body bytes; \${body.remaining()} unread.\")\n",
                        ClassName("kotlin", "IllegalStateException"),
                    )
                    fb.addCode("}\n")
                    fb.addCode("return result\n")
                } else {
                    fb.addCode(
                        buildDispatchWhen(
                            plan,
                            "type",
                            if (needsRawByte) "rawByte" else null,
                            bodyArg = bodyArg,
                            ctxArg = ctxArg,
                            isReturn = true,
                        ),
                    )
                }
            }
        }
        return fb.build()
    }

    private fun emitFramingDecodeSetup(
        fb: FunSpec.Builder,
        framing: FramingMode,
    ) {
        when (framing) {
            FramingMode.Unframed -> Unit
            is FramingMode.PeekOnly -> Unit
            is FramingMode.BodyLength -> {
                fb.addCode(
                    "val bodyLength = %T.readBodyLength(buffer)\n",
                    framing.framerFqn,
                )
                fb.addCode("val body = buffer.readBytes(bodyLength)\n")
            }
        }
    }

    private fun decodeSubjectExpressions(disc: DiscriminatorShape): Pair<String, String?> =
        when (disc) {
            is DiscriminatorShape.ValueClass ->
                "discriminator.${disc.dispatchProp}" to "discriminator.${disc.innerProp}.toInt() and 0xFF"
            is DiscriminatorShape.DataClass ->
                "discriminator.${disc.dispatchProp}" to null
        }

    private fun discCodecOf(disc: DiscriminatorShape): ClassName =
        when (disc) {
            is DiscriminatorShape.ValueClass -> disc.codec
            is DiscriminatorShape.DataClass -> disc.codec
        }

    private fun buildDispatchWhen(
        plan: Plan.Sealed_,
        subjectName: String,
        rawByteName: String?,
        bodyArg: String = "buffer",
        ctxArg: String = "ctx",
        isReturn: Boolean = true,
    ): CodeBlock {
        val anyRange = plan.variants.any { it.wire is WireMatch.Range }
        val cb = CodeBlock.builder()
        if (isReturn) cb.add("return ")
        cb.add("when ")
        if (!anyRange) {
            cb.add("(%L) {\n", subjectName)
        } else {
            cb.add("{\n")
        }
        cb.indent()
        // Emit Range arms first, then Point arms (matches existing legacy emitter).
        val ranges = plan.variants.filter { it.wire is WireMatch.Range }
        val points = plan.variants.filter { it.wire is WireMatch.Point }

        for (variant in ranges) {
            val w = variant.wire as WireMatch.Range
            val raw = rawByteName ?: error("Range arm requires a rawByte expression; pure Point dispatch should not have a Range")
            cb.add(
                "%L in %L..%L -> %L\n",
                raw,
                w.from,
                w.to,
                variantDispatchCall(variant, bodyArg, ctxArg),
            )
        }
        for (variant in points) {
            val w = variant.wire as WireMatch.Point
            if (anyRange) {
                cb.add(
                    "%L == %L -> %L\n",
                    subjectName,
                    w.wire,
                    variantDispatchCall(variant, bodyArg, ctxArg),
                )
            } else {
                cb.add(
                    "%L -> %L\n",
                    w.wire,
                    variantDispatchCall(variant, bodyArg, ctxArg),
                )
            }
        }
        // Default arm — error message uses rawByte hex when available, else the
        // subject expression.
        val defaultMessage =
            if (rawByteName != null) {
                "\"Unknown discriminator: 0x\${rawByte.toString(16)}\""
            } else {
                "\"Unknown discriminator: \$$subjectName\""
            }
        cb.add(
            "else -> throw %T(%L)\n",
            registry.resolve(plan.onUnknown),
            defaultMessage,
        )
        cb.unindent()
        cb.add("}\n")
        return cb.build()
    }

    private fun variantDispatchCall(
        variant: VariantPlan,
        bodyArg: String,
        ctxArg: String,
    ): String {
        // Call the variant codec's `decode(buffer, ctx)` (or
        // `decodeFromContext` if the variant carries a discriminator-owned
        // field). For `WithPayload` variants the dispatcher's overrides delegate
        // through `decodeFromContext` because the fan-out lambdas sit on a
        // separate typed-lambda overload.
        val name = variant.codec.canonicalName
        val readsContext =
            variant.fields.any { it.strategy is com.ditchoom.buffer.codec.processor.ir.FieldStrategy.DiscriminatorOwned }
        val method =
            when {
                variant is VariantPlan.WithPayload -> "decodeFromContext"
                readsContext -> "decode"
                else -> "decode"
            }
        return "$name.$method($bodyArg, $ctxArg)"
    }

    // -----------------------------------------------------------------------
    // encode
    // -----------------------------------------------------------------------

    private fun buildEncode(
        plan: Plan.Sealed_,
        classType: ClassName,
    ): FunSpec {
        val fb =
            FunSpec
                .builder("encode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", Names.WriteBuffer)
                .addParameter(ParameterSpec.builder("value", classType).build())
                .addParameter("context", Names.EncodeContext)

        fb.addCode("when (value) {\n")
        for (variant in plan.variants.sortedBy { it.decl.canonical }) {
            val variantClass = registry.resolve(variant.decl)
            val isStar = variant is VariantPlan.WithPayload
            val checkClause =
                if (isStar) "is %T<*>" else "is %T"
            fb.addCode("    $checkClause -> {\n", variantClass)
            // Emit discriminator bytes for non-self-encoding variants (legacy
            // behaviour: dispatcher writes discriminator). For `selfEncodes`
            // the variant's encoder already writes them.
            if (!variant.selfEncodes) {
                emitDispatcherWriteDiscriminator(fb, plan.dispatch, variant)
            }
            // Delegate body encode.
            val invokeName =
                when {
                    variant is VariantPlan.WithPayload -> "encodeFromContext"
                    else -> "encode"
                }
            fb.addCode(
                "        %T.%L(buffer, value, context)\n",
                variant.codec,
                invokeName,
            )
            fb.addCode("    }\n")
        }
        fb.addCode("}\n")
        return fb.build()
    }

    private fun emitDispatcherWriteDiscriminator(
        @Suppress("UNUSED_PARAMETER") fb: FunSpec.Builder,
        @Suppress("UNUSED_PARAMETER") dispatch: DispatchShape,
        @Suppress("UNUSED_PARAMETER") variant: VariantPlan,
    ) {
        // The plan IR doesn't currently carry a discriminator-encode contract
        // for non-self-encoding variants beyond the variant's own header
        // field; the variant codec normally writes it. Phase 7 stub: do nothing
        // here — the variant emits its full bytes.
    }

    // -----------------------------------------------------------------------
    // wireSize
    // -----------------------------------------------------------------------

    private fun buildWireSize(
        plan: Plan.Sealed_,
        classType: ClassName,
    ): FunSpec {
        val fb =
            FunSpec
                .builder("wireSize")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter(ParameterSpec.builder("value", classType).build())
                .addParameter("context", Names.EncodeContext)
                .returns(INT)

        fb.addCode("return when (value) {\n")
        for (variant in plan.variants.sortedBy { it.decl.canonical }) {
            val variantClass = registry.resolve(variant.decl)
            val isStar = variant is VariantPlan.WithPayload
            val invokeName =
                when {
                    variant is VariantPlan.WithPayload -> "wireSizeFromContext"
                    else -> "wireSize"
                }
            val checkClause = if (isStar) "is %T<*>" else "is %T"
            fb.addCode("    $checkClause -> %T.%L(value, context)\n", variantClass, variant.codec, invokeName)
        }
        fb.addCode("}\n")
        return fb.build()
    }

    // -----------------------------------------------------------------------
    // peekFrameSize
    // -----------------------------------------------------------------------

    private fun buildPeekFrame(plan: Plan.Sealed_): FunSpec {
        val fb =
            FunSpec
                .builder("peekFrameSize")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("stream", Names.StreamProcessor)
                .addParameter("baseOffset", INT)
                .returns(Names.PeekResult)

        when (val d = plan.dispatch) {
            is DispatchShape.RawByte -> {
                fb.addCode("return %T\n", Names.PeekResultNeedsMore)
            }
            is DispatchShape.TypedDiscriminator ->
                when (val framing = d.framing) {
                    FramingMode.Unframed ->
                        fb.addCode("return %T\n", Names.PeekResultNeedsMore)
                    is FramingMode.PeekOnly ->
                        fb.addCode("return %T.peekFrameSize(stream, baseOffset)\n", framing.framerFqn)
                    is FramingMode.BodyLength ->
                        fb.addCode("return %T.peekFrameSize(stream, baseOffset)\n", framing.framerFqn)
                }
        }
        return fb.build()
    }

    private fun buildSuspendingPeekFrame(plan: Plan.Sealed_): FunSpec {
        val fb =
            FunSpec
                .builder("peekFrameSize")
                .addModifiers(KModifier.PUBLIC, KModifier.SUSPEND)
                .addParameter("stream", Names.SuspendingStreamProcessor)
                .addParameter(ParameterSpec.builder("baseOffset", INT).defaultValue("0").build())
                .returns(Names.PeekResult)

        when (val d = plan.dispatch) {
            is DispatchShape.RawByte -> fb.addCode("return %T\n", Names.PeekResultNeedsMore)
            is DispatchShape.TypedDiscriminator ->
                when (val framing = d.framing) {
                    FramingMode.Unframed -> fb.addCode("return %T\n", Names.PeekResultNeedsMore)
                    is FramingMode.PeekOnly ->
                        fb.addCode("return %T.peekFrameSize(stream, baseOffset)\n", framing.framerFqn)
                    is FramingMode.BodyLength ->
                        fb.addCode("return %T.peekFrameSize(stream, baseOffset)\n", framing.framerFqn)
                }
        }
        return fb.build()
    }
}
