package com.ditchoom.buffer.codec.processor

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName

/*
 * CodecEmitterFields — the per-field-type encode/decode emit family extracted
 * from CodecEmitter (step 6). Every appendDecode* / appendEncode* helper for
 * the supported field shapes (scalar, value-class scalar, conditional,
 * length-prefixed string/message/UseCodec, @LengthFrom, @RemainingBytes,
 * bare @UseCodec / @ProtocolMessage scalars) plus the small scalarHeaderBytes
 * / appendBufferPrefix* utilities they share.
 *
 * The orchestration dispatchers (appendDecodeField / appendEncodeField and the
 * batching layer) stay in CodecEmitter and call these unqualified across the
 * package — the same cross-file pattern used in steps 4-5. All are stateless
 * emit helpers (no batchCounter, codeGenerator or logger); the shared
 * naturalScalarWriteStatement moved alongside its read sibling
 * naturalScalarReadExpr in CodecEmitterScalarUtils.kt. Byte-identical codegen
 * verified by the snapshot suite.
 */

internal fun CodecShape.scalarHeaderBytes(): Int = fields.sumOfFixedWireBytes().requireFixed("scalarHeaderBytes")

internal fun appendDecodeScalar(
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

internal fun appendNaturalReadWithSwap(
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

internal fun appendManualScalarDecode(
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

internal fun appendEncodeScalar(
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

internal fun appendNaturalWriteWithSwap(
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

internal fun appendEncodeGuard(
    body: CodeBlock.Builder,
    field: FieldSpec.Scalar,
    ownerSimpleName: String,
) {
    if (field.wireBytes >= field.kind.width) return
    val accessor = "value.${field.name}"
    val (lhs, maxLit) =
        when (field.kind) {
            ScalarKind.ULong -> accessor to "((1uL shl ${Byte.SIZE_BITS * field.wireBytes}) - 1uL)"
            ScalarKind.UInt -> accessor to "((1u shl ${Byte.SIZE_BITS * field.wireBytes}) - 1u)"
            ScalarKind.UShort -> "$accessor.toUInt()" to "((1u shl ${Byte.SIZE_BITS * field.wireBytes}) - 1u)"
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

internal fun appendManualScalarEncode(
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
internal fun appendDecodeValueClassScalar(
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
internal fun appendEncodeValueClassScalar(
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

internal fun appendValueClassNaturalReadWithSwap(
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

internal fun appendValueClassNaturalWriteWithSwap(
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
internal fun appendDecodeConditional(
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
            if (inner.valueClass != null) {
                body.addStatement(
                    "%T(buffer.readString(%L, %T.UTF8))",
                    inner.valueClass.valueClassType,
                    lengthVar,
                    CHARSET_CN,
                )
            } else {
                body.addStatement("buffer.readString(%L, %T.UTF8)", lengthVar, CHARSET_CN)
            }
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
internal fun appendEncodeConditional(
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
                // `localName` is the null-checked non-null value; unwrap the
                // value class via its inner property when present.
                accessor =
                    if (inner.valueClass != null) {
                        "$localName.${inner.valueClass.innerPropertyName}"
                    } else {
                        localName
                    },
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
internal fun appendConditionalScalarSwapDecode(
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

internal fun appendConditionalScalarSwapEncode(
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
internal fun appendEncodeConditionalLengthPrefixedUseCodecList(
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
internal fun appendEncodeConditionalLengthPrefixedUseCodecPayload(
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
    appendPrefixSlotReservation(body, inner.prefixWidth)
    body.addStatement("val %L = buffer.position()", bodyStartVar)
    body.addStatement("%T.encode(buffer, %L, context)", inner.payloadCodecType, accessor)
    body.addStatement("val %L = buffer.position()", endPosVar)
    body.addStatement("val %L = %L - %L", byteCountVar, endPosVar, bodyStartVar)
    if (inner.prefixWidth < Int.SIZE_BYTES) {
        val maxValue = (1L shl (inner.prefixWidth * Byte.SIZE_BITS)) - 1
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
internal fun encodeConditionAccessor(
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
internal fun conditionExpressionLiteral(condition: ConditionRef): String =
    when (condition) {
        is ConditionRef.Sibling -> condition.name
        is ConditionRef.ValueClassProperty -> "${condition.siblingName}.${condition.propertyName}"
        is ConditionRef.RemainingCmp ->
            "remaining ${condition.op.symbol} ${condition.threshold}"
    }

internal fun appendDecodeLengthPrefixed(
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

internal fun appendBufferPrefixDecode(
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

/**
 * Encode a terminal `@LengthPrefixed val: @ProtocolMessage` body. Forks at
 * runtime on the nested codec's wireSize:
 *
 * - **Exact** — write the prefix from the measured size, then the body
 *   (the historical fast path, now with the same prefix-range guard the
 *   String emit carries — the unguarded path silently mask-truncated an
 *   oversized body into a corrupt prefix).
 * - **BackPatch** — a nested body carrying its own variable-length field
 *   reports BackPatch; reserve the prefix slot with placeholder writes,
 *   encode the body, measure the position delta, guard, and back-patch
 *   (the same shape as the `@LengthPrefixed val: String` emit). This path
 *   used to throw ClassCastException on every encode.
 */
internal fun appendEncodeLengthPrefixed(
    body: CodeBlock.Builder,
    field: FieldSpec.LengthPrefixedMessage,
) {
    val fieldPath = "${field.ownerSimpleName}.${field.name}"
    val sizeVar = "${field.name}WireSize"
    body.beginControlFlow(
        "when (val %L = %T.wireSize(value.%L, context))",
        sizeVar,
        field.codecType,
        field.name,
    )
    body.beginControlFlow("is %T.Exact ->", WIRE_SIZE_CN)
    appendLengthPrefixedExactBranch(body, field, fieldPath, sizeVar)
    body.endControlFlow()
    body.beginControlFlow("%T.BackPatch ->", WIRE_SIZE_CN)
    appendLengthPrefixedBackPatchBranch(body, field, fieldPath)
    body.endControlFlow()
    body.endControlFlow()
}

/** Exact branch: prefix from the declared size, then verify encode agreed. */
private fun appendLengthPrefixedExactBranch(
    body: CodeBlock.Builder,
    field: FieldSpec.LengthPrefixedMessage,
    fieldPath: String,
    sizeVar: String,
) {
    val byteCountVar = "${field.name}ByteCount"
    body.addStatement("val %L = %L.bytes", byteCountVar, sizeVar)
    appendPrefixWidthGuard(body, byteCountVar, field.prefixWidth, fieldPath)
    val prefixVar = "${field.name}Prefix"
    body.addStatement("val %L = %L.toUInt()", prefixVar, byteCountVar)
    appendBufferPrefixEncode(body, prefixVar, field.prefixWidth, field.prefixWireOrder)
    // The prefix above was written from the DECLARED size. Verify the body
    // actually encoded to that many bytes — a codec whose wireSize disagrees
    // with its encode must fail loudly here, not ship a frame whose prefix
    // and body disagree (silent wire corruption).
    val bodyStartVar = "${field.name}BodyStart"
    body.addStatement("val %L = buffer.position()", bodyStartVar)
    body.addStatement("%T.encode(buffer, value.%L, context)", field.codecType, field.name)
    body.beginControlFlow("if (buffer.position() - %L != %L)", bodyStartVar, byteCountVar)
    body.addStatement(
        "throw %T(fieldPath = %S, reason = %P)",
        ENCODE_EXCEPTION_CN,
        fieldPath,
        "wireSize declared \${$byteCountVar} bytes but encode wrote " +
            "\${buffer.position() - $bodyStartVar} — the codec's wireSize and encode disagree",
    )
    body.endControlFlow()
}

/** BackPatch branch: reserve, encode, measure, guard, patch. */
private fun appendLengthPrefixedBackPatchBranch(
    body: CodeBlock.Builder,
    field: FieldSpec.LengthPrefixedMessage,
    fieldPath: String,
) {
    val sizePosVar = "${field.name}SizePosition"
    val bodyStartVar = "${field.name}BodyStart"
    val endPosVar = "${field.name}EndPosition"
    val patchCountVar = "${field.name}PatchByteCount"
    body.addStatement("val %L = buffer.position()", sizePosVar)
    appendPrefixSlotReservation(body, field.prefixWidth)
    body.addStatement("val %L = buffer.position()", bodyStartVar)
    body.addStatement("%T.encode(buffer, value.%L, context)", field.codecType, field.name)
    body.addStatement("val %L = buffer.position()", endPosVar)
    body.addStatement("val %L = %L - %L", patchCountVar, endPosVar, bodyStartVar)
    appendPrefixWidthGuard(body, patchCountVar, field.prefixWidth, fieldPath)
    body.addStatement("buffer.position(%L)", sizePosVar)
    val patchPrefixVar = "${field.name}PatchPrefix"
    body.addStatement("val %L = %L.toUInt()", patchPrefixVar, patchCountVar)
    appendBufferPrefixEncode(body, patchPrefixVar, field.prefixWidth, field.prefixWireOrder)
    body.addStatement("buffer.position(%L)", endPosVar)
}

/**
 * Emit the prefix-range guard shared by the length-prefix encode paths: for
 * 1/2-byte prefixes, throw `EncodeException` when the measured body byte
 * count exceeds the prefix's value range. 4-byte prefixes skip the guard —
 * an Int-typed position delta can never exceed the UInt range.
 */
internal fun appendPrefixWidthGuard(
    body: CodeBlock.Builder,
    byteCountVar: String,
    prefixWidth: Int,
    fieldPath: String,
) {
    if (prefixWidth >= Int.SIZE_BYTES) return
    val maxValue = (1L shl (prefixWidth * Byte.SIZE_BITS)) - 1
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
        fieldPath,
        "encoded message byte length \${$byteCountVar} exceeds " +
            "@LengthPrefixed(LengthPrefix.$widthName) max $maxValue",
    )
    body.endControlFlow()
}

internal fun appendDecodeLengthPrefixedString(
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
    val read = CodeBlock.of("buffer.readString(%L, %T.UTF8)", lengthVar, CHARSET_CN)
    // Wrap the decoded String in the value class's primary constructor when
    // the field is a value class over String (wire-identical to bare String).
    if (field.valueClass != null) {
        body.addStatement("val %L = %T(%L)", field.name, field.valueClass.valueClassType, read)
    } else {
        body.addStatement("val %L = %L", field.name, read)
    }
}

/**
 * Emit the prefix read + Int.MAX_VALUE guard + length
 * Int conversion shared by length-prefixed-string field decode
 * and the conditional `@LengthPrefixed @When` decode path.
 * Returns the local variable name holding the resolved
 * (Int-typed) length.
 */
internal fun appendLengthPrefixedStringPrefixDecode(
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

internal fun appendEncodeLengthPrefixedString(
    body: CodeBlock.Builder,
    field: FieldSpec.LengthPrefixedString,
) {
    appendLengthPrefixedStringEncode(
        body = body,
        name = field.name,
        ownerSimpleName = field.ownerSimpleName,
        prefixWidth = field.prefixWidth,
        prefixWireOrder = field.prefixWireOrder,
        // Unwrap the value class via its inner property when present
        // (`value.id.value`); plain String fields use `value.id`.
        accessor =
            if (field.valueClass != null) {
                "value.${field.name}.${field.valueClass.innerPropertyName}"
            } else {
                "value.${field.name}"
            },
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
internal fun appendLengthPrefixedStringEncode(
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
    appendPrefixSlotReservation(body, prefixWidth)
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
    if (prefixWidth < Int.SIZE_BYTES) {
        val maxValue = (1L shl (prefixWidth * Byte.SIZE_BITS)) - 1
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
internal fun appendDecodeLengthFromString(
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
    val read =
        CodeBlock.of("buffer.readString(%L, %T.UTF8)", field.source.decodeAccessor(), CHARSET_CN)
    if (field.valueClass != null) {
        body.addStatement("val %L = %T(%L)", field.name, field.valueClass.valueClassType, read)
    } else {
        body.addStatement("val %L = %L", field.name, read)
    }
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
internal fun appendEncodeLengthFromString(
    body: CodeBlock.Builder,
    field: FieldSpec.LengthFromString,
) {
    val accessor =
        if (field.valueClass != null) {
            "value.${field.name}.${field.valueClass.innerPropertyName}"
        } else {
            "value.${field.name}"
        }
    body.addStatement(
        "buffer.writeString(%L, %T.UTF8)",
        accessor,
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
internal fun appendDecodeLengthFromList(
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
internal fun appendEncodeLengthFromList(
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
internal fun appendDecodeLengthFromMessage(
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
internal fun appendEncodeLengthFromMessage(
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
 * Emit decode for a payload whose codec is not owned by the generated
 * codec, dispatching on how its region is bounded. Each
 * [PayloadExtent] arm owns a whole emit strategy — the bound is the
 * only thing the generated code contributes, so it is the thing that
 * differs.
 */
internal fun appendDecodeDeferredPayload(
    body: CodeBlock.Builder,
    field: FieldSpec.DeferredPayload,
) {
    when (val extent = field.extent) {
        is PayloadExtent.ToLimit -> appendDecodeDeferredPayloadToLimit(body, field, extent)
    }
}

/**
 * Emit decode for
 * `@RemainingBytes @UseCodec(C::class) val: P`. Delegates to the
 * user-supplied `C.decode(buffer, context)` against whatever
 * `buffer.limit()` already says — same caller-bounds-buffer contract
 * as the other `@RemainingBytes` shapes. The outer dispatcher (slice
 * 10d for MQTT) sets the limit before calling this codec.
 */
private fun appendDecodeDeferredPayloadToLimit(
    body: CodeBlock.Builder,
    field: FieldSpec.DeferredPayload,
    extent: PayloadExtent.ToLimit,
) {
    if (extent.reservedTrailingBytes == 0) {
        body.addStatement(
            "val %L = %L.decode(buffer, context)",
            field.name,
            field.source.codecReceiver(),
        )
        return
    }
    // Non-terminal DeferredPayload. Narrow the
    // buffer's limit to leave the trailing FixedSize fields in the
    // outer-limit region; restore the outer limit in a try/finally
    // so the trailing field emits run against the original limit.
    val outerLimitVar = "__${field.name}OuterLimit"
    body.addStatement("val %L = buffer.limit()", outerLimitVar)
    body.addStatement(
        "buffer.setLimit(%L - %L)",
        outerLimitVar,
        extent.reservedTrailingBytes,
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
internal fun appendEncodeDeferredPayload(
    body: CodeBlock.Builder,
    field: FieldSpec.DeferredPayload,
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
 * [appendDecodeDeferredPayload].
 */
internal fun appendDecodeRemainingBytesString(
    body: CodeBlock.Builder,
    field: FieldSpec.RemainingBytesString,
) {
    val read =
        if (field.reservedTrailingBytes == 0) {
            CodeBlock.of("buffer.readString(buffer.remaining(), %T.UTF8)", CHARSET_CN)
        } else {
            // Read the body byte count minus the reserved trailing
            // FixedSize bytes; the trailing field emits run normally after.
            CodeBlock.of(
                "buffer.readString(buffer.remaining() - %L, %T.UTF8)",
                field.reservedTrailingBytes,
                CHARSET_CN,
            )
        }
    if (field.valueClass != null) {
        body.addStatement("val %L = %T(%L)", field.name, field.valueClass.valueClassType, read)
    } else {
        body.addStatement("val %L = %L", field.name, read)
    }
}

/**
 * Encode counterpart for `@RemainingBytes val: String`. Writes the value's
 * UTF-8 byte representation. The encoded byte count is reported via
 * [appendBackPatchWireSize] (the parent message's wireSize collapses to
 * BackPatch because the trailing string's byte count isn't known up front
 * without re-encoding).
 */
internal fun appendEncodeRemainingBytesString(
    body: CodeBlock.Builder,
    field: FieldSpec.RemainingBytesString,
) {
    val accessor =
        if (field.valueClass != null) {
            "value.${field.name}.${field.valueClass.innerPropertyName}"
        } else {
            "value.${field.name}"
        }
    body.addStatement(
        "buffer.writeString(%L, %T.UTF8)",
        accessor,
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
internal fun appendDecodeRemainingBytesProtocolMessageList(
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
internal fun appendEncodeRemainingBytesProtocolMessageList(
    body: CodeBlock.Builder,
    field: FieldSpec.RemainingBytesProtocolMessageList,
) {
    body.beginControlFlow("for (__elem in value.%L)", field.name)
    body.addStatement("%T.encode(buffer, __elem, context)", field.elementCodecClassName)
    body.endControlFlow()
}

/**
 * Emit decode for `@Count val: List<T>` where `T` is a `@ProtocolMessage`
 * element. Reads the element COUNT as an unsigned LEB128 varint (the shipped
 * `UnsignedVarIntCodec`, the same self-delimiting encoding an enum ordinal
 * rides on), then loops exactly that many times decoding one element each
 * iteration. Self-delimiting — no buffer-limit bound is consulted.
 *
 * Generated shape:
 * ```
 * val __<name>Count = UnsignedVarIntCodec.decode(buffer, context).toInt()
 * val <name> = ArrayList<ElementType>(__<name>Count.coerceAtMost(<CAP>))
 * repeat(__<name>Count) {
 *     <name> += ElementCodec.decode(buffer, context)
 * }
 * ```
 */
internal fun appendDecodeCountPrefixedProtocolMessageList(
    body: CodeBlock.Builder,
    field: FieldSpec.CountPrefixedProtocolMessageList,
) {
    val countVar = "__${field.name}Count"
    body.addStatement("val %L = %T.decode(buffer, context).toInt()", countVar, UNSIGNED_VARINT_CODEC_CN)
    // Pre-size the list to the count, capped so a hostile varint can't
    // request a multi-gigabyte allocation up front; the element decodes
    // still fail fast on a truncated buffer well before the list grows.
    body.addStatement(
        "val %L = ArrayList<%T>(%L.coerceIn(0, %L))",
        field.name,
        field.elementClassName,
        countVar,
        COUNT_PREFETCH_CAP,
    )
    body.beginControlFlow("repeat(%L)", countVar)
    body.addStatement("%L += %T.decode(buffer, context)", field.name, field.elementCodecClassName)
    body.endControlFlow()
}

/**
 * Emit encode for `@Count val: List<T>`. Writes the element count as an
 * unsigned LEB128 varint, then each element via the element codec. The count
 * is derived from `list.size`, so the wire form is always self-consistent —
 * no user-trust contract (unlike the byte-length `@LengthFrom` list shapes).
 */
internal fun appendEncodeCountPrefixedProtocolMessageList(
    body: CodeBlock.Builder,
    field: FieldSpec.CountPrefixedProtocolMessageList,
) {
    body.addStatement("%T.encode(buffer, value.%L.size.toUInt(), context)", UNSIGNED_VARINT_CODEC_CN, field.name)
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
internal fun appendDecodeUseCodecScalar(
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
internal fun appendEncodeUseCodecScalar(
    body: CodeBlock.Builder,
    field: FieldSpec.UseCodecScalar,
    shape: CodecShape,
) {
    body.addStatement("%T.encode(buffer, value.%L, context)", field.codecType, field.name)
}

/**
 * Emit decode for an enum field: read the ordinal as an unsigned LEB128 varint, then map it back
 * to an entry. With an `@EnumDefault` an unknown (e.g. newer) ordinal resolves to that entry
 * (forward-compatible); without one it throws [DecodeException].
 */
internal fun appendDecodeEnum(
    body: CodeBlock.Builder,
    field: FieldSpec.EnumScalar,
) {
    body.addStatement("val __%LOrdinal = %T.decode(buffer, context).toInt()", field.name, UNSIGNED_VARINT_CODEC_CN)
    if (field.defaultEntryName != null) {
        body.addStatement(
            "val %L = %T.entries.getOrElse(__%LOrdinal) { %T.%L }",
            field.name,
            field.enumType,
            field.name,
            field.enumType,
            field.defaultEntryName,
        )
    } else {
        body.addStatement(
            "val %L = %T.entries.getOrElse(__%LOrdinal) { throw %T(fieldPath = %S, " +
                "bufferPosition = buffer.position(), expected = %S, actual = __%LOrdinal.toString()) }",
            field.name,
            field.enumType,
            field.name,
            DECODE_EXCEPTION_CN,
            "${field.ownerSimpleName}.${field.name}",
            "an ordinal in 0 until ${field.entryCount}",
            field.name,
        )
    }
}

/** Emit encode for an enum field: write the entry's `ordinal` as an unsigned LEB128 varint. */
internal fun appendEncodeEnum(
    body: CodeBlock.Builder,
    field: FieldSpec.EnumScalar,
) {
    body.addStatement("%T.encode(buffer, value.%L.ordinal.toUInt(), context)", UNSIGNED_VARINT_CODEC_CN, field.name)
}

/**
 * Emit decode/encode for bare `val: T:
 * @ProtocolMessage`. Mirrors [appendEncodeUseCodecScalar] /
 * [appendDecodeUseCodecScalar] minus the bounding-codec branch:
 * the by-name-resolved codec is never a `BoundingLengthCodec` (those
 * are user-supplied length codecs, never `@ProtocolMessage` body
 * codecs).
 */
internal fun appendDecodeProtocolMessageScalar(
    body: CodeBlock.Builder,
    field: FieldSpec.ProtocolMessageScalar,
) {
    body.addStatement("val %L = %T.decode(buffer, context)", field.name, field.codecType)
}

internal fun appendEncodeProtocolMessageScalar(
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
internal fun appendDecodeLengthPrefixedUseCodecList(
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
internal fun appendDecodeLengthPrefixedUseCodecPayload(
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
internal fun appendDecodeLengthPrefixedListBody(
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
 * count by probing each element codec's `wireSize`, writes the prefix
 * via the user codec's `encode`, then iterates and encodes elements.
 * A BackPatch element wireSize at runtime falls back to the
 * scratch-staging `LengthPrefixedListEncoder` — see
 * [appendEncodeLengthPrefixedListBody] for the emitted shape.
 */
internal fun appendEncodeLengthPrefixedUseCodecList(
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
internal fun appendEncodeLengthPrefixedUseCodecPayload(
    body: CodeBlock.Builder,
    field: FieldSpec.LengthPrefixedUseCodecPayload,
) {
    val sizePosVar = "${field.name}SizePosition"
    val bodyStartVar = "${field.name}BodyStart"
    val endPosVar = "${field.name}EndPosition"
    val byteCountVar = "${field.name}ByteCount"
    body.addStatement("val %L = buffer.position()", sizePosVar)
    appendPrefixSlotReservation(body, field.prefixWidth)
    body.addStatement("val %L = buffer.position()", bodyStartVar)
    body.addStatement(
        "%T.encode(buffer, value.%L, context)",
        field.payloadCodecType,
        field.name,
    )
    body.addStatement("val %L = buffer.position()", endPosVar)
    body.addStatement("val %L = %L - %L", byteCountVar, endPosVar, bodyStartVar)
    if (field.prefixWidth < Int.SIZE_BYTES) {
        val maxValue = (1L shl (field.prefixWidth * Byte.SIZE_BITS)) - 1
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
 * pre-measure `as WireSize.Exact` cast doesn't apply. Delegate to the
 * runtime `LengthPrefixedListEncoder`, which stages the elements into a
 * growable scratch to measure the exact body size, writes the VBI
 * prefix, then bulk-copies the body:
 * ```
 * LengthPrefixedListEncoder.encode(
 *     buffer, BufferFactory.Default, <codecType>, <accessor>, ElementCodec, context,
 * )
 * ```
 * The scratch grows on demand (via the codec module's
 * `GrowableWriteBufferPool`), so a list section of any size encodes
 * correctly. An earlier emit used a fixed 64-byte scratch that silently
 * truncated sections over 64 bytes.
 *
 * **Data-class elements** — probe each element's `wireSize`; when all
 * report Exact, write the VBI prefix from the sum and iterate. If any
 * element reports BackPatch at runtime (an element shape the analyze-time
 * scan under-detects), fall back to the scratch path above instead of
 * crashing — closing the latent ClassCastException Audit 2b flagged.
 */
internal fun appendEncodeLengthPrefixedListBody(
    body: CodeBlock.Builder,
    spec: LengthPrefixedListSpec,
    accessor: String,
    namespacePrefix: String,
) {
    if (spec.elementIsBackPatch) {
        // Elements are BackPatch-sized, so the body byte count isn't known until they're
        // encoded. Stage into a growable scratch (via LengthPrefixedListEncoder) that grows
        // to any size, measures the exact body, writes the length prefix, then bulk-copies.
        body.addStatement(
            "%T.encode(buffer, %T.%M, %T, %L, %T, context)",
            LENGTH_PREFIXED_LIST_ENCODER_CN,
            BUFFER_FACTORY_CN,
            BUFFER_FACTORY_DEFAULT_MN,
            spec.codecType,
            accessor,
            spec.elementCodecClassName,
        )
    } else {
        // Pre-measure path for elements the analyzer classified Exact.
        // Probe each element's wireSize rather than casting: if a shape the
        // analyze-time scan under-detects reports BackPatch at runtime,
        // fall back to the scratch-staging LengthPrefixedListEncoder (the
        // same path always-BackPatch elements take) instead of throwing
        // ClassCastException mid-encode.
        val bodyBytesVar = "__${namespacePrefix}BodyBytes"
        val allExactVar = "__${namespacePrefix}AllExact"
        body.addStatement("var %L = 0", bodyBytesVar)
        body.addStatement("var %L = true", allExactVar)
        body.beginControlFlow("for (__elem in %L)", accessor)
        body.addStatement("val __elemSize = %T.wireSize(__elem, context)", spec.elementCodecClassName)
        body.beginControlFlow("if (__elemSize !is %T.Exact)", WIRE_SIZE_CN)
        body.addStatement("%L = false", allExactVar)
        body.addStatement("break")
        body.endControlFlow()
        body.addStatement("%L += __elemSize.bytes", bodyBytesVar)
        body.endControlFlow()
        body.beginControlFlow("if (%L)", allExactVar)
        body.addStatement(
            "%T.encode(buffer, %L.toUInt(), context)",
            spec.codecType,
            bodyBytesVar,
        )
        body.beginControlFlow("for (__elem in %L)", accessor)
        body.addStatement("%T.encode(buffer, __elem, context)", spec.elementCodecClassName)
        body.endControlFlow()
        body.nextControlFlow("else")
        body.addStatement(
            "%T.encode(buffer, %T.%M, %T, %L, %T, context)",
            LENGTH_PREFIXED_LIST_ENCODER_CN,
            BUFFER_FACTORY_CN,
            BUFFER_FACTORY_DEFAULT_MN,
            spec.codecType,
            accessor,
            spec.elementCodecClassName,
        )
        body.endControlFlow()
    }
}

/**
 * Reserve a length-prefix slot by writing `prefixWidth` placeholder bytes.
 * MUST be a write, not a forward `buffer.position(pos + width)` seek: at a
 * capacity boundary a seek past the limit throws a platform-dependent,
 * non-retryable exception (java.nio signals `IllegalArgumentException`),
 * which escapes `encodeToPlatformBuffer`'s grow-and-retry loop. A
 * placeholder write overflows with the retryable [BufferOverflowException]
 * — and on a growable scratch buffer it triggers growth, which a bare seek
 * would not. The real prefix is back-patched over the placeholders once the
 * body's byte count is known.
 */
internal fun appendPrefixSlotReservation(
    body: CodeBlock.Builder,
    prefixWidth: Int,
) {
    body.addStatement("repeat(%L) { buffer.writeUByte(0u) }", prefixWidth)
}

internal fun appendBufferPrefixEncode(
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
