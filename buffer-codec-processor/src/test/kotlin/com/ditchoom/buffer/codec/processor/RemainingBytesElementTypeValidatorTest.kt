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
 * Validator coverage for the retired
 * `@RemainingBytes List<scalar>` and `@RemainingBytes <primitive-array>`
 * shapes. Both promote a copy-by-default decode and provide no spec-
 * meaningful element structure; the right shapes are
 * `@RemainingBytes List<SealedParent>` for discrete
 * spec-defined value spaces or `@RemainingBytes @UseCodec(C::class)
 * val: P: Payload` for opaque bulk bytes.
 */
class RemainingBytesElementTypeValidatorTest {
    @Test
    fun rejectsRemainingBytesListOfUByte() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes

                @ProtocolMessage
                data class M(
                    val tag: UByte,
                    @RemainingBytes val xs: List<UByte>,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("@RemainingBytes on test.M.xs has type `List<kotlin.UByte>`"),
            "diagnostic should name field, type, and element. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("Scalar-element list shapes are retired"),
            "diagnostic should explain the rejection. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("List<SealedParent>"),
            "diagnostic should suggest the sealed-parent path. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("BinaryData"),
            "diagnostic should suggest the BinaryData path. Messages:\n${result.messages}",
        )
    }

    @Test
    fun rejectsRemainingBytesListOfByte() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes

                @ProtocolMessage
                data class M(
                    val tag: UByte,
                    @RemainingBytes val xs: List<Byte>,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(result.messages.contains("List<kotlin.Byte>"))
    }

    @Test
    fun rejectsRemainingBytesListOfUInt() {
        // Wider scalar elements have the same per-element box cost on JS.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes

                @ProtocolMessage
                data class M(
                    val tag: UByte,
                    @RemainingBytes val xs: List<UInt>,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(result.messages.contains("List<kotlin.UInt>"))
    }

    @Test
    fun rejectsRemainingBytesByteArray() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes

                @ProtocolMessage
                data class M(
                    val tag: UByte,
                    @RemainingBytes val xs: ByteArray,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("@RemainingBytes on test.M.xs has type `kotlin.ByteArray`"),
            "diagnostic should name the primitive-array type. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("Primitive array element types are intentionally not supported"),
            "diagnostic should explain the rejection. Messages:\n${result.messages}",
        )
        assertTrue(result.messages.contains("BinaryData"))
    }

    @Test
    fun rejectsRemainingBytesUByteArray() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes

                @ProtocolMessage
                data class M(
                    val tag: UByte,
                    @RemainingBytes val xs: UByteArray,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(result.messages.contains("kotlin.UByteArray"))
    }

    @Test
    fun acceptsRemainingBytesListOfSealedParent() {
        // The spec-faithful replacement for `List<UByte>` when
        // each byte is a discrete value-space variant. This must continue
        // to compile cleanly.
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
                    val id: Int get() = raw.toInt()
                }

                @DispatchOn(Tag::class)
                @ProtocolMessage
                sealed interface Code {
                    @PacketType(0x00) @ProtocolMessage
                    data class Ok(val id: Tag = Tag(0x00u)) : Code
                    @PacketType(0x01) @ProtocolMessage
                    data class Err(val id: Tag = Tag(0x01u)) : Code
                }

                @ProtocolMessage
                data class M(
                    val tag: UByte,
                    @RemainingBytes val xs: List<Code>,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun acceptsRemainingBytesUseCodecPayload() {
        // The spec-faithful replacement for `List<UByte>` when
        // the body is genuinely opaque bytes. This must continue to
        // compile cleanly.
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
                import com.ditchoom.buffer.codec.PeekResult
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes
                import com.ditchoom.buffer.codec.annotations.UseCodec
                import com.ditchoom.buffer.stream.StreamProcessor

                @JvmInline
                value class OpaqueBytes(val bytes: ByteArray) : Payload

                object OpaqueBytesCodec : Codec<OpaqueBytes> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): OpaqueBytes =
                        OpaqueBytes(buffer.readByteArray(buffer.remaining()))
                    override fun encode(buffer: WriteBuffer, value: OpaqueBytes, context: EncodeContext) {
                        buffer.writeBytes(value.bytes)
                    }
                    override fun wireSize(value: OpaqueBytes, context: EncodeContext): WireSize =
                        WireSize.Exact(value.bytes.size)
                    override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult =
                        PeekResult.NoFraming
                }

                @ProtocolMessage
                data class M(
                    val tag: UByte,
                    @RemainingBytes @UseCodec(OpaqueBytesCodec::class) val data: OpaqueBytes,
                )
                """.trimIndent(),
            )
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
