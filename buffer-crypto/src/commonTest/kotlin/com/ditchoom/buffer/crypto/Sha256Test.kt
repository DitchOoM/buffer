package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlin.test.Test
import kotlin.test.assertEquals

/** NIST FIPS 180-4 known-answer vectors for SHA-256. */
class Sha256Test {
    @Test
    fun emptyString() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256(ascii("")).toHex(),
        )
    }

    @Test
    fun abc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256(ascii("abc")).toHex(),
        )
    }

    @Test
    fun twoBlockMessage() {
        // 56-byte message that forces a second padding block.
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256(ascii("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")).toHex(),
        )
    }

    @Test
    fun incrementalUpdateMatchesOneShot() {
        // Hashing "abc" in two updates must equal the one-shot digest.
        val out = BufferFactory.Default.allocate(SHA256_DIGEST_BYTES)
        Sha256Digest().update(ascii("a")).update(ascii("bc")).digestInto(out)
        out.resetForRead()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", out.toHex())
    }

    @Test
    fun updateIsNonDestructive() {
        val input = ascii("abc")
        sha256(input)
        // update() consumed a slice; the caller's position must be untouched.
        assertEquals(3, input.remaining())
    }
}
