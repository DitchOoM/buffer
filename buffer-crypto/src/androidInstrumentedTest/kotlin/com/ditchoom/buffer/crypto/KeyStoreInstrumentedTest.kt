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
            // Only ECDSA P-256 signing is hardware-backed here; other schemes / key agreement refuse.
            assertFailsWith<HardwareKeyException.AlgorithmNotEligible> {
                s.getOrGenerateSigning(alias("ed"), SignatureScheme.Ed25519)
            }
            assertFailsWith<HardwareKeyException.AlgorithmNotEligible> {
                s.getOrGenerateKeyAgreement(alias("kex"), KeyAgreementCurve.P256)
            }
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
