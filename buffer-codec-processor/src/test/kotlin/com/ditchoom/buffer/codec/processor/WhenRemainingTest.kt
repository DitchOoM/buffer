package com.ditchoom.buffer.codec.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WhenRemainingTest {
    // ──────────────────────── Codegen: successful compilation ────────────────────────

    @Test
    fun `single WhenRemaining field compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenRemaining

            @ProtocolMessage
            data class SimpleAck(
                val packetId: UShort,
                @WhenRemaining(1) val reasonCode: UByte? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `multiple WhenRemaining fields compile with cascading encode`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenRemaining

            @ProtocolMessage
            data class AckV5(
                val packetId: UShort,
                @WhenRemaining(1) val reasonCode: UByte? = null,
                @WhenRemaining(1) val extraData: UByte? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `WhenRemaining with various primitive types compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenRemaining

            @ProtocolMessage
            data class MixedTypes(
                val header: UByte,
                @WhenRemaining(1) val optByte: Byte? = null,
                @WhenRemaining(2) val optShort: Short? = null,
                @WhenRemaining(4) val optInt: Int? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `WhenRemaining with large minBytes compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenRemaining

            @ProtocolMessage
            data class LargeMinBytes(
                val tag: UByte,
                @WhenRemaining(100) val largePayload: Int? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `WhenRemaining with LengthPrefixed string compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenRemaining
            import com.ditchoom.buffer.codec.annotations.LengthPrefixed

            @ProtocolMessage
            data class OptionalString(
                val id: UShort,
                @WhenRemaining(2) @LengthPrefixed val name: String? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `all fields are WhenRemaining compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenRemaining

            @ProtocolMessage
            data class AllOptional(
                @WhenRemaining(1) val a: UByte? = null,
                @WhenRemaining(2) val b: UShort? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `WhenRemaining does not generate peekFrameSize`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenRemaining

            @ProtocolMessage
            data class NoPeek(
                val id: UShort,
                @WhenRemaining(1) val reason: UByte? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
        // peekFrameSize should not be generated — no compile error means it was omitted
    }

    // ──────────────────────── Validation: error cases ────────────────────────

    @Test
    fun `WhenRemaining with minBytes 0 fails`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenRemaining

            @ProtocolMessage
            data class BadMinBytes(
                val id: UShort,
                @WhenRemaining(0) val data: UByte? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("minBytes must be greater than 0"), "Expected minBytes error:\n${result.messages}")
    }

    @Test
    fun `WhenRemaining with negative minBytes fails`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenRemaining

            @ProtocolMessage
            data class NegMinBytes(
                val id: UShort,
                @WhenRemaining(-1) val data: UByte? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("minBytes must be greater than 0"), "Expected minBytes error:\n${result.messages}")
    }

    @Test
    fun `WhenRemaining on non-nullable field fails`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenRemaining

            @ProtocolMessage
            data class NonNullable(
                val id: UShort,
                @WhenRemaining(1) val data: UByte = 0u,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("must be nullable"), "Expected nullable error:\n${result.messages}")
    }

    @Test
    fun `WhenRemaining without default value fails`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenRemaining

            @ProtocolMessage
            data class NoDefault(
                val id: UShort,
                @WhenRemaining(1) val data: UByte?,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("default value"), "Expected default value error:\n${result.messages}")
    }

    @Test
    fun `WhenRemaining not at tail fails`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenRemaining

            @ProtocolMessage
            data class NotAtTail(
                val id: UShort,
                @WhenRemaining(1) val optData: UByte? = null,
                val trailer: UByte,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(
            result.messages.contains("contiguous and at the tail"),
            "Expected tail position error:\n${result.messages}",
        )
    }

    @Test
    fun `WhenRemaining not contiguous fails`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenRemaining

            @ProtocolMessage
            data class NotContiguous(
                @WhenRemaining(1) val a: UByte? = null,
                val middle: UByte,
                @WhenRemaining(1) val b: UByte? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(
            result.messages.contains("contiguous and at the tail"),
            "Expected contiguous error:\n${result.messages}",
        )
    }

    // ──────────────────────── Validator unit tests ────────────────────────

    private class TestLogger : com.google.devtools.ksp.processing.KSPLogger {
        val errors = mutableListOf<String>()

        override fun logging(
            message: String,
            symbol: com.google.devtools.ksp.symbol.KSNode?,
        ) {}

        override fun info(
            message: String,
            symbol: com.google.devtools.ksp.symbol.KSNode?,
        ) {}

        override fun warn(
            message: String,
            symbol: com.google.devtools.ksp.symbol.KSNode?,
        ) {}

        override fun error(
            message: String,
            symbol: com.google.devtools.ksp.symbol.KSNode?,
        ) {
            errors.add(message)
        }

        override fun exception(e: Throwable) {}
    }

    private fun field(
        name: String,
        typeName: String,
        strategy: FieldReadStrategy,
        nullable: Boolean = false,
        condition: FieldCondition? = null,
        hasDefault: Boolean = condition != null,
    ): FieldInfo =
        FieldInfo(
            name = name,
            typeName = typeName,
            strategy = strategy,
            isNullable = nullable,
            condition = condition,
            parameter = null,
            hasDefault = hasDefault,
        )

    @Test
    fun `validator accepts valid WhenRemaining fields`() {
        val logger = TestLogger()
        val validator = ConditionalValidator(logger)
        val fields =
            listOf(
                field("id", "kotlin.UShort", FieldReadStrategy.UShortField),
                field(
                    "reason",
                    "kotlin.UByte",
                    FieldReadStrategy.UByteField,
                    nullable = true,
                    condition = FieldCondition.WhenRemaining(1),
                ),
            )
        assertTrue(validator.validate(fields))
        assertTrue(logger.errors.isEmpty())
    }

    @Test
    fun `validator rejects minBytes zero`() {
        val logger = TestLogger()
        val validator = ConditionalValidator(logger)
        val fields =
            listOf(
                field("id", "kotlin.UShort", FieldReadStrategy.UShortField),
                field(
                    "reason",
                    "kotlin.UByte",
                    FieldReadStrategy.UByteField,
                    nullable = true,
                    condition = FieldCondition.WhenRemaining(0),
                ),
            )
        assertFalse(validator.validate(fields))
        assertTrue(logger.errors.any { it.contains("minBytes must be greater than 0") })
    }

    @Test
    fun `validator rejects non-nullable WhenRemaining field`() {
        val logger = TestLogger()
        val validator = ConditionalValidator(logger)
        val fields =
            listOf(
                field("id", "kotlin.UShort", FieldReadStrategy.UShortField),
                field(
                    "reason",
                    "kotlin.UByte",
                    FieldReadStrategy.UByteField,
                    nullable = false,
                    condition = FieldCondition.WhenRemaining(1),
                ),
            )
        assertFalse(validator.validate(fields))
        assertTrue(logger.errors.any { it.contains("must be nullable") })
    }

    @Test
    fun `validator rejects WhenRemaining without default`() {
        val logger = TestLogger()
        val validator = ConditionalValidator(logger)
        val fields =
            listOf(
                field("id", "kotlin.UShort", FieldReadStrategy.UShortField),
                field(
                    "reason",
                    "kotlin.UByte",
                    FieldReadStrategy.UByteField,
                    nullable = true,
                    condition = FieldCondition.WhenRemaining(1),
                    hasDefault = false,
                ),
            )
        assertFalse(validator.validate(fields))
        assertTrue(logger.errors.any { it.contains("default value") })
    }

    @Test
    fun `validator rejects WhenRemaining not at tail`() {
        val logger = TestLogger()
        val validator = ConditionalValidator(logger)
        val fields =
            listOf(
                field(
                    "a",
                    "kotlin.UByte",
                    FieldReadStrategy.UByteField,
                    nullable = true,
                    condition = FieldCondition.WhenRemaining(1),
                ),
                field("b", "kotlin.UByte", FieldReadStrategy.UByteField),
            )
        assertFalse(validator.validate(fields))
        assertTrue(logger.errors.any { it.contains("contiguous and at the tail") })
    }

    @Test
    fun `validator rejects non-contiguous WhenRemaining fields`() {
        val logger = TestLogger()
        val validator = ConditionalValidator(logger)
        val fields =
            listOf(
                field(
                    "a",
                    "kotlin.UByte",
                    FieldReadStrategy.UByteField,
                    nullable = true,
                    condition = FieldCondition.WhenRemaining(1),
                ),
                field("middle", "kotlin.UByte", FieldReadStrategy.UByteField),
                field(
                    "b",
                    "kotlin.UByte",
                    FieldReadStrategy.UByteField,
                    nullable = true,
                    condition = FieldCondition.WhenRemaining(1),
                ),
            )
        assertFalse(validator.validate(fields))
        assertTrue(logger.errors.any { it.contains("contiguous and at the tail") })
    }

    @Test
    fun `validator accepts multiple contiguous WhenRemaining at tail`() {
        val logger = TestLogger()
        val validator = ConditionalValidator(logger)
        val fields =
            listOf(
                field("id", "kotlin.UShort", FieldReadStrategy.UShortField),
                field(
                    "reason",
                    "kotlin.UByte",
                    FieldReadStrategy.UByteField,
                    nullable = true,
                    condition = FieldCondition.WhenRemaining(1),
                ),
                field(
                    "extra",
                    "kotlin.Int",
                    FieldReadStrategy.IntField,
                    nullable = true,
                    condition = FieldCondition.WhenRemaining(4),
                ),
            )
        assertTrue(validator.validate(fields))
        assertTrue(logger.errors.isEmpty())
    }

    @Test
    fun `validator rejects WhenRemaining on RemainingBytes`() {
        val logger = TestLogger()
        val validator = ConditionalValidator(logger)
        val fields =
            listOf(
                field("id", "kotlin.UShort", FieldReadStrategy.UShortField),
                field(
                    "data",
                    "kotlin.String",
                    FieldReadStrategy.RemainingBytesStringField,
                    nullable = true,
                    condition = FieldCondition.WhenRemaining(1),
                ),
            )
        assertFalse(validator.validate(fields))
        assertTrue(logger.errors.any { it.contains("@RemainingBytes") })
    }

    @Test
    fun `WhenTrue and WhenRemaining can coexist on different fields`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenTrue
            import com.ditchoom.buffer.codec.annotations.WhenRemaining

            @ProtocolMessage
            data class Mixed(
                val hasExtra: Boolean,
                @WhenTrue("hasExtra") val extra: Int? = null,
                @WhenRemaining(1) val trailing: UByte? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `WhenRemaining with nested message compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenRemaining

            @ProtocolMessage
            data class Inner(val x: UByte, val y: UByte)

            @ProtocolMessage
            data class Outer(
                val header: UByte,
                @WhenRemaining(2) val inner: Inner? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `WhenRemaining with value class compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenRemaining
            import kotlin.jvm.JvmInline

            @JvmInline
            @ProtocolMessage
            value class ReasonCode(val raw: UByte)

            @ProtocolMessage
            data class AckWithValueClass(
                val packetId: UShort,
                @WhenRemaining(1) val reason: ReasonCode? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `three cascading WhenRemaining fields compile`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenRemaining

            @ProtocolMessage
            data class ThreeOptional(
                val id: UShort,
                @WhenRemaining(1) val a: UByte? = null,
                @WhenRemaining(2) val b: UShort? = null,
                @WhenRemaining(4) val c: Int? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `WhenRemaining encode-only direction compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenRemaining
            import com.ditchoom.buffer.codec.annotations.Direction

            @ProtocolMessage(direction = Direction.EncodeOnly)
            data class EncodeOnlyAck(
                val packetId: UShort,
                @WhenRemaining(1) val reasonCode: UByte? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }

    @Test
    fun `WhenRemaining decode-only direction compiles`() {
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenRemaining
            import com.ditchoom.buffer.codec.annotations.Direction

            @ProtocolMessage(direction = Direction.DecodeOnly)
            data class DecodeOnlyAck(
                val packetId: UShort,
                @WhenRemaining(1) val reasonCode: UByte? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
    }
}
