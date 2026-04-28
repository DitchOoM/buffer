package com.ditchoom.buffer.codec.processor.emitter

import com.ditchoom.buffer.codec.processor.ir.Direction
import com.ditchoom.buffer.codec.processor.ir.DiscriminatorShape
import com.ditchoom.buffer.codec.processor.ir.DispatchShape
import com.ditchoom.buffer.codec.processor.ir.FieldPlan
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.ditchoom.buffer.codec.processor.ir.FramingMode
import com.ditchoom.buffer.codec.processor.ir.PayloadTypeParam
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.PrimitiveKind
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.ir.VariantPlan
import com.ditchoom.buffer.codec.processor.ir.WireMatch
import com.squareup.kotlinpoet.ClassName
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 7 hard-bar test: 12 framing × payload × wire-match matrix cells.
 *
 * Three framings (`Unframed`, `PeekOnly`, `BodyLength`) × two payload variant
 * shapes (`NoPayload` / `WithPayload`) × two wire-match shapes (`Point` /
 * `Range`) = 12 cells. Each is a structural assertion: the emitter produces a
 * sensible TypeSpec for that cell.
 *
 * Per the rearchitecture plan (Phase 7 hard-bar): "structural tests over the
 * emitted FileSpec". Round-trip end-to-end testing is deferred to Phase 7B/8
 * when the new emitter is wired into KSP and the generated codecs compile +
 * link against the protocol fixtures.
 */
class MatrixEmitterTest {
    private val pkg = "com.ditchoom.codec.matrix"

    private fun cn(simple: String) = ClassName(pkg, simple)

    private fun fqn(simple: String) = TypeFqn("$pkg.$simple")

    private fun registry(): TypeRegistry {
        val ext = mutableMapOf<TypeFqn, ClassName>()
        listOf(
            "Root",
            "Root.Alpha",
            "Root.Beta",
            "Root.Gamma",
            "Root.Delta",
            "DiscByte",
            "Framer",
        ).forEach { ext[fqn(it)] = cn(it) }
        ext[TypeFqn("kotlin.IllegalArgumentException")] = ClassName("kotlin", "IllegalArgumentException")
        return TypeRegistry(ext)
    }

    private fun emitText(plan: Plan): String = CodecEmitter(registry()).emit(plan, cn("Root")).toString()

    // ---------------------------------------------------------------------
    // Variant builder helpers
    // ---------------------------------------------------------------------

    private fun noPayloadPoint(
        simple: String,
        wire: Int,
    ) = VariantPlan.NoPayload(
        decl = fqn("Root.$simple"),
        codec = ClassName(pkg, "Root$simple" + "Codec"),
        wire = WireMatch.Point(fqn("Root.$simple"), wire),
        selfEncodes = false,
        dir = Direction.Bidirectional,
        fields = emptyList(),
    )

    private fun noPayloadRange(
        simple: String,
        from: Int,
        to: Int,
    ) = VariantPlan.NoPayload(
        decl = fqn("Root.$simple"),
        codec = ClassName(pkg, "Root$simple" + "Codec"),
        wire = WireMatch.Range(fqn("Root.$simple"), from, to),
        selfEncodes = true,
        dir = Direction.Bidirectional,
        fields = emptyList(),
    )

    private fun withPayloadPoint(
        simple: String,
        wire: Int,
    ) = VariantPlan.WithPayload(
        decl = fqn("Root.$simple"),
        codec = ClassName(pkg, "Root$simple" + "Codec"),
        wire = WireMatch.Point(fqn("Root.$simple"), wire),
        selfEncodes = false,
        dir = Direction.Bidirectional,
        fields = emptyList(),
        typeParams = listOf(PayloadTypeParam("P", null)),
        payloadFields = emptyList(),
    )

    private fun withPayloadRange(
        simple: String,
        from: Int,
        to: Int,
    ) = VariantPlan.WithPayload(
        decl = fqn("Root.$simple"),
        codec = ClassName(pkg, "Root$simple" + "Codec"),
        wire = WireMatch.Range(fqn("Root.$simple"), from, to),
        selfEncodes = true,
        dir = Direction.Bidirectional,
        fields = emptyList(),
        typeParams = listOf(PayloadTypeParam("P", null)),
        payloadFields = emptyList(),
    )

    private fun makeSealed(
        framing: FramingMode,
        variants: List<VariantPlan>,
    ): Plan.Sealed_ =
        Plan.Sealed_(
            decl = fqn("Root"),
            variants = variants,
            dispatch =
                DispatchShape.TypedDiscriminator(
                    disc =
                        DiscriminatorShape.ValueClass(
                            discriminatorType = fqn("DiscByte"),
                            inner = PrimitiveKind.UByte,
                            innerProp = "raw",
                            codec = ClassName(pkg, "DiscByteCodec"),
                            dispatchProp = "value",
                            wireRange = 0..255,
                        ),
                    framing = framing,
                ),
            dir = Direction.Bidirectional,
            onUnknown = TypeFqn("kotlin.IllegalArgumentException"),
        )

    private val framerCn = ClassName(pkg, "Framer")

    // ---------------------------------------------------------------------
    // Matrix cells: 3 framings × 2 payload variants × 2 wire-match shapes
    // ---------------------------------------------------------------------

    private fun assertSensibleEmit(
        plan: Plan.Sealed_,
        @Suppress("UNUSED_PARAMETER") cell: String,
    ) {
        val s = emitText(plan)
        assertTrue(s.contains("public object RootCodec : Codec<Root>"))
        // Decode + encode + wireSize all present.
        assertTrue(s.contains("override fun decode("))
        assertTrue(s.contains("override fun encode("))
        assertTrue(s.contains("override fun wireSize("))
        // No banned ktlint blanket.
        assertTrue(!s.contains("@file:Suppress(\"ktlint\")"))
    }

    @Test
    fun `cell Unframed × NoPayload × Point`() {
        val plan = makeSealed(FramingMode.Unframed, listOf(noPayloadPoint("Alpha", 1), noPayloadPoint("Beta", 2)))
        assertSensibleEmit(plan, "U·N·P")
    }

    @Test
    fun `cell Unframed × NoPayload × Range`() {
        val plan =
            makeSealed(
                FramingMode.Unframed,
                listOf(noPayloadRange("Alpha", 0, 127), noPayloadPoint("Beta", 192)),
            )
        assertSensibleEmit(plan, "U·N·R")
        val s = emitText(plan)
        assertTrue(s.contains("rawByte in 0..127"))
    }

    @Test
    fun `cell Unframed × WithPayload × Point`() {
        val plan =
            makeSealed(FramingMode.Unframed, listOf(withPayloadPoint("Alpha", 1), noPayloadPoint("Beta", 2)))
        assertSensibleEmit(plan, "U·W·P")
        val s = emitText(plan)
        assertTrue(s.contains("RootAlphaCodec.decodeFromContext"))
    }

    @Test
    fun `cell Unframed × WithPayload × Range`() {
        val plan =
            makeSealed(
                FramingMode.Unframed,
                listOf(withPayloadRange("Alpha", 48, 63), noPayloadPoint("Beta", 4)),
            )
        assertSensibleEmit(plan, "U·W·R")
        val s = emitText(plan)
        assertTrue(s.contains("rawByte in 48..63"))
        assertTrue(s.contains("RootAlphaCodec.decodeFromContext"))
    }

    @Test
    fun `cell PeekOnly × NoPayload × Point`() {
        val plan =
            makeSealed(
                FramingMode.PeekOnly(framerFqn = framerCn),
                listOf(noPayloadPoint("Alpha", 1), noPayloadPoint("Beta", 2)),
            )
        assertSensibleEmit(plan, "P·N·P")
        val s = emitText(plan)
        assertTrue(s.contains("Framer.peekFrameSize(stream, baseOffset)"))
    }

    @Test
    fun `cell PeekOnly × NoPayload × Range`() {
        val plan =
            makeSealed(
                FramingMode.PeekOnly(framerFqn = framerCn),
                listOf(noPayloadRange("Alpha", 0, 127), noPayloadPoint("Beta", 192)),
            )
        assertSensibleEmit(plan, "P·N·R")
        val s = emitText(plan)
        assertTrue(s.contains("Framer.peekFrameSize"))
        assertTrue(s.contains("rawByte in 0..127"))
    }

    @Test
    fun `cell PeekOnly × WithPayload × Point`() {
        val plan =
            makeSealed(
                FramingMode.PeekOnly(framerFqn = framerCn),
                listOf(withPayloadPoint("Alpha", 1), noPayloadPoint("Beta", 2)),
            )
        assertSensibleEmit(plan, "P·W·P")
        val s = emitText(plan)
        assertTrue(s.contains("RootAlphaCodec.decodeFromContext"))
    }

    @Test
    fun `cell PeekOnly × WithPayload × Range`() {
        val plan =
            makeSealed(
                FramingMode.PeekOnly(framerFqn = framerCn),
                listOf(withPayloadRange("Alpha", 48, 63), noPayloadPoint("Beta", 4)),
            )
        assertSensibleEmit(plan, "P·W·R")
        val s = emitText(plan)
        assertTrue(s.contains("rawByte in 48..63"))
    }

    @Test
    fun `cell BodyLength × NoPayload × Point`() {
        val plan =
            makeSealed(
                FramingMode.BodyLength(framerFqn = framerCn, discriminatorBytes = 1),
                listOf(noPayloadPoint("Alpha", 1), noPayloadPoint("Beta", 2)),
            )
        assertSensibleEmit(plan, "B·N·P")
        val s = emitText(plan)
        assertTrue(s.contains("Framer.readBodyLength"))
        // Slice 5.5: body-length locals renamed to legacy convention `_bodySlice`.
        assertTrue(s.contains("_bodySlice.remaining() != 0"))
    }

    @Test
    fun `cell BodyLength × NoPayload × Range`() {
        val plan =
            makeSealed(
                FramingMode.BodyLength(framerFqn = framerCn, discriminatorBytes = 1),
                listOf(noPayloadRange("Alpha", 0, 127), noPayloadPoint("Beta", 192)),
            )
        assertSensibleEmit(plan, "B·N·R")
        val s = emitText(plan)
        assertTrue(s.contains("Framer.readBodyLength"))
        assertTrue(s.contains("rawByte in 0..127"))
    }

    @Test
    fun `cell BodyLength × WithPayload × Point`() {
        val plan =
            makeSealed(
                FramingMode.BodyLength(framerFqn = framerCn, discriminatorBytes = 1),
                listOf(withPayloadPoint("Alpha", 1), noPayloadPoint("Beta", 2)),
            )
        assertSensibleEmit(plan, "B·W·P")
        val s = emitText(plan)
        // Slice 5.5: body-length locals renamed to legacy convention `_bodySlice`.
        assertTrue(s.contains("RootAlphaCodec.decodeFromContext(_bodySlice, "))
    }

    @Test
    fun `cell BodyLength × WithPayload × Range`() {
        val plan =
            makeSealed(
                FramingMode.BodyLength(framerFqn = framerCn, discriminatorBytes = 1),
                listOf(withPayloadRange("Alpha", 48, 63), noPayloadPoint("Beta", 4)),
            )
        assertSensibleEmit(plan, "B·W·R")
        val s = emitText(plan)
        assertTrue(s.contains("rawByte in 48..63"))
        // Slice 5.5: body-length locals renamed to legacy convention `_bodySlice`.
        assertTrue(s.contains("RootAlphaCodec.decodeFromContext(_bodySlice, "))
    }

    /**
     * Suppress unused-warning on FieldPlan/FieldStrategy imports from the
     * broader IR — the matrix tests focus on dispatcher shapes; field
     * strategies are exercised by [StructuralEmitterTest].
     */
    @Suppress("unused")
    private val unused: List<Any> = listOf(FieldPlan::class, FieldStrategy::class)
}
