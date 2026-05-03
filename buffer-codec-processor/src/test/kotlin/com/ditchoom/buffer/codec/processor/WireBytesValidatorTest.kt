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
 * Compile-time validator coverage for `@WireBytes(N)` (Stage B, R4).
 *
 * Rule R4: `@WireBytes(N)` declares a wire width narrower than (or
 * equal to) the Kotlin type's natural size. Out-of-range widths
 * (N < 1, N > 8) and widths exceeding the type's natural size
 * (e.g., `@WireBytes(5)` on `UInt`) are compile errors. The natural-
 * width case (e.g., `@WireBytes(4) val x: UInt`) is silently accepted
 * — it produces the same wire bytes as the unannotated form.
 */
class WireBytesValidatorTest {
    @Test
    fun acceptsWireBytesNarrowerThanNaturalWidth() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.Endianness
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.WireBytes

                @ProtocolMessage(wireOrder = Endianness.Big)
                data class NarrowField(
                    @WireBytes(3) val length: UInt,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@WireBytes"),
            "@WireBytes within natural width must compile silently. Messages:\n${result.messages}",
        )
    }

    @Test
    fun acceptsWireBytesEqualToNaturalWidth() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.Endianness
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.WireBytes

                @ProtocolMessage(wireOrder = Endianness.Big)
                data class FullWidth(
                    @WireBytes(4) val full: UInt,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@WireBytes"),
            "@WireBytes(N == natural) must compile silently. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesOnWireBytesAboveNaturalWidth() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.Endianness
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.WireBytes

                @ProtocolMessage(wireOrder = Endianness.Big)
                data class TooWide(
                    @WireBytes(5) val overflowing: UInt,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("test.TooWide.overflowing"),
            "diagnostic should reference the offending field. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("@WireBytes(5)"),
            "diagnostic should name the offending annotation literal. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("kotlin.UInt"),
            "diagnostic should name the field's type. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesOnWireBytesZero() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.Endianness
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.WireBytes

                @ProtocolMessage(wireOrder = Endianness.Big)
                data class ZeroBytes(
                    @WireBytes(0) val empty: UInt,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("@WireBytes(0)"),
            "diagnostic should name the offending value. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("1..8 bytes"),
            "diagnostic should explain the valid range. Messages:\n${result.messages}",
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
