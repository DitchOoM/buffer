package com.ditchoom.buffer.codec.processor

import com.squareup.kotlinpoet.BYTE
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.SHORT
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.U_BYTE
import com.squareup.kotlinpoet.U_INT
import com.squareup.kotlinpoet.U_LONG
import com.squareup.kotlinpoet.U_SHORT

/*
 * CodecEmitterPeek — the peekFrameSize emit cluster extracted from
 * CodecEmitter (step 5): buildPeekFrameFun and its specialized walkers
 * (appendPeekUseCodecScalar / appendPeekBoundingDynamicPrior /
 * appendPeekVariableLengthUseCodecScalar / appendPeekLengthPrefixedUseCodecList /
 * appendSequentialPeek and its appendSequentialPeek* / appendPeek* helpers),
 * the peekBudgetFor heuristic, and the isPeekCollapsingConditional predicate.
 *
 * Two stateless helpers shared with the decode visitor — decodeConditionExpr
 * and appendLengthFromIntMaxGuard — move here too so the top-level peek
 * functions can call them; CodecEmitter's decode code calls them unqualified
 * across the package (the same cross-file pattern step 4 used for
 * decodeAccessor / appendPeekFixedScalar in CodecEmitterDispatch.kt).
 *
 * All are stateless emit helpers (no batchCounter, codeGenerator or logger),
 * moved verbatim to package-internal top-level so CodecEmitter calls them
 * unqualified and unchanged. Byte-identical codegen verified by the snapshot
 * suite.
 */

// Worst-case peek budgets (bytes) per scalar width, `max(⌈typeBits / 7⌉, 1 + typeBytes)`.
private const val BYTE_PEEK_BUDGET = 2
private const val SHORT_PEEK_BUDGET = 3
private const val INT_PEEK_BUDGET = 5
private const val LONG_PEEK_BUDGET = 10

/**
 * Per-field-type peek-budget table.
 *
 * The framework's generic `@UseCodec` peek walker (step 6) materializes
 * a non-consuming view via `stream.peekBuffer(offset, maxBytes)` and
 * runs `codec.decode` against it. `maxBytes` is computed at emit time
 * from this table, sized to cover both 7-bit-continuation encodings
 * (MQTT var-byte-int, LEB128) and sentinel-extended encodings
 * (WebSocket extended length) without per-codec opt-in.
 *
 * Heuristic: `max(⌈typeBits / 7⌉, 1 + typeBytes)`.
 *
 * | Field type   | typeBits | typeBytes | budget |
 * |--------------|----------|-----------|--------|
 * | `Byte`/`UByte`     | 8  | 1 |  2 |
 * | `Short`/`UShort`   | 16 | 2 |  3 |
 * | `Int`/`UInt`       | 32 | 4 |  5 |
 * | `Long`/`ULong`     | 64 | 8 | 10 |
 *
 * Returns `null` for field types outside this set (value-class
 * wrappers, non-scalar types). The caller falls back to
 * `PeekResult.NoFraming` for the enclosing message — the codec is
 * out of the generic peek-walker's reach until a per-codec opt-in
 * lands. None of the current target protocols (MQTT var-byte-int,
 * LEB128, MIDI VLQ, ASN.1 BER, WebSocket extended length) need
 * a wider budget.
 */
internal fun peekBudgetFor(typeName: TypeName): Int? =
    when (typeName) {
        BYTE, U_BYTE -> BYTE_PEEK_BUDGET
        SHORT, U_SHORT -> SHORT_PEEK_BUDGET
        INT, U_INT -> INT_PEEK_BUDGET
        LONG, U_LONG -> LONG_PEEK_BUDGET
        else -> null
    }

/**
 * Sum the `wireBytes` of every `FixedSize` field
 * in the list. Variable-length fields (`LengthPrefixed*`,
 * `Conditional`) contribute 0 and are filtered out by the
 * `filterIsInstance` step. Callers that require the result to
 * cover every field gate on terminal shape before calling.
 */
internal fun buildPeekFrameFun(shape: CodecShape): FunSpec {
    val builder =
        FunSpec
            .builder("peekFrameSize")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("stream", STREAM_PROCESSOR_CN)
            .addParameter("baseOffset", INT)
            .returns(PEEK_RESULT_CN)
    // Consumer-supplied frame-size override: a companion object implementing
    // `FrameDetector` takes over framing wholesale (the escape hatch for
    // wire shapes the walker can't express). Delegate and return.
    shape.customPeek?.let { customPeek ->
        builder.addStatement("return %T.peekFrameSize(stream, baseOffset)", customPeek)
        return builder.build()
    }
    // Generic `@UseCodec` peek walker. When a
    // bounding `UseCodecScalar` field is present, the framework drives
    // the user codec against a non-consuming `stream.peekBuffer(...)`
    // view to discover the value, then computes total = priorBytes +
    // codec-width + value.toInt() — the user codec does the var-int
    // read against the peek view. Placed BEFORE the
    // `RemainingBytesProtocolMessageList` / `RemainingBytesPayload`
    // NoFraming collapses below: a bounding codec gives peek the
    // value-driven byte count that bounds any trailing
    // `@RemainingBytes` body.
    //
    // Conservative fallback to NoFraming for shapes outside the
    // walker's reach: non-bounding `@UseCodec` (no value-to-byte
    // mapping), value-class / non-scalar field types (no peek-budget
    // entry), or non-FixedSize prior fields (the walker assumes a
    // statically-known prior byte count).
    val ucsField =
        shape.fields.firstOrNull { it is FieldSpec.UseCodecScalar } as? FieldSpec.UseCodecScalar
    if (ucsField != null) {
        // A bounding length anchors framing wherever it sits: the decoded
        // value IS the bounded body's byte count, so
        // total = priorBytes + lengthWidth + value. The priors before it may
        // themselves be variable-width as long as each is *self-framing* —
        // fixed-width, a nested-message/value-class `ProtocolMessageScalar`,
        // or a self-delimiting variable-length `@UseCodec` (a varint). This
        // is the real HTTP/3 frame shape: a QUIC-varint `Type` discriminator
        // precedes the QUIC-varint bounding `Length`.
        val boundingField =
            shape.fields
                .firstOrNull { it is FieldSpec.UseCodecScalar && it.isBounding }
                as? FieldSpec.UseCodecScalar
        if (boundingField != null) {
            val budget = peekBudgetFor(boundingField.fieldType)
            val priors = shape.fields.takeWhile { it !== boundingField }
            val priorAreFixed = priors.all { it is FieldSpec.FixedSize }
            val priorAreFramable =
                priors.all {
                    it is FieldSpec.FixedSize ||
                        it is FieldSpec.ProtocolMessageScalar ||
                        (it is FieldSpec.UseCodecScalar && it.isVariableLength)
                }
            // No peek budget for the length field (value-class / non-scalar),
            // or a prior the walker can't size → NoFraming.
            if (budget == null || !priorAreFramable) {
                builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
                return builder.build()
            }
            if (priorAreFixed) {
                // All-fixed priors: the static-offset walker (byte-identical
                // to every pre-existing bounding fixture).
                appendPeekUseCodecScalar(builder, shape, boundingField, budget)
            } else {
                // A self-framing variable-width prior is present (varint
                // discriminator): measure prior offsets at runtime.
                appendPeekBoundingDynamicPrior(builder, shape, boundingField, budget)
            }
            return builder.build()
        }
        // No bounding length: the remaining cases key off the first
        // `@UseCodec` field and require statically-sized priors.
        val priorAreFixed =
            shape.fields
                .takeWhile { it !== ucsField }
                .all { it is FieldSpec.FixedSize }
        if (!priorAreFixed) {
            builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
            return builder.build()
        }
        // Self-delimiting variable-width value (VariableLengthCodec): the
        // value occupies only its own bytes, so total = prior + width +
        // fixed-suffix — provided every field after it is FixedSize (a
        // variable suffix would desync the byte count). Width comes from the
        // codec's own peekFrameSize (no peek budget needed), so this also
        // composes through a value-class codec whose inner is a varint.
        if (ucsField.isVariableLength) {
            val suffixAreFixed =
                shape.fields
                    .dropWhile { it !== ucsField }
                    .drop(1)
                    .all { it is FieldSpec.FixedSize }
            if (!suffixAreFixed) {
                builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
                return builder.build()
            }
            appendPeekVariableLengthUseCodecScalar(builder, shape, ucsField)
            return builder.build()
        }
        // Non-bounding, non-variable-length @UseCodec: no value-to-byte
        // mapping the walker can use.
        builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
        return builder.build()
    }
    // `@LengthPrefixed @UseCodec val: List<E>`
    // peek mirrors the bounding-`UseCodecScalar` walker: total =
    // priorBytes + observed-codec-width + decodedValue.toInt(). The
    // codec is `BoundingLengthCodec<UInt>` (validator-checked); peek
    // budget is the UInt budget (5 bytes — covers 7-bit-continuation
    // var-byte-int up to 4 bytes plus a sentinel byte). NoFraming
    // when prior fields aren't all FixedSize OR the list isn't the
    // last field — fields after the bounded region don't contribute
    // to the value and would desync the formula.
    val lpUcField =
        shape.fields.firstOrNull { it is FieldSpec.LengthPrefixedUseCodecList }
            as? FieldSpec.LengthPrefixedUseCodecList
    if (lpUcField != null) {
        val priorAreFixed =
            shape.fields
                .takeWhile { it !== lpUcField }
                .all { it is FieldSpec.FixedSize }
        val isTerminal = shape.fields.last() === lpUcField
        if (!priorAreFixed || !isTerminal) {
            builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
            return builder.build()
        }
        appendPeekLengthPrefixedUseCodecList(builder, shape, lpUcField, peekBudget = 5)
        return builder.build()
    }
    // `@RemainingBytes List<@ProtocolMessage T>` and
    // `@RemainingBytes val: String` collapse peek to NoFraming. The
    // body's byte count comes from the caller-set buffer limit,
    // which the stream-side peek can't see; consumers must use
    // outer-protocol framing (e.g., MQTT's fixed-header remaining-
    // length) to determine the bounded read.
    if (shape.fields.any {
            it is FieldSpec.RemainingBytesProtocolMessageList ||
                it is FieldSpec.RemainingBytesString
        }
    ) {
        builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
        return builder.build()
    }
    // `@Count List<@ProtocolMessage T>` collapses peek to NoFraming. The
    // element-count prefix gives the number of elements, not a byte span, so
    // the total frame size can only be recovered by decoding each element
    // (variable-width) — which peek must not do. A stream-side consumer frames
    // such a message via its outer protocol, identical to the byte-length list
    // shapes above.
    if (shape.fields.any { it is FieldSpec.CountPrefixedProtocolMessageList }) {
        builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
        return builder.build()
    }
    // Same NoFraming collapse for `@RemainingBytes
    // @UseCodec val: P`. Body byte count is whatever the user codec
    // reads against the caller-set limit; 's outer
    // dispatcher will own peek by reading the fixed header's
    // remaining-length first.
    if (shape.fields.any { it is FieldSpec.RemainingBytesPayload }) {
        builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
        return builder.build()
    }
    // Bare `val: T: @ProtocolMessage` collapses
    // peek to NoFraming. The body's byte count is determined by T's
    // codec at runtime (variable for sealed dispatchers, static for
    // data classes), and we don't invoke decoded codecs in peek.
    // ConnAck.reasonCode et al. peek is owned upstream by the bounding
    // RL field via `appendPeekUseCodecScalar`.
    if (shape.fields.any { it is FieldSpec.ProtocolMessageScalar }) {
        builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
        return builder.build()
    }
    // An enum field's ordinal is a self-delimiting unsigned-LEB128 varint, so a SINGLE enum with
    // all-fixed priors + suffix frames exactly like the `isVariableLength` `@UseCodec` path:
    // total = priorBytes + UnsignedVarIntCodec.peekFrameSize().bytes + suffixBytes. Multiple enums
    // (or a variable suffix) collapse to NoFraming — the same single-variable-field limitation the
    // varint `@UseCodec` peek has (a second variable width would desync the static offset math).
    val enumFields = shape.fields.filterIsInstance<FieldSpec.EnumScalar>()
    if (enumFields.isNotEmpty()) {
        val enumField = enumFields.first()
        val priorsFixed = shape.fields.takeWhile { it !== enumField }.all { it is FieldSpec.FixedSize }
        val suffixFixed =
            shape.fields
                .dropWhile { it !== enumField }
                .drop(1)
                .all { it is FieldSpec.FixedSize }
        if (enumFields.size == 1 && priorsFixed && suffixFixed) {
            appendPeekEnum(builder, shape, enumField)
        } else {
            builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
        }
        return builder.build()
    }
    // `@When("remaining <op> <int>")` collapses peek to
    // NoFraming when reached at this point. The grammar-2 predicate
    // tests the decode buffer's `remaining()` after the upstream
    // bounding `applyBound`; peek has no symmetric primitive, so the
    // conditional inner's wire presence can't be predicted from a
    // stream-only walk. v5 acks (PUBACK et al.) escape this collapse
    // because their bounding RL field is handled by the
    // `UseCodecScalar` branch above — that branch returns total =
    // priorBytes + vbi_width + rl_value before reaching here, so the
    // sequential walk never visits the grammar-2 field.
    //
    // Also collapse for any conditional whose inner is
    // `LengthPrefixedUseCodecList` (the cascading-trailer property
    // bag). The inner shape is variable-length — a VBI prefix +
    // body bytes — and peek can't predict whether the conditional
    // branch is taken without buffer access. v5 ack peek is owned
    // by the bounding RL upstream (same as above).
    //
    // Same collapse for `UseCodecScalar`
    // inners. The user codec's wire width is opaque to the framework
    // (could be a single byte for a typed RC, could be variable),
    // so peek can't size the field without invoking the codec. v5
    // ack peek again handled by the bounding RL upstream.
    if (shape.fields.any { it.isPeekCollapsingConditional() }) {
        builder.addStatement("return %T.NoFraming", PEEK_RESULT_CN)
        return builder.build()
    }
    // All-FixedSize messages collapse to a single arithmetic check —
    // no walk needed, and the generated code is significantly tighter
    // than the sequential path (which would emit a per-field
    // availability check + offset advance).
    //
    // Empty-fields singletons fall into this
    // branch (the `all { ... }` predicate is vacuously true). When
    // the singleton self-frames a `@DispatchOn(value class)`
    // discriminator, add the discriminator's inner-scalar width so
    // the peek count matches what decode actually consumes.
    if (shape.fields.all { it is FieldSpec.FixedSize }) {
        val discriminatorBytes =
            (shape.singletonDispatchDiscriminator?.wireWidth ?: WireWidth.Zero)
                .requireFixed("singletonDiscriminator")
        val total = shape.fields.sumOfFixedWireBytes().requireFixed("sumOfFixedWireBytes") + discriminatorBytes
        builder.addStatement(
            "return if (stream.available() - baseOffset >= %L) %T.Complete(%L) else %T.NeedsMoreData",
            total,
            PEEK_RESULT_CN,
            total,
            PEEK_RESULT_CN,
        )
        return builder.build()
    }
    appendSequentialPeek(builder, shape)
    return builder.build()
}

/**
 * Emit peek for a shape carrying a bounding
 * `@UseCodec val: <scalar>` field. Materializes a non-consuming view
 * via `stream.peekBuffer(baseOffset + priorBytes, peekBudget)`, runs
 * `<codec>.decode` against the view, and computes total =
 * priorBytes + observed-codec-width + decodedValue.toInt(). The
 * user codec does the var-int read against the peek view, so the
 * peek path stays in lockstep with the decode path by construction.
 *
 * `IndexOutOfBoundsException` from the codec (the documented
 * underflow signal on `ReadBuffer`) collapses to
 * `PeekResult.NeedsMoreData`. Other exceptions (e.g. the codec's
 * own `DecodeException` for malformed wire) propagate — the stream
 * genuinely has bad data and the caller should observe the
 * exception, not loop on `NeedsMoreData`.
 *
 * The peek view is released via `freeNativeMemory()` in a `finally`
 * so the slow-path pool buffer is returned even when peek aborts.
 * The fast-path slice's `freeNativeMemory()` is a no-op for non-
 * pooled chunks.
 */
internal fun appendPeekUseCodecScalar(
    builder: FunSpec.Builder,
    shape: CodecShape,
    field: FieldSpec.UseCodecScalar,
    peekBudget: Int,
) {
    val priorBytes =
        shape.fields
            .takeWhile { it !== field }
            .filterIsInstance<FieldSpec.FixedSize>()
            .sumOf { it.wireBytes }
    val body = CodeBlock.builder()
    body.addStatement(
        "if (stream.available() - baseOffset < %L) return %T.NeedsMoreData",
        priorBytes + 1,
        PEEK_RESULT_CN,
    )
    val peekViewVar = "__${field.name}PeekView"
    body.addStatement(
        "val %L = stream.peekBuffer(baseOffset + %L, %L) ?: return %T.NeedsMoreData",
        peekViewVar,
        priorBytes,
        peekBudget,
        PEEK_RESULT_CN,
    )
    body.beginControlFlow("try")
    val priorPosVar = "__${field.name}PriorPos"
    body.addStatement("val %L = %L.position()", priorPosVar, peekViewVar)
    body.beginControlFlow("val %L = try", field.name)
    body.addStatement(
        "%T.decode(%L, %T.Empty)",
        field.codecType,
        peekViewVar,
        DECODE_CONTEXT_CN,
    )
    // Catch the underflow exception cross-platform via simpleName
    // whitelist: JVM `java.nio.BufferUnderflowException` (extends
    // RuntimeException, NOT IndexOutOfBoundsException), JS/WASM/
    // Native `IndexOutOfBoundsException` / `ArrayIndexOutOfBoundsException`.
    // Any other exception (e.g. the codec's own DecodeException for
    // malformed wire) propagates — the stream genuinely has bad
    // data and the caller should observe it, not loop on
    // NeedsMoreData.
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
    val widthVar = "__${field.name}Width"
    body.addStatement("val %L = %L.position() - %L", widthVar, peekViewVar, priorPosVar)
    body.addStatement(
        "val __total = %L + %L + %L.toInt()",
        priorBytes,
        widthVar,
        field.name,
    )
    body.addStatement(
        "return if (stream.available() - baseOffset >= __total) %T.Complete(__total) else %T.NeedsMoreData",
        PEEK_RESULT_CN,
        PEEK_RESULT_CN,
    )
    body.nextControlFlow("finally")
    body.addStatement(
        "(%L as? %T)?.freeNativeMemory()",
        peekViewVar,
        PLATFORM_BUFFER_CN,
    )
    body.endControlFlow()
    builder.addCode(body.build())
}

/**
 * Bounding `@UseCodec` peek where one or more prior fields are **not**
 * fixed-width but are self-framing — most importantly a dispatch variant's
 * re-read varint discriminator (the HTTP/3 frame's QUIC-varint `Type` before
 * the bounding `Length`). The fixed-prior sibling [appendPeekUseCodecScalar]
 * sums a compile-time `priorBytes`; here that sum is accumulated at runtime
 * into `__priorBytes`:
 *
 *  - a `FixedSize` prior contributes its constant `wireBytes`;
 *  - a `ProtocolMessageScalar` or self-delimiting variable-length
 *    `UseCodecScalar` prior contributes the width its own codec's
 *    `peekFrameSize` reports — measured at `baseOffset + __priorBytes`,
 *    exactly the dispatcher's discriminator-framing idiom, so peek and decode
 *    read the same bytes. A non-`Complete` prior result (the discriminator
 *    isn't fully buffered, or itself yields `NoFraming`) propagates straight
 *    out.
 *
 * After the priors, the bounded length is materialized and decoded against a
 * non-consuming `peekBuffer` view (underflow → `NeedsMoreData`, the same
 * contract as [appendPeekUseCodecScalar]) and the frame total is
 * `__priorBytes + lengthWidth + value`.
 */
internal fun appendPeekBoundingDynamicPrior(
    builder: FunSpec.Builder,
    shape: CodecShape,
    field: FieldSpec.UseCodecScalar,
    peekBudget: Int,
) {
    val priors = shape.fields.takeWhile { it !== field }
    val body = CodeBlock.builder()
    body.addStatement("var __priorBytes = 0")
    for (prior in priors) {
        val priorCodec: ClassName? =
            when (prior) {
                is FieldSpec.ProtocolMessageScalar -> prior.codecType
                is FieldSpec.UseCodecScalar -> prior.codecType
                else -> null
            }
        when {
            prior is FieldSpec.FixedSize ->
                body.addStatement("__priorBytes += %L", prior.wireBytes)
            priorCodec != null -> {
                val frameVar = "__${prior.name}Frame"
                body.addStatement(
                    "val %L = %T.peekFrameSize(stream, baseOffset + __priorBytes)",
                    frameVar,
                    priorCodec,
                )
                body.beginControlFlow("if (%L !is %T.Complete)", frameVar, PEEK_RESULT_CN)
                body.addStatement("return %L", frameVar)
                body.endControlFlow()
                body.addStatement("__priorBytes += %L.bytes", frameVar)
            }
            else ->
                error("priorAreFramable should have excluded ${prior::class.simpleName}")
        }
    }
    body.addStatement(
        "if (stream.available() - baseOffset < __priorBytes + 1) return %T.NeedsMoreData",
        PEEK_RESULT_CN,
    )
    val peekViewVar = "__${field.name}PeekView"
    body.addStatement(
        "val %L = stream.peekBuffer(baseOffset + __priorBytes, %L) ?: return %T.NeedsMoreData",
        peekViewVar,
        peekBudget,
        PEEK_RESULT_CN,
    )
    body.beginControlFlow("try")
    val priorPosVar = "__${field.name}PriorPos"
    body.addStatement("val %L = %L.position()", priorPosVar, peekViewVar)
    body.beginControlFlow("val %L = try", field.name)
    body.addStatement(
        "%T.decode(%L, %T.Empty)",
        field.codecType,
        peekViewVar,
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
    val widthVar = "__${field.name}Width"
    body.addStatement("val %L = %L.position() - %L", widthVar, peekViewVar, priorPosVar)
    body.addStatement("val __total = __priorBytes + %L + %L.toInt()", widthVar, field.name)
    body.addStatement(
        "return if (stream.available() - baseOffset >= __total) %T.Complete(__total) else %T.NeedsMoreData",
        PEEK_RESULT_CN,
        PEEK_RESULT_CN,
    )
    body.nextControlFlow("finally")
    body.addStatement(
        "(%L as? %T)?.freeNativeMemory()",
        peekViewVar,
        PLATFORM_BUFFER_CN,
    )
    body.endControlFlow()
    builder.addCode(body.build())
}

/**
 * Peek a message whose single variable-width field is an enum (ordinal as unsigned-LEB128 varint).
 * Mirror of [appendPeekVariableLengthUseCodecScalar] with the codec fixed to `UnsignedVarIntCodec`:
 * total = priorBytes + the varint's observed width + suffixBytes.
 */
internal fun appendPeekEnum(
    builder: FunSpec.Builder,
    shape: CodecShape,
    field: FieldSpec.EnumScalar,
) {
    val priorBytes =
        shape.fields
            .takeWhile { it !== field }
            .filterIsInstance<FieldSpec.FixedSize>()
            .sumOf { it.wireBytes }
    val suffixBytes =
        shape.fields
            .dropWhile { it !== field }
            .drop(1)
            .filterIsInstance<FieldSpec.FixedSize>()
            .sumOf { it.wireBytes }
    val body = CodeBlock.builder()
    val frameVar = "__${field.name}Frame"
    body.addStatement(
        "val %L = %T.peekFrameSize(stream, baseOffset + %L)",
        frameVar,
        UNSIGNED_VARINT_CODEC_CN,
        priorBytes,
    )
    body.beginControlFlow("if (%L !is %T.Complete)", frameVar, PEEK_RESULT_CN)
    body.addStatement("return %L", frameVar)
    body.endControlFlow()
    body.addStatement("val __total = %L + %L.bytes + %L", priorBytes, frameVar, suffixBytes)
    body.addStatement(
        "return if (stream.available() - baseOffset >= __total) %T.Complete(__total) else %T.NeedsMoreData",
        PEEK_RESULT_CN,
        PEEK_RESULT_CN,
    )
    builder.addCode(body.build())
}

/**
 * Emit peek for a shape carrying a non-bounding, **variable-length**
 * `@UseCodec val: <scalar>` field (codec implements `VariableLengthCodec`).
 * The decoded value is *not* a body length, so the frame total is
 * `priorBytes + codecWidth + fixedSuffixBytes` — no value term.
 *
 * Width comes from the codec's own `peekFrameSize` (every
 * `VariableLengthCodec` derives it from `peekValue`): `Complete(width)` once
 * the self-delimiting value is fully buffered, `NeedsMoreData` otherwise.
 * Delegating to the codec means no peek budget is needed and the same path
 * composes through a generated value-class codec whose inner is a varint.
 *
 * Caller guarantees (in [buildPeekFrameFun]): every prior and suffix field
 * is `FixedSize`.
 */
internal fun appendPeekVariableLengthUseCodecScalar(
    builder: FunSpec.Builder,
    shape: CodecShape,
    field: FieldSpec.UseCodecScalar,
) {
    val priorBytes =
        shape.fields
            .takeWhile { it !== field }
            .filterIsInstance<FieldSpec.FixedSize>()
            .sumOf { it.wireBytes }
    val suffixBytes =
        shape.fields
            .dropWhile { it !== field }
            .drop(1)
            .filterIsInstance<FieldSpec.FixedSize>()
            .sumOf { it.wireBytes }
    val body = CodeBlock.builder()
    val frameVar = "__${field.name}Frame"
    body.addStatement(
        "val %L = %T.peekFrameSize(stream, baseOffset + %L)",
        frameVar,
        field.codecType,
        priorBytes,
    )
    // Propagate NeedsMoreData / NoFraming unchanged; only a Complete width
    // lets us size the whole frame.
    body.beginControlFlow("if (%L !is %T.Complete)", frameVar, PEEK_RESULT_CN)
    body.addStatement("return %L", frameVar)
    body.endControlFlow()
    body.addStatement(
        "val __total = %L + %L.bytes + %L",
        priorBytes,
        frameVar,
        suffixBytes,
    )
    body.addStatement(
        "return if (stream.available() - baseOffset >= __total) %T.Complete(__total) else %T.NeedsMoreData",
        PEEK_RESULT_CN,
        PEEK_RESULT_CN,
    )
    builder.addCode(body.build())
}

/**
 * Emit peek for a shape carrying a terminal
 * `@LengthPrefixed @UseCodec val: List<E>` field. Mirrors
 * [appendPeekUseCodecScalar]: drives the prefix codec against a
 * non-consuming `stream.peekBuffer(...)` view, measures observed
 * codec width, computes total = priorBytes + width +
 * decodedValue.toInt(). The decoded UInt is the body byte count, so
 * adding it to the prefix bytes yields the full frame size. Caller
 * has already gated on `priorAreFixed && isTerminal`.
 */
internal fun appendPeekLengthPrefixedUseCodecList(
    builder: FunSpec.Builder,
    shape: CodecShape,
    field: FieldSpec.LengthPrefixedUseCodecList,
    peekBudget: Int,
) {
    val priorBytes =
        shape.fields
            .takeWhile { it !== field }
            .filterIsInstance<FieldSpec.FixedSize>()
            .sumOf { it.wireBytes }
    val body = CodeBlock.builder()
    body.addStatement(
        "if (stream.available() - baseOffset < %L) return %T.NeedsMoreData",
        priorBytes + 1,
        PEEK_RESULT_CN,
    )
    val peekViewVar = "__${field.name}PeekView"
    body.addStatement(
        "val %L = stream.peekBuffer(baseOffset + %L, %L) ?: return %T.NeedsMoreData",
        peekViewVar,
        priorBytes,
        peekBudget,
        PEEK_RESULT_CN,
    )
    body.beginControlFlow("try")
    val priorPosVar = "__${field.name}PriorPos"
    val lengthVar = "__${field.name}Length"
    body.addStatement("val %L = %L.position()", priorPosVar, peekViewVar)
    body.beginControlFlow("val %L = try", lengthVar)
    body.addStatement(
        "%T.decode(%L, %T.Empty)",
        field.codecType,
        peekViewVar,
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
    val widthVar = "__${field.name}Width"
    body.addStatement("val %L = %L.position() - %L", widthVar, peekViewVar, priorPosVar)
    body.addStatement(
        "val __total = %L + %L + %L.toInt()",
        priorBytes,
        widthVar,
        lengthVar,
    )
    body.addStatement(
        "return if (stream.available() - baseOffset >= __total) %T.Complete(__total) else %T.NeedsMoreData",
        PEEK_RESULT_CN,
        PEEK_RESULT_CN,
    )
    body.nextControlFlow("finally")
    body.addStatement(
        "(%L as? %T)?.freeNativeMemory()",
        peekViewVar,
        PLATFORM_BUFFER_CN,
    )
    body.endControlFlow()
    builder.addCode(body.build())
}

/**
 * General sequential peek walk.
 *
 * Tracks a running `__offset` (relative to `baseOffset`) and per
 * field:
 *   - Ensures enough bytes are available before any peek that
 *     would otherwise read past the buffer end.
 *   - Stashes peeked bytes for `Scalar` and `ValueClassScalar`
 *     fields whose names are referenced by a later `Conditional`
 *     source or `LengthFromString` length-carrier. Stashed locals
 *     are named after the field itself (e.g., `flags`,
 *     `payloadLength`) so the predicate / sibling expressions
 *     read naturally.
 *   - Advances `__offset` by the field's contribution: fixed
 *     bytes for `FixedSize`, prefix-width plus body-byte-count
 *     (peeked) for `LengthPrefixed*`, sibling-driven length for
 *     `LengthFromString`, predicate-gated shape for
 *     `Conditional`.
 *
 * Replaces the /3/3.5/4 specialized peek paths
 * (single-LPS-terminal, single-Conditional-terminal,
 * single-LengthFromString-terminal). Equivalent results for those
 * shapes; previously-skipped shapes (multiple sequential
 * variable-length fields, non-terminal Conditional, non-terminal
 * LengthPrefixedString) become emitable here.
 */
internal fun appendSequentialPeek(
    builder: FunSpec.Builder,
    shape: CodecShape,
) {
    val needsPeekStash = collectPeekStashSources(shape)
    val body = CodeBlock.builder()
    body.addStatement("var __offset = 0")
    for (field in shape.fields) {
        when (field) {
            is FieldSpec.Scalar -> {
                appendPeekAvailabilityCheck(body, field.wireWidth)
                if (field.name in needsPeekStash) {
                    appendPeekScalar(body, field, field.name, "__offset")
                }
                body.addStatement("__offset += %L", field.wireWidth.requireFixed("appendSequentialPeek"))
            }
            is FieldSpec.ValueClassScalar -> {
                appendPeekAvailabilityCheck(body, field.wireWidth)
                if (field.name in needsPeekStash) {
                    val rawVar = "${field.name}Raw"
                    // Follow-up: pass the value class's wireOrder
                    // so multi-byte inner kinds (UShort/UInt) assemble in
                    // the correct order on the peek side. Single-byte
                    // kinds ignore the parameter.
                    appendPeekFixedScalar(
                        body = body,
                        kind = field.innerKind,
                        targetVar = rawVar,
                        offsetExpr = "__offset",
                        wireOrder = field.valueClassWireOrder,
                    )
                    body.addStatement(
                        "val %L = %T(%L)",
                        field.name,
                        field.valueClassType,
                        rawVar,
                    )
                }
                body.addStatement("__offset += %L", field.wireWidth.requireFixed("appendSequentialPeek"))
            }
            is FieldSpec.LengthPrefixedString ->
                appendSequentialPeekLengthPrefixed(
                    body = body,
                    name = field.name,
                    ownerSimpleName = field.ownerSimpleName,
                    prefixWidth = field.prefixWidth,
                    prefixWireOrder = field.prefixWireOrder,
                )
            is FieldSpec.LengthPrefixedMessage ->
                appendSequentialPeekLengthPrefixed(
                    body = body,
                    name = field.name,
                    ownerSimpleName = field.ownerSimpleName,
                    prefixWidth = field.prefixWidth,
                    prefixWireOrder = field.prefixWireOrder,
                )
            is FieldSpec.LengthFromString ->
                appendSequentialPeekLengthFrom(
                    body = body,
                    name = field.name,
                    ownerSimpleName = field.ownerSimpleName,
                    source = field.source,
                )
            is FieldSpec.LengthFromList ->
                appendSequentialPeekLengthFrom(
                    body = body,
                    name = field.name,
                    ownerSimpleName = field.ownerSimpleName,
                    source = field.source,
                )
            is FieldSpec.LengthFromMessage ->
                // Peek shape identical to LengthFromString /
                // LengthFromList: body byte count comes from the
                // sibling, regardless of nested-message contents.
                appendSequentialPeekLengthFrom(
                    body = body,
                    name = field.name,
                    ownerSimpleName = field.ownerSimpleName,
                    source = field.source,
                )
            is FieldSpec.RemainingBytesProtocolMessageList ->
                error(
                    "RemainingBytesProtocolMessageList should be handled by " +
                        "buildPeekFrameFun's upfront NoFraming short-circuit before reaching " +
                        "the sequential walk.",
                )
            is FieldSpec.CountPrefixedProtocolMessageList ->
                error(
                    "CountPrefixedProtocolMessageList should be handled by buildPeekFrameFun's " +
                        "upfront NoFraming short-circuit before reaching the sequential walk.",
                )
            is FieldSpec.RemainingBytesPayload ->
                error(
                    "RemainingBytesPayload should be handled by buildPeekFrameFun's " +
                        "upfront NoFraming short-circuit before reaching the sequential walk.",
                )
            is FieldSpec.RemainingBytesString ->
                error(
                    "RemainingBytesString should be handled by buildPeekFrameFun's " +
                        "upfront NoFraming short-circuit before reaching the sequential walk.",
                )
            is FieldSpec.UseCodecScalar ->
                error(
                    "UseCodecScalar should be handled by buildPeekFrameFun's upfront " +
                        "NoFraming short-circuit before reaching the sequential walk; the " +
                        "generic @UseCodec peek walker is not implemented in the sequential path.",
                )
            is FieldSpec.LengthPrefixedUseCodecList ->
                error(
                    "LengthPrefixedUseCodecList should be handled by buildPeekFrameFun's " +
                        "upfront NoFraming short-circuit / dedicated peek emitter before " +
                        "reaching the sequential walk; the terminal-only peek walker is " +
                        "not implemented in the sequential path.",
                )
            is FieldSpec.LengthPrefixedUseCodecPayload ->
                // Peek walks the fixed-width
                // prefix and advances by the body byte count without
                // running the user codec. Same shape as
                // [LengthPrefixedString] / [LengthPrefixedMessage]:
                // the prefix tells us the body size.
                appendSequentialPeekLengthPrefixed(
                    body = body,
                    name = field.name,
                    ownerSimpleName = field.ownerSimpleName,
                    prefixWidth = field.prefixWidth,
                    prefixWireOrder = field.prefixWireOrder,
                )
            is FieldSpec.ProtocolMessageScalar ->
                error(
                    "ProtocolMessageScalar should be handled by buildPeekFrameFun's " +
                        "upfront NoFraming short-circuit before reaching the sequential walk.",
                )
            is FieldSpec.Conditional ->
                appendSequentialPeekConditional(body, field)
            is FieldSpec.EnumScalar ->
                error(
                    "EnumScalar should be handled by buildPeekFrameFun's upfront NoFraming " +
                        "short-circuit before reaching the sequential walk.",
                )
        }
    }
    body.addStatement(
        "return if (stream.available() - baseOffset >= __offset) %T.Complete(__offset) else %T.NeedsMoreData",
        PEEK_RESULT_CN,
        PEEK_RESULT_CN,
    )
    builder.addCode(body.build())
}

/**
 * Walk the fields and collect every name whose value will be
 * referenced later in the peek (by a `Conditional` predicate
 * source or a `LengthFromString` length carrier). The walk
 * stashes those fields' values into named locals.
 */
internal fun collectPeekStashSources(shape: CodecShape): Set<String> {
    val sources = mutableSetOf<String>()
    for (field in shape.fields) {
        when (field) {
            is FieldSpec.Conditional ->
                when (val c = field.condition) {
                    is ConditionRef.Sibling -> sources += c.name
                    is ConditionRef.ValueClassProperty -> sources += c.siblingName
                    // Grammar 2 references no sibling; the peek path
                    // (when reachable) tests `stream.available() - baseOffset`
                    // arithmetic directly. No stash needed.
                    is ConditionRef.RemainingCmp -> {}
                }
            is FieldSpec.LengthFromString -> sources += field.source.siblingName
            is FieldSpec.LengthFromList -> sources += field.source.siblingName
            is FieldSpec.LengthFromMessage -> sources += field.source.siblingName
            else -> { /* not a source */ }
        }
    }
    return sources
}

internal fun appendPeekAvailabilityCheck(
    body: CodeBlock.Builder,
    width: WireWidth,
) {
    val bytes = width.requireFixed("appendPeekAvailabilityCheck")
    body.addStatement(
        "if (stream.available() - baseOffset < __offset + %L) return %T.NeedsMoreData",
        bytes,
        PEEK_RESULT_CN,
    )
}

/**
 * Peek a length-prefixed body (`@LengthPrefixed val:
 * String` or `@LengthPrefixed @ProtocolMessage`) inside the
 * sequential walk. The shape is identical for both: peek the
 * prefix at `__offset`, guard against `Int` overflow when
 * combined with the running offset, advance `__offset` by
 * `prefixWidth + prefix.toInt()`.
 */
internal fun appendSequentialPeekLengthPrefixed(
    body: CodeBlock.Builder,
    name: String,
    ownerSimpleName: String,
    prefixWidth: Int,
    prefixWireOrder: Endianness,
) {
    appendPeekAvailabilityCheck(body, WireWidth.Fixed(prefixWidth))
    appendPeekPrefixAssembly(body, name, prefixWidth, prefixWireOrder, "__offset")
    val prefixVar = "${name}Prefix"
    body.beginControlFlow(
        "if (%L > (Int.MAX_VALUE - __offset - %L).toUInt())",
        prefixVar,
        prefixWidth,
    )
    body.addStatement(
        "throw %T(fieldPath = %S, bufferPosition = baseOffset + __offset, expected = %S, actual = %P)",
        DECODE_EXCEPTION_CN,
        "$ownerSimpleName.$name",
        "__offset + $prefixWidth + length prefix <= \${Int.MAX_VALUE}",
        "\${__offset + $prefixWidth + $prefixVar.toInt()}",
    )
    body.endControlFlow()
    body.addStatement(
        "__offset += %L + %L.toInt()",
        prefixWidth,
        prefixVar,
    )
}

/**
 * Peek a `@LengthFrom`-style slot (terminal `LengthFromString` or
 * `LengthFromList`) inside the sequential walk. The sibling local was
 * peek-stashed earlier (Scalar-sibling case) or the sibling
 * value-class instance was peek-stashed and reconstructed (dotted
 * case); the `LengthSource.decodeAccessor()` produces the right Int
 * expression for either form. The Int.MAX_VALUE guard only applies to
 * the simple form (the dotted property returns Int).
 */
internal fun appendSequentialPeekLengthFrom(
    body: CodeBlock.Builder,
    name: String,
    ownerSimpleName: String,
    source: LengthSource,
) {
    if (source is LengthSource.Sibling) {
        appendLengthFromIntMaxGuard(
            body = body,
            siblingAccessor = source.siblingName,
            siblingKind = source.siblingKind,
            ownerSimpleName = ownerSimpleName,
            fieldName = name,
        )
    }
    body.addStatement(
        "val %LBytes = %L",
        name,
        source.decodeAccessor(),
    )
    body.addStatement(
        "if (stream.available() - baseOffset < __offset + %LBytes) return %T.NeedsMoreData",
        name,
        PEEK_RESULT_CN,
    )
    body.addStatement("__offset += %LBytes", name)
}

/**
 * Peek a `@When` slot inside the sequential walk.
 * The predicate source has already been peek-stashed (added to
 * `needsPeekStash` and read when its field was visited); the
 * inner shape is gated on that stashed local.
 */
internal fun appendSequentialPeekConditional(
    body: CodeBlock.Builder,
    field: FieldSpec.Conditional,
) {
    val condExpr = decodeConditionExpr(field.condition)
    body.beginControlFlow("if (%L)", condExpr)
    when (val inner = field.inner) {
        is ConditionalInner.Scalar -> {
            appendPeekAvailabilityCheck(body, inner.kind.wireWidth)
            body.addStatement("__offset += %L", inner.kind.wireWidth.requireFixed("appendSequentialPeekConditional"))
        }
        is ConditionalInner.LengthPrefixedString ->
            appendSequentialPeekLengthPrefixed(
                body = body,
                name = field.name,
                ownerSimpleName = field.ownerSimpleName,
                prefixWidth = inner.prefixWidth,
                prefixWireOrder = inner.prefixWireOrder,
            )
        is ConditionalInner.ValueClassScalar -> {
            // Peek consumes the inner scalar's
            // natural width when the predicate is true (the value
            // class wraps with no extra wire bytes).
            appendPeekAvailabilityCheck(body, inner.innerKind.wireWidth)
            val innerWidth = inner.innerKind.wireWidth.requireFixed("appendSequentialPeekConditional")
            body.addStatement("__offset += %L", innerWidth)
        }
        is ConditionalInner.LengthPrefixedUseCodecList ->
            // Unreachable: any shape with this inner
            // collapses the whole frame to NoFraming via
            // `buildPeekFrameFun`'s upfront short-circuit, so the
            // sequential walk never reaches here.
            error(
                "appendSequentialPeekConditional reached LengthPrefixedUseCodecList — " +
                    "buildPeekFrameFun should have short-circuited the shape to NoFraming.",
            )
        is ConditionalInner.LengthPrefixedUseCodecPayload ->
            // Peek walks the fixed-width
            // prefix and advances by the body byte count. Same
            // shape as [LengthPrefixedString] — the prefix tells
            // the peek walker how many bytes to advance without
            // running the user codec.
            appendSequentialPeekLengthPrefixed(
                body = body,
                name = field.name,
                ownerSimpleName = field.ownerSimpleName,
                prefixWidth = inner.prefixWidth,
                prefixWireOrder = inner.prefixWireOrder,
            )
        is ConditionalInner.UseCodecScalar ->
            // Same NoFraming short-circuit as
            // LengthPrefixedUseCodecList. The user codec's wire width
            // is opaque, so `buildPeekFrameFun` collapses the whole
            // frame; the sequential walk never visits this branch.
            error(
                "appendSequentialPeekConditional reached UseCodecScalar — " +
                    "buildPeekFrameFun should have short-circuited the shape to NoFraming.",
            )
        is ConditionalInner.ProtocolMessageScalar ->
            // Same NoFraming short-circuit. The
            // generated `<T>Codec.peekFrameSize` could in principle size
            // the inner field, but the cascading-trailer cases that drive
            // this shape use grammar-2 `remaining >= N` predicates whose
            // truth depends on the bounding RL field upstream — the
            // outer codec's peek already returns NeedsMoreData / Complete
            // off the RL value, so a per-field peek would be redundant.
            error(
                "appendSequentialPeekConditional reached ProtocolMessageScalar — " +
                    "buildPeekFrameFun should have short-circuited the shape to NoFraming.",
            )
    }
    body.endControlFlow()
}

/**
 * Decode-side predicate accessor. The decode visitor introduces
 * each prior field as a local in scope (constructor order), so
 * the simple form references the local directly and the dotted
 * form chains the property off the value-class local.
 */
internal fun decodeConditionExpr(condition: ConditionRef): String =
    when (condition) {
        is ConditionRef.Sibling -> condition.name
        is ConditionRef.ValueClassProperty -> "${condition.siblingName}.${condition.propertyName}"
        is ConditionRef.RemainingCmp ->
            "buffer.remaining() ${condition.op.symbol} ${condition.threshold}"
    }

/**
 * `true` for `Conditional` fields whose wire presence can't be predicted
 * from a stream-only peek walk. Four cases:
 *  - grammar-2 `remaining <op> <int>` predicates (depend on the bounded
 *    decode buffer's `remaining()` after upstream `applyBound`),
 *  - inner is `LengthPrefixedUseCodecList` (variable-length bag),
 *  - inner is `UseCodecScalar` (opaque codec wire width),
 *  - inner is `ProtocolMessageScalar` (variable-width sealed dispatch /
 * nested message — ).
 *
 * v5 ack peek escapes this collapse because the bounding RL field
 * upstream is handled by `appendPeekUseCodecScalar` before the
 * sequential walk reaches the conditional.
 */
internal fun FieldSpec.isPeekCollapsingConditional(): Boolean =
    this is FieldSpec.Conditional &&
        (
            condition is ConditionRef.RemainingCmp ||
                inner is ConditionalInner.LengthPrefixedUseCodecList ||
                inner is ConditionalInner.UseCodecScalar ||
                inner is ConditionalInner.ProtocolMessageScalar
        )

/**
 * Order-aware single-scalar peek for the prefix walk. Single-byte
 * kinds (`UByte` / `Byte`) read directly; unsigned multi-byte kinds
 * (`UShort` / `UInt`) assemble bytes BE/LE per the field's
 * resolvedWireOrder. Wider and signed multi-byte kinds aren't required
 * by any in-scope vector; they would need parallel peek paths (signed
 * sign-extension, ULong promotion).
 *
 * `offsetExpr` is the Kotlin sub-expression interpolated into
 * `stream.peekByte(baseOffset + <offsetExpr>)`. Callers with a
 * fixed offset pass `"0"` / `"7"`; the sequential walk
 * passes the running-offset variable (`"__offset"`).
 */
internal fun appendPeekScalar(
    body: CodeBlock.Builder,
    field: FieldSpec.Scalar,
    targetVar: String,
    offsetExpr: String,
) {
    when (field.kind) {
        ScalarKind.Boolean -> {
            body.addStatement(
                "val %L = stream.peekByte(baseOffset + %L) != 0.toByte()",
                targetVar,
                offsetExpr,
            )
        }
        ScalarKind.UByte -> {
            body.addStatement(
                "val %L = stream.peekByte(baseOffset + %L).toUByte()",
                targetVar,
                offsetExpr,
            )
        }
        ScalarKind.Byte -> {
            body.addStatement(
                "val %L = stream.peekByte(baseOffset + %L)",
                targetVar,
                offsetExpr,
            )
        }
        ScalarKind.UShort, ScalarKind.UInt -> {
            val width = field.wireWidth.requireFixed("appendPeekScalar")
            val bigEndian =
                when (field.resolvedWireOrder) {
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
                when (field.kind) {
                    ScalarKind.UShort -> "(%L).toUInt().toUShort()"
                    ScalarKind.UInt -> "(%L).toUInt()"
                    else -> error("unreachable")
                }
            body.addStatement("val %L = $narrow", targetVar, parts.joinToString(" or "))
        }
        ScalarKind.ULong, ScalarKind.Short, ScalarKind.Int, ScalarKind.Long,
        ScalarKind.Float, ScalarKind.Double,
        ->
            error(
                "peek-side reconstruction for sibling kind ${field.kind} not implemented; " +
                    "the analyzer should have rejected this shape until the wider peek path lands.",
            )
    }
}

/**
 * Int.MAX_VALUE guard for `@LengthFrom` siblings whose
 * range exceeds `Int`. `UByte` (max 255), `UShort` (max 65535),
 * `Byte` (max 127), `Short` (max 32767), and `Int` (identity)
 * fit in a non-negative `Int` and skip the guard. `UInt`,
 * `ULong`, and `Long` need the runtime check.
 */
internal fun appendLengthFromIntMaxGuard(
    body: CodeBlock.Builder,
    siblingAccessor: String,
    siblingKind: ScalarKind,
    ownerSimpleName: String,
    fieldName: String,
) {
    val needsGuard =
        when (siblingKind) {
            ScalarKind.UByte, ScalarKind.UShort, ScalarKind.Byte, ScalarKind.Short, ScalarKind.Int -> false
            ScalarKind.UInt -> true
            ScalarKind.ULong -> true
            ScalarKind.Long -> true
            ScalarKind.Boolean ->
                error("Boolean is rejected by analyzeLengthFromStringField; this branch is unreachable.")
            ScalarKind.Float, ScalarKind.Double ->
                error("Float / Double are not valid @LengthFrom siblings; analyzeLengthFromStringField rejects them.")
        }
    if (!needsGuard) return
    val (cmp, actualExpr) =
        when (siblingKind) {
            ScalarKind.UInt -> "Int.MAX_VALUE.toUInt()" to "$siblingAccessor.toString()"
            ScalarKind.ULong -> "Int.MAX_VALUE.toULong()" to "$siblingAccessor.toString()"
            ScalarKind.Long -> "Int.MAX_VALUE.toLong()" to "$siblingAccessor.toString()"
            else -> error("unreachable")
        }
    body.beginControlFlow("if (%L > %L)", siblingAccessor, cmp)
    body.addStatement(
        "throw %T(fieldPath = %S, bufferPosition = -1, expected = %S, actual = %L)",
        DECODE_EXCEPTION_CN,
        "$ownerSimpleName.$fieldName",
        "@LengthFrom source <= \${Int.MAX_VALUE}",
        actualExpr,
    )
    body.endControlFlow()
    // For signed siblings, also reject negative values. Otherwise
    // toInt() returns a negative length and readString would
    // either throw or read past the buffer end with a confusing
    // error.
    if (siblingKind == ScalarKind.Long) {
        body.beginControlFlow("if (%L < 0L)", siblingAccessor)
        body.addStatement(
            "throw %T(fieldPath = %S, bufferPosition = -1, expected = %S, actual = %L.toString())",
            DECODE_EXCEPTION_CN,
            "$ownerSimpleName.$fieldName",
            "@LengthFrom source >= 0",
            siblingAccessor,
        )
        body.endControlFlow()
    }
}

/**
 * Peek-assemble a length-prefix as a `UInt`. `prefixOffsetExpr` is
 * interpolated into `stream.peekByte(baseOffset + <expr>)`; callers
 * with a fixed offset pass `"0"` / `"$N"`, the sequential walk passes
 * the running-offset variable.
 */
internal fun appendPeekPrefixAssembly(
    body: CodeBlock.Builder,
    fieldName: String,
    width: Int,
    wireOrder: Endianness,
    prefixOffsetExpr: String,
) {
    val prefixVar = "${fieldName}Prefix"
    if (width == 1) {
        body.addStatement(
            "val %L = (stream.peekByte(baseOffset + %L).toInt() and 0xFF).toUInt()",
            prefixVar,
            prefixOffsetExpr,
        )
        return
    }
    val bigEndian =
        when (wireOrder) {
            Endianness.Big -> true
            Endianness.Little -> false
            Endianness.Default -> true
        }
    for (i in 0 until width) {
        val byteOffset = if (i == 0) prefixOffsetExpr else "$prefixOffsetExpr + $i"
        body.addStatement(
            "val %L = stream.peekByte(baseOffset + %L).toInt() and 0xFF",
            "${prefixVar}B$i",
            byteOffset,
        )
    }
    val parts =
        (0 until width).map { i ->
            val byteName = "${prefixVar}B$i"
            val shiftBits = if (bigEndian) (width - 1 - i) * 8 else i * 8
            if (shiftBits == 0) byteName else "($byteName shl $shiftBits)"
        }
    body.addStatement("val %L = (%L).toUInt()", prefixVar, parts.joinToString(" or "))
}
