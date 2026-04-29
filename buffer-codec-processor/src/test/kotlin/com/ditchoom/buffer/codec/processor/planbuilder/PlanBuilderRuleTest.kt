package com.ditchoom.buffer.codec.processor.planbuilder

import com.ditchoom.buffer.codec.processor.discovery.RawDirection
import com.ditchoom.buffer.codec.processor.ir.BooleanExpression
import com.ditchoom.buffer.codec.processor.ir.Conditionality
import com.ditchoom.buffer.codec.processor.ir.Direction
import com.ditchoom.buffer.codec.processor.ir.Endianness
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.ditchoom.buffer.codec.processor.ir.LengthEncoding
import com.ditchoom.buffer.codec.processor.ir.LengthSource
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.PrimitiveKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Per-rule unit tests for PhaseB. Each test pins one row of the validator-rules table to a
 * stable error message + a clear remediation hint. Multiple-error accumulation is exercised
 * by [`many violations on one field accumulate without short-circuit`] — that test is the
 * Phase 4 hard-bar fixture for Nel accumulation.
 */
class PlanBuilderRuleTest {
    @Test
    fun `data class with primitive fields produces a Plan_Leaf`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.FixedHeader",
                ctorParameters =
                    listOf(
                        Fixtures.param("raw", Fixtures.primitiveTypeRef("kotlin.UByte")),
                        Fixtures.param("seq", Fixtures.primitiveTypeRef("kotlin.UShort")),
                    ),
            )
        val plan = PlanBuilder.build(symbol).expectRight()
        val leaf = plan as? Plan.Leaf ?: fail("expected Leaf, got $plan")
        assertEquals("test.FixedHeader", leaf.decl.canonical)
        assertEquals(2, leaf.fields.size)
        val raw = leaf.fields[0].strategy as FieldStrategy.Primitive
        assertEquals(PrimitiveKind.UByte, raw.kind)
        assertEquals(1, raw.wireBytes)
        val seq = leaf.fields[1].strategy as FieldStrategy.Primitive
        assertEquals(PrimitiveKind.UShort, seq.kind)
        assertEquals(2, seq.wireBytes)
        assertEquals(Direction.Bidirectional, leaf.dir)
    }

    @Test
    fun `object symbol produces a Plan_Object_`() {
        val symbol = Fixtures.objectSymbol("test.Ping")
        val plan = PlanBuilder.build(symbol).expectRight()
        val obj = plan as? Plan.Object_ ?: fail("expected Object_, got $plan")
        assertEquals("test.Ping", obj.decl.canonical)
        assertEquals(Direction.Bidirectional, obj.dir)
    }

    @Test
    fun `Decode and Encode markers conflict yields KspError`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.Conflict",
                ctorParameters = listOf(Fixtures.param("a", Fixtures.primitiveTypeRef("kotlin.Int"))),
                direction = RawDirection.Conflict,
            )
        val errors = PlanBuilder.build(symbol).expectLeft()
        assertEquals(1, errors.size, "expected exactly one Decode+Encode conflict error: ${errors.all}")
        assertTrue(
            errors.head.message.contains("@Decode and @Encode are mutually exclusive"),
            "expected mutex message; got '${errors.head.message}'",
        )
    }

    @Test
    fun `LengthPrefixed and LengthFrom on same field both named in one error`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.Bad",
                ctorParameters =
                    listOf(
                        Fixtures.param("len", Fixtures.primitiveTypeRef("kotlin.UShort")),
                        Fixtures.param(
                            "name",
                            Fixtures.primitiveTypeRef("kotlin.String"),
                            annotations = listOf(Fixtures.lengthPrefixed(), Fixtures.lengthFrom("len")),
                        ),
                    ),
            )
        val errors = PlanBuilder.build(symbol).expectLeft()
        val msg =
            errors.all.firstOrNull { it.message.contains("mutually exclusive") }?.message
                ?: fail("expected a mutex error; got: ${errors.all.map { it.message }}")
        assertTrue(
            "@LengthPrefixed" in msg && "@LengthFrom" in msg,
            "mutex error must name both annotations; got '$msg'",
        )
    }

    @Test
    fun `LengthPrefixed Varint and VariableByteInteger on same field both named`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.Bad",
                ctorParameters =
                    listOf(
                        Fixtures.param(
                            "id",
                            Fixtures.primitiveTypeRef("kotlin.Int"),
                            annotations = listOf(Fixtures.lengthPrefixed("Varint"), Fixtures.variableByteInteger()),
                        ),
                    ),
            )
        val errors = PlanBuilder.build(symbol).expectLeft()
        val msg =
            errors.all.firstOrNull { it.message.contains("mutually exclusive") }?.message
                ?: fail("expected a mutex error; got: ${errors.all.map { it.message }}")
        assertTrue(
            "@LengthPrefixed" in msg && "@VariableByteInteger" in msg,
            "expected both annotations named; got '$msg'",
        )
    }

    @Test
    fun `LengthFrom referencing unknown field surfaces available list`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.Typo",
                ctorParameters =
                    listOf(
                        Fixtures.param("nameLen", Fixtures.primitiveTypeRef("kotlin.UShort")),
                        Fixtures.param(
                            "name",
                            Fixtures.primitiveTypeRef("kotlin.String"),
                            annotations = listOf(Fixtures.lengthFrom("naameLen")),
                        ),
                    ),
            )
        val errors = PlanBuilder.build(symbol).expectLeft()
        val msg = errors.all.firstOrNull { "naameLen" in it.message }?.message ?: fail("expected typo'd-field error")
        assertTrue("nameLen" in msg, "expected error to list available preceding fields; got '$msg'")
    }

    @Test
    fun `LengthFrom referencing non-numeric field is rejected`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.NonNumericRef",
                ctorParameters =
                    listOf(
                        Fixtures.param(
                            "label",
                            Fixtures.primitiveTypeRef("kotlin.String"),
                            annotations = listOf(Fixtures.lengthPrefixed()),
                        ),
                        Fixtures.param(
                            "name",
                            Fixtures.primitiveTypeRef("kotlin.String"),
                            annotations = listOf(Fixtures.lengthFrom("label")),
                        ),
                    ),
            )
        val errors = PlanBuilder.build(symbol).expectLeft()
        val msg = errors.all.firstOrNull { "not a numeric primitive" in it.message }?.message
        assertTrue(msg != null, "expected non-numeric error; got: ${errors.all.map { it.message }}")
    }

    @Test
    fun `RemainingBytes outside the tail position is rejected`() {
        // A trailing field that is variable-size (here: another @RemainingBytes-style
        // length-prefixed string) must surface the "must appear at the tail" diagnostic.
        // Trailing fixed-size primitive trailers are accepted via the auto-reserve path
        // — covered by DataClassCodegenTest's "remaining bytes followed by fixed-size
        // trailer auto-reserves bytes".
        val symbol =
            Fixtures.dataLike(
                fqn = "test.Misplaced",
                ctorParameters =
                    listOf(
                        Fixtures.param(
                            "msg",
                            Fixtures.primitiveTypeRef("kotlin.String"),
                            annotations = listOf(Fixtures.remainingBytes()),
                        ),
                        Fixtures.param(
                            "trailer",
                            Fixtures.primitiveTypeRef("kotlin.String"),
                            annotations = listOf(Fixtures.lengthPrefixed()),
                        ),
                    ),
            )
        val errors = PlanBuilder.build(symbol).expectLeft()
        val msg =
            errors.all.firstOrNull { "@RemainingBytes" in it.message && "tail" in it.message }
                ?: fail("expected @RemainingBytes-not-at-tail error; got: ${errors.all.map { it.message }}")
        assertTrue("msg" in msg.message)
    }

    @Test
    fun `WireBytes exceeding natural width is rejected`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.WireBytesTooBig",
                ctorParameters =
                    listOf(
                        Fixtures.param(
                            "len",
                            Fixtures.primitiveTypeRef("kotlin.Int"),
                            annotations = listOf(Fixtures.wireBytes(5)),
                        ),
                    ),
            )
        val errors = PlanBuilder.build(symbol).expectLeft()
        val msg = errors.all.firstOrNull { "natural width" in it.message }?.message
        assertTrue(msg != null, "expected natural-width error; got: ${errors.all.map { it.message }}")
    }

    @Test
    fun `WireBytes on Boolean field is rejected`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.WireBytesOnBool",
                ctorParameters =
                    listOf(
                        Fixtures.param(
                            "flag",
                            Fixtures.primitiveTypeRef("kotlin.Boolean"),
                            annotations = listOf(Fixtures.wireBytes(2)),
                        ),
                    ),
            )
        val errors = PlanBuilder.build(symbol).expectLeft()
        assertTrue(errors.all.any { "Bool/Float/Double" in it.message }, "expected disallowed-type error: ${errors.all}")
    }

    @Test
    fun `When expression on a field becomes Conditionality_WhenExpr`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.Cond",
                ctorParameters =
                    listOf(
                        Fixtures.param("hasExtra", Fixtures.primitiveTypeRef("kotlin.Boolean")),
                        Fixtures.param(
                            "extra",
                            Fixtures.nullablePrimitiveTypeRef("kotlin.Int"),
                            annotations = listOf(Fixtures.whenAnnotation("hasExtra")),
                            hasDefault = true,
                        ),
                    ),
            )
        val plan = PlanBuilder.build(symbol).expectRight() as Plan.Leaf
        val cond = plan.fields[1].conditionality as Conditionality.WhenExpr
        assertEquals(BooleanExpression.FieldRef(listOf("hasExtra")), cond.expr)
    }

    @Test
    fun `WhenRemaining maps to RemainingGte conditionality`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.AckV5",
                ctorParameters =
                    listOf(
                        Fixtures.param("packetId", Fixtures.primitiveTypeRef("kotlin.UShort")),
                        Fixtures.param(
                            "reasonCode",
                            Fixtures.nullablePrimitiveTypeRef("kotlin.UByte"),
                            annotations = listOf(Fixtures.whenRemaining(1)),
                            hasDefault = true,
                        ),
                    ),
            )
        val plan = PlanBuilder.build(symbol).expectRight() as Plan.Leaf
        val cond = plan.fields[1].conditionality as Conditionality.WhenExpr
        assertEquals(BooleanExpression.RemainingGte(1), cond.expr)
    }

    @Test
    fun `When expression with malformed body surfaces parser failure as KspError`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.MalformedWhen",
                ctorParameters =
                    listOf(
                        Fixtures.param(
                            "field",
                            Fixtures.nullablePrimitiveTypeRef("kotlin.Int"),
                            annotations = listOf(Fixtures.whenAnnotation("invalid !!! syntax")),
                        ),
                    ),
            )
        val errors = PlanBuilder.build(symbol).expectLeft()
        val msg = errors.all.firstOrNull { "@When expression" in it.message }?.message
        assertTrue(msg != null, "expected @When parser-failure surface; got: ${errors.all.map { it.message }}")
    }

    @Test
    fun `Multiple When and WhenTrue annotations on one field is rejected`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.DoubleWhen",
                ctorParameters =
                    listOf(
                        Fixtures.param(
                            "v",
                            Fixtures.nullablePrimitiveTypeRef("kotlin.Int"),
                            annotations = listOf(Fixtures.whenAnnotation("a"), Fixtures.whenTrueAnnotation("b")),
                        ),
                    ),
            )
        val errors = PlanBuilder.build(symbol).expectLeft()
        val msg =
            errors.all.firstOrNull { "multiple conditional annotations" in it.message }?.message
                ?: fail("expected duplicate-conditional error; got: ${errors.all.map { it.message }}")
        assertTrue("@When" in msg && "@WhenTrue" in msg, "expected both annotations named; got '$msg'")
    }

    @Test
    fun `LengthPrefixed Varint default maxBytes is 4`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.VarintDefault",
                ctorParameters =
                    listOf(
                        Fixtures.param(
                            "topic",
                            Fixtures.primitiveTypeRef("kotlin.String"),
                            annotations = listOf(Fixtures.lengthPrefixed("Varint")),
                        ),
                    ),
            )
        val plan = PlanBuilder.build(symbol).expectRight() as Plan.Leaf
        val str = plan.fields[0].strategy as FieldStrategy.StringField
        val length = str.length as LengthSource.Inline
        assertEquals(LengthEncoding.Varint, length.encoding)
        assertEquals(4, length.maxBytes)
    }

    @Test
    fun `LengthPrefixed Varint maxBytes outside 1-4 is rejected`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.BadVarint",
                ctorParameters =
                    listOf(
                        Fixtures.param(
                            "topic",
                            Fixtures.primitiveTypeRef("kotlin.String"),
                            annotations = listOf(Fixtures.lengthPrefixed("Varint", maxBytes = 5)),
                        ),
                    ),
            )
        val errors = PlanBuilder.build(symbol).expectLeft()
        assertTrue(errors.all.any { "1..4" in it.message }, "expected 1..4 cap error; got: ${errors.all}")
    }

    @Test
    fun `Unannotated unknown type is rejected with @UseCodec hint`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.UnknownType",
                ctorParameters =
                    listOf(
                        Fixtures.param("blob", Fixtures.nestedMessageRef("ext.Bitmap")),
                    ),
            )
        val errors = PlanBuilder.build(symbol).expectLeft()
        val msg = errors.all.firstOrNull { "@UseCodec" in it.message }?.message
        assertTrue(msg != null, "expected @UseCodec hint for unknown type; got: ${errors.all.map { it.message }}")
    }

    @Test
    fun `UseCodec with unresolved class is rejected with classpath hint`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.BadCodec",
                ctorParameters =
                    listOf(
                        Fixtures.param(
                            "blob",
                            Fixtures.nestedMessageRef("ext.Bitmap"),
                            annotations = listOf(Fixtures.useCodec("")),
                        ),
                    ),
            )
        val errors = PlanBuilder.build(symbol).expectLeft()
        val msg = errors.all.firstOrNull { "did not resolve" in it.message }?.message
        assertTrue(msg != null, "expected unresolved-class error; got: ${errors.all.map { it.message }}")
    }

    @Test
    fun `Many violations on one field accumulate without short-circuit (Phase 4 hard-bar)`() {
        // Two simultaneous violations: mutex (LengthPrefixed + LengthFrom) AND duplicate When/WhenRemaining.
        val symbol =
            Fixtures.dataLike(
                fqn = "test.TwoErrors",
                ctorParameters =
                    listOf(
                        Fixtures.param("len", Fixtures.primitiveTypeRef("kotlin.UShort")),
                        Fixtures.param(
                            "name",
                            Fixtures.nullablePrimitiveTypeRef("kotlin.String"),
                            annotations =
                                listOf(
                                    Fixtures.lengthPrefixed(),
                                    Fixtures.lengthFrom("len"),
                                    Fixtures.whenAnnotation("len > 0"),
                                    Fixtures.whenRemaining(1),
                                ),
                        ),
                    ),
            )
        val errors = PlanBuilder.build(symbol).expectLeft()
        assertTrue(errors.size >= 2, "expected at least two accumulated errors; got $errors")
        val messages = errors.all.map { it.message }
        assertTrue(messages.any { "mutually exclusive" in it }, "expected length-mutex error; got $messages")
        assertTrue(messages.any { "multiple conditional annotations" in it }, "expected conditionality-mutex error")
    }

    @Test
    fun `Multi-symbol fixture produces one error per offending symbol`() {
        // One root with two variants; each variant has a unique violation.
        val variantBad1 =
            Fixtures.dataLike(
                fqn = "test.Cmd.Bad1",
                ctorParameters =
                    listOf(
                        Fixtures.param(
                            "x",
                            Fixtures.primitiveTypeRef("kotlin.UByte"),
                            annotations = listOf(Fixtures.wireBytes(0)),
                        ),
                    ),
                annotations = listOf(Fixtures.protocolMessageAnnotation(), Fixtures.packetType(1)),
            )
        val variantBad2 =
            Fixtures.dataLike(
                fqn = "test.Cmd.Bad2",
                ctorParameters =
                    listOf(
                        Fixtures.param(
                            "y",
                            Fixtures.primitiveTypeRef("kotlin.Int"),
                            annotations = listOf(Fixtures.wireBytes(99)),
                        ),
                    ),
                annotations = listOf(Fixtures.protocolMessageAnnotation(), Fixtures.packetType(2)),
            )
        val root =
            Fixtures.sealedRoot(
                fqn = "test.Cmd",
                subclassFqns = listOf("test.Cmd.Bad1", "test.Cmd.Bad2"),
            )
        val scope = mapOf(root.fqn to root, variantBad1.fqn to variantBad1, variantBad2.fqn to variantBad2)
        val errors = PlanBuilder.build(root, scope).expectLeft()
        assertTrue(errors.size >= 2, "expected at least one error per offending variant; got $errors")
    }

    @Test
    fun `wireOrder defaults to Big when annotation is absent`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.NoOrder",
                ctorParameters = listOf(Fixtures.param("v", Fixtures.primitiveTypeRef("kotlin.Int"))),
            )
        val plan = PlanBuilder.build(symbol).expectRight() as Plan.Leaf
        val prim = plan.fields[0].strategy as FieldStrategy.Primitive
        assertEquals(Endianness.Big, prim.order)
    }

    @Test
    fun `WireOrder field annotation overrides class-level wireOrder`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.MixedOrder",
                ctorParameters =
                    listOf(
                        Fixtures.param(
                            "magic",
                            Fixtures.primitiveTypeRef("kotlin.UInt"),
                            annotations = listOf(Fixtures.wireOrder("Big")),
                        ),
                        Fixtures.param("len", Fixtures.primitiveTypeRef("kotlin.UInt")),
                    ),
                annotations = listOf(Fixtures.protocolMessageAnnotation(wireOrder = "Little")),
            )
        val plan = PlanBuilder.build(symbol).expectRight() as Plan.Leaf
        val magic = plan.fields[0].strategy as FieldStrategy.Primitive
        val len = plan.fields[1].strategy as FieldStrategy.Primitive
        assertEquals(Endianness.Big, magic.order)
        assertEquals(Endianness.Little, len.order)
    }

    @Test
    fun `Single-byte field with WireOrder annotation surfaces a no-effect error`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.SingleByteOrder",
                ctorParameters =
                    listOf(
                        Fixtures.param(
                            "raw",
                            Fixtures.primitiveTypeRef("kotlin.UByte"),
                            annotations = listOf(Fixtures.wireOrder("Big")),
                        ),
                    ),
            )
        val errors = PlanBuilder.build(symbol).expectLeft()
        assertTrue(
            errors.all.any { "no effect" in it.message && "single-byte" in it.message },
            "expected no-effect error; got: ${errors.all.map { it.message }}",
        )
    }

    @Test
    fun `Decode marker resolves to Direction_DecodeOnly`() {
        val symbol =
            Fixtures.dataLike(
                fqn = "test.DecodeOnly",
                ctorParameters = listOf(Fixtures.param("v", Fixtures.primitiveTypeRef("kotlin.Int"))),
                direction = RawDirection.DecodeOnly,
            )
        val plan = PlanBuilder.build(symbol).expectRight() as Plan.Leaf
        assertEquals(Direction.DecodeOnly, plan.dir)
    }
}
