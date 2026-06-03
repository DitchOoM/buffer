package com.ditchoom.buffer.codec.processor

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.useKsp2
import org.intellij.lang.annotations.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `@ForwardCompatible` validator coverage:
 *
 *   - **F1** — requires `@FramedBy` on the same type.
 *   - **F2** — requires `@DispatchOn` with a single-byte discriminator.
 *   - **F3** — exactly one `@UnknownVariant` sealed member.
 *   - **F4** — the `@UnknownVariant` member must not carry `@PacketType`.
 *   - **F5** — the `@UnknownVariant` member must be `(opcode: Int, raw: PlatformBuffer)`.
 *
 * Plus an acceptance test: a well-formed forward-compatible union compiles
 * cleanly and emits its dispatcher.
 */
class ForwardCompatibleValidatorTest {
    // Shared preamble: a BoundingLengthCodec<UInt> framing codec and a
    // single-byte @DispatchOn discriminator value class.
    private val preamble =
        """
        package test

        import com.ditchoom.buffer.PlatformBuffer
        import com.ditchoom.buffer.ReadBuffer
        import com.ditchoom.buffer.WriteBuffer
        import com.ditchoom.buffer.codec.BoundingLengthCodec
        import com.ditchoom.buffer.codec.DecodeContext
        import com.ditchoom.buffer.codec.EncodeContext
        import com.ditchoom.buffer.codec.WireSize
        import com.ditchoom.buffer.codec.annotations.DispatchOn
        import com.ditchoom.buffer.codec.annotations.DispatchValue
        import com.ditchoom.buffer.codec.annotations.ForwardCompatible
        import com.ditchoom.buffer.codec.annotations.FramedBy
        import com.ditchoom.buffer.codec.annotations.PacketType
        import com.ditchoom.buffer.codec.annotations.ProtocolMessage
        import com.ditchoom.buffer.codec.annotations.UnknownVariant

        object LenCodec : BoundingLengthCodec<UInt> {
            override val maxWireSize: Int = 4
            override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readUByte().toUInt()
            override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                buffer.writeUByte(value.toUByte())
            }
            override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(1)
            override fun applyBound(buffer: ReadBuffer, decodedValue: UInt) {
                buffer.setLimit(buffer.position() + decodedValue.toInt())
            }
        }

        @JvmInline
        @ProtocolMessage
        value class Op8(val raw: UByte) {
            @DispatchValue
            val code: Int get() = raw.toInt()
        }

        """.trimIndent()

    @Test
    fun acceptsWellFormedForwardCompatibleUnion() {
        val result =
            compile(
                preamble +
                    """

                    @ProtocolMessage
                    @DispatchOn(Op8::class)
                    @FramedBy(LenCodec::class, after = "header")
                    @ForwardCompatible(unknown = Frame.Unknown::class)
                    sealed interface Frame {
                        @ProtocolMessage
                        @PacketType(value = 0x12)
                        data class Known(val header: Op8, val a: UByte) : Frame

                        @UnknownVariant
                        data class Unknown(val opcode: Int, val raw: PlatformBuffer) : Frame
                    }
                    """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun rejectsForwardCompatibleWithoutFramedBy() {
        // F1
        val result =
            compile(
                preamble +
                    """

                    @ProtocolMessage
                    @DispatchOn(Op8::class)
                    @ForwardCompatible(unknown = Frame.Unknown::class)
                    sealed interface Frame {
                        @ProtocolMessage
                        @PacketType(value = 0x12)
                        data class Known(val header: Op8, val a: UByte) : Frame

                        @UnknownVariant
                        data class Unknown(val opcode: Int, val raw: PlatformBuffer) : Frame
                    }
                    """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("framing length"),
            "expected the rule-1 (no framing) diagnostic, got:\n${result.messages}",
        )
    }

    @Test
    fun rejectsTwoUnknownVariants() {
        // F3
        val result =
            compile(
                preamble +
                    """

                    @ProtocolMessage
                    @DispatchOn(Op8::class)
                    @FramedBy(LenCodec::class, after = "header")
                    @ForwardCompatible(unknown = Frame.UnknownA::class)
                    sealed interface Frame {
                        @ProtocolMessage
                        @PacketType(value = 0x12)
                        data class Known(val header: Op8, val a: UByte) : Frame

                        @UnknownVariant
                        data class UnknownA(val opcode: Int, val raw: PlatformBuffer) : Frame

                        @UnknownVariant
                        data class UnknownB(val opcode: Int, val raw: PlatformBuffer) : Frame
                    }
                    """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("exactly one sealed member marked @UnknownVariant"),
            "expected the rule-4 (one unknown variant) diagnostic, got:\n${result.messages}",
        )
    }

    @Test
    fun rejectsUnknownVariantCarryingPacketType() {
        // F4
        val result =
            compile(
                preamble +
                    """

                    @ProtocolMessage
                    @DispatchOn(Op8::class)
                    @FramedBy(LenCodec::class, after = "header")
                    @ForwardCompatible(unknown = Frame.Unknown::class)
                    sealed interface Frame {
                        @ProtocolMessage
                        @PacketType(value = 0x12)
                        data class Known(val header: Op8, val a: UByte) : Frame

                        @UnknownVariant
                        @PacketType(value = 0x99)
                        data class Unknown(val opcode: Int, val raw: PlatformBuffer) : Frame
                    }
                    """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("must not carry @PacketType"),
            "expected the rule-3 (@UnknownVariant has @PacketType) diagnostic, got:\n${result.messages}",
        )
    }

    @Test
    fun rejectsMalformedUnknownVariantShape() {
        // F5 — raw is a ByteArray, not a buffer.
        val result =
            compile(
                preamble +
                    """

                    @ProtocolMessage
                    @DispatchOn(Op8::class)
                    @FramedBy(LenCodec::class, after = "header")
                    @ForwardCompatible(unknown = Frame.Unknown::class)
                    sealed interface Frame {
                        @ProtocolMessage
                        @PacketType(value = 0x12)
                        data class Known(val header: Op8, val a: UByte) : Frame

                        @UnknownVariant
                        data class Unknown(val opcode: Int, val raw: ByteArray) : Frame
                    }
                    """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("opcode: Int, raw: PlatformBuffer"),
            "expected the rule-2 (unknown variant shape) diagnostic, got:\n${result.messages}",
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
