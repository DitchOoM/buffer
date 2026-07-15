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

    // --- secureRandom (kotlin.random.Random over the platform CSPRNG) --------------------------

    @Test
    fun secureRandomBoundedStaysInRange() {
        // 36 is not a power of two, so this exercises Random's unbiased rejection reduction over the
        // CSPRNG — the exact path a bare byte API cannot supply without modulo bias.
        repeat(10_000) {
            val v = secureRandom.nextInt(36)
            assertTrue(v in 0 until 36, "nextInt(36) out of range: $v")
        }
    }

    @Test
    fun secureRandomBoundedCoversEveryBucket() {
        // Over 36*100 draws, missing any of 36 buckets has probability ~(35/36)^3600 ≈ e^-100 — a
        // stuck or biased-to-a-subrange generator fails; a real CSPRNG passes deterministically.
        val seen = mutableSetOf<Int>()
        repeat(3_600) { seen.add(secureRandom.nextInt(36)) }
        assertEquals(36, seen.size, "not every bucket in [0,36) was produced")
    }

    @Test
    fun secureRandomNextBitsZeroIsZero() {
        // The takeUpperBits mask must collapse bitCount == 0 to 0 rather than returning a full int.
        repeat(100) { assertEquals(0, secureRandom.nextBits(0)) }
    }

    @Test
    fun secureRandomNextIntVaries() {
        val values = buildSet { repeat(64) { add(secureRandom.nextInt()) } }
        assertTrue(values.size > 1, "nextInt() produced a constant value across 64 draws")
    }

    @Test
    fun secureRandomNextLongVaries() {
        val values = buildSet { repeat(64) { add(secureRandom.nextLong()) } }
        assertTrue(values.size > 1, "nextLong() produced a constant value across 64 draws")
    }
}
