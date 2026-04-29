package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.ir.Direction
import com.ditchoom.buffer.codec.processor.ir.FieldPlan
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.ditchoom.buffer.codec.processor.ir.Plan
import com.ditchoom.buffer.codec.processor.ir.TypeFqn
import com.ditchoom.buffer.codec.processor.ir.VariantPlan
import com.ditchoom.buffer.codec.processor.ir.WireMatch
import com.squareup.kotlinpoet.ClassName
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiscriminatorFieldTypeCheckerTest {
    @Test
    fun `matching parentDispatchOn passes`() {
        val variant =
            PhaseCFixtures.rangeVariant(
                fqn = "test.Cmd.Pub",
                from = 0x30,
                to = 0x3F,
                fields = listOf(PhaseCFixtures.discriminatorOwnedField("header", "test.Hdr")),
            )
        val sealed: Plan =
            PhaseCFixtures.sealed(
                "test.Cmd",
                listOf(variant),
                dispatch = PhaseCFixtures.valueClassDispatch("test.Hdr"),
            )
        Validator.validate(PhaseCFixtures.toMap(sealed)).asRightOrFail()
    }

    @Test
    fun `mismatched parentDispatchOn surfaces a clear diagnostic`() {
        // Construct a manual NoPayload variant whose DiscriminatorOwned points at the
        // wrong type — bypasses PhaseB's per-symbol check so PhaseC's whole-program
        // re-assertion fires.
        val variant =
            VariantPlan.NoPayload(
                decl = TypeFqn("test.Cmd.Pub"),
                codec = ClassName("test", "CmdPubCodec"),
                wire = WireMatch.Range(TypeFqn("test.Cmd.Pub"), 0x30, 0x3F),
                selfEncodes = true,
                dir = Direction.Bidirectional,
                fields =
                    listOf(
                        FieldPlan(
                            name = "header",
                            type = TypeFqn("test.Hdr"),
                            strategy =
                                FieldStrategy.DiscriminatorOwned(
                                    parentDispatchOn = TypeFqn("test.OtherHdr"),
                                    sealedRootFqn = TypeFqn("test.Cmd"),
                                ),
                        ),
                    ),
            )
        val sealed: Plan =
            PhaseCFixtures.sealed(
                "test.Cmd",
                listOf(variant),
                dispatch = PhaseCFixtures.valueClassDispatch("test.Hdr"),
            )
        val errors = Validator.validate(PhaseCFixtures.toMap(sealed)).asLeftOrFail()
        val msg =
            errors.all
                .firstOrNull {
                    "@DiscriminatorField" in it.message && "OtherHdr" in it.message
                }?.message
        assertNotNull(msg, "expected mismatch diagnostic; got: ${errors.all.map { it.message }}")
        assertTrue("test.Cmd" in msg)
        assertTrue("test.OtherHdr" in msg && "test.Hdr" in msg)
    }
}
