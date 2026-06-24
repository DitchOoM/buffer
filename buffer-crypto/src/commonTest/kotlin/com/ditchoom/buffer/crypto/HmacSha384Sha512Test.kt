package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Known-answer vectors for HMAC-SHA384 / HMAC-SHA512. Covers RFC 4231 Case 2 and a key
 * longer than the 128-byte block (exercises the "hash the key first" path on every backend).
 */
class HmacSha384Sha512Test {
    private val jefe384 =
        "af45d2e376484031617f78d2b58a6b1b9c7ef464f5a01b47" +
            "e42ec3736322445e8e2240ca5e69e2c78b3239ecfab21649"
    private val jefe512 =
        "164b7a7bfcf819e2e395fbe73b56e0a387bd64222e831fd610270cd7ea250554" +
            "9758bf75c05a994a6d034f65f8f0e6fdcaeab1a34d4a6b4b636e070a38bce737"
    private val klong384 =
        "46804650ed802b963703b909887d57afcb0d97f6e56f1c10" +
            "8c55b6cc6e9f107c92f872d89b2cce41d391a7bbe01437d3"
    private val klong512 =
        "570e8c4da93a8bd928732d2fa577124f7bc1fea09d7b06209669452471de9888" +
            "817b9cb9657076a130e0ae62d25545cb725c1d3dd4edd7c878a23b104939536e"

    /** A 200-byte key (bytes 0,1,2,…,199), longer than the 128-byte block. */
    private fun longKey() =
        BufferFactory.Default.allocate(200).also { b ->
            for (i in 0 until 200) b.writeByte(i.toByte())
            b.resetForRead()
        }

    @Test
    fun rfc4231Case2() {
        val msg = "what do ya want for nothing?"
        assertEquals(jefe384, hmacSha384(ascii("Jefe"), ascii(msg)).toHex())
        assertEquals(jefe512, hmacSha512(ascii("Jefe"), ascii(msg)).toHex())
    }

    @Test
    fun keyLongerThanBlock() {
        val msg = "what do ya want for nothing?"
        assertEquals(klong384, hmacSha384(longKey(), ascii(msg)).toHex())
        assertEquals(klong512, hmacSha512(longKey(), ascii(msg)).toHex())
    }

    @Test
    fun incrementalMatchesOneShot() {
        val inc =
            HmacSha512Mac(ascii("Jefe")).update(ascii("what do ya want ")).update(ascii("for nothing?")).let { m ->
                BufferFactory.Default.allocate(HMAC_SHA512_BYTES).also {
                    m.doFinalInto(it)
                    it.resetForRead()
                }
            }
        assertEquals(jefe512, inc.toHex())
    }

    @Test
    fun destTooSmallThrows() {
        assertFailsWith<IllegalArgumentException> {
            hmacSha512(ascii("Jefe"), ascii("x"), BufferFactory.Default.allocate(HMAC_SHA512_BYTES - 1))
        }
        assertFailsWith<IllegalArgumentException> {
            hmacSha384(ascii("Jefe"), ascii("x"), BufferFactory.Default.allocate(HMAC_SHA384_BYTES - 1))
        }
    }
}
