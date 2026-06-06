package com.ditchoom.buffer.codec.processor

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName

/*
 * CodecEmitterDispatch — the sealed-dispatch emit layer extracted from
 * CodecEmitter (step 4): the buildDispatch / buildFramedByDispatchOn /
 * buildDispatchOnAggregating functions and their DispatchShape, DispatchVariant,
 * PayloadCodecSource and LengthSource extension helpers, plus the shared
 * appendPeekFixedScalar value-class peek reconstructor. All are stateless emit
 * helpers (no batchCounter, codeGenerator or logger), moved verbatim to
 * package-internal top-level so CodecEmitter calls them unqualified and
 * unchanged. Byte-identical codegen verified by the snapshot suite.
 */

/** Apply the source class's visibility (issue #175); no-op when public. */
internal fun TypeSpec.Builder.withVisibility(modifier: KModifier?): TypeSpec.Builder =
    if (modifier != null) addModifiers(modifier) else this

/** Apply the unified [CodecVisibility]; Internal → INTERNAL, Public → no-op. */
internal fun TypeSpec.Builder.withVisibility(visibility: CodecVisibility): TypeSpec.Builder =
    when (visibility) {
        CodecVisibility.Internal -> addModifiers(KModifier.INTERNAL)
        CodecVisibility.Public -> this
    }

/**
 * The parent type the decode returns: bare for [Genericity.Monomorphic],
 * parameterized by the type variable for [Genericity.Generic]. This is the
 * type used in the codec's `Codec<...>` superinterface, the encode/decode
 * signatures, and the generic variant-codec constructor calls.
 */
internal fun DispatchShape.parentTypeRef(): TypeName =
    when (val g = genericity) {
        Genericity.Monomorphic -> parentClassName
        is Genericity.Generic ->
            parentClassName.parameterizedBy(
                TypeVariableName(g.binding.typeVariableName),
            )
    }

/** The `when` label for one variant, in the discriminator's radix. */
internal fun DispatchVariant.dispatchLabel(format: LabelFormat): CodeBlock =
    when (format) {
        // Pre-formatted hex string passed as %L after the `0x` prefix —
        // matches the simple path's `"0x%L"`.
        LabelFormat.Hex ->
            CodeBlock.of(
                "0x%L",
                dispatchValue
                    .toString(16)
                    .padStart(2, '0')
                    .uppercase(),
            )
        // Int passed as %L so KotlinPoet underscores large decimals
        // (e.g. `2_048`) — matches the @DispatchOn path.
        LabelFormat.Decimal -> CodeBlock.of("%L", dispatchValue)
    }

/** How the dispatcher references this variant's codec at a call site. */
internal fun DispatchVariant.codecReceiver(): CodeBlock =
    when (val ref = codecRef) {
        VariantCodecRef.StaticObject -> CodeBlock.of("%T", codecClassName)
        is VariantCodecRef.GenericInstance -> CodeBlock.of("%L", ref.fieldName)
    }

/** The expected-set diagnostic string in the discriminator's radix. */
internal fun expectedDispatchSet(shape: DispatchShape): String =
    shape.variants.joinToString(prefix = "one of {", postfix = "}") { variant ->
        when (shape.discriminator.labelFormat) {
            LabelFormat.Hex ->
                "0x${variant.dispatchValue.toString(16).padStart(2, '0').uppercase()}"
            LabelFormat.Decimal -> variant.dispatchValue.toString()
        }
    }

/**
 * THE unified dispatch decode builder. Reproduces the (now-removed)
 * simple-@PacketType and @DispatchOn decode output byte-for-byte by
 * forking on [Discriminator] ownership/labelFormat.
 */
internal fun buildDispatchDecodeFun(shape: DispatchShape): FunSpec {
    val body = CodeBlock.builder()
    val parentTypeRef = shape.parentTypeRef()
    // The `actual = ...` argument to DecodeException — hex-formatted for
    // FixedByte, the decimal value for ValueClass.
    val actualFormat: String

    when (val disc = shape.discriminator) {
        Discriminator.FixedByte -> {
            // Simple @PacketType: consume the discriminator byte.
            body.addStatement("val discriminatorPosition = buffer.position()")
            body.addStatement("val discriminator = buffer.readUByte().toInt()")
            actualFormat = "0x\${discriminator.toString(16).padStart(2, '0').uppercase()}"
            body.beginControlFlow("return when (discriminator)")
        }
        is Discriminator.ValueClass -> {
            // @DispatchOn: peek the discriminator via its codec, rewind so
            // the variant re-reads it as its first value-class field.
            body.addStatement("val discriminatorPosition = buffer.position()")
            body.addStatement(
                "val __discriminator = %T.decode(buffer, context)",
                disc.codecClassName,
            )
            body.addStatement("buffer.position(discriminatorPosition)")
            body.addStatement(
                "val __dispatchValue = %L",
                dispatchValueIntCoercion(
                    disc.dispatchValueKind,
                    "__discriminator.${disc.dispatchValueProperty}",
                ),
            )
            actualFormat = "\${__dispatchValue}"
            body.beginControlFlow("return when (__dispatchValue)")
        }
        is Discriminator.Varint -> {
            // Varint @DispatchOn: identical decode shape to ValueClass —
            // peek the variable-width discriminator via the value class's
            // codec, rewind, and let the variant re-read it. The codec
            // (not a fixed inner-scalar read) does the self-delimiting read.
            body.addStatement("val discriminatorPosition = buffer.position()")
            body.addStatement(
                "val __discriminator = %T.decode(buffer, context)",
                disc.codecClassName,
            )
            body.addStatement("buffer.position(discriminatorPosition)")
            body.addStatement(
                "val __dispatchValue = %L",
                dispatchValueIntCoercion(
                    disc.dispatchValueKind,
                    "__discriminator.${disc.dispatchValueProperty}",
                ),
            )
            actualFormat = "\${__dispatchValue}"
            body.beginControlFlow("return when (__dispatchValue)")
        }
    }

    for (variant in shape.variants) {
        body.addStatement(
            "%L -> %L.decode(buffer, context)",
            variant.dispatchLabel(shape.discriminator.labelFormat),
            variant.codecReceiver(),
        )
    }

    when (val fc = shape.forwardCompat) {
        is ForwardCompat.Enabled -> {
            val framedBy =
                (shape.framing as? Framing.Framed)?.config
                    ?: error("@ForwardCompatible requires @FramedBy; analyzer should not have set forwardCompat")
            appendForwardCompatibleDecodeElse(body, framedBy, fc.config)
        }
        ForwardCompat.Disabled -> {
            body.beginControlFlow("else ->")
            body.addStatement(
                "throw %T(fieldPath = %S, bufferPosition = discriminatorPosition, expected = %S, actual = %P)",
                DECODE_EXCEPTION_CN,
                "${shape.parentSimpleName}.discriminator",
                expectedDispatchSet(shape),
                actualFormat,
            )
            body.endControlFlow()
        }
    }
    body.endControlFlow()

    val builder =
        FunSpec
            .builder("decode")
            .addParameter("buffer", READ_BUFFER_CN)
            .addParameter("context", DECODE_CONTEXT_CN)
            .returns(parentTypeRef)
            .addCode(body.build())
    // `@FramedBy` dispatchers don't implement `Codec<T>` (the encode
    // contract differs), so decode is a plain object/class function rather
    // than an override. Every non-framed dispatcher overrides Codec.decode.
    if (shape.framing is Framing.Unframed) {
        builder.addModifiers(KModifier.OVERRIDE)
    }
    return builder.build()
}

/**
 * The encode-side `is` branch type for a variant on the unified shape.
 * Generic variants smart-cast to their star-projected form
 * (`Foo.Data<*>`) — the dispatcher's `value: Foo<P>` doesn't prove the
 * runtime variant's `P` matches; non-generic variants use the bare class.
 */
internal fun DispatchVariant.branchTypeName(genericity: Genericity): TypeName =
    when (codecRef) {
        VariantCodecRef.StaticObject -> className
        is VariantCodecRef.GenericInstance -> {
            require(genericity is Genericity.Generic) {
                "Generic variant $simpleName requires the dispatcher to bind a payload type parameter"
            }
            className.parameterizedBy(com.squareup.kotlinpoet.STAR)
        }
    }

/**
 * The typed cast TypeName for the variant codec call on the unified shape.
 * Generic variants need `value as Foo.Data<P>` so the variant codec's
 * `<P : Payload>` accepts the value.
 */
internal fun DispatchVariant.typedRef(genericity: Genericity): TypeName =
    when (codecRef) {
        VariantCodecRef.StaticObject -> className
        is VariantCodecRef.GenericInstance -> {
            require(genericity is Genericity.Generic)
            className.parameterizedBy(TypeVariableName(genericity.binding.typeVariableName))
        }
    }

/**
 * THE unified NON-framed dispatch encode builder. Reproduces the
 * (now-removed) simple-@PacketType and non-framed @DispatchOn encode
 * output byte-for-byte by forking on [Discriminator] ownership.
 *
 * - [DiscriminatorOwnership.ConsumedByDispatcher] (FixedByte): the
 *   dispatcher WRITES the discriminator byte (always a hex literal,
 *   regardless of labelFormat — plan risk #2) then delegates to the
 *   variant codec.
 * - [DiscriminatorOwnership.ReReadByVariant] (ValueClass): the dispatcher
 *   writes NOTHING; the variant self-frames the discriminator. Generic
 *   variants get star-projected `is X<*>` branches, a `value as X<P>` cast,
 *   and the function gets `@Suppress("UNCHECKED_CAST")` when any variant is
 *   generic (plan risk #5).
 *
 * Does NOT cover the framed (`@FramedBy`) encode — that keeps the distinct
 * `(value, context, factory): ReadBuffer` signature in
 * [buildFramedByDispatchOnEncodeFun].
 */
internal fun buildDispatchEncodeFun(shape: DispatchShape): FunSpec {
    val parentTypeRef = shape.parentTypeRef()
    val body = CodeBlock.builder()

    when (shape.discriminator.ownership) {
        DiscriminatorOwnership.ConsumedByDispatcher -> {
            // Generic variants (a `<P : Payload>` parent under simple
            // @PacketType) star-project their `is X<*>` branch and cast
            // `value as X<P>` so the injected variant codec instance
            // accepts it — identical machinery to the ReReadByVariant
            // branch below. For monomorphic shapes `branchTypeName` is the
            // bare class and `codecReceiver` the static object, so the
            // output is byte-identical to the pre-generics simple path.
            val anyGeneric =
                shape.variants.any { it.codecRef is VariantCodecRef.GenericInstance }
            if (anyGeneric) {
                body.add("@Suppress(%S)\n", "UNCHECKED_CAST")
            }
            body.beginControlFlow("when (value)")
            for (variant in shape.variants) {
                body.beginControlFlow("is %T ->", variant.branchTypeName(shape.genericity))
                // The discriminator literal is ALWAYS hex (risk #2).
                body.addStatement(
                    "buffer.writeUByte(0x%L.toUByte())",
                    variant.dispatchValue
                        .toString(16)
                        .padStart(2, '0')
                        .uppercase(),
                )
                if (variant.codecRef is VariantCodecRef.GenericInstance) {
                    body.addStatement(
                        "%L.encode(buffer, value as %T, context)",
                        variant.codecReceiver(),
                        variant.typedRef(shape.genericity),
                    )
                } else {
                    body.addStatement(
                        "%L.encode(buffer, value, context)",
                        variant.codecReceiver(),
                    )
                }
                body.endControlFlow()
            }
            body.endControlFlow()
        }
        DiscriminatorOwnership.ReReadByVariant -> {
            val anyGeneric =
                shape.variants.any { it.codecRef is VariantCodecRef.GenericInstance }
            if (anyGeneric) {
                body.add("@Suppress(%S)\n", "UNCHECKED_CAST")
            }
            body.beginControlFlow("when (value)")
            for (variant in shape.variants) {
                val branchType = variant.branchTypeName(shape.genericity)
                if (variant.codecRef is VariantCodecRef.GenericInstance) {
                    body.addStatement(
                        "is %T -> %L.encode(buffer, value as %T, context)",
                        branchType,
                        variant.codecReceiver(),
                        variant.typedRef(shape.genericity),
                    )
                } else {
                    body.addStatement(
                        "is %T -> %L.encode(buffer, value, context)",
                        branchType,
                        variant.codecReceiver(),
                    )
                }
            }
            body.endControlFlow()
        }
    }

    return FunSpec
        .builder("encode")
        .addModifiers(KModifier.OVERRIDE)
        .addParameter("buffer", WRITE_BUFFER_CN)
        .addParameter("value", parentTypeRef)
        .addParameter("context", ENCODE_CONTEXT_CN)
        .addCode(body.build())
        .build()
}

/**
 * THE unified NON-framed dispatch wireSize builder. Reproduces the
 * (now-removed) simple-@PacketType and non-framed @DispatchOn wireSize
 * output byte-for-byte by forking on [Discriminator] ownership — the
 * discriminator-counted-once invariant (plan risk #1).
 *
 * - [DiscriminatorOwnership.ConsumedByDispatcher] (FixedByte): the
 *   DISPATCHER aggregates the discriminator byte — `1 +` the variant body
 *   size. Per variant, fork on [VariantWireSize]: LiteralExact →
 *   `Exact(1 + bytes)`, RuntimeExact → runtime `Exact(1 + inner)`, BackPatch
 *   → `BackPatch`. Delegated is unreachable here (FixedByte variants are
 *   classified concretely).
 * - [DiscriminatorOwnership.ReReadByVariant] (ValueClass): the dispatcher
 *   PURE-DELEGATES (NO `1 +`) — the variant already counts its self-framed
 *   re-read discriminator. `variant.wireSize` is ignored (it's Delegated).
 *   Generic variants get star-projected `is X<*>` branches, a `value as X<P>`
 *   cast, and the function gets `@Suppress("UNCHECKED_CAST")` when any
 *   variant is generic (plan risk #5).
 *
 * Does NOT cover the framed (`@FramedBy`) dispatchers — those OMIT wireSize
 * entirely (plan risk #6); this builder is only routed at the non-framed
 * call sites.
 */
internal fun buildDispatchWireSizeFun(shape: DispatchShape): FunSpec {
    val parentTypeRef = shape.parentTypeRef()
    val body = CodeBlock.builder()

    when (shape.discriminator.ownership) {
        DiscriminatorOwnership.ConsumedByDispatcher -> {
            body.beginControlFlow("return when (value)")
            for (variant in shape.variants) {
                // Generic variants (a `<P : Payload>` parent under simple
                // @PacketType) need the star-projected `is X<*>` branch so
                // the runtime match succeeds. For monomorphic shapes
                // `branchTypeName` is the bare class, so existing output is
                // unchanged. A generic variant always carries
                // `@RemainingBytes val: P`, which classifyVariantWireSize
                // maps to BackPatch — so the RuntimeExact static-codec call
                // below is only ever reached by monomorphic variants.
                val branchType = variant.branchTypeName(shape.genericity)
                when (val ws = variant.wireSize) {
                    is VariantWireSize.LiteralExact ->
                        body.addStatement(
                            "is %T -> %T.Exact(%L)",
                            branchType,
                            WIRE_SIZE_CN,
                            1 + ws.bytes,
                        )
                    is VariantWireSize.BackPatch ->
                        body.addStatement(
                            "is %T -> %T.BackPatch",
                            branchType,
                            WIRE_SIZE_CN,
                        )
                    is VariantWireSize.RuntimeExact -> {
                        body.beginControlFlow("is %T ->", branchType)
                        body.addStatement(
                            "val inner = (%L.wireSize(value, context) as %T.Exact).bytes",
                            variant.codecReceiver(),
                            WIRE_SIZE_CN,
                        )
                        body.addStatement("%T.Exact(1 + inner)", WIRE_SIZE_CN)
                        body.endControlFlow()
                    }
                    // Delegated is produced only by the @DispatchOn adapter
                    // (ReReadByVariant). ConsumedByDispatcher variants are
                    // always classified concretely, so this is unreachable.
                    VariantWireSize.Delegated ->
                        error("ConsumedByDispatcher variant ${variant.simpleName} is never Delegated")
                }
            }
            body.endControlFlow()
        }
        DiscriminatorOwnership.ReReadByVariant -> {
            val anyGeneric =
                shape.variants.any { it.codecRef is VariantCodecRef.GenericInstance }
            if (anyGeneric) {
                body.add("@Suppress(%S)\n", "UNCHECKED_CAST")
            }
            body.beginControlFlow("return when (value)")
            for (variant in shape.variants) {
                val branchType = variant.branchTypeName(shape.genericity)
                if (variant.codecRef is VariantCodecRef.GenericInstance) {
                    body.addStatement(
                        "is %T -> %L.wireSize(value as %T, context)",
                        branchType,
                        variant.codecReceiver(),
                        variant.typedRef(shape.genericity),
                    )
                } else {
                    body.addStatement(
                        "is %T -> %L.wireSize(value, context)",
                        branchType,
                        variant.codecReceiver(),
                    )
                }
            }
            body.endControlFlow()
        }
    }

    return FunSpec
        .builder("wireSize")
        .addModifiers(KModifier.OVERRIDE)
        .addParameter("value", parentTypeRef)
        .addParameter("context", ENCODE_CONTEXT_CN)
        .returns(WIRE_SIZE_CN)
        .addCode(body.build())
        .build()
}

/**
 * THE unified NON-framed dispatch peekFrameSize builder. Reproduces the
 * (now-removed) simple-@PacketType `buildDispatcherPeekFrameFun` and
 * non-framed @DispatchOn `buildDispatchOnPeekFun` output byte-for-byte by
 * forking on [Discriminator] ownership — the discriminator-counted-once
 * invariant (plan §6 risk #1) and the hex-vs-decimal label radix (risk #2).
 *
 * - [DiscriminatorOwnership.ConsumedByDispatcher] (FixedByte): the
 *   DISPATCHER consumes the byte. Min guard `< 1`; inline
 *   `stream.peekByte(baseOffset)` discriminator; hex `when` labels; delegate
 *   to the variant at `baseOffset + 1` and wrap a `Complete` result with
 *   `Complete(1 + inner.bytes)` (the variant body excludes the byte).
 * - [DiscriminatorOwnership.ReReadByVariant] (ValueClass): the dispatcher
 *   PEEKS the discriminator's inner scalar, reconstructs the value class,
 *   coerces the dispatch value. Min guard `< innerWidth`; decimal `when`
 *   labels; PURE-DELEGATE at `baseOffset` (NO `1 +`) — the variant peek
 *   already counts its self-framed re-read discriminator.
 *
 * Does NOT cover the framed (`@FramedBy`) single-walker peek — that keeps
 * [buildFramedByDispatchOnPeekFun]; this builder is only routed at the
 * non-framed call sites.
 */
internal fun buildDispatchPeekFun(shape: DispatchShape): FunSpec {
    val body = CodeBlock.builder()

    // Consumer-supplied frame-size override: a companion object on the sealed
    // parent implementing `FrameDetector` frames the whole dispatch wholesale
    // (RFC 6455 WebSocket: opcode-independent bit-packed escape length +
    // folded mask, which the per-variant peek walk can't derive). Delegate
    // and return, bypassing discriminator routing.
    shape.customPeek?.let { customPeek ->
        return FunSpec
            .builder("peekFrameSize")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("stream", STREAM_PROCESSOR_CN)
            .addParameter("baseOffset", INT)
            .returns(PEEK_RESULT_CN)
            .addStatement("return %T.peekFrameSize(stream, baseOffset)", customPeek)
            .build()
    }

    when (val disc = shape.discriminator) {
        Discriminator.FixedByte -> {
            body.addStatement(
                "if (stream.available() - baseOffset < %L) return %T.NeedsMoreData",
                disc.wireWidth.requireFixed("dispatch peek"),
                PEEK_RESULT_CN,
            )
            body.addStatement(
                "val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF",
            )
            body.beginControlFlow("return when (discriminator)")
            for (variant in shape.variants) {
                body.beginControlFlow(
                    "%L ->",
                    variant.dispatchLabel(shape.discriminator.labelFormat),
                )
                body.beginControlFlow(
                    "when (val inner = %L.peekFrameSize(stream, baseOffset + 1))",
                    variant.codecReceiver(),
                )
                body.addStatement(
                    "is %T.Complete -> %T.Complete(1 + inner.bytes)",
                    PEEK_RESULT_CN,
                    PEEK_RESULT_CN,
                )
                body.addStatement("else -> inner")
                body.endControlFlow()
                body.endControlFlow()
            }
            body.beginControlFlow("else ->")
            body.addStatement(
                "throw %T(fieldPath = %S, bufferPosition = baseOffset, expected = %S, actual = %P)",
                DECODE_EXCEPTION_CN,
                "${shape.parentSimpleName}.discriminator",
                expectedDispatchSet(shape),
                "0x\${discriminator.toString(16).padStart(2, '0').uppercase()}",
            )
            body.endControlFlow()
            body.endControlFlow()
        }
        is Discriminator.ValueClass -> {
            body.addStatement(
                "if (stream.available() - baseOffset < %L) return %T.NeedsMoreData",
                disc.wireWidth.requireFixed("dispatch peek"),
                PEEK_RESULT_CN,
            )
            // Peek the discriminator's inner-scalar bytes at baseOffset and
            // reconstruct the value class — the same path the value-class
            // @When source uses.
            appendPeekFixedScalar(
                body = body,
                kind = disc.innerKind,
                targetVar = "__discRaw",
                offsetExpr = "0",
                wireOrder = disc.innerWireOrder,
            )
            body.addStatement(
                "val __discriminator = %T(__discRaw)",
                disc.className,
            )
            body.addStatement(
                "val __dispatchValue = %L",
                dispatchValueIntCoercion(
                    disc.dispatchValueKind,
                    "__discriminator.${disc.dispatchValueProperty}",
                ),
            )
            body.beginControlFlow("return when (__dispatchValue)")
            for (variant in shape.variants) {
                // Variant.peek counts the discriminator bytes in its own
                // header field, so we delegate at baseOffset, not + 1.
                body.addStatement(
                    "%L -> %L.peekFrameSize(stream, baseOffset)",
                    variant.dispatchLabel(shape.discriminator.labelFormat),
                    variant.codecReceiver(),
                )
            }
            body.beginControlFlow("else ->")
            body.addStatement(
                "throw %T(fieldPath = %S, bufferPosition = baseOffset, expected = %S, actual = %P)",
                DECODE_EXCEPTION_CN,
                "${shape.parentSimpleName}.discriminator",
                expectedDispatchSet(shape),
                "\${__dispatchValue}",
            )
            body.endControlFlow()
            body.endControlFlow()
        }
        is Discriminator.Varint -> {
            // Variable-width discriminator: its byte count isn't known
            // statically, so measure it via the value class codec's own
            // peekFrameSize (which delegates to the consumer's
            // VariableLengthCodec). If the prefix is too short to frame the
            // discriminator, propagate NeedsMoreData / NoFraming unchanged.
            body.addStatement(
                "val __discFrame = %T.peekFrameSize(stream, baseOffset)",
                disc.codecClassName,
            )
            body.beginControlFlow("if (__discFrame !is %T.Complete)", PEEK_RESULT_CN)
            body.addStatement("return __discFrame")
            body.endControlFlow()
            // Decode the discriminator from a non-consuming view of exactly
            // its measured width to recover the dispatch value, then PURE-
            // DELEGATE to the variant at baseOffset (the variant re-reads the
            // self-delimiting discriminator as its first field, so no `1 +`).
            body.addStatement(
                "val __discView = stream.peekBuffer(baseOffset, __discFrame.bytes) ?: return %T.NeedsMoreData",
                PEEK_RESULT_CN,
            )
            body.beginControlFlow("val __dispatchValue = try")
            body.addStatement(
                "val __discriminator = %T.decode(__discView, %T.Empty)",
                disc.codecClassName,
                DECODE_CONTEXT_CN,
            )
            body.addStatement(
                "%L",
                dispatchValueIntCoercion(
                    disc.dispatchValueKind,
                    "__discriminator.${disc.dispatchValueProperty}",
                ),
            )
            body.nextControlFlow("finally")
            body.addStatement(
                "(__discView as? %T)?.freeNativeMemory()",
                PLATFORM_BUFFER_CN,
            )
            body.endControlFlow()
            body.beginControlFlow("return when (__dispatchValue)")
            for (variant in shape.variants) {
                body.addStatement(
                    "%L -> %L.peekFrameSize(stream, baseOffset)",
                    variant.dispatchLabel(shape.discriminator.labelFormat),
                    variant.codecReceiver(),
                )
            }
            body.beginControlFlow("else ->")
            body.addStatement(
                "throw %T(fieldPath = %S, bufferPosition = baseOffset, expected = %S, actual = %P)",
                DECODE_EXCEPTION_CN,
                "${shape.parentSimpleName}.discriminator",
                expectedDispatchSet(shape),
                "\${__dispatchValue}",
            )
            body.endControlFlow()
            body.endControlFlow()
        }
    }

    return FunSpec
        .builder("peekFrameSize")
        .addModifiers(KModifier.OVERRIDE)
        .addParameter("stream", STREAM_PROCESSOR_CN)
        .addParameter("baseOffset", INT)
        .returns(PEEK_RESULT_CN)
        .addCode(body.build())
        .build()
}

/**
 * THE unified dispatch file-spec builder (plan stage 7). Reproduces all
 * four shell variants from one [DispatchShape] by forking on
 * [Genericity] × [Framing]:
 *
 * - **Monomorphic + Unframed** — `object FooCodec : Codec<Parent>` with
 *   decode/encode/wireSize/peek (the unified fun builders, each carrying
 *   `OVERRIDE`). Covers the simple `@PacketType` path and the non-generic
 *   non-framed `@DispatchOn` path. No `Partial` member (neither legacy
 *   path emitted one here).
 * - **Monomorphic + Framed** — `object FooCodec` with NO `Codec<Parent>`
 *   superinterface, decode + framed encode + framed peek (single walker),
 *   NO wireSize (plan risk #6 triple-coupling).
 * - **Generic + Unframed** — `class FooCodec<P : Payload>(private val
 *   payloadCodec: Codec<P>) : Codec<Parent<P>>` + one private
 *   `val <field> = <VariantCodec>(payloadCodec)` per generic variant +
 *   decode/encode/wireSize/peek + the aggregator companion (`Partial<P>`).
 * - **Generic + Framed** — `class FooCodec<P>(payloadCodec)` with NO
 *   `Codec<Parent<P>>` superinterface + decode + framed encode + framed
 *   peek + aggregator companion, NO wireSize.
 *
 * Visibility comes from [DispatchShape.visibility] via [withVisibility].
 * `OVERRIDE` on the funs is handled inside the fun builders (Unframed →
 * override; framed encode/peek → none), so this shell never re-applies it.
 */
internal fun buildDispatchFileSpec(shape: DispatchShape): FileSpec {
    val parentTypeRef = shape.parentTypeRef()
    val framed = shape.framing is Framing.Framed
    val codecType =
        when (val genericity = shape.genericity) {
            Genericity.Monomorphic -> {
                val builder =
                    TypeSpec
                        .objectBuilder(shape.codecSimpleName)
                        .withVisibility(shape.visibility)
                if (!framed) {
                    builder.addSuperinterface(CODEC_CN.parameterizedBy(parentTypeRef))
                }
                builder.addFunction(buildDispatchDecodeFun(shape))
                if (framed) {
                    builder.addFunction(buildFramedByDispatchOnEncodeFun(shape, parentTypeRef))
                    builder.addFunction(buildFramedByDispatchOnPeekFun(shape))
                } else {
                    builder.addFunction(buildDispatchEncodeFun(shape))
                    builder.addFunction(buildDispatchWireSizeFun(shape))
                    builder.addFunction(buildDispatchPeekFun(shape))
                }
                builder.build()
            }
            is Genericity.Generic -> {
                val binding = genericity.binding
                val typeVar = TypeVariableName(binding.typeVariableName, binding.bound)
                val codecOfP = CODEC_CN.parameterizedBy(typeVar)
                val builder =
                    TypeSpec
                        .classBuilder(shape.codecSimpleName)
                        .withVisibility(shape.visibility)
                        .addTypeVariable(typeVar)
                        .primaryConstructor(
                            FunSpec
                                .constructorBuilder()
                                .addParameter(binding.codecParameterName, codecOfP)
                                .build(),
                        ).addProperty(
                            com.squareup.kotlinpoet.PropertySpec
                                .builder(binding.codecParameterName, codecOfP, KModifier.PRIVATE)
                                .initializer(binding.codecParameterName)
                                .build(),
                        )
                if (!framed) {
                    builder.addSuperinterface(CODEC_CN.parameterizedBy(parentTypeRef))
                }
                for (variant in shape.variants) {
                    val ref = variant.codecRef as? VariantCodecRef.GenericInstance ?: continue
                    val fieldType = variant.codecClassName.parameterizedBy(typeVar)
                    builder.addProperty(
                        com.squareup.kotlinpoet.PropertySpec
                            .builder(ref.fieldName, fieldType, KModifier.PRIVATE)
                            .initializer("%T(%L)", variant.codecClassName, binding.codecParameterName)
                            .build(),
                    )
                }
                builder.addFunction(buildDispatchDecodeFun(shape))
                if (framed) {
                    builder.addFunction(buildFramedByDispatchOnEncodeFun(shape, parentTypeRef))
                    builder.addFunction(buildFramedByDispatchOnPeekFun(shape))
                } else {
                    builder.addFunction(buildDispatchEncodeFun(shape))
                    builder.addFunction(buildDispatchWireSizeFun(shape))
                    builder.addFunction(buildDispatchPeekFun(shape))
                }
                // The `decodeAggregating` companion (per-call payload-codec
                // selection) is a ReReadByVariant/@DispatchOn construct — it
                // peeks+rewinds the discriminator. A simple @PacketType
                // (FixedByte) generic dispatcher consumes the byte and relies
                // on the constructor-injected payloadCodec, so it omits the
                // aggregator. (buildDispatchOnAggregatorCompanion requires a
                // ValueClass discriminator and would otherwise error.)
                if (shape.discriminator is Discriminator.ValueClass) {
                    builder.addType(buildDispatchOnAggregatorCompanion(shape, binding))
                }
                builder.build()
            }
        }
    return FileSpec
        .builder(shape.packageName, shape.codecSimpleName)
        .addType(codecType)
        .build()
}

/**
 * Encode for an `@FramedBy@DispatchOn`
 * dispatcher. The signature differs from the non-framed
 * [buildDispatchEncodeFun]
 * by dropping the `WriteBuffer` parameter, adding `factory`, and
 * returning `ReadBuffer`. The `when` body still routes by variant,
 * but every branch calls the variant codec's framed encode (which
 * itself returns `ReadBuffer`). No `OVERRIDE` modifier — the
 * dispatcher does not implement `Codec<T>`.
 *
 * Generic variants are smart-cast to their star-projected form
 * (`is Foo.Data<*>`) and then explicitly cast to `Foo.Data<P>` at
 * the call site so the variant codec's `<P : Payload>` accepts the
 * value (mirrors [buildDispatchEncodeFun]'s behaviour).
 */
internal fun buildFramedByDispatchOnEncodeFun(
    shape: DispatchShape,
    parentTypeRef: TypeName,
): FunSpec {
    val body = CodeBlock.builder()
    val anyGeneric = shape.variants.any { it.codecRef is VariantCodecRef.GenericInstance }
    if (anyGeneric) {
        body.add("@Suppress(%S)\n", "UNCHECKED_CAST")
    }
    body.beginControlFlow("return when (value)")
    for (variant in shape.variants) {
        val branchType = variant.branchTypeName(shape.genericity)
        if (variant.codecRef is VariantCodecRef.GenericInstance) {
            val typedRef = variant.typedRef(shape.genericity)
            body.addStatement(
                "is %T -> %L.encode(value as %T, context, factory)",
                branchType,
                variant.codecReceiver(),
                typedRef,
            )
        } else {
            body.addStatement(
                "is %T -> %L.encode(value, context, factory)",
                branchType,
                variant.codecReceiver(),
            )
        }
    }
    when (val fc = shape.forwardCompat) {
        is ForwardCompat.Enabled -> {
            val framedBy =
                (shape.framing as? Framing.Framed)?.config
                    ?: error("@ForwardCompatible requires @FramedBy; analyzer should not have set forwardCompat")
            appendForwardCompatibleEncodeArm(body, framedBy, fc.config)
        }
        ForwardCompat.Disabled -> Unit
    }
    body.endControlFlow()
    return FunSpec
        .builder("encode")
        .addParameter("value", parentTypeRef)
        .addParameter("context", ENCODE_CONTEXT_CN)
        .addParameter("factory", BUFFER_FACTORY_CN)
        .returns(READ_BUFFER_CN)
        .addCode(body.build())
        .build()
}

/**
 * `peekFrameSize` for an `@FramedBy`
 * `@DispatchOn` dispatcher. Every variant peeks identically (same
 * header width, same prefix codec — that's the point of inheriting
 * `@FramedBy` from the parent), so the per-variant dispatch
 * collapses to a single header+prefix walker.
 */
internal fun buildFramedByDispatchOnPeekFun(shape: DispatchShape): FunSpec {
    val framedBy =
        (shape.framing as? Framing.Framed)?.config
            ?: error("buildFramedByDispatchOnPeekFun called on shape without @FramedBy")
    val headerWireWidth = shape.discriminator.wireWidth.requireFixed("dispatchOnDiscriminator")
    val builder =
        FunSpec
            .builder("peekFrameSize")
            .addParameter("stream", STREAM_PROCESSOR_CN)
            .addParameter(
                com.squareup.kotlinpoet.ParameterSpec
                    .builder("baseOffset", INT)
                    .defaultValue("0")
                    .build(),
            ).returns(PEEK_RESULT_CN)
    val body = CodeBlock.builder()
    body.addStatement(
        "if (stream.available() - baseOffset < %L) return %T.NeedsMoreData",
        headerWireWidth + 1,
        PEEK_RESULT_CN,
    )
    val peekBudget = 5
    body.addStatement(
        "val __framingPeek = stream.peekBuffer(baseOffset + %L, %L) ?: return %T.NeedsMoreData",
        headerWireWidth,
        peekBudget,
        PEEK_RESULT_CN,
    )
    body.beginControlFlow("try")
    body.addStatement("val __framingPeekStart = __framingPeek.position()")
    body.beginControlFlow("val __framingLength = try")
    body.addStatement(
        "%T.decode(__framingPeek, %T.Empty)",
        framedBy.codecClassName,
        DECODE_CONTEXT_CN,
    )
    body.nextControlFlow("catch (__e: %T)", ClassName("kotlin", "Throwable"))
    body.beginControlFlow("when (__e::class.simpleName)")
    body.addStatement(
        "%S, %S, %S -> return %T.NeedsMoreData",
        "BufferUnderflowException",
        "IndexOutOfBoundsException",
        "ArrayIndexOutOfBoundsException",
        PEEK_RESULT_CN,
    )
    body.addStatement("else -> throw __e")
    body.endControlFlow()
    body.endControlFlow()
    body.addStatement(
        "val __framingPrefixWidth = __framingPeek.position() - __framingPeekStart",
    )
    body.addStatement(
        "val __total = %L + __framingPrefixWidth + __framingLength.toInt()",
        headerWireWidth,
    )
    body.addStatement(
        "return if (stream.available() - baseOffset >= __total) %T.Complete(__total) else %T.NeedsMoreData",
        PEEK_RESULT_CN,
        PEEK_RESULT_CN,
    )
    body.nextControlFlow("finally")
    body.addStatement(
        "(__framingPeek as? %T)?.freeNativeMemory()",
        PLATFORM_BUFFER_CN,
    )
    body.endControlFlow()
    builder.addCode(body.build())
    return builder.build()
}

/**
 * Companion object hosting
 * `decodeAggregating(buffer, context, on<Variant>: (...) -> ...,
 * ...)`. The aggregator is a parallel decode pathway that lets
 * the consumer pick the payload codec per call (and per
 * payload-bearing variant) instead of pinning a single codec at
 * dispatcher construction. Each payload-bearing variant gets a
 * lambda parameter `on<VariantName>: (<VariantCodec>.Partial<P>)
 * -> <Parent>.<VariantName><P>` defaulting to a `DecodeException`
 * with field-path attribution per row 17 — consumers override
 * only the variants they expect to receive; un-overridden
 * payload-bearing variants throw at runtime if they arrive.
 *
 * Companion-side placement matches /10c's `partial<P>(
 * ...)` convention: the aggregator's `<P : Payload>` is a
 * function-level type variable, decoupled from any surrounding
 * dispatcher class instantiation. Consumers call
 * `<DispatcherCodec>.decodeAggregating<JpegImage>(buffer,
 * context, onPublish = …)` without instantiating the
 * dispatcher class, since the aggregator never invokes the
 * constructor-injected `payloadCodec` (the per-call lambda
 * supplies the codec via `partial.complete(theirCodec)`).
 *
 * Only emitted on generic dispatchers (non-generic dispatchers
 * have no payload-bearing variants by construction, so the
 * aggregator would have no lambdas).
 */
internal fun buildDispatchOnAggregatorCompanion(
    shape: DispatchShape,
    binding: PayloadTypeParameter,
): TypeSpec =
    TypeSpec
        .companionObjectBuilder()
        .addFunction(buildDispatchOnDecodeAggregatingFun(shape, binding))
        .build()

/**
 * Emit `decodeAggregating(...)` on the
 * generic dispatcher's companion. Same routing logic as
 * `decode(buffer, context)` — but for payload-bearing variants
 * the dispatcher invokes the consumer's lambda with the variant
 * codec's `Partial<P>` instead of calling the constructor-
 * injected variant codec. `<Nothing>`-typed variants take the
 * standard codec dispatch (their codecs need no payload codec).
 *
 * Lambda return type is the **variant** (`Foo.Publish<P>`), not
 * the parent (`Foo<P>`). The discriminator's promise is "this
 * byte means PUBLISH"; the lambda's job is to complete the
 * matched variant from a `Partial`, not to substitute a
 * different variant. Returning the variant gives the dispatcher's
 * `when` branch a typed result that assigns to `Foo<P>` via
 * `out P` covariance with no cast. The "consumer wraps the
 * result" use case is served by wrapping outside the dispatcher
 * call.
 */
internal fun buildDispatchOnDecodeAggregatingFun(
    shape: DispatchShape,
    binding: PayloadTypeParameter,
): FunSpec {
    val disc =
        shape.discriminator as? Discriminator.ValueClass
            ?: error("decodeAggregating requires a ValueClass discriminator")
    val typeVar = TypeVariableName(binding.typeVariableName, binding.bound)
    val parentTypeRef = shape.parentClassName.parameterizedBy(typeVar)

    val funBuilder =
        FunSpec
            .builder("decodeAggregating")
            .addTypeVariable(typeVar)
            .addParameter("buffer", READ_BUFFER_CN)
            .addParameter("context", DECODE_CONTEXT_CN)
            .returns(parentTypeRef)

    for (variant in shape.variants) {
        if (variant.codecRef !is VariantCodecRef.GenericInstance) continue
        val partialType =
            variant.codecClassName
                .nestedClass("Partial")
                .parameterizedBy(typeVar)
        val variantTypedRef = variant.className.parameterizedBy(typeVar)
        val lambdaType =
            LambdaTypeName.get(
                receiver = null,
                parameters = arrayOf(partialType),
                returnType = variantTypedRef,
            )
        // Default-lambda field-path attribution: identifies the
        // variant whose handler was missing. Throws on lambda
        // invocation (i.e., when a payload-bearing variant
        // actually arrives), not at dispatcher construction —
        // unhandled variants only fail when they're received.
        val defaultLambda =
            CodeBlock.of(
                "{ _ -> throw %T(fieldPath = %S, bufferPosition = -1, expected = %S, actual = %S) }",
                DECODE_EXCEPTION_CN,
                "${shape.parentSimpleName}.${variant.simpleName}.handler",
                "consumer-supplied ${variant.simpleName} handler",
                "no handler supplied",
            )
        funBuilder.addParameter(
            ParameterSpec
                .builder(aggregatorLambdaParameterName(variant), lambdaType)
                .defaultValue(defaultLambda)
                .build(),
        )
    }

    val body = CodeBlock.builder()
    body.addStatement("val discriminatorPosition = buffer.position()")
    body.addStatement(
        "val __discriminator = %T.decode(buffer, context)",
        disc.codecClassName,
    )
    body.addStatement("buffer.position(discriminatorPosition)")
    body.addStatement(
        "val __dispatchValue = %L",
        dispatchValueIntCoercion(
            disc.dispatchValueKind,
            "__discriminator.${disc.dispatchValueProperty}",
        ),
    )
    body.beginControlFlow("return when (__dispatchValue)")
    for (variant in shape.variants) {
        if (variant.codecRef is VariantCodecRef.GenericInstance) {
            // Payload-bearing variant: dispatch via the consumer's
            // lambda. The aggregator passes the variant codec's
            // Partial<P> — the lambda completes the decode with the
            // payload codec it chooses for this call.
            body.addStatement(
                "%L -> %L(%T.partial<%T>(buffer, context))",
                variant.dispatchValue,
                aggregatorLambdaParameterName(variant),
                variant.codecClassName,
                typeVar,
            )
        } else {
            // <Nothing>-typed variant: standard dispatch unchanged.
            body.addStatement(
                "%L -> %T.decode(buffer, context)",
                variant.dispatchValue,
                variant.codecClassName,
            )
        }
    }
    body.beginControlFlow("else ->")
    body.addStatement(
        "throw %T(fieldPath = %S, bufferPosition = discriminatorPosition, expected = %S, actual = %P)",
        DECODE_EXCEPTION_CN,
        "${shape.parentSimpleName}.discriminator",
        expectedDispatchSet(shape),
        "\${__dispatchValue}",
    )
    body.endControlFlow()
    body.endControlFlow()
    funBuilder.addCode(body.build())
    return funBuilder.build()
}

/**
 * Derive the lambda parameter name for a
 * payload-bearing variant. Convention: `on<VariantName>` (camel-
 * case lowering of the leading character). Disambiguates lambdas
 * across multiple payload-bearing variants without ambiguity.
 */
internal fun aggregatorLambdaParameterName(variant: DispatchVariant): String = "on${variant.simpleName}"

/**
 * Emit the `@ForwardCompatible` decode `else` arm: skip an
 * unrecognized discriminator's framed payload and preserve it
 * verbatim into the unknown variant, instead of throwing.
 *
 * Position on entry is `discriminatorPosition` (the decode rewinds
 * there before dispatching). We re-read the single discriminator
 * byte as the opcode — capturing the *full* wire byte rather than
 * the (possibly sub-byte) dispatch value, which is what makes
 * re-encode byte-identical — then read the inherited framing prefix
 * to bound the payload, copy the payload into a caller-controlled
 * buffer (default `managed()`), and restore the outer limit so the
 * cursor lands exactly past the op.
 *
 * The copy is mandatory (the design's lifetime contract): the
 * preserved bytes outlive the often-pooled frame buffer, so a
 * non-consuming `slice()` would dangle. `factory.allocate + write`
 * gives an independently-owned buffer.
 */
internal fun appendForwardCompatibleDecodeElse(
    body: CodeBlock.Builder,
    framedBy: FramedByConfig,
    fc: ForwardCompatibleConfig,
) {
    body.beginControlFlow("else ->")
    // Cursor is at discriminatorPosition (rewound by decode); set it
    // explicitly so the read is robust to future reordering.
    body.addStatement("buffer.position(discriminatorPosition)")
    body.addStatement("val __fcOpcode = buffer.readUByte().toInt()")
    body.addStatement(
        "val __fcLength = %T.decode(buffer, context)",
        framedBy.codecClassName,
    )
    body.addStatement("val __fcFrameEnd = buffer.position() + __fcLength.toInt()")
    body.addStatement(
        "val __fcFactory = context[%T] ?: %T.%M()",
        FORWARD_COMPATIBLE_FACTORY_KEY_CN,
        BUFFER_FACTORY_CN,
        BUFFER_FACTORY_MANAGED_MN,
    )
    body.addStatement("val __fcRaw = __fcFactory.allocate(__fcLength.toInt())")
    body.addStatement("val __fcSavedLimit = buffer.limit()")
    body.addStatement("buffer.setLimit(__fcFrameEnd)")
    body.addStatement("__fcRaw.write(buffer)")
    body.addStatement("buffer.setLimit(__fcSavedLimit)")
    body.addStatement("__fcRaw.resetForRead()")
    body.addStatement(
        "%T(%L = __fcOpcode, %L = __fcRaw)",
        fc.unknownClassName,
        fc.opcodeFieldName,
        fc.rawFieldName,
    )
    body.endControlFlow()
}

/**
 * Emit the `@ForwardCompatible` encode arm for an unknown variant:
 * re-frame the preserved payload with the same inherited framing
 * codec the known variants use (via [FramedEncoder]), writing the
 * stored opcode as the single-byte discriminator. Byte-identical to
 * the original wire bytes — same framing codec re-derives the prefix,
 * the discriminator is one byte, and the payload is reproduced
 * verbatim. `raw.slice()` is non-consuming and zero-copy: we own
 * `raw`, and the slice is transient within the encode call.
 */
internal fun appendForwardCompatibleEncodeArm(
    body: CodeBlock.Builder,
    framedBy: FramedByConfig,
    fc: ForwardCompatibleConfig,
) {
    body.add("is %T -> %T.encode(\n", fc.unknownClassName, FRAMED_ENCODER_CN)
    body.indent()
    body.add("factory = factory,\n")
    body.add("framingCodec = %T,\n", framedBy.codecClassName)
    body.add("context = context,\n")
    body.add("headerWireWidth = 1,\n")
    body.add("writeHeader = { __fcBuf -> __fcBuf.writeUByte(value.%L.toUByte()) },\n", fc.opcodeFieldName)
    body.unindent()
    body.beginControlFlow(") { __fcBuf ->")
    body.addStatement("__fcBuf.write(value.%L.slice())", fc.rawFieldName)
    body.endControlFlow()
}

/**
 * Emit-side accessor for the user codec. Returns the receiver Kotlin
 * sub-expression for `<receiver>.decode(...)` / `<receiver>.encode(...)`
 * / `<receiver>.wireSize(...)` calls.
 */
internal fun PayloadCodecSource.codecReceiver(): CodeBlock =
    when (this) {
        is PayloadCodecSource.UserCodecObject -> CodeBlock.of("%T", codecType)
        is PayloadCodecSource.ConstructorInjected -> CodeBlock.of("%L", parameterName)
    }

/**
 * Encode-side accessor for a LengthSource. Returns the
 * Kotlin sub-expression that yields the body byte count as an
 * `Int` when prefixed with `value.`. Used by `wireSize` and the
 * @LengthFrom encode path.
 */
internal fun LengthSource.encodeAccessor(): String =
    when (this) {
        is LengthSource.Sibling -> "value.$siblingName.toInt()"
        is LengthSource.ValueClassProperty -> "value.$siblingName.$propertyName"
    }

/**
 * Decode-side accessor for a LengthSource. Returns the
 * Kotlin sub-expression that yields the body byte count as an
 * `Int` against locals already in scope. The simple form
 * accesses the sibling local directly; the dotted form accesses
 * the value-class local's property (the property returns `Int`,
 * so no `.toInt()` conversion is needed).
 */
internal fun LengthSource.decodeAccessor(): String =
    when (this) {
        is LengthSource.Sibling -> "$siblingName.toInt()"
        is LengthSource.ValueClassProperty -> "$siblingName.$propertyName"
    }

/**
 * Value-class inner-scalar peek. Used for predicate-source
 * reconstruction in `@When` and for discriminator reconstruction in
 * `@DispatchOn`.
 *
 * `offsetExpr` is interpolated into
 * `stream.peekByte(baseOffset + <expr>)`; callers with a fixed
 * offset pass `"$N"`, sequential walk callers pass the running
 * offset variable (`"__offset"`).
 *
 * Single-byte kinds (`UByte` / `Byte` / `Boolean`) read directly.
 * Multi-byte unsigned kinds (`UShort` / `UInt`) assemble bytes
 * BE/LE per `wireOrder`. `ULong` and signed multi-byte kinds
 * aren't required by any in-scope vector and would need parallel
 * paths (ULong promotion / signed sign-extension).
 */
internal fun appendPeekFixedScalar(
    body: CodeBlock.Builder,
    kind: ScalarKind,
    targetVar: String,
    offsetExpr: String,
    wireOrder: Endianness = Endianness.Default,
) {
    when (kind) {
        ScalarKind.UByte ->
            body.addStatement(
                "val %L = stream.peekByte(baseOffset + %L).toUByte()",
                targetVar,
                offsetExpr,
            )
        ScalarKind.Byte ->
            body.addStatement(
                "val %L = stream.peekByte(baseOffset + %L)",
                targetVar,
                offsetExpr,
            )
        ScalarKind.Boolean ->
            body.addStatement(
                "val %L = stream.peekByte(baseOffset + %L) != 0.toByte()",
                targetVar,
                offsetExpr,
            )
        ScalarKind.UShort, ScalarKind.Short, ScalarKind.UInt, ScalarKind.Int -> {
            // Int-domain assembly: extract each byte as `Int and 0xFF`,
            // shift into place (big- or little-endian per wireOrder), then
            // narrow to the inner kind. Signed kinds (Short / Int) narrow
            // by `.toShort()` / no-op so the high byte's sign bit is
            // preserved exactly as the discriminator codec's `readShort()`
            // / `readInt()` would produce it.
            val width = kind.wireWidth.requireFixed("appendPeekFixedScalar")
            val bigEndian =
                when (wireOrder) {
                    Endianness.Big, Endianness.Default -> true
                    Endianness.Little -> false
                }
            for (i in 0 until width) {
                val byteOffset = if (i == 0) offsetExpr else "$offsetExpr + $i"
                body.addStatement(
                    "val %L = stream.peekByte(baseOffset + %L).toInt() and 0xFF",
                    "${targetVar}B$i",
                    byteOffset,
                )
            }
            val parts =
                (0 until width).map { i ->
                    val byteName = "${targetVar}B$i"
                    val shiftBits = if (bigEndian) (width - 1 - i) * 8 else i * 8
                    if (shiftBits == 0) byteName else "($byteName shl $shiftBits)"
                }
            val narrow =
                when (kind) {
                    ScalarKind.UShort -> "(%L).toUInt().toUShort()"
                    ScalarKind.UInt -> "(%L).toUInt()"
                    ScalarKind.Short -> "(%L).toShort()"
                    ScalarKind.Int -> "(%L)"
                    else -> error("unreachable")
                }
            body.addStatement("val %L = $narrow", targetVar, parts.joinToString(" or "))
        }
        ScalarKind.ULong, ScalarKind.Long -> {
            // Long-domain assembly: an 8-byte inner overflows `Int`, so each
            // byte is lifted to `Long and 0xFFL` before shifting (up to 56
            // bits). `ULong` narrows via `.toULong()`; `Long` keeps the
            // assembled value — matching `readULong()` / `readLong()`.
            val width = kind.wireWidth.requireFixed("appendPeekFixedScalar")
            val bigEndian =
                when (wireOrder) {
                    Endianness.Big, Endianness.Default -> true
                    Endianness.Little -> false
                }
            for (i in 0 until width) {
                val byteOffset = if (i == 0) offsetExpr else "$offsetExpr + $i"
                body.addStatement(
                    "val %L = stream.peekByte(baseOffset + %L).toLong() and 0xFFL",
                    "${targetVar}B$i",
                    byteOffset,
                )
            }
            val parts =
                (0 until width).map { i ->
                    val byteName = "${targetVar}B$i"
                    val shiftBits = if (bigEndian) (width - 1 - i) * 8 else i * 8
                    if (shiftBits == 0) byteName else "($byteName shl $shiftBits)"
                }
            val narrow =
                when (kind) {
                    ScalarKind.Long -> "(%L)"
                    ScalarKind.ULong -> "(%L).toULong()"
                    else -> error("unreachable")
                }
            body.addStatement("val %L = $narrow", targetVar, parts.joinToString(" or "))
        }
        ScalarKind.Float, ScalarKind.Double ->
            error(
                "peek-side reconstruction for value-class inner kind $kind not implemented; " +
                    "Float / Double are not integer dispatch discriminators and the analyzer " +
                    "should have rejected this shape.",
            )
    }
}
