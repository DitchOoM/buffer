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
    fun aesGcmWitnessResolves() {
        // The witness is the source of truth (the boolean flags are gone). AES-GCM has no Unavailable
        // variant — it is reachable (Blocking on native, AsyncOnly on the web) on every platform.
        when (CryptoCapabilities.aesGcm) {
            is Aead.Blocking -> Unit
            is Aead.AsyncOnly -> Unit
        }
    }

    @Test
    fun chaChaPolyWitnessResolves() {
        // The witness must resolve to one of the three variants on every platform.
        when (CryptoCapabilities.chaChaPoly) {
            is OptionalAead.Blocking -> Unit
            is OptionalAead.AsyncOnly -> Unit
            OptionalAead.Unavailable -> Unit
        }
    }

    @Test
    fun aesEcbWitnessResolves() {
        // Single-block AES resolves to a native Blocking path or Unavailable (web); the witness must
        // agree with the blocking-availability helper.
        when (CryptoCapabilities.aesEcb) {
            is AesEcb.Blocking -> assertTrue(aesEcbBlockingAvailable, "Blocking implies a sync path")
            AesEcb.Unavailable -> assertFalse(aesEcbBlockingAvailable, "Unavailable implies no sync path")
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
    fun keyAgreementWitnessResolvesForEveryCurve() {
        val curves =
            listOf(
                KeyAgreementCurve.X25519,
                KeyAgreementCurve.P256,
                KeyAgreementCurve.P384,
                KeyAgreementCurve.P521,
            )
        for (curve in curves) {
            // The witness must resolve to one of the three variants on every platform, and a Blocking
            // witness must agree with the sync helper.
            when (CryptoCapabilities.keyAgreement(curve)) {
                is KeyAgreementSupport.Blocking -> assertTrue(supportsSync(curve), "Blocking implies a sync path")
                is KeyAgreementSupport.AsyncOnly -> assertFalse(supportsSync(curve), "AsyncOnly implies no sync path")
                KeyAgreementSupport.Unavailable -> assertFalse(supportsSync(curve), "Unavailable implies no sync path")
            }
        }
        // ECDH P-curves are agreed on every supported platform (sync native or async WebCrypto), so
        // they are never Unavailable; X25519 may be absent on a provider/engine that lacks it.
        for (curve in listOf(KeyAgreementCurve.P256, KeyAgreementCurve.P384, KeyAgreementCurve.P521)) {
            assertTrue(CryptoCapabilities.keyAgreement(curve) != KeyAgreementSupport.Unavailable, "curve $curve")
        }
    }
}
