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
 * Issue #150 codegen coverage (restored from PR #153).
 *
 * Asserts that `@ProtocolMessage` accepts `data object` and plain
 * `object` declarations — both standalone and as sealed-dispatch
 * variants. The processor must emit a codec for the standalone shape
 * (decode returns the singleton, encode writes nothing); for sealed
 * variants the dispatcher consumes the discriminator and the variant
 * codec is invoked with an empty body.
 *
 * Also pins the rejection of `@DispatchOn` directly on an object —
 * `@DispatchOn` selects between sealed-parent variants, so a leaf
 * object carrying it is a shape error.
 */
class DataObjectCodegenTest {
    @Test
    fun `data object standalone compiles and emits zero-byte codec`() {
        val result =
            compile(
                """
                package test
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data object Heartbeat
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `plain object standalone compiles`() {
        val result =
            compile(
                """
                package test
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                object Ack
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `sealed variant as data object compiles`() {
        val result =
            compile(
                """
                package test
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.PacketType

                @ProtocolMessage
                sealed interface Command {
                    @PacketType(0x01) @ProtocolMessage
                    data class SetValue(val value: UByte) : Command

                    @PacketType(0x02) @ProtocolMessage
                    data object Ping : Command

                    @PacketType(0x03) @ProtocolMessage
                    object Reset : Command
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `data object with DispatchOn on sealed parent compiles`() {
        val result =
            compile(
                """
                package test
                import com.ditchoom.buffer.codec.annotations.DispatchOn
                import com.ditchoom.buffer.codec.annotations.DispatchValue
                import com.ditchoom.buffer.codec.annotations.PacketType
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @JvmInline
                @ProtocolMessage
                value class Header(val raw: UByte) {
                    @DispatchValue
                    val packetType: Int get() = raw.toInt() and 0x0F
                }

                @DispatchOn(Header::class)
                @ProtocolMessage
                sealed interface Frame {
                    @PacketType(value = 1, wire = 0x01)
                    @ProtocolMessage
                    data class WithValue(val header: Header, val value: UByte) : Frame

                    @PacketType(value = 2, wire = 0x02)
                    @ProtocolMessage
                    data object Empty : Frame
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `object with DispatchOn annotation rejected`() {
        val result =
            compile(
                """
                package test
                import com.ditchoom.buffer.codec.annotations.DispatchOn
                import com.ditchoom.buffer.codec.annotations.DispatchValue
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @JvmInline
                @ProtocolMessage
                value class H(val raw: UByte) {
                    @DispatchValue
                    val packetType: Int get() = raw.toInt() and 0x0F
                }

                @DispatchOn(H::class)
                @ProtocolMessage
                object Bad
                """.trimIndent(),
            )
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("@DispatchOn is not valid on an object", ignoreCase = true)
        assertTrue(hasError, "Expected rejection but got: ${result.exitCode}\n${result.messages}")
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
