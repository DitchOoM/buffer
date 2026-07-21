package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName

/**
 * Emitter.
 *
 * Generates a sibling `object ${MessageName}Codec : Codec<${MessageName}>`
 * for each `@ProtocolMessage`-annotated symbol whose shape fits the
 * supported surface:
 *
 * ** — fixed-size unsigned scalar fields.** A `data class`
 *     (or `@JvmInline value class`) with one or more `UByte` /
 *     `UShort` / `UInt` / `ULong` fields, each with optional
 *     `@WireOrder` per-field overrides of the message-level wireOrder.
 * ** — `@LengthPrefixed @ProtocolMessage`-typed body.** A
 *     trailing field of `@ProtocolMessage` data class type, length-
 *     prefixed by `LengthPrefix.{Byte|Short|Int}` in the message wire
 *     order; emitter generates `setLimit` + restore decode, prefix-
 * peek `peekFrameSize`, and the lock #4 `Int.MAX_VALUE`
 *     overflow guards.
 * ** — `@WireBytes(N)` narrowing.** A scalar field whose
 *     wire width is narrower than the Kotlin type's natural size.
 *     Always uses manual byte assembly; effective byte order falls
 *     back to `Big` (network) when neither the field nor the message
 *     declares one. Encode emits an `EncodeException` runtime guard
 *     when the value exceeds the narrowed range.
 * ** — value-class wrapper at the top level.** A
 *     `@JvmInline value class` with a single inner unsigned scalar is
 *     treated as a one-field shape. The codec wraps the read scalar
 *     into the value class on decode and unwraps it via the inner
 *     property name on encode. Bit-packed logical fields exposed as
 *     getters in user code are invisible to the emitter (they
 *     introduce no wire format).
 * ** — signed scalar fields.** `Byte` / `Short` / `Int` /
 *     `Long` at their natural width and the message's default byte
 *     order. Manual byte assembly stays unsigned-only; signed scalars
 *     with `@WireBytes` or explicit `@WireOrder` are silently skipped
 *     until a vector justifies the sign-extension design.
 * ** — `@LengthPrefixed val: String` terminal.** A
 *     trailing `String` field with a `LengthPrefix.{Byte|Short|Int}`
 *     prefix in the message wire order. Encode reserves the prefix
 *     slot, writes the body via the runtime's `writeString(text,
 *     Charset.UTF8)`, measures the byte count from the position
 *     delta, and patches the prefix in place (`WireSize.BackPatch` —
 *     locked decision row 15). Encode emits an `EncodeException`
 *     runtime guard when the UTF-8 byte length exceeds the prefix's
 *     range; for 4-byte prefixes the check is skipped because Int
 *     position deltas can never exceed UInt max.
 * ** — simple sealed dispatch with `@PacketType`.** A
 *     `@ProtocolMessage sealed interface` whose direct sealed
 *     subclasses each carry `@PacketType(value)` produces a
 *     dispatcher object: `decode` reads a 1-byte discriminator and
 *     delegates to the matched variant codec, `encode` writes the
 *     discriminator then delegates, `wireSize` is per-variant (literal
 *     `Exact(1 + N)` for fixed-size variants, `BackPatch` if the
 *     variant terminal is `@LengthPrefixed val: String`, runtime
 *     `Exact(1 + variant.bytes)` if the variant terminal is a
 *     `@LengthPrefixed @ProtocolMessage` body), and `peekFrameSize`
 *     peeks the discriminator and delegates to the variant's peek
 *     with `baseOffset + 1`. Unknown discriminator at decode or peek
 * time throws `DecodeException` per. Skips
 * when the parent carries `@DispatchOn`.
 * ** — `@When` against a sibling `Boolean`.**
 *     A constructor parameter `@When("siblingField") val name: T?`
 *     where `siblingField` is a non-nullable `Boolean` parameter
 *     declared before this one. Decode emits
 *     `val name: T? = if (sibling) <readT> else null`; encode skips
 *     the slot entirely when the predicate is false (zero bytes),
 *     and throws `EncodeException` if the predicate is true and the
 * field is null. Per, any `@When`
 *     field collapses message-level `WireSize` to `BackPatch`.
 *     `peekFrameSize` walks scalar prefix fields, peeks the boolean
 *     source statically, and adds the inner field's bytes only when
 * the predicate is true. also adds `Boolean` as a 1-byte
 * scalar (no `@WireBytes` / `@WireOrder`); inner is
 *     restricted to natural-width Scalar — `@LengthPrefixed` inner
 * lands in alongside MQTT v3 CONNECT.
 * ** — dotted `@When("sibling.property")` plus
 *     value-class fields.** A constructor parameter whose type is a
 *     `value class` with a single supported-scalar primary
 *     constructor parameter is a first-class field shape: decode
 *     reads the inner scalar at natural width and constructs the
 *     value class; encode unwraps via the inner property and writes
 *     the inner scalar. The dotted-form `@When("sibling.property")`
 *     resolves the predicate as `sibling.property` against an in-scope
 *     value-class local, where `sibling` is such a value-class field
 *     declared before the bound parameter and `property` is a
 *     `Boolean`-returning `val` declared on that value class.
 *     `peekFrameSize` peeks the value class's inner-scalar bytes at
 *     the sibling's offset, reconstructs the value class, and calls
 *     the predicate property. `@WireBytes` / `@WireOrder` on the
 * outer parameter are out of scope for.
 *
 * Anything outside this surface — `@LengthFrom`, `@RemainingBytes`,
 * `@UseCodec`, `@DispatchOn`, signed scalars in the manual-byte-
 * assembly path, `@LengthPrefixed` on a non-terminal field, non-
 * terminal `@When`, `@LengthPrefixed`-inner `@When` — is
 * silently skipped here and picked up by later stages as their
 * capability lands.
 */
internal class CodecEmitter(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {
    // Accumulated analyzed shapes for the aggregate schema descriptor
    // (SCHEMA_DRIFT.md). Collected across every tryEmit / KSP round; projected
    // into codec-schema.txt by writeSchemaDescriptor() in the processor's
    // finish(). Order does not matter — the descriptor sorts by type name.
    private val collectedCodecShapes = mutableListOf<CodecShape>()
    private val collectedDispatchShapes = mutableListOf<DispatchShape>()
    private val schemaSourceFiles = linkedSetOf<KSFile>()

    fun tryEmit(symbol: KSClassDeclaration) {
        val sourceFile = symbol.containingFile ?: return
        if (Modifier.SEALED in symbol.modifiers && symbol.classKind == ClassKind.INTERFACE) {
            // Compute the unified dispatch shape once: try the @DispatchOn
            // dispatcher path first; if the parent doesn't carry the
            // annotation (NotApplicable), fall back to the simple @PacketType
            // dispatcher. Both analyzers produce one DispatchShape directly
            // (plan stage 8) wrapped in a DispatchAnalysisResult (plan stage 9):
            // a Rejected from the @DispatchOn path is NOT retried on the simple
            // path — it means the parent IS @DispatchOn but malformed.
            val result =
                when (val onResult = analyzeDispatchOnSealedDispatcher(symbol)) {
                    DispatchAnalysisResult.NotApplicable -> analyzeSealedDispatcher(symbol)
                    else -> onResult
                }
            when (result) {
                is DispatchAnalysisResult.Supported -> {
                    val shape = result.shape
                    collectedDispatchShapes += shape
                    schemaSourceFiles += sourceFile
                    val file = buildDispatchFileSpec(shape)
                    codeGenerator
                        .createNewFile(
                            Dependencies(aggregating = false, sourceFile),
                            shape.packageName,
                            shape.codecSimpleName,
                        ).bufferedWriter()
                        .use { writer -> file.writeTo(writer) }
                }
                // A recognized-but-unsupported dispatcher shape with no paired
                // validator diagnostic — emit the diagnostic(s) so the build
                // fails loudly instead of silently producing no codec.
                is DispatchAnalysisResult.Rejected ->
                    result.diagnostics.forEach { logger.error(it.message, it.node) }
                // Not a dispatcher target, or already rejected by a validator
                // diagnostic — stay silent to avoid double-reporting.
                DispatchAnalysisResult.NotApplicable -> return
            }
            return
        }
        when (val r = analyze(symbol)) {
            is AnalysisResult.Supported -> {
                val shape = r.shape
                collectedCodecShapes += shape
                schemaSourceFiles += sourceFile
                val file = buildFileSpec(shape)
                codeGenerator
                    .createNewFile(
                        Dependencies(aggregating = false, sourceFile),
                        shape.packageName,
                        shape.codecSimpleName,
                    ).bufferedWriter()
                    .use { writer ->
                        file.writeTo(writer)
                    }
            }
            // A recognized-but-unsupported shape: emit the diagnostic(s)
            // so the build fails loudly instead of silently producing no
            // codec — covering the silent gaps the validator does not
            // already catch.
            is AnalysisResult.Rejected -> r.diagnostics.forEach { logger.error(it.message, it.node) }
            // Not a codec target (handled elsewhere or already rejected by
            // the validator) — stay silent to avoid double-reporting.
            AnalysisResult.NotApplicable -> return
        }
    }

    /**
     * Emit the aggregate schema descriptor (SCHEMA_DRIFT.md) for every shape analyzed across all
     * KSP rounds. Called once from the processor's `finish()`. Projects the collected IR into the
     * line-oriented `codec-schema.txt` format ([CodecSchemaDescriptor]) and writes it alongside the
     * generated codecs. No-op when nothing was analyzed (an empty descriptor would only churn the
     * baseline). The output aggregates every source file, so it is declared `aggregating = true`.
     */
    fun writeSchemaDescriptor() {
        val text = CodecSchemaDescriptor.render(collectedCodecShapes, collectedDispatchShapes)
        if (text.isEmpty()) return
        codeGenerator
            .createNewFile(
                Dependencies(aggregating = true, *schemaSourceFiles.toTypedArray()),
                packageName = "",
                fileName = "codec-schema",
                extensionName = "txt",
            ).bufferedWriter()
            .use { writer -> writer.write(text) }
    }

    /**
     * /14c — `@FramedBy` file spec. Emits an `object`
     * codec with the new encode signature
     * (`encode(value, context, factory): ReadBuffer`) and the strict
     * decode (`decode(buffer, context): T` with bound assertion). The
     * codec does **not** implement `Codec<T>` — its encode contract
     * differs because the framework owns framing and returns a slice
     * spanning exactly the framed wire bytes (see the handoff,
     * Q5).
     *
     * Adds the `after = "<header>"` path: the named header
     * field sits before the prefix on the wire, so decode reads it
     * first and encode threads it through `FramedEncoder.writeHeader`.
     * Decode + encode emit branches on `framedBy.afterFieldName`; the
     * peek emit reuses the bounding-codec walker shape from
     * (header bytes + observed prefix width + prefix value).
     */
    private fun buildFramedByFileSpec(
        shape: CodecShape,
        framedBy: FramedByConfig,
    ): FileSpec {
        // Reset per-file — see note on buildFileSpec.
        batchCounter = 0
        val typeSpec =
            TypeSpec
                .objectBuilder(shape.codecSimpleName)
                .withVisibility(shape.visibility)
                .addFunction(buildFramedByDecodeFun(shape, framedBy))
                .addFunction(buildFramedByEncodeFun(shape, framedBy))
                .addFunction(buildFramedByPeekFrameFun(shape, framedBy))
                .build()
        return FileSpec
            .builder(shape.packageName, shape.codecSimpleName)
            .addType(typeSpec)
            .build()
    }

    private fun buildFramedByDecodeFun(
        shape: CodecShape,
        framedBy: FramedByConfig,
        messageType: TypeName = shape.messageClassName,
    ): FunSpec {
        val afterField = framedByAfterField(shape, framedBy)
        val body = CodeBlock.builder()
        // `after = "X"` reads the header field
        // before the prefix. The local emitted by appendDecodeField is
        // named after the field, so the constructor invocation below
        // binds it positionally without any extra wiring.
        if (afterField != null) {
            appendDecodeField(body, afterField)
        }
        body.addStatement("val __framingOuterLimit = buffer.limit()")
        body.addStatement(
            "val __framingLength = %T.decode(buffer, context)",
            framedBy.codecClassName,
        )
        appendFramedBodyTruncationGuard(body, "__framingLength", "${shape.ownerSimpleName}.@FramedBy")
        body.addStatement("%T.applyBound(buffer, __framingLength)", framedBy.codecClassName)
        body.addStatement("val __framingStart = buffer.position()")
        body.addStatement("val __framingBound = __framingStart + __framingLength.toInt()")
        body.beginControlFlow("return try")
        appendDecodeFields(body, shape.fields.filter { it !== afterField })
        body.beginControlFlow("if (buffer.position() != __framingBound)")
        body.addStatement(
            "throw %T(\n  fieldPath = %S,\n  bufferPosition = buffer.position(),\n" +
                "  expected = \"body to consume \" + __framingLength + \" bytes\",\n" +
                "  actual = (buffer.position() - __framingStart).toString() + \" bytes\",\n)",
            DECODE_EXCEPTION_CN,
            "${shape.ownerSimpleName}.@FramedBy",
        )
        body.endControlFlow()
        val ctorArgs = shape.fields.joinToString(", ") { "${it.name} = ${it.name}" }
        body.addStatement("%T(%L)", messageType, ctorArgs)
        body.nextControlFlow("finally")
        body.addStatement("buffer.setLimit(__framingOuterLimit)")
        body.endControlFlow()
        return FunSpec
            .builder("decode")
            .addParameter("buffer", READ_BUFFER_CN)
            .addParameter("context", DECODE_CONTEXT_CN)
            .returns(messageType)
            .addCode(body.build())
            .build()
    }

    private fun buildFramedByEncodeFun(
        shape: CodecShape,
        framedBy: FramedByConfig,
        messageType: TypeName = shape.messageClassName,
    ): FunSpec {
        val afterField = framedByAfterField(shape, framedBy)
        val body = CodeBlock.builder()
        // Variable-width header (varint discriminator): the header's
        // width isn't a compile-time constant, so measure it per-value
        // through the header codec's `wireSize` and pass the runtime
        // value to FramedEncoder (whose slack arithmetic is already
        // runtime-`Int`-driven).
        val variableAfter = afterField as? FieldSpec.UseCodecScalar
        if (variableAfter != null) {
            body.addStatement(
                "val __framingHeaderSize = %T.wireSize(value.%L, context)",
                variableAfter.codecType,
                variableAfter.name,
            )
            body.beginControlFlow("require(__framingHeaderSize is %T.Exact)", WIRE_SIZE_CN)
            body.addStatement(
                "%S",
                "framing header codec returned a non-Exact wire size for " +
                    "${shape.ownerSimpleName}.${variableAfter.name}",
            )
            body.endControlFlow()
        }
        val headerWireWidthLiteral: Int? =
            if (variableAfter == null) {
                (afterField?.let(::framedByHeaderWireWidth) ?: WireWidth.Zero)
                    .requireFixed("framedByHeaderWireWidth")
            } else {
                null
            }
        body.add("return %T.encode(\n", FRAMED_ENCODER_CN)
        body.indent()
        body.add("factory = factory,\n")
        body.add("framingCodec = %T,\n", framedBy.codecClassName)
        body.add("context = context,\n")
        if (afterField != null) {
            if (variableAfter != null) {
                body.add("headerWireWidth = __framingHeaderSize.bytes,\n")
            } else {
                body.add("headerWireWidth = %L,\n", headerWireWidthLiteral)
            }
            body.add("writeHeader = { buffer ->\n")
            body.indent()
            appendEncodeField(body, afterField, shape)
            body.unindent()
            body.add("},\n")
        }
        body.unindent()
        body.beginControlFlow(") { buffer ->")
        appendEncodeFields(body, shape.fields.filter { it !== afterField }, shape)
        body.endControlFlow()
        return FunSpec
            .builder("encode")
            .addParameter("value", messageType)
            .addParameter("context", ENCODE_CONTEXT_CN)
            .addParameter("factory", BUFFER_FACTORY_CN)
            .returns(READ_BUFFER_CN)
            .addCode(body.build())
            .build()
    }

    /**
     * Emit `peekFrameSize` for an `@FramedBy`
     * codec. Mirrors [appendPeekLengthPrefixedUseCodecList]: the prefix
     * codec is `BoundingLengthCodec<UInt>` (validator-checked), so the
     * walker drives its `decode` against a non-consuming peek view at
     * `baseOffset + headerWireWidth` and computes total = headerWireWidth
     * + observed-codec-width + decodedValue.toInt().
     */
    private fun buildFramedByPeekFrameFun(
        shape: CodecShape,
        framedBy: FramedByConfig,
    ): FunSpec {
        val afterField = framedByAfterField(shape, framedBy)
        val variableAfter = afterField as? FieldSpec.UseCodecScalar
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
        if (variableAfter != null) {
            // Variable-width header (varint discriminator): measure the
            // header's width via its codec's own peek, then walk the
            // framing prefix at the measured offset. The peek budget is
            // the framing codec's declared worst case rather than the
            // fixed-path literal (a QUIC varint length is 1..8 bytes).
            body.addStatement(
                "val __headerFrame = %T.peekFrameSize(stream, baseOffset)",
                variableAfter.codecType,
            )
            body.beginControlFlow("if (__headerFrame !is %T.Complete)", PEEK_RESULT_CN)
            body.addStatement("return __headerFrame")
            body.endControlFlow()
            body.addStatement("val __headerWireWidth = __headerFrame.bytes")
            body.addStatement(
                "if (stream.available() - baseOffset < __headerWireWidth + 1) return %T.NeedsMoreData",
                PEEK_RESULT_CN,
            )
            body.addStatement(
                "val __framingPeek = stream.peekBuffer(baseOffset + __headerWireWidth, %T.maxWireSize) " +
                    "?: return %T.NeedsMoreData",
                framedBy.codecClassName,
                PEEK_RESULT_CN,
            )
            return buildFramedByPeekFrameTail(builder, body, framedBy, headerWidthExpr = "__headerWireWidth")
        }
        val headerWireWidth =
            (afterField?.let(::framedByHeaderWireWidth) ?: WireWidth.Zero)
                .requireFixed("framedByHeaderWireWidth")
        // Need at least the header bytes plus one prefix byte before
        // attempting the codec read. Wider VBI continuations are caught
        // by the codec's underflow → NeedsMoreData fallback below.
        body.addStatement(
            "if (stream.available() - baseOffset < %L) return %T.NeedsMoreData",
            headerWireWidth + 1,
            PEEK_RESULT_CN,
        )
        // peekBuffer needs a budget large enough for the prefix codec's
        // worst case. MqttRemainingLengthCodec is 1..4 bytes; allow 5 to
        // mirror the emit's UInt VBI peek budget.
        val peekBudget = 5
        body.addStatement(
            "val __framingPeek = stream.peekBuffer(baseOffset + %L, %L) ?: return %T.NeedsMoreData",
            headerWireWidth,
            peekBudget,
            PEEK_RESULT_CN,
        )
        return buildFramedByPeekFrameTail(builder, body, framedBy, headerWidthExpr = headerWireWidth.toString())
    }

    /**
     * Shared tail of the `@FramedBy` `peekFrameSize` emit: decode the
     * framing prefix against the non-consuming `__framingPeek` view
     * (underflow → NeedsMoreData), then total = header + observed prefix
     * width + decoded length. [headerWidthExpr] is either the fixed-path
     * literal (`"1"`) or the variable-path runtime local
     * (`"__headerWireWidth"`) — both interpolate into the same shape.
     */
    private fun buildFramedByPeekFrameTail(
        builder: FunSpec.Builder,
        body: CodeBlock.Builder,
        framedBy: FramedByConfig,
        headerWidthExpr: String,
    ): FunSpec {
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
            headerWidthExpr,
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
     * Resolve the `@FramedByafter`-named
     * field to its [FieldSpec], or `null` when the name doesn't match
     * an analyzed field OR the field shape cannot serve as the framing
     * header (Scalar / ValueClassScalar for the fixed-width emit, plus
     * a variable-length non-bounding UseCodecScalar — the varint
     * discriminator — for the runtime-width emit; this mirrors the
     * validator's E3 acceptance set).
     *
     * Returning `null` for a non-Exact match is a graceful fallback: the
     * validator already logged an `E3` error against the same shape, so
     * KSP will fail the compile. The emitter just needs to avoid
     * crashing while the validator's diagnostic flows through — silently
     * degrading to the `after = ""` emit shape is enough.
     */
    private fun framedByAfterField(
        shape: CodecShape,
        framedBy: FramedByConfig,
    ): FieldSpec? {
        if (framedBy.afterFieldName.isEmpty()) return null
        val field = shape.fields.firstOrNull { it.name == framedBy.afterFieldName } ?: return null
        return when (field) {
            is FieldSpec.Scalar, is FieldSpec.ValueClassScalar -> field
            // Varint discriminator header (a value class whose inner
            // scalar carries `@UseCodec(VariableLengthCodec)`, e.g. an
            // HTTP/3 frame type). Variable wire width: the encode emit
            // measures it per-value via the codec's `wireSize` and the
            // peek emit via the codec's `peekFrameSize`, instead of the
            // compile-time constant the fixed shapes use. A *bounding*
            // UseCodecScalar can never be the framing header (its value
            // is a length, not an opcode) — excluded.
            is FieldSpec.UseCodecScalar -> field.takeIf { it.isVariableLength && !it.isBounding }
            else -> null
        }
    }

    /**
     * Exact wire width of the `@FramedBy`
     * `after`-named header field. Only called for fields that
     * [framedByAfterField] already filtered to Scalar / ValueClassScalar,
     * so the `else` branch is structurally unreachable.
     */
    private fun framedByHeaderWireWidth(field: FieldSpec): WireWidth =
        when (field) {
            is FieldSpec.Scalar -> field.wireWidth
            is FieldSpec.ValueClassScalar -> field.wireWidth
            else -> WireWidth.Zero
        }

    private fun buildFileSpec(shape: CodecShape): FileSpec {
        // Reset per-file so __batchN locals are stable across builds.
        // Otherwise the monotonic counter shifts when KSP processes shapes
        // in a different order between runs — the snapshot baseline would
        // drift on every unrelated edit.
        batchCounter = 0
        if (shape.framedBy != null && shape.payloadTypeParameter == null) {
            return buildFramedByFileSpec(shape, shape.framedBy)
        }
        if (shape.framedBy != null && shape.payloadTypeParameter != null) {
            // Generic variant inheriting `@FramedBy`
            // from a sealed parent. Drops the `Codec<Variant<P>>`
            // superinterface (the framed encode shape isn't a `Codec`),
            // emits framed encode/decode/peek + the `Partial<P>`
            // companion (decode-only, framing-aware via shape.framedBy).
            return FileSpec
                .builder(shape.packageName, shape.codecSimpleName)
                .addType(
                    buildGenericFramedByCodecTypeSpec(
                        shape,
                        shape.payloadTypeParameter,
                        shape.framedBy,
                    ),
                ).build()
        }
        val codecType =
            if (shape.payloadTypeParameter != null) {
                buildGenericCodecTypeSpec(shape, shape.payloadTypeParameter)
            } else {
                TypeSpec
                    .objectBuilder(shape.codecSimpleName)
                    .withVisibility(shape.visibility)
                    .addSuperinterface(CODEC_CN.parameterizedBy(shape.messageClassName))
                    .addFunction(buildDecodeFun(shape))
                    .addFunction(buildEncodeFun(shape))
                    .addFunction(buildWireSizeFun(shape))
                    .addFunction(buildSizeHintFun(shape))
                    .addFunction(buildPeekFrameFun(shape))
                    .also { builder ->
                        // Every codec carrying a typed payload field
                        // gets a `Partial` nested class plus a `partial(buffer,
                        // context)` decode entry. For the (object)
                        // shape, `partial` is a member of the codec object.
                        if (shouldEmitPartial(shape)) {
                            builder.addType(buildPartialClassTypeSpec(shape, payloadTypeParameter = null))
                            builder.addFunction(
                                buildPartialEntryFun(shape, payloadTypeParameter = null),
                            )
                        }
                    }.build()
            }
        return FileSpec
            .builder(shape.packageName, shape.codecSimpleName)
            .addType(codecType)
            .build()
    }

    /**
     * Emit
     * `class FooCodec<P : Payload>(private val payloadCodec: Codec<P>)
     *  : Codec<Foo<P>>`
     * for shapes whose data class declares a `<P : Payload>` type
     * parameter and a corresponding `DeferredPayload` field with
     * `ConstructorInjected` source.
     */
    private fun buildGenericCodecTypeSpec(
        shape: CodecShape,
        binding: PayloadTypeParameter,
    ): TypeSpec {
        val typeVar = TypeVariableName(binding.typeVariableName, binding.bound)
        val parameterizedMessage = shape.messageClassName.parameterizedBy(typeVar)
        val codecOfP = CODEC_CN.parameterizedBy(typeVar)
        return TypeSpec
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
            ).addSuperinterface(CODEC_CN.parameterizedBy(parameterizedMessage))
            .addFunction(buildDecodeFun(shape, parameterizedMessage))
            .addFunction(buildEncodeFun(shape, parameterizedMessage))
            .addFunction(buildWireSizeFun(shape, parameterizedMessage))
            .addFunction(buildSizeHintFun(shape, parameterizedMessage))
            .addFunction(buildPeekFrameFun(shape))
            .also { builder ->
                // For the (class) shape, `Partial` is a
                // nested class (independent type parameter <P : Payload>) and
                // `partial<P>(buffer, context)` lives on a companion object.
                // Companion-side placement matters: consumers must be able to
                // call `MqttPublishV3Codec.partial<JpegImage>(buffer, context)`
                // WITHOUT instantiating the surrounding generic codec class
                // (the whole point of 's Partial is to defer the
                // codec choice past header decode).
                if (shouldEmitPartial(shape)) {
                    builder.addType(buildPartialClassTypeSpec(shape, payloadTypeParameter = binding))
                    builder.addType(buildPartialCompanionObject(shape, payloadTypeParameter = binding))
                }
            }.build()
    }

    /**
     * Generic variant inheriting `@FramedBy`
     * from a sealed parent. Mirrors [buildGenericCodecTypeSpec]'s
     * constructor-injected payload codec field, but emits the framed
     * encode signature (`encode(value, context, factory): ReadBuffer`,
     * no wireSize) and drops the `Codec<Variant<P>>` superinterface
     * (the framed encode shape isn't a `Codec`). The
     * `Partial<P>` + companion `partial<P>(buffer, context)` still
     * emits — those are decode-only, and the partial-flow machinery
     * is framing-aware via [shape.framedBy].
     */
    private fun buildGenericFramedByCodecTypeSpec(
        shape: CodecShape,
        binding: PayloadTypeParameter,
        framedBy: FramedByConfig,
    ): TypeSpec {
        val typeVar = TypeVariableName(binding.typeVariableName, binding.bound)
        val parameterizedMessage = shape.messageClassName.parameterizedBy(typeVar)
        val codecOfP = CODEC_CN.parameterizedBy(typeVar)
        return TypeSpec
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
            ).addFunction(buildFramedByDecodeFun(shape, framedBy, parameterizedMessage))
            .addFunction(buildFramedByEncodeFun(shape, framedBy, parameterizedMessage))
            .addFunction(buildFramedByPeekFrameFun(shape, framedBy))
            .also { builder ->
                if (shouldEmitPartial(shape)) {
                    builder.addType(buildPartialClassTypeSpec(shape, payloadTypeParameter = binding))
                    builder.addType(buildPartialCompanionObject(shape, payloadTypeParameter = binding))
                }
            }.build()
    }

    private fun buildDecodeFun(
        shape: CodecShape,
        messageType: TypeName = shape.messageClassName,
    ): FunSpec {
        val body = CodeBlock.builder()
        // When a bounding `@UseCodec(BoundingLengthCodec)`
        // field is present, fields BEFORE it emit normally; the codec's
        // decode + applyBound emits at its position; fields AFTER it run
        // inside `try { ... } finally { setLimit(outer) }` so the
        // buffer's outer limit is restored even on decode failure. The
        // constructor call becomes the try-block's value expression,
        // returned by the function.
        val boundingIndex = shape.fields.indexOfFirst { it.isBoundingShape() }
        // Issue #150 — `@ProtocolMessage data object` / `object` decode
        // returns the singleton instance, NOT a constructor call. Kotlin
        // references singletons by their class name directly.
        //
        // When the singleton is a sealed variant
        // under `@DispatchOn(value class)`, consume (and discard) the
        // discriminator's inner-scalar bytes first. The dispatcher's
        // peek + reset hands control here at the original buffer
        // position; data-class variants self-frame via their `id:
        // ValueClass` first field, and singleton variants must do the
        // same so the buffer position advances by the discriminator's
        // wire width before the singleton is returned.
        if (shape.isSingletonObject) {
            shape.singletonDispatchDiscriminator?.let { d ->
                body.addStatement(naturalScalarReadExpr(d.innerKind))
            }
            body.addStatement("return %T", messageType)
            return FunSpec
                .builder("decode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", READ_BUFFER_CN)
                .addParameter("context", DECODE_CONTEXT_CN)
                .returns(messageType)
                .addCode(body.build())
                .build()
        }
        val ctorArgs = shape.fields.joinToString(", ") { "${it.name} = ${it.name}" }
        if (boundingIndex < 0) {
            appendDecodeFields(body, shape.fields)
            body.addStatement("return %T(%L)", messageType, ctorArgs)
        } else {
            appendDecodeFields(body, shape.fields.subList(0, boundingIndex + 1))
            body.beginControlFlow("return try")
            appendDecodeFields(body, shape.fields.subList(boundingIndex + 1, shape.fields.size))
            body.addStatement("%T(%L)", messageType, ctorArgs)
            body.nextControlFlow("finally")
            val boundingName = shape.fields[boundingIndex].name
            body.addStatement("buffer.setLimit(__%LOuterLimit)", boundingName)
            body.endControlFlow()
        }
        return FunSpec
            .builder("decode")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("buffer", READ_BUFFER_CN)
            .addParameter("context", DECODE_CONTEXT_CN)
            .returns(messageType)
            .addCode(body.build())
            .build()
    }

    private fun appendDecodeField(
        body: CodeBlock.Builder,
        field: FieldSpec,
    ) {
        when (field) {
            is FieldSpec.Scalar -> appendDecodeScalar(body, field)
            is FieldSpec.LengthPrefixedMessage -> appendDecodeLengthPrefixed(body, field)
            is FieldSpec.LengthPrefixedString -> appendDecodeLengthPrefixedString(body, field)
            is FieldSpec.LengthFromString -> appendDecodeLengthFromString(body, field)
            is FieldSpec.LengthFromList -> appendDecodeLengthFromList(body, field)
            is FieldSpec.LengthFromMessage -> appendDecodeLengthFromMessage(body, field)
            is FieldSpec.RemainingBytesProtocolMessageList ->
                appendDecodeRemainingBytesProtocolMessageList(body, field)
            is FieldSpec.CountPrefixedProtocolMessageList ->
                appendDecodeCountPrefixedProtocolMessageList(body, field)
            is FieldSpec.DeferredPayload -> appendDecodeDeferredPayload(body, field)
            is FieldSpec.RemainingBytesString -> appendDecodeRemainingBytesString(body, field)
            is FieldSpec.UseCodecScalar -> appendDecodeUseCodecScalar(body, field)
            is FieldSpec.LengthPrefixedUseCodecList -> appendDecodeLengthPrefixedUseCodecList(body, field)
            is FieldSpec.LengthPrefixedUseCodecPayload ->
                appendDecodeLengthPrefixedUseCodecPayload(body, field)
            is FieldSpec.ValueClassScalar -> appendDecodeValueClassScalar(body, field)
            is FieldSpec.Conditional -> appendDecodeConditional(body, field)
            is FieldSpec.ProtocolMessageScalar -> appendDecodeProtocolMessageScalar(body, field)
            is FieldSpec.EnumScalar -> appendDecodeEnum(body, field)
        }
    }

    private fun buildEncodeFun(
        shape: CodecShape,
        messageType: TypeName = shape.messageClassName,
    ): FunSpec {
        val body = CodeBlock.builder()
        // Singleton variant under
        // `@DispatchOn(value class)` writes the discriminator literal
        // (mirrors the data-class variant emit, where the variant's
        // `id: ValueClass = ValueClass(byte)` first field round-trips
        // the discriminator through the value-class scalar path).
        // Other singletons (standalone or under simple sealed parents)
        // emit nothing — their parent dispatcher writes the
        // discriminator before delegating.
        shape.singletonDispatchDiscriminator?.let { d ->
            body.addStatement(naturalScalarWriteStatement(d.innerKind, singletonDiscriminatorLiteralAccessor(d)))
        }
        appendEncodeFields(body, shape.fields, shape)
        return FunSpec
            .builder("encode")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("buffer", WRITE_BUFFER_CN)
            .addParameter("value", messageType)
            .addParameter("context", ENCODE_CONTEXT_CN)
            .addCode(body.build())
            .build()
    }

    /**
     * Accessor expression for a singleton
     * variant's `@PacketType.value` literal, narrowed to the
     * discriminator's inner scalar kind. Hex literals (e.g. `0x80`)
     * exceed `Int` range only for kinds wider than 4 bytes; UInt is
     * the widest kind in `peekableDispatcherInnerKinds`, so a hex
     * `Int` literal narrowed via `.toX()` always fits.
     */
    private fun singletonDiscriminatorLiteralAccessor(d: SingletonDispatchDiscriminator): String {
        val hex = "0x${d.literalValue.toString(16).uppercase()}"
        return when (d.innerKind) {
            ScalarKind.UByte -> "$hex.toUByte()"
            ScalarKind.UShort -> "$hex.toUShort()"
            ScalarKind.UInt -> "${hex}u"
            ScalarKind.Byte -> "$hex.toByte()"
            ScalarKind.Boolean, ScalarKind.Short, ScalarKind.Int, ScalarKind.Long, ScalarKind.ULong,
            ScalarKind.Float, ScalarKind.Double,
            ->
                error("singleton dispatch discriminator restricted to peekableDispatcherInnerKinds")
        }
    }

    /**
     * Single-field encode dispatch shared by
     * [buildEncodeFun], [buildFramedByEncodeFun]'s body lambda, and
     * [buildFramedByEncodeFun]'s `writeHeader` lambda. Centralizing the
     * `when` keeps the three call sites in lockstep when a new
     * [FieldSpec] member lands.
     */
    private fun appendEncodeField(
        body: CodeBlock.Builder,
        field: FieldSpec,
        shape: CodecShape,
    ) {
        when (field) {
            is FieldSpec.Scalar -> appendEncodeScalar(body, field, shape.ownerSimpleName)
            is FieldSpec.LengthPrefixedMessage -> appendEncodeLengthPrefixed(body, field)
            is FieldSpec.LengthPrefixedString -> appendEncodeLengthPrefixedString(body, field)
            is FieldSpec.LengthFromString -> appendEncodeLengthFromString(body, field)
            is FieldSpec.LengthFromList -> appendEncodeLengthFromList(body, field)
            is FieldSpec.LengthFromMessage -> appendEncodeLengthFromMessage(body, field)
            is FieldSpec.RemainingBytesProtocolMessageList ->
                appendEncodeRemainingBytesProtocolMessageList(body, field)
            is FieldSpec.CountPrefixedProtocolMessageList ->
                appendEncodeCountPrefixedProtocolMessageList(body, field)
            is FieldSpec.DeferredPayload -> appendEncodeDeferredPayload(body, field)
            is FieldSpec.RemainingBytesString -> appendEncodeRemainingBytesString(body, field)
            is FieldSpec.UseCodecScalar -> appendEncodeUseCodecScalar(body, field, shape)
            is FieldSpec.LengthPrefixedUseCodecList -> appendEncodeLengthPrefixedUseCodecList(body, field)
            is FieldSpec.LengthPrefixedUseCodecPayload ->
                appendEncodeLengthPrefixedUseCodecPayload(body, field)
            is FieldSpec.ValueClassScalar -> appendEncodeValueClassScalar(body, field)
            is FieldSpec.Conditional -> appendEncodeConditional(body, field)
            is FieldSpec.ProtocolMessageScalar -> appendEncodeProtocolMessageScalar(body, field)
            is FieldSpec.EnumScalar -> appendEncodeEnum(body, field)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Batching: coalesces adjacent natural-width scalar reads/writes into
    // one wider read/write plus shift+mask extraction. A measurable
    // hot-path win on header-rich protocols (MQTT, DNS, TCP/IP, TLS) that
    // v5.0.0 regressed when the v4 BatchOptimizer was dropped during the
    // strip-and-rebuild.
    //
    // Gate (case 1 + case 2): the entire candidate group must share one
    // resolved wire order — Default, all-Big, or all-Little. Mixed orders
    // break the batch. Boolean is never batched (it has no byte order; the
    // single-scalar path handles it cleanly).
    //
    // Generated emit shape depends on the group's shared order:
    //
    // - `Default` (follow buffer.byteOrder): the wire's per-field
    //   interpretation depends on buffer.byteOrder, so emit a single outer
    //   `if (buffer.byteOrder == BIG_ENDIAN) { ... } else { ... }` branch
    //   with two arms — BE arm extracts the first field from high bits,
    //   LE arm extracts from low bits. Both arms read into the same
    //   accumulator. This matches single-scalar `Default` semantics on
    //   both buffer orders (the pre-fix `Default` batching silently
    //   field-swapped on LITTLE_ENDIAN buffers — latent since v4, unfixed
    //   in v5's worktree port).
    //
    // - `Big`: canonicalize to a big-endian accumulator (no-op when
    //   buffer.byteOrder == BIG_ENDIAN, else `swapBytes(raw)`), then
    //   extract first field from high bits. Matches single-scalar `Big`,
    //   which produces big-endian wire bytes regardless of buffer order.
    //
    // - `Little`: canonicalize to a little-endian accumulator (no-op when
    //   buffer.byteOrder == LITTLE_ENDIAN, else `swapBytes(raw)`), then
    //   extract first field from low bits. Matches single-scalar `Little`.
    private data class BatchablePart(
        val name: String,
        val sizeBytes: Int,
        val kind: ScalarKind,
        val wireOrder: Endianness,
        // null for plain Scalar; populated for ValueClassScalar so
        // decode can wrap and encode can unwrap via the inner property.
        val valueClass: ClassName?,
        val innerPropertyName: String?,
    )

    private data class BatchGroup(
        val parts: List<BatchablePart>,
        val totalBytes: Int,
        val wireOrder: Endianness,
    )

    private sealed interface BatchItem {
        data class Batched(
            val group: BatchGroup,
        ) : BatchItem

        data class Single(
            val field: FieldSpec,
        ) : BatchItem
    }

    private fun batchablePartOrNull(field: FieldSpec): BatchablePart? =
        when (field) {
            is FieldSpec.Scalar ->
                if (field.wireBytes == field.kind.width && field.kind != ScalarKind.Boolean) {
                    BatchablePart(field.name, field.wireBytes, field.kind, field.resolvedWireOrder, null, null)
                } else {
                    null
                }
            is FieldSpec.ValueClassScalar ->
                if (field.wireBytes == field.innerKind.width && field.innerKind != ScalarKind.Boolean) {
                    BatchablePart(
                        name = field.name,
                        sizeBytes = field.wireBytes,
                        kind = field.innerKind,
                        wireOrder = field.valueClassWireOrder,
                        valueClass = field.valueClassType,
                        innerPropertyName = field.innerPropertyName,
                    )
                } else {
                    null
                }
            else -> null
        }

    private fun coalesceBatches(fields: List<FieldSpec>): List<BatchItem> {
        val result = mutableListOf<BatchItem>()
        val current = mutableListOf<Pair<FieldSpec, BatchablePart>>()
        var currentBytes = 0

        fun flush() {
            while (current.size >= 2) {
                var prefixSize = 0
                var bestCount = 0
                var bestSize = 0
                for (i in current.indices) {
                    prefixSize += current[i].second.sizeBytes
                    val count = i + 1
                    if (count >= 2 && prefixSize in BATCH_ALIGNMENTS) {
                        bestCount = count
                        bestSize = prefixSize
                    }
                    if (prefixSize >= MAX_BATCH_BYTES) break
                }
                if (bestCount >= 2) {
                    val groupParts = current.subList(0, bestCount).map { it.second }
                    result.add(
                        BatchItem.Batched(
                            BatchGroup(groupParts.toList(), bestSize, groupParts[0].wireOrder),
                        ),
                    )
                    val remaining = current.subList(bestCount, current.size).toMutableList()
                    current.clear()
                    current.addAll(remaining)
                    currentBytes = current.sumOf { it.second.sizeBytes }
                } else {
                    val removed = current.removeAt(0)
                    currentBytes -= removed.second.sizeBytes
                    result.add(BatchItem.Single(removed.first))
                }
            }
            for ((field, _) in current) result.add(BatchItem.Single(field))
            current.clear()
            currentBytes = 0
        }

        for (field in fields) {
            val part = batchablePartOrNull(field)
            if (part == null) {
                flush()
                result.add(BatchItem.Single(field))
                continue
            }
            val groupOrder = current.firstOrNull()?.second?.wireOrder
            val orderMismatch = groupOrder != null && groupOrder != part.wireOrder
            if (currentBytes + part.sizeBytes > MAX_BATCH_BYTES || orderMismatch) flush()
            current.add(field to part)
            currentBytes += part.sizeBytes
        }
        flush()
        return result
    }

    private fun appendDecodeFields(
        body: CodeBlock.Builder,
        fields: List<FieldSpec>,
    ) {
        for (item in coalesceBatches(fields)) {
            when (item) {
                is BatchItem.Single -> appendDecodeField(body, item.field)
                is BatchItem.Batched -> appendBatchedDecode(body, item.group)
            }
        }
    }

    private fun appendEncodeFields(
        body: CodeBlock.Builder,
        fields: List<FieldSpec>,
        shape: CodecShape,
    ) {
        for (item in coalesceBatches(fields)) {
            when (item) {
                is BatchItem.Single -> appendEncodeField(body, item.field, shape)
                is BatchItem.Batched -> appendBatchedEncode(body, item.group)
            }
        }
    }

    private fun batchReadInfo(totalBytes: Int): Triple<String, String, Int> =
        when (totalBytes) {
            SHORT_BATCH_BYTES -> Triple("readShort", "Int", SHORT_BATCH_BITS)
            INT_BATCH_BYTES -> Triple("readInt", "Int", INT_BATCH_BITS)
            LONG_BATCH_BYTES -> Triple("readLong", "Long", LONG_BATCH_BITS)
            else -> error("unsupported batch size $totalBytes")
        }

    /**
     * Decode-side emitter. Two emit shapes depending on the group's wire
     * order:
     *
     * - `Big` or `Little`: single canonicalizing val. Generated shape:
     *   `val __batchN = if (buffer.byteOrder == ByteOrder.<WIRE>) raw else swapBytes(raw)`,
     *   followed by per-field extraction in the fixed direction the wire
     *   order dictates (first field = high bits for Big, low for Little).
     *
     * - `Default`: single outer `if (buffer.byteOrder == ...) { ... } else { ... }`
     *   branch with two arms. Both arms share the same accumulator val; each
     *   declares the field locals up-front and assigns inside the arm. JIT
     *   sees one boolean check per group, not one per field.
     */
    private fun appendBatchedDecode(
        body: CodeBlock.Builder,
        group: BatchGroup,
    ) {
        val (readMethod, accumulatorType, accumulatorBits) = batchReadInfo(group.totalBytes)
        val accumulatorVar = "__batch${++batchCounter}"
        when (group.wireOrder) {
            Endianness.Big, Endianness.Little -> {
                val canonicalOrder =
                    if (group.wireOrder == Endianness.Big) "BIG_ENDIAN" else "LITTLE_ENDIAN"
                val rawVar = "${accumulatorVar}Raw"
                if (accumulatorType == "Int" && readMethod == "readShort") {
                    body.addStatement("val %L = buffer.%L().toInt() and 0xFFFF", rawVar, readMethod)
                    body.addStatement(
                        "val %L = if (buffer.byteOrder == %T.%L) %L else %M(%L.toShort()).toInt() and 0xFFFF",
                        accumulatorVar,
                        BYTE_ORDER_CN,
                        canonicalOrder,
                        rawVar,
                        SWAP_BYTES_MN,
                        rawVar,
                    )
                } else {
                    body.addStatement("val %L = buffer.%L()", rawVar, readMethod)
                    body.addStatement(
                        "val %L = if (buffer.byteOrder == %T.%L) %L else %M(%L)",
                        accumulatorVar,
                        BYTE_ORDER_CN,
                        canonicalOrder,
                        rawVar,
                        SWAP_BYTES_MN,
                        rawVar,
                    )
                }
                val firstFieldAtHigh = group.wireOrder == Endianness.Big
                appendBatchedDecodeExtractions(
                    body,
                    group,
                    accumulatorVar,
                    accumulatorType,
                    accumulatorBits,
                    firstFieldAtHigh,
                )
            }
            Endianness.Default -> {
                if (accumulatorType == "Int" && readMethod == "readShort") {
                    body.addStatement(
                        "val %L = buffer.%L().toInt() and 0xFFFF",
                        accumulatorVar,
                        readMethod,
                    )
                } else {
                    body.addStatement("val %L = buffer.%L()", accumulatorVar, readMethod)
                }
                for (part in group.parts) {
                    val typeName = batchPartTypeRender(part)
                    body.addStatement("val %L: %L", part.name, typeName)
                }
                body.beginControlFlow("if (buffer.byteOrder == %T.BIG_ENDIAN)", BYTE_ORDER_CN)
                appendBatchedDecodeAssignments(body, group, accumulatorVar, accumulatorType, accumulatorBits, true)
                body.nextControlFlow("else")
                appendBatchedDecodeAssignments(body, group, accumulatorVar, accumulatorType, accumulatorBits, false)
                body.endControlFlow()
            }
        }
    }

    private fun appendBatchedDecodeExtractions(
        body: CodeBlock.Builder,
        group: BatchGroup,
        accumulatorVar: String,
        accumulatorType: String,
        accumulatorBits: Int,
        firstFieldAtHigh: Boolean,
    ) {
        var highBitOffset = group.totalBytes * 8
        var lowBitOffset = 0
        for (part in group.parts) {
            val fieldBits = part.sizeBytes * 8
            val bitOffset: Int
            if (firstFieldAtHigh) {
                highBitOffset -= fieldBits
                bitOffset = highBitOffset
            } else {
                bitOffset = lowBitOffset
                lowBitOffset += fieldBits
            }
            val raw = batchExtractExpr(accumulatorVar, accumulatorType, accumulatorBits, bitOffset, fieldBits)
            val casted = castFromBatchAccumulator(part.kind, accumulatorType, raw)
            if (part.valueClass != null) {
                body.addStatement("val %L = %T(%L)", part.name, part.valueClass, casted)
            } else {
                body.addStatement("val %L = %L", part.name, casted)
            }
        }
    }

    private fun appendBatchedDecodeAssignments(
        body: CodeBlock.Builder,
        group: BatchGroup,
        accumulatorVar: String,
        accumulatorType: String,
        accumulatorBits: Int,
        firstFieldAtHigh: Boolean,
    ) {
        var highBitOffset = group.totalBytes * 8
        var lowBitOffset = 0
        for (part in group.parts) {
            val fieldBits = part.sizeBytes * 8
            val bitOffset: Int
            if (firstFieldAtHigh) {
                highBitOffset -= fieldBits
                bitOffset = highBitOffset
            } else {
                bitOffset = lowBitOffset
                lowBitOffset += fieldBits
            }
            val raw = batchExtractExpr(accumulatorVar, accumulatorType, accumulatorBits, bitOffset, fieldBits)
            val casted = castFromBatchAccumulator(part.kind, accumulatorType, raw)
            if (part.valueClass != null) {
                body.addStatement("%L = %T(%L)", part.name, part.valueClass, casted)
            } else {
                body.addStatement("%L = %L", part.name, casted)
            }
        }
    }

    private fun batchPartTypeRender(part: BatchablePart): String {
        if (part.valueClass != null) {
            val pkg = part.valueClass.packageName
            val nested = part.valueClass.simpleNames.joinToString(".")
            return if (pkg.isEmpty()) part.valueClass.simpleName else "$pkg.$nested"
        }
        return when (part.kind) {
            ScalarKind.Boolean -> "kotlin.Boolean"
            ScalarKind.UByte -> "kotlin.UByte"
            ScalarKind.Byte -> "kotlin.Byte"
            ScalarKind.UShort -> "kotlin.UShort"
            ScalarKind.Short -> "kotlin.Short"
            ScalarKind.UInt -> "kotlin.UInt"
            ScalarKind.Int -> "kotlin.Int"
            ScalarKind.ULong -> "kotlin.ULong"
            ScalarKind.Long -> "kotlin.Long"
            ScalarKind.Float -> "kotlin.Float"
            ScalarKind.Double -> "kotlin.Double"
        }
    }

    private fun batchExtractExpr(
        accumulatorVar: String,
        accumulatorType: String,
        accumulatorBits: Int,
        bitOffset: Int,
        fieldBits: Int,
    ): String {
        val mask =
            if (fieldBits >= accumulatorBits) {
                ""
            } else {
                " and " + hexMaskLiteral(fieldBits, accumulatorType)
            }
        val shift = if (bitOffset > 0) " ushr $bitOffset" else ""
        return "($accumulatorVar$shift$mask)"
    }

    private fun hexMaskLiteral(
        bits: Int,
        accumulatorType: String,
    ): String {
        val hexBytes = bits / 8
        val hex = "FF".repeat(hexBytes)
        return if (accumulatorType == "Long") "0x${hex}L" else "0x$hex"
    }

    private fun castFromBatchAccumulator(
        kind: ScalarKind,
        accumulatorType: String,
        rawExpr: String,
    ): String =
        when (kind) {
            ScalarKind.UByte -> "$rawExpr.toUByte()"
            ScalarKind.Byte -> "$rawExpr.toByte()"
            ScalarKind.UShort -> "$rawExpr.toUShort()"
            ScalarKind.Short -> "$rawExpr.toShort()"
            ScalarKind.UInt -> "$rawExpr.toUInt()"
            ScalarKind.Int -> if (accumulatorType == "Long") "$rawExpr.toInt()" else rawExpr
            ScalarKind.ULong -> "$rawExpr.toULong()"
            ScalarKind.Long -> rawExpr
            ScalarKind.Float -> {
                val bitsExpr = if (accumulatorType == "Long") "$rawExpr.toInt()" else rawExpr
                "Float.fromBits($bitsExpr)"
            }
            ScalarKind.Double -> "Double.fromBits($rawExpr)"
            ScalarKind.Boolean -> error("Boolean is not batchable")
        }

    /**
     * Encode-side emitter. Symmetric to [appendBatchedDecode]:
     *
     * - `Big` / `Little`: build the canonical (BE / LE) accumulator from
     *   field accessors, then write with conditional `swapBytes` when the
     *   buffer's runtime order differs.
     *
     * - `Default`: branch the entire assembly+write on `buffer.byteOrder`
     *   so each arm orders bits per the buffer's natural single-scalar
     *   interpretation.
     */
    private fun appendBatchedEncode(
        body: CodeBlock.Builder,
        group: BatchGroup,
    ) {
        val (_, accumulatorType, _) = batchReadInfo(group.totalBytes)
        val writeMethod =
            when (group.totalBytes) {
                2 -> "writeShort"
                4 -> "writeInt"
                8 -> "writeLong"
                else -> error("unsupported batch size ${group.totalBytes}")
            }
        when (group.wireOrder) {
            Endianness.Big, Endianness.Little -> {
                val combined = batchEncodeCombineExpr(group, accumulatorType, group.wireOrder == Endianness.Big)
                val converted = convertBatchAccumulatorForWrite(combined, group.totalBytes)
                val canonicalOrder =
                    if (group.wireOrder == Endianness.Big) "BIG_ENDIAN" else "LITTLE_ENDIAN"
                val combinedVar = "__batch${++batchCounter}"
                body.addStatement("val %L = %L", combinedVar, converted)
                body.addStatement(
                    "buffer.%L(if (buffer.byteOrder == %T.%L) %L else %M(%L))",
                    writeMethod,
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    combinedVar,
                    SWAP_BYTES_MN,
                    combinedVar,
                )
            }
            Endianness.Default -> {
                body.beginControlFlow("if (buffer.byteOrder == %T.BIG_ENDIAN)", BYTE_ORDER_CN)
                val combinedBe = batchEncodeCombineExpr(group, accumulatorType, true)
                val convertedBe = convertBatchAccumulatorForWrite(combinedBe, group.totalBytes)
                body.addStatement("buffer.%L(%L)", writeMethod, convertedBe)
                body.nextControlFlow("else")
                val combinedLe = batchEncodeCombineExpr(group, accumulatorType, false)
                val convertedLe = convertBatchAccumulatorForWrite(combinedLe, group.totalBytes)
                body.addStatement("buffer.%L(%L)", writeMethod, convertedLe)
                body.endControlFlow()
            }
        }
    }

    private fun batchEncodeCombineExpr(
        group: BatchGroup,
        accumulatorType: String,
        firstFieldAtHigh: Boolean,
    ): String {
        var highBitOffset = group.totalBytes * 8
        var lowBitOffset = 0
        val accumulatorBits = if (accumulatorType == "Long") 64 else 32
        val terms = mutableListOf<String>()
        for (part in group.parts) {
            val fieldBits = part.sizeBytes * 8
            val bitOffset: Int
            if (firstFieldAtHigh) {
                highBitOffset -= fieldBits
                bitOffset = highBitOffset
            } else {
                bitOffset = lowBitOffset
                lowBitOffset += fieldBits
            }
            val accessor =
                if (part.valueClass != null) {
                    "value.${part.name}.${part.innerPropertyName}"
                } else {
                    "value.${part.name}"
                }
            val asAccumulator = encodeToBatchAccumulator(part.kind, accumulatorType, accessor)
            val masked =
                if (fieldBits >= accumulatorBits) {
                    asAccumulator
                } else {
                    "($asAccumulator and ${hexMaskLiteral(fieldBits, accumulatorType)})"
                }
            terms.add(if (bitOffset > 0) "($masked shl $bitOffset)" else masked)
        }
        return terms.joinToString(" or ")
    }

    private fun convertBatchAccumulatorForWrite(
        combined: String,
        totalBytes: Int,
    ): String =
        when (totalBytes) {
            // size-2 batches use an Int accumulator (so shift/or arithmetic
            // stays in Int) but writeShort takes Short — narrow at the call.
            SHORT_BATCH_BYTES -> "($combined).toShort()"
            // size-4 / size-8 accumulators are already Int / Long, so emit
            // bare expressions. Wrapping in .toInt()/.toLong() triggers the
            // "Redundant call of conversion method" compiler warning.
            INT_BATCH_BYTES -> combined
            LONG_BATCH_BYTES -> combined
            else -> error("unsupported batch size $totalBytes")
        }

    private fun encodeToBatchAccumulator(
        kind: ScalarKind,
        accumulatorType: String,
        accessor: String,
    ): String {
        val intLike = accumulatorType == "Int"
        return when (kind) {
            ScalarKind.UByte -> if (intLike) "$accessor.toInt()" else "$accessor.toLong()"
            ScalarKind.Byte -> if (intLike) "$accessor.toInt()" else "$accessor.toLong()"
            ScalarKind.UShort -> if (intLike) "$accessor.toInt()" else "$accessor.toLong()"
            ScalarKind.Short -> if (intLike) "$accessor.toInt()" else "$accessor.toLong()"
            ScalarKind.UInt -> if (intLike) "$accessor.toInt()" else "$accessor.toLong()"
            ScalarKind.Int -> if (intLike) accessor else "$accessor.toLong()"
            ScalarKind.ULong -> "$accessor.toLong()"
            ScalarKind.Long -> accessor
            ScalarKind.Float -> if (intLike) "$accessor.toRawBits()" else "$accessor.toRawBits().toLong()"
            ScalarKind.Double -> "$accessor.toRawBits()"
            ScalarKind.Boolean -> error("Boolean is not batchable")
        }
    }

    private var batchCounter = 0

    /**
     * Gate for emitting the `Partial` decode pattern. Partial is emitted
     * only when [FieldSpec.DeferredPayload] is the *last* field of
     * the shape. The Partial flow is the streaming-style "decode the
     * header now, defer the payload" contract — meaningful only when the
     * payload is genuinely trailing (e.g. `MqttPacket.Publish<P>`, v3/v5
     * SUBSCRIBE/UNSUBSCRIBE bodies).
     *
     * When a shape also carries a bounding `@UseCodec(BoundingLengthCodec)`
     * field (the MQTT v3 PUBLISH §3.3 wire shape), the `Partial` captures
     * the outer buffer limit at partial-decode time (the same local that
     * `appendDecodeUseCodecScalar` emits as `__<fieldName>OuterLimit`);
     * `complete()` runs the payload decode inside the bounding-narrowed
     * limit (correct for payload bounding) and restores the outer limit
     * via `try/finally` (correct for caller cleanup). Both correctness
     * concerns are handled by capturing the outer limit on the Partial
     * and restoring on completion — no consumer-visible API change versus
     * the unbounded path.
     *
     * Non-terminal payload (issue #168): when [FieldSpec.DeferredPayload]
     * is followed only by fixed-size trailers (e.g. a `checksum: UShort` after
     * `@RemainingBytes val payload: P`), Partial still applies — `partial(...)`
     * reads the header *and* the trailer eagerly (the payload's wire extent is
     * `limit - reservedTrailingBytes`, computable without decoding it), and
     * `complete(...)` re-seeks to the payload region and decodes it. This is
     * gated to the unframed / unbounded case: combining a non-terminal payload
     * with `@FramedBy` or a bounding `@UseCodec` stays terminal-only (the
     * payload-region math would have to compose with the external bound, out of
     * scope here), so those shapes fall back to normal decode/encode.
     */
    private fun shouldEmitPartial(shape: CodecShape): Boolean {
        val payloadIndex = shape.fields.indexOfFirst { it is FieldSpec.DeferredPayload }
        if (payloadIndex < 0) return false
        // A sibling-sized payload (#293) does not get Partial yet. Nothing about
        // the shape forbids it — `complete()` would recompute the bound from the
        // length carrier, which `partial()` has already decoded and exposed as a
        // header `val`, needing no new Partial state. What is unresolved is how
        // that recomputed bound composes with the *other* bounds Partial already
        // juggles (`@FramedBy`, a bounding `@UseCodec` field, the eager trailer
        // seek), and getting that wrong desynchronises a stream rather than
        // failing loudly. Deliberately deferred rather than guessed; these shapes
        // fall back to normal decode/encode, which is fully supported. Framing —
        // the actual ask in #293 — comes from peekFrameSize and is unaffected.
        if ((shape.fields[payloadIndex] as FieldSpec.DeferredPayload).extent is PayloadExtent.Sibling) {
            return false
        }
        val trailing = shape.fields.subList(payloadIndex + 1, shape.fields.size)
        // Terminal payload — the original streaming-defer shape.
        if (trailing.isEmpty()) return true
        // Non-terminal payload: only when the trailer is fixed-size (so the
        // payload's end is computable without decoding) and the payload isn't
        // also externally bounded.
        if (!trailing.all { it is FieldSpec.FixedSize }) return false
        if (shape.framedBy != null) return false
        if (shape.fields.any { it.isBoundingShape() }) return false
        return true
    }

    /**
     * Emit the nested `Partial` class. The `Partial`
     * captures the buffer and context so `complete(...)` can defer the
     * payload decode, and exposes the header fields as `val`s for
     * pre-payload inspection (the topic-keyed dispatch case from
     * `:buffer-flow` acceptance #4).
     *
     * The constructor is `internal` because consumers always reach
     * `Partial` through the `partial(...)` entry function — there's no
     * legitimate reason for foreign-module construction. Generated
     * codecs and consumer code live in the same module, so `internal`
     * keeps the API surface tight without restricting reachability for
     * the codec's own emit.
     *
     * Type-parameter shape:
     * (concrete payload): no type parameter on `Partial`.
     *     `complete(): MessageType` uses the `@UseCodec`-pinned codec.
     * (generic payload `<P: Payload>`): `Partial<P:
     *     Payload>` carries its own type variable (independent of the
     *     surrounding generic codec class — the whole point of slice
     *     10b's Partial is that the payload codec is supplied at
     *     `complete(...)` time, not at codec instantiation).
     *     `complete(payloadCodec: Decoder<P>): MessageType<P>` takes
     *     the codec as a parameter; `Decoder<P>` (not a separate
     *     `PayloadDecoder<P>` SAM) — the contract is identical and a
     *     parallel SAM would be noise.
     */
    private fun buildPartialClassTypeSpec(
        shape: CodecShape,
        payloadTypeParameter: PayloadTypeParameter?,
    ): TypeSpec {
        val payloadIndex = shape.payloadFieldIndex()
        val payloadField =
            (shape.fields.getOrNull(payloadIndex) as? FieldSpec.DeferredPayload)
                ?: error("buildPartialClassTypeSpec called for shape without a DeferredPayload")
        // Fields before the payload (decoded eagerly in `partial`) and the
        // fixed-size trailer after it (also decoded eagerly — non-terminal
        // payload, issue #168). For the terminal shape `afterFields` is empty
        // and the behavior is unchanged.
        val beforeFields = shape.fields.subList(0, payloadIndex)
        val afterFields = shape.fields.subList(payloadIndex + 1, shape.fields.size)
        val nonTerminal = afterFields.isNotEmpty()
        val exposedFields = beforeFields + afterFields
        // When the headers contain a bounding `@UseCodec` field (codec
        // implements `BoundingLengthCodec`), the partial decode mid-walk
        // narrows `buffer.limit()` and stashes the prior limit in
        // `__<fieldName>OuterLimit`. Capture that local on the Partial so
        // `complete()` can restore it. `@FramedBy` inherited from the
        // sealed parent supplies the bound externally (no in-shape field),
        // so we treat it as effectively-bounding for Partial purposes.
        // (Non-terminal shapes are gated out of both by `shouldEmitPartial`.)
        val hasBoundingField = shape.fields.any { it.isBoundingShape() } || shape.framedBy != null

        val classBuilder = TypeSpec.classBuilder("Partial")
        val typeVar =
            payloadTypeParameter?.let {
                TypeVariableName(it.typeVariableName, it.bound)
            }
        if (typeVar != null) classBuilder.addTypeVariable(typeVar)

        val ctorBuilder = FunSpec.constructorBuilder().addModifiers(KModifier.INTERNAL)
        for (field in exposedFields) {
            val typeName = partialFieldTypeName(field)
            ctorBuilder.addParameter(field.name, typeName)
            classBuilder.addProperty(
                com.squareup.kotlinpoet.PropertySpec
                    .builder(field.name, typeName)
                    .initializer(field.name)
                    .build(),
            )
        }
        if (hasBoundingField) {
            ctorBuilder.addParameter("outerLimit", INT)
        }
        // Non-terminal payload: capture the payload's wire region so
        // `complete()` can re-seek to it (the trailer was already consumed
        // in `partial`, so the buffer position no longer sits at the payload).
        if (nonTerminal) {
            ctorBuilder.addParameter("payloadStart", INT)
            ctorBuilder.addParameter("payloadEnd", INT)
        }
        ctorBuilder.addParameter("buffer", READ_BUFFER_CN)
        ctorBuilder.addParameter("context", DECODE_CONTEXT_CN)
        classBuilder.primaryConstructor(ctorBuilder.build())
        // Buffer + context (and the captured outerLimit, if any) are
        // private state used by complete(); no public getter — the
        // consumer should never re-read the buffer or fiddle with the
        // limit through the Partial.
        if (hasBoundingField) {
            classBuilder.addProperty(
                com.squareup.kotlinpoet.PropertySpec
                    .builder("outerLimit", INT, KModifier.PRIVATE)
                    .initializer("outerLimit")
                    .build(),
            )
        }
        if (nonTerminal) {
            classBuilder.addProperty(
                com.squareup.kotlinpoet.PropertySpec
                    .builder("payloadStart", INT, KModifier.PRIVATE)
                    .initializer("payloadStart")
                    .build(),
            )
            classBuilder.addProperty(
                com.squareup.kotlinpoet.PropertySpec
                    .builder("payloadEnd", INT, KModifier.PRIVATE)
                    .initializer("payloadEnd")
                    .build(),
            )
        }
        classBuilder.addProperty(
            com.squareup.kotlinpoet.PropertySpec
                .builder("buffer", READ_BUFFER_CN, KModifier.PRIVATE)
                .initializer("buffer")
                .build(),
        )
        classBuilder.addProperty(
            com.squareup.kotlinpoet.PropertySpec
                .builder("context", DECODE_CONTEXT_CN, KModifier.PRIVATE)
                .initializer("context")
                .build(),
        )

        classBuilder.addFunction(
            buildPartialCompleteFun(shape, payloadTypeParameter, payloadField, hasBoundingField, nonTerminal),
        )
        return classBuilder.build()
    }

    /**
     * Emit the `Partial.complete(...)` function.
     *
     * Path: the codec is `@UseCodec`-pinned, so `complete`
     * takes no parameters and calls `<UserCodec>.decode(buffer, context)`
     * directly. path: the codec is supplied at the call site,
     * so `complete(payloadCodec: Decoder<P>)` accepts the decoder and
     * calls `payloadCodec.decode(buffer, context)`. Both branches share
     * the trailing constructor call.
     */
    private fun buildPartialCompleteFun(
        shape: CodecShape,
        payloadTypeParameter: PayloadTypeParameter?,
        payloadField: FieldSpec.DeferredPayload,
        hasBoundingField: Boolean,
        nonTerminal: Boolean = false,
    ): FunSpec {
        val funBuilder = FunSpec.builder("complete")
        val returnType =
            if (payloadTypeParameter != null) {
                shape.messageClassName.parameterizedBy(
                    TypeVariableName(payloadTypeParameter.typeVariableName),
                )
            } else {
                shape.messageClassName
            }
        funBuilder.returns(returnType)

        // Resolve the payload-decode statement up front; both the
        // RL and no-RL paths emit the same statement, just inside or
        // outside the try-block.
        val payloadDecodeStmt =
            when (val source = payloadField.source) {
                is PayloadCodecSource.UserCodecObject ->
                    CodeBlock.of(
                        "val %L = %T.decode(buffer, context)\n",
                        payloadField.name,
                        source.codecType,
                    )
                is PayloadCodecSource.ConstructorInjected -> {
                    val pTpName =
                        payloadTypeParameter
                            ?: error("ConstructorInjected payload source requires a payload type parameter")
                    funBuilder.addParameter(
                        source.parameterName,
                        DECODER_CN.parameterizedBy(TypeVariableName(pTpName.typeVariableName)),
                    )
                    CodeBlock.of(
                        "val %L = %L.decode(buffer, context)\n",
                        payloadField.name,
                        source.parameterName,
                    )
                }
            }
        val ctorArgs = shape.fields.joinToString(", ") { "${it.name} = ${it.name}" }
        val body = CodeBlock.builder()
        if (nonTerminal) {
            // The trailer was consumed eagerly in `partial`, so the buffer
            // position no longer sits at the payload. Re-seek to the captured
            // payload region, narrow the limit to it, decode, then restore the
            // limit. `payloadStart`/`payloadEnd` were captured at partial time.
            body.addStatement("buffer.position(payloadStart)")
            body.addStatement("val __payloadSavedLimit = buffer.limit()")
            body.addStatement("buffer.setLimit(payloadEnd)")
            body.beginControlFlow("return try")
            body.add(payloadDecodeStmt)
            body.addStatement("%T(%L)", returnType, ctorArgs)
            body.nextControlFlow("finally")
            body.addStatement("buffer.setLimit(__payloadSavedLimit)")
            body.endControlFlow()
        } else if (hasBoundingField) {
            // Payload decode runs inside the bounding-field-narrowed
            // limit (correct for payload bounding); the outer limit is
            // restored via try/finally so the caller's outer limit
            // survives even if the user codec throws.
            body.beginControlFlow("return try")
            body.add(payloadDecodeStmt)
            body.addStatement("%T(%L)", returnType, ctorArgs)
            body.nextControlFlow("finally")
            body.addStatement("buffer.setLimit(outerLimit)")
            body.endControlFlow()
        } else {
            body.add(payloadDecodeStmt)
            body.addStatement("return %T(%L)", returnType, ctorArgs)
        }
        funBuilder.addCode(body.build())
        return funBuilder.build()
    }

    /**
     * Emit the `partial(buffer, context)` decode
     * entry. places this as a member function on the codec
     * `object`; places it on the codec class's companion
     * object (with a fresh `<P : Payload>` type parameter so the call
     * site can choose the payload type without instantiating the
     * surrounding generic codec class).
     *
     * The body decodes every header field (everything before the
     * trailing `DeferredPayload`), then constructs the nested
     * `Partial` capturing the buffer + context. The header decode
     * statements are the same `appendDecodeField` emit used by the
     * full `decode(...)` — Partial differs only in stopping before
     * the payload field and packaging the locals into `Partial`
     * instead of the full message constructor.
     */
    private fun buildPartialEntryFun(
        shape: CodecShape,
        payloadTypeParameter: PayloadTypeParameter?,
    ): FunSpec {
        val payloadIndex = shape.payloadFieldIndex()
        val payloadField = shape.fields[payloadIndex] as FieldSpec.DeferredPayload
        val beforeFields = shape.fields.subList(0, payloadIndex)
        val afterFields = shape.fields.subList(payloadIndex + 1, shape.fields.size)
        val nonTerminal = afterFields.isNotEmpty()
        val partialClassName = ClassName(shape.packageName, shape.codecSimpleName, "Partial")
        val funBuilder = FunSpec.builder("partial")
        val returnType =
            if (payloadTypeParameter != null) {
                funBuilder.addTypeVariable(
                    TypeVariableName(payloadTypeParameter.typeVariableName, payloadTypeParameter.bound),
                )
                partialClassName.parameterizedBy(
                    TypeVariableName(payloadTypeParameter.typeVariableName),
                )
            } else {
                partialClassName
            }
        funBuilder
            .addParameter("buffer", READ_BUFFER_CN)
            .addParameter("context", DECODE_CONTEXT_CN)
            .returns(returnType)

        val body = CodeBlock.builder()
        if (nonTerminal) {
            // Non-terminal payload (issue #168): read the leading fields, then
            // skip the payload region (its end is `limit - reservedTrailingBytes`,
            // computable without decoding) and read the fixed-size trailer
            // eagerly. `complete()` re-seeks to [payloadStart, payloadEnd) to
            // decode the deferred payload. Gated to the unframed/unbounded case
            // by `shouldEmitPartial`, so no framing/bounding handling here.
            appendDecodeFields(body, beforeFields)
            body.addStatement("val __payloadStart = buffer.position()")
            // A non-terminal payload here is a to-limit extent: the reservation is
            // what makes the end computable without decoding. Sibling extents are
            // turned away by `shouldEmitPartial` (see the note there), so this is
            // the only arm the Partial emit can reach.
            val reservedTrailingBytes =
                when (val extent = payloadField.extent) {
                    is PayloadExtent.ToLimit -> extent.reservedTrailingBytes
                    is PayloadExtent.Sibling ->
                        error(
                            "Partial decode reached a sibling-sized payload — shouldEmitPartial " +
                                "gates these out, so the Partial class should never have been emitted.",
                        )
                }
            body.addStatement("val __payloadEnd = buffer.limit() - %L", reservedTrailingBytes)
            body.addStatement("buffer.position(__payloadEnd)")
            appendDecodeFields(body, afterFields)
            val ctorArgs =
                (
                    beforeFields.map { "${it.name} = ${it.name}" } +
                        afterFields.map { "${it.name} = ${it.name}" } +
                        listOf(
                            "payloadStart = __payloadStart",
                            "payloadEnd = __payloadEnd",
                            "buffer = buffer",
                            "context = context",
                        )
                ).joinToString(", ")
            body.addStatement("return %T(%L)", returnType, ctorArgs)
            funBuilder.addCode(body.build())
            return funBuilder.build()
        }
        // Terminal payload. When the parent supplies framing via
        // `@FramedBy`, the variant's partial decode reads the after-field
        // first, then applies the framing bound (capturing the outer
        // limit), then walks the remaining header fields inside the
        // narrowed bound. Mirrors [buildFramedByDecodeFun]'s field order
        // so wire bytes line up.
        val framedBy = shape.framedBy
        val afterField = framedBy?.let { framedByAfterField(shape, it) }
        if (framedBy != null) {
            if (afterField != null) appendDecodeField(body, afterField)
            body.addStatement("val __framingOuterLimit = buffer.limit()")
            body.addStatement(
                "val __framingLength = %T.decode(buffer, context)",
                framedBy.codecClassName,
            )
            appendFramedBodyTruncationGuard(body, "__framingLength", "${shape.ownerSimpleName}.@FramedBy")
            body.addStatement(
                "%T.applyBound(buffer, __framingLength)",
                framedBy.codecClassName,
            )
            appendDecodeFields(body, beforeFields.filter { it !== afterField })
        } else {
            appendDecodeFields(body, beforeFields)
        }
        val boundingField = shape.fields.firstOrNull { it.isBoundingShape() }
        val outerLimitLocal =
            boundingField?.let { "__${it.name}OuterLimit" }
                ?: framedBy?.let { "__framingOuterLimit" }
        val outerLimitArgs =
            outerLimitLocal?.let {
                // appendDecodeUseCodecScalar emits
                // the outer-limit local as `__<fieldName>OuterLimit`;
                // the framed branch emits `__framingOuterLimit`.
                // Either way, hand it to the Partial so `complete()` can
                // restore the outer buffer limit on finally.
                listOf("outerLimit = $it")
            } ?: emptyList()
        val ctorArgs =
            (
                beforeFields.map { "${it.name} = ${it.name}" } +
                    outerLimitArgs +
                    listOf("buffer = buffer", "context = context")
            ).joinToString(", ")
        body.addStatement("return %T(%L)", returnType, ctorArgs)
        funBuilder.addCode(body.build())
        return funBuilder.build()
    }

    /**
     * Companion-object wrapper for the
     * `partial<P>(...)` entry. Companion-side placement is required:
     * a member-side `partial(...)` would force the consumer to first
     * construct `MqttPublishV3Codec(somePayloadCodec)` just to call
     * `partial`, defeating the purpose of deferring the
     * codec choice past the header decode.
     */
    private fun buildPartialCompanionObject(
        shape: CodecShape,
        payloadTypeParameter: PayloadTypeParameter,
    ): TypeSpec =
        TypeSpec
            .companionObjectBuilder()
            .addFunction(buildPartialEntryFun(shape, payloadTypeParameter))
            .build()

    /**
     * Derive the property `TypeName` for a header
     * field on the `Partial` class. The `Partial` mirrors the data
     * class's header fields with their original Kotlin types, so this
     * map is a closed mirror of `FieldSpec`'s shape-to-type mapping.
     * The trailing `DeferredPayload` field is never asked for
     * (it's stripped by `headerFields = shape.fields.dropLast(1)` at
     * every call site).
     */
    private fun partialFieldTypeName(field: FieldSpec): TypeName =
        when (field) {
            is FieldSpec.Scalar -> scalarTypeName(field.kind)
            is FieldSpec.LengthPrefixedString ->
                field.valueClass?.valueClassType ?: ClassName("kotlin", "String")
            is FieldSpec.LengthPrefixedMessage -> field.messageType
            is FieldSpec.LengthFromString ->
                field.valueClass?.valueClassType ?: ClassName("kotlin", "String")
            is FieldSpec.LengthFromMessage -> field.messageType
            is FieldSpec.LengthFromList ->
                ClassName("kotlin.collections", "List").parameterizedBy(field.elementClassName)
            is FieldSpec.CountPrefixedProtocolMessageList ->
                // Self-delimiting, so a `@Count` list may sit as a non-terminal
                // header field ahead of a trailing DeferredPayload; map it
                // to its `List<Element>` Kotlin type (mirror of LengthFromList).
                ClassName("kotlin.collections", "List").parameterizedBy(field.elementClassName)
            is FieldSpec.RemainingBytesProtocolMessageList ->
                error(
                    "partialFieldTypeName called on a RemainingBytesProtocolMessageList field — " +
                        "this shape is terminal-only and the Partial decode pattern only fires " +
                        "for shapes with a trailing DeferredPayload. The two shapes are " +
                        "mutually exclusive at the terminal slot, so this branch is unreachable.",
                )
            is FieldSpec.DeferredPayload ->
                error(
                    "partialFieldTypeName called on a DeferredPayload field — caller " +
                        "should strip the payload field before mapping header types.",
                )
            is FieldSpec.RemainingBytesString ->
                error(
                    "partialFieldTypeName called on a RemainingBytesString field — this shape " +
                        "is terminal-only and the Partial decode pattern only fires for shapes " +
                        "with a trailing DeferredPayload. The two are mutually exclusive " +
                        "at the terminal slot, so this branch is unreachable.",
                )
            is FieldSpec.UseCodecScalar -> field.fieldType
            is FieldSpec.LengthPrefixedUseCodecList ->
                ClassName("kotlin.collections", "List").parameterizedBy(field.elementClassName)
            is FieldSpec.LengthPrefixedUseCodecPayload -> field.payloadType
            is FieldSpec.ValueClassScalar -> field.valueClassType
            is FieldSpec.Conditional -> field.nullableTypeName
            is FieldSpec.ProtocolMessageScalar -> field.fieldType
            is FieldSpec.EnumScalar -> field.enumType
        }
}

/** Index of the (single) `DeferredPayload` field, or -1. */
private fun CodecShape.payloadFieldIndex(): Int = fields.indexOfFirst { it is FieldSpec.DeferredPayload }

/**
 * Emits the framed-body truncation guard between the framing codec's `decode`
 * and the body decode: the declared body must be fully buffered. Without it a
 * truncated frame fails with platform-dependent buffer errors — or worse, on
 * platforms whose buffers clamp limits past capacity (JS), reads silently
 * fabricate zero bytes for the missing region (and the @ForwardCompatible
 * preserve path would even *allocate* the attacker-declared length). Stream
 * readers that gate on `peekFrameSize` never hit this; it protects direct
 * `decode` callers.
 */
internal fun appendFramedBodyTruncationGuard(
    body: CodeBlock.Builder,
    lengthLocal: String,
    fieldPath: String,
) {
    body.beginControlFlow("if (%L.toInt() > buffer.remaining())", lengthLocal)
    body.addStatement(
        "throw %T(\n  fieldPath = %S,\n  bufferPosition = buffer.position(),\n" +
            "  expected = \"a fully-buffered \" + %L + \"-byte framed body\",\n" +
            "  actual = buffer.remaining().toString() + \" bytes available\",\n)",
        DECODE_EXCEPTION_CN,
        fieldPath,
        lengthLocal,
    )
    body.endControlFlow()
}
