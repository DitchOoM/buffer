package com.ditchoom.buffer.crypto

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What the Apple stack must do where the Secure Enclave *cannot* serve ECDH — the other half of
 * "eligibility is probed, not assumed" (the hardware half is [EnclaveKeyAgreementTest]).
 *
 * Three states, each with its own honest answer: a curve the Enclave never backs (X25519) is
 * refused outright; an element whose generate-and-agree probe fails refuses rather than serving
 * ECDH at a silently weaker custody; and a target with no Enclave at all (the iOS simulator, an
 * unentitled binary) degrades to the software store — still functional, never claiming hardware.
 */
class EnclaveAgreementEligibilityTest {
    private val storeName = "bcks-kex-gate-${Random.nextInt(Int.MAX_VALUE)}"

    private fun store(): KeyStore = CryptoCapabilities.keyStore(KeyStoreConfig(name = storeName))

    @Test
    fun ineligibleCurvesAreRefused() =
        runTest {
            val provider = appleEnclaveProviderOrNull() ?: return@runTest
            // X25519 has no Secure Enclave backing on any OS version, whatever the P-256 probe said.
            assertTrue(!provider.eligible(ProtectedKeyAlgorithm.X25519), "X25519 is never Enclave-backed")
            assertFailsWith<HardwareKeyException.AlgorithmNotEligible> {
                provider.generateKeyAgreement(KeyAgreementCurve.X25519, ProtectedKeySpec())
            }
            assertFailsWith<HardwareKeyException.AlgorithmNotEligible> {
                store().getOrGenerateKeyAgreement("x25519-kex", KeyAgreementCurve.X25519)
            }
        }

    @Test
    fun anElementThatCannotAgreeRefusesRatherThanOverPromising() =
        runTest {
            val provider = appleEnclaveProviderOrNull() ?: return@runTest
            if (provider.eligible(ProtectedKeyAlgorithm.EcdhP256)) return@runTest
            // Enclave signing resolved but the generate-and-agree probe did not: ECDH must be refused
            // outright, never served at a silently weaker custody through the hardware store.
            assertFailsWith<HardwareKeyException.AlgorithmNotEligible> {
                provider.generateKeyAgreement(KeyAgreementCurve.P256, ProtectedKeySpec())
            }
            assertFailsWith<HardwareKeyException.AlgorithmNotEligible> {
                store().getOrGenerateKeyAgreement("unsupported-kex", KeyAgreementCurve.P256)
            }
        }

    @Test
    fun withoutAnEnclaveTheStoreDegradesToAnHonestSoftwareTier() =
        runTest {
            if (appleEnclaveProviderOrNull() != null) return@runTest
            // iOS simulator / unentitled binary: keyStore() falls back to the software store. Key
            // agreement still works there — it just must never claim hardware custody.
            val s = store()
            val alias = "degraded-kex"
            val pair = s.getOrGenerateKeyAgreement(alias, KeyAgreementCurve.P256)
            try {
                assertEquals(KeyProvenance.Software, pair.privateKey.provenance)
                assertTrue(
                    s.custodyFor(ProtectedKeyAlgorithm.EcdhP256).tier < CustodyTier.Hardware,
                    "a store without an Enclave must not report hardware custody",
                )
                assertFailsWith<InsufficientKeyCustody> {
                    s.requireTier(ProtectedKeyAlgorithm.EcdhP256, CustodyTier.Hardware)
                }
            } finally {
                pair.close()
                s.delete(alias)
            }
        }
}
