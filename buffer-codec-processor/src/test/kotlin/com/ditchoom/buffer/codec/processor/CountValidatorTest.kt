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
 * Compile-time validator coverage for `@Count` (element-count-prefixed lists).
 *
 * `@Count` is mutually exclusive with the byte-length list framings
 * (`@LengthPrefixed` / `@LengthFrom` / `@RemainingBytes`) on the same field —
 * they are alternative ways to frame a list (element count vs. byte span) and
 * combining them is meaningless. Each combination is a KSP compile error.
 */
class CountValidatorTest {
    @Test
    fun acceptsCountOnProtocolMessageList() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.Count
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class Elem(val a: UByte, val b: UByte)

                @ProtocolMessage
                data class Holder(
                    val id: UByte,
                    @Count val elems: List<Elem>,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun firesWhenCombinedWithRemainingBytes() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.Count
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes

                @ProtocolMessage
                data class Elem(val a: UByte)

                @ProtocolMessage
                data class Holder(
                    @Count @RemainingBytes val elems: List<Elem>,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsCountExclusionError(result)
    }

    @Test
    fun firesWhenCombinedWithLengthPrefixed() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.Count
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class Elem(val a: UByte)

                @ProtocolMessage
                data class Holder(
                    @Count @LengthPrefixed val elems: List<Elem>,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsCountExclusionError(result)
    }

    @Test
    fun firesWhenCombinedWithLengthFrom() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.Count
                import com.ditchoom.buffer.codec.annotations.LengthFrom
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class Elem(val a: UByte)

                @ProtocolMessage
                data class Holder(
                    val len: UShort,
                    @Count @LengthFrom("len") val elems: List<Elem>,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsCountExclusionError(result)
    }

    @Test
    fun firesWhenAppliedToNonList() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.Count
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class Holder(
                    @Count val notAList: UByte,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("@Count supports only List"),
            "diagnostic should explain @Count requires a List. Messages:\n${result.messages}",
        )
    }

    private fun assertContainsCountExclusionError(result: JvmCompilationResult) {
        assertTrue(
            result.messages.contains("@Count cannot be combined with"),
            "diagnostic should name the @Count mutual-exclusion rule. Messages:\n${result.messages}",
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
