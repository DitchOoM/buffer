package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlinx.coroutines.test.runTest
import java.security.Security
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Persistent-alias conformance for [Tpm2Pkcs11KeyStore] — cert-wrapped entries on a PKCS#11 token,
 * runnable only against a configured module. Two required modes, matching Tpm2Pkcs11ProviderTest:
 *
 *  - `buffer.crypto.require.tpm2` (the swtpm CI job): the full signing persistence contract, plus
 *    the honest agreement refusal (tpm2-pkcs11 1.9 lacks `CKM_ECDH1_DERIVE`).
 *  - `buffer.crypto.require.p11.agreement` (the SoftHSM CI job): the same signing contract, plus the
 *    agreement persistence contract that tpm2-pkcs11 cannot yet exercise — proving the dormant path
 *    lights up correctly on a derive-capable module.
 *
 * Unconfigured machines skip (resolution-shape coverage lives in ProtectedKeyResolutionTest).
 *
 * "Process restart" is simulated with [freshProviderStore]: a brand-new SunPKCS11 provider instance
 * over the same module carries no session state, so an alias it can see and drive is a durable token
 * object, not an artifact of the resolved singleton's session.
 */
class Tpm2Pkcs11KeyStoreTest {
    private val requiredTpm = System.getProperty("buffer.crypto.require.tpm2") == "true"
    private val requiredAgreement = System.getProperty("buffer.crypto.require.p11.agreement") == "true"
    private val anyRequired get() = requiredTpm || requiredAgreement

    @Test
    fun signingAliasPersistsAcrossStoreAndProviderInstances() =
        runTest {
            if (!anyRequired) return@runTest
            val store = CryptoCapabilities.keyStore(KeyStoreConfig(name = "bckstest-signing"))
            try {
                val first = store.getOrGenerateSigning(ALIAS, SignatureScheme.EcdsaP256)
                assertIs<KeyCustody.NonExportable.Hardware>(first.custody)
                assertEquals(KeyProvenance.Hardware, first.provenance)
                val msg = hexBuffer("00112233445566778899aabbccddeeff")
                assertTrue(verifyAsync(first.verifyKey, msg, signAsync(first, msg)))

                assertTrue(store.contains(ALIAS))
                assertTrue(ALIAS in store.aliases())

                // Re-attach paths must drive the SAME token key: every signature verifies under the
                // first public key.
                val again = store.getOrGenerateSigning(ALIAS, SignatureScheme.EcdsaP256)
                assertTrue(verifyAsync(first.verifyKey, msg, signAsync(again, msg)))
                val reloaded = assertNotNull(store.loadSigning(ALIAS))
                assertTrue(verifyAsync(first.verifyKey, msg, signAsync(reloaded, msg)))

                val restarted = freshProviderStore("bckstest-signing")
                val survivor = assertNotNull(restarted.loadSigning(ALIAS))
                assertTrue(
                    verifyAsync(first.verifyKey, msg, signAsync(survivor, msg)),
                    "a fresh provider instance must reload the same token key",
                )

                assertTrue(store.delete(ALIAS))
                assertFalse(store.contains(ALIAS))
                assertNull(store.loadSigning(ALIAS))
                assertFalse(store.delete(ALIAS), "double delete answers false, never throws")
            } finally {
                store.delete(ALIAS)
            }
        }

    @Test
    fun kindRulesAndIneligibleAlgorithmsAreTyped() =
        runTest {
            if (!anyRequired) return@runTest
            val store = CryptoCapabilities.keyStore(KeyStoreConfig(name = "bckstest-kinds"))
            try {
                store.getOrGenerateSigning(ALIAS, SignatureScheme.EcdsaP256)
                // Wrong-kind loads answer null, never a mistyped handle.
                assertNull(store.loadKeyAgreement(ALIAS))
                assertNull(store.loadAesGcm(ALIAS))

                // What the token cannot hold is a typed refusal before any alias is touched.
                assertFailsWith<HardwareKeyException.AlgorithmNotEligible> { store.getOrGenerateAesGcm("untouched") }
                assertFailsWith<HardwareKeyException.AlgorithmNotEligible> {
                    store.getOrGenerateSigning("untouched", SignatureScheme.Ed25519)
                }
                assertFailsWith<HardwareKeyException.AlgorithmNotEligible> {
                    store.getOrGenerateKeyAgreement("untouched", KeyAgreementCurve.X25519)
                }
                assertFalse(store.contains("untouched"), "a refused get-or-generate must not create an entry")
            } finally {
                store.delete(ALIAS)
            }
        }

    @Test
    fun distinctStoreNamesNeverSeeEachOthersAliases() =
        runTest {
            if (!anyRequired) return@runTest
            val storeA = CryptoCapabilities.keyStore(KeyStoreConfig(name = "bckstest-ns-a"))
            val storeB = CryptoCapabilities.keyStore(KeyStoreConfig(name = "bckstest-ns-b"))
            try {
                val keyA = storeA.getOrGenerateSigning(ALIAS, SignatureScheme.EcdsaP256)
                val keyB = storeB.getOrGenerateSigning(ALIAS, SignatureScheme.EcdsaP256)
                val msg = hexBuffer("aa55aa55")
                // Same alias, different namespaces: two independent keys.
                assertFalse(verifyAsync(keyA.verifyKey, msg, signAsync(keyB, msg)))
                assertEquals(setOf(ALIAS), storeA.aliases())
                assertEquals(setOf(ALIAS), storeB.aliases())

                assertTrue(storeA.delete(ALIAS))
                assertFalse(storeA.contains(ALIAS))
                assertTrue(storeB.contains(ALIAS), "deleting in one namespace must not touch the other")
            } finally {
                storeA.delete(ALIAS)
                storeB.delete(ALIAS)
            }
        }

    @Test
    fun softwareStorageOverrideKeepsTheSoftwareTier() =
        runTest {
            if (!anyRequired) return@runTest
            // The escape hatch for what the token cannot hold: an explicit software medium wins over
            // the hardware store, with the honestly weaker custody.
            val store =
                CryptoCapabilities.keyStore(KeyStoreConfig(name = "bckstest-sw", storage = InMemoryKeyStorage()))
            assertEquals(KeyCustody.ExportableSoftware, store.custodyFor(ProtectedKeyAlgorithm.EcdsaP256))
            val aes = store.getOrGenerateAesGcm(ALIAS)
            assertEquals(KeyProvenance.Software, aes.provenance)
        }

    @Test
    fun agreementRemainsHonestlyRefusedWhereTheModuleLacksTheMechanism() =
        runTest {
            if (!requiredTpm) return@runTest
            val store = CryptoCapabilities.keyStore(KeyStoreConfig(name = "bckstest-refusal"))
            assertFalse(store.eligible(ProtectedKeyAlgorithm.EcdhP256))
            assertFailsWith<HardwareKeyException.AlgorithmNotEligible> {
                store.getOrGenerateKeyAgreement(KEX, KeyAgreementCurve.P256)
            }
            assertNull(store.loadKeyAgreement(KEX))
            assertFalse(store.contains(KEX))
        }

    @Test
    fun agreementLightsUpAndPersistsOnADeriveCapableModule() =
        runTest {
            if (!requiredAgreement) return@runTest
            val resolution = assertIs<ProtectedKeyResolution.Available>(CryptoCapabilities.protectedKeyResolution)
            assertTrue(
                resolution.provider.eligible(ProtectedKeyAlgorithm.EcdhP256),
                "a derive-capable module must light agreement eligibility up",
            )

            val store = CryptoCapabilities.keyStore(KeyStoreConfig(name = "bckstest-agree"))
            try {
                val ours = store.getOrGenerateKeyAgreement(KEX, KeyAgreementCurve.P256)
                assertEquals(KeyProvenance.Hardware, ours.privateKey.provenance)
                val peer = generateKeyPairAsync(KeyAgreementCurve.P256)
                val info = hexBuffer("6b6578")
                val fromPeer = deriveSharedSecretAsync(peer.privateKey, ours.publicKey, info, 32).toHex()
                assertEquals(fromPeer, deriveSharedSecretAsync(ours.privateKey, peer.publicKey, info, 32).toHex())

                // Reload and simulated restart both reconstruct the same token scalar: the derived
                // secret against the same peer cannot change.
                val reloaded = assertNotNull(store.loadKeyAgreement(KEX))
                assertEquals(fromPeer, deriveSharedSecretAsync(reloaded.privateKey, peer.publicKey, info, 32).toHex())
                val restarted = freshProviderStore("bckstest-agree")
                val survivor = assertNotNull(restarted.loadKeyAgreement(KEX))
                assertEquals(fromPeer, deriveSharedSecretAsync(survivor.privateKey, peer.publicKey, info, 32).toHex())

                // Kind mismatches, both directions.
                assertNull(store.loadSigning(KEX))
                val overAgreement =
                    assertFailsWith<KeyStoreException.AliasMismatch> {
                        store.getOrGenerateSigning(KEX, SignatureScheme.EcdsaP256)
                    }
                assertEquals(ProtectedKeyAlgorithm.EcdhP256, overAgreement.stored)
                store.getOrGenerateSigning(ALIAS, SignatureScheme.EcdsaP256)
                val overSigning =
                    assertFailsWith<KeyStoreException.AliasMismatch> {
                        store.getOrGenerateKeyAgreement(ALIAS, KeyAgreementCurve.P256)
                    }
                assertEquals(ProtectedKeyAlgorithm.EcdsaP256, overSigning.stored)
            } finally {
                store.delete(KEX)
                store.delete(ALIAS)
            }
        }

    /**
     * A [Tpm2Pkcs11KeyStore] over a brand-new provider instance and login — the closest a single
     * process can come to a restart: no session objects, no cached handles, only durable token state.
     */
    private fun freshProviderStore(name: String): KeyStore {
        val module = assertNotNull(System.getProperty("buffer.crypto.tpm2.pkcs11.module"))
        val pin = assertNotNull(System.getProperty("buffer.crypto.tpm2.pkcs11.pin"))
        val slot = System.getProperty("buffer.crypto.tpm2.pkcs11.slotIndex")?.toIntOrNull() ?: 0
        val p11 =
            Security
                .getProvider("SunPKCS11")
                .configure("--name=bckstest-fresh-${freshCount++}\nlibrary=$module\nslotListIndex=$slot\n")
        val keyStore = java.security.KeyStore.getInstance("PKCS11", p11)
        val pinChars = pin.toCharArray()
        try {
            keyStore.load(null, pinChars)
        } finally {
            pinChars.fill(' ')
        }
        return Tpm2Pkcs11KeyStore(Tpm2Pkcs11HardwareKeyProvider(p11, keyStore, module, slot), name)
    }

    private companion object {
        const val ALIAS = "device-identity"
        const val KEX = "device-kex"
        var freshCount = 0
    }
}
