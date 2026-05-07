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
     * J.M.6.c (issue #151 part 2) — `@RemainingBytes` followed by a
     * fixed-size scalar trailer is now ACCEPTED. The analyzer subtracts
     * the trailer's wire bytes from `buffer.limit()` before the body
     * read, so the trailer survives intact.
     *
     * This is the shape PR #153's TrailingChecksum fixture pinned and
     * the J.M.6.c PNG fixture exercises end-to-end (length + type +
     * `@RemainingBytes` data + 4-byte CRC).
     */
    @Test
    fun acceptsRemainingBytesStringWithFixedScalarTrailer() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes

                @ProtocolMessage
                data class TextWithChecksum(
                    @RemainingBytes val text: String,
                    val checksum: UByte,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("error:"),
            "fixed-size scalar trailer must be accepted. Messages:\n${result.messages}",
        )
    }

    /**
     * J.M.6.c — `@RemainingBytes` followed by a *variable-size* trailer
     * is rejected with a focused error naming both the body field and
     * the offending trailer. Variable-size trailers (here: a second
     * `@LengthPrefixed val: String`) leave the body decode with no way
     * to know its end without re-encoding.
     */
    @Test
    fun rejectsRemainingBytesStringFollowedByVariableSizeTrailer() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes

                @ProtocolMessage
                data class TextWithVariableTrailer(
                    @RemainingBytes val text: String,
                    @LengthPrefixed val footer: String,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("@RemainingBytes on test.TextWithVariableTrailer.text"),
            "diagnostic should name the body field. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("test.TextWithVariableTrailer.footer"),
            "diagnostic should name the offending trailer. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("@LengthPrefixed"),
            "diagnostic should mention the trailing annotation. Messages:\n${result.messages}",
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
