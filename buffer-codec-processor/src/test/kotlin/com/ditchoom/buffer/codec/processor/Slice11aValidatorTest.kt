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

/**
 * Phase J.M.5 slice 11a — validator-side coverage for the emitter
 * widening on `@When @UseCodec val: T?` (sealed-parent inner) and
 * `@RemainingBytes val: List<E>` (sealed-parent element).
 *
 * Pre-slice these shapes were silently rejected by the emitter
 * (analyzer returned `null` and the codec wasn't generated). The
 * widening adds an explicit branch in [analyzeConditionalInner] for
 * the `@When @UseCodec` case (mirror of [analyzeUseCodecScalarField])
 * and lifts [analyzeRemainingBytesProtocolMessageListField] to accept
 * sealed parents (mirror of audit-2a's
 * [analyzeLengthPrefixedListSpec]).
 *
 * Slice 11b substitutes typed `V5XReasonCode` parents into the v5
 * fixture and lands the byte-level coverage.
 */
class Slice11aValidatorTest {
    @Test
    fun acceptsConditionalUseCodecOnScalarField() {
        // Pre-slice this shape fell through `analyzeConditionalInner`'s
        // bare-scalar branch and returned null silently because the
        // analyzer required no `@UseCodec` annotation alongside `@When`
        // on a scalar inner. Slice 11a adds the explicit branch.
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
                import com.ditchoom.buffer.codec.annotations.When

                object IntPassthroughCodec : Codec<Int> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): Int = buffer.readInt()
                    override fun encode(buffer: WriteBuffer, value: Int, context: EncodeContext) {
                        buffer.writeInt(value)
                    }
                    override fun wireSize(value: Int, context: EncodeContext): WireSize = WireSize.Exact(4)
                }

                @ProtocolMessage
                data class HeaderWithConditionalUseCodec(
                    val present: Boolean,
                    @When("present") @UseCodec(IntPassthroughCodec::class) val n: Int? = null,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@UseCodec"),
            "no @UseCodec diagnostic should fire on @When @UseCodec scalar inner. Messages:\n${result.messages}",
        )
    }

    @Test
    fun acceptsConditionalUseCodecOnSealedParentField() {
        // The slice 11a widening's primary motivation: typed reason
        // codes on the v5 acks (slice 11b lands the substitution).
        // Validator path is the same as the scalar case — only the
        // analyzer-side branch differs (it accepts the sealed parent
        // via the `KSClassDeclaration` fallback in the type resolver).
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
                import com.ditchoom.buffer.codec.annotations.PacketType
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec
                import com.ditchoom.buffer.codec.annotations.When

                @ProtocolMessage
                sealed interface ReasonCode {
                    @ProtocolMessage @PacketType(0x00) data class Success(val tag: UByte = 0x00u) : ReasonCode
                    @ProtocolMessage @PacketType(0x80) data class Error(val tag: UByte = 0x80u) : ReasonCode
                }

                // Hand-written delegate: KSP-generated `ReasonCodeCodec`
                // isn't visible to the round-1 analyzer that resolves
                // `@UseCodec(...)` references. Slice 11b's v5 fixture
                // uses the same hand-written-delegate pattern.
                object ReasonCodeDelegateCodec : Codec<ReasonCode> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): ReasonCode =
                        ReasonCodeCodec.decode(buffer, context)
                    override fun encode(buffer: WriteBuffer, value: ReasonCode, context: EncodeContext) {
                        ReasonCodeCodec.encode(buffer, value, context)
                    }
                    override fun wireSize(value: ReasonCode, context: EncodeContext): WireSize =
                        ReasonCodeCodec.wireSize(value, context)
                }

                @ProtocolMessage
                data class Ack(
                    val present: Boolean,
                    @When("present") @UseCodec(ReasonCodeDelegateCodec::class) val rc: ReasonCode? = null,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("not yet supported"),
            "no deferred-shape diagnostic should fire on @When @UseCodec sealed-parent inner. Messages:\n${result.messages}",
        )
    }

    @Test
    fun acceptsRemainingBytesListOnSealedParentElement() {
        // Audit-2a lifted `@LengthPrefixed @UseCodec val: List<E>` to
        // accept sealed-parent E. Slice 11a applies the same lift to
        // `@RemainingBytes val: List<E>`. Element codec is auto-
        // generated and resolved internally by the emitter (no
        // `@UseCodec` reference, no chicken-and-egg).
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.PacketType
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes

                @ProtocolMessage
                sealed interface Frame {
                    @ProtocolMessage @PacketType(0x01) data class Ping(val tag: UByte = 0x01u) : Frame
                    @ProtocolMessage @PacketType(0x02) data class Pong(val tag: UByte = 0x02u) : Frame
                }

                @ProtocolMessage
                data class FrameBatch(
                    @RemainingBytes val frames: List<Frame>,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@RemainingBytes"),
            "no @RemainingBytes diagnostic should fire on a sealed-parent element. Messages:\n${result.messages}",
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
