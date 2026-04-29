package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.codec.processor.ir.Batch
import com.ditchoom.buffer.codec.processor.ir.BooleanExpression
import com.ditchoom.buffer.codec.processor.ir.Conditionality
import com.ditchoom.buffer.codec.processor.ir.Direction
import com.ditchoom.buffer.codec.processor.ir.Endianness
import com.ditchoom.buffer.codec.processor.ir.FieldPlan
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.ditchoom.buffer.codec.processor.ir.LengthEncoding
import com.ditchoom.buffer.codec.processor.ir.LengthSource
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.PrimitiveKind
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.UNIT

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
        // Phase 9 Step 4 — Cap 2: top-level `@Payload` data class fan-out.
        // When the leaf declares one or more `@Payload` type parameters, the
        // standard `Codec<T>` interface can't be implemented (the unbound type
        // parameter has no type argument). Instead we emit a dedicated codec
        // object with `<P> decode<P>(buffer, context, payloadDecoder)` /
        // `<P> encode<P>(buffer, value, context, payloadEncoder)` /
        // `<P> wireSize<P>(value, context, payloadSize)` overloads, plus
        // `decodeFromContext` / `encodeFromContext` / `wireSizeFromContext`
        // bridges that read the lambdas from `Context.Key` objects emitted
        // alongside the codec. Mirrors legacy `CodecGenerator.buildPayloadCodecFile`.
        if (plan.payloadTypeParams.isNotEmpty()) {
            return emitPayloadCodec(plan, classType)
        }
        val codecName = ClassName(classType.packageName, classType.simpleNames.joinToString("") + "Codec")
        val type = TypeSpec.objectBuilder(codecName)

        // Slice 3: direction-aware superinterface. `Codec<T>` for bidirectional;
        // `Decoder<T>` for decode-only; `Encoder<T>` for encode-only. Mirrors
        // legacy `CodecGenerator.buildCodecFile` selection logic exactly.
        val superIface =
            when (plan.dir) {
                Direction.Bidirectional -> Names.Codec
                Direction.DecodeOnly -> Names.Decoder
                Direction.EncodeOnly -> Names.Encoder
            }
        type.addSuperinterface(superIface.parameterizedBy(classType))

        val canDecode = plan.dir != Direction.EncodeOnly
        val canEncode = plan.dir != Direction.DecodeOnly

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

        if (canDecode) type.addFunction(buildDecodeFun(plan, classType))
        if (canEncode) type.addFunction(buildEncodeFun(plan, classType))
        if (canEncode) type.addFunction(buildWireSizeFun(plan, classType))
        // Peek emission: peek belongs to FrameDetector which is part of `Codec<T>`
        // (and conceptually decoding). Emit the override only when the codec is
        // decode-capable. The non-suspending overload is `override fun` for
        // bidirectional Codec<T> (FrameDetector contract); for DecodeOnly the
        // superinterface is `Decoder<T>` which has no `peekFrameSize`, so we emit
        // a `public fun` with a `baseOffset = 0` default instead — matching legacy
        // `PeekFrameSizeEmitter.buildPeekFun(implementsCodec = false)`.
        if (canDecode) {
            val implementsCodec = plan.dir == Direction.Bidirectional
            type.addFunction(buildPeekFrameSizeFun(peekPlan, minHeader, implementsCodec))
            type.addFunction(buildSuspendingPeekFrameSizeFun(peekPlan, minHeader))
        }

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
        // Slice 3: a Collection_ field with `LengthSource.Inline.Varint` (the
        // MQTT v5 properties shape) needs the variable-byte helpers AND the
        // *LengthPrefixed write helper for byte-prefixed slice writes.
        val collectionVarint =
            fields.any { f ->
                val s = f.strategy
                s is FieldStrategy.Collection_ &&
                    s.length is LengthSource.Inline &&
                    s.length.encoding == LengthEncoding.Varint
            }
        val needsVarint =
            fields.any { f ->
                val s = f.strategy
                s is FieldStrategy.VarInt ||
                    (s is FieldStrategy.StringField && s.length is LengthSource.Inline && s.length.encoding == LengthEncoding.Varint)
            } ||
                collectionVarint
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
        // Slice 3: any multi-byte primitive marked Little-endian lowers to a
        // `Short.reverseBytes()` / `Int.reverseBytes()` / `Long.reverseBytes()` call,
        // mirroring legacy `Primitive.swappedReadExpr` / `swappedWriteExpr`. Add
        // the matching extension import. Single-byte kinds skip the swap and add
        // no import.
        val needsReverseBytes =
            fields.any { f ->
                val s = f.strategy
                s is FieldStrategy.Primitive &&
                    s.order == com.ditchoom.buffer.codec.processor.ir.Endianness.Little &&
                    !FieldOps.isSingleByte(s.kind)
            }
        if (needsReverseBytes) {
            fileBuilder.addImport("com.ditchoom.buffer", "reverseBytes")
        }
        // Slice 3: when a Collection_ field's element type lives in a different
        // package than the host codec, add an import for the element type's
        // simple name so `buildList<Element>` resolves. Mirrors legacy
        // `CodecGenerator.addExtensionImports` cross-package handling.
        val currentPackage = fileBuilder.packageName
        for (f in fields) {
            val s = f.strategy
            if (s is FieldStrategy.Collection_) {
                val elementFqn = s.elementCodec.elementType.canonical
                val elementPkg = elementFqn.substringBeforeLast('.', missingDelimiterValue = "")
                val elementSimple = elementFqn.substringAfterLast('.').substringBefore('$')
                if (elementPkg.isNotBlank() && elementPkg != currentPackage) {
                    fileBuilder.addImport(elementPkg, elementSimple)
                }
            }
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
            is FieldStrategy.Primitive -> CodeBlock.of("%L", primitiveReadExpr(s.kind, s.order, s.wireBytes))
            // Phase 9 Step 3: value-class auto-detected field — read the inner primitive
            // and wrap with the value-class constructor.
            is FieldStrategy.ValueClass -> {
                val inner = s.inner
                if (inner is FieldStrategy.Primitive) {
                    val read = primitiveReadExpr(inner.kind, inner.order, inner.wireBytes)
                    CodeBlock.of("%L(%L)", s.valueClassFqn.canonical, read)
                } else {
                    null
                }
            }
            else -> null
        }
    }

    /**
     * Read expression for a primitive — Boolean lowers to `byte != 0.toByte()`.
     * Cap 4: when `wireBytes` differs from the natural width for the kind, the
     * call delegates to [FieldOps.readExpr] with the custom-width overload
     * (shift-and-mask sequence).
     */
    private fun primitiveReadExpr(
        kind: PrimitiveKind,
        order: Endianness = Endianness.Big,
        wireBytes: Int = FieldOps.naturalWireBytes(kind),
    ): String =
        when (kind) {
            PrimitiveKind.Bool -> "buffer.readByte() != 0.toByte()"
            else -> FieldOps.readExpr(kind, order, wireBytes)
        }

    /** Write statement for a primitive — Boolean lowers to a conditional `writeByte`. */
    private fun primitiveWriteExpr(
        kind: PrimitiveKind,
        valueExpr: String,
        order: Endianness = Endianness.Big,
        wireBytes: Int = FieldOps.naturalWireBytes(kind),
    ): String =
        when (kind) {
            PrimitiveKind.Bool ->
                "buffer.writeByte(if ($valueExpr) 1.toByte() else 0.toByte())"
            else -> FieldOps.writeExpr(kind, order, wireBytes, valueExpr)
        }

    private fun decodeStatement(field: FieldPlan): CodeBlock? =
        when (val s = field.strategy) {
            is FieldStrategy.Primitive -> {
                val read = primitiveReadExpr(s.kind, s.order, s.wireBytes)
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
                wrapConditional(
                    field,
                    "val ${field.name} = ${nestedDecodeExpr(s.codec.canonicalName, s.length)}\n",
                )

            is FieldStrategy.External ->
                wrapConditional(
                    field,
                    "val ${field.name} = ${nestedDecodeExpr(s.codec.canonicalName, s.length)}\n",
                )

            is FieldStrategy.DiscriminatorOwned ->
                CodeBlock.of(
                    "val %L = context[%T.DiscriminatorKey] ?: error(\"Discriminator missing from context\")\n",
                    field.name,
                    registry.codecOf(s.parentDispatchOn),
                )

            // SPI: `descriptor.decodeRaw` is the inline read expression (e.g.
            // `MyCodec.decode(buffer, context)`). For legacy single-string descriptors,
            // `decodeRaw` falls back to `raw` so existing fixtures keep working without
            // changes. Mirrors legacy `FieldReadStrategy.Custom` lowering — the provider
            // produces the call site's text directly and the emitter substitutes it.
            is FieldStrategy.Spi ->
                wrapConditional(field, "val ${field.name} = ${s.descriptor.decodeRaw}\n")
            is FieldStrategy.VarInt ->
                wrapConditional(field, "val ${field.name} = buffer.readVariableByteInteger()\n")
            is FieldStrategy.StringField ->
                wrapConditional(field, "val ${field.name} = ${stringDecodeExpr(s.length)}\n")
            is FieldStrategy.Collection_ ->
                wrapConditional(field, "val ${field.name} = ${collectionDecodeExpr(s)}\n")

            // Phase 9 Step 3: value-class auto-detected — read inner primitive and
            // wrap in the value-class constructor.
            is FieldStrategy.ValueClass -> {
                val inner = s.inner
                if (inner is FieldStrategy.Primitive) {
                    val read = primitiveReadExpr(inner.kind, inner.order, inner.wireBytes)
                    wrapConditional(field, "val ${field.name} = ${s.valueClassFqn.canonical}($read)\n")
                } else {
                    wrapConditional(field, "// TODO: non-primitive inner strategy on ValueClass not implemented\n")
                }
            }
        }

    /**
     * Mirrors legacy `FieldCodeEmitter.readCollectionExpression` byte-for-byte for the
     * length sources the new pipeline supports:
     *
     *  - [LengthSource.Inline] — the MQTT v5 properties shape (Varint byte-count prefix +
     *    slice). Also Byte/Short/Int prefix variants.
     *  - [LengthSource.FromField] — count prefix from a sibling field (e.g. SubscribeByCount).
     *  - [LengthSource.Remaining] — loop until the buffer slice is empty (e.g. SubscribeRequest).
     */
    private fun collectionDecodeExpr(s: FieldStrategy.Collection_): String {
        val codec = s.elementCodec.codec.canonicalName
        val typeSimple =
            s.elementCodec.elementType.canonical
                .substringAfterLast('.')
        return when (val l = s.length) {
            is LengthSource.FromField ->
                "buildList<$typeSimple> { repeat(${l.name}.toInt()) { add($codec.decode(buffer, context)) } }"
            is LengthSource.Remaining ->
                if (l.trailingBytes > 0) {
                    "buildList<$typeSimple> { while (buffer.remaining() > ${l.trailingBytes}) " +
                        "{ add($codec.decode(buffer, context)) } }"
                } else {
                    "buildList<$typeSimple> { while (buffer.remaining() > 0) { add($codec.decode(buffer, context)) } }"
                }
            is LengthSource.Inline -> {
                val readLen =
                    when (l.encoding) {
                        LengthEncoding.Byte -> "buffer.readByte().toInt() and 0xFF"
                        LengthEncoding.Short -> "buffer.readUnsignedShort().toInt()"
                        LengthEncoding.Int -> "buffer.readInt()"
                        LengthEncoding.Varint -> "buffer.readVariableByteInteger()"
                    }
                "run { val _len = $readLen; val _slice = buffer.readBytes(_len); " +
                    "buildList<$typeSimple> { while (_slice.remaining() > 0) " +
                    "{ add($codec.decode(_slice, context)) } } }"
            }
        }
    }

    /**
     * Cap 3 — `@LengthPrefixed` / `@LengthFrom` / `@RemainingBytes` on
     * NestedMessage / External. Mirrors legacy
     * `FieldCodeEmitter.readNestedWithLengthExpression` byte-for-byte.
     *
     * When [length] is null, defers to the nested codec's own framing
     * (the existing "bare" decode shape).
     */
    private fun nestedDecodeExpr(
        codec: String,
        length: LengthSource?,
    ): String {
        if (length == null) return "$codec.decode(buffer, context)"
        return when (length) {
            is LengthSource.Inline -> {
                val readLen =
                    when (length.encoding) {
                        LengthEncoding.Byte -> "buffer.readByte().toInt() and 0xFF"
                        LengthEncoding.Short -> "buffer.readShort().toInt() and 0xFFFF"
                        LengthEncoding.Int -> "buffer.readInt()"
                        LengthEncoding.Varint -> "buffer.readVariableByteInteger()"
                    }
                "run { val _len = $readLen; $codec.decode(buffer.readBytes(_len), context) }"
            }
            is LengthSource.FromField ->
                "$codec.decode(buffer.readBytes(${length.name}.toInt()), context)"
            is LengthSource.Remaining ->
                if (length.trailingBytes > 0) {
                    "$codec.decode(buffer.readBytes(buffer.remaining() - ${length.trailingBytes}), context)"
                } else {
                    "$codec.decode(buffer.readBytes(buffer.remaining()), context)"
                }
        }
    }

    /**
     * Cap 3 — encode side: two-pass (`wireSize → writePrefix → writeBody`)
     * for fixed-width prefixes; for varint the legacy
     * `emitInlineVarintLengthPrefixed` shape is used.
     */
    private fun nestedEncodeExpr(
        codec: String,
        length: LengthSource?,
        valueExpr: String,
        fieldName: String,
    ): String {
        if (length == null) return "$codec.encode(buffer, $valueExpr, context)"
        val encodeBody = "$codec.encode(buffer, $valueExpr, context)"
        return when (length) {
            is LengthSource.Inline -> {
                when (length.encoding) {
                    LengthEncoding.Byte ->
                        "run { val _pos = buffer.position(); buffer.writeByte(0.toByte()); " +
                            "$encodeBody; val _end = buffer.position(); val _len = _end - _pos - 1; " +
                            "buffer.position(_pos); buffer.writeByte(_len.toByte()); buffer.position(_end) }"
                    LengthEncoding.Short ->
                        "run { val _pos = buffer.position(); buffer.writeShort(0.toShort()); " +
                            "$encodeBody; val _end = buffer.position(); val _len = _end - _pos - 2; " +
                            "buffer.position(_pos); buffer.writeShort(_len.toShort()); buffer.position(_end) }"
                    LengthEncoding.Int ->
                        "run { val _pos = buffer.position(); buffer.writeInt(0); " +
                            "$encodeBody; val _end = buffer.position(); val _len = _end - _pos - 4; " +
                            "buffer.position(_pos); buffer.writeInt(_len); buffer.position(_end) }"
                    LengthEncoding.Varint -> {
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
                        "run { val _l$suffix = $codec.wireSize($valueExpr, context); " +
                            "${capCheck}buffer.writeVariableByteInteger(_l$suffix); $encodeBody }"
                    }
                }
            }
            // FromField + Remaining encode without writing a prefix — the length
            // is already on the wire via the sibling field or is implicit.
            is LengthSource.FromField,
            is LengthSource.Remaining,
            -> encodeBody
        }
    }

    /**
     * Cap 3 — wireSize site: a length-framed nested field contributes the
     * prefix bytes plus the nested wireSize. Bare nested (no length) just
     * contributes the nested wireSize.
     */
    private fun nestedSizeExpr(
        codec: String,
        length: LengthSource?,
        valueExpr: String,
    ): String {
        val nestedSize = "$codec.wireSize($valueExpr, context)"
        if (length == null) return nestedSize
        return when (length) {
            is LengthSource.Inline -> {
                when (length.encoding) {
                    LengthEncoding.Byte -> "(1 + $nestedSize)"
                    LengthEncoding.Short -> "(2 + $nestedSize)"
                    LengthEncoding.Int -> "(4 + $nestedSize)"
                    LengthEncoding.Varint -> {
                        // Varint prefix size depends on the nested wireSize itself.
                        "run { val _b = $nestedSize; variableByteSizeInt(_b) + _b }"
                    }
                }
            }
            is LengthSource.FromField,
            is LengthSource.Remaining,
            -> nestedSize
        }
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
        val cond = field.conditionality
        if (cond is Conditionality.WhenExpr) {
            val expr = lowerBoolExprDecode(cond.expr)
            cb.add("val %L = if (%L) {\n", field.name, expr)
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

    /**
     * Lower [BooleanExpression] to a Kotlin expression suitable for the **decode**
     * site, where `RemainingGte` resolves against `buffer.remaining()` and
     * `FieldRef` paths resolve against locals already-declared earlier in the
     * decode body (PhaseB ensures forward references aren't possible at
     * decode-time, since fields decode in declaration order).
     */
    private fun lowerBoolExprDecode(expr: BooleanExpression): String =
        when (expr) {
            is BooleanExpression.RemainingGte -> "buffer.remaining() >= ${expr.min}"
            is BooleanExpression.FieldRef -> expr.path.joinToString(".")
            is BooleanExpression.Eq ->
                "${lowerBoolExprDecode(expr.lhs)} == ${expr.rhs}"
            is BooleanExpression.Gt ->
                "${lowerBoolExprDecode(expr.lhs)} > ${expr.rhs}"
        }

    /**
     * Lower [BooleanExpression] to a Kotlin expression suitable for the
     * **encode** / **wireSize** site, where `FieldRef` paths walk from
     * `value.<head>.<rest>...`.
     */
    private fun lowerBoolExprEncode(expr: BooleanExpression): String =
        when (expr) {
            is BooleanExpression.RemainingGte ->
                // Encode side has no `remaining()` semantics; cascading WhenRemaining
                // tails encode via the smart-cast local pattern (see buildEncodeFun).
                // This branch is retained for completeness — it can only arise in
                // an emit path that doesn't go through buildEncodeFun (none today).
                "true"
            is BooleanExpression.FieldRef -> "value.${expr.path.joinToString(".")}"
            is BooleanExpression.Eq ->
                "${lowerBoolExprEncode(expr.lhs)} == ${expr.rhs}"
            is BooleanExpression.Gt ->
                "${lowerBoolExprEncode(expr.lhs)} > ${expr.rhs}"
        }

    /**
     * True when the field's `WhenExpr` conditionality lowers the field to a
     * **nullable** local — i.e. the field truly couldn't be present on the wire
     * and the constructor argument is nullable. `RemainingGte` is the canonical
     * trailing-tail conditional (MQTT v5 PubAck reasonCode); `FieldRef` plus
     * `Eq`/`Gt` paths share the same nullable-tail semantics.
     *
     * The encode side smart-casts on a `value.field` local; the decode side
     * already returns `null` from the `else` branch.
     */
    private fun isNullableConditional(field: FieldPlan): Boolean = field.conditionality is Conditionality.WhenExpr

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

        // Conditional encoding has two modes (mirrors legacy CodecGenerator's split):
        //
        //  * `WhenExpr.RemainingGte` (lowered from `@WhenRemaining`) — cascades.
        //    The trailing tail of fields is gated by a chain of nested `if (X != null)`
        //    blocks: when an earlier tail field is null, every following one also
        //    skips. Mirrors `emitWhenRemainingEncode` byte-for-byte. The legacy
        //    grammar is "cascade applies only to a contiguous tail of WhenRemaining
        //    fields"; PhaseB's `WhenRemaining not at tail` validation already
        //    rejects interleaved cases.
        //
        //  * Other `WhenExpr` (path-based `FieldRef`, `Eq`, `Gt`) — independent
        //    blocks. Each conditional opens and closes its own `if (local != null) { ... }`
        //    around the field's encode call. The unconditional fields between two
        //    conditionals encode at depth 0.
        //
        // The split lets path-based conditionals interleave with unconditional
        // fields (e.g. `ConditionalBatchTestMessage` has `cond1`, `cond2`,
        // `trailer` where `trailer` is unconditional and must always write).
        val effective = effectiveFields(plan).filter { it.name !in batchedFieldNames }
        val whenRemainingTail = mutableListOf<FieldPlan>()
        // Walk back from the tail collecting contiguous WhenRemaining fields.
        for (i in effective.indices.reversed()) {
            val f = effective[i]
            val cond = f.conditionality
            if (cond is Conditionality.WhenExpr && cond.expr is BooleanExpression.RemainingGte) {
                whenRemainingTail.add(0, f)
            } else {
                break
            }
        }
        val whenRemainingNames = whenRemainingTail.map { it.name }.toSet()
        for (field in effective) {
            if (field.name in whenRemainingNames) continue
            val cond = field.conditionality
            if (cond is Conditionality.WhenExpr) {
                // Independent block: bind a smart-cast local, null-check, encode.
                fb.addCode("val %L = value.%L\n", field.name, field.name)
                fb.addCode("if (%L != null) {\n", field.name)
                val stmt = encodeStatement(field)
                if (stmt != null) fb.addCode(stmt)
                fb.addCode("}\n")
            } else {
                val stmt = encodeStatement(field) ?: continue
                fb.addCode(stmt)
            }
        }
        // Tail cascade for `RemainingGte` chain.
        if (whenRemainingTail.isNotEmpty()) {
            for (f in whenRemainingTail) {
                fb.addCode("val %L = value.%L\n", f.name, f.name)
                fb.addCode("if (%L != null) {\n", f.name)
                val stmt = encodeStatement(f)
                if (stmt != null) fb.addCode(stmt)
            }
            repeat(whenRemainingTail.size) { fb.addCode("}\n") }
        }
        return fb.build()
    }

    private fun encodeStatement(field: FieldPlan): CodeBlock? =
        when (val s = field.strategy) {
            is FieldStrategy.Primitive -> {
                val ref =
                    if (isNullableConditional(field)) field.name else "value.${field.name}"
                CodeBlock.of("%L\n", primitiveWriteExpr(s.kind, ref, s.order, s.wireBytes))
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
                CodeBlock.of(
                    "%L\n",
                    nestedEncodeExpr(s.codec.canonicalName, s.length, "value.${field.name}", field.name),
                )

            is FieldStrategy.External ->
                CodeBlock.of(
                    "%L\n",
                    nestedEncodeExpr(s.codec.canonicalName, s.length, "value.${field.name}", field.name),
                )

            is FieldStrategy.DiscriminatorOwned -> null // dispatcher already wrote the bytes
            is FieldStrategy.Spi -> {
                // SPI write descriptors lower from `descriptor.encodeRaw`. Asymmetric
                // SPI providers (decode side calls a `read*` extension, encode side a
                // `write*` extension) supply distinct decode/encode strings; legacy
                // single-string descriptors back both via `raw`.
                CodeBlock.of("%L\n", s.descriptor.encodeRaw)
            }
            is FieldStrategy.VarInt -> {
                val ref =
                    if (isNullableConditional(field)) field.name else "value.${field.name}"
                CodeBlock.of("buffer.writeVariableByteInteger(%L)\n", ref)
            }
            is FieldStrategy.StringField -> {
                val ref =
                    if (isNullableConditional(field)) field.name else "value.${field.name}"
                CodeBlock.of("%L\n", stringEncodeExpr(s.length, ref, field.name))
            }
            is FieldStrategy.Collection_ -> {
                val ref =
                    if (isNullableConditional(field)) field.name else "value.${field.name}"
                CodeBlock.of("%L\n", collectionEncodeExpr(s, ref))
            }

            // Phase 9 Step 3: value-class auto-detected — read inner property off the
            // wrapper, then emit the inner-primitive's write. For nullable conditional
            // fields, the smart-cast local already holds the wrapper so we still walk
            // through `<local>.<innerPropertyName>`.
            is FieldStrategy.ValueClass -> {
                val inner = s.inner
                val baseRef =
                    if (isNullableConditional(field)) field.name else "value.${field.name}"
                val innerRef = "$baseRef.${s.innerPropertyName}"
                if (inner is FieldStrategy.Primitive) {
                    CodeBlock.of("%L\n", primitiveWriteExpr(inner.kind, innerRef, inner.order, inner.wireBytes))
                } else {
                    CodeBlock.of("// TODO: non-primitive inner strategy on ValueClass not implemented\n")
                }
            }
        }

    /**
     * Mirrors legacy `FieldCodeEmitter.writeCollectionExpression` byte-for-byte for
     * the supported length sources.
     */
    private fun collectionEncodeExpr(
        s: FieldStrategy.Collection_,
        valueExpr: String,
    ): String {
        val codec = s.elementCodec.codec.canonicalName
        return when (val l = s.length) {
            is LengthSource.FromField,
            is LengthSource.Remaining,
            -> "$valueExpr.forEach { $codec.encode(buffer, it, context) }"

            is LengthSource.Inline -> {
                val encodeBody = "$valueExpr.forEach { $codec.encode(buffer, it, context) }"
                when (l.encoding) {
                    LengthEncoding.Varint -> {
                        // `_l` short-name matches the legacy `emitInlineVarintLengthPrefixed` shape
                        // with empty fieldName suffix. The legacy emitter passes `fieldName` so the
                        // suffix becomes `_$fieldName`; for collections the legacy site uses
                        // the field name too — match that exactly.
                        val maxBytes = l.maxBytes.takeIf { it in 1..3 } ?: 0
                        val capCheck =
                            if (maxBytes in 1..3) {
                                "require(_l in 0..com.ditchoom.buffer.variableByteMax($maxBytes)) { " +
                                    "\"field '' encoded length \$_l exceeds maxBytes=$maxBytes " +
                                    "(max value \${com.ditchoom.buffer.variableByteMax($maxBytes)})\" }; "
                            } else {
                                ""
                            }
                        "run { val _l = $valueExpr.sumOf { $codec.wireSize(it, context) }; " +
                            "${capCheck}buffer.writeVariableByteInteger(_l); $encodeBody }"
                    }
                    LengthEncoding.Byte ->
                        "run { val _pos = buffer.position(); buffer.writeByte(0.toByte()); $encodeBody; " +
                            "val _end = buffer.position(); val _len = _end - _pos - 1; " +
                            "buffer.position(_pos); buffer.writeByte(_len.toByte()); buffer.position(_end) }"
                    LengthEncoding.Short ->
                        "run { val _pos = buffer.position(); buffer.writeShort(0.toShort()); $encodeBody; " +
                            "val _end = buffer.position(); val _len = _end - _pos - 2; " +
                            "buffer.position(_pos); buffer.writeShort(_len.toShort()); buffer.position(_end) }"
                    LengthEncoding.Int ->
                        "run { val _pos = buffer.position(); buffer.writeInt(0); $encodeBody; " +
                            "val _end = buffer.position(); val _len = _end - _pos - 4; " +
                            "buffer.position(_pos); buffer.writeInt(_len); buffer.position(_end) }"
                }
            }
        }
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
                    CodeBlock.of(
                        "%L",
                        nestedSizeExpr(s.codec.canonicalName, s.length, "value.${field.name}"),
                    )
                is FieldStrategy.External ->
                    CodeBlock.of(
                        "%L",
                        nestedSizeExpr(s.codec.canonicalName, s.length, "value.${field.name}"),
                    )
                is FieldStrategy.DiscriminatorOwned -> CodeBlock.of("0")
                is FieldStrategy.VarInt -> CodeBlock.of("variableByteSizeInt(value.%L)", field.name)
                is FieldStrategy.StringField ->
                    CodeBlock.of("%L", stringSizeExpr(s.length, "value.${field.name}"))
                is FieldStrategy.Collection_ ->
                    CodeBlock.of("%L", collectionSizeExpr(s, "value.${field.name}"))
                is FieldStrategy.Spi -> {
                    if (s.descriptor.fixedSize >= 0) {
                        CodeBlock.of("%L", s.descriptor.fixedSize)
                    } else if (s.descriptor.wireSizeRaw.isNotBlank()) {
                        // Slice 5a: variable-size SPI — emit the descriptor's `wireSizeRaw`
                        // verbatim. Mirrors legacy `WireSizeEmitter.wireSizeExpression` /
                        // `Custom` branch which emits `${sizeFn.functionName}($valueExpr, $contextArgs)`
                        // (typically `MyCodec.wireSize(value.fieldName)`). The provider produces
                        // the call site's text directly and the emitter substitutes it.
                        CodeBlock.of("%L", s.descriptor.wireSizeRaw)
                    } else {
                        // SPI variable-size with no wireSizeRaw: validator rejects this combination
                        // (SpiDescriptorChecker), so this branch shouldn't fire when the
                        // pipeline routes a class through. Defensive: emit 0 to keep emit safe.
                        CodeBlock.of("0")
                    }
                }
                // Phase 9 Step 3: value-class wraps a primitive on the wire — its size is
                // the inner primitive's `wireBytes`.
                is FieldStrategy.ValueClass -> {
                    val inner = s.inner
                    if (inner is FieldStrategy.Primitive) {
                        CodeBlock.of("%L", inner.wireBytes)
                    } else {
                        CodeBlock.of("0")
                    }
                }
            }
        return if (field.conditionality is Conditionality.WhenExpr) {
            CodeBlock.of("(if (value.%L != null) %L else 0)", field.name, raw)
        } else {
            raw
        }
    }

    /**
     * Mirrors legacy `WireSizeEmitter.wireSizeExpression` for `CollectionField` —
     * sum of element wireSize plus the prefix bytes when the length is inline.
     */
    private fun collectionSizeExpr(
        s: FieldStrategy.Collection_,
        valueExpr: String,
    ): String {
        val codec = s.elementCodec.codec.canonicalName
        val sumExpr = "$valueExpr.sumOf { $codec.wireSize(it, context) }"
        return when (val l = s.length) {
            is LengthSource.FromField,
            is LengthSource.Remaining,
            -> sumExpr
            is LengthSource.Inline ->
                when (l.encoding) {
                    LengthEncoding.Byte -> "(1 + $sumExpr)"
                    LengthEncoding.Short -> "(2 + $sumExpr)"
                    LengthEncoding.Int -> "(4 + $sumExpr)"
                    LengthEncoding.Varint ->
                        "run { val _b = $sumExpr; variableByteSizeInt(_b) + _b }"
                }
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
                // Phase 9 Step 3: value-class wraps a primitive — peek treats it as
                // the inner primitive's fixed wire width.
                is FieldStrategy.ValueClass -> {
                    val inner = s.inner
                    if (inner is FieldStrategy.Primitive) {
                        fixedAccum += inner.wireBytes
                    } else {
                        return null
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

    // -----------------------------------------------------------------------
    // Phase 9 Step 4 — Cap 2: top-level @Payload data class fan-out
    // -----------------------------------------------------------------------

    /**
     * Phase 9 Step 4-redo — Cap 2 receiver-style emitter for `Plan.Leaf` with
     * one or more `@Payload` type parameters. Mirrors legacy
     * `CodecGenerator.buildPayloadCodecFile` line-for-line for the receiver
     * convention so generated source diffs cleanly when the Step C7 defer
     * drops:
     *
     *  - Skips the `Codec<T>` superinterface (the unbound type parameter `P`
     *    has no instantiable type at object level).
     *  - Emits typed-lambda `<P> decode(buffer, context, payloadDecoder)`
     *    where the decode lambda is **receiver-style**
     *    `${EnclosingSimpleNames}Context.(ReadBuffer) -> P`. The receiver gives
     *    the caller typed access to every already-decoded non-payload field
     *    via `this`, which is the load-bearing case for MQTT v5 (read parsed
     *    properties before deciding the application-payload shape) and any
     *    `@UseCodec` zero-copy handoff (image decoders, framers, etc. that
     *    need the surrounding metadata to interpret the bytes correctly).
     *  - Encode + wireSize lambdas stay **bare**: the caller already holds a
     *    typed `P`, so receiver scoping adds no information; the surface
     *    matches legacy.
     *  - Multi-payload classes get one lambda per `@Payload` type-parameter
     *    (legacy convention: parameter named `decode<FieldName>` /
     *    `encode<FieldName>` / `size<FieldName>`).
     *  - Decode body sequence: read non-payload fields + raw payload slices
     *    → construct `${Context}` from the non-payload locals →
     *    `_ctx.decodePayload(_raw_payload)` invokes the lambda with the
     *    receiver bound.
     *  - Per-payload-field `<FieldName>DecodeKey` stores
     *    `${Context}.(ReadBuffer) -> Any?` (receiver-style mirror of the
     *    typed-lambda shape so `decodeFromContext` can pass through without
     *    a lambda wrap). `EncodeKey` / `SizeKey` keep the bare-lambda type.
     *  - `decodeFromContext`, `encodeFromContext`, `wireSizeFromContext`
     *    bridges read the lambdas off the context and delegate.
     */
    private fun emitPayloadCodec(
        plan: Plan.Leaf,
        classType: ClassName,
    ): FileSpec {
        val codecName = ClassName(classType.packageName, classType.simpleNames.joinToString("") + "Codec")
        val type = TypeSpec.objectBuilder(codecName)
        val canDecode = plan.dir != Direction.EncodeOnly
        val canEncode = plan.dir != Direction.DecodeOnly

        // Build lookup from field name → typeParam name for PayloadSlot binding.
        val fieldNameToTypeParam =
            plan.payloadFields.associate { it.fieldName to it.typeParamName }

        // All payload fields on a single leaf share the same context class
        // (legacy `PayloadContextGenerator` convention). Resolve once.
        val contextType = payloadContextTypeFor(plan)

        if (canDecode) type.addFunction(buildPayloadDecodeFun(plan, classType, fieldNameToTypeParam, contextType))
        if (canEncode) {
            type.addFunction(buildPayloadEncodeFun(plan, classType, fieldNameToTypeParam))
            type.addFunction(buildPayloadWireSizeFun(plan, classType, fieldNameToTypeParam))
        }

        // Per-payload-field DecodeKey / EncodeKey / SizeKey data objects.
        for (pf in plan.payloadFields) {
            type.addType(buildPayloadDecodeKey(pf.fieldName, contextType))
            type.addType(buildPayloadEncodeKey(pf.fieldName))
            type.addType(buildPayloadSizeKey(pf.fieldName))
        }

        // Context-bridge overloads. Star-projected return / value types so the
        // dispatcher can call them through the `Codec<T<*>>` interface.
        if (canDecode) type.addFunction(buildPayloadDecodeFromContextFun(plan, classType))
        if (canEncode) {
            type.addFunction(buildPayloadEncodeFromContextFun(plan, classType))
            type.addFunction(buildPayloadWireSizeFromContextFun(plan, classType))
        }

        val fileBuilder =
            FileSpec
                .builder(codecName.packageName, codecName.simpleName)
                .addType(type.build())
        addExtensionImports(fileBuilder, plan)
        return fileBuilder.build()
    }

    /**
     * Resolves the receiver `*Context` class for the leaf's payload-decode
     * lambda. `payloadFields` is non-empty by construction (caller guards on
     * `payloadTypeParams.isNotEmpty()` upstream and
     * [com.ditchoom.buffer.codec.processor.planbuilder.PlanBuilder] only
     * populates `payloadTypeParams` when at least one ctor parameter binds
     * to one of those type parameters). Every entry shares the same
     * `contextClassFqn`, so we read the first one.
     */
    private fun payloadContextTypeFor(plan: Plan.Leaf): ClassName {
        val first =
            plan.payloadFields.firstOrNull()
                ?: error(
                    "emitPayloadCodec invoked for ${plan.decl.canonical} with no payloadFields; " +
                        "Plan IR contract: payloadTypeParams.isNotEmpty() implies payloadFields.isNotEmpty()",
                )
        return TypeRegistry.splitFqn(first.contextClassFqn)
    }

    private fun starReturnType(
        classType: ClassName,
        plan: Plan.Leaf,
    ) = classType.parameterizedBy(plan.payloadTypeParams.map { STAR })

    private fun typedReturnType(
        classType: ClassName,
        plan: Plan.Leaf,
    ) = classType.parameterizedBy(plan.payloadTypeParams.map { TypeVariableName(it.name) })

    private fun buildPayloadDecodeFun(
        plan: Plan.Leaf,
        classType: ClassName,
        @Suppress("UNUSED_PARAMETER") fieldNameToTypeParam: Map<String, String>,
        contextType: ClassName,
    ): FunSpec {
        val fb =
            FunSpec
                .builder("decode")
                .addParameter("buffer", Names.ReadBuffer)
                .addParameter(
                    ParameterSpec
                        .builder("context", Names.DecodeContext)
                        .defaultValue("%T.Empty", Names.DecodeContext)
                        .build(),
                )
        for (tp in plan.payloadTypeParams) {
            fb.addTypeVariable(TypeVariableName(tp.name))
        }
        for (pf in plan.payloadFields) {
            val tp = TypeVariableName(pf.typeParamName)
            val lambdaType =
                LambdaTypeName.get(
                    receiver = contextType,
                    parameters = listOf(ParameterSpec.unnamed(Names.ReadBuffer)),
                    returnType = tp,
                )
            fb.addParameter(payloadDecodeParamName(pf.fieldName), lambdaType)
        }
        fb.returns(typedReturnType(classType, plan))

        // Phase 1: read non-payload fields + raw payload slices in declaration order.
        val cb = CodeBlock.builder()
        for (f in plan.fields) {
            val s = f.strategy
            if (s is FieldStrategy.PayloadSlot) {
                emitPayloadRawRead(cb, f, s)
            } else {
                val stmt = decodeStatement(f) ?: continue
                cb.add(stmt)
            }
        }
        // Phase 2: build the typed receiver context from the already-decoded
        // non-payload locals so the user-supplied lambda can read sibling
        // fields off `this`. Object-form when every field is @Payload (rare).
        val nonPayloadLocals = plan.fields.filter { it.strategy !is FieldStrategy.PayloadSlot }
        if (nonPayloadLocals.isEmpty()) {
            cb.addStatement("val _ctx = %T", contextType)
        } else {
            cb.addStatement(
                "val _ctx = %T(%L)",
                contextType,
                nonPayloadLocals.joinToString(", ") { it.name },
            )
        }
        // Phase 3: invoke each payload-decoder lambda via the receiver so
        // `this` inside the lambda is the typed context.
        for (pf in plan.payloadFields) {
            val rawVar = "_raw_${pf.fieldName}"
            val paramName = payloadDecodeParamName(pf.fieldName)
            cb.addStatement("val %L = _ctx.%L(%L)", pf.fieldName, paramName, rawVar)
        }
        // Phase 4: constructor call.
        val ctorArgs = plan.fields.joinToString(", ") { "${it.name} = ${it.name}" }
        cb.addStatement("return %T(%L)", classType, ctorArgs)
        fb.addCode(cb.build())
        return fb.build()
    }

    /**
     * Reads the raw payload bytes into `_raw_<fieldName>` (a `ReadBuffer` slice).
     * Mirrors legacy `addPayloadRawRead` for the length sources Cap 2 supports.
     */
    private fun emitPayloadRawRead(
        cb: CodeBlock.Builder,
        field: FieldPlan,
        strategy: FieldStrategy.PayloadSlot,
    ) {
        val rawVar = "_raw_${field.name}"
        val readBody =
            when (val l = strategy.length) {
                is LengthSource.Remaining ->
                    if (l.trailingBytes > 0) {
                        "buffer.readBytes(buffer.remaining() - ${l.trailingBytes})"
                    } else {
                        "buffer.readBytes(buffer.remaining())"
                    }
                is LengthSource.FromField -> "buffer.readBytes(${l.name}.toInt())"
                is LengthSource.Inline -> {
                    val readLen =
                        when (l.encoding) {
                            LengthEncoding.Byte -> "buffer.readByte().toInt() and 0xFF"
                            LengthEncoding.Short -> "buffer.readUnsignedShort().toInt()"
                            LengthEncoding.Int -> "buffer.readInt()"
                            LengthEncoding.Varint -> "buffer.readVariableByteInteger()"
                        }
                    "run { val _len = $readLen; buffer.readBytes(_len) }"
                }
            }
        cb.addStatement("val %L: %T = %L", rawVar, Names.ReadBuffer, readBody)
    }

    private fun buildPayloadEncodeFun(
        plan: Plan.Leaf,
        classType: ClassName,
        fieldNameToTypeParam: Map<String, String>,
    ): FunSpec {
        val returnType = typedReturnType(classType, plan)
        val fb =
            FunSpec
                .builder("encode")
                .addParameter("buffer", Names.WriteBuffer)
                .addParameter(ParameterSpec.builder("value", returnType).build())
                .addParameter(
                    ParameterSpec
                        .builder("context", Names.EncodeContext)
                        .defaultValue("%T.Empty", Names.EncodeContext)
                        .build(),
                )
        for (tp in plan.payloadTypeParams) {
            fb.addTypeVariable(TypeVariableName(tp.name))
        }
        for (pf in plan.payloadFields) {
            val tp = TypeVariableName(pf.typeParamName)
            val lambdaType =
                LambdaTypeName.get(
                    parameters =
                        listOf(
                            ParameterSpec.unnamed(Names.WriteBuffer),
                            ParameterSpec.unnamed(tp),
                        ),
                    returnType = UNIT,
                )
            fb.addParameter(payloadEncodeParamName(pf.fieldName), lambdaType)
        }
        val cb = CodeBlock.builder()
        for (f in plan.fields) {
            val s = f.strategy
            if (s is FieldStrategy.PayloadSlot) {
                emitPayloadEncode(cb, f, s)
            } else {
                val stmt = encodeStatement(f) ?: continue
                cb.add(stmt)
            }
        }
        fb.addCode(cb.build())
        return fb.build()
    }

    /**
     * Mirrors legacy `addPayloadEncodeBody`: writes the length prefix (if
     * applicable) then invokes the user-supplied `encode<FieldName>` lambda
     * with `buffer` and `value.<fieldName>` so the lambda writes typed payload
     * bytes directly into the host buffer.
     */
    private fun emitPayloadEncode(
        cb: CodeBlock.Builder,
        field: FieldPlan,
        strategy: FieldStrategy.PayloadSlot,
    ) {
        val encodeFn = payloadEncodeParamName(field.name)
        val sizeFn = payloadSizeParamName(field.name)
        val valueExpr = "value.${field.name}"
        val suffix = "_${field.name}"
        when (val l = strategy.length) {
            is LengthSource.Remaining,
            is LengthSource.FromField,
            -> cb.addStatement("%L(buffer, %L)", encodeFn, valueExpr)
            is LengthSource.Inline ->
                when (l.encoding) {
                    LengthEncoding.Varint -> {
                        cb.addStatement("val _l%L = %L(%L)", suffix, sizeFn, valueExpr)
                        if (l.maxBytes in 1..3) {
                            cb.addStatement(
                                "require(_l%L in 0..com.ditchoom.buffer.variableByteMax(%L)) { %P }",
                                suffix,
                                l.maxBytes,
                                "field '${field.name}' encoded length \$_l$suffix exceeds " +
                                    "maxBytes=${l.maxBytes} (max value " +
                                    "\${com.ditchoom.buffer.variableByteMax(${l.maxBytes})})",
                            )
                        }
                        cb.addStatement("buffer.writeVariableByteInteger(_l%L)", suffix)
                        cb.addStatement("%L(buffer, %L)", encodeFn, valueExpr)
                    }
                    LengthEncoding.Byte -> {
                        cb.addStatement("val _pos%L = buffer.position()", suffix)
                        cb.addStatement("buffer.writeByte(0.toByte())")
                        cb.addStatement("%L(buffer, %L)", encodeFn, valueExpr)
                        cb.addStatement("val _end%L = buffer.position()", suffix)
                        cb.addStatement("val _len%L = _end%L - _pos%L - 1", suffix, suffix, suffix)
                        cb.addStatement("buffer.position(_pos%L)", suffix)
                        cb.addStatement("buffer.writeByte(_len%L.toByte())", suffix)
                        cb.addStatement("buffer.position(_end%L)", suffix)
                    }
                    LengthEncoding.Short -> {
                        cb.addStatement("val _pos%L = buffer.position()", suffix)
                        cb.addStatement("buffer.writeShort(0.toShort())")
                        cb.addStatement("%L(buffer, %L)", encodeFn, valueExpr)
                        cb.addStatement("val _end%L = buffer.position()", suffix)
                        cb.addStatement("val _len%L = _end%L - _pos%L - 2", suffix, suffix, suffix)
                        cb.addStatement("buffer.position(_pos%L)", suffix)
                        cb.addStatement("buffer.writeShort(_len%L.toShort())", suffix)
                        cb.addStatement("buffer.position(_end%L)", suffix)
                    }
                    LengthEncoding.Int -> {
                        cb.addStatement("val _pos%L = buffer.position()", suffix)
                        cb.addStatement("buffer.writeInt(0)")
                        cb.addStatement("%L(buffer, %L)", encodeFn, valueExpr)
                        cb.addStatement("val _end%L = buffer.position()", suffix)
                        cb.addStatement("val _len%L = _end%L - _pos%L - 4", suffix, suffix, suffix)
                        cb.addStatement("buffer.position(_pos%L)", suffix)
                        cb.addStatement("buffer.writeInt(_len%L)", suffix)
                        cb.addStatement("buffer.position(_end%L)", suffix)
                    }
                }
        }
    }

    private fun buildPayloadWireSizeFun(
        plan: Plan.Leaf,
        classType: ClassName,
        fieldNameToTypeParam: Map<String, String>,
    ): FunSpec {
        val returnType = typedReturnType(classType, plan)
        val fb =
            FunSpec
                .builder("wireSize")
                .addParameter(ParameterSpec.builder("value", returnType).build())
                .addParameter(
                    ParameterSpec
                        .builder("context", Names.EncodeContext)
                        .defaultValue("%T.Empty", Names.EncodeContext)
                        .build(),
                )
                .returns(INT)
        for (tp in plan.payloadTypeParams) {
            fb.addTypeVariable(TypeVariableName(tp.name))
        }
        for (pf in plan.payloadFields) {
            val tp = TypeVariableName(pf.typeParamName)
            val lambdaType =
                LambdaTypeName.get(
                    parameters = listOf(ParameterSpec.unnamed(tp)),
                    returnType = INT,
                )
            fb.addParameter(payloadSizeParamName(pf.fieldName), lambdaType)
        }
        val cb = CodeBlock.builder()
        cb.addStatement("var _size = 0")
        for (f in plan.fields) {
            val s = f.strategy
            if (s is FieldStrategy.PayloadSlot) {
                cb.addStatement("_size += %L", payloadFieldWireSizeExpr(f, s))
            } else {
                cb.addStatement("_size += %L", sizeContribution(f))
            }
        }
        cb.addStatement("return _size")
        fb.addCode(cb.build())
        return fb.build()
    }

    /**
     * Returns the wire-size expression for a payload field. Mirrors legacy
     * `payloadFieldWireSizeExpr`: prefix bytes (if any) + `size<FieldName>(value.<field>)`.
     */
    private fun payloadFieldWireSizeExpr(
        field: FieldPlan,
        strategy: FieldStrategy.PayloadSlot,
    ): String {
        val sizeFn = payloadSizeParamName(field.name)
        val bodySize = "$sizeFn(value.${field.name})"
        return when (val l = strategy.length) {
            is LengthSource.Remaining,
            is LengthSource.FromField,
            -> bodySize
            is LengthSource.Inline ->
                when (l.encoding) {
                    LengthEncoding.Byte -> "(1 + $bodySize)"
                    LengthEncoding.Short -> "(2 + $bodySize)"
                    LengthEncoding.Int -> "(4 + $bodySize)"
                    LengthEncoding.Varint ->
                        "run { val _l = $bodySize; com.ditchoom.buffer.variableByteSizeInt(_l) + _l }"
                }
        }
    }

    private fun buildPayloadDecodeKey(
        fieldName: String,
        contextType: ClassName,
    ): TypeSpec {
        // Receiver-style `${Context}.(ReadBuffer) -> Any?` mirrors the
        // typed-lambda parameter shape so `decodeFromContext` can read
        // the lambda from the context and pass it through to `decode<P>`
        // without an intermediate wrap (the type system uses contravariance
        // on `Any?` to match any inferred `P`).
        val lambdaType =
            LambdaTypeName.get(
                receiver = contextType,
                parameters = listOf(ParameterSpec.unnamed(Names.ReadBuffer)),
                returnType = ANY.copy(nullable = true),
            )
        return TypeSpec
            .objectBuilder("${capitalizeFirst(fieldName)}DecodeKey")
            .addModifiers(KModifier.PUBLIC, KModifier.DATA)
            .superclass(Names.CodecContext.nestedClass("Key").parameterizedBy(lambdaType))
            .build()
    }

    private fun buildPayloadEncodeKey(fieldName: String): TypeSpec {
        val lambdaType =
            LambdaTypeName.get(
                parameters =
                    listOf(
                        ParameterSpec.unnamed(Names.WriteBuffer),
                        ParameterSpec.unnamed(ANY.copy(nullable = true)),
                    ),
                returnType = UNIT,
            )
        return TypeSpec
            .objectBuilder("${capitalizeFirst(fieldName)}EncodeKey")
            .addModifiers(KModifier.PUBLIC, KModifier.DATA)
            .superclass(Names.CodecContext.nestedClass("Key").parameterizedBy(lambdaType))
            .build()
    }

    private fun buildPayloadSizeKey(fieldName: String): TypeSpec {
        val lambdaType =
            LambdaTypeName.get(
                parameters = listOf(ParameterSpec.unnamed(ANY.copy(nullable = true))),
                returnType = INT,
            )
        return TypeSpec
            .objectBuilder("${capitalizeFirst(fieldName)}SizeKey")
            .addModifiers(KModifier.PUBLIC, KModifier.DATA)
            .superclass(Names.CodecContext.nestedClass("Key").parameterizedBy(lambdaType))
            .build()
    }

    private fun buildPayloadDecodeFromContextFun(
        plan: Plan.Leaf,
        classType: ClassName,
    ): FunSpec {
        val cb = CodeBlock.builder()
        val args = mutableListOf<String>()
        for (pf in plan.payloadFields) {
            val cap = capitalizeFirst(pf.fieldName)
            val local = "_decode$cap"
            cb.addStatement(
                "val %L = context[%LDecodeKey] ?: error(%S)",
                local,
                cap,
                "DecodeContext missing ${classType.simpleNames.joinToString("")}Codec.${cap}DecodeKey. " +
                    "Register: ctx.with(${classType.simpleNames.joinToString("")}Codec.${cap}DecodeKey) { pr -> ... }",
            )
            args += local
        }
        cb.addStatement("return decode(buffer, context, %L)", args.joinToString(", "))
        return FunSpec
            .builder("decodeFromContext")
            .addParameter("buffer", Names.ReadBuffer)
            .addParameter("context", Names.DecodeContext)
            .returns(starReturnType(classType, plan))
            .addCode(cb.build())
            .build()
    }

    private fun buildPayloadEncodeFromContextFun(
        plan: Plan.Leaf,
        classType: ClassName,
    ): FunSpec {
        val cb = CodeBlock.builder()
        val args = mutableListOf<String>()
        for (pf in plan.payloadFields) {
            val cap = capitalizeFirst(pf.fieldName)
            val local = "_encode$cap"
            cb.addStatement(
                "val %L = context[%LEncodeKey] ?: error(%S)",
                local,
                cap,
                "EncodeContext missing ${classType.simpleNames.joinToString("")}Codec.${cap}EncodeKey. " +
                    "Register: ctx.with(${classType.simpleNames.joinToString("")}Codec.${cap}EncodeKey) { buf, v -> ... }",
            )
            args += local
        }
        cb.addStatement("encode(buffer, value, context, %L)", args.joinToString(", "))
        return FunSpec
            .builder("encodeFromContext")
            .addAnnotation(
                com.squareup.kotlinpoet.AnnotationSpec
                    .builder(Suppress::class)
                    .addMember("%S", "UNCHECKED_CAST")
                    .build(),
            )
            .addParameter("buffer", Names.WriteBuffer)
            .addParameter("value", starReturnType(classType, plan))
            .addParameter("context", Names.EncodeContext)
            .addCode(cb.build())
            .build()
    }

    private fun buildPayloadWireSizeFromContextFun(
        plan: Plan.Leaf,
        classType: ClassName,
    ): FunSpec {
        val cb = CodeBlock.builder()
        val args = mutableListOf<String>()
        for (pf in plan.payloadFields) {
            val cap = capitalizeFirst(pf.fieldName)
            val local = "_size$cap"
            cb.addStatement(
                "val %L = context[%LSizeKey] ?: error(%S)",
                local,
                cap,
                "EncodeContext missing ${classType.simpleNames.joinToString("")}Codec.${cap}SizeKey. " +
                    "Register: ctx.with(${classType.simpleNames.joinToString("")}Codec.${cap}SizeKey) { v -> ... }",
            )
            args += local
        }
        cb.addStatement("return wireSize(value, context, %L)", args.joinToString(", "))
        return FunSpec
            .builder("wireSizeFromContext")
            .addAnnotation(
                com.squareup.kotlinpoet.AnnotationSpec
                    .builder(Suppress::class)
                    .addMember("%S", "UNCHECKED_CAST")
                    .build(),
            )
            .addParameter("value", starReturnType(classType, plan))
            .addParameter("context", Names.EncodeContext)
            .returns(INT)
            .addCode(cb.build())
            .build()
    }

    // Lambda parameter-name helpers — mirror legacy `decode${capitalize(name)}` etc.
    private fun payloadDecodeParamName(fieldName: String): String = "decode${capitalizeFirst(fieldName)}"

    private fun payloadEncodeParamName(fieldName: String): String = "encode${capitalizeFirst(fieldName)}"

    private fun payloadSizeParamName(fieldName: String): String = "size${capitalizeFirst(fieldName)}"

    private fun capitalizeFirst(s: String): String =
        if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)
}

private inline fun FunSpec.Builder.indent(block: () -> Unit) {
    // indent placeholder — `addCode` already line-formats. The function exists
    // to make the conditional encode loop above readable.
    block()
}
