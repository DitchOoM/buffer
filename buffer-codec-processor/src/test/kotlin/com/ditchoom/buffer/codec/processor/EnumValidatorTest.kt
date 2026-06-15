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
 * Compile-time coverage for enum-field codegen. An enum field's ordinal rides as an unsigned
 * LEB128 varint; an optional `@EnumDefault` entry is the unknown-ordinal decode sink. Rejections:
 * more than one `@EnumDefault`, and `@WireBytes`/`@WireOrder` on an enum field (the varint owns the
 * wire shape).
 */
class EnumValidatorTest {
    @Test
    fun acceptsEnumFieldWithAndWithoutDefault() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.EnumDefault
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                enum class Color { @EnumDefault Unknown, Red, Green }
                enum class Priority { Low, High }

                @ProtocolMessage
                data class Style(val color: Color, val priority: Priority, val weight: UByte)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun firesOnMultipleEnumDefault() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.EnumDefault
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                enum class Color { @EnumDefault Unknown, @EnumDefault Red, Green }

                @ProtocolMessage
                data class Style(val color: Color)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("@EnumDefault"),
            "diagnostic should name @EnumDefault. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesOnWireBytesOnEnumField() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.WireBytes

                enum class Color { Red, Green }

                @ProtocolMessage
                data class Style(@WireBytes(2) val color: Color)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("@WireBytes"),
            "diagnostic should name @WireBytes. Messages:\n${result.messages}",
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
