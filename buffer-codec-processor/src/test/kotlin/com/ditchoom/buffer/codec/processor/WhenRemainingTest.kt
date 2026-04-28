package com.ditchoom.buffer.codec.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
    fun `WhenRemaining encode does not emit redundant non-null assertion`() {
        // Regression: the encode emitter used to produce `value.x!!` inside
        // `if (value.x != null)` blocks. For final-class properties Kotlin
        // smart-casts `value.x` after the null check, so the `!!` is
        // unnecessary and trips UNNECESSARY_NOT_NULL_ASSERTION. Fix captures
        // a local `val` before the check and references it at the use site.
        val source =
            SourceFile.kotlin(
                "Test.kt",
                """
            package test
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.WhenRemaining

            @ProtocolMessage
            data class TailOptionals(
                val packetId: UShort,
                @WhenRemaining(1) val reasonCode: UByte? = null,
                @WhenRemaining(2) val extra: UShort? = null,
            )
            """,
            )
        val result = compileWithKsp(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation failed:\n${result.messages}")
        val codec =
            result.generatedSources.singleOrNull { it.contains("object TailOptionalsCodec") }
                ?: error("Expected TailOptionalsCodec in generated sources:\n${result.generatedSources}")
        assertFalse(
            codec.contains("value.reasonCode!!") || codec.contains("value.extra!!"),
            "WhenRemaining encode should not emit `value.X!!` (smart-cast via local val). Generated:\n$codec",
        )
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

    // ──────────────────────── Mixed conditional cases ────────────────────────

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
