package com.ditchoom.buffer.codec

import kotlin.test.Test
import kotlin.test.assertEquals

class PeekedIntegerTest {
    private fun describe(peeked: PeekedInteger): String {
        val notRepresentable = "notRepresentable"
        return peeked.fold({ "value:$it" }, { "needsMoreData" }, { notRepresentable })
    }

    @Test
    fun everyStateFoldsToItsOwnBranch() {
        assertEquals("value:0", describe(PeekedInteger.of(0L)))
        assertEquals("value:${Long.MAX_VALUE}", describe(PeekedInteger.of(Long.MAX_VALUE)))
        assertEquals("needsMoreData", describe(PeekedInteger.NeedsMoreData))
        assertEquals("notRepresentable", describe(PeekedInteger.NotRepresentable))
        assertEquals("PeekedInteger.of(7)", PeekedInteger.of(7L).toString())
    }

    @Test
    fun aValueOutsideTheNonNegativeLongRangeIsNotRepresentableRatherThanAState() {
        assertEquals(PeekedInteger.NotRepresentable, PeekedInteger.of(-1L))
        assertEquals(PeekedInteger.NotRepresentable, PeekedInteger.of(-2L))
        assertEquals(PeekedInteger.NotRepresentable, PeekedInteger.of(Long.MIN_VALUE))
        assertEquals(PeekedInteger.NotRepresentable, PeekedInteger.of(ULong.MAX_VALUE))
        assertEquals(PeekedInteger.of(Long.MAX_VALUE), PeekedInteger.of(Long.MAX_VALUE.toULong()))
        assertEquals(PeekedInteger.of(5L), PeekedInteger.of(5uL))
    }
}
