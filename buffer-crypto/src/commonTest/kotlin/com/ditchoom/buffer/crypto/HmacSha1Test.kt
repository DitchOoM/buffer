package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.repeatedByte
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlin.test.Test
import kotlin.test.assertEquals

/** RFC 2202 known-answer vectors for HMAC-SHA1. */
class HmacSha1Test {
    @Test
    fun case1() {
        // key = 0x0b x20, data = "Hi There"
        assertEquals(
            "b617318655057264e28bc0b6fb378c8ef146be00",
            hmacSha1(repeatedByte(0x0b, 20), ascii("Hi There")).toHex(),
        )
    }

    @Test
    fun case2() {
        // key = "Jefe", data = "what do ya want for nothing?"
        assertEquals(
            "effcdf6ae5eb2fa2d27416d5f184df9c259a7c79",
            hmacSha1(ascii("Jefe"), ascii("what do ya want for nothing?")).toHex(),
        )
    }

    @Test
    fun case3LongData() {
        // key = 0xaa x20, data = 0xdd x50
        assertEquals(
            "125d7342b9ac11cd91a39af48aa17b4f63f175d3",
            hmacSha1(repeatedByte(0xaa, 20), repeatedByte(0xdd, 50)).toHex(),
        )
    }

    @Test
    fun case6LongKeyIsHashed() {
        // key = 0xaa x80 (longer than the 64-byte block → key gets pre-hashed)
        assertEquals(
            "aa4ae5e15272d00e95705637ce8a3b55ed402112",
            hmacSha1(
                repeatedByte(0xaa, 80),
                ascii("Test Using Larger Than Block-Size Key - Hash Key First"),
            ).toHex(),
        )
    }

    @Test
    fun streamedMessageMatchesOneShot() {
        val out = BufferFactory.Default.allocate(HMAC_SHA1_BYTES)
        HmacSha1Mac(repeatedByte(0x0b, 20)).update(ascii("Hi ")).update(ascii("There")).doFinalInto(out)
        out.resetForRead()
        assertEquals("b617318655057264e28bc0b6fb378c8ef146be00", out.toHex())
    }
}
