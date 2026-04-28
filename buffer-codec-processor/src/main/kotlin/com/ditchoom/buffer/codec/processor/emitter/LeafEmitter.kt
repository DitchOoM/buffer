package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.codec.processor.ir.Batch
import com.ditchoom.buffer.codec.processor.ir.Conditionality
import com.ditchoom.buffer.codec.processor.ir.FieldPlan
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
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
        val minHeader = computeMinHeader(plan)
        type.addProperty(
            PropertySpec
                .builder("MIN_HEADER_BYTES", INT, KModifier.PUBLIC, KModifier.CONST)
                .initializer("%L", minHeader)
                .build(),
        )

        type.addFunction(buildDecodeFun(plan, classType))
        type.addFunction(buildEncodeFun(plan, classType))
        type.addFunction(buildWireSizeFun(plan, classType))
        type.addFunction(buildPeekFrameSizeFun(plan, minHeader))
        type.addFunction(buildSuspendingPeekFrameSizeFun(plan, minHeader))

        // ContextDecode contracts (e.g. variants whose discriminator field comes
        // from `context[Key]` rather than the buffer).
        contextDecodes.forEach { type.addFunction(it.toFunSpec(classType)) }

        return FileSpec
            .builder(codecName.packageName, codecName.simpleName)
            .addType(type.build())
            .build()
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
            is FieldStrategy.Primitive -> CodeBlock.of("buffer.%L()", FieldOps.readCall(s.kind))
            else -> null
        }
    }

    private fun decodeStatement(field: FieldPlan): CodeBlock? =
        when (val s = field.strategy) {
            is FieldStrategy.Primitive -> {
                val read = "buffer.${FieldOps.readCall(s.kind)}()"
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
            is FieldStrategy.VarInt -> wrapConditional(field, "// TODO: VarInt strategy not yet emitted\n")
            is FieldStrategy.StringField -> wrapConditional(field, "// TODO: StringField strategy not yet emitted\n")
            is FieldStrategy.Collection_ -> wrapConditional(field, "// TODO: Collection strategy not yet emitted\n")
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
            is FieldStrategy.Primitive ->
                if (field.conditionality is Conditionality.WhenExpr) {
                    // After the smart-cast: `field.raw` if it's a value class? We
                    // don't know — emit a generic `field` reference; callers
                    // declaring conditional fields wrap them in a value class.
                    CodeBlock.of("buffer.%L(%L)\n", FieldOps.writeCall(s.kind), field.name)
                } else {
                    CodeBlock.of("buffer.%L(value.%L)\n", FieldOps.writeCall(s.kind), field.name)
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
            is FieldStrategy.VarInt -> CodeBlock.of("// TODO: VarInt strategy\n")
            is FieldStrategy.StringField -> CodeBlock.of("// TODO: StringField strategy\n")
            is FieldStrategy.Collection_ -> CodeBlock.of("// TODO: Collection strategy\n")
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
                else -> CodeBlock.of("/* TODO size of %L */ 0", field.name)
            }
        return if (field.conditionality is Conditionality.WhenExpr) {
            CodeBlock.of("(if (value.%L != null) %L else 0)", field.name, raw)
        } else {
            raw
        }
    }

    // -----------------------------------------------------------------------
    // peekFrameSize
    // -----------------------------------------------------------------------

    private fun buildPeekFrameSizeFun(
        @Suppress("UNUSED_PARAMETER") plan: Plan.Leaf,
        minHeader: Int,
    ): FunSpec =
        FunSpec
            .builder("peekFrameSize")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("stream", Names.StreamProcessor)
            .addParameter("baseOffset", INT)
            .returns(Names.PeekResult)
            .addCode("return %T(%L)\n", Names.PeekResultSize, minHeader)
            .build()

    private fun buildSuspendingPeekFrameSizeFun(
        @Suppress("UNUSED_PARAMETER") plan: Plan.Leaf,
        minHeader: Int,
    ): FunSpec =
        FunSpec
            .builder("peekFrameSize")
            .addModifiers(KModifier.PUBLIC, KModifier.SUSPEND)
            .addParameter("stream", Names.SuspendingStreamProcessor)
            .addParameter(ParameterSpec.builder("baseOffset", INT).defaultValue("0").build())
            .returns(Names.PeekResult)
            .addCode("return %T(%L)\n", Names.PeekResultSize, minHeader)
            .build()

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
