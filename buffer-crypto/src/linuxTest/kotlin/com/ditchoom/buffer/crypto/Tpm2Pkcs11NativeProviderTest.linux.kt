@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * On-token conformance for the Kotlin/Native tpm2-pkcs11-backed [HardwareKeyProvider] — the Linux
 * hardware tier that only runs against a real TPM or a software PKCS#11 rig. Two required modes,
 * selected by environment (Kotlin/Native has no system properties), mirroring the JVM twin:
 *
 *  - `BUFFER_CRYPTO_REQUIRE_TPM2=true` (the swtpm CI job): the resolution MUST be Available and the
 *    full signing contract is pinned — plus agreement's honest refusal (tpm2-pkcs11 1.9 advertises
 *    no `CKM_ECDH1_DERIVE`).
 *  - `BUFFER_CRYPTO_REQUIRE_P11_AGREEMENT=true` (the SoftHSM CI job): the signing contract plus the
 *    agreement contract that tpm2-pkcs11 cannot yet exercise, through the same dlopen bridge.
 *
 * Unconfigured machines skip; the typed resolution shape is covered by ProtectedKeyResolutionTest.
 */
class Tpm2Pkcs11NativeProviderTest {
    private val requiredTpm = envFlag("BUFFER_CRYPTO_REQUIRE_TPM2")
    private val requiredAgreement = envFlag("BUFFER_CRYPTO_REQUIRE_P11_AGREEMENT")
    private val anyRequired get() = requiredTpm || requiredAgreement

    @Test
    fun tokenBackendResolvesAndSignsWhenRequired() =
        runTest {
            if (!anyRequired) return@runTest
            val resolution =
                assertIs<ProtectedKeyResolution.Available>(
                    CryptoCapabilities.protectedKeyResolution,
                    "the PKCS#11 rig is configured, so resolution must be Available",
                )
            assertEquals(ProtectedKeyBackend.Tpm2Pkcs11, resolution.backend)
            val provider = assertIs<HardwareKeyProvider>(resolution.provider)

            // The secure-element witness surfaces the token (issue: hardware never Available on Linux).
            val hw = assertIs<HardwareSupport.Available>(CryptoCapabilities.hardware)
            assertEquals(provider, hw.provider)

            assertTrue(provider.eligible(ProtectedKeyAlgorithm.EcdsaP256))
            val signing = provider.generateSigning(SignatureScheme.EcdsaP256, ProtectedKeySpec())
            try {
                assertEquals(KeyProvenance.Hardware, signing.provenance)
                val custody = signing.custody
                assertIs<KeyCustody.NonExportable.Hardware>(custody)
                // PKCS#11 cannot confirm a discrete vs. firmware TPM; the claim stays conservative.
                assertFalse(custody.dedicatedSecureElement)
                val message = ascii("tpm2-pkcs11 native identity signature")
                val signature = signAsync(signing, message)
                assertTrue(
                    verifyAsync(signing.verifyKey, message, signature),
                    "token-sign must verify under its public key via the software witness",
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
                assertFailsWith<AuthorizationFailed> { signAsync(denied, ascii("denied")) }
            } finally {
                denied.close()
            }
        }

    @Test
    fun agreementIsHonestlyRefusedWhereTheModuleLacksTheMechanism() =
        runTest {
            if (!requiredTpm) return@runTest
            val resolution = assertIs<ProtectedKeyResolution.Available>(CryptoCapabilities.protectedKeyResolution)
            val provider = resolution.provider

            // tpm2-pkcs11 1.9 advertises no CKM_ECDH1_DERIVE: the end-to-end probe must fail closed.
            assertFalse(provider.eligible(ProtectedKeyAlgorithm.EcdhP256))
            assertFailsWith<HardwareKeyException.AlgorithmNotEligible> {
                provider.generateKeyAgreement(KeyAgreementCurve.P256, ProtectedKeySpec())
            }

            // The total resolver still serves agreement — from the software floor, with honest custody.
            val resolver = CryptoCapabilities.keyProvider()
            assertEquals(KeyCustody.ExportableSoftware, resolver.custodyFor(ProtectedKeyAlgorithm.EcdhP256))
            resolver.requireTier(ProtectedKeyAlgorithm.EcdsaP256, CustodyTier.Hardware)
            assertFailsWith<InsufficientKeyCustody> {
                resolver.requireTier(ProtectedKeyAlgorithm.EcdhP256, CustodyTier.Hardware)
            }
        }

    @Test
    fun agreementLightsUpOnADeriveCapableModule() =
        runTest {
            if (!requiredAgreement) return@runTest
            val resolution = assertIs<ProtectedKeyResolution.Available>(CryptoCapabilities.protectedKeyResolution)
            val provider = resolution.provider
            assertTrue(
                provider.eligible(ProtectedKeyAlgorithm.EcdhP256),
                "a derive-capable module must light agreement eligibility up",
            )

            val pair = provider.generateKeyAgreement(KeyAgreementCurve.P256, ProtectedKeySpec())
            try {
                assertEquals(KeyProvenance.Hardware, pair.privateKey.provenance)
                val peer = generateKeyPairAsync(KeyAgreementCurve.P256)
                val info = hexBuffer("6b6578")
                val viaToken = deriveSharedSecretAsync(pair.privateKey, peer.publicKey, info, SECRET_BYTES).toHex()
                val viaPeer = deriveSharedSecretAsync(peer.privateKey, pair.publicKey, info, SECRET_BYTES).toHex()
                assertEquals(viaPeer, viaToken, "token ECDH must match the software peer's derivation")

                // A well-formed but off-curve peer point is the uniform typed rejection — the module
                // (or TPM, which must validate per TPM2_ECDH_ZGen) refuses, never a raw CKR leak.
                val offCurve = KeyAgreementPublicKey.of(KeyAgreementCurve.P256, hexBuffer("04" + "ee".repeat(64)))
                assertNotNull(
                    assertFailsWith<InvalidPublicKey> {
                        deriveSharedSecretAsync(pair.privateKey, offCurve, info, SECRET_BYTES)
                    },
                )
            } finally {
                pair.close()
            }
        }
}

@OptIn(ExperimentalForeignApi::class)
private fun envFlag(name: String): Boolean = getenv(name)?.toKString() == "true"

private const val SECRET_BYTES = 32
