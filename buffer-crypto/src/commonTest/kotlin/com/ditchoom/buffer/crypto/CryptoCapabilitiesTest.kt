package com.ditchoom.buffer.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The facade must stay a faithful alias of the underlying `supports*` source-of-truth flags — these
 * assertions fail if the two ever drift apart, on whatever platform the suite runs.
 */
class CryptoCapabilitiesTest {
    @Test
    fun aesGcmMirrorsUnderlyingFlags() {
        assertEquals(supportsSyncAesGcm, CryptoCapabilities.aesGcm.sync)
        assertEquals(supportsAesGcmAnyPath, CryptoCapabilities.aesGcm.anyPath)
    }

    @Test
    fun chaChaPolyMirrorsUnderlyingFlags() {
        assertEquals(supportsSyncChaChaPoly, CryptoCapabilities.chaChaPoly.sync)
        assertEquals(supportsChaChaPoly, CryptoCapabilities.chaChaPoly.anyPath)
    }

    @Test
    fun aeadDispatchMapsToTheRightPrimitive() {
        assertEquals(CryptoCapabilities.aesGcm, CryptoCapabilities.aead(HpkeAead.Aes128Gcm))
        assertEquals(CryptoCapabilities.aesGcm, CryptoCapabilities.aead(HpkeAead.Aes256Gcm))
        assertEquals(CryptoCapabilities.chaChaPoly, CryptoCapabilities.aead(HpkeAead.ChaCha20Poly1305))
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
