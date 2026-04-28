package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.codec.processor.ir.Plan
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

/**
 * Phase 7 emitter for [Plan.Object_] — singleton message with no fields.
 *
 * Covers shape 5 (MQTT v4 PingResponse) from the per-shape catalog.
 *
 * The generated codec is the simplest possible Codec: it returns the singleton
 * instance from `decode`, does nothing on `encode`, returns 0 from `wireSize`,
 * and returns `PeekResult.Size(0)` from `peekFrameSize` (matching the
 * "zero-width frame" contract for header-only PINGREQ/PINGRESP).
 */
class ObjectEmitter {
    fun emit(
        plan: Plan.Object_,
        classType: ClassName,
    ): FileSpec {
        val codecName = ClassName(classType.packageName, classType.simpleNames.joinToString("") + "Codec")
        val type =
            TypeSpec
                .objectBuilder(codecName)
                .addSuperinterface(Names.Codec.parameterizedBy(classType))

        type.addProperty(
            PropertySpec
                .builder("MIN_HEADER_BYTES", INT, KModifier.PUBLIC, KModifier.CONST)
                .initializer("%L", 0)
                .build(),
        )

        type.addFunction(
            FunSpec
                .builder("decode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", Names.ReadBuffer)
                .addParameter("context", Names.DecodeContext)
                .returns(classType)
                .addCode("return %T\n", classType)
                .build(),
        )

        type.addFunction(
            FunSpec
                .builder("encode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("buffer", Names.WriteBuffer)
                .addParameter(ParameterSpec.builder("value", classType).build())
                .addParameter("context", Names.EncodeContext)
                .build(),
        )

        type.addFunction(
            FunSpec
                .builder("wireSize")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter(ParameterSpec.builder("value", classType).build())
                .addParameter("context", Names.EncodeContext)
                .returns(INT)
                .addCode("return 0\n")
                .build(),
        )

        type.addFunction(
            FunSpec
                .builder("peekFrameSize")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("stream", Names.StreamProcessor)
                .addParameter("baseOffset", INT)
                .returns(Names.PeekResult)
                .addCode("return %T(0)\n", Names.PeekResultSize)
                .build(),
        )

        // Suspending overload — required for parity with the legacy emitter so the
        // stream-processor reader call sites that take a `SuspendingStreamProcessor`
        // (e.g. mqtt + websocket clients) keep the same shape after this codec
        // moves through the new pipeline.
        type.addFunction(
            FunSpec
                .builder("peekFrameSize")
                .addModifiers(KModifier.PUBLIC, KModifier.SUSPEND)
                .addParameter("stream", Names.SuspendingStreamProcessor)
                .addParameter(ParameterSpec.builder("baseOffset", INT).defaultValue("0").build())
                .returns(Names.PeekResult)
                .addCode("return %T(0)\n", Names.PeekResultSize)
                .build(),
        )

        return FileSpec
            .builder(codecName.packageName, codecName.simpleName)
            .addType(type.build())
            .build()
    }
}
