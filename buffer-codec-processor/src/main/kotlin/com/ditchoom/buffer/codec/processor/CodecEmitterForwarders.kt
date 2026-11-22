package com.ditchoom.buffer.codec.processor

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName

/*
 * CodecEmitterForwarders — the deprecated `peekFrameSize` / `wireSize` overrides every
 * generated codec carries next to `frameSize` / `wireBytes`, so callers still on the boxed
 * API get the generated answer instead of the interface's NoFraming / BackPatch default.
 */

internal val WIRE_SIZE_CN = ClassName("com.ditchoom.buffer.codec", "WireSize")
internal val PEEK_RESULT_CN = ClassName("com.ditchoom.buffer.codec", "PeekResult")
private val TO_PEEK_RESULT_MN = MemberName("com.ditchoom.buffer.codec", "toPeekResult")
private val TO_WIRE_SIZE_MN = MemberName("com.ditchoom.buffer.codec", "toWireSize")

/** The deprecated `peekFrameSize`, forwarding to [peek] (the `frameSize` spec). */
internal fun legacyPeekFrameSizeFun(peek: FunSpec): FunSpec =
    FunSpec
        .builder("peekFrameSize")
        .addAnnotations(deprecatedForwarder("frameSize(stream, baseOffset)"))
        .addModifiers(KModifier.OVERRIDE)
        .addParameter("stream", STREAM_PROCESSOR_CN)
        .addParameter("baseOffset", INT)
        .returns(PEEK_RESULT_CN)
        .addStatement("return %N(stream, baseOffset).%M(stream.available() - baseOffset)", peek, TO_PEEK_RESULT_MN)
        .build()

/** [peek] followed by its [legacyPeekFrameSizeFun], for placements that add both as members. */
internal fun withLegacyPeekFrameSize(peek: FunSpec): List<FunSpec> = listOf(peek, legacyPeekFrameSizeFun(peek))

/** The deprecated `wireSize`, forwarding to [wireBytes] (the `wireBytes` spec). */
internal fun legacyWireSizeFun(wireBytes: FunSpec): FunSpec =
    FunSpec
        .builder("wireSize")
        .addAnnotations(deprecatedForwarder("wireBytes(value, context)"))
        .addModifiers(wireBytes.modifiers)
        .addParameters(wireBytes.parameters)
        .returns(WIRE_SIZE_CN)
        .addStatement("return %N(value, context).%M()", wireBytes, TO_WIRE_SIZE_MN)
        .build()

/** [wireBytes] followed by its [legacyWireSizeFun]. */
internal fun withLegacyWireSize(wireBytes: FunSpec): List<FunSpec> = listOf(wireBytes, legacyWireSizeFun(wireBytes))

/** `@Deprecated` + `@Suppress("DEPRECATION")` for an override forwarding to [replaceWith]. */
private fun deprecatedForwarder(replaceWith: String): List<AnnotationSpec> =
    listOf(
        AnnotationSpec
            .builder(Deprecated::class)
            .addMember(
                "message = %S",
                "Call ${replaceWith.substringBefore('(')}, which does not box. Removed in the next major release.",
            ).addMember("replaceWith = %T(%S)", ReplaceWith::class, replaceWith)
            .build(),
        AnnotationSpec
            .builder(Suppress::class)
            .addMember("%S", "DEPRECATION")
            .build(),
    )
