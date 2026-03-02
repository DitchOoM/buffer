package com.ditchoom.buffer.codec.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataClassCodegenTest {
    @Test
    fun `simple two field struct generates codec`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            data class SimpleStruct(val id: UShort, val value: Int)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `all primitive types compile successfully`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            data class AllTypes(
                val b: Byte,
                val ub: UByte,
                val s: Short,
                val us: UShort,
                val i: Int,
                val ui: UInt,
                val l: Long,
                val ul: ULong,
                val f: Float,
                val d: Double,
                val bool: Boolean,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `length prefixed string field compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed

            @ProtocolMessage
            data class StringMsg(val id: UByte, @LengthPrefixed val name: String)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `conditional field compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenTrue
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed

            @ProtocolMessage
            data class ConditionalMsg(
                val hasName: Boolean,
                @WhenTrue("hasName") @LengthPrefixed val name: String? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `non-data class causes error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage

            @ProtocolMessage
            class NotDataClass(val id: Int)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("must be applied to a data class or sealed interface")
        assertTrue(hasError, "Expected error for non-data class but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `remaining bytes string field compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.RemainingBytes

            @ProtocolMessage
            data class TrailingMsg(val id: UByte, @RemainingBytes val data: String)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `remaining bytes on non-last field causes error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.RemainingBytes

            @ProtocolMessage
            data class BadMsg(@RemainingBytes val data: String, val id: UByte)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("@RemainingBytes can only be used on the last")
        assertTrue(hasError, "Expected error for @RemainingBytes on non-last field but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `length from field compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthFrom

            @ProtocolMessage
            data class LengthFromMsg(val length: UShort, @LengthFrom("length") val data: String)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `payload type parameter generates generic codec`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.Payload
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed

            @ProtocolMessage
            data class PayloadMsg<@Payload P>(
                val id: UShort,
                @LengthPrefixed val data: P,
            )
            """,
            )
        val result = compileWithKspAndPayloadStubs(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `multiple payload type parameters generate per-type lambdas`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.Payload
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed

            @ProtocolMessage
            data class MultiPayload<@Payload WP, @Payload PP>(
                val id: UShort,
                @LengthPrefixed val willPayload: WP,
                @LengthPrefixed val password: PP,
            )
            """,
            )
        val result = compileWithKspAndPayloadStubs(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `payload field without length annotation causes error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.Payload

            @ProtocolMessage
            data class BadPayload<@Payload P>(
                val id: UShort,
                val data: P,
            )
            """,
            )
        val result = compileWithKspAndPayloadStubs(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("requires a length annotation")
        assertTrue(hasError, "Expected error for payload without length annotation but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `payload field with remaining bytes`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.Payload
            import com.ditchoom.buffer.codec.annotations.RemainingBytes

            @ProtocolMessage
            data class TrailingPayload<@Payload P>(
                val id: UShort,
                @RemainingBytes val data: P,
            )
            """,
            )
        val result = compileWithKspAndPayloadStubs(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `payload field with int length prefix`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.Payload
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed
            import com.ditchoom.buffer.codec.annotations.LengthPrefix

            @ProtocolMessage
            data class IntPrefixPayload<@Payload P>(
                val id: UShort,
                @LengthPrefixed(LengthPrefix.Int) val data: P,
            )
            """,
            )
        val result = compileWithKspAndPayloadStubs(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `wire bytes 3 on Int compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WireBytes

            @ProtocolMessage
            data class ThreeByteMsg(val id: UByte, @WireBytes(3) val value: Int)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `wire bytes on Float causes error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WireBytes

            @ProtocolMessage
            data class BadWireBytes(val id: UByte, @WireBytes(2) val value: Float)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("only numeric types")
        assertTrue(hasError, "Expected error for @WireBytes on Float but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `wire bytes exceeding type size causes error`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WireBytes

            @ProtocolMessage
            data class BadWireBytes(val id: UByte, @WireBytes(5) val value: Int)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("exceeds")
        assertTrue(hasError, "Expected error for oversized @WireBytes but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `wire bytes 1 on Short compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WireBytes

            @ProtocolMessage
            data class OneByteShort(val flags: UByte, @WireBytes(1) val value: Short)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `conflicting LengthPrefixed and RemainingBytes rejected`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed
            import com.ditchoom.buffer.codec.annotations.RemainingBytes

            @ProtocolMessage
            data class BadMsg(val id: UByte, @LengthPrefixed @RemainingBytes val data: String)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("conflicting length annotations", ignoreCase = true)
        assertTrue(hasError, "Expected error for conflicting annotations but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `conflicting LengthPrefixed and LengthFrom rejected`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed
            import com.ditchoom.buffer.codec.annotations.LengthFrom

            @ProtocolMessage
            data class BadMsg(val len: UShort, @LengthPrefixed @LengthFrom("len") val data: String)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("conflicting length annotations", ignoreCase = true)
        assertTrue(hasError, "Expected error for conflicting annotations but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `conflicting RemainingBytes and LengthFrom rejected`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.RemainingBytes
            import com.ditchoom.buffer.codec.annotations.LengthFrom

            @ProtocolMessage
            data class BadMsg(val len: UShort, @RemainingBytes @LengthFrom("len") val data: String)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("conflicting length annotations", ignoreCase = true)
        assertTrue(hasError, "Expected error for conflicting annotations but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `all three length annotations rejected`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed
            import com.ditchoom.buffer.codec.annotations.RemainingBytes
            import com.ditchoom.buffer.codec.annotations.LengthFrom

            @ProtocolMessage
            data class BadMsg(val len: UShort, @LengthPrefixed @RemainingBytes @LengthFrom("len") val data: String)
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("conflicting length annotations", ignoreCase = true)
        assertTrue(hasError, "Expected error for conflicting annotations but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `conditional field without default value rejected`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenTrue

            @ProtocolMessage
            data class BadMsg(
                val hasData: Boolean,
                @WhenTrue("hasData") val data: Int? ,
            )
            """,
            )
        val result = compileWithKsp(source)
        val hasError =
            result.exitCode == KotlinCompilation.ExitCode.COMPILATION_ERROR ||
                result.messages.contains("must have a default value", ignoreCase = true)
        assertTrue(hasError, "Expected error for conditional without default but got: ${result.exitCode}\n${result.messages}")
    }

    @Test
    fun `length prefixed with byte prefix compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed
            import com.ditchoom.buffer.codec.annotations.LengthPrefix

            @ProtocolMessage
            data class BytePrefixMsg(val id: UByte, @LengthPrefixed(LengthPrefix.Byte) val name: String)
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }
}
