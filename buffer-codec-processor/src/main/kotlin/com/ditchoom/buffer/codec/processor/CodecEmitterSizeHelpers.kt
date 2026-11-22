package com.ditchoom.buffer.codec.processor

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT

/*
 * Small emit helpers that unwrap a nested `WireByteCount` on the size path.
 */

/**
 * `val <sizeVar> = <codec>.wireBytes(<valueExpr>, context)`, unwrapped: a BackPatch
 * answer makes the enclosing `wireBytes` return BackPatch.
 */
internal fun appendExactOrBackPatch(
    builder: FunSpec.Builder,
    sizeVar: String,
    codec: CodeBlock,
    valueExpr: String,
) {
    builder.addStatement(
        "val %L = %L.wireBytes(%L, context).fold(exact = { it }, backPatch = { return %T.BackPatch })",
        sizeVar,
        codec,
        valueExpr,
        WIRE_BYTE_COUNT_CN,
    )
}

/**
 * `val <sizeVar>: Int` = the exact encoded width of a `@FramedBy` header [valueExpr]
 * through [codec]; anything else throws `FramingSizeException`.
 */
internal fun appendFramingHeaderSize(
    body: CodeBlock.Builder,
    sizeVar: String,
    codec: ClassName,
    valueExpr: String,
) {
    body.addStatement(
        "val %L = %T.wireBytes(%L, context).fold(exact = { if (it > %T.MAX_VALUE) throw %T(%T) else it.toInt() }, " +
            "backPatch = { throw %T(%T) })",
        sizeVar,
        codec,
        valueExpr,
        INT,
        FRAMING_SIZE_EXCEPTION_CN,
        codec,
        FRAMING_SIZE_EXCEPTION_CN,
        codec,
    )
}
