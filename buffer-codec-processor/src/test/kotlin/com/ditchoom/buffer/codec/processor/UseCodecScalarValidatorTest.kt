package com.ditchoom.buffer.codec.processor

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.useKsp2
import org.intellij.lang.annotations.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase I.1 step 3 — `@UseCodec` validator coverage for the bare-scalar
 * shape (no framing annotation). The bare shape unblocks user-supplied
 * length codecs like `MqttRemainingLengthCodec`. Slice 10a's
 * `@RemainingBytes @UseCodec val: P` shape and the deferred
 * `@LengthPrefixed @UseCodec` / `@LengthFrom @UseCodec` shapes still produce
 * the same diagnostics as before.
 */
class UseCodecScalarValidatorTest {
    @Test
    fun acceptsBareUseCodecOnScalarFieldWithMatchingCodec() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                object UIntPassthroughCodec : Codec<UInt> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                }

                @ProtocolMessage
                data class HeaderWithUseCodecScalar(
                    @UseCodec(UIntPassthroughCodec::class) val length: UInt,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@UseCodec"),
            "no @UseCodec diagnostic should fire on a bare scalar shape. Messages:\n${result.messages}",
        )
    }

    @Test
    fun rejectsBareUseCodecOnScalarWithMismatchedCodecType() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                object IntCodec : Codec<Int> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): Int = buffer.readInt()
                    override fun encode(buffer: WriteBuffer, value: Int, context: EncodeContext) {
                        buffer.writeInt(value)
                    }
                    override fun wireSize(value: Int, context: EncodeContext): WireSize = WireSize.Exact(4)
                }

                @ProtocolMessage
                data class HeaderWithMismatchedCodec(
                    @UseCodec(IntCodec::class) val length: UInt,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("does not implement"),
            "diagnostic should report the Codec<T> type mismatch. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("HeaderWithMismatchedCodec.length"),
            "diagnostic should reference the offending field. Messages:\n${result.messages}",
        )
    }

    @Test
    fun rejectsBareUseCodecOnScalarWhenTargetIsNotObject() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                class UIntClassCodec : Codec<UInt> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                }

                @ProtocolMessage
                data class HeaderWithClassCodec(
                    @UseCodec(UIntClassCodec::class) val length: UInt,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("not a Kotlin `object` declaration"),
            "diagnostic should reject the non-object target. Messages:\n${result.messages}",
        )
    }

    @Test
    fun rejectsLengthPrefixedUseCodecAsDeferred() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                object UIntPassthroughCodec : Codec<UInt> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                }

                @ProtocolMessage
                data class HeaderWithLengthPrefixedUseCodec(
                    @LengthPrefixed @UseCodec(UIntPassthroughCodec::class) val n: UInt,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("not yet supported"),
            "@LengthPrefixed @UseCodec should still produce the deferred diagnostic. Messages:\n${result.messages}",
        )
    }

    @Test
    fun rejectsLengthFromUseCodecAsDeferred() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.LengthFrom
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                object UIntPassthroughCodec : Codec<UInt> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                }

                @ProtocolMessage
                data class HeaderWithLengthFromUseCodec(
                    val length: Int,
                    @LengthFrom("length") @UseCodec(UIntPassthroughCodec::class) val payload: UInt,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("not yet supported"),
            "@LengthFrom @UseCodec should still produce the deferred diagnostic. Messages:\n${result.messages}",
        )
    }

    @Test
    fun rejectsRemainingBytesUseCodecOnNonPayloadScalar() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes
                import com.ditchoom.buffer.codec.annotations.UseCodec

                object UIntPassthroughCodec : Codec<UInt> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                }

                @ProtocolMessage
                data class HeaderWithRemainingBytesScalar(
                    @RemainingBytes @UseCodec(UIntPassthroughCodec::class) val n: UInt,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("requires the bound") &&
                result.messages.contains("Payload"),
            "@RemainingBytes @UseCodec on a non-Payload field should still be rejected. " +
                "Messages:\n${result.messages}",
        )
    }

    @Test
    fun acceptsBareUseCodecOnValueClassScalarField() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                @JvmInline
                value class FrameLength(val raw: UInt)

                object FrameLengthCodec : Codec<FrameLength> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): FrameLength =
                        FrameLength(buffer.readInt().toUInt())
                    override fun encode(buffer: WriteBuffer, value: FrameLength, context: EncodeContext) {
                        buffer.writeInt(value.raw.toInt())
                    }
                    override fun wireSize(value: FrameLength, context: EncodeContext): WireSize =
                        WireSize.Exact(4)
                }

                @ProtocolMessage
                data class HeaderWithValueClassScalar(
                    @UseCodec(FrameLengthCodec::class) val length: FrameLength,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@UseCodec"),
            "no @UseCodec diagnostic should fire on a bare value-class shape. Messages:\n${result.messages}",
        )
    }

    private fun compile(
        @Language("kotlin") source: String,
    ): JvmCompilationResult =
        KotlinCompilation()
            .apply {
                sources = listOf(SourceFile.kotlin("Test.kt", source))
                inheritClassPath = true
                messageOutputStream = System.out
                useKsp2()
                configureKsp {
                    symbolProcessorProviders += ProtocolMessageProcessorProvider()
                }
            }.compile()
}
