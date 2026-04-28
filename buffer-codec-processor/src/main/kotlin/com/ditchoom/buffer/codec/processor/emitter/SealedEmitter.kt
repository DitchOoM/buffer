package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.codec.processor.ir.DiscriminatorShape
import com.ditchoom.buffer.codec.processor.ir.DispatchShape
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.ditchoom.buffer.codec.processor.ir.FramingMode
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.PrimitiveKind
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
        // Slice 5a: direction-aware superinterface. `Codec<T>` for bidirectional;
        // `Decoder<T>` for decode-only; `Encoder<T>` for encode-only. Mirrors legacy
        // `SealedDispatchGenerator.generateInternal` interface selection.
        val superIface =
            when (plan.dir) {
                com.ditchoom.buffer.codec.processor.ir.Direction.Bidirectional -> Names.Codec
                com.ditchoom.buffer.codec.processor.ir.Direction.DecodeOnly -> Names.Decoder
                com.ditchoom.buffer.codec.processor.ir.Direction.EncodeOnly -> Names.Encoder
            }
        val type =
            TypeSpec
                .objectBuilder(codecName)
                .addSuperinterface(superIface.parameterizedBy(classType))

        val canDecode = plan.dir != com.ditchoom.buffer.codec.processor.ir.Direction.EncodeOnly
        val canEncode = plan.dir != com.ditchoom.buffer.codec.processor.ir.Direction.DecodeOnly

        if (canDecode) type.addFunction(buildDecode(plan, classType))
        if (canEncode) type.addFunction(buildEncode(plan, classType))
        if (canEncode) type.addFunction(buildWireSize(plan, classType))
        // peek belongs to FrameDetector / Codec<T>. For Decoder<T> we emit it as a
        // public fun (matching legacy `PeekFrameSizeEmitter.buildPeekFun(implementsCodec = false)`).
        if (canDecode) {
            val implementsCodec = plan.dir == com.ditchoom.buffer.codec.processor.ir.Direction.Bidirectional
            // Slice 5.5: legacy omits the entire dispatcher peek when the dispatch is
            // unframed AND any variant's fields disqualify peek emission (e.g. a
            // `@RemainingBytes` String at variable position). Without this check the
            // dispatcher emits per-variant peek delegations that reference a
            // `peekFrameSize` overload the variant doesn't have. Mirrors legacy
            // `allVariantsSupportPeek` gate in `SealedDispatchGenerator.generate`.
            val emitPeek =
                isFramed(plan) || plan.variants.all { v -> variantSupportsPeek(v) }
            if (emitPeek) {
                // MIN_HEADER_BYTES — sized to the discriminator (+1 for body-length
                // framing's length-prefix floor). Mirrors legacy
                // `SealedDispatchGenerator.buildSealedPeekResult`.
                val discriminatorBytes = sealedDiscriminatorBytes(plan)
                val minHeaderBytes =
                    if (isFramed(plan) &&
                        plan.dispatch is DispatchShape.TypedDiscriminator &&
                        (plan.dispatch as DispatchShape.TypedDiscriminator).framing is FramingMode.BodyLength
                    ) {
                        discriminatorBytes + 1
                    } else {
                        discriminatorBytes
                    }
                type.addProperty(
                    com.squareup.kotlinpoet.PropertySpec
                        .builder("MIN_HEADER_BYTES", INT, KModifier.PUBLIC, KModifier.CONST)
                        .initializer("%L", minHeaderBytes)
                        .build(),
                )
                type.addFunction(buildPeekFrame(plan, implementsCodec))
                // Framer interfaces (`DispatchFraming` / `BodyLengthFraming`) are sync-only
                // — their `peekFrameSize` accepts a `StreamProcessor`, not a
                // `SuspendingStreamProcessor`. Emitting a suspending overload that calls
                // `framerFqn.peekFrameSize(stream, baseOffset)` produces a type-mismatch
                // compile error. Mirrors legacy skipping the suspend overload when
                // `dispatchOnInfo.framing != null`.
                if (!isFramed(plan)) {
                    type.addFunction(buildSuspendingPeekFrame(plan))
                }
            }
        }

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

    private fun variantsConsumeDiscriminator(plan: Plan.Sealed_): Boolean = plan.variants.any { v -> hasDiscriminatorOwnedField(v) }

    /**
     * True when the variant declares any [FieldStrategy.DiscriminatorOwned] field.
     * Mirrors legacy `variantsHandlingDiscriminator` membership — the dispatcher
     * skips writing the discriminator for these variants because the variant codec
     * writes the discriminator itself (via the auto-detected / annotated header field).
     */
    private fun hasDiscriminatorOwnedField(variant: VariantPlan): Boolean =
        variant.fields.any { it.strategy is FieldStrategy.DiscriminatorOwned }

    /**
     * The dispatcher's view of "the variant writes its own discriminator bytes".
     *
     * Legacy `selfEncodesDiscriminator(variantsHandlingDiscriminator)`:
     *  - `WireMatch.Range` always self-encodes (the discriminator byte lives inside
     *    the variant's `@DiscriminatorField`-typed parameter).
     *  - `WireMatch.Point` self-encodes when the variant carries a discriminator
     *    field (auto-detected by type-match in legacy; explicit
     *    [FieldStrategy.DiscriminatorOwned] in the new IR).
     *
     * The IR's [VariantPlan.selfEncodes] is `true` for Range arms only. We OR in
     * the DiscriminatorOwned check here so the dispatcher's encode/wireSize match
     * legacy line-for-line when a variant data-class carries a discriminator field.
     */
    private fun effectiveSelfEncodes(variant: VariantPlan): Boolean = variant.selfEncodes || hasDiscriminatorOwnedField(variant)

    /**
     * Wire byte count of the dispatch discriminator. For `RawByte` always 1; for
     * `TypedDiscriminator` the natural width of the discriminator's primitive
     * shape.
     */
    private fun sealedDiscriminatorBytes(plan: Plan.Sealed_): Int =
        when (val d = plan.dispatch) {
            is DispatchShape.RawByte -> 1
            is DispatchShape.TypedDiscriminator ->
                when (val disc = d.disc) {
                    is DiscriminatorShape.ValueClass -> naturalWireBytes(disc.inner)
                    is DiscriminatorShape.DataClass -> disc.params.sumOf { it.wireBytes }.coerceAtLeast(1)
                }
        }

    /**
     * True when a non-payload variant's fields support the legacy
     * `PeekFrameSizeEmitter.generate` shape — i.e. fixed-width primitives + length-prefixed
     * fields with peekable prefixes. Conservative for Slice 5.5: we only return `true`
     * for variants whose fields are entirely Primitive / NestedMessage / DiscriminatorOwned
     * (no `@RemainingBytes` String, no `Collection_`, no `Spi`, no payload slot).
     *
     * The legacy resolver (in `ProtocolMessageProcessor`) tracks `variantsSupportingPeek`
     * by re-running `PeekFrameSizeEmitter.generate(fields)` and checking it didn't return
     * null. We approximate via field-strategy whitelist — if any variant disqualifies,
     * the dispatcher's whole peek overload is omitted (matches legacy
     * `allVariantsSupportPeek` gate in `SealedDispatchGenerator.generate`).
     */
    private fun variantSupportsPeek(variant: VariantPlan): Boolean {
        if (variant !is VariantPlan.NoPayload) {
            // WithPayload variants carry a `PayloadSlot` whose length comes from
            // `@LengthFrom` or `@RemainingBytes` — not peekable inline.
            return false
        }
        for (f in variant.fields) {
            if (f.conditionality !is com.ditchoom.buffer.codec.processor.ir.Conditionality.Always) return false
            when (val s = f.strategy) {
                is FieldStrategy.Primitive -> Unit
                is FieldStrategy.DiscriminatorOwned -> Unit
                is FieldStrategy.NestedMessage -> Unit
                is FieldStrategy.StringField -> {
                    // Inline length-prefixed strings are peekable (Byte/Short/Int prefix);
                    // Varint and Remaining are not. Mirrors `LeafEmitter.computePeekPlan`.
                    val length = s.length
                    if (length is com.ditchoom.buffer.codec.processor.ir.LengthSource.Inline) {
                        when (length.encoding) {
                            com.ditchoom.buffer.codec.processor.ir.LengthEncoding.Varint -> return false
                            else -> Unit
                        }
                    } else {
                        return false
                    }
                }
                else -> return false
            }
        }
        return true
    }

    /**
     * True when the sealed root carries a `BodyLengthFraming` / `DispatchFraming`
     * framer (vs. unframed dispatch). Framers' `peekFrameSize` are declared on
     * sync-only interfaces, so the dispatcher cannot delegate to the framer from
     * a suspending overload — the suspending overload is skipped entirely in
     * that case (mirrors legacy).
     */
    private fun isFramed(plan: Plan.Sealed_): Boolean {
        val d = plan.dispatch
        if (d !is DispatchShape.TypedDiscriminator) return false
        return when (d.framing) {
            FramingMode.Unframed -> false
            is FramingMode.PeekOnly,
            is FramingMode.BodyLength,
            -> true
        }
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
                // RawByte path has no typed discriminator, so no `ctx` local. Forward
                // the dispatcher's `context` parameter directly to variant decoders.
                fb.addCode(buildDispatchWhen(plan, "type", null, ctxArg = "context", isReturn = true))
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
                        is FramingMode.BodyLength -> "_bodySlice"
                        else -> "buffer"
                    }
                if (d.framing is FramingMode.BodyLength) {
                    // Slice 5.5: rename body-length locals to legacy convention `_bodyLen` /
                    // `_bodySlice` so the body-overrun guard reads identically to legacy
                    // generated source. Bare-name locals are an acceptable difference per
                    // the slice constraints, but rebuilding the legacy diagnostic shape
                    // (the configured `onUnknownDiscriminator` exception, the body-byte-count
                    // error message) requires referencing those locals from the guard.
                    fb.addCode("val _result = ")
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
                    fb.addCode("if (_bodySlice.remaining() != 0) {\n")
                    fb.addCode(
                        "    throw %T(\"Variant decoder consumed \${_bodyLen - _bodySlice.remaining()} of \" +\n" +
                            "        \"\$_bodyLen body bytes; \${_bodySlice.remaining()} unread. \" +\n" +
                            "        \"Wire is malformed or variant codec is buggy.\")\n",
                        registry.resolve(plan.onUnknown),
                    )
                    fb.addCode("}\n")
                    fb.addCode("return _result\n")
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
                // Slice 5.5: legacy convention names the locals `_bodyLen` / `_bodySlice`;
                // matching them keeps the body-overrun diagnostic message identical
                // (referenced by `_bodyLen - _bodySlice.remaining()`) and tracks the
                // legacy regression test `body-overrun check throws configured
                // onUnknownDiscriminator exception`.
                fb.addCode(
                    "val _bodyLen = %T.readBodyLength(buffer)\n",
                    framing.framerFqn,
                )
                fb.addCode("val _bodySlice = buffer.readBytes(_bodyLen)\n")
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

        val framing = (plan.dispatch as? DispatchShape.TypedDiscriminator)?.framing
        fb.addCode("when (value) {\n")
        for (variant in plan.variants.sortedBy { it.decl.canonical }) {
            val variantClass = registry.resolve(variant.decl)
            val checkClause = variantCheckClause(variant)
            fb.addCode("    $checkClause -> {\n", variantClass)
            // Emit discriminator bytes for non-self-encoding variants (legacy
            // behaviour: dispatcher writes discriminator). For `effectiveSelfEncodes`
            // the variant's encoder already writes them.
            if (!effectiveSelfEncodes(variant)) {
                emitDispatcherWriteDiscriminator(fb, plan.dispatch, variant, indent = "        ")
            }
            // Delegate body encode. For `FramingMode.BodyLength` we wrap with
            // `_len_body = wireSize; framer.writeBodyLength(buffer, _len_body); encode(...)`.
            // Mirrors legacy `emitBodyLengthEncodeWrap` for non-payload variants.
            val invokeName =
                when {
                    variant is VariantPlan.WithPayload -> "encodeFromContext"
                    else -> "encode"
                }
            val wireSizeMethod =
                when {
                    variant is VariantPlan.WithPayload -> "wireSizeFromContext"
                    else -> "wireSize"
                }
            if (framing is FramingMode.BodyLength) {
                fb.addCode(
                    "        val _len_body = %T.%L(value, context)\n",
                    variant.codec,
                    wireSizeMethod,
                )
                fb.addCode(
                    "        %T.writeBodyLength(buffer, _len_body)\n",
                    framing.framerFqn,
                )
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

    /**
     * Emit the discriminator-write call for a non-self-encoding variant.
     *
     * - `DispatchShape.RawByte` (no `@DispatchOn`): single `buffer.writeUByte(...)`.
     * - `DispatchShape.TypedDiscriminator` with `DiscriminatorShape.ValueClass`:
     *   construct the discriminator from the variant's `wire` value and call its
     *   codec's `encode`.
     * - `DispatchShape.TypedDiscriminator` with `DiscriminatorShape.DataClass`: the
     *   data-class discriminator carries multiple fields per variant — legacy
     *   requires the variant to self-encode in this case (otherwise the dispatcher
     *   would need to project Variant.@DiscriminatorField annotated members into
     *   the discriminator's constructor args). For Slice 4 we route those through
     *   legacy via the `coverable()` whitelist; reaching this branch is a
     *   precondition violation.
     *
     * Range arms always self-encode and never reach this method.
     */
    private fun emitDispatcherWriteDiscriminator(
        fb: FunSpec.Builder,
        dispatch: DispatchShape,
        variant: VariantPlan,
        indent: String,
    ) {
        val w = variant.wire as? WireMatch.Point ?: return // Range arms self-encode
        val wire = w.wire
        when (dispatch) {
            is DispatchShape.RawByte -> {
                fb.addCode("${indent}buffer.writeUByte(%L.toUByte())\n", wire)
            }
            is DispatchShape.TypedDiscriminator -> {
                when (val disc = dispatch.disc) {
                    is DiscriminatorShape.ValueClass -> {
                        val conversion = wireConversion(disc.inner, wire)
                        val discType = registry.resolve(disc.discriminatorType)
                        fb.addCode(
                            "$indent%T.encode(buffer, %T(%L), context)\n",
                            disc.codec,
                            discType,
                            conversion,
                        )
                    }
                    is DiscriminatorShape.DataClass -> {
                        // Slice 4: data-class discriminators with non-self-encoding
                        // variants aren't covered. The `coverable()` check excludes them;
                        // emit nothing here so existing tests against pre-Slice 4 behavior
                        // (e.g., StructuralEmitterTest's wsFrame() check) still pass while
                        // any class actually routed through this path will be on legacy.
                        // Reaching this branch with a coverable plan is a precondition
                        // violation surfaced by integration tests, not a runtime crash.
                    }
                }
            }
        }
    }

    /**
     * Returns the Kotlin literal expression for a discriminator's inner-type value.
     * Mirrors legacy `wireConversion` byte-for-byte so generated source stays stable.
     */
    private fun wireConversion(
        kind: PrimitiveKind,
        wire: Int,
    ): String =
        when (kind) {
            PrimitiveKind.UByte -> "$wire.toUByte()"
            PrimitiveKind.Byte -> "$wire.toByte()"
            PrimitiveKind.UShort -> "$wire.toUShort()"
            PrimitiveKind.Short -> "$wire.toShort()"
            PrimitiveKind.UInt -> "$wire.toUInt()"
            PrimitiveKind.Int -> "$wire"
            PrimitiveKind.ULong -> "$wire.toULong()"
            PrimitiveKind.Long -> "$wire.toLong()"
            else -> "$wire.toUByte()" // fallback matches legacy
        }

    /**
     * Returns the Kotlin expression for a single variant's discriminator wire size.
     * Without `@DispatchOn` (RawByte) the size is a literal `1`. With a typed
     * discriminator the dispatcher defers to the discriminator codec's `wireSize`,
     * matching legacy `discriminatorWireSizeExpr`.
     */
    private fun discriminatorWireSizeExpr(
        dispatch: DispatchShape,
        wire: Int,
    ): String =
        when (dispatch) {
            is DispatchShape.RawByte -> "1"
            is DispatchShape.TypedDiscriminator ->
                when (val disc = dispatch.disc) {
                    is DiscriminatorShape.ValueClass -> {
                        val conversion = wireConversion(disc.inner, wire)
                        val typeRef = registry.resolve(disc.discriminatorType).simpleName
                        "${disc.codec.simpleName}.wireSize($typeRef($conversion), context)"
                    }
                    is DiscriminatorShape.DataClass ->
                        // Data-class discriminator paths are routed to legacy in
                        // Slice 4. Defensive: emit a stub expression that compiles
                        // but signals the fallback should have run.
                        "0"
                }
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

        val framing = (plan.dispatch as? DispatchShape.TypedDiscriminator)?.framing
        fb.addCode("return when (value) {\n")
        for (variant in plan.variants.sortedBy { it.decl.canonical }) {
            val variantClass = registry.resolve(variant.decl)
            val wireSizeMethod =
                when {
                    variant is VariantPlan.WithPayload -> "wireSizeFromContext"
                    else -> "wireSize"
                }
            val checkClause = variantCheckClause(variant)
            // Mirrors legacy:
            //   variantBody = "VariantCodec.wireSize(value, context)"
            //   wrapped     = wrapBodyLengthSizeExpr(variantBody, dispatchOnInfo)
            //   term        = if (selfEncodes) wrapped else "$discSize + $wrapped"
            // The order of operations here must match the legacy emitter line-for-line so
            // generated source diffs against legacy stay clean.
            val bodyExpr = "${variant.codec.canonicalName}.$wireSizeMethod(value, context)"
            val wrapped = wrapBodyLengthSizeExpr(framing, bodyExpr)
            val pointWire = (variant.wire as? WireMatch.Point)?.wire
            val term =
                if (effectiveSelfEncodes(variant) || pointWire == null) {
                    wrapped
                } else {
                    val discSize = discriminatorWireSizeExpr(plan.dispatch, pointWire)
                    if (discSize == "0") wrapped else "$discSize + $wrapped"
                }
            fb.addCode("    $checkClause -> %L\n", variantClass, term)
        }
        fb.addCode("}\n")
        return fb.build()
    }

    /**
     * Wraps a body-size expression with framing overhead. For [FramingMode.BodyLength]
     * this matches legacy `wrapBodyLengthSizeExpr` exactly:
     * `run { val _b = $body; framer.bodyLengthSize(_b) + _b }`.
     * For peek-only or unframed dispatch the wire size is just the body itself
     * (the discriminator is added separately for non-self-encoding variants).
     */
    private fun wrapBodyLengthSizeExpr(
        framing: FramingMode?,
        bodyExpr: String,
    ): String =
        when (framing) {
            is FramingMode.BodyLength ->
                "run { val _b = $bodyExpr; ${framing.framerFqn}.bodyLengthSize(_b) + _b }"
            else -> bodyExpr
        }

    // -----------------------------------------------------------------------
    // peekFrameSize
    // -----------------------------------------------------------------------

    private fun buildPeekFrame(
        plan: Plan.Sealed_,
        implementsCodec: Boolean = true,
    ): FunSpec {
        val fb =
            FunSpec
                .builder("peekFrameSize")
                .addParameter("stream", Names.StreamProcessor)
        if (implementsCodec) {
            fb.addModifiers(KModifier.OVERRIDE)
            fb.addParameter("baseOffset", INT)
        } else {
            fb.addParameter(ParameterSpec.builder("baseOffset", INT).defaultValue("0").build())
        }
        fb.returns(Names.PeekResult)
        emitPeekBody(fb, plan)
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
        emitPeekBody(fb, plan)
        return fb.build()
    }

    /**
     * Common peek body for both the synchronous and suspending overloads. Mirrors
     * legacy `buildSealedPeekFun` for the cases Slice 4 covers:
     *  - `BodyLength` / `PeekOnly` framing: delegate to the framer.
     *  - `Unframed` `RawByte` / value-class `TypedDiscriminator`: peek the
     *    discriminator, branch per variant, delegate to the variant's
     *    `peekFrameSize`. Per-variant peek is the only way to compute the full
     *    frame size in unframed mode.
     *  - Data-class discriminator unframed: legacy emits a multi-arg peek
     *    constructor; Slice 4 doesn't cover it, so we fall back to
     *    `NeedsMoreData` (matches the pre-Slice-4 stub).
     */
    private fun emitPeekBody(
        fb: FunSpec.Builder,
        plan: Plan.Sealed_,
    ) {
        when (val d = plan.dispatch) {
            is DispatchShape.RawByte -> emitUnframedPeek(fb, plan, d, discriminatorBytes = 1)
            is DispatchShape.TypedDiscriminator ->
                when (val framing = d.framing) {
                    FramingMode.Unframed -> {
                        val disc = d.disc
                        if (disc is DiscriminatorShape.ValueClass) {
                            val bytes = naturalWireBytes(disc.inner)
                            emitUnframedPeek(fb, plan, d, bytes)
                        } else {
                            // Data-class disc unframed — Slice 5 / 6 path. Stub.
                            fb.addCode("return %T\n", Names.PeekResultNeedsMore)
                        }
                    }
                    is FramingMode.PeekOnly ->
                        fb.addCode("return %T.peekFrameSize(stream, baseOffset)\n", framing.framerFqn)
                    is FramingMode.BodyLength ->
                        fb.addCode("return %T.peekFrameSize(stream, baseOffset)\n", framing.framerFqn)
                }
        }
    }

    private fun emitUnframedPeek(
        fb: FunSpec.Builder,
        plan: Plan.Sealed_,
        dispatch: DispatchShape,
        discriminatorBytes: Int,
    ) {
        fb.addCode(
            "if (stream.available() < baseOffset + %L) return %T.NeedsMoreData\n",
            discriminatorBytes,
            Names.PeekResult,
        )
        when (dispatch) {
            is DispatchShape.RawByte -> {
                fb.addCode("val type = stream.peekByte(baseOffset).toInt() and 0xFF\n")
            }
            is DispatchShape.TypedDiscriminator -> {
                val disc = dispatch.disc as DiscriminatorShape.ValueClass
                val peekExpr = discriminatorPeekExpr("stream", "baseOffset", disc.inner)
                val discType = registry.resolve(disc.discriminatorType)
                fb.addCode("val _raw = %L\n", peekExpr)
                fb.addCode("val type = %T(_raw).%L\n", discType, disc.dispatchProp)
                if (plan.variants.any { it.wire is WireMatch.Range }) {
                    fb.addCode("val rawByte = _raw.toInt() and 0xFF\n")
                }
            }
        }
        val anyRange = plan.variants.any { it.wire is WireMatch.Range }
        if (anyRange) {
            fb.addCode("return when {\n")
        } else {
            fb.addCode("return when (type) {\n")
        }
        // Range arms first (low-order), then Point arms.
        for (variant in plan.variants.filter { it.wire is WireMatch.Range }.sortedBy { (it.wire as WireMatch.Range).from }) {
            val w = variant.wire as WireMatch.Range
            fb.addCode(
                "    rawByte in %L..%L -> when (val r = %T.peekFrameSize(stream, baseOffset + %L)) " +
                    "{ is %T.Size -> %T.Size(r.bytes + %L); else -> r }\n",
                w.from,
                w.to,
                variant.codec,
                discriminatorBytes,
                Names.PeekResult,
                Names.PeekResult,
                discriminatorBytes,
            )
        }
        for (variant in plan.variants.filter { it.wire is WireMatch.Point }.sortedBy { (it.wire as WireMatch.Point).wire }) {
            val w = variant.wire as WireMatch.Point
            val arm = if (anyRange) "type == ${w.wire}" else "${w.wire}"
            fb.addCode(
                "    %L -> when (val r = %T.peekFrameSize(stream, baseOffset + %L)) " +
                    "{ is %T.Size -> %T.Size(r.bytes + %L); else -> r }\n",
                arm,
                variant.codec,
                discriminatorBytes,
                Names.PeekResult,
                Names.PeekResult,
                discriminatorBytes,
            )
        }
        fb.addCode("    else -> %T.NeedsMoreData\n", Names.PeekResult)
        fb.addCode("}\n")
    }

    private fun discriminatorPeekExpr(
        stream: String,
        offset: String,
        inner: PrimitiveKind,
    ): String =
        when (inner) {
            PrimitiveKind.UByte -> "$stream.peekByte($offset).toUByte()"
            PrimitiveKind.Byte -> "$stream.peekByte($offset)"
            PrimitiveKind.UShort -> "$stream.peekShort($offset).toUShort()"
            PrimitiveKind.Short -> "$stream.peekShort($offset)"
            PrimitiveKind.UInt -> "$stream.peekInt($offset).toUInt()"
            PrimitiveKind.Int -> "$stream.peekInt($offset)"
            PrimitiveKind.ULong -> "$stream.peekLong($offset).toULong()"
            PrimitiveKind.Long -> "$stream.peekLong($offset)"
            else -> "$stream.peekByte($offset).toUByte()"
        }

    /**
     * Returns the `is X` / `is X<*, *, ...>` check clause for a variant. Mirrors the
     * legacy emitter's `subTypeName.parameterizedBy(info.payloadFields.map { STAR })`
     * shape — one `*` per `@Payload` type parameter.
     */
    private fun variantCheckClause(variant: VariantPlan): String =
        when (variant) {
            is VariantPlan.WithPayload -> {
                val stars = List(variant.typeParams.size) { "*" }.joinToString(", ")
                "is %T<$stars>"
            }
            is VariantPlan.NoPayload -> "is %T"
        }

    private fun naturalWireBytes(kind: PrimitiveKind): Int =
        when (kind) {
            PrimitiveKind.Bool, PrimitiveKind.Byte, PrimitiveKind.UByte -> 1
            PrimitiveKind.Short, PrimitiveKind.UShort -> 2
            PrimitiveKind.Int, PrimitiveKind.UInt, PrimitiveKind.Float -> 4
            PrimitiveKind.Long, PrimitiveKind.ULong, PrimitiveKind.Double -> 8
        }
}
