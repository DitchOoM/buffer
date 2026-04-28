package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.codec.processor.ir.Batch
import com.ditchoom.buffer.codec.processor.ir.Conditionality
import com.ditchoom.buffer.codec.processor.ir.FieldPlan
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.ditchoom.buffer.codec.processor.ir.LengthEncoding
import com.ditchoom.buffer.codec.processor.ir.LengthSource
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

/**
 * Phase 7 emitter for [Plan.Leaf] — plain protocol messages with a primary
 * constructor and a flat field list.
 *
 * Covers shapes 1-4 from the per-shape catalog:
 *  - fixed-width primary ctor (MqttFixedHeader, TLS ContentType)
 *  - fixed prefix + tail buffer slice (gRPC frame, TLS record)
 *  - conditional fields (MQTT v5 PubAck)
 *  - batched bit-extraction (MQTT v3 ConnectFlags)
 *
 * The emitter is structural; it does not handle every imaginable
 * [FieldStrategy] variant — see the per-strategy `decode`/`encode`/`size`
 * helpers for what is supported. Strategies the test fixtures don't exercise
 * fall through to a guard that emits a `// TODO` comment so the snapshot
 * regression catches drift.
 */
class LeafEmitter(
    private val registry: TypeRegistry,
) {
    fun emit(
        plan: Plan.Leaf,
        classType: ClassName,
        contextDecodes: List<ContextDecode> = emptyList(),
    ): FileSpec {
        val codecName = ClassName(classType.packageName, classType.simpleNames.joinToString("") + "Codec")
        val type =
            TypeSpec
                .objectBuilder(codecName)
                .addSuperinterface(Names.Codec.parameterizedBy(classType))

        // MIN_HEADER_BYTES — sum of fixed-width prefix bytes up to the first
        // variable / conditional field. For all-fixed messages, that's the full
        // wire size.
        val peekPlan = computePeekPlan(plan)
        val minHeader = peekPlan?.minHeader ?: computeMinHeader(plan)
        type.addProperty(
            PropertySpec
                .builder("MIN_HEADER_BYTES", INT, KModifier.PUBLIC, KModifier.CONST)
                .initializer("%L", minHeader)
                .build(),
        )

        type.addFunction(buildDecodeFun(plan, classType))
        type.addFunction(buildEncodeFun(plan, classType))
        type.addFunction(buildWireSizeFun(plan, classType))
        // Peek emission: when the wire is peekable we emit step-based peek; when
        // it isn't (VarInt, Varint-prefixed string, remaining-bytes, conditional),
        // we still emit constant `PeekResult.Size(minHeader)` for callers — the
        // legacy emitter omitted peek entirely in this case but the Slice 2 plan
        // accepts the simpler constant (the inherited `Codec.peekFrameSize`
        // default returns `NeedsMoreData`; emitting `Size(minHeader)` is at least
        // as informative for the all-fixed prefix portion).
        type.addFunction(buildPeekFrameSizeFun(peekPlan, minHeader))
        type.addFunction(buildSuspendingPeekFrameSizeFun(peekPlan, minHeader))

        // ContextDecode contracts (e.g. variants whose discriminator field comes
        // from `context[Key]` rather than the buffer).
        contextDecodes.forEach { type.addFunction(it.toFunSpec(classType)) }

        val fileBuilder =
            FileSpec
                .builder(codecName.packageName, codecName.simpleName)
                .addType(type.build())
        addExtensionImports(fileBuilder, plan)
        return fileBuilder.build()
    }

    /**
     * Mirrors `CodecGenerator.addExtensionImports` for the strategies the new
     * pipeline supports. The legacy emitter's import list is the byte-for-byte
     * baseline — adding the same names here keeps generated source diffs clean
     * when consumer suites flip from legacy to new.
     */
    private fun addExtensionImports(
        fileBuilder: FileSpec.Builder,
        plan: Plan.Leaf,
    ) {
        val fields = plan.fields
        val needsLengthPrefixed =
            fields.any { f ->
                val s = f.strategy
                s is FieldStrategy.StringField &&
                    s.length is LengthSource.Inline &&
                    s.length.encoding == LengthEncoding.Short
            }
        if (needsLengthPrefixed) {
            fileBuilder.addImport("com.ditchoom.buffer", "readLengthPrefixedUtf8String")
            fileBuilder.addImport("com.ditchoom.buffer", "writeLengthPrefixedUtf8String")
        }
        val needsVarint =
            fields.any { f ->
                val s = f.strategy
                s is FieldStrategy.VarInt ||
                    (s is FieldStrategy.StringField && s.length is LengthSource.Inline && s.length.encoding == LengthEncoding.Varint)
            }
        if (needsVarint) {
            fileBuilder.addImport("com.ditchoom.buffer", "readVariableByteInteger")
            fileBuilder.addImport("com.ditchoom.buffer", "writeVariableByteInteger")
            fileBuilder.addImport("com.ditchoom.buffer", "writeVariableByteIntegerLengthPrefixed")
            fileBuilder.addImport("com.ditchoom.buffer", "variableByteSizeInt")
        }
        val needsUtf8Length = fields.any { it.strategy is FieldStrategy.StringField }
        if (needsUtf8Length) {
            fileBuilder.addImport("com.ditchoom.buffer", "utf8Length")
        }
    }

    /**
     * Override how a single field's `decode`/`encode` is built. Used by the
     * sealed-emitter to inject discriminator-from-context reads on variants.
     */
    data class ContextDecode(
        val funSpec: FunSpec,
    ) {
        fun toFunSpec(
            @Suppress("UNUSED_PARAMETER") classType: ClassName,
        ): FunSpec = funSpec
    }

    // -----------------------------------------------------------------------
    // decode
    // -----------------------------------------------------------------------

    private fun buildDecodeFun(
        plan: Plan.Leaf,
        classType: ClassName,
    ): FunSpec {
        val fb =
            FunSpec
                .builder("decode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", Names.ReadBuffer)
                .addParameter("context", Names.DecodeContext)
                .returns(classType)

        val effectiveFields = effectiveFields(plan)

        // Fast path: single fixed-width primitive constructor argument with
        // empty `batches` lowers to a single-line `return ClassType(read…())`.
        if (plan.batches.isEmpty() && effectiveFields.size == 1 && effectiveFields[0].conditionality is Conditionality.Always) {
            val f = effectiveFields[0]
            val readExpr = decodeFieldInline(f)
            if (readExpr != null) {
                fb.addCode("return %T(%L)\n", classType, readExpr)
                return fb.build()
            }
        }

        // Process batches first.
        val batchFieldNames =
            plan.batches
                .flatMap { it.extractions }
                .map { it.targetField }
                .toSet()

        for (batch in plan.batches) {
            emitBatchDecode(fb, batch)
        }

        // Field-by-field decode.
        val ctorArgs = mutableListOf<String>()
        for (f in effectiveFields) {
            if (f.name in batchFieldNames) {
                ctorArgs += f.name
                continue
            }
            val cb = decodeStatement(f) ?: continue
            fb.addCode(cb)
            ctorArgs += f.name
        }

        // Build the constructor call with named args for readability.
        val ctorBuilder = CodeBlock.builder()
        ctorBuilder.add("return %T(", classType)
        ctorArgs.forEachIndexed { idx, name ->
            if (idx > 0) ctorBuilder.add(", ")
            ctorBuilder.add("%L = %L", name, name)
        }
        ctorBuilder.add(")\n")
        fb.addCode(ctorBuilder.build())
        return fb.build()
    }

    private fun emitBatchDecode(
        fb: FunSpec.Builder,
        batch: Batch,
    ) {
        // Read a primitive-of-given-width into `bits`, then extract each target
        // field via `((bits ushr shift) and mask)`.
        val readCall =
            when (batch.widthBytes) {
                1 -> "readUnsignedByte().toInt() and 0xFF"
                2 -> "readShort().toInt() and 0xFFFF"
                4 -> "readInt()"
                else -> error("Unsupported batch width: ${batch.widthBytes}")
            }
        fb.addCode("val bits = buffer.%L\n", readCall)
        for (ex in batch.extractions) {
            val expr =
                if (ex.shift == 0) {
                    "(bits and 0x${Integer.toHexString(ex.mask)}) != 0"
                } else {
                    "((bits ushr ${ex.shift}) and 0x${Integer.toHexString(ex.mask)})"
                }
            fb.addCode("val %L = %L\n", ex.targetField, expr)
        }
    }

    private fun decodeFieldInline(field: FieldPlan): CodeBlock? {
        if (field.conditionality !is Conditionality.Always) return null
        return when (val s = field.strategy) {
            is FieldStrategy.Primitive -> CodeBlock.of("%L", primitiveReadExpr(s.kind))
            else -> null
        }
    }

    /** Read expression for a primitive — Boolean lowers to `byte != 0.toByte()`. */
    private fun primitiveReadExpr(kind: com.ditchoom.buffer.codec.processor.ir.PrimitiveKind): String =
        when (kind) {
            com.ditchoom.buffer.codec.processor.ir.PrimitiveKind.Bool -> "buffer.readByte() != 0.toByte()"
            else -> "buffer.${FieldOps.readCall(kind)}()"
        }

    /** Write statement for a primitive — Boolean lowers to a conditional `writeByte`. */
    private fun primitiveWriteExpr(
        kind: com.ditchoom.buffer.codec.processor.ir.PrimitiveKind,
        valueExpr: String,
    ): String =
        when (kind) {
            com.ditchoom.buffer.codec.processor.ir.PrimitiveKind.Bool ->
                "buffer.writeByte(if ($valueExpr) 1.toByte() else 0.toByte())"
            else -> "buffer.${FieldOps.writeCall(kind)}($valueExpr)"
        }

    private fun decodeStatement(field: FieldPlan): CodeBlock? =
        when (val s = field.strategy) {
            is FieldStrategy.Primitive -> {
                val read = primitiveReadExpr(s.kind)
                wrapConditional(field, "val ${field.name} = $read\n")
            }

            is FieldStrategy.PayloadSlot ->
                when (s.length) {
                    is LengthSource.Remaining -> {
                        val read = "buffer.readBytes(buffer.remaining())"
                        wrapConditional(field, "val ${field.name} = $read\n")
                    }
                    is LengthSource.FromField -> {
                        val lengthFieldExpr = "${s.length.name}.toInt()"
                        wrapConditional(field, "val ${field.name} = buffer.readBytes($lengthFieldExpr)\n")
                    }
                    is LengthSource.Inline -> wrapConditional(field, "// TODO: inline-length payload not implemented\n")
                }

            is FieldStrategy.NestedMessage ->
                wrapConditional(field, "val ${field.name} = ${s.codec.canonicalName}.decode(buffer, context)\n")

            is FieldStrategy.External ->
                wrapConditional(field, "val ${field.name} = ${s.codec.canonicalName}.decode(buffer, context)\n")

            is FieldStrategy.DiscriminatorOwned ->
                CodeBlock.of(
                    "val %L = context[%T.DiscriminatorKey] ?: error(\"Discriminator missing from context\")\n",
                    field.name,
                    registry.codecOf(s.parentDispatchOn),
                )

            is FieldStrategy.Spi -> wrapConditional(field, "// TODO: SPI strategy not yet emitted\n")
            is FieldStrategy.VarInt ->
                wrapConditional(field, "val ${field.name} = buffer.readVariableByteInteger()\n")
            is FieldStrategy.StringField ->
                wrapConditional(field, "val ${field.name} = ${stringDecodeExpr(s.length)}\n")
            is FieldStrategy.Collection_ -> wrapConditional(field, "// TODO: Collection strategy not yet emitted\n")
        }

    /** Mirrors legacy `FieldCodeEmitter.readExpression` for `LengthPrefixedStringField` and friends. */
    private fun stringDecodeExpr(length: LengthSource): String =
        when (length) {
            is LengthSource.Inline ->
                when (length.encoding) {
                    LengthEncoding.Short -> "buffer.readLengthPrefixedUtf8String().second"
                    LengthEncoding.Byte ->
                        "run { val _len = buffer.readByte().toInt() and 0xFF; buffer.readString(_len) }"
                    LengthEncoding.Int ->
                        "run { val _len = buffer.readInt(); buffer.readString(_len) }"
                    LengthEncoding.Varint ->
                        "run { val _len = buffer.readVariableByteInteger(); buffer.readString(_len) }"
                }
            is LengthSource.FromField -> "buffer.readString(${length.name}.toInt())"
            is LengthSource.Remaining ->
                if (length.trailingBytes > 0) {
                    "buffer.readString(buffer.remaining() - ${length.trailingBytes})"
                } else {
                    "buffer.readString(buffer.remaining())"
                }
        }

    private fun wrapConditional(
        field: FieldPlan,
        innerText: String,
    ): CodeBlock {
        val cb = CodeBlock.builder()
        if (field.conditionality is Conditionality.WhenExpr) {
            cb.add("val %L = if (buffer.remaining() >= 1) {\n", field.name)
            // Strip leading `val name = ` and trailing newline from inner.
            val stripped = innerText.substringAfter(" = ").trimEnd()
            cb.indent()
            cb.add("%L\n", stripped)
            cb.unindent()
            cb.add("} else {\n")
            cb.indent()
            cb.add("null\n")
            cb.unindent()
            cb.add("}\n")
        } else {
            cb.add(innerText)
        }
        return cb.build()
    }

    // -----------------------------------------------------------------------
    // encode
    // -----------------------------------------------------------------------

    private fun buildEncodeFun(
        plan: Plan.Leaf,
        classType: ClassName,
    ): FunSpec {
        val fb =
            FunSpec
                .builder("encode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", Names.WriteBuffer)
                .addParameter(ParameterSpec.builder("value", classType).build())
                .addParameter("context", Names.EncodeContext)

        // Batches: write a single packed byte/short/int per batch, OR'ing the
        // contributions of each extraction back together.
        val batchedFieldNames =
            plan.batches
                .flatMap { it.extractions }
                .map { it.targetField }
                .toSet()
        for (batch in plan.batches) {
            val parts =
                batch.extractions.joinToString(" or ") { ex ->
                    val fieldRef = "value.${ex.targetField}"
                    if (ex.shift == 0) {
                        // Boolean → conditional masked bit; unsigned numeric →
                        // raw `and mask`. Either way: `(if (field) mask else 0)`
                        // works for booleans.
                        "(if ($fieldRef) 0x${Integer.toHexString(ex.mask)} else 0)"
                    } else {
                        "(($fieldRef.toInt() and 0x${Integer.toHexString(ex.mask)}) shl ${ex.shift})"
                    }
                }
            val writeName =
                when (batch.widthBytes) {
                    1 -> "writeUByte"
                    2 -> "writeUShort"
                    4 -> "writeUInt"
                    else -> error("Unsupported batch width: ${batch.widthBytes}")
                }
            val cast =
                when (batch.widthBytes) {
                    1 -> ".toUByte()"
                    2 -> ".toUShort()"
                    4 -> ".toUInt()"
                    else -> ""
                }
            fb.addCode("buffer.%L((%L)%L)\n", writeName, parts, cast)
        }

        // Conditional-cascade encoding: smart-cast on a temporary local at each
        // depth. We open and close depths around contiguous conditional blocks.
        var depth = 0
        for (field in effectiveFields(plan)) {
            if (field.name in batchedFieldNames) continue
            if (field.conditionality is Conditionality.WhenExpr) {
                fb.addCode(
                    "val %L = value.%L\n",
                    field.name,
                    field.name,
                )
                fb.addCode("if (%L != null) {\n", field.name)
                fb.indent { /* indent applied via add */ }
                depth++
            }
            val stmt = encodeStatement(field) ?: continue
            fb.addCode(stmt)
        }
        repeat(depth) {
            fb.addCode("}\n")
        }
        return fb.build()
    }

    private fun encodeStatement(field: FieldPlan): CodeBlock? =
        when (val s = field.strategy) {
            is FieldStrategy.Primitive -> {
                val ref =
                    if (field.conditionality is Conditionality.WhenExpr) field.name else "value.${field.name}"
                CodeBlock.of("%L\n", primitiveWriteExpr(s.kind, ref))
            }

            is FieldStrategy.PayloadSlot ->
                when (s.length) {
                    is LengthSource.Remaining ->
                        CodeBlock.of("buffer.writeBytes(value.%L)\n", field.name)
                    is LengthSource.FromField ->
                        CodeBlock.of("buffer.writeBytes(value.%L)\n", field.name)
                    is LengthSource.Inline -> CodeBlock.of("// TODO: inline-length payload\n")
                }

            is FieldStrategy.NestedMessage ->
                CodeBlock.of("%T.encode(buffer, value.%L, context)\n", s.codec, field.name)

            is FieldStrategy.External ->
                CodeBlock.of("%T.encode(buffer, value.%L, context)\n", s.codec, field.name)

            is FieldStrategy.DiscriminatorOwned -> null // dispatcher already wrote the bytes
            is FieldStrategy.Spi -> CodeBlock.of("// TODO: SPI strategy\n")
            is FieldStrategy.VarInt -> {
                val ref =
                    if (field.conditionality is Conditionality.WhenExpr) field.name else "value.${field.name}"
                CodeBlock.of("buffer.writeVariableByteInteger(%L)\n", ref)
            }
            is FieldStrategy.StringField -> {
                val ref =
                    if (field.conditionality is Conditionality.WhenExpr) field.name else "value.${field.name}"
                CodeBlock.of("%L\n", stringEncodeExpr(s.length, ref, field.name))
            }
            is FieldStrategy.Collection_ -> CodeBlock.of("// TODO: Collection strategy\n")
        }

    /** Mirrors legacy `FieldCodeEmitter.writeExpression` string branches. */
    private fun stringEncodeExpr(
        length: LengthSource,
        valueExpr: String,
        fieldName: String,
    ): String =
        when (length) {
            is LengthSource.Inline ->
                when (length.encoding) {
                    LengthEncoding.Short -> "buffer.writeLengthPrefixedUtf8String($valueExpr)"
                    LengthEncoding.Byte ->
                        "run { val _pos = buffer.position(); buffer.writeByte(0.toByte()); buffer.writeString($valueExpr); " +
                            "val _end = buffer.position(); val _len = _end - _pos - 1; " +
                            "buffer.position(_pos); buffer.writeByte(_len.toByte()); buffer.position(_end) }"
                    LengthEncoding.Int ->
                        "run { val _pos = buffer.position(); buffer.writeInt(0); buffer.writeString($valueExpr); " +
                            "val _end = buffer.position(); val _len = _end - _pos - 4; " +
                            "buffer.position(_pos); buffer.writeInt(_len); buffer.position(_end) }"
                    LengthEncoding.Varint -> {
                        // Mirrors legacy `emitInlineVarintLengthPrefixed` shape exactly so the emitted
                        // text is byte-for-byte identical when a class moves from legacy to new.
                        val suffix = if (fieldName.isEmpty()) "" else "_$fieldName"
                        val maxBytes = length.maxBytes.takeIf { it in 1..3 } ?: 0
                        val capCheck =
                            if (maxBytes in 1..3) {
                                "require(_l$suffix in 0..com.ditchoom.buffer.variableByteMax($maxBytes)) { " +
                                    "\"field '$fieldName' encoded length \$_l$suffix exceeds maxBytes=$maxBytes " +
                                    "(max value \${com.ditchoom.buffer.variableByteMax($maxBytes)})\" }; "
                            } else {
                                ""
                            }
                        "run { val _l$suffix = $valueExpr.utf8Length(); ${capCheck}buffer.writeVariableByteInteger(_l$suffix); buffer.writeString($valueExpr) }"
                    }
                }
            is LengthSource.FromField -> "buffer.writeString($valueExpr)"
            is LengthSource.Remaining -> "buffer.writeString($valueExpr)"
        }

    // -----------------------------------------------------------------------
    // wireSize
    // -----------------------------------------------------------------------

    private fun buildWireSizeFun(
        plan: Plan.Leaf,
        classType: ClassName,
    ): FunSpec {
        val fb =
            FunSpec
                .builder("wireSize")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter(ParameterSpec.builder("value", classType).build())
                .addParameter("context", Names.EncodeContext)
                .returns(INT)

        // Bit-extraction batches contribute a fixed `widthBytes` constant
        // regardless of how many target fields they fan out to. Add this fixed
        // contribution to the literal head; the field walker skips fields
        // already represented by a batch.
        val batchBytes = plan.batches.sumOf { it.widthBytes }
        val batchedFieldNames =
            plan.batches
                .flatMap { it.extractions }
                .map { it.targetField }
                .toSet()
        val fields = effectiveFields(plan).filter { it.name !in batchedFieldNames }
        val sizePlan =
            WireSizeEmitter.choose(
                fields = fields,
                fixedSizeOf = WireSizeEmitter::defaultFixedSizeOf,
                contributionFor = { sizeContribution(it) },
            )

        when (sizePlan) {
            is WireSizeEmitter.Plan.ConstLiteral ->
                fb.addCode("return %L\n", sizePlan.totalBytes + batchBytes)

            is WireSizeEmitter.Plan.FixedPlusOneVariable -> {
                val prefix = sizePlan.prefixBytes + batchBytes
                if (prefix == 0) {
                    fb.addCode("return %L\n", sizePlan.variableExpr)
                } else {
                    fb.addCode("return %L + %L\n", prefix, sizePlan.variableExpr)
                }
            }

            is WireSizeEmitter.Plan.Accumulator -> {
                fb.addCode("var size = %L\n", batchBytes)
                sizePlan.contributions.forEach { fb.addCode("size += %L\n", it) }
                fb.addCode("return size\n")
            }
        }
        return fb.build()
    }

    private fun sizeContribution(field: FieldPlan): CodeBlock {
        val raw =
            when (val s = field.strategy) {
                is FieldStrategy.Primitive -> CodeBlock.of("%L", s.wireBytes)
                is FieldStrategy.PayloadSlot -> CodeBlock.of("value.%L.remaining()", field.name)
                is FieldStrategy.NestedMessage ->
                    CodeBlock.of("%T.wireSize(value.%L, context)", s.codec, field.name)
                is FieldStrategy.External ->
                    CodeBlock.of("%T.wireSize(value.%L)", s.codec, field.name)
                is FieldStrategy.DiscriminatorOwned -> CodeBlock.of("0")
                is FieldStrategy.VarInt -> CodeBlock.of("variableByteSizeInt(value.%L)", field.name)
                is FieldStrategy.StringField ->
                    CodeBlock.of("%L", stringSizeExpr(s.length, "value.${field.name}"))
                else -> CodeBlock.of("/* TODO size of %L */ 0", field.name)
            }
        return if (field.conditionality is Conditionality.WhenExpr) {
            CodeBlock.of("(if (value.%L != null) %L else 0)", field.name, raw)
        } else {
            raw
        }
    }

    /** Mirrors legacy `WireSizeEmitter.wireSizeExpression` for string strategies. */
    private fun stringSizeExpr(
        length: LengthSource,
        valueExpr: String,
    ): String =
        when (length) {
            is LengthSource.Inline ->
                when (length.encoding) {
                    LengthEncoding.Byte -> "(1 + $valueExpr.utf8Length())"
                    LengthEncoding.Short -> "(2 + $valueExpr.utf8Length())"
                    LengthEncoding.Int -> "(4 + $valueExpr.utf8Length())"
                    LengthEncoding.Varint ->
                        "run { val _l = $valueExpr.utf8Length(); variableByteSizeInt(_l) + _l }"
                }
            is LengthSource.FromField -> "$valueExpr.utf8Length()"
            is LengthSource.Remaining -> "$valueExpr.utf8Length()"
        }

    // -----------------------------------------------------------------------
    // peekFrameSize
    // -----------------------------------------------------------------------

    private fun buildPeekFrameSizeFun(
        plan: PeekPlan?,
        minHeader: Int,
    ): FunSpec {
        val fb =
            FunSpec
                .builder("peekFrameSize")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("stream", Names.StreamProcessor)
                .addParameter("baseOffset", INT)
                .returns(Names.PeekResult)
        emitPeekBody(fb, plan, minHeader)
        return fb.build()
    }

    private fun buildSuspendingPeekFrameSizeFun(
        plan: PeekPlan?,
        minHeader: Int,
    ): FunSpec {
        val fb =
            FunSpec
                .builder("peekFrameSize")
                .addModifiers(KModifier.PUBLIC, KModifier.SUSPEND)
                .addParameter("stream", Names.SuspendingStreamProcessor)
                .addParameter(ParameterSpec.builder("baseOffset", INT).defaultValue("0").build())
                .returns(Names.PeekResult)
        emitPeekBody(fb, plan, minHeader)
        return fb.build()
    }

    private fun emitPeekBody(
        fb: FunSpec.Builder,
        plan: PeekPlan?,
        minHeader: Int,
    ) {
        if (plan == null || plan.allFixed) {
            fb.addCode("return %T(%L)\n", Names.PeekResultSize, minHeader)
            return
        }
        fb.addCode("var offset = baseOffset\n")
        for (step in plan.steps) {
            when (step) {
                is PeekStep.AddFixed -> if (step.bytes > 0) fb.addCode("offset += %L\n", step.bytes)
                is PeekStep.PeekUShortPrefix -> {
                    fb.addCode("if (stream.available() < offset + 2) return %T.NeedsMoreData\n", Names.PeekResult)
                    fb.addCode("val %L = stream.peekShort(offset).toInt() and 0xFFFF\n", step.varName)
                    fb.addCode("offset += 2\n")
                    fb.addCode("offset += %L\n", step.varName)
                }
                is PeekStep.PeekUBytePrefix -> {
                    fb.addCode("if (stream.available() < offset + 1) return %T.NeedsMoreData\n", Names.PeekResult)
                    fb.addCode("val %L = stream.peekByte(offset).toInt() and 0xFF\n", step.varName)
                    fb.addCode("offset += 1\n")
                    fb.addCode("offset += %L\n", step.varName)
                }
                is PeekStep.PeekIntPrefix -> {
                    fb.addCode("if (stream.available() < offset + 4) return %T.NeedsMoreData\n", Names.PeekResult)
                    fb.addCode("val %L = stream.peekInt(offset)\n", step.varName)
                    fb.addCode("offset += 4\n")
                    fb.addCode("offset += %L\n", step.varName)
                }
                is PeekStep.AddCapturedLen -> fb.addCode("offset += %L\n", step.varName)
            }
        }
        fb.addCode("return %T.Size(offset - baseOffset)\n", Names.PeekResult)
    }

    /**
     * Mirrors `PeekFrameSizeEmitter.generate` for the strategies the new pipeline
     * supports (Primitive + StringField + VarInt). Returns `null` when peek
     * generation is impossible — the legacy behaviour of "no peek functions
     * emitted" then applies.
     */
    private fun computePeekPlan(plan: Plan.Leaf): PeekPlan? {
        // Pre-scan: which fields are referenced by `@LengthFrom`? Their captured
        // length variables must be retained for the consuming field's add-step.
        val lengthFromTargets = mutableSetOf<String>()
        for (f in plan.fields) {
            val s = f.strategy
            if (s is FieldStrategy.StringField && s.length is LengthSource.FromField) {
                lengthFromTargets.add(s.length.name)
            }
        }
        val steps = mutableListOf<PeekStep>()
        var fixedAccum = 0
        for (b in plan.batches) fixedAccum += b.widthBytes
        val capturedLen = mutableMapOf<String, String>()
        val effective = effectiveFields(plan)
        val batchedNames =
            plan.batches
                .flatMap { it.extractions }
                .map { it.targetField }
                .toSet()
        for (f in effective) {
            if (f.name in batchedNames) continue
            if (f.conditionality !is Conditionality.Always) {
                // Conditional fields make exact frame size impossible.
                return null
            }
            val s = f.strategy
            when (s) {
                is FieldStrategy.Primitive -> {
                    if (f.name in lengthFromTargets) {
                        // Capture this primitive into a local for the @LengthFrom consumer.
                        steps.add(PeekStep.AddFixed(fixedAccum))
                        fixedAccum = 0
                        val varName = "_${f.name}"
                        when (s.wireBytes) {
                            1 -> steps.add(PeekStep.PeekUBytePrefix(varName, captureOnly = true))
                            2 -> steps.add(PeekStep.PeekUShortPrefix(varName, captureOnly = true))
                            4 -> steps.add(PeekStep.PeekIntPrefix(varName, captureOnly = true))
                            else -> return null
                        }
                        capturedLen[f.name] = varName
                        // PeekUShortPrefix etc. above already emit `offset += N` AND `offset += varName`.
                        // For capture-only we want only the byte advance, NOT the addVar; rework below.
                        return null // Capture+consume pairs aren't supported in the simple emit; bail out.
                    }
                    fixedAccum += s.wireBytes
                }
                is FieldStrategy.VarInt -> return null // VBI is variable-width; legacy omits peek too.
                is FieldStrategy.StringField -> {
                    when (val l = s.length) {
                        is LengthSource.Inline ->
                            when (l.encoding) {
                                LengthEncoding.Byte -> {
                                    steps.add(PeekStep.AddFixed(fixedAccum))
                                    fixedAccum = 0
                                    steps.add(PeekStep.PeekUBytePrefix("_${f.name}Len", captureOnly = false))
                                }
                                LengthEncoding.Short -> {
                                    steps.add(PeekStep.AddFixed(fixedAccum))
                                    fixedAccum = 0
                                    steps.add(PeekStep.PeekUShortPrefix("_${f.name}Len", captureOnly = false))
                                }
                                LengthEncoding.Int -> {
                                    steps.add(PeekStep.AddFixed(fixedAccum))
                                    fixedAccum = 0
                                    steps.add(PeekStep.PeekIntPrefix("_${f.name}Len", captureOnly = false))
                                }
                                LengthEncoding.Varint -> return null // legacy omits peek for VBI prefix
                            }
                        is LengthSource.FromField -> {
                            val cap = capturedLen[l.name] ?: return null
                            steps.add(PeekStep.AddFixed(fixedAccum))
                            fixedAccum = 0
                            steps.add(PeekStep.AddCapturedLen(cap))
                        }
                        is LengthSource.Remaining -> return null // legacy omits peek
                    }
                }
                is FieldStrategy.PayloadSlot,
                is FieldStrategy.NestedMessage,
                is FieldStrategy.External,
                is FieldStrategy.DiscriminatorOwned,
                is FieldStrategy.Spi,
                is FieldStrategy.Collection_,
                -> return null
            }
        }
        steps.add(PeekStep.AddFixed(fixedAccum))

        val allFixed = steps.all { it is PeekStep.AddFixed }
        val minHeader =
            if (allFixed) {
                steps.filterIsInstance<PeekStep.AddFixed>().sumOf { it.bytes }
            } else {
                // Up to and including the first variable peek prefix.
                var sum = 0
                for (step in steps) {
                    when (step) {
                        is PeekStep.AddFixed -> sum += step.bytes
                        is PeekStep.PeekUBytePrefix -> return PeekPlan(steps, sum + 1, allFixed = false)
                        is PeekStep.PeekUShortPrefix -> return PeekPlan(steps, sum + 2, allFixed = false)
                        is PeekStep.PeekIntPrefix -> return PeekPlan(steps, sum + 4, allFixed = false)
                        is PeekStep.AddCapturedLen -> {} // no min contribution
                    }
                }
                sum
            }
        return PeekPlan(steps.filter { it !is PeekStep.AddFixed || it.bytes > 0 }, minHeader, allFixed)
    }

    private data class PeekPlan(
        val steps: List<PeekStep>,
        val minHeader: Int,
        val allFixed: Boolean,
    )

    private sealed interface PeekStep {
        data class AddFixed(
            val bytes: Int,
        ) : PeekStep

        data class PeekUBytePrefix(
            val varName: String,
            val captureOnly: Boolean,
        ) : PeekStep

        data class PeekUShortPrefix(
            val varName: String,
            val captureOnly: Boolean,
        ) : PeekStep

        data class PeekIntPrefix(
            val varName: String,
            val captureOnly: Boolean,
        ) : PeekStep

        data class AddCapturedLen(
            val varName: String,
        ) : PeekStep
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private fun computeMinHeader(plan: Plan.Leaf): Int {
        // Sum of fixed widths up to first variable / conditional field.
        var total = 0
        for (b in plan.batches) total += b.widthBytes
        for (f in effectiveFields(plan)) {
            if (plan.batches.any { batch -> batch.extractions.any { it.targetField == f.name } }) continue
            if (f.conditionality !is Conditionality.Always) break
            val sz = WireSizeEmitter.defaultFixedSizeOf(f)
            if (sz < 0) break
            total += sz
        }
        return total
    }

    /** All fields except those replaced by a batched bit-extraction read. */
    private fun effectiveFields(plan: Plan.Leaf): List<FieldPlan> = plan.fields
}

private inline fun FunSpec.Builder.indent(block: () -> Unit) {
    // indent placeholder — `addCode` already line-formats. The function exists
    // to make the conditional encode loop above readable.
    block()
}
