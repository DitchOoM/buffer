package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.ir.Plan
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CycleDetectorTest {
    @Test
    fun `non-cyclic graph passes`() {
        val a: Plan = PhaseCFixtures.leaf("test.A", listOf(PhaseCFixtures.nestedField("b", "test.B")))
        val b: Plan = PhaseCFixtures.leaf("test.B", listOf(PhaseCFixtures.nestedField("c", "test.C")))
        val c: Plan = PhaseCFixtures.leaf("test.C")
        Validator.validate(PhaseCFixtures.toMap(a, b, c)).asRightOrFail()
    }

    @Test
    fun `direct A to B to A cycle is named in the diagnostic`() {
        val a: Plan = PhaseCFixtures.leaf("test.A", listOf(PhaseCFixtures.nestedField("b", "test.B")))
        val b: Plan = PhaseCFixtures.leaf("test.B", listOf(PhaseCFixtures.nestedField("a", "test.A")))
        val errors = Validator.validate(PhaseCFixtures.toMap(a, b)).asLeftOrFail()
        val msg = errors.all.firstOrNull { "Recursive @ProtocolMessage cycle" in it.message }?.message
        assertNotNull(msg, "expected cycle diagnostic; got: ${errors.all.map { it.message }}")
        assertTrue("test.A" in msg && "test.B" in msg, "expected both classes named in '$msg'")
    }

    @Test
    fun `self-cycle is detected with a clear self-reference message`() {
        val a: Plan = PhaseCFixtures.leaf("test.A", listOf(PhaseCFixtures.nestedField("self", "test.A")))
        val errors = Validator.validate(PhaseCFixtures.toMap(a)).asLeftOrFail()
        val msg = errors.all.firstOrNull { "references itself" in it.message }?.message
        assertNotNull(msg, "expected self-reference message; got: ${errors.all.map { it.message }}")
        assertTrue("test.A" in msg)
    }

    @Test
    fun `three-way cycle A to B to C to A is reported once`() {
        val a: Plan = PhaseCFixtures.leaf("test.A", listOf(PhaseCFixtures.nestedField("b", "test.B")))
        val b: Plan = PhaseCFixtures.leaf("test.B", listOf(PhaseCFixtures.nestedField("c", "test.C")))
        val c: Plan = PhaseCFixtures.leaf("test.C", listOf(PhaseCFixtures.nestedField("a", "test.A")))
        val errors = Validator.validate(PhaseCFixtures.toMap(a, b, c)).asLeftOrFail()
        val cycles = errors.all.filter { "Recursive @ProtocolMessage cycle" in it.message }
        assertTrue(cycles.size == 1, "expected one cycle diagnostic, got ${cycles.size}: ${cycles.map { it.message }}")
        assertTrue("test.A" in cycles[0].message)
        assertTrue("test.B" in cycles[0].message)
        assertTrue("test.C" in cycles[0].message)
    }

    @Test
    fun `parallel non-cyclic edges to the same target do not produce false positives`() {
        val shared: Plan = PhaseCFixtures.leaf("test.Shared")
        val a: Plan = PhaseCFixtures.leaf("test.A", listOf(PhaseCFixtures.nestedField("s", "test.Shared")))
        val b: Plan = PhaseCFixtures.leaf("test.B", listOf(PhaseCFixtures.nestedField("s", "test.Shared")))
        Validator.validate(PhaseCFixtures.toMap(a, b, shared)).asRightOrFail()
    }
}
