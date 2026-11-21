package com.ditchoom.buffer.codec.processor

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import com.tschuchort.compiletesting.useKsp2
import org.intellij.lang.annotations.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Compile-time contract for `@SinceVersion` — decode-side tolerance for trailing fields
 * appended after a peer was built.
 *
 * The load-bearing cases here are the two REJECTIONS of unbounded nesting
 * ([rejectsUnboundedNestedMessage], [rejectsOptionalTrailingListElement]). Everything else
 * guards ergonomics; those two guard soundness. Without them the processor emits a decoder
 * that reads the ENCLOSING message's bytes into the nested message's optional field and
 * returns a plausible wrong value — no exception, no test failure on a well-formed frame.
 */
class SinceVersionValidatorTest {
    @Test
    fun `accepts a bounded trailing run`() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.SinceVersion

                @ProtocolMessage
                data class Register(
                    val id: Int,
                    @SinceVersion(2) val retries: Int = 3,
                    @SinceVersion(3) val flag: Boolean = true,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@SinceVersion"),
            "a valid trailing run must compile silently. Messages:\n${result.messages}",
        )
    }

    @Test
    fun `generates nested guards that omit absent arguments`() {
        val (result, source) =
            compileAndReadCodec(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.SinceVersion

                @ProtocolMessage
                data class Register(
                    val id: Int,
                    @SinceVersion(2) val retries: Int = 3,
                    @SinceVersion(3) val flag: Boolean = true,
                )
                """.trimIndent(),
                "RegisterCodec.kt",
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        // Guards derived from each field's own width: Int = 4, Boolean = 1.
        assertTrue(source.contains("buffer.remaining() < 4"), "expected a 4-byte guard in:\n$source")
        assertTrue(source.contains("buffer.remaining() < 1"), "expected a 1-byte guard in:\n$source")
        // The three arities: absence is the ARGUMENT BEING OMITTED, not a null being passed.
        assertTrue(source.contains("Register(id = id)"), "expected the v1 arity in:\n$source")
        assertTrue(
            source.contains("Register(id = id, retries = retries)"),
            "expected the v2 arity in:\n$source",
        )
        assertTrue(
            source.contains("Register(id = id, retries = retries, flag = flag)"),
            "expected the full arity in:\n$source",
        )
        assertFalse(source.contains("= null"), "optional trailing fields must not go through null:\n$source")
    }

    @Test
    fun `encoder still writes every field`() {
        val (result, source) =
            compileAndReadCodec(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.SinceVersion

                @ProtocolMessage
                data class Register(val id: Int, @SinceVersion(2) val retries: Int = 3)
                """.trimIndent(),
                "RegisterCodec.kt",
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val encodeBody = source.substringAfter("override fun encode").substringBefore("override fun")
        // The batch optimizer may coalesce adjacent scalars into one wide write, so assert the
        // field is REFERENCED and unconditionally so — not that a particular call shape appears.
        assertTrue(
            encodeBody.contains("value.retries"),
            "@SinceVersion is decode-only: encode must write the field unconditionally:\n$encodeBody",
        )
        assertFalse(
            encodeBody.contains("remaining()"),
            "encode must not gate on remaining():\n$encodeBody",
        )
        assertFalse(
            encodeBody.contains("if (value.retries"),
            "encode must not gate the slot on the field's value:\n$encodeBody",
        )
    }

    // ---- the soundness rejections -----------------------------------------

    @Test
    fun rejectsUnboundedNestedMessage() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.SinceVersion

                @ProtocolMessage
                data class Inner(val a: Int, @SinceVersion(2) val opt: Int = 0)

                @ProtocolMessage
                data class Outer(val inner: Inner, val afterInner: Int)
                """.trimIndent(),
            )
        assertEquals(
            KotlinCompilation.ExitCode.COMPILATION_ERROR,
            result.exitCode,
            "nesting an optional-trailing message UNBOUNDED must fail the build, not generate a " +
                "decoder that reads Outer.afterInner into Inner.opt. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("UNBOUNDED"),
            "diagnostic must name the boundedness problem:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("@LengthPrefixed"),
            "diagnostic must name the fix:\n${result.messages}",
        )
    }

    @Test
    fun `accepts a nested optional-trailing message behind a length prefix`() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.SinceVersion

                @ProtocolMessage
                data class Inner(val a: Int, @SinceVersion(2) val opt: Int = 0)

                @ProtocolMessage
                data class Outer(val beforeInner: Int, @LengthPrefixed val inner: Inner)
                """.trimIndent(),
            )
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "@LengthPrefixed bounds the nested extent, so the guard is sound:\n${result.messages}",
        )
    }

    @Test
    fun rejectsOptionalTrailingListElement() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.Count
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.SinceVersion

                @ProtocolMessage
                data class Entry(val a: Int, @SinceVersion(2) val opt: Int = 0)

                @ProtocolMessage
                data class Listing(@Count val entries: List<Entry>)
                """.trimIndent(),
            )
        assertEquals(
            KotlinCompilation.ExitCode.COMPILATION_ERROR,
            result.exitCode,
            "list elements are not bounded from one another — element k's guard would consume " +
                "element k+1's head. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("element k"),
            "diagnostic must explain the element-to-element aliasing:\n${result.messages}",
        )
    }

    @Test
    fun `rejects unbounded nesting of a When remaining message too`() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.When

                @ProtocolMessage
                data class Inner(val a: Int, @When("remaining >= 4") val opt: Int? = null)

                @ProtocolMessage
                data class Outer(val inner: Inner, val afterInner: Int)
                """.trimIndent(),
            )
        assertEquals(
            KotlinCompilation.ExitCode.COMPILATION_ERROR,
            result.exitCode,
            "the boundedness rule covers grammar 2 as well as @SinceVersion:\n${result.messages}",
        )
    }

    // ---- shape rejections --------------------------------------------------

    @Test
    fun `rejects a required field after an optional one`() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.SinceVersion

                @ProtocolMessage
                data class M(val a: Int, @SinceVersion(2) val opt: Int = 0, val trailer: Int)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(result.messages.contains("trailer"), result.messages)
    }

    @Test
    fun `rejects a missing Kotlin default`() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.SinceVersion

                @ProtocolMessage
                data class M(val a: Int, @SinceVersion(2) val opt: Int)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(result.messages.contains("requires a Kotlin default"), result.messages)
    }

    @Test
    fun `rejects a nullable optional trailing field`() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.SinceVersion

                @ProtocolMessage
                data class M(val a: Int, @SinceVersion(2) val opt: Int? = null)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(result.messages.contains("NON-nullable"), result.messages)
    }

    @Test
    fun `rejects a variable-width trailing field`() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.SinceVersion

                @ProtocolMessage
                data class M(val a: Int, @SinceVersion(2) @LengthPrefixed val note: String = "")
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(result.messages.contains("no compile-time minimum width"), result.messages)
    }

    @Test
    fun `rejects a decreasing version`() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.SinceVersion

                @ProtocolMessage
                data class M(
                    val a: Int,
                    @SinceVersion(3) val x: Int = 0,
                    @SinceVersion(2) val y: Int = 0,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(result.messages.contains("is lower than"), result.messages)
    }

    @Test
    fun `rejects SinceVersion combined with When`() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.SinceVersion
                import com.ditchoom.buffer.codec.annotations.When

                @ProtocolMessage
                data class M(
                    val a: Int,
                    @SinceVersion(2) @When("remaining >= 4") val opt: Int = 0,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(result.messages.contains("Pick one"), result.messages)
    }

    // ---- grammar-2 threshold/width agreement --------------------------------

    @Test
    fun `rejects a When remaining threshold narrower than the field`() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.When

                @ProtocolMessage
                data class M(val a: Int, @When("remaining >= 1") val opt: Int? = null)
                """.trimIndent(),
            )
        assertEquals(
            KotlinCompilation.ExitCode.COMPILATION_ERROR,
            result.exitCode,
            "a 1-byte guard on a 4-byte read admits frames the read then fails on:\n${result.messages}",
        )
        assertTrue(result.messages.contains("at least 4 bytes"), result.messages)
    }

    @Test
    fun `accepts a When remaining threshold that matches the field`() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.When

                @ProtocolMessage
                data class M(val a: Int, @When("remaining >= 4") val opt: Int? = null)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    // ---- harness ------------------------------------------------------------

    private fun compile(
        @Language("kotlin") source: String,
    ): JvmCompilationResult = compileAndReadCodec(source, codecFileName = null).first

    private fun compileAndReadCodec(
        @Language("kotlin") source: String,
        codecFileName: String?,
    ): Pair<JvmCompilationResult, String> {
        val compilation =
            KotlinCompilation().apply {
                sources = listOf(SourceFile.kotlin("Test.kt", source))
                inheritClassPath = true
                messageOutputStream = System.out
                useKsp2()
                configureKsp {
                    symbolProcessorProviders += ProtocolMessageProcessorProvider()
                }
            }
        val result = compilation.compile()
        val generated =
            if (codecFileName == null) {
                ""
            } else {
                compilation
                    .kspSourcesDir
                    .walkTopDown()
                    .firstOrNull { it.isFile && it.name == codecFileName }
                    ?.readText() ?: ""
            }
        return result to generated
    }
}
