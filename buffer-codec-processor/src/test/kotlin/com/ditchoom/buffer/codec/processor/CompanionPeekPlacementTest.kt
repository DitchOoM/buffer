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
 * hand-built KotlinPoet specs so they pin the rules without paying a KSP compile.
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
                        peek = peekFun("return payloadCodec.peekFrameSize(stream, baseOffset)"),
                    )
            }
        val message = failure.message.orEmpty()
        assertTrue(
            "payloadCodec" in message,
            "the error must name the offending property, was: $message",
        )
        assertTrue(
            "keep this shape's peek a member" in message,
            "the error must name the way out, was: $message",
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
                    peek = peekFun("return publishCodec.peekFrameSize(stream, baseOffset)"),
                    partial = partialFun(),
                )
            }
        assertTrue("publishCodec" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `a peek built from type-name receivers and __ locals hoists`() {
        val builder =
            genericCodecBuilder()
                .addCodecCompanion(
                    peek =
                        peekFun(
                            """
                            |val __headerFrame = VarIntCodec.peekFrameSize(stream, baseOffset)
                            |val __total = __headerFrame.bytes + 2
                            |return PeekResult.Complete(__total)
                            """.trimMargin(),
                        ),
                )
        val companion = builder.build().typeSpecs.single()
        assertTrue(companion.isCompanion)
        assertEquals(listOf("peekFrameSize"), companion.funSpecs.map { it.name })
    }

    /**
     * A constant-`NoFraming` body hoists like any other. Placement follows what
     * the body *can* reach, not whether the shape frames — the same rule an
     * `object` codec has always followed, where `FooCodec.peekFrameSize(...)`
     * resolves regardless and `NoFraming` is a runtime answer.
     */
    @Test
    fun `an unframable shape hoists its constant body too`() {
        val builder =
            genericCodecBuilder()
                .addCodecCompanion(peek = peekFun("return PeekResult.NoFraming"))
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
                peek =
                    peekFun(
                        """
                        |val __payloadCodecWidth = 4
                        |return PeekResult.Complete(__payloadCodecWidth)
                        """.trimMargin(),
                    ),
            )
    }

    /**
     * The unframed generic dispatcher's shape: its peek stays a member, so no
     * `peek` is passed and nothing is checked. The guard must key off what is
     * being hoisted, not off the codec having a peek at all.
     */
    @Test
    fun `a companion with no hoisted peek is not checked`() {
        val builder = genericCodecBuilder().addCodecCompanion(partial = partialFun())
        val companion = builder.build().typeSpecs.single()
        assertEquals(listOf("partial"), companion.funSpecs.map { it.name })
        assertTrue(companion.superinterfaces.isEmpty(), "no framing ⇒ no FrameDetector")
    }

    @Test
    fun `no entry points at all adds no companion`() {
        val builder = genericCodecBuilder().addCodecCompanion()
        assertTrue(builder.build().typeSpecs.isEmpty())
    }

    // ---- the forwarder's `Companion.` receiver ----------------------------

    /**
     * [memberPeekFun] forwards to `Companion.peekFrameSize(...)` — the implicit
     * name of an *unnamed* companion. An aggregator is the one companion built
     * elsewhere, so it is the one that could arrive named and leave the
     * forwarder calling a receiver that does not exist.
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
                        peek = peekFun("return PeekResult.Complete(4)"),
                        aggregator = named,
                    )
            }
        assertTrue("Companion" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `an unnamed aggregator companion takes the framing merge`() {
        val builder =
            genericCodecBuilder()
                .addCodecCompanion(
                    peek = peekFun("return PeekResult.Complete(4)"),
                    aggregator =
                        TypeSpec
                            .companionObjectBuilder()
                            .addFunction(FunSpec.builder("decodeAggregating").build())
                            .build(),
                )
        val companion = builder.build().typeSpecs.single()
        assertEquals(
            listOf("decodeAggregating", "peekFrameSize"),
            companion.funSpecs.map { it.name }.sorted(),
        )
        assertEquals(1, companion.superinterfaces.size, "the companion is a FrameDetector value")
    }

    /**
     * The forwarder names `Companion` explicitly. An unqualified call would
     * resolve back to the member itself and recurse forever in generated code.
     */
    @Test
    fun `the member forwarder qualifies its call with Companion`() {
        val forwarder = memberPeekFun(peekFun("return PeekResult.Complete(4)"))
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
