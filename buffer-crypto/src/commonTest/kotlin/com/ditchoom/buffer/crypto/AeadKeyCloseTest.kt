package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.managed
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The AEAD key wrappers are [AutoCloseable] for parity with [SigningKey] and
 * [KeyAgreementPrivateKey]. Closing must zero the backing key material and be idempotent.
 *
 * A managed (heap) secure factory is used so the backing buffer stays readable AFTER close()
 * (managed free is a GC no-op), letting the test observe the wipe in isolation — the same
 * technique [SecureBufferTest] uses.
 */
class AeadKeyCloseTest {
    private val secureManaged = BufferFactory.managed().secure()

    private val aes128Key = "11754cd72aec309bf52f7687212e8957"
    private val aes256Key = "feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308"
    private val chachaKey = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"

    @Test
    fun aesGcmKeyCloseWipesMaterial() {
        val key = AesGcmKey.of(hexBuffer(aes128Key), secureManaged)
        val len = key.material.limit()
        assertEquals(AES_128_KEY_BYTES, len)
        // Material is non-zero before close (it is the key bytes).
        var anyNonZero = false
        for (i in 0 until len) if (key.material.get(i).toInt() != 0) anyNonZero = true
        assertEquals(true, anyNonZero, "key material should be non-zero before close")
        key.close()
        for (i in 0 until len) assertEquals(0, key.material.get(i).toInt(), "byte $i not wiped")
    }

    @Test
    fun aesGcm256KeyCloseWipesMaterial() {
        val key = AesGcmKey.of(hexBuffer(aes256Key), secureManaged)
        val len = key.material.limit()
        assertEquals(AES_256_KEY_BYTES, len)
        key.close()
        for (i in 0 until len) assertEquals(0, key.material.get(i).toInt(), "byte $i not wiped")
    }

    @Test
    fun chaChaPolyKeyCloseWipesMaterial() {
        val key = ChaChaPolyKey.of(hexBuffer(chachaKey), secureManaged)
        val len = key.material.limit()
        assertEquals(CHACHA_KEY_BYTES, len)
        key.close()
        for (i in 0 until len) assertEquals(0, key.material.get(i).toInt(), "byte $i not wiped")
    }

    @Test
    fun aesGcmKeyCloseIsIdempotent() {
        val key = AesGcmKey.of(hexBuffer(aes128Key), secureManaged)
        key.close()
        key.close() // second close must not throw
        for (i in 0 until key.material.limit()) assertEquals(0, key.material.get(i).toInt())
    }

    @Test
    fun chaChaPolyKeyCloseIsIdempotent() {
        val key = ChaChaPolyKey.of(hexBuffer(chachaKey), secureManaged)
        key.close()
        key.close()
        for (i in 0 until key.material.limit()) assertEquals(0, key.material.get(i).toInt())
    }
}
