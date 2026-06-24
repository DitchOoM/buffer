package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * AEAD known-answer + round-trip + capability tests.
 *
 * Every test drives the cross-platform async surface (`aesGcm*Async`, and the internal
 * explicit-nonce seam for vectors), which is the only surface available on the web, so the
 * same suite runs on JVM, JS, and WASM. The KAT vectors pin the exact ciphertext+tag so a
 * tag/keystream regression on any platform fails here; the negative discipline lives in
 * [AeadTamperTest] and the Wycheproof suite in [AeadWycheproofTest].
 */
class AeadTest {
    // --- AES-GCM NIST-style known-answer vectors (key, iv, aad, pt, ct, tag) ---

    // NIST gcmEncryptExtIV128: 128-bit key, 96-bit IV, empty pt/aad.
    private val aes128Empty =
        Vec(
            key = "11754cd72aec309bf52f7687212e8957",
            iv = "3c819d9a9bed087615030b65",
            aad = "",
            pt = "",
            ct = "",
            tag = "250327c674aaf477aef2675748cf6971",
        )

    // NIST gcmEncryptExtIV128 with plaintext + aad.
    private val aes128WithAad =
        Vec(
            key = "feffe9928665731c6d6a8f9467308308",
            iv = "cafebabefacedbaddecaf888",
            aad = "feedfacedeadbeeffeedfacedeadbeefabaddad2",
            pt =
                "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a31" +
                    "8a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39",
            ct =
                "42831ec2217774244b7221b784d0d49ce3aa212f2c02a4e035c17e2329ac" +
                    "a12e21d514b25466931c7d8f6a5aac84aa051ba30b396a0aac973d58e091",
            tag = "5bc94fbc3221a5db94fae95ae7121a47",
        )

    // NIST gcmEncryptExtIV256: 256-bit key.
    private val aes256WithAad =
        Vec(
            key = "feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308",
            iv = "cafebabefacedbaddecaf888",
            aad = "feedfacedeadbeeffeedfacedeadbeefabaddad2",
            pt =
                "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a31" +
                    "8a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39",
            ct =
                "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555" +
                    "d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662",
            tag = "76fc6ece0f4e1768cddf8853bb2d551b",
        )

    @Test
    fun aesGcmKatRoundTrips() =
        runTest {
            for (v in listOf(aes128Empty, aes128WithAad, aes256WithAad)) {
                val key = AesGcmKey.of(hexBuffer(v.key))
                val aad = if (v.aad.isEmpty()) null else hexBuffer(v.aad)
                // Encrypt under the vector's fixed IV and check ct+tag bit-for-bit.
                val sealed = aesGcmSealWithNonceAsync(key, hexBuffer(v.iv), aad, hexBuffer(v.pt), BufferFactory.Default)
                assertEquals(v.ct + v.tag, sealed.toHex(), "AES-GCM ct+tag for key=${v.key.length * 4}bit")
                // Decrypt the same ct+tag back to the plaintext.
                val opened =
                    aesGcmOpenWithNonceAsync(key, hexBuffer(v.iv), aad, hexBuffer(v.ct + v.tag), BufferFactory.Default)
                assertEquals(v.pt, opened.toHex(), "AES-GCM decrypt")
            }
        }

    @Test
    fun aesGcmSelfFramingRoundTrip() =
        runTest {
            val key = AesGcmKey.of(hexBuffer(aes128WithAad.key))
            val aad = ascii("context-v1")
            val plaintext = ascii("the quick brown fox jumps over the lazy dog")
            val sealed = aesGcmSealAsync(key, plaintext, aad, BufferFactory.Default)
            // nonce(12) ‖ ct(len) ‖ tag(16)
            assertEquals(AEAD_NONCE_BYTES + plaintext.remaining() + AEAD_TAG_BYTES, sealed.remaining())
            val opened = aesGcmOpenAsync(sealed, key, aad, BufferFactory.Default)
            assertEquals(plaintext.toHex(), opened.toHex())
        }

    @Test
    fun aesGcmFreshNonceEachSeal() =
        runTest {
            val key = AesGcmKey.of(hexBuffer(aes256WithAad.key))
            val pt = ascii("same plaintext")
            val a = aesGcmSealAsync(key, pt, null, BufferFactory.Default)
            val b = aesGcmSealAsync(key, pt, null, BufferFactory.Default)
            // The 12-byte nonce prefix must differ between two seals of the same plaintext.
            val aNonce = a.toHex().substring(0, AEAD_NONCE_BYTES * 2)
            val bNonce = b.toHex().substring(0, AEAD_NONCE_BYTES * 2)
            assertTrue(aNonce != bNonce, "CSPRNG nonce must differ across seals")
        }

    // --- ChaCha20-Poly1305: RFC 8439 §2.8.2 known-answer (capability-gated) ---

    private val chachaRfc8439 =
        Vec(
            key = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f",
            iv = "070000004041424344454647",
            aad = "50515253c0c1c2c3c4c5c6c7",
            pt =
                "4c616469657320616e642047656e746c656d656e206f662074686520636c6173" +
                    "73206f66202739393a204966204920636f756c64206f6666657220796f75206f" +
                    "6e6c79206f6e652074697020666f7220746865206675747572652c2073756e73" +
                    "637265656e20776f756c642062652069742e",
            ct =
                "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                    "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
                    "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                    "3ff4def08e4b7a9de576d26586cec64b6116",
            tag = "1ae10b594f09e26a7e902ecbd0600691",
        )

    @Test
    fun chaChaPolyCapabilityContract() =
        runTest {
            val key = ChaChaPolyKey.of(hexBuffer(chachaRfc8439.key))
            val aad = hexBuffer(chachaRfc8439.aad)
            if (supportsChaChaPoly) {
                // Self-framing round-trip (the framing seal generates its own nonce). The exact
                // RFC 8439 ct+tag is pinned separately in chaChaPolyExplicitNonceKat.
                val sealed = chaChaPolySeal(key, hexBuffer(chachaRfc8439.pt), aad, BufferFactory.Default)
                val opened = chaChaPolyOpen(sealed, key, aad, BufferFactory.Default)
                assertEquals(chachaRfc8439.pt, opened.toHex(), "ChaCha20-Poly1305 round-trip")
            } else {
                // Web: ChaCha is not in WebCrypto and never polyfilled — both surfaces must throw.
                assertFailsWith<UnsupportedOperationException> {
                    chaChaPolySeal(key, ascii("x"), aad, BufferFactory.Default)
                }
                assertFailsWith<UnsupportedOperationException> {
                    chaChaPolySealAsync(key, ascii("x"), aad, BufferFactory.Default)
                }
            }
        }

    @Test
    fun chaChaPolyExplicitNonceKat() {
        if (!supportsChaChaPoly) return
        val key = ChaChaPolyKey.of(hexBuffer(chachaRfc8439.key))
        val out = BufferFactory.Default.allocate(messageLen(chachaRfc8439.pt) + AEAD_TAG_BYTES)
        chaChaPolySeal(key, hexBuffer(chachaRfc8439.iv), hexBuffer(chachaRfc8439.aad), hexBuffer(chachaRfc8439.pt), out)
        out.resetForRead()
        assertEquals(chachaRfc8439.ct + chachaRfc8439.tag, out.toHex(), "ChaCha20-Poly1305 RFC 8439 ct+tag")
    }

    @Test
    fun aesGcmSyncCapabilityContract() {
        if (supportsSyncAesGcm) {
            val key = AesGcmKey.of(hexBuffer(aes128WithAad.key))
            val sealed = aesGcmSeal(key, ascii("hello"), null, BufferFactory.Default)
            val opened = aesGcmOpen(sealed, key, null, BufferFactory.Default)
            assertEquals(ascii("hello").toHex(), opened.toHex())
        } else {
            // Web: synchronous AES-GCM is unavailable (WebCrypto is async-only).
            assertFailsWith<UnsupportedOperationException> {
                aesGcmSeal(AesGcmKey.of(hexBuffer(aes128WithAad.key)), ascii("x"), null, BufferFactory.Default)
            }
        }
    }

    @Test
    fun keyLengthValidation() {
        assertFailsWith<IllegalArgumentException> { AesGcmKey.of(hexBuffer("00112233")) }
        assertFailsWith<IllegalArgumentException> {
            // 192-bit AES key is not supported by this wrapper (128/256 only).
            AesGcmKey.of(hexBuffer("000102030405060708090a0b0c0d0e0f1011121314151617"))
        }
        assertFailsWith<IllegalArgumentException> { ChaChaPolyKey.of(hexBuffer("00112233")) }
    }

    private fun messageLen(hex: String) = hex.length / 2

    private data class Vec(
        val key: String,
        val iv: String,
        val aad: String,
        val pt: String,
        val ct: String,
        val tag: String,
    )
}
