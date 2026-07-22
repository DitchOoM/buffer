package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * On-token conformance for the tpm2-pkcs11-backed [HardwareKeyProvider] — the desktop-JVM hardware
 * tier that only runs against a real TPM or the swtpm CI harness.
 *
 * Two modes, selected by the `buffer.crypto.require.tpm2` system property (forwarded from the
 * Gradle `-P` property of the same name):
 *  - **required** (the swtpm CI job, or a locally configured rig): the resolution MUST be
 *    [ProtectedKeyResolution.Available] for [ProtectedKeyBackend.Tpm2Pkcs11], and the full contract
 *    is pinned — sign → verify round-trip, hardware custody, denied-gate, honest ECDH refusal
 *    (tpm2-pkcs11 1.9 advertises no `CKM_ECDH1_DERIVE`), and software-floor routing.
 *  - **unconfigured** (a plain dev machine / non-TPM CI leg): the resolution must still be a
 *    *typed* state that agrees with the frozen witnesses (covered in ProtectedKeyResolutionTest);
 *    the required-mode assertions are skipped, never silently faked.
 */
class Tpm2Pkcs11ProviderTest {
    private val required = System.getProperty("buffer.crypto.require.tpm2") == "true"

    @Test
    fun tpmBackendResolvesAndSignsWhenRequired() =
        runTest {
            if (!required) return@runTest
            val resolution =
                assertIs<ProtectedKeyResolution.Available>(
                    CryptoCapabilities.protectedKeyResolution,
                    "the swtpm harness is configured, so resolution must be Available",
                )
            assertEquals(ProtectedKeyBackend.Tpm2Pkcs11, resolution.backend)
            val provider = assertIs<HardwareKeyProvider>(resolution.provider)

            // The secure-element witness surfaces the TPM (issue: hardware never Available on JVM).
            val hw = assertIs<HardwareSupport.Available>(CryptoCapabilities.hardware)
            assertEquals(provider, hw.provider)

            // Sign on the token, verify under the captured public key via the ordinary witness ops.
            assertTrue(provider.eligible(ProtectedKeyAlgorithm.EcdsaP256))
            val signing = provider.generateSigning(SignatureScheme.EcdsaP256, ProtectedKeySpec())
            try {
                assertEquals(KeyProvenance.Hardware, signing.provenance)
                val custody = signing.custody
                assertIs<KeyCustody.NonExportable.Hardware>(custody)
                // PKCS#11 cannot confirm a discrete vs. firmware TPM; the claim stays conservative.
                assertFalse(
                    custody.dedicatedSecureElement,
                    "the TPM backend must not claim a confirmed dedicated element",
                )
                val ops = assertNotNull(signatureAsyncOrNull(SignatureScheme.EcdsaP256))
                val message = ascii("tpm2-backed identity signature")
                val signature = ops.sign(signing, message)
                assertTrue(
                    ops.verify(signing.verifyKey, message, signature),
                    "token-sign must verify under its public key",
                )
            } finally {
                signing.close()
            }

            // A denying advisory gate surfaces as AuthorizationFailed, exactly like the other backends.
            val denied =
                provider.generateSigning(
                    SignatureScheme.EcdsaP256,
                    ProtectedKeySpec(authorization = HardwareAuthorization { false }),
                )
            try {
                val ops = assertNotNull(signatureAsyncOrNull(SignatureScheme.EcdsaP256))
                assertFailsWith<AuthorizationFailed> { ops.sign(denied, ascii("denied")) }
            } finally {
                denied.close()
            }
        }

    @Test
    fun agreementIsHonestlyRefusedAndRoutedToTheSoftwareFloor() =
        runTest {
            if (!required) return@runTest
            val resolution = assertIs<ProtectedKeyResolution.Available>(CryptoCapabilities.protectedKeyResolution)
            val provider = resolution.provider

            // tpm2-pkcs11 1.9 advertises no CKM_ECDH1_DERIVE: the end-to-end probe must fail closed,
            // never over-promise. (When the module gains the mechanism, this flips via the probe —
            // at which point these assertions are the thing to update.)
            assertFalse(provider.eligible(ProtectedKeyAlgorithm.EcdhP256))
            assertFailsWith<HardwareKeyException.AlgorithmNotEligible> {
                provider.generateKeyAgreement(KeyAgreementCurve.P256, ProtectedKeySpec())
            }

            // The total resolver still serves agreement — from the software floor, with honest custody.
            val resolver = CryptoCapabilities.keyProvider()
            assertEquals(KeyCustody.ExportableSoftware, resolver.custodyFor(ProtectedKeyAlgorithm.EcdhP256))
            val routed = resolver.generateKeyAgreement(KeyAgreementCurve.P256, ProtectedKeySpec())
            try {
                assertEquals(KeyProvenance.Software, routed.privateKey.provenance)
            } finally {
                routed.close()
            }

            // requireTier: hardware signing holds; hardware agreement refuses with the typed custody error.
            resolver.requireTier(ProtectedKeyAlgorithm.EcdsaP256, CustodyTier.Hardware)
            assertFailsWith<InsufficientKeyCustody> {
                resolver.requireTier(ProtectedKeyAlgorithm.EcdhP256, CustodyTier.Hardware)
            }
        }
}
