package com.ditchoom.buffer.codec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeclaredFrameBytesTest {
    @Test
    fun totalsInsideIntRangeAreExact() {
        assertEquals(0, declaredFrameBytes(0, 0L))
        assertEquals(9, declaredFrameBytes(9, 0L))
        assertEquals(Int.MAX_VALUE, declaredFrameBytes(6, Int.MAX_VALUE - 6L))
        assertEquals(Int.MAX_VALUE, declaredFrameBytes(0, Int.MAX_VALUE.toULong()))
    }

    @Test
    fun aTotalOnePastIntMaxIsRejectedWithTheExactDeclaration() {
        val thrown = assertFailsWith<FrameTooLargeException> { declaredFrameBytes(6, Int.MAX_VALUE - 5L) }
        assertEquals(Int.MAX_VALUE + 1L, thrown.declaredBytes)
        assertEquals(Int.MAX_VALUE, thrown.maxBytes)
    }

    @Test
    fun declarationsThatWouldWrapAnIntAreRejected() {
        for (declared in listOf(1L shl 31, (1L shl 32) - 1, (1L shl 32) + 3)) {
            val thrown = assertFailsWith<FrameTooLargeException> { declaredFrameBytes(9, declared) }
            assertEquals(9 + declared, thrown.declaredBytes)
        }
    }

    @Test
    fun negativeDeclarationsAreRejected() {
        val thrown = assertFailsWith<FrameTooLargeException> { declaredFrameBytes(4, -1L) }
        assertEquals(-1L, thrown.declaredBytes)
    }

    @Test
    fun hugeDeclarationsSaturateInsteadOfWrapping() {
        fun rejected(block: () -> Int): Long = assertFailsWith<FrameTooLargeException> { block() }.declaredBytes
        assertEquals(Long.MAX_VALUE, rejected { declaredFrameBytes(9, Long.MAX_VALUE) })
        assertEquals(Long.MAX_VALUE, rejected { declaredFrameBytes(9, ULong.MAX_VALUE) })
        assertEquals(Long.MAX_VALUE, rejected { declaredFrameBytes(0, 1uL shl 63) })
    }
}
