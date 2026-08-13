package com.ditchoom.buffer.codec.processor

import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Guards on companion-side `peekFrameSize` placement (#348), exercised against
 * hand-built KotlinPoet specs so they pin the rule without paying a KSP compile.
 *
 * Hoisting a peek body onto a companion is sound only because every receiver the
 * peek walkers emit is a type name and every other operand is a `__`-prefixed
 * local — nothing a companion cannot see. That is a property of the walkers, not
 * of any type, so [addCodecCompanion] re-derives it. These tests are what keep
 * the check honest: without them the guard is itself unverified.
 */
class CompanionPeekPlacementTest {
    // ---- the hoisted body may not name an instance property ---------------

    @Test
    fun `hoisting a peek that names an instance property is a processor error`() {
        val failure =
            assertFailsWith<IllegalStateException> {
                genericCodecBuilder()
                    .addCodecCompanion(
                        CodecCompanion.FramingOnly(
                            peekFun("return payloadCodec.peekFrameSize(stream, baseOffset)"),
                        ),
                    )
            }
        val message = failure.message.orEmpty()
        assertTrue(
            "payloadCodec" in message,
            "the error must name the offending property, was: $message",
        )
        assertTrue(
            "PeekEmit.Unframed" in message,
            "the error must name the alternative classification, was: $message",
        )
    }

    /**
     * The dispatcher case: per-variant codec fields are instance properties too,
     * and they are added to the builder in a loop rather than declared once — so
     * the check reads names off the builder instead of taking a list.
     */
    @Test
    fun `hoisting a peek that names a per-variant codec field is a processor error`() {
        val builder =
            genericCodecBuilder()
                .addProperty(
                    PropertySpec
                        .builder("publishCodec", INT, KModifier.PRIVATE)
                        .initializer("0")
                        .build(),
                )
        val failure =
            assertFailsWith<IllegalStateException> {
                builder.addCodecCompanion(
                    CodecCompanion.PartialAndFraming(
                        partial = partialFun(),
                        peek = peekFun("return publishCodec.peekFrameSize(stream, baseOffset)"),
                    ),
                )
            }
        assertTrue("publishCodec" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `a peek built from type-name receivers and __ locals hoists`() {
        val builder =
            genericCodecBuilder()
                .addCodecCompanion(
                    CodecCompanion.FramingOnly(
                        peekFun(
                            """
                            |val __headerFrame = VarIntCodec.peekFrameSize(stream, baseOffset)
                            |val __total = __headerFrame.bytes + 2
                            |return PeekResult.Complete(__total)
                            """.trimMargin(),
                        ),
                    ),
                )
        val companion = builder.build().typeSpecs.single()
        assertTrue(companion.isCompanion)
        assertEquals(listOf("peekFrameSize"), companion.funSpecs.map { it.name })
    }

    /**
     * The check is a word-boundary match, not a substring one. Peek walkers name
     * their locals `__payloadCodecWidth`-style; flagging those would fail builds
     * that are correct.
     */
    @Test
    fun `a local whose name merely contains a property name does not trip the check`() {
        genericCodecBuilder()
            .addCodecCompanion(
                CodecCompanion.FramingOnly(
                    peekFun(
                        """
                        |val __payloadCodecWidth = 4
                        |return PeekResult.Complete(__payloadCodecWidth)
                        """.trimMargin(),
                    ),
                ),
            )
    }

    /**
     * An `Unframed` peek is never hoisted, so its body is unconstrained — the
     * check must key off placement, not off the mere presence of a peek.
     */
    @Test
    fun `a member-only companion is not checked`() {
        genericCodecBuilder()
            .addCodecCompanion(CodecCompanion.PartialOnly(partialFun()))
    }

    // ---- the forwarder's `Companion.` receiver ----------------------------

    /**
     * [memberPeekFun] forwards to `Companion.peekFrameSize(...)` — the implicit
     * name of an *unnamed* companion. `AggregatorAndFraming` is the one arm that
     * reuses a companion built elsewhere, so it is the one place a name could
     * arrive and leave the forwarder calling a receiver that does not exist.
     */
    @Test
    fun `framing cannot be merged into a named aggregator companion`() {
        val named =
            TypeSpec
                .companionObjectBuilder("Aggregator")
                .addFunction(FunSpec.builder("decodeAggregating").build())
                .build()
        val failure =
            assertFailsWith<IllegalArgumentException> {
                genericCodecBuilder()
                    .addCodecCompanion(
                        CodecCompanion.AggregatorAndFraming(
                            aggregator = named,
                            peek = peekFun("return PeekResult.NoFraming"),
                        ),
                    )
            }
        assertTrue("Companion" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `an unnamed aggregator companion takes the framing merge`() {
        val builder =
            genericCodecBuilder()
                .addCodecCompanion(
                    CodecCompanion.AggregatorAndFraming(
                        aggregator =
                            TypeSpec
                                .companionObjectBuilder()
                                .addFunction(FunSpec.builder("decodeAggregating").build())
                                .build(),
                        peek = peekFun("return PeekResult.Complete(4)"),
                    ),
                )
        val companion = builder.build().typeSpecs.single()
        assertEquals(
            listOf("decodeAggregating", "peekFrameSize"),
            companion.funSpecs.map { it.name }.sorted(),
        )
    }

    /**
     * The forwarder names `Companion` explicitly. An unqualified call would
     * resolve back to the member itself and recurse forever in generated code.
     */
    @Test
    fun `the member forwarder qualifies its call with Companion`() {
        val forwarder = memberPeekFun(PeekEmit.Framed(peekFun("return PeekResult.Complete(4)")))
        assertTrue(
            "Companion.peekFrameSize(stream, baseOffset)" in forwarder.body.toString(),
            forwarder.body.toString(),
        )
    }

    // ---- fixtures ---------------------------------------------------------

    /** A generic codec class shell: the injected `payloadCodec` and nothing else. */
    private fun genericCodecBuilder(): TypeSpec.Builder =
        TypeSpec
            .classBuilder("FakeCodec")
            .addProperty(
                PropertySpec
                    .builder("payloadCodec", INT, KModifier.PRIVATE)
                    .initializer("0")
                    .build(),
            )

    private fun peekFun(body: String): FunSpec =
        FunSpec
            .builder("peekFrameSize")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("stream", STREAM_PROCESSOR_CN)
            .addParameter("baseOffset", INT)
            .returns(PEEK_RESULT_CN)
            .addStatement("%L", body)
            .build()

    private fun partialFun(): FunSpec = FunSpec.builder("partial").build()
}
