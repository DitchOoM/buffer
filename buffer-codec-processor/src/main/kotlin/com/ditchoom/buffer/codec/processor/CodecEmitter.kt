package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
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
            // codec (the Outcome-3 bug class). These cover the §2.5/§2.6
            // silent gaps the validator does not already catch.
            is AnalysisResult.Rejected -> r.diagnostics.forEach { logger.error(it.message, it.node) }
            // Not a codec target (handled elsewhere or already rejected by
            // the validator) — stay silent to avoid double-reporting.
            AnalysisResult.NotApplicable -> return
        }
    }

    /**
     * Write statement for a natural-width scalar given an
     * accessor expression. Boolean encodes as `0x00` / `0x01`.
     */
    private fun naturalScalarWriteStatement(
        kind: ScalarKind,
        accessor: String,
    ): String =
        when (kind) {
            ScalarKind.Boolean -> "buffer.writeByte(if ($accessor) 1.toByte() else 0.toByte())"
            ScalarKind.UByte -> "buffer.writeUByte($accessor)"
            ScalarKind.UShort -> "buffer.writeUShort($accessor)"
            ScalarKind.UInt -> "buffer.writeUInt($accessor)"
            ScalarKind.ULong -> "buffer.writeULong($accessor)"
            ScalarKind.Byte -> "buffer.writeByte($accessor)"
            ScalarKind.Short -> "buffer.writeShort($accessor)"
            ScalarKind.Int -> "buffer.writeInt($accessor)"
            ScalarKind.Long -> "buffer.writeLong($accessor)"
            ScalarKind.Float -> "buffer.writeFloat($accessor)"
            ScalarKind.Double -> "buffer.writeDouble($accessor)"
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
        val headerWireWidth =
            (afterField?.let(::framedByHeaderWireWidth) ?: WireWidth.Zero)
                .requireFixed("framedByHeaderWireWidth")
        val body = CodeBlock.builder()
        body.add("return %T.encode(\n", FRAMED_ENCODER_CN)
        body.indent()
        body.add("factory = factory,\n")
        body.add("framingCodec = %T,\n", framedBy.codecClassName)
        body.add("context = context,\n")
        if (afterField != null) {
            body.add("headerWireWidth = %L,\n", headerWireWidth)
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
        val headerWireWidth =
            (afterField?.let(::framedByHeaderWireWidth) ?: WireWidth.Zero)
                .requireFixed("framedByHeaderWireWidth")
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
     * Resolve the `@FramedByafter`-named
     * field to its [FieldSpec], or `null` when the name doesn't match
     * an analyzed field OR the field shape cannot carry an Exact wire
     * width (only Scalar / ValueClassScalar are accepted; this mirrors
     * the validator's E3 acceptance set).
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
     * parameter and a corresponding `RemainingBytesPayload` field with
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
            is FieldSpec.RemainingBytesPayload -> appendDecodeRemainingBytesPayload(body, field)
            is FieldSpec.RemainingBytesString -> appendDecodeRemainingBytesString(body, field)
            is FieldSpec.UseCodecScalar -> appendDecodeUseCodecScalar(body, field)
            is FieldSpec.LengthPrefixedUseCodecList -> appendDecodeLengthPrefixedUseCodecList(body, field)
            is FieldSpec.LengthPrefixedUseCodecPayload ->
                appendDecodeLengthPrefixedUseCodecPayload(body, field)
            is FieldSpec.ValueClassScalar -> appendDecodeValueClassScalar(body, field)
            is FieldSpec.Conditional -> appendDecodeConditional(body, field)
            is FieldSpec.ProtocolMessageScalar -> appendDecodeProtocolMessageScalar(body, field)
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
            is FieldSpec.RemainingBytesPayload -> appendEncodeRemainingBytesPayload(body, field)
            is FieldSpec.RemainingBytesString -> appendEncodeRemainingBytesString(body, field)
            is FieldSpec.UseCodecScalar -> appendEncodeUseCodecScalar(body, field, shape)
            is FieldSpec.LengthPrefixedUseCodecList -> appendEncodeLengthPrefixedUseCodecList(body, field)
            is FieldSpec.LengthPrefixedUseCodecPayload ->
                appendEncodeLengthPrefixedUseCodecPayload(body, field)
            is FieldSpec.ValueClassScalar -> appendEncodeValueClassScalar(body, field)
            is FieldSpec.Conditional -> appendEncodeConditional(body, field)
            is FieldSpec.ProtocolMessageScalar -> appendEncodeProtocolMessageScalar(body, field)
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
                    if (prefixSize >= 8) break
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
            if (currentBytes + part.sizeBytes > 8 || orderMismatch) flush()
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
            2 -> Triple("readShort", "Int", 16)
            4 -> Triple("readInt", "Int", 32)
            8 -> Triple("readLong", "Long", 64)
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
            return if (pkg.isEmpty()) part.valueClass.simpleName else "$pkg.${part.valueClass.simpleNames.joinToString(".")}"
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
            ScalarKind.Float -> if (accumulatorType == "Long") "Float.fromBits($rawExpr.toInt())" else "Float.fromBits($rawExpr)"
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
            2 -> "($combined).toShort()"
            // size-4 / size-8 accumulators are already Int / Long, so emit
            // bare expressions. Wrapping in .toInt()/.toLong() triggers the
            // "Redundant call of conversion method" compiler warning.
            4 -> combined
            8 -> combined
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
     * only when [FieldSpec.RemainingBytesPayload] is the *last* field of
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
     * Non-terminal payload (issue #168): when [FieldSpec.RemainingBytesPayload]
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
        val payloadIndex = shape.fields.indexOfFirst { it is FieldSpec.RemainingBytesPayload }
        if (payloadIndex < 0) return false
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

    /** Index of the (single) `RemainingBytesPayload` field, or -1. */
    private fun payloadFieldIndex(shape: CodecShape): Int = shape.fields.indexOfFirst { it is FieldSpec.RemainingBytesPayload }

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
        val payloadIndex = payloadFieldIndex(shape)
        val payloadField =
            (shape.fields.getOrNull(payloadIndex) as? FieldSpec.RemainingBytesPayload)
                ?: error("buildPartialClassTypeSpec called for shape without a RemainingBytesPayload")
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
        payloadField: FieldSpec.RemainingBytesPayload,
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
     * trailing `RemainingBytesPayload`), then constructs the nested
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
        val payloadIndex = payloadFieldIndex(shape)
        val payloadField = shape.fields[payloadIndex] as FieldSpec.RemainingBytesPayload
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
            body.addStatement("val __payloadEnd = buffer.limit() - %L", payloadField.reservedTrailingBytes)
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
     * The trailing `RemainingBytesPayload` field is never asked for
     * (it's stripped by `headerFields = shape.fields.dropLast(1)` at
     * every call site).
     */
    private fun partialFieldTypeName(field: FieldSpec): TypeName =
        when (field) {
            is FieldSpec.Scalar -> scalarTypeName(field.kind)
            is FieldSpec.LengthPrefixedString -> ClassName("kotlin", "String")
            is FieldSpec.LengthPrefixedMessage -> field.messageType
            is FieldSpec.LengthFromString -> ClassName("kotlin", "String")
            is FieldSpec.LengthFromMessage -> field.messageType
            is FieldSpec.LengthFromList ->
                ClassName("kotlin.collections", "List").parameterizedBy(field.elementClassName)
            is FieldSpec.RemainingBytesProtocolMessageList ->
                error(
                    "partialFieldTypeName called on a RemainingBytesProtocolMessageList field — " +
                        "this shape is terminal-only and the Partial decode pattern only fires " +
                        "for shapes with a trailing RemainingBytesPayload. The two shapes are " +
                        "mutually exclusive at the terminal slot, so this branch is unreachable.",
                )
            is FieldSpec.RemainingBytesPayload ->
                error(
                    "partialFieldTypeName called on a RemainingBytesPayload field — caller " +
                        "should strip the payload field before mapping header types.",
                )
            is FieldSpec.RemainingBytesString ->
                error(
                    "partialFieldTypeName called on a RemainingBytesString field — this shape " +
                        "is terminal-only and the Partial decode pattern only fires for shapes " +
                        "with a trailing RemainingBytesPayload. The two are mutually exclusive " +
                        "at the terminal slot, so this branch is unreachable.",
                )
            is FieldSpec.UseCodecScalar -> field.fieldType
            is FieldSpec.LengthPrefixedUseCodecList ->
                ClassName("kotlin.collections", "List").parameterizedBy(field.elementClassName)
            is FieldSpec.LengthPrefixedUseCodecPayload -> field.payloadType
            is FieldSpec.ValueClassScalar -> field.valueClassType
            is FieldSpec.Conditional -> field.nullableTypeName
            is FieldSpec.ProtocolMessageScalar -> field.fieldType
        }

    private fun buildWireSizeFun(
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
        // Any `@UseCodec val: <scalar>` field's wireSize comes from
        // the user codec, which may be Exact or BackPatch. Collapse to
        // BackPatch unconditionally — runtime-Exact-via-cast (mirroring
        // LengthPrefixedMessage) is a follow-on once a vector measurably
        // benefits.
        if (shape.fields.any { it is FieldSpec.UseCodecScalar }) {
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
        when (val terminal = shape.fields.lastOrNull()) {
            is FieldSpec.LengthPrefixedMessage -> {
                val headerBytes = scalarHeaderBytes(shape) + terminal.prefixWidth
                builder.addStatement(
                    "val %L = (%T.wireSize(value.%L, context) as %T.Exact).bytes",
                    "${terminal.name}Size",
                    terminal.codecType,
                    terminal.name,
                    WIRE_SIZE_CN,
                )
                builder.addStatement(
                    "return %T.Exact(%L + %L)",
                    WIRE_SIZE_CN,
                    headerBytes,
                    "${terminal.name}Size",
                )
            }
            is FieldSpec.LengthFromString -> {
                // Body byte count comes from the resolved LengthSource
                // (sibling.toInt() for simple, sibling.property for
                // dotted). User-trusted.
                val prefixBytes = scalarHeaderBytes(shape)
                builder.addStatement(
                    "return %T.Exact(%L + %L)",
                    WIRE_SIZE_CN,
                    prefixBytes,
                    terminal.source.encodeAccessor(),
                )
            }
            is FieldSpec.LengthFromList -> {
                // Same Exact shape via LengthSource.
                val prefixBytes = scalarHeaderBytes(shape)
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
                val prefixBytes = scalarHeaderBytes(shape)
                builder.addStatement(
                    "return %T.Exact(%L + %L)",
                    WIRE_SIZE_CN,
                    prefixBytes,
                    terminal.source.encodeAccessor(),
                )
            }
            is FieldSpec.RemainingBytesProtocolMessageList -> {
                // Body byte count = sum of element wireSizes.
                // Each element codec's wireSize is cast to Exact at runtime —
                // same convention as LengthPrefixedMessage's `as Exact` cast
                // above; BackPatch element codecs throw ClassCastException
                // (fixture-design contract for this slice).
                val prefixBytes = scalarHeaderBytes(shape)
                builder.addStatement(
                    "return %T.Exact(%L + value.%L.sumOf { (%T.wireSize(it, context) as %T.Exact).bytes })",
                    WIRE_SIZE_CN,
                    prefixBytes,
                    terminal.name,
                    terminal.elementCodecClassName,
                    WIRE_SIZE_CN,
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

    private fun scalarHeaderBytes(shape: CodecShape): Int = shape.fields.sumOfFixedWireBytes().requireFixed("scalarHeaderBytes")

    private fun appendDecodeScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
    ) {
        val widthMatches = field.wireBytes == field.kind.width
        val explicitOrder = field.resolvedWireOrder != Endianness.Default
        // Natural-width Default — trust buffer.byteOrder.
        if (widthMatches && !explicitOrder) {
            body.addStatement("val %L = %L", field.name, naturalScalarReadExpr(field.kind))
            return
        }
        // Natural-width explicit Big/Little on a multi-byte scalar. Read at
        // the natural width and canonicalize via swapBytes when buffer.byteOrder
        // differs from the wire order. Matches the batched single-field code
        // shape — single readShort/readInt/readLong instead of N readUByte +
        // shift/or assembly. (1-byte scalars fall through to the manual path
        // since they have no byte order; the manual path emits a single byte
        // read for that case.)
        if (widthMatches && explicitOrder && field.kind.width > 1) {
            appendNaturalReadWithSwap(body, field)
            return
        }
        val bigEndian =
            when (field.resolvedWireOrder) {
                Endianness.Little -> false
                Endianness.Big, Endianness.Default -> true
            }
        appendManualScalarDecode(body, field, bigEndian)
    }

    private fun appendNaturalReadWithSwap(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
    ) {
        val canonicalOrder =
            if (field.resolvedWireOrder == Endianness.Big) "BIG_ENDIAN" else "LITTLE_ENDIAN"
        val readMethod =
            when (field.kind.width) {
                2 -> "readShort"
                4 -> "readInt"
                8 -> "readLong"
                else -> error("unsupported natural width ${field.kind.width}")
            }
        val rawVar = "${field.name}Raw"
        body.addStatement("val %L = buffer.%L()", rawVar, readMethod)
        when (field.kind) {
            ScalarKind.Short, ScalarKind.Int, ScalarKind.Long ->
                body.addStatement(
                    "val %L = if (buffer.byteOrder == %T.%L) %L else %M(%L)",
                    field.name,
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    rawVar,
                    SWAP_BYTES_MN,
                    rawVar,
                )
            ScalarKind.UShort, ScalarKind.UInt, ScalarKind.ULong -> {
                val toUnsigned =
                    when (field.kind) {
                        ScalarKind.UShort -> "toUShort"
                        ScalarKind.UInt -> "toUInt"
                        ScalarKind.ULong -> "toULong"
                        else -> error("unreachable")
                    }
                body.addStatement(
                    "val %L = (if (buffer.byteOrder == %T.%L) %L else %M(%L)).%L()",
                    field.name,
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    rawVar,
                    SWAP_BYTES_MN,
                    rawVar,
                    toUnsigned,
                )
            }
            ScalarKind.Float ->
                body.addStatement(
                    "val %L = Float.fromBits(if (buffer.byteOrder == %T.%L) %L else %M(%L))",
                    field.name,
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    rawVar,
                    SWAP_BYTES_MN,
                    rawVar,
                )
            ScalarKind.Double ->
                body.addStatement(
                    "val %L = Double.fromBits(if (buffer.byteOrder == %T.%L) %L else %M(%L))",
                    field.name,
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    rawVar,
                    SWAP_BYTES_MN,
                    rawVar,
                )
            ScalarKind.UByte, ScalarKind.Byte, ScalarKind.Boolean ->
                error("1-byte scalar should not take the natural-read-with-swap path")
        }
    }

    private fun appendManualScalarDecode(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
        bigEndian: Boolean,
    ) {
        val width = field.wireBytes
        if (field.kind == ScalarKind.UByte && width == 1) {
            body.addStatement("val %L = buffer.readUByte()", field.name)
            return
        }
        if (field.kind == ScalarKind.Byte && width == 1) {
            body.addStatement("val %L = buffer.readByte()", field.name)
            return
        }
        // Assemble the wire bytes into a wide unsigned accumulator, then narrow
        // to the field's declared kind. Signed kinds reinterpret the bit pattern
        // via toShort()/toInt()/toLong() (Kotlin's UShort/UInt/ULong .toX() are
        // bit-preserving). Float/Double go through fromBits().
        val accumulator = if (width >= 5) "toULong" else "toUInt"
        for (i in 0 until width) {
            body.addStatement(
                "val %L = buffer.readUByte().%L()",
                "${field.name}B$i",
                accumulator,
            )
        }
        val parts =
            (0 until width).map { i ->
                val byteName = "${field.name}B$i"
                val shiftBits = if (bigEndian) (width - 1 - i) * 8 else i * 8
                if (shiftBits == 0) byteName else "($byteName shl $shiftBits)"
            }
        val combined = if (parts.size == 1) parts[0] else "(${parts.joinToString(" or ")})"
        when (field.kind) {
            ScalarKind.UByte -> body.addStatement("val %L = %L.toUByte()", field.name, combined)
            ScalarKind.UShort -> body.addStatement("val %L = %L.toUShort()", field.name, combined)
            ScalarKind.UInt, ScalarKind.ULong -> body.addStatement("val %L = %L", field.name, combined)
            ScalarKind.Byte -> body.addStatement("val %L = %L.toByte()", field.name, combined)
            ScalarKind.Short -> body.addStatement("val %L = %L.toShort()", field.name, combined)
            ScalarKind.Int -> body.addStatement("val %L = %L.toInt()", field.name, combined)
            ScalarKind.Long -> body.addStatement("val %L = %L.toLong()", field.name, combined)
            ScalarKind.Float ->
                body.addStatement("val %L = Float.fromBits(%L.toInt())", field.name, combined)
            ScalarKind.Double ->
                body.addStatement("val %L = Double.fromBits(%L.toLong())", field.name, combined)
            ScalarKind.Boolean ->
                error("Boolean is pinned to the natural-read path; analyzeField rejects manual-path Boolean")
        }
    }

    private fun appendEncodeScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
        ownerSimpleName: String,
    ) {
        appendEncodeGuard(body, field, ownerSimpleName)
        val accessor = "value.${field.name}"
        val widthMatches = field.wireBytes == field.kind.width
        val explicitOrder = field.resolvedWireOrder != Endianness.Default
        if (widthMatches && !explicitOrder) {
            body.addStatement(naturalScalarWriteStatement(field.kind, accessor))
            return
        }
        // Natural-width explicit Big/Little on a multi-byte scalar. Convert
        // to the natural integer type, conditionally swapBytes, write.
        if (widthMatches && explicitOrder && field.kind.width > 1) {
            appendNaturalWriteWithSwap(body, field, accessor)
            return
        }
        val bigEndian =
            when (field.resolvedWireOrder) {
                Endianness.Little -> false
                Endianness.Big, Endianness.Default -> true
            }
        appendManualScalarEncode(body, field, accessor, bigEndian)
    }

    private fun appendNaturalWriteWithSwap(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
        accessor: String,
    ) {
        val canonicalOrder =
            if (field.resolvedWireOrder == Endianness.Big) "BIG_ENDIAN" else "LITTLE_ENDIAN"
        val writeMethod =
            when (field.kind.width) {
                2 -> "writeShort"
                4 -> "writeInt"
                8 -> "writeLong"
                else -> error("unsupported natural width ${field.kind.width}")
            }
        val rawExpr =
            when (field.kind) {
                ScalarKind.Short, ScalarKind.Int, ScalarKind.Long -> accessor
                ScalarKind.UShort -> "$accessor.toShort()"
                ScalarKind.UInt -> "$accessor.toInt()"
                ScalarKind.ULong -> "$accessor.toLong()"
                ScalarKind.Float -> "$accessor.toRawBits()"
                ScalarKind.Double -> "$accessor.toRawBits()"
                ScalarKind.UByte, ScalarKind.Byte, ScalarKind.Boolean ->
                    error("1-byte scalar should not take the natural-write-with-swap path")
            }
        val rawVar = "${field.name}Raw"
        body.addStatement("val %L = %L", rawVar, rawExpr)
        body.addStatement(
            "buffer.%L(if (buffer.byteOrder == %T.%L) %L else %M(%L))",
            writeMethod,
            BYTE_ORDER_CN,
            canonicalOrder,
            rawVar,
            SWAP_BYTES_MN,
            rawVar,
        )
    }

    private fun appendEncodeGuard(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
        ownerSimpleName: String,
    ) {
        if (field.wireBytes >= field.kind.width) return
        val accessor = "value.${field.name}"
        val (lhs, maxLit) =
            when (field.kind) {
                ScalarKind.ULong -> accessor to "((1uL shl ${8 * field.wireBytes}) - 1uL)"
                ScalarKind.UInt -> accessor to "((1u shl ${8 * field.wireBytes}) - 1u)"
                ScalarKind.UShort -> "$accessor.toUInt()" to "((1u shl ${8 * field.wireBytes}) - 1u)"
                // wireBytes < 1 is rejected by analyzeField
                ScalarKind.UByte -> return
                // signed kinds reject @WireBytes narrowing in analyzeField
                ScalarKind.Byte, ScalarKind.Short, ScalarKind.Int, ScalarKind.Long -> return
                // Float/Double also reject @WireBytes narrowing
                ScalarKind.Float, ScalarKind.Double -> return
                // analyzeField pins Boolean to natural width — never narrows
                ScalarKind.Boolean -> return
            }
        val maxValue = (1L shl (8 * field.wireBytes)) - 1
        body.beginControlFlow("if (%L > %L)", lhs, maxLit)
        body.addStatement(
            "throw %T(fieldPath = %S, reason = %S)",
            ENCODE_EXCEPTION_CN,
            "$ownerSimpleName.${field.name}",
            "value exceeds @WireBytes(${field.wireBytes}) range (max $maxValue)",
        )
        body.endControlFlow()
    }

    private fun appendManualScalarEncode(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
        accessor: String,
        bigEndian: Boolean,
    ) {
        val width = field.wireBytes
        if (field.kind == ScalarKind.UByte && width == 1) {
            body.addStatement("buffer.writeUByte(%L)", accessor)
            return
        }
        if (field.kind == ScalarKind.Byte && width == 1) {
            body.addStatement("buffer.writeByte(%L)", accessor)
            return
        }
        // Convert the field value to a wide unsigned accumulator (UInt for
        // ≤4 bytes, ULong for >4) and shift bytes off the high or low end
        // per wire order. Signed kinds reinterpret via .toUInt()/.toULong()
        // (bit-preserving). Float/Double go through toRawBits().
        val wide =
            when (field.kind) {
                ScalarKind.UByte -> "$accessor.toUInt()"
                ScalarKind.UShort -> "$accessor.toUInt()"
                ScalarKind.UInt -> accessor
                ScalarKind.ULong -> accessor
                ScalarKind.Byte -> "$accessor.toUByte().toUInt()"
                ScalarKind.Short -> "$accessor.toUShort().toUInt()"
                ScalarKind.Int -> "$accessor.toUInt()"
                ScalarKind.Long -> "$accessor.toULong()"
                ScalarKind.Float -> "$accessor.toRawBits().toUInt()"
                ScalarKind.Double -> "$accessor.toRawBits().toULong()"
                ScalarKind.Boolean ->
                    error("Boolean is pinned to the natural-write path; analyzeField rejects manual-path Boolean")
            }
        val maskLit = if (width >= 5) "0xFFuL" else "0xFFu"
        for (i in 0 until width) {
            val shiftBits = if (bigEndian) (width - 1 - i) * 8 else i * 8
            val expr =
                if (shiftBits == 0) {
                    "$wide and $maskLit"
                } else {
                    "($wide shr $shiftBits) and $maskLit"
                }
            body.addStatement("buffer.writeUByte((%L).toUByte())", expr)
        }
    }

    /**
     * Emit decode for a `@JvmInline value class` field
     * with a single supported-scalar inner. Reads the inner scalar at
     * natural width and constructs the value class via its primary
     * constructor. The local is named after the outer parameter so
     * dotted-form `@When` resolvers can address it as `<name>.<property>`.
     */
    private fun appendDecodeValueClassScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.ValueClassScalar,
    ) {
        // Honor the value class's declared @ProtocolMessage(wireOrder) the
        // same way plain Scalars honor @WireOrder / parent wireOrder: explicit
        // Big / Little wins over buffer.byteOrder. Multi-byte inner kinds get
        // the swapBytes fast path (matches the single-scalar Scalar emit).
        // 1-byte inner kinds have no byte order — the natural read suffices.
        if (field.valueClassWireOrder != Endianness.Default && field.innerKind.width > 1) {
            appendValueClassNaturalReadWithSwap(body, field)
            return
        }
        body.addStatement(
            "val %L = %T(%L)",
            field.name,
            field.valueClassType,
            naturalScalarReadExpr(field.innerKind),
        )
    }

    /**
     * Emit encode for a value-class field. Unwraps
     * via the inner property name and writes the inner scalar at
     * natural width — or, when the value class declares an explicit
     * wireOrder, takes the swap fast path so the wire bytes match
     * the value class's contract regardless of buffer.byteOrder.
     */
    private fun appendEncodeValueClassScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.ValueClassScalar,
    ) {
        if (field.valueClassWireOrder != Endianness.Default && field.innerKind.width > 1) {
            appendValueClassNaturalWriteWithSwap(body, field)
            return
        }
        body.addStatement(
            naturalScalarWriteStatement(
                field.innerKind,
                "value.${field.name}.${field.innerPropertyName}",
            ),
        )
    }

    private fun appendValueClassNaturalReadWithSwap(
        body: CodeBlock.Builder,
        field: FieldSpec.ValueClassScalar,
    ) {
        val canonicalOrder =
            if (field.valueClassWireOrder == Endianness.Big) "BIG_ENDIAN" else "LITTLE_ENDIAN"
        val readMethod =
            when (field.innerKind.width) {
                2 -> "readShort"
                4 -> "readInt"
                8 -> "readLong"
                else -> error("unsupported value-class inner width ${field.innerKind.width}")
            }
        val rawVar = "${field.name}Raw"
        body.addStatement("val %L = buffer.%L()", rawVar, readMethod)
        val toUnsigned =
            when (field.innerKind) {
                ScalarKind.UShort -> "toUShort"
                ScalarKind.UInt -> "toUInt"
                ScalarKind.ULong -> "toULong"
                else -> null
            }
        if (toUnsigned != null) {
            body.addStatement(
                "val %L = %T((if (buffer.byteOrder == %T.%L) %L else %M(%L)).%L())",
                field.name,
                field.valueClassType,
                BYTE_ORDER_CN,
                canonicalOrder,
                rawVar,
                SWAP_BYTES_MN,
                rawVar,
                toUnsigned,
            )
        } else {
            body.addStatement(
                "val %L = %T(if (buffer.byteOrder == %T.%L) %L else %M(%L))",
                field.name,
                field.valueClassType,
                BYTE_ORDER_CN,
                canonicalOrder,
                rawVar,
                SWAP_BYTES_MN,
                rawVar,
            )
        }
    }

    private fun appendValueClassNaturalWriteWithSwap(
        body: CodeBlock.Builder,
        field: FieldSpec.ValueClassScalar,
    ) {
        val canonicalOrder =
            if (field.valueClassWireOrder == Endianness.Big) "BIG_ENDIAN" else "LITTLE_ENDIAN"
        val writeMethod =
            when (field.innerKind.width) {
                2 -> "writeShort"
                4 -> "writeInt"
                8 -> "writeLong"
                else -> error("unsupported value-class inner width ${field.innerKind.width}")
            }
        val accessor = "value.${field.name}.${field.innerPropertyName}"
        val rawExpr =
            when (field.innerKind) {
                ScalarKind.Short, ScalarKind.Int, ScalarKind.Long -> accessor
                ScalarKind.UShort -> "$accessor.toShort()"
                ScalarKind.UInt -> "$accessor.toInt()"
                ScalarKind.ULong -> "$accessor.toLong()"
                ScalarKind.Float -> "$accessor.toRawBits()"
                ScalarKind.Double -> "$accessor.toRawBits()"
                else -> error("inner kind ${field.innerKind} cannot reach the swap path")
            }
        val rawVar = "${field.name}Raw"
        body.addStatement("val %L = %L", rawVar, rawExpr)
        body.addStatement(
            "buffer.%L(if (buffer.byteOrder == %T.%L) %L else %M(%L))",
            writeMethod,
            BYTE_ORDER_CN,
            canonicalOrder,
            rawVar,
            SWAP_BYTES_MN,
            rawVar,
        )
    }

    /**
     * Emit a `@When` decode block.
     *
     * Generated shape:
     * ```
     * val <name>: <NullableType> = if (<source>) <readExpr> else null
     * ```
     *
     * The source is a sibling `Boolean` local already in scope (decode visits
     * fields in constructor order, and analyzeConditionalField has verified
     * the source is declared before this field). `readExpr` is the natural-
     * width scalar read for the inner kind ( restricts inner to a
     * natural-width Scalar; widens to LengthPrefixedString).
     */
    private fun appendDecodeConditional(
        body: CodeBlock.Builder,
        field: FieldSpec.Conditional,
    ) {
        when (val inner = field.inner) {
            is ConditionalInner.Scalar -> {
                if (inner.wireOrder != Endianness.Default && inner.kind.width > 1) {
                    appendConditionalScalarSwapDecode(
                        body = body,
                        fieldName = field.name,
                        nullableTypeName = field.nullableTypeName,
                        condition = decodeConditionExpr(field.condition),
                        kind = inner.kind,
                        wireOrder = inner.wireOrder,
                        wrapValueClass = null,
                    )
                } else {
                    body.addStatement(
                        "val %L: %T = if (%L) %L else null",
                        field.name,
                        field.nullableTypeName,
                        decodeConditionExpr(field.condition),
                        naturalScalarReadExpr(inner.kind),
                    )
                }
            }
            is ConditionalInner.LengthPrefixedString -> {
                body.beginControlFlow(
                    "val %L: %T = if (%L)",
                    field.name,
                    field.nullableTypeName,
                    decodeConditionExpr(field.condition),
                )
                val lengthVar =
                    appendLengthPrefixedStringPrefixDecode(
                        body = body,
                        name = field.name,
                        ownerSimpleName = field.ownerSimpleName,
                        prefixWidth = inner.prefixWidth,
                        prefixWireOrder = inner.prefixWireOrder,
                    )
                body.addStatement("buffer.readString(%L, %T.UTF8)", lengthVar, CHARSET_CN)
                body.nextControlFlow("else")
                body.addStatement("null")
                body.endControlFlow()
            }
            is ConditionalInner.ValueClassScalar -> {
                // Wrap the natural-width inner read
                // in the value-class constructor (mirror of 's
                // non-conditional `appendDecodeValueClassScalar`).
                if (inner.valueClassWireOrder != Endianness.Default && inner.innerKind.width > 1) {
                    appendConditionalScalarSwapDecode(
                        body = body,
                        fieldName = field.name,
                        nullableTypeName = field.nullableTypeName,
                        condition = decodeConditionExpr(field.condition),
                        kind = inner.innerKind,
                        wireOrder = inner.valueClassWireOrder,
                        wrapValueClass = inner.valueClassType,
                    )
                } else {
                    body.addStatement(
                        "val %L: %T = if (%L) %T(%L) else null",
                        field.name,
                        field.nullableTypeName,
                        decodeConditionExpr(field.condition),
                        inner.valueClassType,
                        naturalScalarReadExpr(inner.innerKind),
                    )
                }
            }
            is ConditionalInner.LengthPrefixedUseCodecList -> {
                // `@When @LengthPrefixed @UseCodec(C) val
                // xs: List<E>?` — predicate-true branch runs the
                // inner-bag decode ( shared body). Else null.
                body.beginControlFlow(
                    "val %L: %T = if (%L)",
                    field.name,
                    field.nullableTypeName,
                    decodeConditionExpr(field.condition),
                )
                appendDecodeLengthPrefixedListBody(
                    body = body,
                    spec = inner.spec,
                    listLocalName = "${field.name}Value",
                    namespacePrefix = field.name,
                )
                body.addStatement("%LValue", field.name)
                body.nextControlFlow("else")
                body.addStatement("null")
                body.endControlFlow()
            }
            is ConditionalInner.LengthPrefixedUseCodecPayload -> {
                // Predicate-true branch reads the
                // fixed-width prefix, narrows `buffer.limit()` to position
                // + length, runs `<C>.decode`, restores the outer limit.
                // Mirrors [appendDecodeLengthPrefixedUseCodecPayload] but
                // wrapped in the conditional's `if (predicate)` gate.
                body.beginControlFlow(
                    "val %L: %T = if (%L)",
                    field.name,
                    field.nullableTypeName,
                    decodeConditionExpr(field.condition),
                )
                val lengthVar =
                    appendLengthPrefixedStringPrefixDecode(
                        body = body,
                        name = field.name,
                        ownerSimpleName = field.ownerSimpleName,
                        prefixWidth = inner.prefixWidth,
                        prefixWireOrder = inner.prefixWireOrder,
                    )
                val outerLimitVar = "__${field.name}OuterLimit"
                body.addStatement("val %L = buffer.limit()", outerLimitVar)
                body.addStatement("buffer.setLimit(buffer.position() + %L)", lengthVar)
                body.beginControlFlow("try")
                body.addStatement("%T.decode(buffer, context)", inner.payloadCodecType)
                body.nextControlFlow("finally")
                body.addStatement("buffer.setLimit(%L)", outerLimitVar)
                body.endControlFlow()
                body.nextControlFlow("else")
                body.addStatement("null")
                body.endControlFlow()
            }
            is ConditionalInner.UseCodecScalar -> {
                // `@When @UseCodec(C) val: T?`.
                // Predicate-true delegates to the codec object's
                // `decode(buffer, context)`, just like the non-conditional
                // `appendDecodeUseCodecScalar` path; predicate-false yields
                // null. The cascading-trailer cases use grammar-2
                // `remaining >= N` predicates so the read only runs when
                // the bounded buffer still has bytes to spend.
                body.addStatement(
                    "val %L: %T = if (%L) %T.decode(buffer, context) else null",
                    field.name,
                    field.nullableTypeName,
                    decodeConditionExpr(field.condition),
                    inner.codecType,
                )
            }
            is ConditionalInner.ProtocolMessageScalar -> {
                // Bare `@When val: T?` for a
                // `@ProtocolMessage` data class or sealed parent. The
                // codec class resolves to `${T.simpleName}Codec`
                // by-name; the call shape is identical to UseCodecScalar.
                body.addStatement(
                    "val %L: %T = if (%L) %T.decode(buffer, context) else null",
                    field.name,
                    field.nullableTypeName,
                    decodeConditionExpr(field.condition),
                    inner.codecType,
                )
            }
        }
    }

    /**
     * /3 — emit a `@When` encode block.
     *
     * Generated shape:
     * ```
     * if (value.<source>) {
     *     val <name>Value = value.<name> ?: throw EncodeException(...)
     *     <writeStatement(s) using `<name>Value`>
     * }
     * ```
     *
     * `<source>` is `value.<sibling>` for the simple form and
     * `value.<sibling>.<property>` for the dotted form. The body is
     * a single-line scalar write for `ConditionalInner.Scalar` and
     * the BackPatch length-prefix sequence for
     * `ConditionalInner.LengthPrefixedString` (.5).
     *
     * Predicate-false branch writes nothing (zero bytes for the slot, per
     * ). Predicate-true with `value.<name> == null`
     * throws `EncodeException` with field-path attribution (row 20).
     */
    private fun appendEncodeConditional(
        body: CodeBlock.Builder,
        field: FieldSpec.Conditional,
    ) {
        body.beginControlFlow("if (%L)", encodeConditionAccessor(field.condition, field.name))
        val localName = "${field.name}Value"
        body.addStatement(
            "val %L = value.%L ?: throw %T(fieldPath = %S, reason = %S)",
            localName,
            field.name,
            ENCODE_EXCEPTION_CN,
            "${field.ownerSimpleName}.${field.name}",
            "@When(\"${conditionExpressionLiteral(field.condition)}\") predicate is true but field is null",
        )
        when (val inner = field.inner) {
            is ConditionalInner.Scalar ->
                if (inner.wireOrder != Endianness.Default && inner.kind.width > 1) {
                    appendConditionalScalarSwapEncode(
                        body = body,
                        accessor = localName,
                        kind = inner.kind,
                        wireOrder = inner.wireOrder,
                        valueClassInnerProperty = null,
                    )
                } else {
                    body.addStatement(naturalScalarWriteStatement(inner.kind, localName))
                }
            is ConditionalInner.LengthPrefixedString ->
                appendLengthPrefixedStringEncode(
                    body = body,
                    name = field.name,
                    ownerSimpleName = field.ownerSimpleName,
                    prefixWidth = inner.prefixWidth,
                    prefixWireOrder = inner.prefixWireOrder,
                    accessor = localName,
                )
            is ConditionalInner.ValueClassScalar ->
                // Unwrap the value class via the
                // inner property name (mirror of 's
                // non-conditional `appendEncodeValueClassScalar`).
                if (inner.valueClassWireOrder != Endianness.Default && inner.innerKind.width > 1) {
                    appendConditionalScalarSwapEncode(
                        body = body,
                        accessor = localName,
                        kind = inner.innerKind,
                        wireOrder = inner.valueClassWireOrder,
                        valueClassInnerProperty = inner.innerPropertyName,
                    )
                } else {
                    body.addStatement(
                        naturalScalarWriteStatement(
                            inner.innerKind,
                            "$localName.${inner.innerPropertyName}",
                        ),
                    )
                }
            is ConditionalInner.LengthPrefixedUseCodecList ->
                appendEncodeConditionalLengthPrefixedUseCodecList(
                    body = body,
                    field = field,
                    inner = inner,
                    accessor = localName,
                )
            is ConditionalInner.LengthPrefixedUseCodecPayload ->
                // BackPatch shape mirroring
                // [appendEncodeLengthPrefixedUseCodecPayload]: reserve
                // prefix slot, run `<C>.encode`, measure body byte count,
                // patch the prefix in place, restore position. Reads the
                // smart-cast non-null `<name>Value` local established by
                // the outer `appendEncodeConditional`.
                appendEncodeConditionalLengthPrefixedUseCodecPayload(
                    body = body,
                    field = field,
                    inner = inner,
                    accessor = localName,
                )
            is ConditionalInner.UseCodecScalar ->
                // Mirror of the non-conditional
                // `appendEncodeUseCodecScalar`. Predicate-true with
                // smart-cast non-null `<name>Value` (established above)
                // delegates to the user codec's `encode`.
                body.addStatement(
                    "%T.encode(buffer, %L, context)",
                    inner.codecType,
                    localName,
                )
            is ConditionalInner.ProtocolMessageScalar ->
                // Same encode shape as
                // UseCodecScalar; the only thing that differs is how the
                // codec class name was resolved at analyze time.
                body.addStatement(
                    "%T.encode(buffer, %L, context)",
                    inner.codecType,
                    localName,
                )
        }
        body.endControlFlow()
    }

    // Shared helper for the @When + explicit-wireOrder case. Generates a
    // block-expression if/else where the `if` arm reads the natural-width
    // wire value and canonicalizes via swapBytes; matches the contract
    // explicit wire order should beat buffer.byteOrder, mirroring the
    // non-conditional Scalar / ValueClassScalar swap path. `wrapValueClass`
    // routes the swapped value through the value class's constructor when
    // present (ValueClassScalar conditional path).
    private fun appendConditionalScalarSwapDecode(
        body: CodeBlock.Builder,
        fieldName: String,
        nullableTypeName: TypeName,
        condition: String,
        kind: ScalarKind,
        wireOrder: Endianness,
        wrapValueClass: ClassName?,
    ) {
        val canonicalOrder =
            if (wireOrder == Endianness.Big) "BIG_ENDIAN" else "LITTLE_ENDIAN"
        val readMethod =
            when (kind.width) {
                2 -> "readShort"
                4 -> "readInt"
                8 -> "readLong"
                else -> error("unsupported conditional width ${kind.width}")
            }
        val rawVar = "${fieldName}Raw"
        body.beginControlFlow(
            "val %L: %T = if (%L)",
            fieldName,
            nullableTypeName,
            condition,
        )
        body.addStatement("val %L = buffer.%L()", rawVar, readMethod)
        // Emit the swap + cast (+ optional value-class wrap) as the if-block's
        // value expression via KotlinPoet placeholders so ByteOrder and
        // swapBytes resolve through the file's imports (not as FQNs).
        val unsignedCast =
            when (kind) {
                ScalarKind.UShort -> "toUShort"
                ScalarKind.UInt -> "toUInt"
                ScalarKind.ULong -> "toULong"
                else -> null
            }
        when {
            wrapValueClass != null && unsignedCast != null ->
                body.addStatement(
                    "%T((if (buffer.byteOrder == %T.%L) %L else %M(%L)).%L())",
                    wrapValueClass,
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    rawVar,
                    SWAP_BYTES_MN,
                    rawVar,
                    unsignedCast,
                )
            wrapValueClass != null ->
                body.addStatement(
                    "%T(if (buffer.byteOrder == %T.%L) %L else %M(%L))",
                    wrapValueClass,
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    rawVar,
                    SWAP_BYTES_MN,
                    rawVar,
                )
            unsignedCast != null ->
                body.addStatement(
                    "(if (buffer.byteOrder == %T.%L) %L else %M(%L)).%L()",
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    rawVar,
                    SWAP_BYTES_MN,
                    rawVar,
                    unsignedCast,
                )
            kind == ScalarKind.Float ->
                body.addStatement(
                    "Float.fromBits(if (buffer.byteOrder == %T.%L) %L else %M(%L))",
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    rawVar,
                    SWAP_BYTES_MN,
                    rawVar,
                )
            kind == ScalarKind.Double ->
                body.addStatement(
                    "Double.fromBits(if (buffer.byteOrder == %T.%L) %L else %M(%L))",
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    rawVar,
                    SWAP_BYTES_MN,
                    rawVar,
                )
            else ->
                body.addStatement(
                    "if (buffer.byteOrder == %T.%L) %L else %M(%L)",
                    BYTE_ORDER_CN,
                    canonicalOrder,
                    rawVar,
                    SWAP_BYTES_MN,
                    rawVar,
                )
        }
        body.nextControlFlow("else")
        body.addStatement("null")
        body.endControlFlow()
    }

    private fun appendConditionalScalarSwapEncode(
        body: CodeBlock.Builder,
        accessor: String,
        kind: ScalarKind,
        wireOrder: Endianness,
        valueClassInnerProperty: String?,
    ) {
        val canonicalOrder =
            if (wireOrder == Endianness.Big) "BIG_ENDIAN" else "LITTLE_ENDIAN"
        val writeMethod =
            when (kind.width) {
                2 -> "writeShort"
                4 -> "writeInt"
                8 -> "writeLong"
                else -> error("unsupported conditional width ${kind.width}")
            }
        val resolvedAccessor =
            if (valueClassInnerProperty != null) "$accessor.$valueClassInnerProperty" else accessor
        val rawExpr =
            when (kind) {
                ScalarKind.Short, ScalarKind.Int, ScalarKind.Long -> resolvedAccessor
                ScalarKind.UShort -> "$resolvedAccessor.toShort()"
                ScalarKind.UInt -> "$resolvedAccessor.toInt()"
                ScalarKind.ULong -> "$resolvedAccessor.toLong()"
                ScalarKind.Float -> "$resolvedAccessor.toRawBits()"
                ScalarKind.Double -> "$resolvedAccessor.toRawBits()"
                ScalarKind.UByte, ScalarKind.Byte, ScalarKind.Boolean ->
                    error("1-byte kind should not take the conditional swap path")
            }
        val rawVar = "${accessor}Raw"
        body.addStatement("val %L = %L", rawVar, rawExpr)
        body.addStatement(
            "buffer.%L(if (buffer.byteOrder == %T.%L) %L else %M(%L))",
            writeMethod,
            BYTE_ORDER_CN,
            canonicalOrder,
            rawVar,
            SWAP_BYTES_MN,
            rawVar,
        )
    }

    /**
     * Encode a conditional `@LengthPrefixed @UseCodec(C)
     * val xs: List<E>?`. Audit-2a deduplication: delegates to the shared
     * `appendEncodeLengthPrefixedListBody` helper.
     *
     * `accessor` is the smart-cast non-null local established by the
     * outer `appendEncodeConditional` (`<name>Value`). The
     * non-conditional emit reads `value.<name>` instead — same shape,
     * different read expression (the helper takes `accessor` as a
     * parameter to absorb the difference).
     */
    private fun appendEncodeConditionalLengthPrefixedUseCodecList(
        body: CodeBlock.Builder,
        field: FieldSpec.Conditional,
        inner: ConditionalInner.LengthPrefixedUseCodecList,
        accessor: String,
    ) {
        appendEncodeLengthPrefixedListBody(
            body = body,
            spec = inner.spec,
            accessor = accessor,
            namespacePrefix = field.name,
        )
    }

    /**
     * Encode a conditional `@LengthPrefixed
     * @UseCodec(C) val: T?` where T : Payload. BackPatch shape mirroring
     * [appendEncodeLengthPrefixedUseCodecPayload]: reserve prefix slot,
     * run `<C>.encode(buffer, accessor, context)` against the
     * accumulating buffer, measure body byte count from the position
     * delta, patch the prefix, restore position past the body.
     *
     * `accessor` is the smart-cast non-null local established by the
     * outer `appendEncodeConditional` (`<name>Value`). The non-
     * conditional emit reads `value.<name>` directly.
     */
    private fun appendEncodeConditionalLengthPrefixedUseCodecPayload(
        body: CodeBlock.Builder,
        field: FieldSpec.Conditional,
        inner: ConditionalInner.LengthPrefixedUseCodecPayload,
        accessor: String,
    ) {
        val sizePosVar = "${field.name}SizePosition"
        val bodyStartVar = "${field.name}BodyStart"
        val endPosVar = "${field.name}EndPosition"
        val byteCountVar = "${field.name}ByteCount"
        body.addStatement("val %L = buffer.position()", sizePosVar)
        body.addStatement("buffer.position(%L + %L)", sizePosVar, inner.prefixWidth)
        body.addStatement("val %L = buffer.position()", bodyStartVar)
        body.addStatement("%T.encode(buffer, %L, context)", inner.payloadCodecType, accessor)
        body.addStatement("val %L = buffer.position()", endPosVar)
        body.addStatement("val %L = %L - %L", byteCountVar, endPosVar, bodyStartVar)
        if (inner.prefixWidth < 4) {
            val maxValue = (1L shl (inner.prefixWidth * 8)) - 1
            val widthName =
                when (inner.prefixWidth) {
                    1 -> "Byte"
                    2 -> "Short"
                    else -> error("unreachable: prefixWidth must be 1, 2, or 4")
                }
            body.beginControlFlow("if (%L > %L)", byteCountVar, maxValue)
            body.addStatement(
                "throw %T(fieldPath = %S, reason = %P)",
                ENCODE_EXCEPTION_CN,
                "${field.ownerSimpleName}.${field.name}",
                "encoded payload byte length \${$byteCountVar} exceeds " +
                    "@LengthPrefixed(LengthPrefix.$widthName) max $maxValue",
            )
            body.endControlFlow()
        }
        body.addStatement("buffer.position(%L)", sizePosVar)
        val prefixVar = "${field.name}Prefix"
        body.addStatement("val %L = %L.toUInt()", prefixVar, byteCountVar)
        appendBufferPrefixEncode(body, prefixVar, inner.prefixWidth, inner.prefixWireOrder)
        body.addStatement("buffer.position(%L)", endPosVar)
    }

    /**
     * Encode-side predicate accessor. Encode reads from the message
     * value, so all paths start at `value.`. Simple form is
     * `value.<sibling>`; dotted form is `value.<sibling>.<property>`.
     *
     * Grammar 2 (`remaining <op> <int>`) encode semantics differ:
     * cascading-trailer fields are gated on whether the caller provided
     * a non-null value (the encode-side has no buffer-`remaining()` to
     * test against — the slot is included iff the field is set). Caller
     * is responsible for keeping the cascade consistent (don't set a
     * later trailer if an earlier one is null).
     */
    private fun encodeConditionAccessor(
        condition: ConditionRef,
        fieldName: String,
    ): String =
        when (condition) {
            is ConditionRef.Sibling -> "value.${condition.name}"
            is ConditionRef.ValueClassProperty -> "value.${condition.siblingName}.${condition.propertyName}"
            is ConditionRef.RemainingCmp -> "value.$fieldName != null"
        }

    /**
     * Reconstruct the original `@When("...")` expression literal
     * for use in `EncodeException` field-path messages (row 20).
     */
    private fun conditionExpressionLiteral(condition: ConditionRef): String =
        when (condition) {
            is ConditionRef.Sibling -> condition.name
            is ConditionRef.ValueClassProperty -> "${condition.siblingName}.${condition.propertyName}"
            is ConditionRef.RemainingCmp ->
                "remaining ${condition.op.symbol} ${condition.threshold}"
        }

    private fun appendDecodeLengthPrefixed(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthPrefixedMessage,
    ) {
        val prefixVar = "${field.name}Prefix"
        appendBufferPrefixDecode(body, prefixVar, field.prefixWidth, field.prefixWireOrder)
        body.beginControlFlow("if (%L > Int.MAX_VALUE.toUInt())", prefixVar)
        body.addStatement(
            "throw %T(fieldPath = %S, bufferPosition = -1, expected = %S, actual = %L.toString())",
            DECODE_EXCEPTION_CN,
            "${field.ownerSimpleName}.${field.name}",
            "length prefix <= \${Int.MAX_VALUE}",
            prefixVar,
        )
        body.endControlFlow()
        val resolvedVar = "${field.name}Length"
        body.addStatement("val %L = %L.toInt()", resolvedVar, prefixVar)
        val outerVar = "${field.name}OuterLimit"
        body.addStatement("val %L = buffer.limit()", outerVar)
        body.addStatement("buffer.setLimit(buffer.position() + %L)", resolvedVar)
        body.beginControlFlow("val %L = try", field.name)
        body.addStatement("%T.decode(buffer, context)", field.codecType)
        body.nextControlFlow("finally")
        body.addStatement("buffer.setLimit(%L)", outerVar)
        body.endControlFlow()
    }

    private fun appendBufferPrefixDecode(
        body: CodeBlock.Builder,
        targetVar: String,
        prefixWidth: Int,
        wireOrder: Endianness,
    ) {
        if (prefixWidth == 1) {
            body.addStatement("val %L = buffer.readUByte().toUInt()", targetVar)
            return
        }
        val bigEndian =
            when (wireOrder) {
                Endianness.Big -> true
                Endianness.Little -> false
                Endianness.Default -> true
            }
        for (i in 0 until prefixWidth) {
            body.addStatement(
                "val %L = buffer.readUByte().toUInt()",
                "${targetVar}B$i",
            )
        }
        val parts =
            (0 until prefixWidth).map { i ->
                val byteName = "${targetVar}B$i"
                val shiftBits = if (bigEndian) (prefixWidth - 1 - i) * 8 else i * 8
                if (shiftBits == 0) byteName else "($byteName shl $shiftBits)"
            }
        body.addStatement("val %L = (%L)", targetVar, parts.joinToString(" or "))
    }

    private fun appendEncodeLengthPrefixed(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthPrefixedMessage,
    ) {
        val prefixVar = "${field.name}Prefix"
        body.addStatement(
            "val %L = (%T.wireSize(value.%L, context) as %T.Exact).bytes.toUInt()",
            prefixVar,
            field.codecType,
            field.name,
            WIRE_SIZE_CN,
        )
        appendBufferPrefixEncode(body, prefixVar, field.prefixWidth, field.prefixWireOrder)
        body.addStatement("%T.encode(buffer, value.%L, context)", field.codecType, field.name)
    }

    private fun appendDecodeLengthPrefixedString(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthPrefixedString,
    ) {
        val lengthVar =
            appendLengthPrefixedStringPrefixDecode(
                body = body,
                name = field.name,
                ownerSimpleName = field.ownerSimpleName,
                prefixWidth = field.prefixWidth,
                prefixWireOrder = field.prefixWireOrder,
            )
        body.addStatement(
            "val %L = buffer.readString(%L, %T.UTF8)",
            field.name,
            lengthVar,
            CHARSET_CN,
        )
    }

    /**
     * Emit the prefix read + Int.MAX_VALUE guard + length
     * Int conversion shared by length-prefixed-string field decode
     * and the conditional `@LengthPrefixed @When` decode path.
     * Returns the local variable name holding the resolved
     * (Int-typed) length.
     */
    private fun appendLengthPrefixedStringPrefixDecode(
        body: CodeBlock.Builder,
        name: String,
        ownerSimpleName: String,
        prefixWidth: Int,
        prefixWireOrder: Endianness,
    ): String {
        val prefixVar = "${name}Prefix"
        appendBufferPrefixDecode(body, prefixVar, prefixWidth, prefixWireOrder)
        body.beginControlFlow("if (%L > Int.MAX_VALUE.toUInt())", prefixVar)
        body.addStatement(
            "throw %T(fieldPath = %S, bufferPosition = -1, expected = %S, actual = %L.toString())",
            DECODE_EXCEPTION_CN,
            "$ownerSimpleName.$name",
            "length prefix <= \${Int.MAX_VALUE}",
            prefixVar,
        )
        body.endControlFlow()
        val lengthVar = "${name}Length"
        body.addStatement("val %L = %L.toInt()", lengthVar, prefixVar)
        return lengthVar
    }

    private fun appendEncodeLengthPrefixedString(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthPrefixedString,
    ) {
        appendLengthPrefixedStringEncode(
            body = body,
            name = field.name,
            ownerSimpleName = field.ownerSimpleName,
            prefixWidth = field.prefixWidth,
            prefixWireOrder = field.prefixWireOrder,
            accessor = "value.${field.name}",
        )
    }

    /**
     * Shared BackPatch encoder for length-prefixed-string
     * fields and the conditional `@LengthPrefixed @When` encode
     * path.
     *
     * `accessor` is the expression that yields the string value;
     * field-form callers pass `value.<name>`, conditional-form
     * callers pass the locally-bound non-null value (already
     * null-checked at the conditional gate). `name` is used for
     * generated-variable naming and the field-path attribution
     * literal.
     */
    private fun appendLengthPrefixedStringEncode(
        body: CodeBlock.Builder,
        name: String,
        ownerSimpleName: String,
        prefixWidth: Int,
        prefixWireOrder: Endianness,
        accessor: String,
    ) {
        // BackPatch pattern: reserve prefix slot, write
        // the body via the runtime's UTF-8 path, measure byte count from the
        // position delta, patch the prefix in place, restore position past the
        // body. The runtime's `writeString(text, Charset.UTF8)` is zero-`ByteArray`
        // on JVM / Apple / JS; the WASM and nonJvm `writeString` paths still
        // allocate one ByteArray per call (, deferred to a
        // separate runtime task).
        val sizePosVar = "${name}SizePosition"
        val bodyStartVar = "${name}BodyStart"
        val endPosVar = "${name}EndPosition"
        val byteCountVar = "${name}ByteCount"
        body.addStatement("val %L = buffer.position()", sizePosVar)
        body.addStatement("buffer.position(%L + %L)", sizePosVar, prefixWidth)
        body.addStatement("val %L = buffer.position()", bodyStartVar)
        body.addStatement(
            "buffer.writeString(%L, %T.UTF8)",
            accessor,
            CHARSET_CN,
        )
        body.addStatement("val %L = buffer.position()", endPosVar)
        body.addStatement("val %L = %L - %L", byteCountVar, endPosVar, bodyStartVar)
        // Runtime overflow guard. For 4-byte prefixes the max (UInt.MAX_VALUE =
        // 2^32-1) exceeds Int.MAX_VALUE, so a position-delta byte count can never
        // overflow it — the check would be dead code.
        if (prefixWidth < 4) {
            val maxValue = (1L shl (prefixWidth * 8)) - 1
            val widthName =
                when (prefixWidth) {
                    1 -> "Byte"
                    2 -> "Short"
                    else -> error("unreachable: prefixWidth must be 1, 2, or 4")
                }
            body.beginControlFlow("if (%L > %L)", byteCountVar, maxValue)
            body.addStatement(
                "throw %T(fieldPath = %S, reason = %P)",
                ENCODE_EXCEPTION_CN,
                "$ownerSimpleName.$name",
                "UTF-8 byte length \${$byteCountVar} exceeds @LengthPrefixed(LengthPrefix.$widthName) max $maxValue",
            )
            body.endControlFlow()
        }
        body.addStatement("buffer.position(%L)", sizePosVar)
        val prefixVar = "${name}Prefix"
        body.addStatement("val %L = %L.toUInt()", prefixVar, byteCountVar)
        appendBufferPrefixEncode(body, prefixVar, prefixWidth, prefixWireOrder)
        body.addStatement("buffer.position(%L)", endPosVar)
    }

    /**
     * Emit decode for `@LengthFrom("siblingField")
     * val: String`. The sibling local is in scope (decode visits
     * fields in constructor order, and analyzeLengthFromStringField
     * has verified the sibling is declared before this field).
     *
     * Generated shape:
     * ```
     * <Int.MAX_VALUE guard for sibling kinds whose range exceeds Int>
     * val <name>Length = <sibling>.toInt()
     * val <name> = buffer.readString(<name>Length, Charset.UTF8)
     * ```
     *
     * The guard is skipped for `Byte` / `Short` / `Int` / `UByte` /
     * `UShort`, whose values fit in a non-negative `Int`. `UInt`,
     * `ULong`, and `Long` need the runtime guard because their range
     * exceeds `Int.MAX_VALUE`.
     */
    private fun appendDecodeLengthFromString(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthFromString,
    ) {
        // Simple form needs an Int.MAX_VALUE guard for kinds whose
        // range exceeds Int (UInt / ULong / Long); the dotted form's
        // property returns Int directly so no guard is needed.
        if (field.source is LengthSource.Sibling) {
            appendLengthFromIntMaxGuard(
                body = body,
                siblingAccessor = field.source.siblingName,
                siblingKind = field.source.siblingKind,
                ownerSimpleName = field.ownerSimpleName,
                fieldName = field.name,
            )
        }
        // Inline the sibling/property accessor rather than binding
        // an intermediate. A `${field.name}Length` intermediate would
        // shadow the sibling local when the user names the carrier
        // `<bound>Length` — a natural Kotlin convention that the
        // generated code must not break.
        body.addStatement(
            "val %L = buffer.readString(%L, %T.UTF8)",
            field.name,
            field.source.decodeAccessor(),
            CHARSET_CN,
        )
    }

    /**
     * Emit encode for `@LengthFrom("siblingField")
     * val: String`. The sibling field has already been encoded by
     * the prior field's emit step; this step writes only the body.
     * The user is responsible for keeping `value.<sibling>`
     * consistent with `value.<name>.encodeToByteArray().size`; the
     * codec trusts that contract (a runtime cross-check would
     * allocate per row 16).
     */
    private fun appendEncodeLengthFromString(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthFromString,
    ) {
        body.addStatement(
            "buffer.writeString(value.%L, %T.UTF8)",
            field.name,
            CHARSET_CN,
        )
    }

    /**
     * Emit decode for `@LengthFrom("siblingField")
     * val: List<T>`. Bounds the buffer via `setLimit` to the
     * sibling-derived byte count, loops reading elements via the
     * element codec until the bounded position is reached, restores
     * the outer limit. The `try`/`finally` guarantees limit
     * restoration even if an element decode throws.
     *
     * Generated shape:
     * ```
     * <Int.MAX_VALUE guard for the sibling kind, if needed>
     * val <name>Bytes = <sibling>.toInt()
     * val <name>OuterLimit = buffer.limit()
     * buffer.setLimit(buffer.position() + <name>Bytes)
     * val <name> = mutableListOf<ElementType>()
     * try {
     *     while (buffer.position() < buffer.limit()) {
     *         <name> += ElementCodec.decode(buffer, context)
     *     }
     * } finally {
     *     buffer.setLimit(<name>OuterLimit)
     * }
     * ```
     */
    private fun appendDecodeLengthFromList(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthFromList,
    ) {
        if (field.source is LengthSource.Sibling) {
            appendLengthFromIntMaxGuard(
                body = body,
                siblingAccessor = field.source.siblingName,
                siblingKind = field.source.siblingKind,
                ownerSimpleName = field.ownerSimpleName,
                fieldName = field.name,
            )
        }
        val bytesVar = "${field.name}Bytes"
        val outerLimitVar = "${field.name}OuterLimit"
        body.addStatement("val %L = %L", bytesVar, field.source.decodeAccessor())
        body.addStatement("val %L = buffer.limit()", outerLimitVar)
        body.addStatement("buffer.setLimit(buffer.position() + %L)", bytesVar)
        body.addStatement("val %L = mutableListOf<%T>()", field.name, field.elementClassName)
        body.beginControlFlow("try")
        body.beginControlFlow("while (buffer.position() < buffer.limit())")
        body.addStatement("%L += %T.decode(buffer, context)", field.name, field.elementCodecClassName)
        body.endControlFlow()
        body.nextControlFlow("finally")
        body.addStatement("buffer.setLimit(%L)", outerLimitVar)
        body.endControlFlow()
    }

    /**
     * Emit encode for `@LengthFrom("siblingField")
     * val: List<T>`. Iterates the list and writes each element via
     * the element codec. The user is responsible for keeping
     * `value.<sibling>` consistent with the sum of element wire
     * sizes (same row-16 trust contract as the LengthFromString
     * encode path).
     */
    private fun appendEncodeLengthFromList(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthFromList,
    ) {
        body.beginControlFlow("for (__elem in value.%L)", field.name)
        body.addStatement("%T.encode(buffer, __elem, context)", field.elementCodecClassName)
        body.endControlFlow()
    }

    /**
     * (issue #151 part 1) — emit decode for
     * `@LengthFrom("siblingField") val: T : @ProtocolMessage`. Bounds the
     * buffer via `setLimit` to the sibling-derived end, delegates to
     * `<TCodec>.decode(buffer, context)`, restores the outer limit in a
     * `try`/`finally`. Same outer-limit-restore template as
     * [appendDecodeLengthFromList].
     *
     * Generated shape:
     * ```
     * <Int.MAX_VALUE guard for the sibling kind, if needed>
     * val <name>Bytes = <sibling>.toInt()
     * val <name>OuterLimit = buffer.limit()
     * buffer.setLimit(buffer.position() + <name>Bytes)
     * val <name> = try {
     *     <TCodec>.decode(buffer, context)
     * } finally {
     *     buffer.setLimit(<name>OuterLimit)
     * }
     * ```
     */
    private fun appendDecodeLengthFromMessage(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthFromMessage,
    ) {
        if (field.source is LengthSource.Sibling) {
            appendLengthFromIntMaxGuard(
                body = body,
                siblingAccessor = field.source.siblingName,
                siblingKind = field.source.siblingKind,
                ownerSimpleName = field.ownerSimpleName,
                fieldName = field.name,
            )
        }
        val bytesVar = "${field.name}Bytes"
        val outerLimitVar = "${field.name}OuterLimit"
        body.addStatement("val %L = %L", bytesVar, field.source.decodeAccessor())
        body.addStatement("val %L = buffer.limit()", outerLimitVar)
        body.addStatement("buffer.setLimit(buffer.position() + %L)", bytesVar)
        body.beginControlFlow("val %L = try", field.name)
        body.addStatement("%T.decode(buffer, context)", field.codecType)
        body.nextControlFlow("finally")
        body.addStatement("buffer.setLimit(%L)", outerLimitVar)
        body.endControlFlow()
    }

    /**
     * Emit encode for `@LengthFrom("siblingField") val: T:
     * @ProtocolMessage`. Single delegation to `<TCodec>.encode`. The
     * sibling field has already been encoded by the prior field's emit
     * step; the user is responsible for keeping `value.<sibling>`
     * consistent with `<TCodec>.wireSize(value.<name>, context).bytes`.
     */
    private fun appendEncodeLengthFromMessage(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthFromMessage,
    ) {
        body.addStatement(
            "%T.encode(buffer, value.%L, context)",
            field.codecType,
            field.name,
        )
    }

    /**
     * Emit decode for
     * `@RemainingBytes @UseCodec(C::class) val: P`. Delegates to the
     * user-supplied `C.decode(buffer, context)` against whatever
     * `buffer.limit()` already says — same caller-bounds-buffer contract
     * as the other `@RemainingBytes` shapes. The outer dispatcher (slice
     * 10d for MQTT) sets the limit before calling this codec.
     */
    private fun appendDecodeRemainingBytesPayload(
        body: CodeBlock.Builder,
        field: FieldSpec.RemainingBytesPayload,
    ) {
        if (field.reservedTrailingBytes == 0) {
            body.addStatement(
                "val %L = %L.decode(buffer, context)",
                field.name,
                field.source.codecReceiver(),
            )
            return
        }
        // Non-terminal RemainingBytesPayload. Narrow the
        // buffer's limit to leave the trailing FixedSize fields in the
        // outer-limit region; restore the outer limit in a try/finally
        // so the trailing field emits run against the original limit.
        val outerLimitVar = "__${field.name}OuterLimit"
        body.addStatement("val %L = buffer.limit()", outerLimitVar)
        body.addStatement(
            "buffer.setLimit(%L - %L)",
            outerLimitVar,
            field.reservedTrailingBytes,
        )
        body.beginControlFlow("val %L = try", field.name)
        body.addStatement("%L.decode(buffer, context)", field.source.codecReceiver())
        body.nextControlFlow("finally")
        body.addStatement("buffer.setLimit(%L)", outerLimitVar)
        body.endControlFlow()
    }

    /**
     * Emit encode for
     * `@RemainingBytes @UseCodec(C::class) val: P`. Delegates to the
     * user-supplied `C.encode(buffer, value.<name>, context)`. No length
     * carrier on the wire — the user codec writes its bytes against the
     * buffer's current position and the trust contract (row 16) leaves
     * total-byte-count consistency to the outer dispatcher.
     */
    private fun appendEncodeRemainingBytesPayload(
        body: CodeBlock.Builder,
        field: FieldSpec.RemainingBytesPayload,
    ) {
        body.addStatement(
            "%L.encode(buffer, value.%L, context)",
            field.source.codecReceiver(),
            field.name,
        )
    }

    /**
     * `@RemainingBytes val: String` — decode reads UTF-8 bytes from the current
     * position to `buffer.limit()`. The caller (or an outer dispatcher) is
     * responsible for narrowing `buffer.limit()` to the bounded extent before
     * invoking decode; same caller-bounds-buffer contract as
     * [appendDecodeRemainingBytesPayload].
     */
    private fun appendDecodeRemainingBytesString(
        body: CodeBlock.Builder,
        field: FieldSpec.RemainingBytesString,
    ) {
        if (field.reservedTrailingBytes == 0) {
            body.addStatement(
                "val %L = buffer.readString(buffer.remaining(), %T.UTF8)",
                field.name,
                CHARSET_CN,
            )
            return
        }
        // Read the body byte count minus the reserved trailing
        // FixedSize bytes; the trailing field emits run normally after.
        body.addStatement(
            "val %L = buffer.readString(buffer.remaining() - %L, %T.UTF8)",
            field.name,
            field.reservedTrailingBytes,
            CHARSET_CN,
        )
    }

    /**
     * Encode counterpart for `@RemainingBytes val: String`. Writes the value's
     * UTF-8 byte representation. The encoded byte count is reported via
     * [appendBackPatchWireSize] (the parent message's wireSize collapses to
     * BackPatch because the trailing string's byte count isn't known up front
     * without re-encoding).
     */
    private fun appendEncodeRemainingBytesString(
        body: CodeBlock.Builder,
        field: FieldSpec.RemainingBytesString,
    ) {
        body.addStatement(
            "buffer.writeString(value.%L, %T.UTF8)",
            field.name,
            CHARSET_CN,
        )
    }

    /**
     * Emit decode for `@RemainingBytes val: List<T>` where
     * `T` is a `@ProtocolMessage data class`. Loops `while
     * (buffer.position() < buffer.limit())` reading each element via
     * the element's own codec. Caller-bounds-buffer contract: an outer
     * dispatcher (e.g. MQTT's fixed-header remaining-length variable-
     * length integer) sets `buffer.limit()` before delegating.
     *
     * Generated shape:
     * ```
     * val <name> = mutableListOf<ElementType>()
     * while (buffer.position() < buffer.limit()) {
     *     <name> += ElementCodec.decode(buffer, context)
     * }
     * ```
     */
    private fun appendDecodeRemainingBytesProtocolMessageList(
        body: CodeBlock.Builder,
        field: FieldSpec.RemainingBytesProtocolMessageList,
    ) {
        body.addStatement("val %L = mutableListOf<%T>()", field.name, field.elementClassName)
        if (field.reservedTrailingBytes == 0) {
            body.beginControlFlow("while (buffer.position() < buffer.limit())")
        } else {
            // Leave room for the trailing FixedSize fields.
            body.beginControlFlow(
                "while (buffer.position() < buffer.limit() - %L)",
                field.reservedTrailingBytes,
            )
        }
        body.addStatement("%L += %T.decode(buffer, context)", field.name, field.elementCodecClassName)
        body.endControlFlow()
    }

    /**
     * Emit encode for `@RemainingBytes val: List<T>` where
     * `T` is a `@ProtocolMessage data class`. Iterates the list and
     * writes each element via the element codec. The encoded byte
     * count is implicit in the outer protocol's framing — same row 16
     * trust contract as `LengthFromList`'s encode path.
     */
    private fun appendEncodeRemainingBytesProtocolMessageList(
        body: CodeBlock.Builder,
        field: FieldSpec.RemainingBytesProtocolMessageList,
    ) {
        body.beginControlFlow("for (__elem in value.%L)", field.name)
        body.addStatement("%T.encode(buffer, __elem, context)", field.elementCodecClassName)
        body.endControlFlow()
    }

    /**
     * Emit decode for bare `@UseCodec val: <scalar>`.
     * Delegates to the user-supplied codec object's `decode(buffer,
     * context)`. When the codec implements [BoundingLengthCodec], the
     * outer buffer limit is captured into `__<name>OuterLimit` BEFORE
     * decode (so the surrounding try/finally restores the caller's
     * outer limit even if the user codec or `applyBound` throws), and
     * `applyBound(buffer, <name>)` runs after decode to narrow the
     * limit for subsequent fields — driven by interface inspection on
     * the codec target (the outer-limit-restore pattern).
     */
    private fun appendDecodeUseCodecScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.UseCodecScalar,
    ) {
        if (field.isBounding) {
            body.addStatement("val __%LOuterLimit = buffer.limit()", field.name)
        }
        body.addStatement("val %L = %T.decode(buffer, context)", field.name, field.codecType)
        if (field.isBounding) {
            body.addStatement("%T.applyBound(buffer, %L)", field.codecType, field.name)
        }
    }

    /**
     * Emit encode for bare `@UseCodec val: <scalar>`.
     * Delegates to the user-supplied codec object's `encode(buffer,
     * value.<name>, context)`. The user codec owns the wire shape;
     * the framework neither validates nor measures the encoded width.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun appendEncodeUseCodecScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.UseCodecScalar,
        shape: CodecShape,
    ) {
        body.addStatement("%T.encode(buffer, value.%L, context)", field.codecType, field.name)
    }

    /**
     * Emit decode/encode for bare `val: T:
     * @ProtocolMessage`. Mirrors [appendEncodeUseCodecScalar] /
     * [appendDecodeUseCodecScalar] minus the bounding-codec branch:
     * the by-name-resolved codec is never a `BoundingLengthCodec` (those
     * are user-supplied length codecs, never `@ProtocolMessage` body
     * codecs).
     */
    private fun appendDecodeProtocolMessageScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.ProtocolMessageScalar,
    ) {
        body.addStatement("val %L = %T.decode(buffer, context)", field.name, field.codecType)
    }

    private fun appendEncodeProtocolMessageScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.ProtocolMessageScalar,
    ) {
        body.addStatement("%T.encode(buffer, value.%L, context)", field.codecType, field.name)
    }

    /**
     * Emit decode for `@LengthPrefixed
     * @UseCodec(C::class) val xs: List<E>`. The codec drives the prefix
     * read and applies the resulting bound to `buffer.limit()`; the list
     * is read element-by-element via E's codec inside the bounded region.
     * Self-contained `try`/`finally` restores the outer limit, so
     * subsequent fields run at the original limit.
     *
     * Generated shape:
     * ```
     * val __<name>OuterLimit = buffer.limit()
     * val __<name>Length = <codecType>.decode(buffer, context)
     * <codecType>.applyBound(buffer, __<name>Length)
     * val <name> = mutableListOf<ElementType>()
     * try {
     *     while (buffer.position() < buffer.limit()) {
     *         <name> += ElementCodec.decode(buffer, context)
     *     }
     * } finally {
     *     buffer.setLimit(__<name>OuterLimit)
     * }
     * ```
     */
    private fun appendDecodeLengthPrefixedUseCodecList(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthPrefixedUseCodecList,
    ) {
        appendDecodeLengthPrefixedListBody(
            body = body,
            spec = field.spec,
            listLocalName = field.name,
            namespacePrefix = field.name,
        )
    }

    /**
     * Emit decode for `@LengthPrefixed
     * @UseCodec(C::class) val: T : Payload`. Reads the fixed-width
     * unsigned-int prefix, narrows `buffer.limit()` to position + length,
     * delegates the body decode to `C.decode(buffer, context)`, and
     * restores the outer limit in `try/finally`.
     *
     * Generated shape:
     * ```
     * val <name>Prefix = <prefix-decode>
     * if (<name>Prefix > Int.MAX_VALUE.toUInt()) throw DecodeException(...)
     * val <name>Length = <name>Prefix.toInt()
     * val __<name>OuterLimit = buffer.limit()
     * buffer.setLimit(buffer.position() + <name>Length)
     * val <name> = try {
     *     <PayloadCodec>.decode(buffer, context)
     * } finally {
     *     buffer.setLimit(__<name>OuterLimit)
     * }
     * ```
     */
    private fun appendDecodeLengthPrefixedUseCodecPayload(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthPrefixedUseCodecPayload,
    ) {
        val lengthVar =
            appendLengthPrefixedStringPrefixDecode(
                body = body,
                name = field.name,
                ownerSimpleName = field.ownerSimpleName,
                prefixWidth = field.prefixWidth,
                prefixWireOrder = field.prefixWireOrder,
            )
        val outerLimitVar = "__${field.name}OuterLimit"
        body.addStatement("val %L = buffer.limit()", outerLimitVar)
        body.addStatement("buffer.setLimit(buffer.position() + %L)", lengthVar)
        body.beginControlFlow("val %L = try", field.name)
        body.addStatement("%T.decode(buffer, context)", field.payloadCodecType)
        body.nextControlFlow("finally")
        body.addStatement("buffer.setLimit(%L)", outerLimitVar)
        body.endControlFlow()
    }

    /**
     * Shared decode body for the VBI-prefixed
     * list shape. Emitted by both `FieldSpec.LengthPrefixedUseCodecList`
     *  and the conditional-inner branch in
     * `appendDecodeConditional`. Five-step sequence:
     * capture outer limit → codec.decode VBI prefix → applyBound →
     * mutableListOf → try-while-finally restore outer limit.
     *
     * `listLocalName` is the variable that holds the decoded list. The
     * non-conditional path uses the field's own name (`<field>`); the
     * conditional path uses `<field>Value` because `<field>` is a
     * nullable-typed local that `appendDecodeConditional` sets via the
     * `if (predicate) { ... <listLocal> } else null` construction.
     *
     * `namespacePrefix` keys the local-variable names (`__<prefix>
     * OuterLimit`, `__<prefix>Length`). Field path passes the field
     * name; conditional path also passes the field name (so encode/
     * decode share scratch local names within the same conditional
     * slot).
     */
    private fun appendDecodeLengthPrefixedListBody(
        body: CodeBlock.Builder,
        spec: LengthPrefixedListSpec,
        listLocalName: String,
        namespacePrefix: String,
    ) {
        val outerLimitVar = "__${namespacePrefix}OuterLimit"
        val lengthVar = "__${namespacePrefix}Length"
        body.addStatement("val %L = buffer.limit()", outerLimitVar)
        body.addStatement("val %L = %T.decode(buffer, context)", lengthVar, spec.codecType)
        body.addStatement("%T.applyBound(buffer, %L)", spec.codecType, lengthVar)
        body.addStatement("val %L = mutableListOf<%T>()", listLocalName, spec.elementClassName)
        body.beginControlFlow("try")
        body.beginControlFlow("while (buffer.position() < buffer.limit())")
        body.addStatement("%L += %T.decode(buffer, context)", listLocalName, spec.elementCodecClassName)
        body.endControlFlow()
        body.nextControlFlow("finally")
        body.addStatement("buffer.setLimit(%L)", outerLimitVar)
        body.endControlFlow()
    }

    /**
     * Emit encode for `@LengthPrefixed
     * @UseCodec(C::class) val xs: List<E>`. Pre-measures the body byte
     * count via the element codec's `wireSize` (cast to `Exact`), writes
     * the prefix via the user codec's `encode`, then iterates and encodes
     * elements. BackPatch element codecs throw `ClassCastException` —
     * same fixture-design contract as `RemainingBytesProtocolMessageList`
     * and `LengthPrefixedMessage`.
     *
     * Generated shape:
     * ```
     * val __<name>BodyBytes = value.<name>.sumOf {
     *     (ElementCodec.wireSize(it, context) as WireSize.Exact).bytes
     * }
     * <codecType>.encode(buffer, __<name>BodyBytes.toUInt(), context)
     * for (__elem in value.<name>) {
     *     ElementCodec.encode(buffer, __elem, context)
     * }
     * ```
     */
    private fun appendEncodeLengthPrefixedUseCodecList(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthPrefixedUseCodecList,
    ) {
        appendEncodeLengthPrefixedListBody(
            body = body,
            spec = field.spec,
            accessor = "value.${field.name}",
            namespacePrefix = field.name,
        )
    }

    /**
     * Emit encode for `@LengthPrefixed
     * @UseCodec(C::class) val: T : Payload`. BackPatch shape mirroring
     * [appendLengthPrefixedStringEncode]: reserve prefix slot, run
     * `C.encode(buffer, value.<name>, context)` against the accumulating
     * buffer, measure the body byte count from the position delta,
     * patch the prefix in place, restore position past the body.
     */
    private fun appendEncodeLengthPrefixedUseCodecPayload(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthPrefixedUseCodecPayload,
    ) {
        val sizePosVar = "${field.name}SizePosition"
        val bodyStartVar = "${field.name}BodyStart"
        val endPosVar = "${field.name}EndPosition"
        val byteCountVar = "${field.name}ByteCount"
        body.addStatement("val %L = buffer.position()", sizePosVar)
        body.addStatement("buffer.position(%L + %L)", sizePosVar, field.prefixWidth)
        body.addStatement("val %L = buffer.position()", bodyStartVar)
        body.addStatement(
            "%T.encode(buffer, value.%L, context)",
            field.payloadCodecType,
            field.name,
        )
        body.addStatement("val %L = buffer.position()", endPosVar)
        body.addStatement("val %L = %L - %L", byteCountVar, endPosVar, bodyStartVar)
        if (field.prefixWidth < 4) {
            val maxValue = (1L shl (field.prefixWidth * 8)) - 1
            val widthName =
                when (field.prefixWidth) {
                    1 -> "Byte"
                    2 -> "Short"
                    else -> error("unreachable: prefixWidth must be 1, 2, or 4")
                }
            body.beginControlFlow("if (%L > %L)", byteCountVar, maxValue)
            body.addStatement(
                "throw %T(fieldPath = %S, reason = %P)",
                ENCODE_EXCEPTION_CN,
                "${field.ownerSimpleName}.${field.name}",
                "encoded payload byte length \${$byteCountVar} exceeds " +
                    "@LengthPrefixed(LengthPrefix.$widthName) max $maxValue",
            )
            body.endControlFlow()
        }
        body.addStatement("buffer.position(%L)", sizePosVar)
        val prefixVar = "${field.name}Prefix"
        body.addStatement("val %L = %L.toUInt()", prefixVar, byteCountVar)
        appendBufferPrefixEncode(body, prefixVar, field.prefixWidth, field.prefixWireOrder)
        body.addStatement("buffer.position(%L)", endPosVar)
    }

    /**
     * Shared encode body for the VBI-prefixed
     * list shape. Emitted by both `FieldSpec.LengthPrefixedUseCodecList`
     *  and `appendEncodeConditional`'s
     * `LengthPrefixedUseCodecList` branch.
     *
     * `accessor` is the read-side expression for the list — `value.
     * <name>` for the non-conditional path; the smart-cast non-null
     * local (`<name>Value`) for the conditional path. `namespacePrefix`
     * keys the scratch / body-bytes locals.
     *
     * Two encode paths, gated by `spec.elementIsBackPatch`:
     *
     * **Sealed elements** — variants commonly carry BackPatch-wireSize
     * fields (`@LengthPrefixed val: String`, `@When` trailers), so the
     * pre-measure `as WireSize.Exact` cast doesn't apply. Encode each
     * element into a scratch buffer first to capture the actual byte
     * count, then write the VBI prefix and bulk-copy:
     * ```
     * BufferFactory.Default.allocate(64, buffer.byteOrder).use { __<n>Scratch ->
     *     for (__elem in <accessor>) {
     *         ElementCodec.encode(__<n>Scratch, __elem, context)
     *     }
     *     val __<n>BodyBytes = __<n>Scratch.position()
     *     <codecType>.encode(buffer, __<n>BodyBytes.toUInt(), context)
     *     __<n>Scratch.resetForRead()
     *     buffer.write(__<n>Scratch)
     * }
     * ```
     * 64-byte starting allocation is a heuristic — `BufferFactory` grows
     * on demand for buffers that exceed it. Tunable per-field if a
     * measurable hot path emerges.
     *
     * **Data-class elements** — pre-measure body bytes via the element
     * codec's `wireSize as Exact`, write VBI prefix, iterate. BackPatch
     * elements throw `ClassCastException` — same fixture-design contract
     * as `RemainingBytesProtocolMessageList` and `LengthPrefixedMessage`.
     * Audit 2b notes the latent risk: a data-class element with a
     * `@LengthPrefixed val: String` field has BackPatch wireSize and
     * would CCE; no current fixture trips it because all sealed-parent
     * cases are routed through the scratch path.
     */
    private fun appendEncodeLengthPrefixedListBody(
        body: CodeBlock.Builder,
        spec: LengthPrefixedListSpec,
        accessor: String,
        namespacePrefix: String,
    ) {
        if (spec.elementIsBackPatch) {
            val scratchVar = "__${namespacePrefix}Scratch"
            val bodyBytesVar = "__${namespacePrefix}BodyBytes"
            body.beginControlFlow(
                "%T.%M.allocate(64, buffer.byteOrder).%M { %L ->",
                BUFFER_FACTORY_CN,
                BUFFER_FACTORY_DEFAULT_MN,
                BUFFER_USE_MN,
                scratchVar,
            )
            body.beginControlFlow("for (__elem in %L)", accessor)
            body.addStatement(
                "%T.encode(%L, __elem, context)",
                spec.elementCodecClassName,
                scratchVar,
            )
            body.endControlFlow()
            body.addStatement("val %L = %L.position()", bodyBytesVar, scratchVar)
            body.addStatement(
                "%T.encode(buffer, %L.toUInt(), context)",
                spec.codecType,
                bodyBytesVar,
            )
            body.addStatement("%L.resetForRead()", scratchVar)
            body.addStatement("buffer.write(%L)", scratchVar)
            body.endControlFlow()
        } else {
            val bodyBytesVar = "__${namespacePrefix}BodyBytes"
            body.addStatement(
                "val %L = %L.sumOf { (%T.wireSize(it, context) as %T.Exact).bytes }",
                bodyBytesVar,
                accessor,
                spec.elementCodecClassName,
                WIRE_SIZE_CN,
            )
            body.addStatement(
                "%T.encode(buffer, %L.toUInt(), context)",
                spec.codecType,
                bodyBytesVar,
            )
            body.beginControlFlow("for (__elem in %L)", accessor)
            body.addStatement("%T.encode(buffer, __elem, context)", spec.elementCodecClassName)
            body.endControlFlow()
        }
    }

    private fun appendBufferPrefixEncode(
        body: CodeBlock.Builder,
        prefixVar: String,
        prefixWidth: Int,
        wireOrder: Endianness,
    ) {
        if (prefixWidth == 1) {
            body.addStatement("buffer.writeUByte((%L and 0xFFu).toUByte())", prefixVar)
            return
        }
        val bigEndian =
            when (wireOrder) {
                Endianness.Big -> true
                Endianness.Little -> false
                Endianness.Default -> true
            }
        for (i in 0 until prefixWidth) {
            val shiftBits = if (bigEndian) (prefixWidth - 1 - i) * 8 else i * 8
            val expr =
                if (shiftBits == 0) {
                    "$prefixVar and 0xFFu"
                } else {
                    "($prefixVar shr $shiftBits) and 0xFFu"
                }
            body.addStatement("buffer.writeUByte((%L).toUByte())", expr)
        }
    }
}
