package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.SignatureTestSupport.verifyHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Curated real Wycheproof vectors (C2SP `testvectors_v1`) for every scheme, run through verify:
 * `valid` must be accepted, `invalid` must be rejected. The `invalid` cases are the heart of the
 * security contract here — they exercise our rejection branch against non-canonical DER,
 * malleable / non-canonical S (Ed25519), `r=0`/`s=0`/`r,s≥n`, off-curve points, and truncated /
 * garbage signatures, which a KAT alone never touches.
 *
 * Both ECDSA encodings are tested: the DER (`..._test.json`) and P1363 (`..._p1363_test.json`)
 * vector files. The runner picks the right encoding per platform — DER on JVM/Android/Apple, P1363
 * on JS/WASM — so each platform is checked against the encoding it actually produces and consumes.
 */
class SignatureWycheproofTest {
    private suspend fun runEcdsa(
        scheme: SignatureScheme,
        derJson: String,
        p1363Json: String,
    ) {
        val vectorJson = if (ecdsaSignatureEncoding == EcdsaSignatureEncoding.Der) derJson else p1363Json
        val summary =
            SignatureWycheproof.run(vectorJson) { pk, msg, sig ->
                verifyHex(scheme, pk, msg, sig)
            }
        assertTrue(summary.total > 0, "expected curated ${scheme.schemeName} vectors to run")
    }

    @Test
    fun ed25519Vectors() =
        runTest {
            if (!ed25519AsyncAvailable()) return@runTest // engine without Ed25519; covered by capability test
            val summary =
                SignatureWycheproof.run(WycheproofVectorsEd25519.JSON) { pk, msg, sig ->
                    verifyHex(SignatureScheme.Ed25519, pk, msg, sig)
                }
            assertTrue(summary.total > 0, "expected curated Ed25519 vectors to run")
        }

    @Test
    fun ecdsaP256Vectors() =
        runTest {
            runEcdsa(
                SignatureScheme.EcdsaP256,
                WycheproofVectorsEcdsaP256.JSON,
                WycheproofVectorsEcdsaP256P1363.JSON,
            )
        }

    @Test
    fun ecdsaP384Vectors() =
        runTest {
            runEcdsa(
                SignatureScheme.EcdsaP384,
                WycheproofVectorsEcdsaP384.JSON,
                WycheproofVectorsEcdsaP384P1363.JSON,
            )
        }

    @Test
    fun ecdsaP521Vectors() =
        runTest {
            runEcdsa(
                SignatureScheme.EcdsaP521,
                WycheproofVectorsEcdsaP521.JSON,
                WycheproofVectorsEcdsaP521P1363.JSON,
            )
        }
}
