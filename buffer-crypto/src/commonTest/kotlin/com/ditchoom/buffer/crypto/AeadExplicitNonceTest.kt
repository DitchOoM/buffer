package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import com.ditchoom.buffer.pool.BufferPool
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Explicit-nonce AEAD tests, driven through the **public capability witness** ops
 * ([AeadAsyncOps.sealWithNonce] / [AeadAsyncOps.openWithNonce] and their blocking peers) rather
 * than the internal explicit-nonce seam. This proves the public surface: the caller-supplied nonce
 * reaches the primitive, the output is bare `ciphertext ‖ tag` with no nonce prefix, the KAT
 * ct+tag is bit-exact, a wrong nonce length is rejected, and a tampered ciphertext fails as the
 * opaque [VerificationFailed]. The per-backing bridge coverage of the underlying primitives lives
 * in [AeadBackingTests]; here a pooled/slice sweep confirms the witness ops delegate transparently.
 */
class AeadExplicitNonceTest {
    // NIST AES-128-GCM vector with AAD (shared with AeadTest / AeadBackingTests).
    private val aes128 =
        Vec(
            key = "feffe9928665731c6d6a8f9467308308",
            iv = "cafebabefacedbaddecaf888",
            aad = "feedfacedeadbeeffeedfacedeadbeefabaddad2",
            pt =
                "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721" +
                    "c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39",
            ct =
                "42831ec2217774244b7221b784d0d49ce3aa212f2c02a4e035c17e2329aca12e" +
                    "21d514b25466931c7d8f6a5aac84aa051ba30b396a0aac973d58e091",
            tag = "5bc94fbc3221a5db94fae95ae7121a47",
        )

    // NIST AES-128-GCM vector with empty plaintext and empty AAD (auth-only payload).
    private val aes128Empty =
        Vec(
            key = "11754cd72aec309bf52f7687212e8957",
            iv = "3c819d9a9bed087615030b65",
            aad = "",
            pt = "",
            ct = "",
            tag = "250327c674aaf477aef2675748cf6971",
        )

    // NIST AES-256-GCM vector with AAD.
    private val aes256 =
        Vec(
            key = "feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308",
            iv = "cafebabefacedbaddecaf888",
            aad = "feedfacedeadbeeffeedfacedeadbeefabaddad2",
            pt =
                "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721" +
                    "c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39",
            ct =
                "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
                    "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662",
            tag = "76fc6ece0f4e1768cddf8853bb2d551b",
        )

    // RFC 8439 §2.8.2 ChaCha20-Poly1305 vector.
    private val chacha =
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

    // --- AES-GCM: public async witness (available on every platform, incl. web) ---

    @Test
    fun aesGcmAsyncExplicitNonceKatAndRoundTrip() =
        runTest {
            val ops = aesGcmAsyncOps()
            for (v in listOf(aes128Empty, aes128, aes256)) {
                val key = AesGcmKey.of(hexBuffer(v.key))
                val sealed = ops.sealWithNonce(key, hexBuffer(v.iv), hexBuffer(v.pt), v.aad(), BufferFactory.Default)
                // Bare ct ‖ tag — no 12-byte nonce prefix — and bit-exact against the vector.
                assertEquals(v.ptLen() + AEAD_TAG_BYTES, sealed.remaining(), "no nonce framing (key=${v.keyBits()})")
                assertEquals(v.ct + v.tag, sealed.toHex(), "AES-GCM ct+tag (key=${v.keyBits()})")

                val opened =
                    ops.openWithNonce(hexBuffer(v.iv), hexBuffer(v.ct + v.tag), key, v.aad(), BufferFactory.Default)
                assertEquals(v.pt, opened.toHex(), "AES-GCM decrypt (key=${v.keyBits()})")
            }
        }

    // --- AES-GCM: public blocking witness (native only) ---

    @Test
    fun aesGcmBlockingExplicitNonceKatAndRoundTrip() {
        val ops = aesGcmBlockingOpsOrNull() ?: return
        for (v in listOf(aes128, aes256)) {
            val key = AesGcmKey.of(hexBuffer(v.key))
            val sealed =
                ops.sealWithNonceBlocking(key, hexBuffer(v.iv), hexBuffer(v.pt), v.aad(), BufferFactory.Default)
            assertEquals(v.ct + v.tag, sealed.toHex(), "AES-GCM blocking ct+tag (key=${v.keyBits()})")
            val opened =
                ops.openWithNonceBlocking(hexBuffer(v.iv), hexBuffer(v.ct + v.tag), key, v.aad(), BufferFactory.Default)
            assertEquals(v.pt, opened.toHex(), "AES-GCM blocking decrypt (key=${v.keyBits()})")
        }
    }

    // --- ChaCha20-Poly1305: public witness (capability-gated; unavailable on web) ---

    @Test
    fun chaChaPolyExplicitNonceKatAndRoundTrip() =
        runTest {
            val ops = chaChaPolyAsyncOrNull() ?: return@runTest
            val key = ChaChaPolyKey.of(hexBuffer(chacha.key))
            val sealed =
                ops.sealWithNonce(key, hexBuffer(chacha.iv), hexBuffer(chacha.pt), chacha.aad(), BufferFactory.Default)
            assertEquals(chacha.ptLen() + AEAD_TAG_BYTES, sealed.remaining(), "no nonce framing")
            assertEquals(chacha.ct + chacha.tag, sealed.toHex(), "ChaCha20-Poly1305 ct+tag")
            val opened =
                ops.openWithNonce(
                    hexBuffer(chacha.iv),
                    hexBuffer(chacha.ct + chacha.tag),
                    key,
                    chacha.aad(),
                    BufferFactory.Default,
                )
            assertEquals(chacha.pt, opened.toHex(), "ChaCha20-Poly1305 decrypt")
        }

    // --- Nonce-length discipline ---

    @Test
    fun rejectsWrongNonceLength() =
        runTest {
            val ops = aesGcmAsyncOps()
            val key = AesGcmKey.of(hexBuffer(aes128.key))
            val shortNonce = "cafebabefacedbaddecaf8" // 11 bytes
            val longNonce = "cafebabefacedbaddecaf888aa" // 13 bytes
            for (bad in listOf(shortNonce, longNonce)) {
                assertFailsWith<IllegalArgumentException>("seal nonce=${bad.length / 2}B") {
                    ops.sealWithNonce(key, hexBuffer(bad), hexBuffer(aes128.pt), aes128.aad(), BufferFactory.Default)
                }
                assertFailsWith<IllegalArgumentException>("open nonce=${bad.length / 2}B") {
                    ops.openWithNonce(
                        hexBuffer(bad),
                        hexBuffer(aes128.ct + aes128.tag),
                        key,
                        aes128.aad(),
                        BufferFactory.Default,
                    )
                }
            }
        }

    @Test
    fun rejectsCiphertextShorterThanTag() =
        runTest {
            val ops = aesGcmAsyncOps()
            val key = AesGcmKey.of(hexBuffer(aes128.key))
            // 15 bytes cannot even hold the 16-byte tag — rejected before the primitive runs.
            assertFailsWith<IllegalArgumentException> {
                ops.openWithNonce(
                    hexBuffer(aes128.iv),
                    hexBuffer("000102030405060708090a0b0c0d0e"),
                    key,
                    aes128.aad(),
                    BufferFactory.Default,
                )
            }
        }

    // --- Tamper: authentication failure is opaque ---

    @Test
    fun tamperedCiphertextFailsOpaque() =
        runTest {
            val ops = aesGcmAsyncOps()
            val key = AesGcmKey.of(hexBuffer(aes128.key))
            // Flip the low bit of the first ciphertext byte.
            val flipped = (aes128.ct.substring(0, 1).toInt(16) xor 0x1).toString(16) + aes128.ct.substring(1)
            assertFailsWith<VerificationFailed> {
                ops.openWithNonce(
                    hexBuffer(aes128.iv),
                    hexBuffer(flipped + aes128.tag),
                    key,
                    aes128.aad(),
                    BufferFactory.Default,
                )
            }
            // Wrong AAD must fail identically (same opaque type).
            assertFailsWith<VerificationFailed> {
                ops.openWithNonce(
                    hexBuffer(aes128.iv),
                    hexBuffer(aes128.ct + aes128.tag),
                    key,
                    Aad.Of(hexBuffer("00")),
                    BufferFactory.Default,
                )
            }
        }

    // --- Wrapper transparency: pooled/slice-backed nonce, plaintext, and AAD ---

    @Test
    fun explicitNonceIsWrapperTransparent() =
        runTest {
            val ops = aesGcmAsyncOps()
            val pool = BufferPool()
            val key = AesGcmKey.of(hexBuffer(aes128.key))
            for (kind in CryptoBackings.inputs) {
                val nonce = CryptoBackings.place(kind, hexBuffer(aes128.iv), pool)
                val pt = CryptoBackings.place(kind, hexBuffer(aes128.pt), pool)
                val aad = Aad.Of(CryptoBackings.place(kind, hexBuffer(aes128.aad), pool))
                val sealed = ops.sealWithNonce(key, nonce, pt, aad, BufferFactory.Default)
                assertEquals(aes128.ct + aes128.tag, sealed.toHex(), "seal via $kind-backed inputs")

                val nonce2 = CryptoBackings.place(kind, hexBuffer(aes128.iv), pool)
                val ct = CryptoBackings.place(kind, hexBuffer(aes128.ct + aes128.tag), pool)
                val aad2 = Aad.Of(CryptoBackings.place(kind, hexBuffer(aes128.aad), pool))
                val opened = ops.openWithNonce(nonce2, ct, key, aad2, BufferFactory.Default)
                assertEquals(aes128.pt, opened.toHex(), "open via $kind-backed inputs")
            }
            pool.clear()
        }

    private fun aesGcmBlockingOpsOrNull(): AeadBlockingOps<AesGcmKey, SyncCapableAesGcmKey>? =
        when (val w = CryptoCapabilities.aesGcm) {
            is Aead.Blocking -> w.ops
            is Aead.AsyncOnly -> null
        }

    private data class Vec(
        val key: String,
        val iv: String,
        val aad: String,
        val pt: String,
        val ct: String,
        val tag: String,
    ) {
        fun aad() = if (aad.isEmpty()) Aad.None else Aad.Of(hexBuffer(aad))

        fun ptLen() = pt.length / 2

        fun keyBits() = key.length * 4
    }
}
