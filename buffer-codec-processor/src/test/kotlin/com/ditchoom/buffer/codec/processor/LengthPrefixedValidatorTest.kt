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
 * Compile-time validator coverage for `@LengthPrefixed`.
 *
 * Rule R3 (annotation widening): `@LengthPrefixed` already covers
 * `String`-typed fields and now also covers `@ProtocolMessage` data
 * class fields under identical semantics — encode emits the prefix
 * carrying the body's `wireSize` in bytes; decode reads the prefix and
 * bounds inner decode. The processor must accept both shapes silently.
 *
 * R3 stays scoped to `String` + `@ProtocolMessage`. Payload-typed
 * widening is — when that lands, R1's Payload exclusion is
 * removed and R3 expands to accept `@Payload`-typed type parameters.
 */
class LengthPrefixedValidatorTest {
    @Test
    fun acceptsLengthPrefixedOnStringField() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class Greeting(
                    @LengthPrefixed val name: String,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@LengthPrefixed"),
            "@LengthPrefixed on String must compile silently. Messages:\n${result.messages}",
        )
    }

    @Test
    fun acceptsLengthPrefixedOnProtocolMessageField() {
        // R3 widening: `@LengthPrefixed` accepts a `@ProtocolMessage` data
        // class field. The validator must stay silent — 's
        // redesign-2 (`WavFmtChunk` → `WavFmtBody`) depends on this shape.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthPrefix
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class Body(val a: UByte, val b: UByte)

                @ProtocolMessage
                data class Frame(
                    val fourCC: UInt,
                    @LengthPrefixed(LengthPrefix.Int) val body: Body,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@LengthPrefixed"),
            "@LengthPrefixed on a @ProtocolMessage field must compile silently. " +
                "Messages:\n${result.messages}",
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
