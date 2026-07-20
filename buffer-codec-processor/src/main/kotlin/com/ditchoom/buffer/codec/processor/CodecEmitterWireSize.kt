package com.ditchoom.buffer.codec.processor

import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeName

/*
 * CodecEmitterWireSize — buildWireSizeFun extracted from CodecEmitter (step 7).
 * Emits the codec's `wireSize(value): WireSize` override: literal Exact for
 * all-fixed shapes, BackPatch / runtime Exact sums for variable shapes. A
 * stateless emit leaf (no batchCounter, codeGenerator or logger) moved verbatim
 * to package-internal top-level; CodecEmitter's buildFileSpec calls it
 * unqualified across the package. Byte-identical codegen verified by the
 * snapshot suite.
 */

internal fun buildWireSizeFun(
    shape: CodecShape,
    messageType: TypeName = shape.messageClassName,
): FunSpec {
    val builder =
        FunSpec
            .builder("wireSize")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("value", messageType)
            .addParameter("context", ENCODE_CONTEXT_CN)
            .returns(WIRE_SIZE_CN)
    // Any `@When` field collapses the message
    // wireSize to BackPatch — we don't attempt conditional-Exact arithmetic.
    if (shape.fields.any { it is FieldSpec.Conditional }) {
        builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
        return builder.build()
    }
    // A `@RemainingBytes @UseCodec val: P` field's
    // wireSize comes from the user codec, which may be Exact or
    // BackPatch. takes the conservative BackPatch path
    // unconditionally — promoting to runtime-Exact-via-cast (mirroring
    // LengthPrefixedMessage) is a follow-on once we have a vector
    // where the size optimization actually matters.
    if (shape.fields.any { it is FieldSpec.RemainingBytesPayload }) {
        builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
        return builder.build()
    }
    // An OPAQUE `@UseCodec val: <scalar>` field's wireSize is unknown to the framework, so
    // the message collapses to BackPatch. A `VariableLengthCodec`-backed field
    // (`isVariableLength`) is the exception: it reports `Exact(encodedLength(value))` at
    // runtime, so a message whose only variable fields are such codecs stays on the
    // precompute path via the runtime-Exact branch below (just before the terminal `when`).
    // This subsumes the former sole-varint-value-class special case (#186): a single
    // variable-length `@UseCodec` scalar (an HTTP/3 frame type wrapping a QUIC varint) now
    // falls through to that branch, which yields the same `Exact(encodedLength)` the
    // `@FramedBy`-after-varint emit needs to size its framing header per value.
    if (shape.fields.any { it is FieldSpec.UseCodecScalar && !it.isVariableLength }) {
        builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
        return builder.build()
    }
    // `@LengthPrefixed @UseCodec val: List<E>`
    // wireSize composes the user codec's prefix size with the sum of
    // element wireSizes. Same conservative BackPatch collapse as the
    // bare-scalar case — runtime-Exact-via-cast is a follow-on.
    if (shape.fields.any { it is FieldSpec.LengthPrefixedUseCodecList }) {
        builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
        return builder.build()
    }
    // `@LengthPrefixed @UseCodec val: T: Payload`
    // wireSize composes a fixed prefix with the user codec's body size,
    // which is opaque. Same conservative BackPatch collapse as the
    // bare-scalar / list cases — runtime-Exact-via-cast is a follow-on.
    if (shape.fields.any { it is FieldSpec.LengthPrefixedUseCodecPayload }) {
        builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
        return builder.build()
    }
    // Bare `val: T: @ProtocolMessage` wireSize
    // delegates to T's codec at runtime (RuntimeExact). Same conservative
    // BackPatch collapse on the parent — promoting to runtime-Exact-via-
    // cast is a follow-on once a vector benefits.
    if (shape.fields.any { it is FieldSpec.ProtocolMessageScalar }) {
        builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
        return builder.build()
    }
    // Any `@LengthPrefixed val: String` collapses
    // wireSize to BackPatch (pre-measuring the UTF-8 byte length is the
    // walk the BackPatch path collapses into the single writeString call).
    // The rule applies regardless of position now that LPS
    // String can appear non-terminally.
    if (shape.fields.any { it is FieldSpec.LengthPrefixedString }) {
        builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
        return builder.build()
    }
    // `@RemainingBytes val: String` collapses wireSize to BackPatch for the
    // same reason `@LengthPrefixed val: String` does — pre-measuring the
    // UTF-8 byte count is the walk the BackPatch path collapses into the
    // single writeString call.
    if (shape.fields.any { it is FieldSpec.RemainingBytesString }) {
        builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
        return builder.build()
    }
    // `@RemainingBytes List<E>` where E is a
    // sealed parent (or otherwise BackPatch-element) collapses to
    // BackPatch. The runtime-Exact emit below sums element wireSizes
    // via `as WireSize.Exact` cast; sealed-parent variants carrying
    // `@LengthPrefixed val: String` or `@When` trailers produce
    // BackPatch wireSize and would CCE on that cast. No fixture trips
    // this today, but the guard is required for correctness once a
    // typed-RC list lands.
    if (shape.fields.any {
            it is FieldSpec.RemainingBytesProtocolMessageList && it.elementIsBackPatch
        }
    ) {
        builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
        return builder.build()
    }
    // Non-terminal `@RemainingBytes*` fields collapse the
    // parent's wireSize to BackPatch. The terminal-`when` branches
    // below assume the body field IS the terminal (so they sum
    // header bytes + body bytes); when the body sits before a
    // FixedSize trailer the terminal is the trailer's Scalar /
    // ValueClassScalar, the body's bytes get dropped, and the
    // resulting Exact value would under-count the message.
    // BackPatch sidesteps the bookkeeping — the encoder uses a
    // growable buffer and reports the actual byte count after the
    // body emits, same shape as `@LengthPrefixed val: String`.
    if (shape.fields.any {
            it is FieldSpec.RemainingBytesProtocolMessageList && it.reservedTrailingBytes != 0
        }
    ) {
        builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
        return builder.build()
    }
    // `@Count List<E>` with a BackPatch-shaped element collapses to BackPatch
    // for the same reason `@RemainingBytes List<E>` does: the runtime-Exact emit
    // below casts each element wireSize `as Exact`, which would CCE on a
    // BackPatch element variant.
    if (shape.fields.any { it is FieldSpec.CountPrefixedProtocolMessageList && it.elementIsBackPatch }) {
        builder.addStatement("return %T.BackPatch", WIRE_SIZE_CN)
        return builder.build()
    }

    // Runtime-Exact: the message's only variable-width fields are runtime-Exact — a
    // `VariableLengthCodec`-backed `@UseCodec` scalar (reports `Exact(encodedLength)`), an enum
    // whose ordinal rides as an `UnsignedVarIntCodec` varint, or a `@Count` element-count list
    // (varint(N) + sum of element wireSizes) — and every other field is FixedSize.
    // Sum the compile-time fixed bytes with each variable field's runtime `Exact` (the same
    // `as Exact` cast LengthPrefixedMessage uses) so enclosing messages keep the precompute path
    // instead of degrading to BackPatch. Mirrors the peekFrameSize walk's shape.
    val runtimeExactVarFields = shape.fields.filter { it.isRuntimeExactVar() }
    if (runtimeExactVarFields.isNotEmpty() &&
        shape.fields.all { it is FieldSpec.FixedSize || it.isRuntimeExactVar() }
    ) {
        val fixedBytes = shape.fields.sumOfFixedWireBytes().requireFixed("runtimeExactWireSize")
        for (f in runtimeExactVarFields) {
            when (f) {
                is FieldSpec.UseCodecScalar -> {
                    // The VariableLengthCodec contract fills wireSize in as
                    // `Exact(encodedLength(value))`, but a user codec may
                    // override it — degrade to BackPatch instead of casting
                    // (a cast would CCE on a contract violation).
                    builder.beginControlFlow(
                        "val %L = when (val __s = %T.wireSize(value.%L, context))",
                        "__${f.name}Size",
                        f.codecType,
                        f.name,
                    )
                    builder.addStatement("is %T.Exact -> __s.bytes", WIRE_SIZE_CN)
                    builder.addStatement("%T.BackPatch -> return %T.BackPatch", WIRE_SIZE_CN, WIRE_SIZE_CN)
                    builder.endControlFlow()
                }
                is FieldSpec.EnumScalar ->
                    builder.addStatement(
                        "val %L = (%T.wireSize(value.%L.ordinal.toUInt(), context) as %T.Exact).bytes",
                        "__${f.name}Size",
                        UNSIGNED_VARINT_CODEC_CN,
                        f.name,
                        WIRE_SIZE_CN,
                    )
                is FieldSpec.CountPrefixedProtocolMessageList ->
                    appendCountListWireSizeLocal(builder, f)
                else -> {}
            }
        }
        val sizeTerms = listOf(fixedBytes.toString()) + runtimeExactVarFields.map { "__${it.name}Size" }
        val sumExpr = sizeTerms.joinToString(" + ")
        builder.addStatement("return %T.Exact(%L)", WIRE_SIZE_CN, sumExpr)
        return builder.build()
    }
    when (val terminal = shape.fields.lastOrNull()) {
        is FieldSpec.LengthPrefixedMessage -> {
            // The nested message's wireSize is Exact for fixed/runtime-Exact
            // bodies, but a BackPatch-shaped body (e.g. one carrying a
            // `@LengthPrefixed val: String`) must degrade the parent to
            // BackPatch instead of casting (a cast threw ClassCastException
            // on every encode).
            val headerBytes = shape.scalarHeaderBytes() + terminal.prefixWidth
            builder.beginControlFlow(
                "return when (val %L = %T.wireSize(value.%L, context))",
                "${terminal.name}Size",
                terminal.codecType,
                terminal.name,
            )
            builder.addStatement(
                "is %T.Exact -> %T.Exact(%L + %L.bytes)",
                WIRE_SIZE_CN,
                WIRE_SIZE_CN,
                headerBytes,
                "${terminal.name}Size",
            )
            builder.addStatement("%T.BackPatch -> %T.BackPatch", WIRE_SIZE_CN, WIRE_SIZE_CN)
            builder.endControlFlow()
        }
        is FieldSpec.LengthFromString -> {
            // Body byte count comes from the resolved LengthSource
            // (sibling.toInt() for simple, sibling.property for
            // dotted). User-trusted.
            val prefixBytes = shape.scalarHeaderBytes()
            builder.addStatement(
                "return %T.Exact(%L + %L)",
                WIRE_SIZE_CN,
                prefixBytes,
                terminal.source.encodeAccessor(),
            )
        }
        is FieldSpec.LengthFromList -> {
            // Same Exact shape via LengthSource.
            val prefixBytes = shape.scalarHeaderBytes()
            builder.addStatement(
                "return %T.Exact(%L + %L)",
                WIRE_SIZE_CN,
                prefixBytes,
                terminal.source.encodeAccessor(),
            )
        }
        is FieldSpec.LengthFromMessage -> {
            // Body byte count = sibling-resolved length
            // (same row-16 user-trust contract as LengthFromString /
            // LengthFromList). The nested message's own wireSize is
            // RuntimeExact at runtime, but we don't query it here:
            // the user supplies the sibling and is responsible for
            // keeping it consistent with the body's encoded size.
            val prefixBytes = shape.scalarHeaderBytes()
            builder.addStatement(
                "return %T.Exact(%L + %L)",
                WIRE_SIZE_CN,
                prefixBytes,
                terminal.source.encodeAccessor(),
            )
        }
        is FieldSpec.RemainingBytesProtocolMessageList -> {
            // Body byte count = sum of element wireSizes. Probe each
            // element's wireSize and short-circuit to BackPatch on the
            // first non-Exact result — the analyze-time `elementIsBackPatch`
            // guard diverts always-BackPatch element types upfront, but an
            // element shape the scan under-detects must degrade instead of
            // throwing ClassCastException.
            val prefixBytes = shape.scalarHeaderBytes()
            val bodySizeVar = "__${terminal.name}BodySize"
            appendElementWireSizeProbeLoop(
                builder = builder,
                listAccessor = "value.${terminal.name}",
                elementCodec = terminal.elementCodecClassName,
                sumVar = bodySizeVar,
            )
            builder.addStatement(
                "return %T.Exact(%L + %L)",
                WIRE_SIZE_CN,
                prefixBytes,
                bodySizeVar,
            )
        }
        is FieldSpec.RemainingBytesPayload ->
            error(
                "RemainingBytesPayload terminal shape should be handled by the BackPatch " +
                    "early-return at the top of buildWireSizeFun; reaching this branch " +
                    "indicates a missed early return.",
            )
        else -> {
            // Singleton variant under
            // `@DispatchOn(value class)` self-frames the
            // discriminator (read in decode, write in encode), so
            // its wire byte count is the discriminator's inner-
            // scalar width. All other singletons keep `Exact(0)` —
            // their parent dispatcher writes/reads the discriminator
            // around the call.
            val discriminatorBytes =
                (shape.singletonDispatchDiscriminator?.wireWidth ?: WireWidth.Zero)
                    .requireFixed("singletonDiscriminator")
            val total = shape.fields.sumOfFixedWireBytes().requireFixed("sumOfFixedWireBytes") + discriminatorBytes
            builder.addStatement("return %T.Exact(%L)", WIRE_SIZE_CN, total)
        }
    }
    return builder.build()
}

/**
 * Emit the `__<name>Size` local for a `@Count` element-count list inside the
 * runtime-Exact wireSize sum: the varint width of the element count plus the
 * sum of element wireSizes. Element sizes are probed with a short-circuit —
 * the first non-Exact element wireSize makes the whole message report
 * BackPatch. Always-BackPatch element types are diverted upfront by the
 * `elementIsBackPatch` guard; the probe is the runtime backstop for element
 * shapes the analyze-time scan under-detects (previously those threw
 * ClassCastException on the `as Exact` cast).
 */
internal fun appendCountListWireSizeLocal(
    builder: FunSpec.Builder,
    field: FieldSpec.CountPrefixedProtocolMessageList,
) {
    val countSizeVar = "__${field.name}CountSize"
    builder.addStatement(
        "val %L = (%T.wireSize(value.%L.size.toUInt(), context) as %T.Exact).bytes",
        countSizeVar,
        UNSIGNED_VARINT_CODEC_CN,
        field.name,
        WIRE_SIZE_CN,
    )
    val elementsSizeVar = "__${field.name}ElementsSize"
    appendElementWireSizeProbeLoop(
        builder = builder,
        listAccessor = "value.${field.name}",
        elementCodec = field.elementCodecClassName,
        sumVar = elementsSizeVar,
    )
    builder.addStatement(
        "val %L = %L + %L",
        "__${field.name}Size",
        countSizeVar,
        elementsSizeVar,
    )
}

/**
 * Emit the element-size probe loop shared by the `@Count` and terminal
 * `@RemainingBytes List<E>` wireSize sums:
 * ```
 * var <sumVar> = 0
 * for (__elem in <listAccessor>) {
 *     when (val __elemSize = ElementCodec.wireSize(__elem, context)) {
 *         is WireSize.Exact -> <sumVar> += __elemSize.bytes
 *         WireSize.BackPatch -> return WireSize.BackPatch
 *     }
 * }
 * ```
 * The short-circuit is what keeps a mixed-shape element type safe: only when
 * EVERY element reports Exact may the message stay on the precompute path.
 */
private fun appendElementWireSizeProbeLoop(
    builder: FunSpec.Builder,
    listAccessor: String,
    elementCodec: TypeName,
    sumVar: String,
) {
    builder.addStatement("var %L = 0", sumVar)
    builder.beginControlFlow("for (__elem in %L)", listAccessor)
    builder.beginControlFlow("when (val __elemSize = %T.wireSize(__elem, context))", elementCodec)
    builder.addStatement("is %T.Exact -> %L += __elemSize.bytes", WIRE_SIZE_CN, sumVar)
    builder.addStatement("%T.BackPatch -> return %T.BackPatch", WIRE_SIZE_CN, WIRE_SIZE_CN)
    builder.endControlFlow()
    builder.endControlFlow()
}

/**
 * A field whose wireSize is known only at runtime but always `Exact`: a
 * `VariableLengthCodec`-backed `@UseCodec` scalar, an enum riding its ordinal
 * as an `UnsignedVarIntCodec` varint, or a `@Count` element-count list (varint
 * element count plus the summed element wireSizes).
 */
private fun FieldSpec.isRuntimeExactVar(): Boolean =
    this is FieldSpec.EnumScalar ||
        this is FieldSpec.CountPrefixedProtocolMessageList ||
        (this is FieldSpec.UseCodecScalar && isVariableLength)
