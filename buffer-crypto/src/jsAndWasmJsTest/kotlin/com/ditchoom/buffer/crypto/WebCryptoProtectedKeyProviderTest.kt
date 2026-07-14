package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import com.ditchoom.buffer.use
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end tests for the WebCrypto non-exportable **software** key provider (js/wasmJs). Exercises
 * the real `subtle.generateKey(..., extractable = false, ...)` path and the gated closures the
 * generated keys carry: ECDSA sign→verify (P1363), self-framed AES-GCM seal→open, ECDH derive
 * agreeing with a software peer, and HPKE `openBase` composing with a non-exportable recipient key.
 * Also pins the custody a consumer sees through [CryptoCapabilities.keyProvider] and that a
 * hardware-tier requirement fails loudly on the web.
 *
 * Uses P-256 throughout (broadly available in every WebCrypto engine); X25519 support is
 * engine-dependent and covered by the feature-detection path elsewhere.
 */
class WebCryptoProtectedKeyProviderTest {
    private fun provider(): ProtectedKeyProvider =
        when (val support = CryptoCapabilities.protectedKeys) {
            is ProtectedKeySupport.Available -> support.provider
            ProtectedKeySupport.Unavailable -> fail("web exposes a non-exportable WebCrypto key provider")
        }

    private fun aesGcmOps(): AeadAsyncOps<AesGcmKey, SyncCapableAesGcmKey> =
        when (val aead = CryptoCapabilities.aesGcm) {
            is Aead.AsyncOnly -> aead.ops
            is Aead.Blocking -> aead.ops
        }

    @Test
    fun protectedKeysAvailableAsNonExportableSoftwareNotHardware() =
        runTest {
            val p = provider()
            assertEquals(KeyCustody.NonExportable.Software, p.custody)
            assertFalse(p.custody.exportable, "a WebCrypto key is non-exportable")
            assertEquals(KeyProvenance.Software, p.custody.provenance, "WebCrypto is software-backed, not a secure element")
            // The non-exportable-software tier must NOT masquerade as hardware.
            assertEquals(HardwareSupport.Unavailable, CryptoCapabilities.hardware)
        }

    @Test
    fun keyProviderRoutesEligibleAlgsToNonExportableSoftware() =
        runTest {
            val keys = CryptoCapabilities.keyProvider()
            val eligible =
                listOf(
                    ProtectedKeyAlgorithm.EcdsaP256,
                    ProtectedKeyAlgorithm.EcdsaP384,
                    ProtectedKeyAlgorithm.EcdsaP521,
                    ProtectedKeyAlgorithm.AesGcm,
                    ProtectedKeyAlgorithm.EcdhP256,
                    ProtectedKeyAlgorithm.EcdhP384,
                    ProtectedKeyAlgorithm.EcdhP521,
                )
            for (alg in eligible) {
                assertEquals(CustodyTier.NonExportableSoftware, keys.custodyFor(alg).tier, "$alg routes to WebCrypto")
            }
            // Ed25519 is not offered by the WebCrypto tier → the resolver falls to the software floor.
            assertEquals(CustodyTier.ExportableSoftware, keys.custodyFor(ProtectedKeyAlgorithm.Ed25519).tier)
        }

    @Test
    fun requireHardwareTierThrowsOnWeb() {
        // No secure element on the web: demanding Hardware fails loudly with a structured, non-secret error.
        val ex =
            assertFailsWith<InsufficientKeyCustody> {
                CryptoCapabilities.keyProvider().requireTier(ProtectedKeyAlgorithm.EcdsaP256, CustodyTier.Hardware)
            }
        assertEquals(CustodyTier.Hardware, ex.required)
        assertEquals(CustodyTier.NonExportableSoftware, ex.available)
    }

    @Test
    fun ecdsaNonExportableSignVerifies() =
        runTest {
            provider().generateSigning(SignatureScheme.EcdsaP256, ProtectedKeySpec()).use { sk ->
                assertEquals(KeyCustody.NonExportable.Software, sk.custody)
                assertEquals(SignatureScheme.EcdsaP256, sk.scheme)
                val message = ascii("non-exportable ECDSA-P256 message")
                val signature = signAsync(sk, message)
                // WebCrypto ECDSA is raw P1363 (r ‖ s, 64 bytes for P-256).
                assertEquals(64, signature.remaining(), "P-256 P1363 signature length")
                assertTrue(verifyAsync(sk.verifyKey, message, signature), "the non-exportable key's signature verifies")
                assertFalse(verifyAsync(sk.verifyKey, ascii("a different message"), signature), "a tampered message is rejected")
            }
        }

    @Test
    fun aesGcmNonExportableSealOpenRoundTrips() =
        runTest {
            val ops = aesGcmOps()
            provider().generateAesGcm(ProtectedKeySpec()).use { key ->
                assertEquals(KeyCustody.NonExportable.Software, key.custody)
                assertEquals(AES_256_KEY_BYTES * Byte.SIZE_BITS, key.sizeBits)
                val plaintext = ascii("the quick brown fox jumps over the lazy dog")
                val aad = ascii("associated data")
                // The JS-minted nonce is framed inside the sealed buffer (nonce ‖ ct ‖ tag).
                val sealed = ops.seal(key, plaintext, Aad.Of(aad), BufferFactory.Default)
                val opened = ops.open(sealed, key, Aad.Of(aad), BufferFactory.Default)
                assertEquals(plaintext.toHex(), opened.toHex(), "AES-256-GCM round-trips through the non-exportable key")
            }
        }

    @Test
    fun aesGcmNonExportableRejectsTamperedCiphertext() =
        runTest {
            val ops = aesGcmOps()
            provider().generateAesGcm(ProtectedKeySpec()).use { key ->
                val sealed = ops.seal(key, ascii("secret payload"), Aad.None, BufferFactory.Default)
                // Flip the last byte (inside the tag) into a fresh copy → the tag no longer authenticates.
                val tampered = flipByte(sealed, sealed.remaining() - 1)
                assertFailsWith<VerificationFailed> { ops.open(tampered, key, Aad.None, BufferFactory.Default) }
            }
        }

    @Test
    fun ecdhNonExportableDeriveMatchesSoftwarePeer() =
        runTest {
            val curve = KeyAgreementCurve.P256
            val protectedPair = provider().generateKeyAgreement(curve, ProtectedKeySpec())
            val softwarePeer = generateKeyPairAsync(curve)
            try {
                assertTrue(protectedPair.privateKey is ProtectedKeyAgreementPrivateKey, "the KA private key is non-exportable")
                assertEquals(KeyProvenance.Software, protectedPair.privateKey.provenance)
                val info = ascii("ecdh-p256 derive info")
                val salt = ascii("derive salt")
                // Non-exportable side derives via the gated closure; software side via the ordinary path.
                val fromProtected = deriveSharedSecretAsync(protectedPair.privateKey, softwarePeer.publicKey, info, 32, salt)
                val fromSoftware = deriveSharedSecretAsync(softwarePeer.privateKey, protectedPair.publicKey, info, 32, salt)
                assertEquals(
                    fromProtected.toHex(),
                    fromSoftware.toHex(),
                    "the non-exportable key and the software peer derive the same key",
                )
                // A non-exportable KA key exposes no scalar.
                assertFailsWith<UnsupportedOperationException> { protectedPair.privateKey.exportEncoded() }
            } finally {
                protectedPair.close()
                softwarePeer.close()
            }
        }

    @Test
    fun hpkeOpenBaseComposesWithNonExportableRecipient() =
        runTest {
            val kem = HpkeKem.DhkemP256HkdfSha256
            val suite = HpkeSuite(kem, HpkeKdf.HkdfSha256, HpkeAead.Aes128Gcm)
            val ops =
                when (val witness = CryptoCapabilities.hpke(suite)) {
                    is HpkeSupport.Supported -> witness.ops
                    is HpkeSupport.Unsupported -> fail("DHKEM(P-256) HPKE must be supported on web: ${witness.missing}")
                }
            val recipientPair = provider().generateKeyAgreement(kem.curve, ProtectedKeySpec())
            try {
                // Anyone can seal to the published recipient public key...
                val recipientPublic = hpkeImportPublicKey(kem, recipientPair.publicKey.encoded)
                val info = ascii("hpke non-exportable recipient")
                val keyToWrap = CryptoTestVectors.repeatedByte(0x2a, 32) // a 32-byte session key
                val aad = ascii("wrap aad")
                val sealed = ops.sealBase(recipientPublic, Info.Of(info), keyToWrap, Aad.Of(aad))

                // ...and only the holder of the non-exportable private key opens it — the recipient
                // scalar never enters the JS heap; the KEM decap DH runs through the gated closure.
                val recipientPrivate = hpkeRecipientPrivateKey(kem, recipientPair)
                val recovered = ops.openBase(recipientPrivate, sealed.enc, Info.Of(info), sealed.ciphertext, Aad.Of(aad))
                assertEquals(keyToWrap.toHex(), recovered.toHex(), "HPKE openBase composes with a non-exportable recipient key")
            } finally {
                recipientPair.close()
            }
        }

    /** A fresh read-ready copy of [source] with the byte at relative index [i] flipped (no ByteArray). */
    private fun flipByte(
        source: ReadBuffer,
        i: Int,
    ): ReadBuffer {
        val start = source.position()
        val n = source.remaining()
        val out = BufferFactory.Default.allocate(n)
        for (k in 0 until n) {
            val b = source.get(start + k)
            out.writeByte(if (k == i) (b.toInt() xor 0x01).toByte() else b)
        }
        out.resetForRead()
        return out
    }
}
