package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.ir.BooleanExpression
import com.ditchoom.buffer.codec.processor.ir.Direction
import com.ditchoom.buffer.codec.processor.ir.FramingMode
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.squareup.kotlinpoet.ClassName
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Hard-bar test: a fixture violating multiple independent rules surfaces every error in
 * a single Either.Left without short-circuiting on the first failure.
 */
class ValidatorErrorAccumulationTest {
    @Test
    fun `range overlap, direction mismatch, missing framer, and typo'd When path all surface together`() {
        val cyclicA: Plan =
            PhaseCFixtures.leaf(
                "test.A",
                listOf(PhaseCFixtures.nestedField("b", "test.B")),
            )
        val cyclicB: Plan =
            PhaseCFixtures.leaf(
                "test.B",
                listOf(PhaseCFixtures.nestedField("a", "test.A")),
            )

        val sealedRoot: Plan =
            PhaseCFixtures.sealed(
                "test.Cmd",
                listOf(
                    PhaseCFixtures.variant("test.Cmd.A", wire = 1, dir = Direction.EncodeOnly),
                    PhaseCFixtures.variant("test.Cmd.B", wire = 1, dir = Direction.Bidirectional),
                ),
                dispatch =
                    PhaseCFixtures.valueClassDispatch(
                        discriminatorFqn = "test.Header",
                        framing = FramingMode.PeekOnly(framerFqn = ClassName("test", "MissingFraming")),
                    ),
                dir = Direction.DecodeOnly,
            )

        val whenLeaf: Plan =
            PhaseCFixtures.leaf(
                "test.Conditional",
                listOf(
                    PhaseCFixtures.primitiveField("len"),
                    PhaseCFixtures.whenField(
                        name = "extra",
                        typeFqn = "kotlin.UByte",
                        expr = BooleanExpression.FieldRef(listOf("naameLen")),
                    ),
                ),
            )

        val errors = Validator.validate(PhaseCFixtures.toMap(cyclicA, cyclicB, sealedRoot, whenLeaf)).asLeftOrFail()
        val messages = errors.all.map { it.message }
        assertTrue(messages.any { "overlapping" in it }, "expected range overlap; got: $messages")
        assertTrue(messages.any { "Direction mismatch" in it }, "expected direction mismatch; got: $messages")
        assertTrue(messages.any { "could not be resolved" in it }, "expected missing framer; got: $messages")
        assertTrue(messages.any { "Recursive @ProtocolMessage cycle" in it }, "expected cycle; got: $messages")
        assertTrue(messages.any { "naameLen" in it }, "expected @When typo; got: $messages")
        assertTrue(errors.size >= 5, "expected ≥5 accumulated errors; got ${errors.size}")
    }

    @Test
    fun `empty plan map produces a successful ValidatedProgram`() {
        val program = Validator.validate(emptyMap()).asRightOrFail()
        assertTrue(program.size == 0)
    }
}
