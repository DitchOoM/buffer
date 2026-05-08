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
 * Compile-time validator coverage for 's simple sealed
 * dispatch (`@ProtocolMessage sealed interface` + `@PacketType` on
 * each variant). Every direct sealed subclass must carry
 * `@PacketType(value = N)` with `N in 0..255`, and `value` must be
 * unique within a parent. Sealed parents carrying `@DispatchOn` are
 * skipped ( surface).
 */
class SealedDispatcherValidatorTest {
    @Test
    fun acceptsValidSealedDispatcher() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.PacketType
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                sealed interface Command {
                    @ProtocolMessage @PacketType(0x01)
                    data class Ping(val ts: Long) : Command

                    @ProtocolMessage @PacketType(0x02)
                    data class Echo(@LengthPrefixed val msg: String) : Command
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@PacketType"),
            "valid sealed dispatcher must compile silently. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesOnVariantMissingPacketType() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.PacketType
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                sealed interface Command {
                    @ProtocolMessage @PacketType(0x01)
                    data class Ping(val ts: Long) : Command

                    @ProtocolMessage
                    data class Pong(val ts: Long) : Command
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("test.Command.Pong"),
            "diagnostic should name the offending subclass. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("test.Command"),
            "diagnostic should name the parent. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("@PacketType"),
            "diagnostic should mention @PacketType. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesOnDuplicatePacketTypeValue() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.PacketType
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                sealed interface Command {
                    @ProtocolMessage @PacketType(0x01)
                    data class A(val x: Long) : Command

                    @ProtocolMessage @PacketType(0x01)
                    data class B(val y: Long) : Command
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("@PacketType(1)"),
            "diagnostic should name the duplicated value. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("test.Command.A") && result.messages.contains("test.Command.B"),
            "diagnostic should reference both colliding subclasses. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesOnPacketTypeAboveByteRange() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.PacketType
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                sealed interface Command {
                    @ProtocolMessage @PacketType(256)
                    data class TooBig(val x: Long) : Command
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("@PacketType(256)"),
            "diagnostic should name the out-of-range value. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("0..255"),
            "diagnostic should explain the valid range. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesOnPacketTypeBelowZero() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.PacketType
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                sealed interface Command {
                    @ProtocolMessage @PacketType(-1)
                    data class Negative(val x: Long) : Command
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("@PacketType(-1)"),
            "diagnostic should name the offending value. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("0..255"),
            "diagnostic should explain the valid range. Messages:\n${result.messages}",
        )
    }

    @Test
    fun acceptsLooseDataClassWithPacketType() {
        // A top-level @ProtocolMessage data class carrying @PacketType but with
        // no sealed parent is not in the simple-dispatch surface. The processor
        // emits a standalone codec for it (the data-class path is unaffected by
        // @PacketType); the sealed-dispatcher validator simply does not engage
        // because there is no parent to validate against. No diagnostic.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.PacketType
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                @PacketType(0x01)
                data class Loose(val ts: Long)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@PacketType"),
            "loose @PacketType must compile silently. Messages:\n${result.messages}",
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
