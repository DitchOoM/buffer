package com.ditchoom.buffer.codec.processor

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.useKsp2
import org.intellij.lang.annotations.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Soundness rules for `@When("remaining <op> N")` — the grammar-2 optional-trailing predicate.
 *
 * Two independent guards, both of which previously did not exist:
 *
 *  - **Boundedness.** `remaining()` counts bytes left in the *buffer*, not in the message. A
 *    message carrying this grammar, nested unbounded, reads the ENCLOSING message's next fields
 *    into its optional slot — returning a plausible wrong value on a well-formed frame, with no
 *    exception. That is strictly worse than the throw it replaces, which is why these are hard
 *    errors rather than warnings.
 *  - **Threshold/width agreement.** A threshold below the field's minimum wire width admits
 *    frames the guarded read then fails on, defeating the tolerance the predicate exists to
 *    provide.
 */
class OptionalTrailingBoundednessTest {
    // ---- boundedness --------------------------------------------------------

    @Test
    fun rejectsUnboundedNestedMessage() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.When

                @ProtocolMessage
                data class Inner(val a: Int, @When("remaining >= 4") val opt: Int? = null)

                @ProtocolMessage
                data class Outer(val inner: Inner, val afterInner: Int)
                """.trimIndent(),
            )
        assertEquals(
            KotlinCompilation.ExitCode.COMPILATION_ERROR,
            result.exitCode,
            "nesting an optional-trailing message UNBOUNDED must fail the build, not generate a " +
                "decoder that reads Outer.afterInner into Inner.opt. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("UNBOUNDED"),
            "diagnostic must name the boundedness problem:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("@LengthPrefixed"),
            "diagnostic must name the fix:\n${result.messages}",
        )
    }

    @Test
    fun `accepts a nested optional-trailing message behind a length prefix`() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.When

                @ProtocolMessage
                data class Inner(val a: Int, @When("remaining >= 4") val opt: Int? = null)

                @ProtocolMessage
                data class Outer(val beforeInner: Int, @LengthPrefixed val inner: Inner)
                """.trimIndent(),
            )
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "@LengthPrefixed bounds the nested extent, so the guard is sound:\n${result.messages}",
        )
    }

    @Test
    fun rejectsOptionalTrailingListElement() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.Count
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.When

                @ProtocolMessage
                data class Entry(val a: Int, @When("remaining >= 4") val opt: Int? = null)

                @ProtocolMessage
                data class Listing(@Count val entries: List<Entry>)
                """.trimIndent(),
            )
        assertEquals(
            KotlinCompilation.ExitCode.COMPILATION_ERROR,
            result.exitCode,
            "list elements are not bounded from one another — element k's guard would consume " +
                "element k+1's head. Messages:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("element k"),
            "diagnostic must explain the element-to-element aliasing:\n${result.messages}",
        )
    }

    @Test
    fun `accepts a message with no optional trailing fields nested bare`() {
        // The rule must not fire on ordinary nesting — only on types whose decode tests
        // remaining(). Without this, the check would reject most of the existing corpus.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                @ProtocolMessage
                data class Inner(val a: Int, val b: Int)

                @ProtocolMessage
                data class Outer(val inner: Inner, val afterInner: Int)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `accepts a framed sealed parent whose variants carry the grammar`() {
        // A @FramedBy parent narrows the limit around each variant, so the variants' guards are
        // bounded by construction. This is the MQTT v5 ack-cascade shape; rejecting it would
        // break every already-shipped consumer of grammar 2.
        val result = compile(FRAMED_SEALED_PARENT)
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "a @FramedBy parent bounds its variants:\n${result.messages}",
        )
    }

    // ---- threshold / width agreement ----------------------------------------

    @Test
    fun `rejects a threshold narrower than the field`() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.When

                @ProtocolMessage
                data class M(val a: Int, @When("remaining >= 1") val opt: Int? = null)
                """.trimIndent(),
            )
        assertEquals(
            KotlinCompilation.ExitCode.COMPILATION_ERROR,
            result.exitCode,
            "a 1-byte guard on a 4-byte read admits frames the read then fails on:\n${result.messages}",
        )
        assertTrue(result.messages.contains("at least 4 bytes"), result.messages)
    }

    @Test
    fun `accepts a threshold that matches the field`() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.When

                @ProtocolMessage
                data class M(val a: Int, @When("remaining >= 4") val opt: Int? = null)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `accepts a threshold wider than the field`() {
        // The rule is `threshold >= minimum`, not equality. Cumulative thresholds — guarding an
        // earlier field on the width of the whole tail — are a real idiom in shipped consumers.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.When

                @ProtocolMessage
                data class M(
                    val a: Int,
                    @When("remaining >= 8") val first: Int? = null,
                    @When("remaining >= 4") val second: Int? = null,
                )
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `accepts a value class over a scalar at its inner width`() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.codec.annotations.ProtocolMessage
                import com.ditchoom.buffer.codec.annotations.When
                import kotlin.jvm.JvmInline

                @JvmInline
                @ProtocolMessage
                value class Tag(val value: Int)

                @ProtocolMessage
                data class M(val a: Int, @When("remaining >= 4") val tag: Tag? = null)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    // ---- harness ------------------------------------------------------------

    private fun compile(
        @Language("kotlin") source: String,
    ): JvmCompilationResult {
        val compilation =
            KotlinCompilation().apply {
                sources = listOf(SourceFile.kotlin("Test.kt", source))
                inheritClassPath = true
                messageOutputStream = System.out
                useKsp2()
                configureKsp {
                    symbolProcessorProviders += ProtocolMessageProcessorProvider()
                }
            }
        return compilation.compile()
    }

    private companion object {
        /** MQTT-shaped framed dispatch parent: value-class discriminator + bounding length codec. */
        @Language("kotlin")
        private val FRAMED_SEALED_PARENT =
            """
            package test

            import com.ditchoom.buffer.ReadBuffer
            import com.ditchoom.buffer.WriteBuffer
            import com.ditchoom.buffer.codec.BoundingLengthCodec
            import com.ditchoom.buffer.codec.DecodeContext
            import com.ditchoom.buffer.codec.EncodeContext
            import com.ditchoom.buffer.codec.WireSize
            import com.ditchoom.buffer.codec.annotations.DispatchOn
            import com.ditchoom.buffer.codec.annotations.DispatchValue
            import com.ditchoom.buffer.codec.annotations.FramedBy
            import com.ditchoom.buffer.codec.annotations.PacketType
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.codec.annotations.When
            import kotlin.jvm.JvmInline

            object LenCodec : BoundingLengthCodec<UInt> {
                override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt =
                    buffer.readUByte().toUInt()
                override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                    buffer.writeUByte(value.toUByte())
                }
                override fun wireSize(value: UInt, context: EncodeContext): WireSize =
                    WireSize.Exact(1)
                override fun applyBound(buffer: ReadBuffer, decodedValue: UInt) {
                    buffer.setLimit(buffer.position() + decodedValue.toInt())
                }
                override val maxWireSize: Int = 1
            }

            @JvmInline
            @ProtocolMessage
            value class Header(val raw: UByte) {
                @DispatchValue
                val kind: Int get() = raw.toUInt().shr(4).toInt()
            }

            @DispatchOn(Header::class)
            @FramedBy(LenCodec::class, after = "header")
            @ProtocolMessage
            sealed interface Frame {
                @PacketType(value = 1, wire = 0x10)
                @ProtocolMessage
                data class Ack(
                    val header: Header,
                    val id: Int,
                    @When("remaining >= 4") val reason: Int? = null,
                ) : Frame
            }
            """.trimIndent()
    }
}
