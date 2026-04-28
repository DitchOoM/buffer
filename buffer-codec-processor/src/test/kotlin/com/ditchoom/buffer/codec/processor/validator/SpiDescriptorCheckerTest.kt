package com.ditchoom.buffer.codec.processor.validator

import com.ditchoom.buffer.codec.processor.ir.Plan
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpiDescriptorCheckerTest {
    @Test
    fun `descriptor with fixedSize is accepted`() {
        val leaf: Plan =
            PhaseCFixtures.leaf(
                "test.Frame",
                listOf(PhaseCFixtures.spiField("rsv", "kotlin.UByte", providerId = "rsv-spi", fixedSize = 1)),
            )
        Validator.validate(PhaseCFixtures.toMap(leaf)).asRightOrFail()
    }

    @Test
    fun `descriptor with raw payload is accepted even when fixedSize is unknown`() {
        val leaf: Plan =
            PhaseCFixtures.leaf(
                "test.Frame",
                listOf(PhaseCFixtures.spiField("blob", "kotlin.String", providerId = "rsv-spi", fixedSize = -1, raw = "magic-payload")),
            )
        Validator.validate(PhaseCFixtures.toMap(leaf)).asRightOrFail()
    }

    @Test
    fun `descriptor with neither fixedSize nor raw is rejected`() {
        val leaf: Plan =
            PhaseCFixtures.leaf(
                "test.Frame",
                listOf(PhaseCFixtures.spiField("blob", "kotlin.String", providerId = "rsv-spi", fixedSize = -1, raw = "")),
            )
        val errors = Validator.validate(PhaseCFixtures.toMap(leaf)).asLeftOrFail()
        val msg = errors.all.firstOrNull { "ambiguous descriptor" in it.message }?.message
        assertNotNull(msg, "expected ambiguous-SPI diagnostic; got: ${errors.all.map { it.message }}")
        assertTrue("rsv-spi" in msg)
        assertTrue("blob" in msg)
    }
}
