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
 * The namespaced facade objects ([Kex], [Hpke]) are thin delegates over the existing top-level
 * functions. These tests prove the delegation is wired correctly and behaves identically to the
 * top-level surface, on every platform (driving the async/cross-platform paths). AEAD and signatures
 * have no facade object — their operations live on the [Aead] / [OptionalAead] / [SignatureSupport]
 * capability witnesses, exercised via [aeadWitnessRoundTrips] / [signatureWitnessRoundTrips].
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
    fun signatureWitnessRoundTrips() =
        runTest {
            if (!ed25519AsyncAvailable()) return@runTest
            // RFC 8032 §7.1 test vector 1 (matching seed/public-key pair), driven through the witness.
            val seed = hexBuffer("9d61b19deffebc3a6f689b25f8a1ada92a2c4a26e3aa1bd2f60ba844af492ec2")
            val pub = hexBuffer("dacdbc0f4e3606de5619c8a565a6864275feddf264b11b130abc1167e4f5d034")
            val ops = signatureAsyncOrNull(SignatureScheme.Ed25519) ?: return@runTest
            val msg = ascii("witness signing")
            SigningKey.ed25519(seed).use { signing ->
                val sig = ops.sign(signing, msg, BufferFactory.Default)
                assertTrue(
                    ops.verify(VerifyKey.ed25519(pub), msg, sig),
                    "witness verify must accept witness signature",
                )
            }
        }

    @Test
    fun kexFacadeImportMatchesTopLevel() {
        // Key-agreement *operations* now live on the KeyAgreementSupport witness; the Kex facade only
        // carries the platform-independent key import. Prove Kex.importPrivateKey delegates to the
        // top-level importPrivateKey by deriving the same secret with each, against a shared peer.
        val curve = KeyAgreementCurve.P256
        if (!supportsRawScalarKat(curve) || !supportsSync(curve)) return
        val scalarHex = "7d7dc5f71eb29ddaf80d6214632eeae03d9058af1fb6d22ed80badb62bc1a534"
        val peer = generateKeyPair(curve)
        val info = ascii("kex-facade")
        val viaFacade = Kex.importPrivateKey(curve, hexBuffer(scalarHex))
        val viaTopLevel = importPrivateKey(curve, hexBuffer(scalarHex))
        try {
            val a = deriveSharedSecret(viaFacade, peer.publicKey, info, 32)
            val b = deriveSharedSecret(viaTopLevel, peer.publicKey, info, 32)
            assertEquals(b.toHex(), a.toHex())
        } finally {
            viaFacade.close()
            viaTopLevel.close()
            peer.close()
        }
    }

    @Test
    fun hpkeWitnessResolvesForSuite() {
        val suite =
            HpkeSuite(
                HpkeKem.DhkemX25519HkdfSha256,
                HpkeKdf.HkdfSha256,
                HpkeAead.ChaCha20Poly1305,
            )
        // The witness must resolve to one of the two variants, consistent with the hpkeSupported helper.
        when (CryptoCapabilities.hpke(suite)) {
            is HpkeSupport.Supported -> assertTrue(hpkeSupported(suite))
            is HpkeSupport.Unsupported -> assertTrue(!hpkeSupported(suite))
        }
    }

    @Test
    fun hpkeFacadeKeysFeedWitnessOps() =
        runTest {
            // The HPKE facade keeps key construction (generateKeyPair / import); the seal/open ops live
            // on the HpkeSupport witness. Prove the facade-built keys round-trip through the witness ops.
            val suite =
                HpkeSuite(
                    HpkeKem.DhkemX25519HkdfSha256,
                    HpkeKdf.HkdfSha256,
                    HpkeAead.Aes256Gcm,
                )
            val ops = (CryptoCapabilities.hpke(suite) as? HpkeSupport.Supported)?.ops ?: return@runTest
            val recipient = Hpke.generateKeyPair(suite.kem)
            val info = Info.Of(ascii("hpke-facade"))
            val pt = ascii("namespaced HPKE")
            val sealed = ops.sealBase(recipient.publicKey, info, pt)
            val opened = ops.openBase(recipient.privateKey, sealed.enc, info, sealed.ciphertext)
            assertEquals(pt.toHex(), opened.toHex())
            recipient.close()
        }
}
