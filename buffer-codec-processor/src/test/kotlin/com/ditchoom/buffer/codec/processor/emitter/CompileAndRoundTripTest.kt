package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.squareup.kotlinpoet.ClassName
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 9 Step 1 — In-process compile + round-trip harness for [CodecEmitter].
 *
 * The harness drives [CodecEmitter] against a hand-built [Plan] IR fixture,
 * compiles the resulting Kotlin source in-process via the Kotlin embeddable
 * compiler (kctfork's [KotlinCompilation]) against the real `:buffer` and
 * `:buffer-codec` JVM jars, loads the generated codec object via reflection,
 * and round-trips a fixture value through encode → decode. Equality is
 * structural.
 *
 * Why this exists: Phase 9 Steps 2-4 delete the legacy emitter and fix-forward
 * the resulting compile errors by adding capabilities to the new emitter. Each
 * capability needs a fast-feedback test that proves the emitted code actually
 * round-trips, not just that the emit text matches a pinned snapshot. This
 * harness is the prerequisite for "delete and fix forward" to be safe.
 *
 * The third fixture below — [Plan.Sealed_] with a [com.ditchoom.buffer.codec.processor.ir.VariantPlan.WithPayload]
 * variant — is the load-bearing one. It documents the gap that's been hidden
 * behind `pipelineEligible()`'s defers in `ProtocolMessageProcessor.kt`: at
 * HEAD `5a0a1bc` the [SealedEmitter] only emits the context-overload path
 * (`decode(buf, ctx)` reading lambdas from the context Key) — it does NOT
 * emit the typed-lambda overload (`<P> decode<P>(buf, payloadDecoder)`) that
 * legacy consumers actually call. Step 3 of the accelerated phase 9 plan
 * implements that capability; this test is the assertion that flips when
 * the capability lands.
 */
class CompileAndRoundTripTest {
    private val emitter = CodecEmitter(EmitterFixtures.standardRegistry())

    private fun emitText(
        plan: Plan,
        classType: ClassName,
    ): String = emitter.emit(plan, classType).toString()

    // ---------------------------------------------------------------------
    // Fixture 1 — Plan.Object_ zero-byte singleton (KeepAliveTick / PingResponse).
    //
    // Validates: encoder writes nothing, decoder returns the singleton, wire
    // size is 0, and structural equality holds across the round-trip.
    // ---------------------------------------------------------------------

    @Test
    fun `Object_ singleton round-trips through compiled codec`() {
        val plan = EmitterFixtures.pingResponse()
        val classType = EmitterFixtures.cn("PingResponse")
        val codecSource = emitText(plan, classType)

        val userTypeSource =
            """
            package com.ditchoom.codec.test

            data object PingResponse
            """.trimIndent()

        val (codec, _) =
            compileAndLoadCodec(
                codecSource = codecSource,
                userTypes = userTypeSource,
                codecFqn = "com.ditchoom.codec.test.PingResponseCodec",
            )

        // Build the singleton instance reflectively (data object PingResponse).
        val ptrCls = codec::class.java.classLoader.loadClass("com.ditchoom.codec.test.PingResponse")
        val singleton = ptrCls.getField("INSTANCE").get(null)

        // Encode → decode round-trip.
        val buffer: PlatformBuffer = BufferFactory.Default.allocate(0)
        invokeEncode(codec, buffer, singleton)
        // Object_ is zero-width; verify the encoder wrote nothing.
        assertEquals(0, buffer.position(), "encode(PingResponse) wrote bytes; expected zero-width")
        buffer.resetForRead()
        val decoded = invokeDecode(codec, buffer)

        assertEquals(singleton, decoded, "Object_ singleton not preserved across round-trip")

        // Also verify wireSize.
        val ws = invokeWireSize(codec, singleton)
        assertEquals(0, ws, "wireSize(PingResponse) should be 0")
    }

    // ---------------------------------------------------------------------
    // Fixture 2 — Plan.Leaf, value class wrapping a single primitive.
    //
    // Models the MqttFixedHeader shape: `value class MqttFixedHeader(val raw: UByte)`.
    // Validates: a non-default value round-trips correctly, wire size is 1.
    // ---------------------------------------------------------------------

    @Test
    fun `Leaf value-class single-primitive round-trips through compiled codec`() {
        val plan = EmitterFixtures.mqttFixedHeader()
        val classType = EmitterFixtures.cn("MqttFixedHeader")
        val codecSource = emitText(plan, classType)

        // The emitted snapshot expects MqttFixedHeader to be a class with a UByte
        // primary-ctor `raw`. A value class is the production shape; for a
        // round-trip test a regular data class with the same shape works
        // identically and avoids JVM inline-class reflection quirks. The wire
        // format is identical (one UByte).
        val userTypeSource =
            """
            package com.ditchoom.codec.test

            data class MqttFixedHeader(val raw: UByte)
            """.trimIndent()

        val (codec, _) =
            compileAndLoadCodec(
                codecSource = codecSource,
                userTypes = userTypeSource,
                codecFqn = "com.ditchoom.codec.test.MqttFixedHeaderCodec",
            )

        val cls = codec::class.java.classLoader.loadClass("com.ditchoom.codec.test.MqttFixedHeader")
        // Pick a non-default value with the high bit set so a sign-confused
        // implementation surfaces. UByte is an inline class — at JVM ABI level
        // the constructor parameter is `Byte`, so `getDeclaredConstructor(UByte::class.java)`
        // fails. Find the single primary constructor that takes a Byte.
        val ctor =
            cls.declaredConstructors.firstOrNull {
                it.parameterCount == 1 && it.parameterTypes[0] == Byte::class.javaPrimitiveType
            } ?: error(
                "no Byte-arg constructor on $cls; candidates: ${
                    cls.declaredConstructors.joinToString { it.toString() }
                }",
            )
        ctor.isAccessible = true
        val value = ctor.newInstance(0x9E.toByte())

        val buffer: PlatformBuffer = BufferFactory.Default.allocate(64)
        invokeEncode(codec, buffer, value)
        assertEquals(1, buffer.position(), "encode(MqttFixedHeader) should write exactly 1 byte")
        buffer.resetForRead()
        val decoded = invokeDecode(codec, buffer)

        assertEquals(value, decoded, "MqttFixedHeader value not preserved across round-trip")

        val ws = invokeWireSize(codec, value)
        assertEquals(1, ws, "wireSize(MqttFixedHeader) should be 1")
    }

    // ---------------------------------------------------------------------
    // Fixture 3 — load-bearing — Plan.Sealed_ with a VariantPlan.WithPayload arm.
    //
    // This fixture documents the gap that motivates Phase 9 Step 3.
    //
    // At HEAD `5a0a1bc`, [SealedEmitter] emits a `decode(buf, ctx)` override
    // that delegates to per-variant `decodeFromContext(...)` for `WithPayload`
    // variants. It does NOT emit the typed-lambda overload that the consumers
    // call:
    //
    //     fun <P> decode(buffer: ReadBuffer, payloadDecoder: SomeCtx.(ReadBuffer) -> P): Sealed
    //
    // Without that overload, `ControlPacketV4Codec.decode<WP, P>(buffer, ...)`
    // and similar consumer call sites can't be generated, and the 80
    // underscored-locals legacy fan-out keeps the legacy emitter alive.
    //
    // Today's assertion: the emitted source does NOT contain the typed-lambda
    // signature. When Step 3 implements the capability, an emitter subagent
    // will:
    //   1. Add typed-lambda emission to SealedEmitter (and the corresponding
    //      Leaf typed-lambda overload in LeafEmitter for the variant codecs).
    //   2. Flip this test: replace `assertNoTypedLambdaOverload(...)` with
    //      a real round-trip via the typed-lambda overload (allocate buffer,
    //      call decode<P>(buffer, lambda) reflectively, assert equality).
    //   3. Add a richer fixture body — the current synthetic Plan only has
    //      empty `payloadFields = emptyList()`, which is enough to expose
    //      the *signature* gap but not the *behavior* (since no payload
    //      slot is read on the wire). Step 3 should extend the fixture to
    //      emit a real PayloadSlot field and assert decode produces the
    //      lambda-supplied payload.
    //
    // The fixture reuses [EmitterFixtures.controlPacketV5Slice5a] which is
    // exactly the shape (BodyLength sealed root, one WithPayload + one
    // NoPayload arm) the typed-lambda overload needs to handle.
    // ---------------------------------------------------------------------

    @Test
    fun `Sealed_ WithPayload variant emits no typed-lambda overload at HEAD 5a0a1bc`() {
        val plan = EmitterFixtures.controlPacketV5Slice5a()
        val classType = EmitterFixtures.cn("ControlPacketV5Slice5a")
        val emitted = emitText(plan, classType)

        // Sanity: dispatcher emits the context-overload path (this is what
        // exists today).
        assertTrue(
            emitted.contains("decodeFromContext(_bodySlice, ctx)"),
            "expected context-overload decode delegation to be emitted; got:\n$emitted",
        )
        assertTrue(
            emitted.contains("encodeFromContext(buffer, value, context)"),
            "expected context-overload encode delegation to be emitted; got:\n$emitted",
        )

        // The gap: no typed-lambda fan-out emitted. Step 3 must flip this.
        //
        // The legacy shape we expect Step 3 to emit (from
        // SealedDispatchGenerator.kt buildPayloadDispatch, lines 666-1070) is:
        //
        //     public fun <P> decode(
        //         buffer: ReadBuffer,
        //         payloadDecoder: PublishContext.(ReadBuffer) -> P,
        //     ): ControlPacketV5Slice5a { ... }
        //
        // Match on the type-parameter declaration `<P>` on a `decode` overload
        // followed by a `payloadDecoder` lambda parameter. (Match against the
        // raw FileSpec text; KotlinPoet's emitter pretty-prints `fun <P> decode`.)
        val typedLambdaSignature = Regex("""\bfun\s*<\s*P\s*>\s*decode\s*\(""")
        assertNoMatch(
            typedLambdaSignature,
            emitted,
            "FAIL → Step 3 capability has landed. Flip this test: assert the typed-lambda " +
                "overload IS present and round-trip a value through it. See the comment " +
                "above this test for the migration steps.",
        )

        val payloadDecoderParam = Regex("""\bpayloadDecoder\s*:""")
        assertNoMatch(
            payloadDecoderParam,
            emitted,
            "FAIL → Step 3 capability has landed (payloadDecoder lambda param emitted). " +
                "Flip this test: round-trip via the typed-lambda overload.",
        )
    }

    // ---------------------------------------------------------------------
    // Compile + load helpers.
    // ---------------------------------------------------------------------

    /**
     * Compile [codecSource] (the output of [CodecEmitter]) together with the
     * supporting [userTypes] sources (the user-declared `@ProtocolMessage`
     * types) against the real `:buffer` and `:buffer-codec` JVM jars, then
     * reflectively load the codec object at [codecFqn].
     *
     * `inheritClassPath = true` exposes the host JVM classpath to the
     * embedded compiler, so generated code that references
     * `com.ditchoom.buffer.ReadBuffer`, `com.ditchoom.buffer.codec.Codec`,
     * etc. resolves against the real runtime types.
     */
    private fun compileAndLoadCodec(
        codecSource: String,
        userTypes: String,
        codecFqn: String,
    ): Pair<Any, KotlinCompilation> {
        val compilation =
            KotlinCompilation().apply {
                sources =
                    listOf(
                        SourceFile.kotlin("UserTypes.kt", userTypes),
                        SourceFile.kotlin("EmittedCodec.kt", codecSource),
                    )
                inheritClassPath = true
                messageOutputStream = System.out
                kotlincArguments = listOf("-Xskip-metadata-version-check")
            }
        val result = compilation.compile()
        if (result.exitCode != KotlinCompilation.ExitCode.OK) {
            fail(
                "in-process compile of emitted codec failed (exitCode=${result.exitCode}).\n\n" +
                    "--- emitted codec source ---\n$codecSource\n" +
                    "--- user types ---\n$userTypes\n" +
                    "--- compiler messages ---\n${result.messages}\n",
            )
        }
        val cl = result.classLoader
        val cls = cl.loadClass(codecFqn)
        val instance =
            try {
                // Kotlin `object` exposes a public `INSTANCE` field.
                cls.getField("INSTANCE").get(null)
            } catch (t: NoSuchFieldException) {
                fail("loaded class $codecFqn but it is not a Kotlin object (no INSTANCE field): $t")
            }
        return instance to compilation
    }

    /**
     * Invoke the codec's `encode(buffer, value, context)` reflectively. The
     * codec's class is generated, so we can't statically reference its
     * methods.
     */
    private fun invokeEncode(
        codec: Any,
        buffer: Any,
        value: Any,
    ) {
        val m =
            codec::class.java.methods.firstOrNull {
                it.name == "encode" && it.parameterCount == 3
            } ?: error("no encode(buffer, value, context) on ${codec::class.java.name}")
        m.invoke(codec, buffer, value, EncodeContext.Empty)
    }

    private fun invokeDecode(
        codec: Any,
        buffer: Any,
    ): Any? {
        val m =
            codec::class.java.methods.firstOrNull {
                it.name == "decode" && it.parameterCount == 2
            } ?: error("no decode(buffer, context) on ${codec::class.java.name}")
        return m.invoke(codec, buffer, DecodeContext.Empty)
    }

    private fun invokeWireSize(
        codec: Any,
        value: Any,
    ): Int {
        val m =
            codec::class.java.methods.firstOrNull {
                it.name == "wireSize" && it.parameterCount == 2
            } ?: error("no wireSize(value, context) on ${codec::class.java.name}")
        return m.invoke(codec, value, EncodeContext.Empty) as Int
    }

    private fun assertNoMatch(
        pattern: Regex,
        text: String,
        explanation: String,
    ) {
        val match = pattern.find(text)
        if (match != null) {
            fail(
                "$explanation\nMatched ${pattern.pattern} at:\n  ${
                    text.substring(
                        (match.range.first - 40).coerceAtLeast(0),
                        (match.range.last + 80).coerceAtMost(text.length),
                    )
                }",
            )
        }
    }
}
