package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Drives curated real Wycheproof `testvectors_v1` cases through the implementation. Because the
 * library never returns the raw DH output, a vector is "accepted" iff the derived key equals the
 * **independent** HKDF of the vector's published `shared` secret — proving raw DH + KDF together.
 *
 * Verdict mapping (see [Wycheproof]):
 *  - `valid` → must accept (derive succeeds and matches HKDF(shared)).
 *  - `invalid` → must reject (off-curve / infinity / bad encoding → [InvalidPublicKey] or a length
 *    [IllegalArgumentException]).
 *  - `acceptable` → recorded, not asserted by the runner. We additionally **promote** the X25519
 *    low-order / zero-shared-secret cases to a hard *reject* assertion (our stricter RFC 7748 §6.1
 *    policy), in [x25519ZeroSharedSecretIsRejected].
 *
 * Gated on [supportsRawScalarKat]: the vectors carry raw private scalars, importable only where
 * the platform key encoding is the raw scalar (JCA). Apple/web get round-trip coverage elsewhere.
 */
class KeyAgreementWycheproofTest {
    private val wpInfo = "wycheproof-info"
    private val wpSalt = "wycheproof-salt"

    private fun expectedDerived(
        sharedHex: String,
        length: Int,
    ): String {
        val out =
            Hkdf.derive(
                salt = CryptoTestVectors.ascii(wpSalt),
                ikm = hexBuffer(sharedHex),
                info = CryptoTestVectors.ascii(wpInfo),
                length = length,
                factory = BufferFactory.Default,
            )
        return out.toHex()
    }

    private fun acceptsVector(
        curve: KeyAgreementCurve,
        case: WycheproofCase,
    ): Boolean {
        val priv = importPrivateKey(curve, case.testHex("private"))
        return try {
            val pub = KeyAgreementPublicKey.of(curve, case.testHex("public"))
            val derived: ReadBuffer =
                deriveSharedSecret(
                    privateKey = priv,
                    peerPublicKey = pub,
                    info = CryptoTestVectors.ascii(wpInfo),
                    length = curve.sharedSecretBytes,
                    salt = CryptoTestVectors.ascii(wpSalt),
                )
            // For valid cases the published `shared` is the raw secret; accept iff KDF(shared) matches.
            val sharedHex = case.testHexOrNull("shared")?.toHex()
            if (sharedHex.isNullOrEmpty()) {
                true // no expected secret (some acceptable cases) — derive simply succeeded
            } else {
                derived.toHex() == expectedDerived(sharedHex, curve.sharedSecretBytes)
            }
        } finally {
            priv.close()
        }
    }

    private fun runFile(
        curve: KeyAgreementCurve,
        json: String,
    ) {
        if (!supportsRawScalarKat(curve)) return
        val summary = Wycheproof.run(json) { case -> acceptsVector(curve, case) }
        assertTrue(summary.total > 0, "${curve.curveName} curated Wycheproof vectors must run")
    }

    @Test fun x25519() = runFile(KeyAgreementCurve.X25519, WycheproofVectorsX25519.JSON)

    @Test fun ecdhP256() = runFile(KeyAgreementCurve.P256, WycheproofVectorsEcdhP256.JSON)

    @Test fun ecdhP256WebCryptoRawPoint() = runFile(KeyAgreementCurve.P256, WycheproofVectorsEcdhP256WebCrypto.JSON)

    @Test fun ecdhP384() = runFile(KeyAgreementCurve.P384, WycheproofVectorsEcdhP384.JSON)

    @Test fun ecdhP521() = runFile(KeyAgreementCurve.P521, WycheproofVectorsEcdhP521.JSON)

    /**
     * Security-policy promotion: every X25519 vector whose published shared secret is all-zero
     * (low-order / small-subgroup public point, RFC 7748 §6.1) MUST be rejected with
     * [InvalidPublicKey] — never silently produce a key from an attacker-controlled secret.
     */
    @Test
    fun x25519ZeroSharedSecretIsRejected() {
        val curve = KeyAgreementCurve.X25519
        if (!supportsRawScalarKat(curve)) return
        var checked = 0
        Wycheproof.run(WycheproofVectorsX25519.JSON) { case ->
            val shared = case.testHexOrNull("shared")?.toHex()
            if (shared != null && shared.isNotEmpty() && shared.all { it == '0' }) {
                // Zero-shared cases are all `acceptable` in the vectors; the runner records the
                // outcome (does not assert it), so returning `false` (reject) is consistent with our
                // stricter policy. The hard assertion is the assertFailsWith below.
                checked++
                val priv = importPrivateKey(curve, case.testHex("private"))
                try {
                    assertFailsWith<InvalidPublicKey>("tcId ${case.tcId}: zero-shared point must be rejected") {
                        deriveSharedSecret(
                            priv,
                            KeyAgreementPublicKey.of(curve, case.testHex("public")),
                            CryptoTestVectors.ascii(wpInfo),
                            32,
                            CryptoTestVectors.ascii(wpSalt),
                        )
                    }
                } finally {
                    priv.close()
                }
                false
            } else {
                // Non-zero cases: honour the spec verdict via the normal accept logic.
                acceptsVector(curve, case)
            }
        }
        assertTrue(checked > 0, "expected curated X25519 zero-shared-secret cases to exist")
    }
}
