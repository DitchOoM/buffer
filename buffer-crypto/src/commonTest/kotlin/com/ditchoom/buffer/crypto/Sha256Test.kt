package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.repeatedByte
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

    @Test
    fun paddingBoundaries() {
        // Message lengths straddling the 64-byte block boundary and the 56-byte point where
        // the 8-byte length field forces an extra block — the classic SHA-256 padding edges.
        // Vectors are SHA-256 of byte 0x61 ('a') repeated N times. These exercise the
        // hand-rolled js/wasmJs core; JVM/Apple confirm the vectors against their system libs.
        val expected =
            mapOf(
                55 to "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318",
                56 to "b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a",
                57 to "f13b2d724659eb3bf47f2dd6af1accc87b81f09f59f2b75e5c0bed6589dfe8c6",
                63 to "7d3e74a05d7db15bce4ad9ec0658ea98e3f06eeecf16b4c6fff2da457ddc2f34",
                64 to "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb",
                65 to "635361c48bb9eab14198e76ea8ab7f1a41685d6ad62aa9146d301d4f17eb0ae0",
                119 to "31eba51c313a5c08226adf18d4a359cfdfd8d2e816b13f4af952f7ea6584dcfb",
                120 to "2f3d335432c70b580af0e8e1b3674a7c020d683aa5f73aaaedfdc55af904c21c",
            )
        for ((n, hex) in expected) {
            assertEquals(hex, sha256(repeatedByte(0x61, n)).toHex(), "SHA-256 of 'a'*$n")
        }
    }
}
