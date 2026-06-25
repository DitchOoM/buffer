package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.repeatedByte
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * HKDF-SHA384 / HKDF-SHA512 (RFC 5869) known-answer and boundary tests. Inputs follow
 * RFC 5869 Test Case 1's structure; expected OKM produced by a reference HMAC-based HKDF.
 */
class HkdfSha384Sha512Test {
    private val ikm = repeatedByte(0x0b, 22)
    private val salt = hexBuffer("000102030405060708090a0b0c")
    private val info = hexBuffer("f0f1f2f3f4f5f6f7f8f9")

    @Test
    fun hkdfSha512Tc1() {
        val okm = HkdfSha512.derive(Salt.Of(salt), ikm, Info.Of(info), 42, BufferFactory.Default)
        assertEquals(
            "832390086cda71fb47625bb5ceb168e4c8e26a1a16ed34d9fc7fe92c1481579338da362cb8d9f925d7cb",
            okm.toHex(),
        )
    }

    @Test
    fun hkdfSha384Tc1() {
        val okm = HkdfSha384.derive(Salt.Of(salt), ikm, Info.Of(info), 42, BufferFactory.Default)
        assertEquals(
            "9b5097a86038b805309076a44b3a9f38063e25b516dcbf369f394cfab43685f748b6457763e4f0204fc5",
            okm.toHex(),
        )
    }

    @Test
    fun hkdfSha512NullSaltAndInfo() {
        val okm = HkdfSha512.derive(salt = Salt.None, ikm = ikm, info = Info.None, length = 42, factory = BufferFactory.Default)
        assertEquals(
            "f5fa02b18298a72a8c23898a8703472c6eb179dc204c03425c970e3b164bf90fff22d04836d0e2343bac",
            okm.toHex(),
        )
    }

    @Test
    fun maxLengthBoundary() {
        // RFC 5869 caps Expand output at 255 * HashLen.
        val max512 = 255 * SHA512_DIGEST_BYTES
        assertEquals(max512, HkdfSha512.derive(Salt.Of(salt), ikm, Info.Of(info), max512, BufferFactory.Default).remaining())
        assertFailsWith<IllegalArgumentException> {
            HkdfSha512.derive(Salt.Of(salt), ikm, Info.Of(info), max512 + 1, BufferFactory.Default)
        }
        val max384 = 255 * SHA384_DIGEST_BYTES
        assertEquals(max384, HkdfSha384.derive(Salt.Of(salt), ikm, Info.Of(info), max384, BufferFactory.Default).remaining())
        assertFailsWith<IllegalArgumentException> {
            HkdfSha384.derive(Salt.Of(salt), ikm, Info.Of(info), max384 + 1, BufferFactory.Default)
        }
    }
}
