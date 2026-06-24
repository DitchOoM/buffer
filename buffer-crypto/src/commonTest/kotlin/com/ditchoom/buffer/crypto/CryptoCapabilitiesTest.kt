package com.ditchoom.buffer.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The capability facade / witnesses must stay faithful to the underlying `supports*`
 * source-of-truth flags — these assertions fail if the two ever drift apart, on whatever platform
 * the suite runs. AEAD capability is a witness ([Aead] / [OptionalAead]); its variant must match
 * the sync/availability flags.
 */
class CryptoCapabilitiesTest {
    @Test
    fun aesGcmWitnessMatchesSyncCapability() {
        when (CryptoCapabilities.aesGcm) {
            is Aead.Blocking -> assertTrue(supportsSyncAesGcm, "Blocking witness implies a sync path")
            is Aead.AsyncOnly -> assertFalse(supportsSyncAesGcm, "AsyncOnly witness implies no sync path")
        }
    }

    @Test
    fun chaChaPolyWitnessMatchesCapability() {
        when (CryptoCapabilities.chaChaPoly) {
            is OptionalAead.Blocking -> assertTrue(supportsChaChaPoly && supportsSyncChaChaPoly)
            is OptionalAead.AsyncOnly -> assertTrue(supportsChaChaPoly)
            OptionalAead.Unavailable -> assertFalse(supportsChaChaPoly)
        }
    }

    @Test
    fun signatureWitnessResolvesForEveryScheme() {
        val schemes =
            listOf(
                SignatureScheme.Ed25519,
                SignatureScheme.EcdsaP256,
                SignatureScheme.EcdsaP384,
                SignatureScheme.EcdsaP521,
            )
        for (scheme in schemes) {
            // The witness must resolve to one of the three variants on every platform.
            when (CryptoCapabilities.signatures(scheme)) {
                is SignatureSupport.Blocking -> Unit
                is SignatureSupport.AsyncOnly -> Unit
                SignatureSupport.Unavailable -> Unit
            }
        }
        // ECDSA verification is always available, so ECDSA is never Unavailable.
        for (scheme in listOf(SignatureScheme.EcdsaP256, SignatureScheme.EcdsaP384, SignatureScheme.EcdsaP521)) {
            assertTrue(CryptoCapabilities.signatures(scheme) != SignatureSupport.Unavailable)
        }
        // The signing-from-scalar key-construction capability still mirrors its flag.
        assertEquals(supportsEcdsaSigningFromScalar, CryptoCapabilities.ecdsaSigningFromScalar)
    }

    @Test
    fun keyAgreementMirrorsUnderlyingForEveryCurve() {
        val curves =
            listOf(
                KeyAgreementCurve.X25519,
                KeyAgreementCurve.P256,
                KeyAgreementCurve.P384,
                KeyAgreementCurve.P521,
            )
        for (curve in curves) {
            assertEquals(supportsSync(curve), CryptoCapabilities.keyAgreementSync(curve), "curve $curve")
        }
    }
}
