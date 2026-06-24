package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The namespaced facade objects ([Sign], [Kex], [Hpke]) are thin delegates over the existing
 * top-level functions. These tests prove the delegation is wired correctly and behaves identically
 * to the top-level surface, on every platform (driving the async/cross-platform paths). AEAD has no
 * facade object — its operations live on the [Aead] / [OptionalAead] capability witnesses, exercised
 * via [aeadWitnessRoundTrips].
 */
class CryptoFacadeTest {
    private val aes256Key = "feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308"

    @Test
    fun aeadWitnessRoundTrips() =
        runTest {
            val ops = aesGcmAsyncOps()
            val key = AesGcmKey.of(hexBuffer(aes256Key))
            val aad = Aad.Of(ascii("context-v1"))
            val plaintext = ascii("witness AEAD round-trip")
            val sealed = ops.seal(key, plaintext, aad, BufferFactory.Default)
            assertEquals(AEAD_NONCE_BYTES + plaintext.remaining() + AEAD_TAG_BYTES, sealed.remaining())
            val opened = ops.open(sealed, key, aad, BufferFactory.Default)
            assertEquals(plaintext.toHex(), opened.toHex())
        }

    @Test
    fun signFacadeMaxBytesMatchesTopLevel() {
        val schemes =
            listOf(
                SignatureScheme.Ed25519,
                SignatureScheme.EcdsaP256,
                SignatureScheme.EcdsaP384,
                SignatureScheme.EcdsaP521,
            )
        for (scheme in schemes) {
            assertEquals(maxSignatureBytes(scheme), Sign.maxSignatureBytes(scheme))
        }
    }

    @Test
    fun signFacadeRoundTrips() =
        runTest {
            if (!ed25519AsyncAvailable()) return@runTest
            // RFC 8032 §7.1 test vector 1 (matching seed/public-key pair).
            val seed = hexBuffer("9d61b19deffebc3a6f689b25f8a1ada92a2c4a26e3aa1bd2f60ba844af492ec2")
            val pub = hexBuffer("dacdbc0f4e3606de5619c8a565a6864275feddf264b11b130abc1167e4f5d034")
            val signing = SigningKey.ed25519(seed)
            val verify = VerifyKey.ed25519(pub)
            val msg = ascii("namespaced signing")
            val sig = Sign.signAsync(signing, msg, BufferFactory.Default)
            assertTrue(Sign.verifyAsync(verify, msg, sig), "facade verify must accept facade signature")
        }

    @Test
    fun kexFacadeDerivesSameSecretAsTopLevel() =
        runTest {
            val curve = KeyAgreementCurve.X25519
            val a = generateKeyPairAsync(curve)
            val b = generateKeyPairAsync(curve)
            val info = ascii("kex-facade")
            // Both sides derive the same shared secret; facade and top-level must agree.
            val viaFacade =
                Kex.deriveSharedSecretAsync(a.privateKey, b.publicKey, info, 32, null, BufferFactory.Default)
            val viaTopLevel =
                deriveSharedSecretAsync(b.privateKey, a.publicKey, info, 32, null, BufferFactory.Default)
            assertEquals(viaTopLevel.toHex(), viaFacade.toHex())
            a.close()
            b.close()
        }

    @Test
    fun hpkeFacadeSupportedMatchesTopLevel() {
        val suite =
            HpkeSuite(
                HpkeKem.DhkemX25519HkdfSha256,
                HpkeKdf.HkdfSha256,
                HpkeAead.ChaCha20Poly1305,
            )
        assertEquals(hpkeSupported(suite), Hpke.supported(suite))
    }

    @Test
    fun hpkeFacadeSealOpenRoundTrips() =
        runTest {
            val suite =
                HpkeSuite(
                    HpkeKem.DhkemX25519HkdfSha256,
                    HpkeKdf.HkdfSha256,
                    HpkeAead.Aes256Gcm,
                )
            if (!Hpke.supported(suite)) return@runTest
            val recipient = Hpke.generateKeyPair(suite.kem)
            val info = ascii("hpke-facade")
            val pt = ascii("namespaced HPKE")
            val sealed = Hpke.sealBase(suite, recipient.publicKey, info, pt, null, BufferFactory.Default)
            val opened =
                Hpke.openBase(
                    suite,
                    recipient.privateKey,
                    sealed.enc,
                    info,
                    sealed.ciphertext,
                    null,
                    BufferFactory.Default,
                )
            assertEquals(pt.toHex(), opened.toHex())
            recipient.close()
        }
}
