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
 * Compile-time validator coverage for `@UseTextPolicy`.
 *
 * Accepted: `Utf8.Lenient` / `Utf8.Strict` singletons, objects implementing
 * `TextPolicyProvider`, field- and message-level placement on String-shaped fields.
 * Rejected with a diagnostic: `Utf8.Checked` (no channel for a checked result in generated
 * bodies), non-object classes, non-String fields.
 */
class UseTextPolicyValidatorTest {
    @Test
    fun acceptsBuiltInPoliciesFieldAndMessageLevel() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.Utf8
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseTextPolicy

                @ProtocolMessage
                @UseTextPolicy(Utf8.Lenient::class)
                data class Publish(
                    @UseTextPolicy(Utf8.Strict::class)
                    @LengthPrefixed val topic: String,
                    @LengthPrefixed val note: String,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun acceptsTextPolicyProviderObject() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.FluentTextPolicy
                import com.ditchoom.buffer.Utf8
                import com.ditchoom.buffer.codec.TextPolicyProvider
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseTextPolicy

                object MyPolicy : TextPolicyProvider {
                    override val policy: FluentTextPolicy = Utf8.Lenient
                }

                @ProtocolMessage
                data class Note(
                    @UseTextPolicy(MyPolicy::class)
                    @LengthPrefixed val text: String,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun rejectsCheckedPolicy() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.Utf8
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseTextPolicy

                @ProtocolMessage
                data class Note(
                    @UseTextPolicy(Utf8.Checked::class)
                    @LengthPrefixed val text: String,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("Utf8.Checked" in result.messages, result.messages)
    }

    @Test
    fun rejectsNonObjectPolicy() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.FluentTextPolicy
                import com.ditchoom.buffer.Utf8
                import com.ditchoom.buffer.codec.TextPolicyProvider
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseTextPolicy

                class NotAnObject : TextPolicyProvider {
                    override val policy: FluentTextPolicy = Utf8.Lenient
                }

                @ProtocolMessage
                data class Note(
                    @UseTextPolicy(NotAnObject::class)
                    @LengthPrefixed val text: String,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("object" in result.messages, result.messages)
    }

    @Test
    fun rejectsPolicyOnNonStringField() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.Utf8
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseTextPolicy

                @ProtocolMessage
                data class Note(
                    @UseTextPolicy(Utf8.Strict::class)
                    val id: Int,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("String" in result.messages, result.messages)
    }

    @Test
    fun generatedCodeUsesPinnedPolicyAndContextFallback() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.Utf8
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseTextPolicy

                @ProtocolMessage
                data class Publish(
                    @UseTextPolicy(Utf8.Lenient::class)
                    @LengthPrefixed val topic: String,
                    @LengthPrefixed val payload: String,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val generated = generatedCodecSource(result, "PublishCodec.kt")
        assertTrue("writeText(value.topic, Utf8.Lenient)" in generated, generated)
        assertTrue("context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY" in generated, generated)
        assertTrue("writeString" !in generated, "writeString must be fully replaced:\n$generated")
        assertTrue("readString" !in generated, "readString must be fully replaced:\n$generated")
    }

    private fun generatedCodecSource(
        result: JvmCompilationResult,
        fileName: String,
    ): String {
        val root = result.outputDirectory.parentFile.resolve("ksp/sources")
        val file = root.walkTopDown().firstOrNull { it.name == fileName }
        requireNotNull(file) { "generated $fileName not found under $root" }
        return file.readText()
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
