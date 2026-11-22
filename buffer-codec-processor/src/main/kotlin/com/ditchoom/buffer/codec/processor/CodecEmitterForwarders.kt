package com.ditchoom.buffer.codec.processor

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier

/*
 * CodecEmitterForwarders — the deprecated `peekFrameSize` / `wireSize` forwarders every
 * generated codec carries next to `frameSize` / `wireBytes`, and the small emit helpers
 * that unwrap a nested `WireByteCount`.
 */

/**
 * The deprecated `peekFrameSize`, forwarding to [peek] (the `frameSize` spec) so callers
 * still on [PeekResult] get the same answer.
 */
internal fun legacyPeekFrameSizeFun(peek: FunSpec): FunSpec =
    FunSpec
        .builder("peekFrameSize")
        .addAnnotations(deprecatedForwarder("frameSize(stream, baseOffset)"))
        .addModifiers(KModifier.OVERRIDE)
        .addParameter("stream", STREAM_PROCESSOR_CN)
        .addParameter("baseOffset", INT)
        .returns(PEEK_RESULT_CN)
        .addStatement("return %N(stream, baseOffset).toPeekResult(stream.available() - baseOffset)", peek)
        .build()

/**
 * `@Deprecated` + `@Suppress("DEPRECATION")` for a generated override of a deprecated
 * member that forwards to its replacement, [replaceWith].
 */
internal fun deprecatedForwarder(replaceWith: String): List<AnnotationSpec> =
    listOf(
        AnnotationSpec
            .builder(Deprecated::class)
            .addMember("message = %S", "Call ${replaceWith.substringBefore('(')}.")
            .addMember("replaceWith = %T(%S)", ReplaceWith::class, replaceWith)
            .build(),
        AnnotationSpec
            .builder(Suppress::class)
            .addMember("%S", "DEPRECATION")
            .build(),
    )

/** [peek] followed by its [legacyPeekFrameSizeFun], for placements that add both as members. */
internal fun withLegacyPeekFrameSize(peek: FunSpec): List<FunSpec> = listOf(peek, legacyPeekFrameSizeFun(peek))

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
 * The deprecated `wireSize`, forwarding to [wireBytes] (the `wireBytes` spec) so callers
 * still on `WireSize` get the same answer.
 */
internal fun legacyWireSizeFun(wireBytes: FunSpec): FunSpec =
    FunSpec
        .builder("wireSize")
        .addAnnotations(deprecatedForwarder("wireBytes(value, context)"))
        .addModifiers(wireBytes.modifiers)
        .addParameters(wireBytes.parameters)
        .returns(WIRE_SIZE_CN)
        .addStatement("return %N(value, context).toWireSize()", wireBytes)
        .build()

/** [wireBytes] followed by its [legacyWireSizeFun]. */
internal fun withLegacyWireSize(wireBytes: FunSpec): List<FunSpec> = listOf(wireBytes, legacyWireSizeFun(wireBytes))

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
