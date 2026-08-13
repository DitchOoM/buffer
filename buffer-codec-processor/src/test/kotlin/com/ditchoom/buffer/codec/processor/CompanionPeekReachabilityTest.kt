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
 * What a *consumer* can reach on a generated codec, asserted by compiling consumer code against
 * the generated output rather than by reading the emitted text.
 *
 * `CompanionPeekPlacementTest` covers the emitter's own rules against hand-built specs, and the
 * snapshot baseline pins what gets written. Neither can state the claim that matters here, which
 * is about an *absence*: the unframed generic dispatcher is the one shape whose peek cannot be
 * hoisted (its body names constructor-injected variant codec fields), so it has no companion
 * entry point and a consumer must hold an instance. Only a compile can witness that.
 *
 * Each negative pairs with a positive control over the same fixture, so a broken fixture fails
 * the control instead of silently "passing" the negative — a compile-failure assertion that only
 * checks the exit code passes for every reason, including the ones you did not mean.
 */
class CompanionPeekReachabilityTest {
    // ---- the unframed generic dispatcher: instance only --------------------

    @Test
    fun anUnframedGenericDispatcherPeeksThroughAnInstance() {
        val result = compile(unframedGenericDispatcher(consumer = INSTANCE_PEEK))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun anUnframedGenericDispatcherHasNoCompanionPeek() {
        val result = compile(unframedGenericDispatcher(consumer = COMPANION_PEEK))
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            "peekFrameSize" in result.messages,
            "the failure must be about the missing entry point, not the fixture: ${result.messages}",
        )
    }

    // ---- every other class-shaped codec: both ------------------------------

    /**
     * The contrast that makes the negative meaningful. A framed generic dispatcher's peek is one
     * collapsed header+prefix walk naming only type names, so it hoists — same fixture shape,
     * same consumer expression, opposite answer.
     */
    @Test
    fun aFramedGenericDispatcherPeeksOffItsCompanion() {
        val result = compile(framedGenericDispatcher(consumer = COMPANION_PEEK))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    /**
     * And an *unframable* generic codec hoists too — placement follows what the body can reach,
     * not whether the shape frames. `NoFraming` is the runtime answer, not a missing symbol.
     */
    @Test
    fun anUnframableGenericCodecStillPeeksOffItsCompanion() {
        val result = compile(unframableGenericCodec(consumer = COMPANION_PEEK))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun anUnframableGenericCodecCompanionIsAFrameDetectorValue() {
        val result = compile(unframableGenericCodec(consumer = COMPANION_AS_DETECTOR))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    // ---- fixtures ---------------------------------------------------------

    private fun unframedGenericDispatcher(consumer: String) =
        """
        package test

        $CONSUMER_IMPORTS
        import com.ditchoom.buffer.codec.annotations.LengthPrefixed
        import com.ditchoom.buffer.codec.annotations.PacketType
        import com.ditchoom.buffer.codec.annotations.RemainingBytes

        @ProtocolMessage
        sealed interface Command<out P : Payload> {
            @ProtocolMessage @PacketType(0x01)
            data class Ping(val ts: Long) : Command<Nothing>

            @ProtocolMessage @PacketType(0x02)
            data class Publish<P : Payload>(
                @LengthPrefixed val topic: String,
                @RemainingBytes val payload: P,
            ) : Command<P>
        }

        ${consumer.replace(CODEC, "CommandCodec")}
        """.trimIndent()

    private fun framedGenericDispatcher(consumer: String) =
        """
        package test

        $CONSUMER_IMPORTS
        import com.ditchoom.buffer.codec.annotations.DispatchOn
        import com.ditchoom.buffer.codec.annotations.DispatchValue
        import com.ditchoom.buffer.codec.annotations.FramedBy
        import com.ditchoom.buffer.codec.annotations.PacketType
        import com.ditchoom.buffer.codec.annotations.RemainingBytes

        $LENGTH_CODEC

        @JvmInline
        @ProtocolMessage
        value class TinyHeader(val raw: UByte) {
            @DispatchValue
            val kind: Int get() = raw.toUInt().shr(4).toInt()
        }

        // Mirrors the shipped `Slice14cGenericFramedDispatch` shape: a value-class
        // discriminator the framing prefix follows.
        @DispatchOn(TinyHeader::class)
        @FramedBy(MyLengthCodec::class, after = "header")
        @ProtocolMessage
        sealed interface Framed<out P : Payload> {
            @ProtocolMessage @PacketType(value = 1, wire = 0x10)
            data class Empty(val header: TinyHeader, val a: UByte) : Framed<Nothing>

            @ProtocolMessage @PacketType(value = 2, wire = 0x20)
            data class Body<P : Payload>(
                val header: TinyHeader,
                @RemainingBytes val payload: P,
            ) : Framed<P>
        }

        ${consumer.replace(CODEC, "FramedCodec")}
        """.trimIndent()

    private fun unframableGenericCodec(consumer: String) =
        """
        package test

        $CONSUMER_IMPORTS
        import com.ditchoom.buffer.codec.annotations.RemainingBytes

        // A to-limit payload gives peek no byte count to derive, so this collapses to NoFraming.
        @ProtocolMessage
        data class Envelope<P : Payload>(
            val kind: UByte,
            @RemainingBytes val payload: P,
        )

        ${consumer.replace(CODEC, "EnvelopeCodec")}
        """.trimIndent()

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

    private companion object {
        /** Replaced per fixture with that fixture's generated codec name. */
        const val CODEC = "%CODEC%"

        val CONSUMER_IMPORTS =
            """
            import com.ditchoom.buffer.codec.FrameDetector
            import com.ditchoom.buffer.codec.Payload
            import com.ditchoom.buffer.codec.PeekResult
            import com.ditchoom.buffer.codec.annotations.ProtocolMessage
            import com.ditchoom.buffer.stream.StreamProcessor
            """.trimIndent()

        val LENGTH_CODEC =
            """
            import com.ditchoom.buffer.ReadBuffer
            import com.ditchoom.buffer.WriteBuffer
            import com.ditchoom.buffer.codec.BoundingLengthCodec
            import com.ditchoom.buffer.codec.DecodeContext
            import com.ditchoom.buffer.codec.EncodeContext
            import com.ditchoom.buffer.codec.WireSize

            object MyLengthCodec : BoundingLengthCodec<UInt> {
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

        val PAYLOAD_CODEC =
            """
            class MyPayload(val raw: UByte) : Payload

            class MyPayloadCodec : com.ditchoom.buffer.codec.Codec<MyPayload> {
                override fun decode(
                    buffer: com.ditchoom.buffer.ReadBuffer,
                    context: com.ditchoom.buffer.codec.DecodeContext,
                ): MyPayload = MyPayload(buffer.readByte().toUByte())

                override fun encode(
                    buffer: com.ditchoom.buffer.WriteBuffer,
                    value: MyPayload,
                    context: com.ditchoom.buffer.codec.EncodeContext,
                ) {
                    buffer.writeByte(value.raw.toByte())
                }

                override fun wireSize(
                    value: MyPayload,
                    context: com.ditchoom.buffer.codec.EncodeContext,
                ): com.ditchoom.buffer.codec.WireSize = com.ditchoom.buffer.codec.WireSize.Exact(1)
            }
            """.trimIndent()

        /** Reaches peek through a constructed codec — always available. */
        val INSTANCE_PEEK =
            """
            fun peekViaInstance(stream: StreamProcessor, payloadCodec: MyPayloadCodec): PeekResult =
                $CODEC(payloadCodec).peekFrameSize(stream)
            $PAYLOAD_CODEC
            """.trimIndent()

        /** Reaches peek with no instance in the expression — only where the peek is hoisted. */
        val COMPANION_PEEK =
            """
            fun peekViaCompanion(stream: StreamProcessor): PeekResult = $CODEC.peekFrameSize(stream)
            """.trimIndent()

        /** Binds the companion as a value, not merely a name carrying a same-shaped function. */
        val COMPANION_AS_DETECTOR =
            """
            fun detector(): FrameDetector = $CODEC
            """.trimIndent()
    }
}
