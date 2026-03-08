package com.ditchoom.buffer.codec.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertTrue

class ForbiddenTypeTest {
    @Test
    fun `ReadBuffer field causes compile error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.ReadBuffer

            @ProtocolMessage
            data class BadMessage(val data: ReadBuffer)
            """,
            )
        val result = compileWithKspAndBufferStubs(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("forbidden type")
        assertTrue(hasError, "Expected error for ReadBuffer field but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `ByteArray field causes compile error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            data class BadMessage(val data: ByteArray)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("forbidden type")
        assertTrue(hasError, "Expected error for ByteArray field but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `PacketType value above 255 causes compile error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.PacketType

            @ProtocolMessage
            sealed interface BadProtocol {
                @ProtocolMessage
                @PacketType(0x1234)
                data class TooLarge(val x: Int) : BadProtocol
            }
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("out of range")
        assertTrue(hasError, "Expected error for PacketType > 255 but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `PacketType negative value causes compile error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.PacketType

            @ProtocolMessage
            sealed interface BadProtocol {
                @ProtocolMessage
                @PacketType(-1)
                data class Negative(val x: Int) : BadProtocol
            }
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("out of range")
        assertTrue(hasError, "Expected error for negative PacketType but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `duplicate PacketType values cause compile error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.PacketType

            @ProtocolMessage
            sealed interface BadProtocol {
                @ProtocolMessage
                @PacketType(0x01)
                data class First(val x: Int) : BadProtocol

                @ProtocolMessage
                @PacketType(0x01)
                data class Second(val y: Int) : BadProtocol
            }
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("is used by both")
        assertTrue(hasError, "Expected error for duplicate PacketType but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `sealed interface with no subclasses causes compile error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            sealed interface EmptyProtocol
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("no subclasses")
        assertTrue(hasError, "Expected error for empty sealed interface but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `empty WhenTrue expression causes compile error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenTrue

            @ProtocolMessage
            data class BadMessage(
                val flag: Boolean,
                @WhenTrue("") val extra: Int? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("is empty")
        assertTrue(hasError, "Expected error for empty @WhenTrue but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `WriteBuffer field causes compile error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.WriteBuffer

            @ProtocolMessage
            data class BadMessage(val data: WriteBuffer)
            """,
            )
        val result = compileWithKspAndBufferStubs(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("forbidden type")
        assertTrue(hasError, "Expected error for WriteBuffer field but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `WireBytes zero causes compile error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WireBytes

            @ProtocolMessage
            data class BadMessage(@WireBytes(0) val x: Int)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("out of range")
        assertTrue(hasError, "Expected error for @WireBytes(0) but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `WireBytes negative causes compile error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WireBytes

            @ProtocolMessage
            data class BadMessage(@WireBytes(-1) val x: Int)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("out of range")
        assertTrue(hasError, "Expected error for @WireBytes(-1) but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `bare String without length annotation causes compile error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            data class BadMessage(val name: String)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("requires a length annotation")
        assertTrue(hasError, "Expected error for bare String field but got: ${result.exitCode}\n${result.messages}")
    }
}
