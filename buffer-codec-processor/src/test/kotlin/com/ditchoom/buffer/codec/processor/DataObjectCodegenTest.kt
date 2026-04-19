package com.ditchoom.buffer.codec.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataObjectCodegenTest {
    @Test
    fun `data object standalone compiles and emits zero-byte codec`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            data object Heartbeat
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `plain object standalone compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            object Ack
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `sealed variant as data object compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
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
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `data object with DispatchOn on sealed parent compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.*

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
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `object with DispatchOn annotation rejected`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.*

            @JvmInline
            @ProtocolMessage
            value class H(val raw: UByte) {
                @DispatchValue
                val packetType: Int get() = raw.toInt() and 0x0F
            }

            @DispatchOn(H::class)
            @ProtocolMessage
            object Bad
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("@DispatchOn is not valid on an object", ignoreCase = true)
        assertTrue(hasError, "Expected rejection but got: ${result.exitCode}\n${result.messages}")
    }
}
