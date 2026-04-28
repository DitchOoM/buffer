package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.processor.ir.Direction
import com.ditchoom.buffer.codec.processor.ir.Endianness
import com.ditchoom.buffer.codec.processor.ir.FieldPlan
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.ditchoom.buffer.codec.processor.ir.LengthEncoding
import com.ditchoom.buffer.codec.processor.ir.LengthSource
import com.ditchoom.buffer.codec.processor.ir.PayloadFieldRef
import com.ditchoom.buffer.codec.processor.ir.PayloadTypeParam
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.PrimitiveKind
import com.ditchoom.buffer.codec.processor.ir.ProviderId
import com.ditchoom.buffer.codec.processor.ir.SpiDescriptor
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.ir.VariantPlan
import com.ditchoom.buffer.codec.processor.ir.WireMatch
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
    // Phase 9 Step 3 Phase B — capability harness fixtures (1-7).
    //
    // Each capability fixture below was added by Step 3 Phase B so the
    // capability gap is documented as a failing-or-current-broken assertion
    // in the harness. As Phase C ports each capability, the corresponding
    // fixture flips from "assert current broken" to "assert round-trip green".
    //
    // Fixture index:
    //   1. Sealed_ WithPayload typed-lambda dispatcher        — covered by
    //      `Sealed_ WithPayload variant emits no typed-lambda overload at HEAD 5a0a1bc`
    //      above. (Pre-existing, Step 1 fixture.)
    //   2. Top-level @Payload data class fan-out              — Cap 2 below.
    //   3. @LengthPrefixed on NestedMessage / External        — Cap 3 below.
    //   4. @WireBytes non-natural width                       — Cap 4 below.
    //   5. ConditionalValidator rules in PhaseB               — Cap 5 below.
    //   6. @DispatchOn on non-sealed diagnostic               — Cap 6 below.
    //   7. CodecFieldProvider SPI threading                   — Cap 7 below.
    // ---------------------------------------------------------------------

    // ---- Capability 2 — Top-level @Payload data class fan-out -----------
    //
    // The legacy `CodecGenerator` emitted two pairs of decode/encode
    // overloads for a `data class Foo<@Payload P>(...)` shape:
    //   1. `<P> fun decode(buffer, payloadDecoder: (ReadBuffer) -> P): Foo<P>`
    //      — typed-lambda overload (no context).
    //   2. `fun decode(buffer, context): Foo<*>` reading the lambda from a Key.
    //
    // Today, the new pipeline's [LeafEmitter] emits only #2 (the
    // context-overload path). Round-tripping any payload typed as a generic
    // type parameter `P` therefore can't go through the lambda
    // overload. This fixture documents the gap.
    //
    // Test fixture: `Plan.Leaf` with one fixed prefix (UByte) + one
    // PayloadSlot field whose length is FromField. Asserts the typed-lambda
    // signature is NOT in the emitted source.
    @Test
    fun `cap2 Leaf with Payload type parameter emits no typed-lambda overload at HEAD`() {
        val plan = grpcFrameLikePayloadFixture()
        val classType = EmitterFixtures.cn("PayloadOnlyLeaf")
        val emitted = emitText(plan, classType)

        // Sanity: context-overload path is emitted.
        assertTrue(
            emitted.contains("fun decode("),
            "expected at least one decode( overload to be emitted; got:\n$emitted",
        )

        // The gap: no typed-lambda fan-out emitted.
        // Legacy shape we expect Phase C Cap 2 to emit:
        //
        //     public fun <P> decode(
        //         buffer: ReadBuffer,
        //         payloadDecoder: (ReadBuffer) -> P,
        //     ): PayloadOnlyLeaf<P> { ... }
        val typedLambdaSignature = Regex("""\bfun\s*<\s*P\s*>\s*decode\s*\(""")
        assertNoMatch(
            typedLambdaSignature,
            emitted,
            "FAIL → Phase C Cap 2 has landed (Plan.Leaf typed-lambda overload emitted). " +
                "Flip this test: round-trip a value through the typed-lambda overload.",
        )
        val payloadDecoderParam = Regex("""\bpayloadDecoder\s*:""")
        assertNoMatch(
            payloadDecoderParam,
            emitted,
            "FAIL → Phase C Cap 2 has landed (payloadDecoder lambda param emitted). " +
                "Flip this test: round-trip via the typed-lambda overload.",
        )
    }

    // ---- Capability 3 — @LengthPrefixed on NestedMessage / External -----
    //
    // Legacy `FieldCodeEmitter` for a nested-message field annotated
    // `@LengthPrefixed(VarInt)` would:
    //   * Decode: read length prefix → `readBytes(len)` slice → `Codec.decode(slice, ctx)`.
    //   * Encode: two-pass (`wireSize → writePrefix → writeBody`).
    //
    // The new pipeline IR ([FieldStrategy.NestedMessage] + [FieldStrategy.External])
    // does NOT carry a [LengthSource]; it's structurally impossible for the
    // emitter to know to slice. The decode site emits a bare
    // `Codec.decode(buffer, context)` call with no length prefix read.
    //
    // Test fixture: `Plan.Leaf` with a NestedMessage field. Asserts the emit
    // does NOT contain the `readBytes(...).` slice pattern that legacy used
    // for length-prefixed nested-message decode.
    @Test
    fun `cap3 NestedMessage emits no length-prefix slice at HEAD`() {
        val plan = nestedMessageLeafFixture()
        val classType = EmitterFixtures.cn("NestedMessageLeaf")
        val emitted = emitText(plan, classType)

        // The bare nested-codec decode call exists today.
        assertTrue(
            emitted.contains("InnerMessageCodec.decode(buffer, context)"),
            "expected bare nested-codec decode at HEAD; got:\n$emitted",
        )

        // The gap: no length-prefix slice emitted around the nested decode.
        // Legacy shape we expect Phase C Cap 3 to emit:
        //
        //     val _innerLen = buffer.readVariableByteInteger()
        //     val _innerSlice = buffer.readBytes(_innerLen)
        //     val inner = InnerMessageCodec.decode(_innerSlice, context)
        val sliceFollowedByNested = Regex("""readBytes\s*\([^)]+\)[^\n]*\n[^\n]*InnerMessageCodec\.decode""")
        assertNoMatch(
            sliceFollowedByNested,
            emitted,
            "FAIL → Phase C Cap 3 has landed (length-prefix slice emitted around nested decode). " +
                "Flip this test: round-trip a value with a length-prefixed nested message and " +
                "assert wire bytes match the legacy two-pass shape.",
        )
    }

    // ---- Capability 4 — @WireBytes non-natural width --------------------
    //
    // Legacy `CustomWidthEmitter` emitted shift-and-mask sequences for
    // `@WireBytes(3)` / 5 / 6 / 7 — values that don't map to a single
    // `readByte`/`readShort`/`readInt`/`readLong`.
    //
    // Today the emitter uses [FieldOps.readExpr] / [FieldOps.writeExpr]
    // which always read the natural width for the [PrimitiveKind]; the
    // [FieldStrategy.Primitive.wireBytes] field is parsed but ignored at
    // emit. So a `Plan.Leaf` with `Primitive(Int, wireBytes=3)` produces
    // `buffer.readInt()` (4 bytes) instead of a 3-byte read.
    //
    // Test fixture: `Plan.Leaf` with a single `Primitive(Int, wireBytes=3)`
    // field. Asserts the emitted decode reads `readInt()` (the bug) — Phase
    // C Cap 4 will replace this with a 3-byte shift-and-mask sequence.
    @Test
    fun `cap4 Primitive wireBytes 3 emits natural-width read at HEAD`() {
        val plan = wireBytesThreeFixture()
        val classType = EmitterFixtures.cn("ThreeByteInt")
        val emitted = emitText(plan, classType)

        // Today: full Int read (4 bytes), ignoring wireBytes=3.
        assertTrue(
            emitted.contains("buffer.readInt()"),
            "expected natural-width Int read at HEAD; got:\n$emitted",
        )

        // The gap: no 3-byte shift-and-mask read pattern.
        // Legacy shape we expect Phase C Cap 4 to emit (BE):
        //
        //     val b0 = buffer.readUnsignedByte().toInt()
        //     val b1 = buffer.readUnsignedByte().toInt()
        //     val b2 = buffer.readUnsignedByte().toInt()
        //     val value = (b0 shl 16) or (b1 shl 8) or b2
        val shiftAndMaskPattern = Regex("""shl\s+16\)\s+or\s+\(""")
        assertNoMatch(
            shiftAndMaskPattern,
            emitted,
            "FAIL → Phase C Cap 4 has landed (shift-and-mask 3-byte read emitted). " +
                "Flip this test: round-trip a value of 0x123456 through the codec and " +
                "assert wire size is 3.",
        )
    }

    // ---- Capability 5 — ConditionalValidator rules in PhaseB ------------
    //
    // Legacy `ConditionalValidator` enforced four rules at PhaseA:
    //   * Conditional field must be nullable.
    //   * Conditional field must have a default value.
    //   * @WhenRemaining minBytes must be > 0.
    //   * @WhenRemaining fields must be contiguous and at the constructor tail.
    //
    // The new pipeline's [FieldStrategyBuilder.buildConditionality] only
    // checks `minBytes >= 0` (legacy: `> 0`) and emits no diagnostic for
    // the other three rules. The Phase A test cleanup deleted the negative-
    // case KSP tests (lines 199-339 of WhenRemainingTest.kt) and the
    // legacy-shape unit tests (lines 343-554) since they referenced the
    // now-deleted ConditionalValidator. Phase D Cap 5 ports the rules into
    // PhaseB so those negative cases re-surface.
    //
    // The harness operates on `Plan` IR directly, bypassing KSP/PhaseB —
    // so this capability fixture is necessarily a `compileWithKsp`-shaped
    // diagnostic fixture, not a round-trip. It lives outside the
    // `CompileAndRoundTripTest` class per architectural separation; the
    // test file `WhenRemainingTest.kt` will gain the corresponding
    // negative-case tests when Phase D Cap 5 lands. This fixture asserts
    // the CURRENT (broken) behavior: the new pipeline accepts these
    // malformed shapes.
    //
    // Currently a no-op placeholder — see WhenRemainingTest.kt diff in
    // Phase A's commit for the negative cases that were stripped, and
    // Phase D's commit for the same cases re-introduced.
    @Test
    fun `cap5 ConditionalValidator rules placeholder — see WhenRemainingTest`() {
        // No-op: the diagnostic capability lives outside this Plan-IR-driven
        // harness. Phase D Cap 5 either ports the rules to PhaseB (and adds
        // negative-case KSP tests to WhenRemainingTest.kt) or defers with
        // explicit reasoning. This placeholder exists to document the
        // capability slot in this file.
    }

    // ---- Capability 6 — @DispatchOn on non-sealed diagnostic ------------
    //
    // Legacy `FieldAnalyzer` rejected `@DispatchOn` annotations on objects
    // and non-sealed data classes with a clear "@DispatchOn is not valid
    // on an object" diagnostic. The new pipeline's [PlanBuilder] does not
    // currently emit this diagnostic.
    //
    // Like Cap 5, this is diagnostic-only. The `compileWithKsp`-shaped
    // negative case lives in `DataObjectCodegenTest.kt`'s
    // `object with DispatchOn annotation rejected` test (currently failing
    // — surfaced by Phase A's commit). Phase D Cap 6 ports the diagnostic
    // and that test re-passes.
    //
    // Currently a no-op placeholder.
    @Test
    fun `cap6 DispatchOn on non-sealed placeholder — see DataObjectCodegenTest`() {
        // No-op — see comment above.
    }

    // ---- Capability 7 — CodecFieldProvider SPI threading ---------------
    //
    // The Spi field strategy already exists in the new IR
    // ([FieldStrategy.Spi]) and the [LeafEmitter] substitutes the
    // descriptor's `decodeRaw` / `encodeRaw` text directly. What's missing
    // (per the blocker file) is "threading custom-provider FQN through
    // Discovery → PlanBuilder → FieldStrategyBuilder" — i.e. surfacing the
    // user-registered `CodecFieldProvider` class FQN in the emitted file
    // so the consumer's KSP run picks up provider-registered descriptors
    // without an explicit @UseCodec annotation.
    //
    // The blocker explicitly notes this is "defensive-only" — neither MQTT
    // nor websocket consumers exercise it. Phase D Cap 7 either ports it
    // (if the work is small) or defers with explicit reasoning.
    //
    // This fixture round-trips an existing SPI-strategy fixture
    // (asymmetricSpiLeaf) through the harness as proof that today's SPI
    // descriptor substitution path works. The "threading" gap is structural
    // (descriptor provided directly in the IR rather than discovered from
    // a registered provider class), so a round-trip can't expose it
    // without rebuilding the Discovery → PlanBuilder pipeline. The test
    // documents this and notes the deferral candidate.
    @Test
    fun `cap7 SPI descriptor substitution works at HEAD — provider FQN threading deferred`() {
        val plan = EmitterFixtures.asymmetricSpiLeaf()
        val classType = EmitterFixtures.cn("AsymmetricSpiLeaf")
        val emitted = emitText(plan, classType)

        // Today: descriptor's decodeRaw / encodeRaw text is substituted
        // directly into the emit. This is what consumers rely on.
        assertTrue(
            emitted.contains("buffer.readCidr()"),
            "expected SPI decodeRaw to be substituted; got:\n$emitted",
        )
        assertTrue(
            emitted.contains("buffer.writeCidr(value.cidr)"),
            "expected SPI encodeRaw to be substituted; got:\n$emitted",
        )

        // Provider FQN threading is the deferred capability. There is no
        // assertion to flip here because today's IR carries the descriptor
        // directly — so the fixture documents that descriptor substitution
        // works and Phase D Cap 7 may either:
        //   1. Port the Discovery-time provider lookup → IR descriptor
        //      threading and add a separate fixture asserting that path; or
        //   2. Defer with a rationale (no consumer uses it).
    }

    // ---------------------------------------------------------------------
    // Phase B fixture builders — all factory `private fun` for the harness.
    // ---------------------------------------------------------------------

    /**
     * Cap 2 fixture — `Plan.Leaf` with a `@Payload P` type parameter and a
     * single PayloadSlot field whose length is `Remaining` (the simplest
     * form). The variant codec must emit a typed-lambda decode overload to
     * thread the payload-decoder lambda; today only the context-overload
     * exists.
     */
    private fun grpcFrameLikePayloadFixture(): Plan.Leaf =
        Plan.Leaf(
            decl = EmitterFixtures.fqn("PayloadOnlyLeaf"),
            fields =
                listOf(
                    FieldPlan(
                        name = "kind",
                        type = TypeFqn("kotlin.UByte"),
                        strategy = FieldStrategy.Primitive(PrimitiveKind.UByte, 1, Endianness.Big),
                    ),
                    FieldPlan(
                        name = "payload",
                        type = TypeFqn("kotlin.P"),
                        strategy =
                            FieldStrategy.PayloadSlot(
                                typeParam = "P",
                                length = LengthSource.Remaining(trailingBytes = 0),
                            ),
                    ),
                ),
            batches = emptyList(),
            dir = Direction.Bidirectional,
        )

    /**
     * Cap 3 fixture — `Plan.Leaf` with a NestedMessage field that today is
     * decoded via a bare `Codec.decode(buffer, ctx)` call. Phase C Cap 3
     * threads `LengthSource` through and slices the buffer first.
     */
    private fun nestedMessageLeafFixture(): Plan.Leaf =
        Plan.Leaf(
            decl = EmitterFixtures.fqn("NestedMessageLeaf"),
            fields =
                listOf(
                    FieldPlan(
                        name = "header",
                        type = TypeFqn("kotlin.UByte"),
                        strategy = FieldStrategy.Primitive(PrimitiveKind.UByte, 1, Endianness.Big),
                    ),
                    FieldPlan(
                        name = "inner",
                        type = EmitterFixtures.fqn("InnerMessage"),
                        strategy =
                            FieldStrategy.NestedMessage(
                                codec = ClassName("com.ditchoom.codec.test", "InnerMessageCodec"),
                            ),
                    ),
                ),
            batches = emptyList(),
            dir = Direction.Bidirectional,
        )

    /**
     * Cap 4 fixture — `Plan.Leaf` with a single `Primitive(Int, wireBytes=3)`.
     * The IR carries `wireBytes=3` but the emitter ignores it.
     */
    private fun wireBytesThreeFixture(): Plan.Leaf =
        Plan.Leaf(
            decl = EmitterFixtures.fqn("ThreeByteInt"),
            fields =
                listOf(
                    FieldPlan(
                        name = "value",
                        type = TypeFqn("kotlin.Int"),
                        strategy = FieldStrategy.Primitive(PrimitiveKind.Int, wireBytes = 3, order = Endianness.Big),
                    ),
                ),
            batches = emptyList(),
            dir = Direction.Bidirectional,
        )

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
