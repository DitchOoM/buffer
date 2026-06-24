package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.repeatedByte
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlin.test.Test
import kotlin.test.assertEquals

/** RFC 5869 known-answer vectors for HKDF-SHA256. */
class HkdfTest {
    private fun derive(
        salt: ReadBuffer?,
        ikm: ReadBuffer,
        info: ReadBuffer?,
        length: Int,
    ): String = Hkdf.derive(salt.toSalt(), ikm, info.toInfo(), length, BufferFactory.Default).toHex()

    @Test
    fun rfc5869Case1Extract() {
        val prk = BufferFactory.Default.allocate(HMAC_SHA256_BYTES)
        Hkdf.extractInto(salt = Salt.Of(hexBuffer("000102030405060708090a0b0c")), ikm = repeatedByte(0x0b, 22), dest = prk)
        prk.resetForRead()
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", prk.toHex())
    }

    @Test
    fun rfc5869Case1Expand() {
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            derive(
                salt = hexBuffer("000102030405060708090a0b0c"),
                ikm = repeatedByte(0x0b, 22),
                info = hexBuffer("f0f1f2f3f4f5f6f7f8f9"),
                length = 42,
            ),
        )
    }

    @Test
    fun rfc5869Case3EmptySaltAndInfo() {
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            derive(salt = null, ikm = repeatedByte(0x0b, 22), info = null, length = 42),
        )
    }

    @Test
    fun expandProducesRequestedLength() {
        assertEquals(1, Hkdf.derive(Salt.None, repeatedByte(1, 32), Info.None, 1, BufferFactory.Default).remaining())
        assertEquals(12, Hkdf.derive(Salt.None, repeatedByte(1, 32), Info.None, 12, BufferFactory.Default).remaining())
        assertEquals(80, Hkdf.derive(Salt.None, repeatedByte(1, 32), Info.None, 80, BufferFactory.Default).remaining())
    }
}
