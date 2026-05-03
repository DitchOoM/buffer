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
    fun firesOnAdjacentLengthFromMessageBody() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthFrom
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class Body(val a: UByte, val b: UByte)

                @ProtocolMessage
                data class Frame(
                    val fourCC: UInt,
                    val chunkSize: UInt,
                    @LengthFrom("chunkSize") val body: Body,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContainsAdjacentLengthFromError(
            result,
            owner = "test.Frame",
            boundField = "body",
            referencedField = "chunkSize",
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
