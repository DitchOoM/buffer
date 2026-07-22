package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * On-device conformance for the AndroidKeystore-backed [KeyStore] — the persistent
 * [KeyCustody.NonExportable.Hardware] tier that no host-JVM or emulator-less run can exercise (a
 * host JVM has no `AndroidKeyStore` provider, so `keyStore()` there resolves to the on-disk software
 * store instead). Runs under `connectedCheck`. The common [KeyStoreConformanceTest] covers the
 * software tier; this pins the hardware store's contract: get-or-generate idempotency, reload across
 * a simulated restart (a *fresh* `keyStore()` over the same [KeyStoreConfig.name]), delete,
 * alias-mismatch, that a persistent key's `close()` does NOT delete the entry, and that custody is
 * actually reported as hardware.
 *
 * Every test uses a unique alias and deletes it in [cleanup] so repeated `connectedCheck` runs on the
 * same device start clean (AndroidKeystore entries otherwise survive the process).
 */
class KeyStoreInstrumentedTest {
    private val storeName = "buffer-crypto-test"

    private fun store(): KeyStore = CryptoCapabilities.keyStore(KeyStoreConfig(name = storeName))

    private val touched = mutableListOf<String>()

    private fun alias(name: String): String = name.also { touched += it }

    @AfterTest
    fun cleanup() =
        runTest {
            val s = store()
            touched.forEach { s.delete(it) }
        }

    @Test
    fun keyStoreIsHardwareBacked() =
        runTest {
            val s = store()
            assertEquals(CustodyTier.Hardware, s.custodyFor(ProtectedKeyAlgorithm.EcdsaP256).tier)
            // The whole PR-289 custody machinery applies to a hardware KeyStore verbatim.
            assertEquals(s, s.requireTier(ProtectedKeyAlgorithm.EcdsaP256, CustodyTier.Hardware))
            // requireTier refuses an algorithm the store cannot hold rather than over-promising
            // hardware custody for it (the store backs only AES-GCM + ECDSA P-256).
            assertFailsWith<HardwareKeyException.AlgorithmNotEligible> {
                s.requireTier(ProtectedKeyAlgorithm.Ed25519, CustodyTier.Hardware)
            }
        }

    @Test
    fun signingIsIdempotentReloadsAcrossRestartAndSigns() =
        runTest {
            val a = alias("device-identity")
            val first = store().getOrGenerateSigning(a, SignatureScheme.EcdsaP256)
            assertEquals(KeyProvenance.Hardware, first.provenance)
            val pub = first.verifyKey.exportEncoded().toHex()

            // A second get-or-generate over a fresh store returns the SAME key, not a new one.
            val again = store().getOrGenerateSigning(a, SignatureScheme.EcdsaP256)
            assertEquals(pub, again.verifyKey.exportEncoded().toHex())

            // Simulated restart: a brand-new store instance re-attaches to the persisted key, which
            // signs, and the original public key verifies it.
            val reloaded = assertNotNull(store().loadSigning(a))
            assertEquals(KeyProvenance.Hardware, reloaded.provenance)
            val ops = assertNotNull(signatureAsyncOrNull(SignatureScheme.EcdsaP256))
            val msg = ascii("hardware key store signature")
            val sig = ops.sign(reloaded, msg)
            assertTrue(ops.verify(first.verifyKey, msg, sig), "original verifyKey must verify the reloaded key's signature")
        }

    @Test
    fun aesGcmReloadsAndOpensWhatTheOriginalSealed() =
        runTest {
            val a = alias("local-data")
            val original = store().getOrGenerateAesGcm(a)
            assertEquals(KeyProvenance.Hardware, original.provenance)
            val ops = aesGcmAsyncOps()
            val plaintext = ascii("open-me-after-restart")
            val sealed = ops.seal(original, plaintext, Aad.Of(ascii("ctx")))

            val reloaded = assertNotNull(store().loadAesGcm(a))
            val opened = ops.open(sealed, reloaded, Aad.Of(ascii("ctx")))
            assertEquals(plaintext.toHex(), opened.toHex(), "reloaded hw key must open what the original sealed")
        }

    @Test
    fun getOrGenerateThrowsAliasMismatchNeverSilentlyReplaces() =
        runTest {
            val a = alias("mixed")
            store().getOrGenerateAesGcm(a)
            val clash =
                assertFailsWith<KeyStoreException.AliasMismatch> {
                    store().getOrGenerateSigning(a, SignatureScheme.EcdsaP256)
                }
            assertEquals(ProtectedKeyAlgorithm.AesGcm, clash.stored)
            assertEquals(ProtectedKeyAlgorithm.EcdsaP256, clash.requested)
        }

    @Test
    fun persistentKeyCloseDoesNotDelete() =
        runTest {
            val a = alias("persist")
            val key = store().getOrGenerateSigning(a, SignatureScheme.EcdsaP256)
            key.close() // releases the in-process handle; the keystore entry must survive
            assertTrue(store().contains(a))
            assertNotNull(store().loadSigning(a))
        }

    @Test
    fun deleteRemovesAndAliasesReflectContents() =
        runTest {
            val sign = alias("sign")
            val enc = alias("enc")
            val s = store()
            s.getOrGenerateSigning(sign, SignatureScheme.EcdsaP256)
            s.getOrGenerateAesGcm(enc)
            assertTrue(s.aliases().containsAll(setOf(sign, enc)))
            assertTrue(s.contains(sign))

            assertTrue(s.delete(sign))
            assertFalse(s.contains(sign))
            assertNull(s.loadSigning(sign))
            assertFalse(s.delete(sign)) // second delete is a no-op
            assertFalse(s.aliases().contains(sign))
        }

    @Test
    fun ineligibleAlgorithmsAreRefused() =
        runTest {
            val s = store()
            // Ed25519 signing and X25519 agreement are never keystore-backed on any API level.
            assertFailsWith<HardwareKeyException.AlgorithmNotEligible> {
                s.getOrGenerateSigning(alias("ed"), SignatureScheme.Ed25519)
            }
            assertFailsWith<HardwareKeyException.AlgorithmNotEligible> {
                s.getOrGenerateKeyAgreement(alias("x-kex"), KeyAgreementCurve.X25519)
            }
            // ECDH P-256 is probed (PURPOSE_AGREE_KEY, API 31+): where the device lacks it, the
            // store must refuse rather than over-promise hardware custody. Where it holds, the
            // round-trip contract is pinned by keyAgreementIsIdempotentReloadsAcrossRestartAndDerives.
            if (!s.eligible(ProtectedKeyAlgorithm.EcdhP256)) {
                assertFailsWith<HardwareKeyException.AlgorithmNotEligible> {
                    s.getOrGenerateKeyAgreement(alias("kex"), KeyAgreementCurve.P256)
                }
            }
        }

    @Test
    fun keyAgreementIsIdempotentReloadsAcrossRestartAndDerives() =
        runTest {
            val s = store()
            // API-level-honest gate: pre-31 devices (the API-21 CI leg) exercise the refusal branch
            // in ineligibleAlgorithmsAreRefused; 31+ devices (the API-35 CI leg) exercise this one.
            if (!s.eligible(ProtectedKeyAlgorithm.EcdhP256)) return@runTest
            val a = alias("device-kex")
            val first = s.getOrGenerateKeyAgreement(a, KeyAgreementCurve.P256)
            assertEquals(KeyProvenance.Hardware, first.privateKey.provenance)
            assertEquals(CustodyTier.Hardware, s.custodyFor(ProtectedKeyAlgorithm.EcdhP256).tier)
            val pub = first.publicKey.exportSpki().toHex()

            // A second get-or-generate over a fresh store returns the SAME key, not a new one.
            val again = store().getOrGenerateKeyAgreement(a, KeyAgreementCurve.P256)
            assertEquals(pub, again.publicKey.exportSpki().toHex())

            // Simulated restart: a brand-new store re-attaches, and the reloaded handle derives the
            // same secret as a software peer does against the persisted public key — the keystore
            // ECDH is real, standard, and stable across reloads.
            val reloaded = assertNotNull(store().loadKeyAgreement(a))
            assertEquals(pub, reloaded.publicKey.exportSpki().toHex())
            val ops = assertNotNull(keyAgreementAsyncOrNull(KeyAgreementCurve.P256))
            val peer = ops.generateKeyPair()
            val info = Info.Of(ascii("keystore-kex"))
            val viaFirst = ops.deriveSharedSecret(first.privateKey, peer.publicKey, info, length = 32)
            val viaReloaded = ops.deriveSharedSecret(reloaded.privateKey, peer.publicKey, info, length = 32)
            val viaPeer = ops.deriveSharedSecret(peer.privateKey, reloaded.publicKey, info, length = 32)
            assertEquals(viaPeer.toHex(), viaFirst.toHex(), "hw derive must match the peer's derivation")
            assertEquals(viaPeer.toHex(), viaReloaded.toHex(), "reloaded hw key must derive the same secret")
        }

    @Test
    fun agreementAliasNeverSilentlyReplacesAnotherKind() =
        runTest {
            val s = store()
            if (!s.eligible(ProtectedKeyAlgorithm.EcdhP256)) return@runTest
            val a = alias("mixed-kex")
            s.getOrGenerateSigning(a, SignatureScheme.EcdsaP256)
            // Both kinds are keystore PrivateKeyEntries — the purpose-recorded kind must still win.
            val clash =
                assertFailsWith<KeyStoreException.AliasMismatch> {
                    s.getOrGenerateKeyAgreement(a, KeyAgreementCurve.P256)
                }
            assertEquals(ProtectedKeyAlgorithm.EcdsaP256, clash.stored)
            assertEquals(ProtectedKeyAlgorithm.EcdhP256, clash.requested)
            // And the kind-mismatched loads answer null, never a mistyped handle.
            assertNull(s.loadKeyAgreement(a))
            val kex = alias("kex-then-sign")
            s.getOrGenerateKeyAgreement(kex, KeyAgreementCurve.P256)
            assertNull(s.loadSigning(kex))
        }

    @Test
    fun invalidAliasesAreRejected() =
        runTest {
            val s = store()
            for (bad in listOf("", "bad/alias", "has space", "a".repeat(256))) {
                assertFailsWith<IllegalArgumentException>("alias '$bad' must be rejected") {
                    s.getOrGenerateSigning(bad, SignatureScheme.EcdsaP256)
                }
            }
        }
}
