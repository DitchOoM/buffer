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
 * Compile-time validator coverage for `@LengthFrom`.
 *
 * Rule R1 (adjacent-rejection): `@LengthFrom("X")` on field F where X
 * is the field immediately preceding F in the same `@ProtocolMessage`
 * data class is a compile error **when the bound field has a viable
 * `@LengthPrefixed` migration target** (String or `@ProtocolMessage`
 * data class type). Bound fields whose type extends the Payload
 * marker interface (`com.ditchoom.buffer.codec.Payload`) are skipped
 * — `@LengthPrefixed` does not yet widen to cover Payload slots
 * (Stage H deferral), and forbidding the adjacent shape today would
 * leave those fields with no migration path. `@LengthFrom` is
 * otherwise reserved for genuine remote-prefix uses (length carried
 * in a non-adjacent field, parsed elsewhere).
 */
class LengthFromValidatorTest {
    @Test
    fun firesOnAdjacentLengthFromString() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthFrom
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class NamedRecord(
                    val nameLength: UShort,
                    @LengthFrom("nameLength") val name: String,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsAdjacentLengthFromError(
            result,
            owner = "test.NamedRecord",
            boundField = "name",
            referencedField = "nameLength",
        )
    }

    @Test
    fun acceptsAdjacentLengthFromOnNestedProtocolMessageBody() {
        // J.M.6.b carve-out (issue #151 part 1): R1 stays silent when the
        // bound field's type is a nested `@ProtocolMessage` data class or
        // sealed parent. The `@LengthPrefixed` migration target only
        // supports 1 / 2 / 4-byte prefixes (LengthPrefix.Byte / Short /
        // Int) — protocols with non-standard prefix widths (e.g. TLS
        // uint24) cannot express their wire shape via `@LengthPrefixed`
        // and genuinely need `@LengthFrom` even when the length sibling
        // is adjacent.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthFrom
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.WireBytes

                @ProtocolMessage
                data class Body(val a: UByte, val b: UByte)

                @ProtocolMessage
                data class Frame(
                    val msgType: UByte,
                    @WireBytes(3) val length: UInt,
                    @LengthFrom("length") val body: Body,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("immediately preceding constructor parameter"),
            "adjacent @LengthFrom on nested @ProtocolMessage must not trigger R1. Messages:\n${result.messages}",
        )
    }

    @Test
    fun acceptsRemoteLengthFromNonAdjacent() {
        // Genuine remote-prefix: the length carrier is not the immediately
        // preceding field. R1 must stay silent here — that's the surviving
        // valid use of `@LengthFrom` after Q3 narrows the annotation.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthFrom
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class RemoteHeader(
                    val payloadLength: UShort,
                    val flags: UByte,
                    val correlationId: UInt,
                    @LengthFrom("payloadLength") val payload: String,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@LengthFrom"),
            "non-adjacent @LengthFrom must not trigger R1. Messages:\n${result.messages}",
        )
    }

    @Test
    fun acceptsAdjacentLengthFromOnPayloadField() {
        // Bound field's type extends the Payload marker interface, which has
        // no @LengthPrefixed migration target until Stage H widens the
        // annotation. R1 must stay silent here — firing would leave Payload
        // slots with no migration path. (See PHASE_10_RESUME.md Stage-H
        // follow-up note.)
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.Payload
                import com.ditchoom.buffer.codec.annotations.LengthFrom
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                data class OpaqueBlob(val bytes: kotlin.ByteArray) : Payload

                @ProtocolMessage
                data class PayloadBoundFrame(
                    val length: UShort,
                    @LengthFrom("length") val data: OpaqueBlob,
                )
                """.trimIndent(),
            )
        // We don't assert OK exitCode — other validators (e.g., §8) may have
        // independent opinions about Payload-tagged fields. The §8 walk
        // short-circuits on Payload, but we only assert here that R1's own
        // adjacent-rejection diagnostic does not fire.
        assertFalse(
            result.messages.contains("immediately preceding"),
            "R1 must not fire when bound field type extends Payload. Messages:\n${result.messages}",
        )
    }

    @Test
    fun acceptsLengthFromOnFirstParameter() {
        // `@LengthFrom` on the first constructor parameter cannot be
        // adjacent to a preceding parameter; R1 has nothing to say. (The
        // referenced field `outerLength` is parent-passed via a separate
        // mechanism; whether that resolves is not R1's concern.)
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthFrom
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class ParentBoundFrame(
                    @LengthFrom("outerLength") val payload: String,
                )
                """.trimIndent(),
            )
        // We don't assert OK exitCode here (the referenced field doesn't exist
        // in this fixture, which other validators may flag) — only that R1's
        // adjacent-rejection diagnostic does not appear.
        assertFalse(
            result.messages.contains("immediately preceding"),
            "R1 must not fire when @LengthFrom is on the first parameter. Messages:\n${result.messages}",
        )
    }

    private fun assertContainsAdjacentLengthFromError(
        result: JvmCompilationResult,
        owner: String,
        boundField: String,
        referencedField: String,
    ) {
        assertTrue(
            result.messages.contains("$owner.$boundField"),
            "diagnostic should reference the bound field $owner.$boundField. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("$owner.$referencedField"),
            "diagnostic should reference the preceding field $owner.$referencedField. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("@LengthFrom"),
            "diagnostic should name the offending annotation. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("@LengthPrefixed"),
            "diagnostic should point at @LengthPrefixed as the replacement. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("immediately preceding"),
            "diagnostic should explain the adjacent relationship. Messages:\n${result.messages}",
        )
    }

    @Test
    fun acceptsNonAdjacentNumericSibling() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthFrom
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class RemoteHeader(
                    val payloadLength: UShort,
                    val flags: UByte,
                    val correlationId: UInt,
                    @LengthFrom("payloadLength") val payload: String,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(
            result.messages.contains("@LengthFrom"),
            "valid non-adjacent @LengthFrom must compile silently. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesWhenSiblingIsMissingAndListsAvailable() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthFrom
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class Msg(
                    val expectedLength: UShort,
                    val flags: UByte,
                    @LengthFrom("missing") val payload: String,
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
            "diagnostic should name the missing field. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("expectedLength"),
            "diagnostic should list available numeric siblings (expectedLength). Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesWhenSiblingIsDeclaredAfterBoundField() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthFrom
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class Msg(
                    @LengthFrom("payloadLength") val payload: String,
                    val payloadLength: UShort,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("declared at-or-after"),
            "diagnostic should explain the declaration-order rule. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesWhenSiblingIsNonNumeric() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthFrom
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class Msg(
                    val tag: String,
                    val flags: UByte,
                    @LengthFrom("tag") val payload: String,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("test.Msg.payload"),
            "diagnostic should reference the offending field. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("non-nullable numeric scalar"),
            "diagnostic should name the numeric-source rule. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesWhenBoundFieldIsNotString() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthFrom
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class Msg(
                    val payloadLength: UShort,
                    val flags: UByte,
                    @LengthFrom("payloadLength") val payload: Int,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("test.Msg.payload"),
            "diagnostic should reference the offending field. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("non-nullable `String`"),
            "diagnostic should name the field-type universe restriction. Messages:\n${result.messages}",
        )
    }

    @Test
    fun firesWhenBoundFieldIsNullableString() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthFrom
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class Msg(
                    val payloadLength: UShort,
                    val flags: UByte,
                    @LengthFrom("payloadLength") val payload: String?,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("non-nullable `String`"),
            "diagnostic should name the non-nullable rule. Messages:\n${result.messages}",
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
