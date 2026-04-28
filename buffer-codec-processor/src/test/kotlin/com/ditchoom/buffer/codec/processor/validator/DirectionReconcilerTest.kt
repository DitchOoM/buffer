package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.ir.Direction
import com.ditchoom.buffer.codec.processor.ir.Plan
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DirectionReconcilerTest {
    @Test
    fun `bidirectional sealed root accepts any variant direction`() {
        val sealed: Plan =
            PhaseCFixtures.sealed(
                "test.Cmd",
                listOf(
                    PhaseCFixtures.variant("test.Cmd.A", wire = 1, dir = Direction.DecodeOnly),
                    PhaseCFixtures.variant("test.Cmd.B", wire = 2, dir = Direction.EncodeOnly),
                    PhaseCFixtures.variant("test.Cmd.C", wire = 3, dir = Direction.Bidirectional),
                ),
                dir = Direction.Bidirectional,
            )
        Validator.validate(PhaseCFixtures.toMap(sealed)).asRightOrFail()
    }

    @Test
    fun `decode-only sealed root accepts decode and bidirectional variants`() {
        val sealed: Plan =
            PhaseCFixtures.sealed(
                "test.Cmd",
                listOf(
                    PhaseCFixtures.variant("test.Cmd.A", wire = 1, dir = Direction.DecodeOnly),
                    PhaseCFixtures.variant("test.Cmd.B", wire = 2, dir = Direction.Bidirectional),
                ),
                dir = Direction.DecodeOnly,
            )
        Validator.validate(PhaseCFixtures.toMap(sealed)).asRightOrFail()
    }

    @Test
    fun `decode-only sealed root rejects encode-only variant`() {
        val sealed: Plan =
            PhaseCFixtures.sealed(
                "test.Cmd",
                listOf(
                    PhaseCFixtures.variant("test.Cmd.A", wire = 1, dir = Direction.EncodeOnly),
                ),
                dir = Direction.DecodeOnly,
            )
        val errors = Validator.validate(PhaseCFixtures.toMap(sealed)).asLeftOrFail()
        val msg = errors.all.firstOrNull { "Direction mismatch" in it.message }?.message
        assertNotNull(msg, "expected mismatch; got: ${errors.all.map { it.message }}")
        assertTrue("test.Cmd.A" in msg && "test.Cmd" in msg)
        assertTrue("EncodeOnly" in msg && "DecodeOnly" in msg)
    }

    @Test
    fun `encode-only sealed root rejects decode-only variant`() {
        val sealed: Plan =
            PhaseCFixtures.sealed(
                "test.Cmd",
                listOf(
                    PhaseCFixtures.variant("test.Cmd.A", wire = 1, dir = Direction.DecodeOnly),
                ),
                dir = Direction.EncodeOnly,
            )
        val errors = Validator.validate(PhaseCFixtures.toMap(sealed)).asLeftOrFail()
        assertTrue(errors.all.any { "Direction mismatch" in it.message })
    }
}
