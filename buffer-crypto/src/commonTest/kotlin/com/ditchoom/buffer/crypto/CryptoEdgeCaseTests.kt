package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.repeatedByte
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Boundary and precondition coverage for the crypto primitives. */
class CryptoEdgeCaseTests {
    @Test
    fun hmacEmptyMessage() {
        // HMAC-SHA256(key="Jefe", message="").
        assertEquals(
            "923598ca6d64af2a5dba79dcd021a8a0fe5c5f557519adaaf0ad532d4506dd30",
            hmacSha256(ascii("Jefe"), ascii("")).toHex(),
        )
    }

    @Test
    fun hmacKeyLengthBoundaries() {
        // Keys at the 64-byte block boundary: <= block is zero-padded, > block is hashed first.
        // Vectors are HMAC-SHA256(key=0x6b ('k') repeated N, message="abc").
        val expected =
            mapOf(
                63 to "23670528b6b8c55d2f8ef2f36c1f000cec7c23ec76881c485f2e89b5fe705cca",
                64 to "ae0c0e4a2340cf50185eb46aaa8723f4769153661612e212fb0d1fa3170c6202",
                65 to "ed378e5dfa30dc98814ba09b2e610d9b6af66054922ceef9480da094a3f11b2d",
            )
        for ((keyLen, hex) in expected) {
            assertEquals(hex, hmacSha256(repeatedByte(0x6b, keyLen), ascii("abc")).toHex(), "key len $keyLen")
        }
    }

    @Test
    fun hkdfZeroLengthProducesEmptyOutput() {
        val out =
            Hkdf.derive(
                salt = null,
                ikm = repeatedByte(0x0b, 22),
                info = null,
                length = 0,
                factory = BufferFactory.Default,
            )
        assertEquals(0, out.remaining())
    }

    @Test
    fun hkdfMaxLengthSucceeds() {
        // RFC 5869 caps Expand output at 255 * HashLen = 8160 bytes.
        val out =
            Hkdf.derive(
                salt = null,
                ikm = repeatedByte(0x0b, 22),
                info = null,
                length = 255 * SHA256_DIGEST_BYTES,
                factory = BufferFactory.Default,
            )
        assertEquals(255 * SHA256_DIGEST_BYTES, out.remaining())
    }

    @Test
    fun hkdfOverMaxLengthThrows() {
        assertFailsWith<IllegalArgumentException> {
            Hkdf.derive(
                salt = null,
                ikm = repeatedByte(0x0b, 22),
                info = null,
                length = 255 * SHA256_DIGEST_BYTES + 1,
                factory = BufferFactory.Default,
            )
        }
    }

    @Test
    fun sha256DestTooSmallThrows() {
        assertFailsWith<IllegalArgumentException> {
            sha256(ascii("abc"), BufferFactory.Default.allocate(SHA256_DIGEST_BYTES - 1))
        }
    }

    @Test
    fun hmacDestTooSmallThrows() {
        assertFailsWith<IllegalArgumentException> {
            hmacSha256(ascii("Jefe"), ascii("abc"), BufferFactory.Default.allocate(HMAC_SHA256_BYTES - 1))
        }
    }

    @Test
    fun cryptoRandomFillsOnlyRemainingLeavingPrefixIntact() {
        // Write a known prefix, advance past it, then fill the rest with random bytes.
        // The prefix must be untouched and the random tail must not be all-zero.
        val buf = BufferFactory.Default.allocate(8 + SHA256_DIGEST_BYTES)
        repeat(8) { buf.writeByte(0x7f) }
        cryptoRandomInto(buf) // fills the remaining 32 bytes at the current position
        buf.resetForRead()
        repeat(8) { assertEquals(0x7f.toByte(), buf.readByte(), "prefix byte $it must be preserved") }
        var anyNonZero = false
        repeat(SHA256_DIGEST_BYTES) { if (buf.readByte() != 0.toByte()) anyNonZero = true }
        assertTrue(anyNonZero, "random tail must not be all zero")
    }
}
