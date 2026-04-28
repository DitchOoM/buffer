package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.ir.Plan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RangeDisjointnessTest {
    @Test
    fun `disjoint single-byte points pass`() {
        val sealed: Plan =
            PhaseCFixtures.sealed(
                "test.Cmd",
                listOf(
                    PhaseCFixtures.variant("test.Cmd.A", wire = 1),
                    PhaseCFixtures.variant("test.Cmd.B", wire = 2),
                ),
            )
        Validator.validate(PhaseCFixtures.toMap(sealed)).asRightOrFail()
    }

    @Test
    fun `two points colliding on the same byte names both classes and the byte`() {
        val sealed: Plan =
            PhaseCFixtures.sealed(
                "test.Cmd",
                listOf(
                    PhaseCFixtures.variant("test.Cmd.A", wire = 0x10),
                    PhaseCFixtures.variant("test.Cmd.B", wire = 0x10),
                ),
            )
        val errors = Validator.validate(PhaseCFixtures.toMap(sealed)).asLeftOrFail()
        val msg = errors.all.firstOrNull { "overlapping" in it.message }?.message
        assertNotNull(msg, "expected overlap diagnostic; got: ${errors.all.map { it.message }}")
        assertTrue("test.Cmd.A" in msg && "test.Cmd.B" in msg, "expected both classes named in '$msg'")
        assertTrue("0x10" in msg, "expected byte named in '$msg'")
    }

    @Test
    fun `range overlapping with point is rejected and names colliding bytes`() {
        val sealed: Plan =
            PhaseCFixtures.sealed(
                "test.Cmd",
                listOf(
                    PhaseCFixtures.rangeVariant("test.Cmd.PublishLike", from = 0x30, to = 0x3F),
                    PhaseCFixtures.variant("test.Cmd.PublishMidByte", wire = 0x33),
                ),
            )
        val errors = Validator.validate(PhaseCFixtures.toMap(sealed)).asLeftOrFail()
        val msg = errors.all.firstOrNull { "overlapping" in it.message }?.message
        assertNotNull(msg, "expected range×point overlap diagnostic; got: ${errors.all.map { it.message }}")
        assertTrue("test.Cmd.PublishLike" in msg && "test.Cmd.PublishMidByte" in msg)
        assertTrue("0x33" in msg, "expected the colliding byte named in '$msg'")
    }

    @Test
    fun `two overlapping ranges name the colliding range bounds`() {
        val sealed: Plan =
            PhaseCFixtures.sealed(
                "test.Cmd",
                listOf(
                    PhaseCFixtures.rangeVariant("test.Cmd.A", from = 0x30, to = 0x3F),
                    PhaseCFixtures.rangeVariant("test.Cmd.B", from = 0x35, to = 0x4F),
                ),
            )
        val errors = Validator.validate(PhaseCFixtures.toMap(sealed)).asLeftOrFail()
        val msg = errors.all.firstOrNull { "overlapping" in it.message }?.message
        assertNotNull(msg)
        assertTrue("0x35" in msg && "0x3F" in msg, "expected colliding range '0x35..0x3F' in '$msg'")
    }

    @Test
    fun `non-overlapping range plus point passes`() {
        val sealed: Plan =
            PhaseCFixtures.sealed(
                "test.Cmd",
                listOf(
                    PhaseCFixtures.rangeVariant("test.Cmd.A", from = 0x30, to = 0x3F),
                    PhaseCFixtures.variant("test.Cmd.B", wire = 0x40),
                    PhaseCFixtures.variant("test.Cmd.C", wire = 0x10),
                ),
            )
        Validator.validate(PhaseCFixtures.toMap(sealed)).asRightOrFail()
    }

    @Test
    fun `three-way overlap reports two overlap pairs`() {
        val sealed: Plan =
            PhaseCFixtures.sealed(
                "test.Cmd",
                listOf(
                    PhaseCFixtures.variant("test.Cmd.A", wire = 1),
                    PhaseCFixtures.variant("test.Cmd.B", wire = 1),
                    PhaseCFixtures.variant("test.Cmd.C", wire = 1),
                ),
            )
        val errors = Validator.validate(PhaseCFixtures.toMap(sealed)).asLeftOrFail()
        val overlaps = errors.all.count { "overlapping" in it.message }
        // B overlaps A (1 error); C overlaps both A and B (2 errors) → 3 total
        assertEquals(3, overlaps, "expected 3 overlap pairs from three-way collision; got: ${errors.all}")
    }
}
