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
 * `@FramedBy` validator coverage. Four diagnostics
 * exercised; **E6** (coexistence with `@DerivedLength`) is omitted because
 * `@DerivedLength` was removed in the same commit (Q6 — same-commit
 * cleanup), so the test fixture cannot construct the conflict scenario.
 * The validator still carries the E6 check defensively.
 *
 *   - **E1** — codec target must implement `BoundingLengthCodec<UInt>`.
 *   - **E2** — `after = "X"` names a field not on the primary constructor.
 *   - **E3** — `after = "X"` names a field that does not have Exact wire width.
 *   - **E4** — class has `@PacketType` (or its sealed parent does) but
 *     `after = ""`. Discriminator must precede the prefix.
 */
class FramedByValidatorTest {
    @Test
    fun acceptsStandaloneFramedByOnDataClass() {
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.BoundingLengthCodec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.FramedBy
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                object MyLengthCodec : BoundingLengthCodec<UInt> {
                    override val maxWireSize: Int = 4
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                    override fun applyBound(buffer: ReadBuffer, decodedValue: UInt) {
                        buffer.setLimit(buffer.position() + decodedValue.toInt())
                    }
                }

                @ProtocolMessage
                @FramedBy(MyLengthCodec::class)
                data class StandaloneFramed(val payload: UByte, val tail: UShort)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue(
            !result.messages.contains("e: ") || !result.messages.contains("@FramedBy"),
            "no @FramedBy diagnostic should fire on a valid standalone shape. Messages:\n${result.messages}",
        )
    }

    @Test
    fun rejectsCodecThatDoesNotImplementBoundingLengthCodecOfUInt() {
        // E1 — plain Codec<UInt> doesn't satisfy the BoundingLengthCodec<UInt> requirement.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.Codec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.FramedBy
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                object PlainUIntCodec : Codec<UInt> {
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                }

                @ProtocolMessage
                @FramedBy(PlainUIntCodec::class)
                data class BadFramed(val payload: UByte)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("BoundingLengthCodec<UInt>"),
            "expected BoundingLengthCodec<UInt> diagnostic, got:\n${result.messages}",
        )
    }

    @Test
    fun rejectsAfterNameNotOnPrimaryConstructor() {
        // E2 — after = "ghost" but the class has no field named "ghost".
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.BoundingLengthCodec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.FramedBy
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                object MyCodec : BoundingLengthCodec<UInt> {
                    override val maxWireSize: Int = 4
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                    override fun applyBound(buffer: ReadBuffer, decodedValue: UInt) {
                        buffer.setLimit(buffer.position() + decodedValue.toInt())
                    }
                }

                @ProtocolMessage
                @FramedBy(MyCodec::class, after = "ghost")
                data class MissingAfter(val payload: UByte, val tail: UShort)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("ghost") && result.messages.contains("Available"),
            "expected after-name-not-on-primary-constructor diagnostic, got:\n${result.messages}",
        )
    }

    @Test
    fun rejectsAfterFieldWithoutExactWireWidth() {
        // E3 — after = "name" but `name: String` doesn't have Exact wire width.
        val result =
            compile(
                """
                package test

                import com.ditchoom.buffer.ReadBuffer
                import com.ditchoom.buffer.WriteBuffer
                import com.ditchoom.buffer.codec.BoundingLengthCodec
                import com.ditchoom.buffer.codec.DecodeContext
                import com.ditchoom.buffer.codec.EncodeContext
                import com.ditchoom.buffer.codec.WireSize
                import com.ditchoom.buffer.codec.annotations.FramedBy
                import com.ditchoom.buffer.codec.annotations.LengthPrefixed
                import com.ditchoom.buffer.codec.annotations.ProtocolMessage

                object MyCodec : BoundingLengthCodec<UInt> {
                    override val maxWireSize: Int = 4
                    override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt = buffer.readInt().toUInt()
                    override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                        buffer.writeInt(value.toInt())
                    }
                    override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                    override fun applyBound(buffer: ReadBuffer, decodedValue: UInt) {
                        buffer.setLimit(buffer.position() + decodedValue.toInt())
                    }
                }

                @ProtocolMessage
                @FramedBy(MyCodec::class, after = "name")
                data class StringAfter(@LengthPrefixed val name: String, val tail: UShort)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("Exact wire width"),
            "expected Exact-wire-width diagnostic, got:\n${result.messages}",
        )
    }

    @Test
    fun rejectsAfterEmptyOnDispatchOnSealedParent() {
        // E4 — a dispatching sealed parent requires after = "<headerField>" so the discriminator
        // precedes the framing prefix on the wire. Stated on a @DispatchOn parent: without one,
        // E5 rejects first and this would silently be testing that instead.
        val result =
            compile(
                """
                package test

                $VALIDATOR_IMPORTS
                import com.ditchoom.buffer.codec.annotations.DispatchOn
                import com.ditchoom.buffer.codec.annotations.DispatchValue

                $BOUNDING_CODEC

                @JvmInline
                @ProtocolMessage
                value class Header(val raw: UByte) {
                    @DispatchValue
                    val kind: Int get() = raw.toUInt().shr(4).toInt()
                }

                @DispatchOn(Header::class)
                @ProtocolMessage
                @FramedBy(MyCodec::class)
                sealed interface Bad {
                    @ProtocolMessage @PacketType(value = 1, wire = 0x10)
                    data class Ping(val header: Header, val timestamp: Long) : Bad
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("after = \"\""),
            "expected E4 (after-empty) diagnostic, got:\n${result.messages}",
        )
    }

    /**
     * **E5** — the shape from #359. Every `@FramedBy` check passed on this declaration and the
     * emitted dispatcher then failed to compile, because the analyzer derives framing for the
     * dispatcher only under `@DispatchOn`: the variants inherited framing while the dispatcher
     * did not, so the unframed emit called framed variant codecs with the `Codec` signature.
     *
     * `after = "kind"` here is what E4's own message tells you to write, and `kind` is present
     * with `Exact` wire width on every variant — so E1–E4 all pass. Without E5 the only signal
     * is an argument-type mismatch inside generated source.
     */
    @Test
    fun rejectsFramedByOnSealedParentWithoutDispatchOn() {
        val result =
            compile(
                """
                package test

                $VALIDATOR_IMPORTS

                $BOUNDING_CODEC

                @ProtocolMessage
                @FramedBy(MyCodec::class, after = "kind")
                sealed interface Bad {
                    @ProtocolMessage @PacketType(0x01)
                    data class Ping(val kind: UByte, val timestamp: Long) : Bad

                    @ProtocolMessage @PacketType(0x02)
                    data class Pong(val kind: UByte) : Bad
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("@DispatchOn"),
            "expected E5 (framed sealed parent needs @DispatchOn), got:\n${result.messages}",
        )
    }

    /**
     * The positive control for E5: the shipped shape must keep compiling. Without this, E5 could
     * over-reject every framed dispatcher and the negative above would still pass.
     */
    @Test
    fun acceptsFramedByOnDispatchOnSealedParent() {
        val result =
            compile(
                """
                package test

                $VALIDATOR_IMPORTS
                import com.ditchoom.buffer.codec.annotations.DispatchOn
                import com.ditchoom.buffer.codec.annotations.DispatchValue

                $BOUNDING_CODEC

                @JvmInline
                @ProtocolMessage
                value class Header(val raw: UByte) {
                    @DispatchValue
                    val kind: Int get() = raw.toUInt().shr(4).toInt()
                }

                @DispatchOn(Header::class)
                @ProtocolMessage
                @FramedBy(MyCodec::class, after = "header")
                sealed interface Good {
                    @ProtocolMessage @PacketType(value = 1, wire = 0x10)
                    data class Ping(val header: Header, val timestamp: Long) : Good

                    @ProtocolMessage @PacketType(value = 2, wire = 0x20)
                    data class Pong(val header: Header) : Good
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    /**
     * The second way into #359, found while writing this file as an *accept* case: framing one
     * variant of an unframed dispatcher breaks the same way. The dispatcher calls every variant
     * codec through one encode signature, and framing changes that signature, so `OuterCodec`
     * failed to compile with the identical argument-shift mismatch.
     */
    @Test
    fun rejectsFramedByOnASingleVariantOfAnUnframedDispatcher() {
        val result =
            compile(
                """
                package test

                $VALIDATOR_IMPORTS

                $BOUNDING_CODEC

                @ProtocolMessage
                sealed interface Outer {
                    @ProtocolMessage
                    @PacketType(0x01)
                    @FramedBy(MyCodec::class, after = "kind")
                    data class Ping(val kind: UByte, val timestamp: Long) : Outer

                    @ProtocolMessage @PacketType(0x02)
                    data class Pong(val kind: UByte) : Outer
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("single variant"),
            "expected E5 (uniform framing across a dispatch family), got:\n${result.messages}",
        )
    }

    /** A framed variant under a framed @DispatchOn parent is the shipped shape and must compile. */
    @Test
    fun acceptsAFramedVariantUnderAFramedDispatchOnParent() {
        val result =
            compile(
                """
                package test

                $VALIDATOR_IMPORTS
                import com.ditchoom.buffer.codec.annotations.DispatchOn
                import com.ditchoom.buffer.codec.annotations.DispatchValue

                $BOUNDING_CODEC

                @JvmInline
                @ProtocolMessage
                value class Header(val raw: UByte) {
                    @DispatchValue
                    val kind: Int get() = raw.toUInt().shr(4).toInt()
                }

                @DispatchOn(Header::class)
                @ProtocolMessage
                @FramedBy(MyCodec::class, after = "header")
                sealed interface Good {
                    @ProtocolMessage
                    @PacketType(value = 1, wire = 0x10)
                    @FramedBy(MyCodec::class, after = "header")
                    data class Ping(val header: Header, val timestamp: Long) : Good
                }
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    /** A standalone @FramedBy message that is not in any dispatch family is untouched by E5. */
    @Test
    fun acceptsFramedByOnANonDispatchNestedMessage() {
        val result =
            compile(
                """
                package test

                $VALIDATOR_IMPORTS

                $BOUNDING_CODEC

                @ProtocolMessage
                @FramedBy(MyCodec::class, after = "kind")
                data class Standalone(val kind: UByte, val timestamp: Long)
                """.trimIndent(),
            )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    private companion object {
        val VALIDATOR_IMPORTS =
            """
            import com.ditchoom.buffer.ReadBuffer
            import com.ditchoom.buffer.WriteBuffer
            import com.ditchoom.buffer.codec.BoundingLengthCodec
            import com.ditchoom.buffer.codec.DecodeContext
            import com.ditchoom.buffer.codec.EncodeContext
            import com.ditchoom.buffer.codec.WireSize
            import com.ditchoom.buffer.codec.annotations.FramedBy
            import com.ditchoom.buffer.codec.annotations.PacketType
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            """.trimIndent()

        val BOUNDING_CODEC =
            """
            object MyCodec : BoundingLengthCodec<UInt> {
                override val maxWireSize: Int = 4
                override fun decode(buffer: ReadBuffer, context: DecodeContext): UInt =
                    buffer.readInt().toUInt()
                override fun encode(buffer: WriteBuffer, value: UInt, context: EncodeContext) {
                    buffer.writeInt(value.toInt())
                }
                override fun wireSize(value: UInt, context: EncodeContext): WireSize = WireSize.Exact(4)
                override fun applyBound(buffer: ReadBuffer, decodedValue: UInt) {
                    buffer.setLimit(buffer.position() + decodedValue.toInt())
                }
            }
            """.trimIndent()
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
