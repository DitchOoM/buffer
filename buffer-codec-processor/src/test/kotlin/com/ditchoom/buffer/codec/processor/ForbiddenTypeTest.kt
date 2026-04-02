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

    @Test
    fun `DispatchOn wire value overflowing UByte causes compile error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.PacketType
            import com.ditchoom.buffer.codec.annotations.DispatchOn
            import com.ditchoom.buffer.codec.annotations.DispatchValue

            @JvmInline
            @ProtocolMessage
            value class Header(val raw: UByte) {
                @DispatchValue
                val type: Int get() = raw.toInt().shr(4)
            }

            @DispatchOn(Header::class)
            @ProtocolMessage
            sealed interface BadProtocol {
                @ProtocolMessage
                @PacketType(value = 1, wire = 300)
                data class Overflow(val x: Int) : BadProtocol
            }
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("overflows")
        assertTrue(hasError, "Expected error for wire=300 on UByte discriminator but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `DispatchOn wire value negative for unsigned type causes compile error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.PacketType
            import com.ditchoom.buffer.codec.annotations.DispatchOn
            import com.ditchoom.buffer.codec.annotations.DispatchValue

            @JvmInline
            @ProtocolMessage
            value class Header(val raw: UShort) {
                @DispatchValue
                val type: Int get() = raw.toInt()
            }

            @DispatchOn(Header::class)
            @ProtocolMessage
            sealed interface BadProtocol {
                @ProtocolMessage
                @PacketType(value = 1, wire = -5)
                data class Negative(val x: Int) : BadProtocol
            }
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("overflows")
        assertTrue(hasError, "Expected error for wire=-5 on UShort discriminator but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `LengthFrom referencing non-existent field causes compile error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthFrom

            @ProtocolMessage
            data class BadMessage(
                val size: UShort,
                @LengthFrom("missing") val data: String,
            )
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("no field named 'missing'")
        assertTrue(hasError, "Expected error for @LengthFrom bad ref but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `WhenTrue referencing non-boolean field causes compile error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenTrue

            @ProtocolMessage
            data class BadMessage(
                val count: Int,
                @WhenTrue("count") val extra: Short? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("not Boolean")
        assertTrue(hasError, "Expected error for non-Boolean @WhenTrue but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `DispatchOn without DispatchValue causes compile error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.PacketType
            import com.ditchoom.buffer.codec.annotations.DispatchOn

            @JvmInline
            @ProtocolMessage
            value class NoDispatch(val raw: UByte)

            @DispatchOn(NoDispatch::class)
            @ProtocolMessage
            sealed interface BadProtocol {
                @ProtocolMessage
                @PacketType(1)
                data class First(val x: Int) : BadProtocol
            }
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("@DispatchValue")
        assertTrue(hasError, "Expected error for @DispatchOn without @DispatchValue but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `DispatchValue non-Int return type causes compile error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.PacketType
            import com.ditchoom.buffer.codec.annotations.DispatchOn
            import com.ditchoom.buffer.codec.annotations.DispatchValue

            @JvmInline
            @ProtocolMessage
            value class Header(val raw: UByte) {
                @DispatchValue
                val type: String get() = raw.toString()
            }

            @DispatchOn(Header::class)
            @ProtocolMessage
            sealed interface BadProtocol {
                @ProtocolMessage
                @PacketType(1)
                data class First(val x: Int) : BadProtocol
            }
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("must return Int")
        assertTrue(hasError, "Expected error for non-Int @DispatchValue but got: ${result.exitCode}\n${result.messages}")
    }
}
