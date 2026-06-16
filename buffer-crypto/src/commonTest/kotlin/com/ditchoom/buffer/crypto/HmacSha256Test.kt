package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.repeatedByte
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlin.test.Test
import kotlin.test.assertEquals

/** RFC 4231 known-answer vectors for HMAC-SHA256. */
class HmacSha256Test {
    @Test
    fun case1() {
        // key = 0x0b x20, data = "Hi There"
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            hmacSha256(repeatedByte(0x0b, 20), ascii("Hi There")).toHex(),
        )
    }

    @Test
    fun case2() {
        // key = "Jefe", data = "what do ya want for nothing?"
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            hmacSha256(ascii("Jefe"), ascii("what do ya want for nothing?")).toHex(),
        )
    }

    @Test
    fun case6LongKeyIsHashed() {
        // key = 0xaa x131 (longer than the 64-byte block → key gets pre-hashed)
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            hmacSha256(
                repeatedByte(0xaa, 131),
                ascii("Test Using Larger Than Block-Size Key - Hash Key First"),
            ).toHex(),
        )
    }

    @Test
    fun streamedMessageMatchesOneShot() {
        val out = BufferFactory.Default.allocate(HMAC_SHA256_BYTES)
        HmacSha256Mac(repeatedByte(0x0b, 20)).update(ascii("Hi ")).update(ascii("There")).doFinalInto(out)
        out.resetForRead()
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", out.toHex())
    }
}
