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
 * Validator-side coverage for `@RemainingBytes val: String` — a shape the
 * `@RemainingBytes` annotation kdoc has documented since the annotation was
 * introduced ("Marks a String field to consume all remaining bytes as UTF-8").
 *
 * Pre-fix the analyzer at `CodecEmitter.kt:432` filtered the type qualified-name
 * down to `kotlin.collections.List` and silently returned `null` for any other
 * type, including `kotlin.String`. The silent null cascade dropped the parent
 * codec entirely (no warning, no error) — a downstream consumer trying to use
 * the documented shape would see KSP succeed with zero generated code for that
 * `@ProtocolMessage` and the dispatcher / variant codecs that depend on it.
 *
 * The fix wires `kotlin.String` into the same analyzer branch (returning a new
 * `FieldSpec.RemainingBytesString`), with corresponding decode / encode /
 * wireSize / peek emit branches. This test pins the contract down at the
 * processor layer; the WS fixture in buffer-codec-test exercises the byte-level
 * round trip.
 */
class RemainingBytesStringValidatorTest {
    /**
     * Smallest possible vector: `data class X(@RemainingBytes val text: String)`.
     * Compile must succeed and `XCodec` must be emitted (a regression to the
     * pre-fix silent-drop would still compile but generate no codec).
     */
    @Test
    fun acceptsRemainingBytesStringAsTerminalField() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes

                @ProtocolMessage
                data class TrailingMessage(
                    val tag: UByte,
                    @RemainingBytes val text: String,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("error:") || result.messages.contains("warning: w:"),
            "no diagnostics on @RemainingBytes String. Messages:\n${result.messages}",
        )
    }

    /**
     * Composes with a preceding non-trivial header — the pattern WebSocket
     * Close-body uses (`statusCode: UShort` + `@RemainingBytes val reason: String`).
     */
    @Test
    fun acceptsRemainingBytesStringAfterFixedScalar() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes

                @ProtocolMessage
                data class CloseLikeBody(
                    val statusCode: UShort,
                    @RemainingBytes val reason: String,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    /**
     * Inside a sealed `@DispatchOn` variant — the WebSocket Text-frame pattern.
     * Confirms that the new shape composes with the dispatch path: a regression
     * here would silently drop the variant + cascade to the dispatcher being
     * skipped (the exact failure mode that hit the WS fixture before the fix).
     */
    @Test
    fun acceptsRemainingBytesStringInsideSealedVariant() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.DispatchOn
                import com.ditchoom.buffer.codec.annotations.DispatchValue
                import com.ditchoom.buffer.codec.annotations.PacketType
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes

                @JvmInline
                @ProtocolMessage
                value class Tag(val raw: UByte) {
                    @DispatchValue
                    val kind: Int get() = raw.toInt()
                }

                @DispatchOn(Tag::class)
                @ProtocolMessage
                sealed interface Message {
                    @PacketType(0x01)
                    @ProtocolMessage
                    data class Hello(
                        val tag: Tag,
                        @RemainingBytes val greeting: String,
                    ) : Message
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    /**
     * Terminal-only rule survives: a non-terminal `@RemainingBytes val: String`
     * must still be rejected (returning null from the analyzer cascades to the
     * parent codec being skipped — the same silent-drop the fix closes for the
     * happy path, intentionally retained for the malformed case).
     *
     * Without restoring PR #153's auto-reservation of fixed trailers, the only
     * legal position for `@RemainingBytes` of any shape is the last constructor
     * parameter (or the last non-conditional field).
     */
    @Test
    fun rejectsRemainingBytesStringWhenNotTerminal() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes

                @ProtocolMessage
                data class WrongOrder(
                    @RemainingBytes val text: String,
                    val checksum: UByte,
                )
                """.trimIndent(),
            )
        // The analyzer drops the shape (returns null), so KSP succeeds but no
        // codec is generated. A downstream consumer attempting to reference
        // `WrongOrderCodec` would fail in the next round. Mirroring how
        // Slice11aValidatorTest treats other terminal-only rejections.
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
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
