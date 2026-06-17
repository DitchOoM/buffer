package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConstantTimeTest {
    @Test
    fun equalBuffersMatch() {
        val a = hexBuffer("00112233445566778899aabbccddeeff")
        val b = hexBuffer("00112233445566778899aabbccddeeff")
        assertTrue(a.constantTimeEquals(b))
        assertTrue(b.constantTimeEquals(a))
    }

    @Test
    fun differingLastByteDoesNotMatch() {
        val a = hexBuffer("00112233445566778899aabbccddeeff")
        val b = hexBuffer("00112233445566778899aabbccddeefe")
        assertFalse(a.constantTimeEquals(b))
    }

    @Test
    fun differingFirstByteDoesNotMatch() {
        val a = hexBuffer("00112233445566778899aabbccddeeff")
        val b = hexBuffer("01112233445566778899aabbccddeeff")
        assertFalse(a.constantTimeEquals(b))
    }

    @Test
    fun differentLengthsDoNotMatch() {
        val a = hexBuffer("0011223344")
        val b = hexBuffer("00112233")
        assertFalse(a.constantTimeEquals(b))
        assertFalse(b.constantTimeEquals(a))
    }

    @Test
    fun emptyBuffersMatch() {
        val a = hexBuffer("")
        val b = hexBuffer("")
        assertTrue(a.constantTimeEquals(b))
    }

    @Test
    fun comparisonIsNonDestructive() {
        val a = hexBuffer("00112233445566778899aabbccddeeff")
        val b = hexBuffer("00112233445566778899aabbccddeeff")
        val aPos = a.position()
        val bPos = b.position()
        val aRem = a.remaining()
        a.constantTimeEquals(b)
        assertEquals(aPos, a.position(), "left position must be unchanged")
        assertEquals(bPos, b.position(), "right position must be unchanged")
        assertEquals(aRem, a.remaining(), "left remaining must be unchanged")
    }
}
