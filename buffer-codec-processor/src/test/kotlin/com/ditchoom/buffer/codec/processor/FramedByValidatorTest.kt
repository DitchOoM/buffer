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
 * Phase J.M.5 slice 14b — `@FramedBy` validator coverage. Four diagnostics
 * exercised; **E6** (coexistence with `@DerivedLength`) is omitted because
 * `@DerivedLength` was removed in the same commit (Q6 — same-commit
 * cleanup), so the test fixture cannot construct the conflict scenario.
 * The validator still carries the E6 check defensively.
 *
 *   - **E1** — codec target must implement `BoundingLengthCodec<UInt>`.
 *   - **E2** — `after = "X"` names a field not on the primary constructor.
 *   - **E3** — `after = "X"` names a field that does not have Exact wire width.
 *   - **E4** — class has `@PacketType` (or its sealed parent does) but
 *     `after = ""`. Discriminator must precede the prefix.
 */
class FramedByValidatorTest {
    @Test
    fun acceptsStandaloneFramedByOnDataClass() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.BoundingLengthCodec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.FramedBy
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                object MyLengthCodec : BoundingLengthCodec<UInt> {
                    override val maxWireSize: Int = 4
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                    override fun applyBound(buffer: ReadBuffer, decodedValue: UInt) {
                        buffer.setLimit(buffer.position() + decodedValue.toInt())
                    }
                }

                @ProtocolMessage
                @FramedBy(MyLengthCodec::class)
                data class StandaloneFramed(val payload: UByte, val tail: UShort)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue(
            !result.messages.contains("e: ") || !result.messages.contains("@FramedBy"),
            "no @FramedBy diagnostic should fire on a valid standalone shape. Messages:\n${result.messages}",
        )
    }

    @Test
    fun rejectsCodecThatDoesNotImplementBoundingLengthCodecOfUInt() {
        // E1 — plain Codec<UInt> doesn't satisfy the BoundingLengthCodec<UInt> requirement.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.FramedBy
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                object PlainUIntCodec : Codec<UInt> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                }

                @ProtocolMessage
                @FramedBy(PlainUIntCodec::class)
                data class BadFramed(val payload: UByte)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("BoundingLengthCodec<UInt>"),
            "expected BoundingLengthCodec<UInt> diagnostic, got:\n${result.messages}",
        )
    }

    @Test
    fun rejectsAfterNameNotOnPrimaryConstructor() {
        // E2 — after = "ghost" but the class has no field named "ghost".
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.BoundingLengthCodec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.FramedBy
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                object MyCodec : BoundingLengthCodec<UInt> {
                    override val maxWireSize: Int = 4
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                    override fun applyBound(buffer: ReadBuffer, decodedValue: UInt) {
                        buffer.setLimit(buffer.position() + decodedValue.toInt())
                    }
                }

                @ProtocolMessage
                @FramedBy(MyCodec::class, after = "ghost")
                data class MissingAfter(val payload: UByte, val tail: UShort)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("ghost") && result.messages.contains("Available"),
            "expected after-name-not-on-primary-constructor diagnostic, got:\n${result.messages}",
        )
    }

    @Test
    fun rejectsAfterFieldWithoutExactWireWidth() {
        // E3 — after = "name" but `name: String` doesn't have Exact wire width.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.BoundingLengthCodec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.FramedBy
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                object MyCodec : BoundingLengthCodec<UInt> {
                    override val maxWireSize: Int = 4
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                    override fun applyBound(buffer: ReadBuffer, decodedValue: UInt) {
                        buffer.setLimit(buffer.position() + decodedValue.toInt())
                    }
                }

                @ProtocolMessage
                @FramedBy(MyCodec::class, after = "name")
                data class StringAfter(@LengthPrefixed val name: String, val tail: UShort)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("Exact wire width"),
            "expected Exact-wire-width diagnostic, got:\n${result.messages}",
        )
    }

    @Test
    fun rejectsAfterEmptyOnSealedParentWithPacketTypeVariants() {
        // E4 — sealed parent with @PacketType variants requires after = "<headerField>" so the
        // discriminator precedes the framing prefix on the wire.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.BoundingLengthCodec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.FramedBy
                import com.ditchoom.buffer.codec.annotations.PacketType
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                object MyCodec : BoundingLengthCodec<UInt> {
                    override val maxWireSize: Int = 4
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                    override fun applyBound(buffer: ReadBuffer, decodedValue: UInt) {
                        buffer.setLimit(buffer.position() + decodedValue.toInt())
                    }
                }

                @ProtocolMessage
                @FramedBy(MyCodec::class)
                sealed interface Bad {
                    @ProtocolMessage @PacketType(1)
                    data class Ping(val timestamp: Long) : Bad
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("after = \"\"") || result.messages.contains("discriminator"),
            "expected E4 (after-empty-with-PacketType) diagnostic, got:\n${result.messages}",
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
