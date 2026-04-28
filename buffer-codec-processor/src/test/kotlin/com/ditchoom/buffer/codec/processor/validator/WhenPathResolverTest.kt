package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.ir.BooleanExpression
import com.ditchoom.buffer.codec.processor.ir.Plan
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WhenPathResolverTest {
    @Test
    fun `single-segment path resolves to a sibling field`() {
        val leaf: Plan =
            PhaseCFixtures.leaf(
                "test.Frame",
                listOf(
                    PhaseCFixtures.primitiveField("hasExtra"),
                    PhaseCFixtures.whenField(
                        name = "extra",
                        typeFqn = "kotlin.UByte",
                        expr = BooleanExpression.FieldRef(listOf("hasExtra")),
                    ),
                ),
            )
        Validator.validate(PhaseCFixtures.toMap(leaf)).asRightOrFail()
    }

    @Test
    fun `typo single-segment path emits available-fields hint`() {
        val leaf: Plan =
            PhaseCFixtures.leaf(
                "test.Frame",
                listOf(
                    PhaseCFixtures.primitiveField("hasExtra"),
                    PhaseCFixtures.primitiveField("seq", typeFqn = "kotlin.UShort"),
                    PhaseCFixtures.whenField(
                        name = "extra",
                        typeFqn = "kotlin.UByte",
                        expr = BooleanExpression.FieldRef(listOf("hasExtraa")),
                    ),
                ),
            )
        val errors = Validator.validate(PhaseCFixtures.toMap(leaf)).asLeftOrFail()
        val msg = errors.all.firstOrNull { "hasExtraa" in it.message }?.message
        assertNotNull(msg, "expected typo'd-path diagnostic; got: ${errors.all.map { it.message }}")
        assertTrue("Available" in msg)
        assertTrue("hasExtra" in msg && "seq" in msg, "expected available paths listed; got: '$msg'")
    }

    @Test
    fun `dotted path resolves through a known nested message`() {
        val flags: Plan =
            PhaseCFixtures.leaf(
                "test.Flags",
                listOf(
                    PhaseCFixtures.primitiveField(
                        "willFlag",
                        typeFqn = "kotlin.Boolean",
                        kind = com.ditchoom.buffer.codec.processor.ir.PrimitiveKind.Bool,
                    ),
                    PhaseCFixtures.primitiveField(
                        "cleanSession",
                        typeFqn = "kotlin.Boolean",
                        kind = com.ditchoom.buffer.codec.processor.ir.PrimitiveKind.Bool,
                    ),
                ),
            )
        val frame: Plan =
            PhaseCFixtures.leaf(
                "test.Frame",
                listOf(
                    PhaseCFixtures.nestedField("flags", typeFqn = "test.Flags"),
                    PhaseCFixtures.whenField(
                        name = "willTopic",
                        typeFqn = "kotlin.String",
                        expr = BooleanExpression.FieldRef(listOf("flags", "willFlag")),
                    ),
                ),
            )
        Validator.validate(PhaseCFixtures.toMap(frame, flags)).asRightOrFail()
    }

    @Test
    fun `dotted path fails when the second segment does not exist on the nested type`() {
        val flags: Plan =
            PhaseCFixtures.leaf(
                "test.Flags",
                listOf(
                    PhaseCFixtures.primitiveField(
                        "willFlag",
                        typeFqn = "kotlin.Boolean",
                        kind = com.ditchoom.buffer.codec.processor.ir.PrimitiveKind.Bool,
                    ),
                ),
            )
        val frame: Plan =
            PhaseCFixtures.leaf(
                "test.Frame",
                listOf(
                    PhaseCFixtures.nestedField("flags", typeFqn = "test.Flags"),
                    PhaseCFixtures.whenField(
                        name = "willTopic",
                        typeFqn = "kotlin.String",
                        expr = BooleanExpression.FieldRef(listOf("flags", "wilFlag")),
                    ),
                ),
            )
        val errors = Validator.validate(PhaseCFixtures.toMap(frame, flags)).asLeftOrFail()
        val msg = errors.all.firstOrNull { "wilFlag" in it.message }?.message
        assertNotNull(msg, "expected nested-typo diagnostic; got: ${errors.all.map { it.message }}")
        assertTrue("test.Flags" in msg)
        assertTrue("willFlag" in msg, "expected available-fields hint listing 'willFlag'; got: '$msg'")
    }

    @Test
    fun `RemainingGte expressions are not field-resolved`() {
        val leaf: Plan =
            PhaseCFixtures.leaf(
                "test.AckV5",
                listOf(
                    PhaseCFixtures.primitiveField(
                        "packetId",
                        typeFqn = "kotlin.UShort",
                        kind = com.ditchoom.buffer.codec.processor.ir.PrimitiveKind.UShort,
                    ),
                    PhaseCFixtures.whenField(
                        name = "reasonCode",
                        typeFqn = "kotlin.UByte",
                        expr = BooleanExpression.RemainingGte(1),
                    ),
                ),
            )
        Validator.validate(PhaseCFixtures.toMap(leaf)).asRightOrFail()
    }

    @Test
    fun `Eq and Gt expressions resolve their lhs FieldRef recursively`() {
        val leaf: Plan =
            PhaseCFixtures.leaf(
                "test.Header",
                listOf(
                    PhaseCFixtures.primitiveField("qos"),
                    PhaseCFixtures.whenField(
                        name = "ack",
                        typeFqn = "kotlin.UByte",
                        expr = BooleanExpression.Gt(BooleanExpression.FieldRef(listOf("qos")), 0),
                    ),
                    PhaseCFixtures.whenField(
                        name = "reason",
                        typeFqn = "kotlin.UByte",
                        expr = BooleanExpression.Eq(BooleanExpression.FieldRef(listOf("qoss")), 1),
                    ),
                ),
            )
        val errors = Validator.validate(PhaseCFixtures.toMap(leaf)).asLeftOrFail()
        // Only "qoss" should fail; "qos" is well-defined.
        val matching = errors.all.filter { "qoss" in it.message }
        assertTrue(matching.isNotEmpty(), "expected qoss typo diagnostic")
        assertTrue(errors.all.none { "@When on 'test.Header.ack'" in it.message })
    }
}
