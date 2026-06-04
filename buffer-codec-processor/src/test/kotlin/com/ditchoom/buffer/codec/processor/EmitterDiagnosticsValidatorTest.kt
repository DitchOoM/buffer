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
 * Negative-test coverage for the codec emitter's loud diagnostics.
 *
 * Branch `codec/dispatch-wirewidth-track` migrated the emitter
 * (`CodecEmitter.analyze()` / `analyzeField()`) from silently skipping
 * recognized-but-unsupported shapes (the "Outcome-3" silent-codec bug
 * class) to returning `AnalysisResult.Rejected` / `FieldAnalysis.Err`
 * carrying a `Diagnostic`. `CodecEmitter` forwards every such diagnostic
 * to `logger.error`, failing the build loudly.
 *
 * Each test below constructs the minimal source that reaches one emitter
 * diagnostic and asserts both that the compile fails and that the
 * distinctive emitter substring appears in the messages. Where the
 * earlier-running validator (`ProtocolMessageProcessor`) catches the same
 * shape first with a different message, the test asserts on the message
 * that actually appears (documented inline).
 */
class EmitterDiagnosticsValidatorTest {
    // 1. Unknown/typo annotation on a field.
    @Test
    fun firesOnUnknownAnnotationOnField() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                annotation class Bogus

                @ProtocolMessage
                data class HasBogusField(@Bogus val x: Int)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("unknown/unsupported annotation"),
            "expected emitter unknown-annotation diagnostic. Messages:\n${result.messages}",
        )
    }

    // 2. Nullable field without @When.
    @Test
    fun firesOnNullableFieldWithoutWhen() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class NullableField(val x: Int?)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("nullable field type requires @When"),
            "expected emitter nullable-without-@When diagnostic. Messages:\n${result.messages}",
        )
    }

    // 3. Bare String field without a length annotation.
    @Test
    fun firesOnBareStringField() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class BareString(val s: String)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("String field requires @LengthPrefixed"),
            "expected emitter bare-String diagnostic. Messages:\n${result.messages}",
        )
    }

    // 4. @RemainingBytes on an unsupported type.
    @Test
    fun firesOnRemainingBytesUnsupportedType() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes

                @ProtocolMessage
                data class RemBytesInt(@RemainingBytes val x: Int)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("@RemainingBytes supports only"),
            "expected emitter @RemainingBytes-unsupported-type diagnostic. Messages:\n${result.messages}",
        )
    }

    // 5. @RemainingBytes combined with @LengthPrefixed.
    @Test
    fun firesOnRemainingBytesCombinedWithLengthPrefixed() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes

                @ProtocolMessage
                data class RemBytesPlusLp(@RemainingBytes @LengthPrefixed val s: String)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("@RemainingBytes cannot be combined with @LengthFrom/@LengthPrefixed/@WireBytes"),
            "expected emitter @RemainingBytes-combination diagnostic. Messages:\n${result.messages}",
        )
    }

    // 6. value class with an unsupported scalar inner.
    @Test
    fun firesOnValueClassUnsupportedInner() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @JvmInline
                value class CharWrap(val c: Char)

                @ProtocolMessage
                data class HasCharWrap(val w: CharWrap)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("value class inner type is not a supported scalar"),
            "expected emitter value-class-inner diagnostic. Messages:\n${result.messages}",
        )
    }

    // 7. @WireBytes on a value-class field.
    @Test
    fun firesOnWireBytesOnValueClassField() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.WireBytes

                @JvmInline
                value class IntWrap(val v: Int)

                @ProtocolMessage
                data class HasWireBytesVc(@WireBytes(3) val w: IntWrap)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("@WireBytes on a value-class field is not yet supported"),
            "expected emitter @WireBytes-on-value-class diagnostic. Messages:\n${result.messages}",
        )
    }

    // 8. @LengthFrom combined with @LengthPrefixed.
    @Test
    fun firesOnLengthFromCombinedWithLengthPrefixed() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthFrom
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class LfPlusLp(
                    val len: Int,
                    @LengthFrom("len") @LengthPrefixed val s: String,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        // The validator's adjacency check also fires here; assert on the
        // EMITTER's mutual-exclusion message, which appears alongside it.
        assertTrue(
            result.messages.contains("@LengthFrom cannot be combined with @LengthPrefixed/@WireBytes"),
            "expected emitter @LengthFrom-combination diagnostic. Messages:\n${result.messages}",
        )
    }

    // 9. @LengthPrefixed combined with @WireBytes.
    @Test
    fun firesOnLengthPrefixedCombinedWithWireBytes() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.WireBytes

                @ProtocolMessage
                data class LpPlusWb(@LengthPrefixed @WireBytes(2) val s: String)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("@LengthPrefixed cannot be combined with @WireBytes"),
            "expected emitter @LengthPrefixed-with-@WireBytes diagnostic. Messages:\n${result.messages}",
        )
    }

    // 10. @LengthPrefixed @ProtocolMessage body in a NON-terminal position.
    @Test
    fun firesOnNonTerminalLengthPrefixedMessage() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class Inner(val a: Int)

                @ProtocolMessage
                data class NonTerminalLpMsg(
                    @LengthPrefixed val body: Inner,
                    val trailer: Int,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("must be the last field"),
            "expected emitter terminal-only diagnostic. Messages:\n${result.messages}",
        )
    }

    // 11. @LengthFrom String in a non-terminal position (analyze-level Rejected).
    @Test
    fun firesOnNonTerminalLengthFromString() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthFrom
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class NonTerminalLfString(
                    val len: UShort,
                    val mid: Int,
                    @LengthFrom("len") val s: String,
                    val trailer: Int,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("@LengthFrom val: String must be the last field"),
            "expected emitter @LengthFrom-terminal-only diagnostic. Messages:\n${result.messages}",
        )
    }

    // 12. Multiple bounding fields in one message.
    @Test
    fun firesOnMultipleBoundingFields() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.BoundingLengthCodec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                object Bound1 : BoundingLengthCodec<Int> {
                    override val maxWireSize: Int = 4
                    override fun applyBound(buffer: ReadBuffer, decodedValue: Int) {
                        buffer.setLimit(buffer.position() + decodedValue)
                    }
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): Int = buffer.readInt()
                    override fun encode(buffer: WriteBuffer, value: Int, context: EncodeContext) {
                        buffer.writeInt(value)
                    }
                    override fun wireSize(value: Int, context: EncodeContext): WireSize = WireSize.Exact(4)
                }

                object Bound2 : BoundingLengthCodec<Int> {
                    override val maxWireSize: Int = 4
                    override fun applyBound(buffer: ReadBuffer, decodedValue: Int) {
                        buffer.setLimit(buffer.position() + decodedValue)
                    }
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): Int = buffer.readInt()
                    override fun encode(buffer: WriteBuffer, value: Int, context: EncodeContext) {
                        buffer.writeInt(value)
                    }
                    override fun wireSize(value: Int, context: EncodeContext): WireSize = WireSize.Exact(4)
                }

                @ProtocolMessage
                data class TwoBounds(
                    @UseCodec(Bound1::class) val a: Int,
                    @UseCodec(Bound2::class) val b: Int,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("at most one bounding"),
            "expected emitter multiple-bounding diagnostic. Messages:\n${result.messages}",
        )
    }

    // 13. Declares <P : Payload> but no @RemainingBytes field consumes it.
    @Test
    fun firesOnUnusedPayloadTypeParameter() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.Payload
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class UnusedPayloadTp<P : Payload>(val a: Int)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        // Both the validator (long-form guidance) and the emitter fire on
        // this shape. Assert on the distinctive short-form EMITTER substring.
        assertTrue(
            result.messages.contains("declares <P : Payload> but no @RemainingBytes val: P field uses it"),
            "expected emitter unused-type-parameter diagnostic. Messages:\n${result.messages}",
        )
    }

    // 14. @UseCodec combined with @WireOrder.
    @Test
    fun firesOnUseCodecCombinedWithWireOrder() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.annotations.Endianness
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec
                import com.ditchoom.buffer.codec.annotations.WireOrder

                object IntCodec : Codec<Int> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): Int = buffer.readInt()
                    override fun encode(buffer: WriteBuffer, value: Int, context: EncodeContext) {
                        buffer.writeInt(value)
                    }
                }

                @ProtocolMessage
                data class UseCodecPlusWireOrder(
                    @UseCodec(IntCodec::class) @WireOrder(Endianness.Little) val x: Int,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("@UseCodec cannot be combined with @WireOrder"),
            "expected emitter @UseCodec-with-@WireOrder diagnostic. Messages:\n${result.messages}",
        )
    }

    // 15. A totally unsupported field type.
    @Test
    fun firesOnUnsupportedFieldType() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                class SomeRandomClass

                @ProtocolMessage
                data class HasRandomField(val x: SomeRandomClass)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("unsupported field type"),
            "expected emitter unsupported-field-type diagnostic. Messages:\n${result.messages}",
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
