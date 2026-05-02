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
 * Compile-time validator coverage for the Section 8 rule: raw-bytes
 * types (`ReadBuffer`, `WriteBuffer`, `PlatformBuffer`, `ByteArray`,
 * `ByteBuffer`) cannot appear in the fields of a `@ProtocolMessage`
 * data class. The walk recurses through `@JvmInline value class`
 * wrappers and generic type arguments, and short-circuits at any node
 * whose type extends `com.ditchoom.buffer.codec.Payload`.
 */
class ProtocolMessagePayloadValidatorTest {
    @Test
    fun firesOnRawReadBufferField() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class RawReadBufferField(val raw: ReadBuffer)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsRawBytesError(result, "RawReadBufferField.raw", "com.ditchoom.buffer.ReadBuffer")
    }

    @Test
    fun firesThroughValueClassWrapper() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @JvmInline
                value class ChunkBody(val raw: ReadBuffer)

                @ProtocolMessage
                data class ChunkWithWrappedBuffer(val body: ChunkBody)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsRawBytesError(result, "ChunkWithWrappedBuffer.body", "com.ditchoom.buffer.ReadBuffer")
    }

    @Test
    fun doesNotFireWhenFieldExtendsPayload() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.Payload
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                data class OpaqueBlob(val bytes: ByteArray) : Payload

                @ProtocolMessage
                data class ChunkWithPayloadField(val body: OpaqueBlob)
                """.trimIndent(),
            )
        // The Payload short-circuit must fire BEFORE we descend into OpaqueBlob's
        // ByteArray field — that's the consumer's documented escape hatch.
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("Section 8"),
            "Payload-tagged field should not trigger the §8 diagnostic. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesOnByteArrayField() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class RawByteArrayField(val bytes: ByteArray)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsRawBytesError(result, "RawByteArrayField.bytes", "kotlin.ByteArray")
    }

    @Test
    fun firesOnGenericArgument() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class ListOfRaw(val frames: List<ByteArray>)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsRawBytesError(result, "ListOfRaw.frames", "kotlin.ByteArray")
    }

    @Test
    fun acceptsBenignFields() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class ScalarOnly(
                    val id: UInt,
                    val name: String,
                    val flag: Boolean,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(result.messages.contains("Section 8"), "no diagnostic should fire on scalar fields")
    }

    @Test
    fun acceptsValueClassOverPayload() {
        // Value class wrapping a Payload-tagged inner — walk descends into
        // the inner type, hits Payload, and short-circuits. No diagnostic.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.Payload
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                data class OpaqueBlob(val bytes: ByteArray) : Payload

                @JvmInline
                value class WrappedPayload(val payload: OpaqueBlob)

                @ProtocolMessage
                data class ChunkWithWrappedPayload(val body: WrappedPayload)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("Section 8"),
            "Payload short-circuit should apply at every walk depth, including value-class inner types",
        )
    }

    private fun assertContainsRawBytesError(
        result: JvmCompilationResult,
        ownerDotField: String,
        forbiddenType: String,
    ) {
        assertTrue(
            result.messages.contains("@ProtocolMessage field test.$ownerDotField"),
            "diagnostic should reference the offending field. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains(forbiddenType),
            "diagnostic should name the forbidden type $forbiddenType. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("Section 8"),
            "diagnostic should cite §8. Messages:\n${result.messages}",
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
