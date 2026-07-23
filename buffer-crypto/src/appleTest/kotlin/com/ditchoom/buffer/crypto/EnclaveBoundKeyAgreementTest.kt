package com.ditchoom.buffer.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * User-authentication-bound Secure Enclave ECDH — the Apple half of biometric key agreement.
 *
 * What runs headless (and does here, on real Enclave hardware): the Enclave *advertises* per-derive
 * agreement ([PerUseAgreementCapable]), and an OS-bound (`SecAccessControl`) agreement key both
 * generates and reports hardware custody. Generation does not prompt — only a derive does — so these
 * need no human.
 *
 * The **positive** path (authenticate → derive succeeds; per-use prompts every time; session prompts
 * once per window) drives a real Touch ID / Face ID prompt and is therefore attended: it is covered
 * in the manual device session, not here. This test never triggers a prompt.
 */
class EnclaveBoundKeyAgreementTest {
    private fun authenticator() = LocalAuthAuthenticator(reason = "unit test bound agreement")

    /** The Enclave provider narrowed to user-auth capability, or null where it cannot be driven. */
    private fun boundProvider(): PerUseAgreementCapable? {
        val auth = authenticator().takeIf { it.available } ?: return null
        return appleEnclaveProviderOrNull()?.userAuthenticated(auth) as? PerUseAgreementCapable
    }

    @Test
    fun enclaveProviderAdvertisesPerDeriveAgreement() {
        val provider = appleEnclaveProviderOrNull() ?: return
        val auth = authenticator()
        if (!auth.available) return
        // Unlike Android (window-only), the Enclave binds authentication to the exact derive, so the
        // Apple user-auth provider IS a PerUseAgreementCapable — the narrowing a consumer relies on.
        assertTrue(
            provider.userAuthenticated(auth) is PerUseAgreementCapable,
            "the Enclave user-auth provider must advertise per-derive agreement",
        )
    }

    @Test
    fun sessionBoundAgreementKeyGeneratesWithHardwareCustody() =
        runTest {
            val provider = boundProvider() ?: return@runTest
            if (!provider.eligible(ProtectedKeyAlgorithm.EcdhP256)) return@runTest
            // Generating an access-controlled Enclave agreement key does not prompt (only a derive
            // does), so this exercises enclaveGenerateKaP256AccessControlled on real hardware headless.
            val policy = UserAuthenticationPolicy.Session(5.minutes)
            val pair = provider.generateKeyAgreement(KeyAgreementCurve.P256, policy)
            try {
                assertEquals(KeyProvenance.Hardware, pair.privateKey.provenance)
                assertFailsWith<UnsupportedOperationException>("an Enclave scalar is never exportable") {
                    pair.privateKey.exportEncoded()
                }
            } finally {
                pair.close()
            }
        }

    @Test
    fun perUseBoundAgreementKeyGeneratesWithHardwareCustody() =
        runTest {
            val provider = boundProvider() ?: return@runTest
            if (!provider.eligible(ProtectedKeyAlgorithm.EcdhP256)) return@runTest
            val pair = provider.generateKeyAgreement(KeyAgreementCurve.P256, UserAuthenticationPolicy.PerUse())
            try {
                assertEquals(KeyProvenance.Hardware, pair.privateKey.provenance)
            } finally {
                pair.close()
            }
        }

    @Test
    fun boundAgreementRejectsIneligibleCurve() =
        runTest {
            val provider = boundProvider() ?: return@runTest
            assertFailsWith<HardwareKeyException.AlgorithmNotEligible> {
                provider.generateKeyAgreement(KeyAgreementCurve.X25519, UserAuthenticationPolicy.Session(5.minutes))
            }
        }
}
