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
        // Slice 10a tightened the contract: a Payload-typed field with no
        // resolution mechanism (no `@UseCodec`) is a hard error, not a
        // silent skip. The §8 short-circuit assertion still holds when the
        // field uses the slice 10a composition `@RemainingBytes
        // @UseCodec(...)` against a user-supplied Codec object — the walk
        // halts at the Payload marker before descending into OpaqueBlob's
        // ByteArray inner.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.Payload
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes
                import com.ditchoom.buffer.codec.annotations.UseCodec

                data class OpaqueBlob(val bytes: ByteArray) : Payload

                object OpaqueBlobCodec : Codec<OpaqueBlob> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): OpaqueBlob =
                        OpaqueBlob(buffer.readByteArray(buffer.remaining()))
                    override fun encode(buffer: WriteBuffer, value: OpaqueBlob, context: EncodeContext) {
                        buffer.writeBytes(value.bytes)
                    }
                    override fun wireSize(value: OpaqueBlob, context: EncodeContext): WireSize =
                        WireSize.Exact(value.bytes.size)
                }

                @ProtocolMessage
                data class ChunkWithPayloadField(
                    @RemainingBytes @UseCodec(OpaqueBlobCodec::class) val body: OpaqueBlob,
                )
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
    fun firesOnRawWriteBufferField() {
        // Phase J.M.5 slice 15b — same §8/D1 ban applies to WriteBuffer.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class RawWriteBufferField(val raw: WriteBuffer)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsRawBytesError(result, "RawWriteBufferField.raw", "com.ditchoom.buffer.WriteBuffer")
    }

    @Test
    fun firesOnRawPlatformBufferField() {
        // Phase J.M.5 slice 15b — same §8/D1 ban applies to PlatformBuffer.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.PlatformBuffer
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class RawPlatformBufferField(val raw: PlatformBuffer)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsRawBytesError(
            result,
            "RawPlatformBufferField.raw",
            "com.ditchoom.buffer.PlatformBuffer",
        )
    }

    @Test
    fun acceptsLengthPrefixedUseCodecPayloadValueClass() {
        // Phase J.M.5 slice 15b — the recommended migration target for raw
        // ByteArray-bearing fields: wrap bytes in a `Payload`-marked value
        // class and reference the codec via `@LengthPrefixed @UseCodec`
        // (slice 15a shape). The §8 walk halts at the Payload marker
        // before descending into the value class's `ByteArray` inner.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.Payload
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.UseCodec

                @JvmInline
                value class Blob(val bytes: ByteArray) : Payload

                object BlobCodec : Codec<Blob> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): Blob =
                        Blob(buffer.readByteArray(buffer.remaining()))
                    override fun encode(buffer: WriteBuffer, value: Blob, context: EncodeContext) {
                        buffer.writeBytes(value.bytes)
                    }
                    override fun wireSize(value: Blob, context: EncodeContext): WireSize =
                        WireSize.Exact(value.bytes.size)
                }

                @ProtocolMessage
                data class FrameWithBlob(
                    @LengthPrefixed @UseCodec(BlobCodec::class) val data: Blob,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("Section 8") || result.messages.contains("slice 15 D1"),
            "wrapping bytes in a Payload value class is the documented migration target — " +
                "no §8/D1 diagnostic should fire. Messages:\n${result.messages}",
        )
    }

    @Test
    fun diagnosticReferencesSlice15Doctrine() {
        // Phase J.M.5 slice 15b — the diagnostic must point at slice 15
        // D1 (raw types forbidden) and D2 (Payload marker required) as
        // the documented migration path, in addition to the existing §8
        // citation.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class StillRaw(val bytes: ByteArray)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("slice 15 D1"),
            "diagnostic should reference slice 15 D1. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("slice 15 D2"),
            "diagnostic should reference slice 15 D2 (the Payload-marker migration target). " +
                "Messages:\n${result.messages}",
        )
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
