package com.ditchoom.buffer.codec.processor

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec

internal val STREAM_PROCESSOR = ClassName("com.ditchoom.buffer.stream", "StreamProcessor")
internal val SUSPENDING_STREAM_PROCESSOR = ClassName("com.ditchoom.buffer.stream", "SuspendingStreamProcessor")
internal val PEEK_RESULT = ClassName("com.ditchoom.buffer.stream", "PeekResult")

/**
 * Generates `peekFrameSize(stream, baseOffset): Int?` and `MIN_HEADER_BYTES` for a codec.
 *
 * The generated method peeks at a [StreamProcessor] to determine the total number of bytes
 * the codec will read, without consuming any data. Returns null if insufficient data is
 * available to determine the frame size.
 */
internal object PeekFrameSizeEmitter {
    fun generate(fields: List<FieldInfo>): PeekGenResult? {
        // Pre-scan: find fields referenced by @LengthFrom
        val lengthFromTargets = mutableSetOf<String>()
        // Pre-scan: find fields referenced by @WhenTrue conditions (the root field name)
        val conditionTargets = mutableSetOf<String>()
        for (field in fields) {
            val strategy = field.strategy
            when {
                strategy is FieldReadStrategy.LengthFromStringField -> lengthFromTargets.add(strategy.field)
                strategy is FieldReadStrategy.PayloadField && strategy.lengthKind is LengthKind.FromField ->
                    lengthFromTargets.add(strategy.lengthKind.field)
                strategy is FieldReadStrategy.UseCodecField && strategy.lengthKind is LengthKind.FromField ->
                    lengthFromTargets.add(strategy.lengthKind.field)
            }
            val cond = field.condition
            if (cond is FieldCondition.WhenTrue) {
                conditionTargets.add(cond.expression.split(".")[0])
            }
        }

        val steps = mutableListOf<PeekStep>()
        var fixedAccum = 0
        val capturedLengths = mutableMapOf<String, String>()
        val fieldsByName = mutableMapOf<String, FieldInfo>()

        for (field in fields) {
            fieldsByName[field.name] = field

            // If this field is a condition target, emit a peek to capture its value
            if (field.name in conditionTargets) {
                steps.add(PeekStep.FlushFixed(fixedAccum))
                fixedAccum = 0
                val strategy = field.strategy
                when {
                    strategy is FieldReadStrategy.PrimitiveField && strategy.primitive == Primitive.BOOLEAN -> {
                        steps.add(PeekStep.PeekConditionBoolean("_cond_${field.name}"))
                    }
                    strategy is FieldReadStrategy.ValueClassField -> {
                        val inner = resolvePeekInfo(strategy) ?: return null
                        steps.add(
                            PeekStep.PeekConditionValueClass(
                                "_cond_${field.name}",
                                field.typeName,
                                inner,
                            ),
                        )
                    }
                    else -> return null // unsupported condition target type
                }
            }

            // @WhenRemaining fields make exact frame size calculation impossible
            if (field.condition is FieldCondition.WhenRemaining) return null

            // Handle @WhenTrue conditional fields
            if (field.condition != null) {
                val cond = field.condition as FieldCondition.WhenTrue
                val condExpr = resolveConditionExpr(cond.expression) ?: return null

                // Process the conditional field's strategy into inner steps
                val innerSteps = mutableListOf<PeekStep>()
                var innerFixed = 0
                innerFixed = processField(field, innerSteps, innerFixed, lengthFromTargets, capturedLengths)
                    ?: return null
                if (innerFixed > 0) innerSteps.add(PeekStep.FlushFixed(innerFixed))

                steps.add(PeekStep.FlushFixed(fixedAccum))
                fixedAccum = 0
                steps.add(
                    PeekStep.Conditional(
                        condExpr,
                        innerSteps.filter { it !is PeekStep.FlushFixed || it.bytes > 0 },
                    ),
                )
                continue
            }

            fixedAccum = processField(field, steps, fixedAccum, lengthFromTargets, capturedLengths) ?: return null
        }

        steps.add(PeekStep.FlushFixed(fixedAccum))

        val minHeaderBytes = computeMinHeaderBytes(steps, fields)
        val isAllFixed =
            steps.none {
                it is PeekStep.PeekPrefix ||
                    it is PeekStep.PeekLength ||
                    it is PeekStep.DelegateNested ||
                    it is PeekStep.Conditional
            }

        return PeekGenResult(
            minHeaderBytes = minHeaderBytes,
            steps = steps.filter { it !is PeekStep.FlushFixed || it.bytes > 0 },
            isAllFixed = isAllFixed,
        )
    }

    /**
     * Processes a single field into peek steps. Returns the updated fixedAccum, or null if generation
     * is impossible for this field.
     */
    private fun processField(
        field: FieldInfo,
        steps: MutableList<PeekStep>,
        fixedAccum: Int,
        lengthFromTargets: Set<String>,
        capturedLengths: MutableMap<String, String>,
    ): Int? {
        var accum = fixedAccum
        val strategy = field.strategy
        when {
            strategy is FieldReadStrategy.DiscriminatorField -> {
                // 0 wire bytes
            }

            strategy.fixedSize >= 0 -> {
                if (field.name in lengthFromTargets) {
                    val peekInfo = resolvePeekInfo(strategy) ?: return null
                    steps.add(PeekStep.FlushFixed(accum))
                    accum = 0
                    val varName = "_${field.name}"
                    steps.add(PeekStep.PeekLength(varName, peekInfo))
                    capturedLengths[field.name] = varName
                }
                accum += strategy.fixedSize
            }

            strategy is FieldReadStrategy.LengthPrefixedStringField -> {
                steps.add(PeekStep.FlushFixed(accum))
                accum = 0
                val prefixBytes = prefixByteCount(strategy.prefix)
                val varName = "_${field.name}Len"
                steps.add(PeekStep.PeekPrefix(varName, prefixBytes))
                accum += prefixBytes
                steps.add(PeekStep.AddVariable(varName))
            }

            strategy is FieldReadStrategy.LengthFromStringField -> {
                steps.add(PeekStep.FlushFixed(accum))
                accum = 0
                val varName = capturedLengths[strategy.field] ?: return null
                steps.add(PeekStep.AddVariable(varName))
            }

            strategy is FieldReadStrategy.PayloadField -> {
                when (val lk = strategy.lengthKind) {
                    is LengthKind.Prefixed -> {
                        steps.add(PeekStep.FlushFixed(accum))
                        accum = 0
                        val prefixBytes = prefixByteCount(lk.prefix)
                        val varName = "_${field.name}Len"
                        steps.add(PeekStep.PeekPrefix(varName, prefixBytes))
                        accum += prefixBytes
                        steps.add(PeekStep.AddVariable(varName))
                    }
                    is LengthKind.FromField -> {
                        steps.add(PeekStep.FlushFixed(accum))
                        accum = 0
                        val varName = capturedLengths[lk.field] ?: return null
                        steps.add(PeekStep.AddVariable(varName))
                    }
                    is LengthKind.Remaining -> return null
                }
            }

            strategy is FieldReadStrategy.UseCodecField -> {
                val lk = strategy.lengthKind ?: return null
                when (lk) {
                    is LengthKind.Prefixed -> {
                        steps.add(PeekStep.FlushFixed(accum))
                        accum = 0
                        val prefixBytes = prefixByteCount(lk.prefix)
                        val varName = "_${field.name}Len"
                        steps.add(PeekStep.PeekPrefix(varName, prefixBytes))
                        accum += prefixBytes
                        steps.add(PeekStep.AddVariable(varName))
                    }
                    is LengthKind.FromField -> {
                        steps.add(PeekStep.FlushFixed(accum))
                        accum = 0
                        val varName = capturedLengths[lk.field] ?: return null
                        steps.add(PeekStep.AddVariable(varName))
                    }
                    is LengthKind.Remaining -> return null
                }
            }

            strategy is FieldReadStrategy.NestedMessageField -> {
                steps.add(PeekStep.FlushFixed(accum))
                accum = 0
                steps.add(PeekStep.DelegateNested(strategy.codecName))
            }

            strategy is FieldReadStrategy.RemainingBytesStringField -> return null
            strategy is FieldReadStrategy.CollectionField -> return null
            strategy is FieldReadStrategy.Custom -> {
                if (strategy.descriptor.fixedSize < 0) return null
                accum += strategy.descriptor.fixedSize
            }

            else -> return null
        }
        return accum
    }

    /**
     * Resolves a @WhenTrue condition expression into a peek-based boolean expression string.
     *
     * Simple: "hasExtra" → "_cond_hasExtra"
     * Dotted: "flags.willFlag" → "_cond_flags.willFlag"
     *
     * The referenced variable is created by PeekConditionBoolean/PeekConditionValueClass steps.
     */
    private fun resolveConditionExpr(expression: String): String? {
        val parts = expression.split(".")
        return when (parts.size) {
            1 -> "_cond_${parts[0]}"
            2 -> "_cond_${parts[0]}.${parts[1]}"
            else -> null
        }
    }

    fun buildFunctions(
        result: PeekGenResult,
        implementsCodec: Boolean = true,
    ): List<FunSpec> =
        listOf(
            buildPeekFun(result, suspending = false, implementsCodec = implementsCodec),
            buildPeekFun(result, suspending = true),
        )

    fun buildMinHeaderProperty(result: PeekGenResult): PropertySpec =
        PropertySpec
            .builder("MIN_HEADER_BYTES", INT)
            .addModifiers(KModifier.CONST)
            .initializer("%L", result.minHeaderBytes)
            .build()

    private fun buildPeekFun(
        result: PeekGenResult,
        suspending: Boolean,
        implementsCodec: Boolean = true,
    ): FunSpec {
        val streamType = if (suspending) SUSPENDING_STREAM_PROCESSOR else STREAM_PROCESSOR
        val builder =
            FunSpec
                .builder("peekFrameSize")
                .addParameter("stream", streamType)

        if (suspending) {
            builder.addParameter(
                com.squareup.kotlinpoet.ParameterSpec
                    .builder("baseOffset", INT)
                    .defaultValue("0")
                    .build(),
            )
            builder.addModifiers(KModifier.SUSPEND)
        } else if (implementsCodec) {
            builder.addParameter("baseOffset", INT)
            builder.addModifiers(KModifier.OVERRIDE)
        } else {
            builder.addParameter(
                com.squareup.kotlinpoet.ParameterSpec
                    .builder("baseOffset", INT)
                    .defaultValue("0")
                    .build(),
            )
        }
        builder.returns(PEEK_RESULT)

        val code = CodeBlock.builder()

        if (result.isAllFixed) {
            val totalFixed = result.steps.filterIsInstance<PeekStep.FlushFixed>().sumOf { it.bytes }
            code.addStatement("return %T.Size(%L)", PEEK_RESULT, totalFixed)
        } else {
            code.addStatement("var offset = baseOffset")
            emitSteps(code, result.steps)
            code.addStatement("return %T.Size(offset - baseOffset)", PEEK_RESULT)
        }

        builder.addCode(code.build())
        return builder.build()
    }

    private fun emitSteps(
        code: CodeBlock.Builder,
        steps: List<PeekStep>,
    ) {
        for (step in steps) {
            when (step) {
                is PeekStep.FlushFixed -> {
                    if (step.bytes > 0) {
                        code.addStatement("offset += %L", step.bytes)
                    }
                }
                is PeekStep.PeekPrefix -> {
                    code.addStatement("if (stream.available() < offset + %L) return %T.NeedsMoreData", step.prefixBytes, PEEK_RESULT)
                    code.addStatement("val %L = %L", step.varName, peekUnsignedExpr("stream", "offset", step.prefixBytes))
                }
                is PeekStep.PeekLength -> {
                    code.addStatement("if (stream.available() < offset + %L) return %T.NeedsMoreData", step.info.wireBytes, PEEK_RESULT)
                    code.addStatement("val %L = %L", step.varName, peekExpr("stream", "offset", step.info))
                }
                is PeekStep.AddVariable -> {
                    code.addStatement("offset += %L", step.varName)
                }
                is PeekStep.DelegateNested -> {
                    val varPrefix = step.codecName.removeSuffix("Codec").replaceFirstChar { it.lowercase() }
                    code.addStatement(
                        "val _%LPeek = %L.peekFrameSize(stream, offset)",
                        varPrefix,
                        step.codecName,
                    )
                    code.addStatement(
                        "val _%LSize = (_%LPeek as? %T.Size)?.bytes ?: return %T.NeedsMoreData",
                        varPrefix,
                        varPrefix,
                        PEEK_RESULT,
                        PEEK_RESULT,
                    )
                    code.addStatement("offset += _%LSize", varPrefix)
                }
                is PeekStep.PeekConditionBoolean -> {
                    code.addStatement(
                        "if (stream.available() < offset + 1) return %T.NeedsMoreData",
                        PEEK_RESULT,
                    )
                    code.addStatement(
                        "val %L = stream.peekByte(offset).toInt() != 0",
                        step.varName,
                    )
                }
                is PeekStep.PeekConditionValueClass -> {
                    val wireBytes = step.peekInfo.wireBytes
                    code.addStatement("if (stream.available() < offset + %L) return %T.NeedsMoreData", wireBytes, PEEK_RESULT)
                    code.addStatement(
                        "val %L = %L(%L)",
                        step.varName,
                        step.wrapperType,
                        peekNativeExpr("stream", "offset", step.peekInfo),
                    )
                }
                is PeekStep.Conditional -> {
                    code.beginControlFlow("if (%L)", step.conditionExpr)
                    emitSteps(code, step.innerSteps)
                    code.endControlFlow()
                }
            }
        }
    }

    private fun computeMinHeaderBytes(
        steps: List<PeekStep>,
        fields: List<FieldInfo>,
    ): Int {
        val hasVariable =
            steps.any {
                it is PeekStep.PeekPrefix ||
                    it is PeekStep.PeekLength ||
                    it is PeekStep.DelegateNested ||
                    it is PeekStep.Conditional
            }
        if (!hasVariable) {
            return steps.filterIsInstance<PeekStep.FlushFixed>().sumOf { it.bytes }
        }

        var fixedSoFar = 0
        for (step in steps) {
            when (step) {
                is PeekStep.FlushFixed -> fixedSoFar += step.bytes
                is PeekStep.PeekPrefix -> return fixedSoFar + step.prefixBytes
                is PeekStep.PeekLength -> return fixedSoFar + step.info.wireBytes
                is PeekStep.DelegateNested -> return fixedSoFar + 1
                is PeekStep.PeekConditionBoolean -> return fixedSoFar + 1
                is PeekStep.PeekConditionValueClass -> return fixedSoFar + step.peekInfo.wireBytes
                is PeekStep.Conditional -> return fixedSoFar + 1 // condition field already counted
                is PeekStep.AddVariable -> {}
            }
        }
        return fixedSoFar
    }

    private fun resolvePeekInfo(strategy: FieldReadStrategy): PeekInfo? =
        when (strategy) {
            is FieldReadStrategy.PrimitiveField -> PeekInfo(strategy.wireBytes, strategy.primitive.signed)
            is FieldReadStrategy.ValueClassField -> resolvePeekInfo(strategy.innerStrategy)
            else -> null
        }

    /** Peek expression that returns the native type (e.g., UByte, UShort) for value class construction. */
    private fun peekNativeExpr(
        stream: String,
        offset: String,
        info: PeekInfo,
    ): String {
        val wireBytes = info.wireBytes
        val signed = info.signed
        return when {
            wireBytes == 1 && !signed -> "$stream.peekByte($offset).toUByte()"
            wireBytes == 1 && signed -> "$stream.peekByte($offset)"
            wireBytes == 2 && !signed -> "$stream.peekShort($offset).toUShort()"
            wireBytes == 2 && signed -> "$stream.peekShort($offset)"
            wireBytes == 4 && !signed -> "$stream.peekInt($offset).toUInt()"
            wireBytes == 4 && signed -> "$stream.peekInt($offset)"
            wireBytes == 8 && !signed -> "$stream.peekLong($offset).toULong()"
            wireBytes == 8 && signed -> "$stream.peekLong($offset)"
            else -> peekExpr(stream, offset, info) // fallback to Int expression
        }
    }

    private fun peekUnsignedExpr(
        stream: String,
        offset: String,
        prefixBytes: Int,
    ): String =
        when (prefixBytes) {
            1 -> "$stream.peekByte($offset).toInt() and 0xFF"
            2 -> "$stream.peekShort($offset).toInt() and 0xFFFF"
            4 -> "$stream.peekInt($offset)"
            else -> error("Unsupported prefix size: $prefixBytes")
        }

    private fun peekExpr(
        stream: String,
        offset: String,
        info: PeekInfo,
    ): String {
        val wireBytes = info.wireBytes
        val signed = info.signed
        return when {
            wireBytes == 1 && !signed -> "$stream.peekByte($offset).toInt() and 0xFF"
            wireBytes == 1 && signed -> "$stream.peekByte($offset).toInt()"
            wireBytes == 2 && !signed -> "$stream.peekShort($offset).toInt() and 0xFFFF"
            wireBytes == 2 && signed -> "$stream.peekShort($offset).toInt()"
            wireBytes == 4 -> "$stream.peekInt($offset)"
            wireBytes == 8 -> "$stream.peekLong($offset).toInt()"
            else -> buildCustomWidthPeek(stream, offset, wireBytes, signed)
        }
    }

    private fun buildCustomWidthPeek(
        stream: String,
        offset: String,
        wireBytes: Int,
        signed: Boolean,
    ): String {
        val parts =
            (0 until wireBytes).map { i ->
                val shift = (wireBytes - 1 - i) * 8
                val byte = "($stream.peekByte($offset + $i).toInt() and 0xFF)"
                if (shift > 0) "$byte shl $shift" else byte
            }
        val assembled = parts.joinToString(" or ")
        return if (signed && wireBytes < 4) {
            val shiftAmount = 32 - wireBytes * 8
            "(($assembled) shl $shiftAmount) shr $shiftAmount"
        } else {
            assembled
        }
    }

    private fun prefixByteCount(prefix: String): Int =
        when (prefix) {
            "Byte" -> 1
            "Short" -> 2
            "Int" -> 4
            else -> 2
        }
}

internal data class PeekGenResult(
    val minHeaderBytes: Int,
    val steps: List<PeekStep>,
    val isAllFixed: Boolean,
)

internal data class PeekInfo(
    val wireBytes: Int,
    val signed: Boolean,
)

internal sealed class PeekStep {
    data class FlushFixed(
        val bytes: Int,
    ) : PeekStep()

    data class PeekPrefix(
        val varName: String,
        val prefixBytes: Int,
    ) : PeekStep()

    data class PeekLength(
        val varName: String,
        val info: PeekInfo,
    ) : PeekStep()

    data class AddVariable(
        val varName: String,
    ) : PeekStep()

    data class DelegateNested(
        val codecName: String,
    ) : PeekStep()

    /** Peek a Boolean field for condition evaluation. */
    data class PeekConditionBoolean(
        val varName: String,
    ) : PeekStep()

    /** Peek a value class field for condition evaluation (e.g., ConnectFlags). */
    data class PeekConditionValueClass(
        val varName: String,
        val wrapperType: String,
        val peekInfo: PeekInfo,
    ) : PeekStep()

    /** Conditional block: only add inner steps if conditionExpr is true. */
    data class Conditional(
        val conditionExpr: String,
        val innerSteps: List<PeekStep>,
    ) : PeekStep()
}
