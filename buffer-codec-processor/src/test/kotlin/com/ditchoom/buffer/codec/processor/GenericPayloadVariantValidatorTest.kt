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
 * Issue #176 — compile-time validator coverage for the type-unsafe
 * sealed shape where the sealed PARENT is non-generic but one or more
 * variants declare a `<P : Payload>` type parameter (and therefore
 * extend the raw parent instead of `Parent<P>` / `Parent<Nothing>`).
 *
 * The dispatcher has nowhere to bind the variant codec's `<P>` at
 * dispatch time, so the generated decode/encode would not compile.
 * The validator must reject the shape on BOTH the simple `@PacketType`
 * path and the `@DispatchOn` bit-packed path, and must accept the
 * correct generic-parent form.
 */
class GenericPayloadVariantValidatorTest {
    @Test
    fun firesOnNonGenericParentWithGenericVariantSimpleDispatch() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.Payload
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.PacketType
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes

                @ProtocolMessage
                sealed interface FrameCommand {
                    @ProtocolMessage @PacketType(0x01)
                    data class Headered(val ts: Long) : FrameCommand

                    @ProtocolMessage @PacketType(0x02)
                    data class WithPayload<P : Payload>(
                        @LengthPrefixed val topic: String,
                        @RemainingBytes val payload: P,
                    ) : FrameCommand
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("sealed parent"),
            "diagnostic should name the parent. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("generic-payload variant"),
            "diagnostic should name the shape error. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("test.FrameCommand.WithPayload"),
            "diagnostic should name the offending variant. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("<out P : Payload>"),
            "diagnostic should suggest the fix. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesOnNonGenericParentWithGenericVariantDispatchOn() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.Payload
                import com.ditchoom.buffer.codec.annotations.DispatchOn
                import com.ditchoom.buffer.codec.annotations.DispatchValue
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.PacketType
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes

                @JvmInline
                @ProtocolMessage
                value class TinyHeader(val raw: UByte) {
                    @DispatchValue
                    val packetType: Int get() = raw.toUInt().shr(4).toInt()
                }

                @DispatchOn(TinyHeader::class)
                @ProtocolMessage
                sealed interface FrameCommand {
                    @ProtocolMessage @PacketType(value = 1, wire = 0x10)
                    data class Headered(val header: TinyHeader, val a: UByte) : FrameCommand

                    @ProtocolMessage @PacketType(value = 2, wire = 0x20)
                    data class WithPayload<P : Payload>(
                        val header: TinyHeader,
                        @LengthPrefixed val topic: String,
                        @RemainingBytes val payload: P,
                    ) : FrameCommand
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("sealed parent"),
            "diagnostic should name the parent. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("generic-payload variant"),
            "diagnostic should name the shape error. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("test.FrameCommand.WithPayload"),
            "diagnostic should name the offending variant. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("<out P : Payload>"),
            "diagnostic should suggest the fix. Messages:\n${result.messages}",
        )
    }

    @Test
    fun acceptsGenericParentDispatchOn() {
        // Mirrors the proven-correct Slice14cGenericFramedDispatch fixture:
        // a generic parent `<out P : Payload>` with a non-generic variant
        // extending `Parent<Nothing>` and a generic variant extending
        // `Parent<P>`. This is the supported generic dispatch shape and the
        // new validator must NOT fire on it.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.Payload
                import com.ditchoom.buffer.codec.annotations.DispatchOn
                import com.ditchoom.buffer.codec.annotations.DispatchValue
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.PacketType
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.RemainingBytes

                @JvmInline
                @ProtocolMessage
                value class TinyHeader(val raw: UByte) {
                    @DispatchValue
                    val packetType: Int get() = raw.toUInt().shr(4).toInt()
                }

                @DispatchOn(TinyHeader::class)
                @ProtocolMessage
                sealed interface FrameCommand<out P : Payload> {
                    @ProtocolMessage @PacketType(value = 1, wire = 0x10)
                    data class Headered(val header: TinyHeader, val a: UByte) : FrameCommand<Nothing>

                    @ProtocolMessage @PacketType(value = 2, wire = 0x20)
                    data class WithPayload<P : Payload>(
                        val header: TinyHeader,
                        @LengthPrefixed val topic: String,
                        @RemainingBytes val payload: P,
                    ) : FrameCommand<P>
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("generic-payload variant"),
            "correct generic-parent shape must compile silently. Messages:\n${result.messages}",
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
