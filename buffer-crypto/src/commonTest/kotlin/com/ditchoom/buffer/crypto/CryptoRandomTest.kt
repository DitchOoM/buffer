package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CryptoRandomTest {
    @Test
    fun producesRequestedLength() {
        assertEquals(32, cryptoRandom(32).remaining())
    }

    @Test
    fun twoDrawsDiffer() {
        // Astronomically unlikely to collide; a constant generator would fail here.
        assertNotEquals(cryptoRandom(32).toHex(), cryptoRandom(32).toHex())
    }

    @Test
    fun notAllZero() {
        val hex = cryptoRandom(32).toHex()
        assertTrue(hex.any { it != '0' }, "CSPRNG returned all zero bytes")
    }
}
