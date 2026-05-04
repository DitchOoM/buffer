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
 * Compile-time validator coverage for `@WhenTrue` (Stage E,
 * Locked Decision row 19). Slice 2 covers the simple-name form
 * (`"siblingField"`); slice 3 covers the dotted form
 * (`"sibling.property"`) where the sibling is a `value class`
 * exposing a `Boolean`-returning `val` property. The validator
 * surfaces user-facing diagnostics naming the offending field path
 * and listing the available `Boolean`-returning `val` properties on
 * the resolved value-class type.
 */
class WhenTrueValidatorTest {
    @Test
    fun acceptsSimpleSiblingBoolean() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.WhenTrue

                @ProtocolMessage
                data class Simple(
                    val hasExtra: Boolean,
                    @WhenTrue("hasExtra") val extra: Int? = null,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@WhenTrue"),
            "valid simple-form @WhenTrue must compile silently. Messages:\n${result.messages}",
        )
    }

    @Test
    fun acceptsDottedValueClassProperty() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.WhenTrue
                import kotlin.jvm.JvmInline

                @JvmInline
                @ProtocolMessage
                value class Flags(val raw: UByte) {
                    val want: Boolean get() = (raw.toInt() and 0x01) != 0
                }

                @ProtocolMessage
                data class Msg(
                    val flags: Flags,
                    @WhenTrue("flags.want") val payload: Int? = null,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@WhenTrue"),
            "valid dotted @WhenTrue must compile silently. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesOnDeeperThanOneLevelPath() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.WhenTrue
                import kotlin.jvm.JvmInline

                @JvmInline
                @ProtocolMessage
                value class Flags(val raw: UByte) {
                    val want: Boolean get() = (raw.toInt() and 0x01) != 0
                }

                @ProtocolMessage
                data class Msg(
                    val flags: Flags,
                    @WhenTrue("flags.want.extra") val payload: Int? = null,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("test.Msg.payload"),
            "diagnostic should reference the offending field. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("deeper-than-one-level"),
            "diagnostic should explain the depth limit. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesWhenSiblingIsNotAValueClass() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.WhenTrue

                @ProtocolMessage
                data class Container(val want: Boolean)

                @ProtocolMessage
                data class Msg(
                    val container: Container,
                    @WhenTrue("container.want") val payload: Int? = null,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("test.Msg.payload"),
            "diagnostic should reference the offending field. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("not a `value class`"),
            "diagnostic should name the sibling-must-be-value-class rule. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesWhenPropertyIsAbsentAndListsAvailable() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.WhenTrue
                import kotlin.jvm.JvmInline

                @JvmInline
                @ProtocolMessage
                value class Flags(val raw: UByte) {
                    val want: Boolean get() = (raw.toInt() and 0x01) != 0
                    val keepAlive: Boolean get() = (raw.toInt() and 0x02) != 0
                }

                @ProtocolMessage
                data class Msg(
                    val flags: Flags,
                    @WhenTrue("flags.missing") val payload: Int? = null,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("test.Msg.payload"),
            "diagnostic should reference the offending field. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("`missing`"),
            "diagnostic should name the missing property. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("want") && result.messages.contains("keepAlive"),
            "diagnostic should list available Boolean val properties (want, keepAlive). " +
                "Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesWhenPropertyIsNotBooleanReturning() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.WhenTrue
                import kotlin.jvm.JvmInline

                @JvmInline
                @ProtocolMessage
                value class Flags(val raw: UByte) {
                    val width: Int get() = raw.toInt() and 0x07
                }

                @ProtocolMessage
                data class Msg(
                    val flags: Flags,
                    @WhenTrue("flags.width") val payload: Int? = null,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("`width`"),
            "diagnostic should name the offending property. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("Boolean"),
            "diagnostic should explain the Boolean-return rule. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesWhenPropertyIsNullableBoolean() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.WhenTrue
                import kotlin.jvm.JvmInline

                @JvmInline
                @ProtocolMessage
                value class Flags(val raw: UByte) {
                    val want: Boolean? get() = if (raw == 0u.toUByte()) null else true
                }

                @ProtocolMessage
                data class Msg(
                    val flags: Flags,
                    @WhenTrue("flags.want") val payload: Int? = null,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("`want`"),
            "diagnostic should name the offending property. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesWhenBoundFieldIsNotNullable() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.WhenTrue
                import kotlin.jvm.JvmInline

                @JvmInline
                @ProtocolMessage
                value class Flags(val raw: UByte) {
                    val want: Boolean get() = (raw.toInt() and 0x01) != 0
                }

                @ProtocolMessage
                data class Msg(
                    val flags: Flags,
                    @WhenTrue("flags.want") val payload: Int = 0,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("test.Msg.payload"),
            "diagnostic should reference the offending field. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("nullable"),
            "diagnostic should name the nullability rule. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesWhenSiblingIsDeclaredAfterBoundField() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.WhenTrue
                import kotlin.jvm.JvmInline

                @JvmInline
                @ProtocolMessage
                value class Flags(val raw: UByte) {
                    val want: Boolean get() = (raw.toInt() and 0x01) != 0
                }

                @ProtocolMessage
                data class Msg(
                    @WhenTrue("flags.want") val payload: Int? = null,
                    val flags: Flags,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("declared at-or-after"),
            "diagnostic should explain the declaration-order rule. Messages:\n${result.messages}",
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
