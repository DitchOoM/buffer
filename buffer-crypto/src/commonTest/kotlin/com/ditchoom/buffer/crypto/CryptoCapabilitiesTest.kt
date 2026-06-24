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
    fun signatureFlagsMirrorUnderlying() {
        assertEquals(supportsSyncEd25519, CryptoCapabilities.ed25519Sync)
        assertEquals(supportsSyncEcdsa, CryptoCapabilities.ecdsaSync)
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
