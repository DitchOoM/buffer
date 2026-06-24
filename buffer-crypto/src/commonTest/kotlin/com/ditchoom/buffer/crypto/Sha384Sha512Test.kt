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

/**
 * NIST/FIPS 180-4 known-answer vectors for SHA-384 and SHA-512, plus boundary cases that
 * exercise the block loop and both padding paths (a message that leaves <16 bytes in the
 * final block must spill into a second padded block). KAT values produced by a reference
 * implementation; every platform matching them gives transitive cross-platform consistency.
 */
class Sha384Sha512Test {
    // (message-builder, sha384, sha512)
    private val empty384 =
        "38b060a751ac96384cd9327eb1b1e36a21fdb71114be0743" +
            "4c0cc7bf63f6e1da274edebfe76f65fbd51ad2f14898b95b"
    private val empty512 =
        "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce" +
            "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e"
    private val abc384 =
        "cb00753f45a35e8bb5a03d699ac65007272c32ab0eded163" +
            "1a8b605a43ff5bed8086072ba1e7cc2358baeca134c825a7"
    private val abc512 =
        "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
            "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"

    // 111- and 112-byte messages straddle the 1024-bit block's length-field boundary.
    private val e111v384 =
        "a47bc504cd628dbc1abac61dd4fb95c14a5ce395ce0a2ba2" +
            "3e29f065159ab694335cd8664a63cdc8a1b1ce318cf99187"
    private val e112v384 =
        "2f4396d8a08aa20908f102b110f5895325c27e87b7f2cc60" +
            "fb80e191762f4557aa6edc8ce5877bb67541c6628c664892"
    private val e111v512 =
        "419c4a5a61a60515b115bc4c67f412b9d5e3a9b044451cccf91fd9cf917ff148" +
            "8c05942ea6598d2fc40183befd85786cff8ac0bcd5fd7fff666968a198f22e21"
    private val e112v512 =
        "059c1d8b62b418815a520e62302b26cc7f297c90d4f674fbdc30a13ad9c39603" +
            "ca79c46501559c71c82f755fd3831ce1360cf518b0f0bee628ad67be5ad39ddd"

    @Test
    fun sha384KnownAnswers() {
        assertEquals(empty384, sha384(ascii("")).toHex())
        assertEquals(abc384, sha384(ascii("abc")).toHex())
        assertEquals(e111v384, sha384(repeatedByte('b'.code, 111)).toHex())
        assertEquals(e112v384, sha384(repeatedByte('c'.code, 112)).toHex())
    }

    @Test
    fun sha512KnownAnswers() {
        assertEquals(empty512, sha512(ascii("")).toHex())
        assertEquals(abc512, sha512(ascii("abc")).toHex())
        assertEquals(e111v512, sha512(repeatedByte('b'.code, 111)).toHex())
        assertEquals(e112v512, sha512(repeatedByte('c'.code, 112)).toHex())
    }

    @Test
    fun incrementalMatchesOneShot() {
        // Feeding "ab" then "c" must equal hashing "abc" in one shot (streaming contract).
        val inc384 =
            Sha384Digest().update(ascii("ab")).update(ascii("c")).let { d ->
                BufferFactory.Default.allocate(SHA384_DIGEST_BYTES).also {
                    d.digestInto(it)
                    it.resetForRead()
                }
            }
        assertEquals(abc384, inc384.toHex())
        val inc512 =
            Sha512Digest().update(ascii("ab")).update(ascii("c")).let { d ->
                BufferFactory.Default.allocate(SHA512_DIGEST_BYTES).also {
                    d.digestInto(it)
                    it.resetForRead()
                }
            }
        assertEquals(abc512, inc512.toHex())
    }

    @Test
    fun digestIsInputSensitive() {
        // One-bit input change must change the digest (negative/avalanche sanity).
        assertTrue(sha512(ascii("abc")).toHex() != sha512(ascii("abd")).toHex())
        assertTrue(sha384(ascii("abc")).toHex() != sha384(ascii("abd")).toHex())
    }

    @Test
    fun destTooSmallThrows() {
        assertFailsWith<IllegalArgumentException> {
            sha512(ascii("abc"), BufferFactory.Default.allocate(SHA512_DIGEST_BYTES - 1))
        }
        assertFailsWith<IllegalArgumentException> {
            sha384(ascii("abc"), BufferFactory.Default.allocate(SHA384_DIGEST_BYTES - 1))
        }
    }
}
