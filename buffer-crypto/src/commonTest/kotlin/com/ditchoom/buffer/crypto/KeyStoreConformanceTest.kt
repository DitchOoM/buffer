package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Platform-agnostic conformance for the [SoftwareKeyStore] (the `ExportableSoftware` tier), driven
 * over an [InMemoryKeyStorage] medium so it runs identically on every target. It exercises
 * [SoftwareKeyStore] directly rather than `CryptoCapabilities.keyStore()`, because the platform store
 * varies by tier (web resolves to the non-exportable WebCrypto/IndexedDB store); the per-platform
 * `keyStore()` wiring is covered by the platform suites (`FileKeyStoreTest`, `WebCryptoKeyStoreTest`).
 * "Reload across launches" is simulated by opening a *fresh* store over the *same* storage instance
 * ([SoftwareKeyStore] is stateless over its medium). Uses only ECDSA-P256 / AES-256-GCM / ECDH-P256 —
 * available on every platform — so the suite never skips.
 */
class KeyStoreConformanceTest {
    private fun store(storage: KeyStorage): KeyStore = SoftwareKeyStore(storage)

    private fun aesOps() =
        when (val w = CryptoCapabilities.aesGcm) {
            is Aead.Blocking -> w.ops
            is Aead.AsyncOnly -> w.ops
        }

    @Test
    fun signingIsIdempotentAndReloadableAndSigns() =
        runTest {
            val s = InMemoryKeyStorage()
            val first = store(s).getOrGenerateSigning("device-identity", SignatureScheme.EcdsaP256)
            val pub = first.verifyKey.exportEncoded().toHex()
            assertEquals(KeyCustody.ExportableSoftware, first.custody)

            // A second get-or-generate over the same storage returns the SAME key, not a new one.
            val again = store(s).getOrGenerateSigning("device-identity", SignatureScheme.EcdsaP256)
            assertEquals(pub, again.verifyKey.exportEncoded().toHex())

            // The reloaded private key actually signs, and the original public key verifies it —
            // proving the persisted scalar reconstructs into a working keypair.
            val reloaded = assertNotNull(store(s).loadSigning("device-identity"))
            assertEquals(KeyCustody.ExportableSoftware, reloaded.custody)
            val msg = hexBuffer("00112233445566778899aabbccddeeff")
            val sig = signAsync(reloaded, msg)
            assertTrue(verifyAsync(first.verifyKey, msg, sig), "original verifyKey must verify the reloaded key's signature")
        }

    @Test
    fun aesGcmReconstructsAndOpensWhatTheOriginalSealed() =
        runTest {
            val s = InMemoryKeyStorage()
            val original = store(s).getOrGenerateAesGcm("local-data")
            assertEquals(KeyCustody.ExportableSoftware, original.custody)
            val plaintext = "6f70656e2d6d652d6166746572"
            val sealed = aesOps().seal(original, hexBuffer(plaintext))

            val reloaded = assertNotNull(store(s).loadAesGcm("local-data"))
            val opened = aesOps().open(sealed, reloaded)
            assertEquals(plaintext, opened.toHex())
        }

    @Test
    fun keyAgreementReconstructsToTheSameSharedSecret() =
        runTest {
            val s = InMemoryKeyStorage()
            val ours = store(s).getOrGenerateKeyAgreement("kex", KeyAgreementCurve.P256)
            val peer = generateKeyPairAsync(KeyAgreementCurve.P256)
            val info = hexBuffer("6b6578")

            val reloaded = assertNotNull(store(s).loadKeyAgreement("kex"))
            val fromReloaded = deriveSharedSecretAsync(reloaded.privateKey, peer.publicKey, info, 32).toHex()
            val fromPeer = deriveSharedSecretAsync(peer.privateKey, ours.publicKey, info, 32).toHex()
            assertEquals(fromReloaded, fromPeer)
        }

    @Test
    fun getOrGenerateThrowsAliasMismatchNeverSilentlyReplaces() =
        runTest {
            val s = InMemoryKeyStorage()
            store(s).getOrGenerateSigning("k", SignatureScheme.EcdsaP256)
            // Same kind, different scheme.
            val schemeClash =
                assertFailsWith<KeyStoreException.AliasMismatch> {
                    store(s).getOrGenerateSigning("k", SignatureScheme.EcdsaP384)
                }
            assertEquals(ProtectedKeyAlgorithm.EcdsaP256, schemeClash.stored)
            assertEquals(ProtectedKeyAlgorithm.EcdsaP384, schemeClash.requested)

            // Different kind entirely.
            store(s).getOrGenerateAesGcm("aes")
            val kindClash =
                assertFailsWith<KeyStoreException.AliasMismatch> {
                    store(s).getOrGenerateSigning("aes", SignatureScheme.EcdsaP256)
                }
            assertEquals(ProtectedKeyAlgorithm.AesGcm, kindClash.stored)
        }

    @Test
    fun loadOfTheWrongKindIsNullNotAnError() =
        runTest {
            val s = InMemoryKeyStorage()
            store(s).getOrGenerateAesGcm("aes")
            assertNull(store(s).loadSigning("aes"))
            assertNull(store(s).loadKeyAgreement("aes"))
            assertNull(store(s).loadSigning("never-stored"))
        }

    @Test
    fun persistentKeyCloseDoesNotDelete() =
        runTest {
            val s = InMemoryKeyStorage()
            val key = store(s).getOrGenerateSigning("id", SignatureScheme.EcdsaP256)
            key.close() // releases the in-memory handle; the stored key must survive
            assertNotNull(store(s).loadSigning("id"))
            assertTrue(store(s).contains("id"))
        }

    @Test
    fun deleteRemovesAndAliasesReflectContents() =
        runTest {
            val s = InMemoryKeyStorage()
            val st = store(s)
            st.getOrGenerateSigning("a", SignatureScheme.EcdsaP256)
            st.getOrGenerateAesGcm("b")
            assertEquals(setOf("a", "b"), st.aliases())
            assertTrue(st.contains("a"))

            assertTrue(st.delete("a"))
            assertFalse(st.contains("a"))
            assertNull(st.loadSigning("a"))
            assertFalse(st.delete("a")) // second delete is a no-op
            assertEquals(setOf("b"), st.aliases())
        }

    @Test
    fun custodyIsReportedAndRequireTierGuardsIt() =
        runTest {
            val st = store(InMemoryKeyStorage())
            assertEquals(CustodyTier.ExportableSoftware, st.custodyFor(ProtectedKeyAlgorithm.EcdsaP256).tier)
            // The whole PR-289 custody machinery applies to a KeyStore verbatim.
            assertFailsWith<InsufficientKeyCustody> {
                st.requireTier(ProtectedKeyAlgorithm.EcdsaP256, CustodyTier.Hardware)
            }
            // Software floor is met, so this passes and is chainable.
            assertEquals(st, st.requireTier(ProtectedKeyAlgorithm.EcdsaP256, CustodyTier.ExportableSoftware))
        }

    @Test
    fun invalidAesSizeIsRejectedWithoutPoisoningTheAlias() =
        runTest {
            val s = InMemoryKeyStorage()
            // An unsupported size must fail up front and persist nothing — otherwise the alias would be
            // durably poisoned (every later get/load re-throwing from AesGcmKey.of).
            assertFailsWith<IllegalArgumentException> {
                store(s).getOrGenerateAesGcm("k", ProtectedKeySpec(aesKeySizeBits = 192))
            }
            assertFalse(store(s).contains("k"))
            // The alias is untouched, so a subsequent valid request succeeds.
            val key = store(s).getOrGenerateAesGcm("k")
            assertEquals(KeyCustody.ExportableSoftware, key.custody)
        }

    @Test
    fun invalidAliasesAreRejected() =
        runTest {
            val st = store(InMemoryKeyStorage())
            for (bad in listOf("", "bad/alias", "has space", "a".repeat(256))) {
                assertFailsWith<IllegalArgumentException>("alias '$bad' must be rejected") {
                    st.getOrGenerateSigning(bad, SignatureScheme.EcdsaP256)
                }
            }
        }
}
