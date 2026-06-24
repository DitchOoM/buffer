package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Known-answer vectors: RFC 7748 §5.2 (X25519) and a NIST CAVP ECDH P-256 vector. The library only
 * exposes the *derived* key (raw DH is never returned), so each KAT verifies the full pipeline:
 * import the known private scalar, build the known peer public point, derive, and compare against
 * the **independent** HKDF of the published raw shared secret. Equality proves both that our raw DH
 * equals the vector's `shared` and that the KDF stage is correct.
 *
 * Gated on [supportsRawScalarKat] — the vectors are raw private scalars, which only JCA imports
 * directly. Apple/web (different private encodings) get round-trip + Wycheproof coverage instead.
 */
class KeyAgreementKatTest {
    private val katInfo = "kat-info"
    private val katSalt = "kat-salt"

    /** Independent reference: HKDF-SHA256 of the published raw secret, the value the API must return. */
    private fun expectedDerived(
        rawSecretHex: String,
        length: Int,
    ): String {
        val out =
            Hkdf.derive(
                salt = CryptoTestVectors.ascii(katSalt),
                ikm = hexBuffer(rawSecretHex),
                info = CryptoTestVectors.ascii(katInfo),
                length = length,
                factory = BufferFactory.Default,
            )
        return out.toHex()
    }

    private fun runKat(
        curve: KeyAgreementCurve,
        privScalarHex: String,
        peerPublicHex: String,
        rawSecretHex: String,
    ) {
        if (!supportsRawScalarKat(curve)) return
        val priv = importPrivateKey(curve, hexBuffer(privScalarHex))
        try {
            val pub = KeyAgreementPublicKey.of(curve, hexBuffer(peerPublicHex))
            val derived: ReadBuffer =
                deriveSharedSecret(
                    privateKey = priv,
                    peerPublicKey = pub,
                    info = CryptoTestVectors.ascii(katInfo),
                    length = 32,
                    salt = CryptoTestVectors.ascii(katSalt),
                )
            assertEquals(expectedDerived(rawSecretHex, 32), derived.toHex(), "${curve.curveName} KAT")
        } finally {
            priv.close()
        }
    }

    @Test
    fun x25519Rfc7748Section5_2() {
        // RFC 7748 §5.2 first test vector.
        runKat(
            curve = KeyAgreementCurve.X25519,
            privScalarHex = "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4",
            peerPublicHex = "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c",
            rawSecretHex = "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
        )
    }

    @Test
    fun x25519Rfc7748Section5_2SecondVector() {
        runKat(
            curve = KeyAgreementCurve.X25519,
            privScalarHex = "4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d",
            peerPublicHex = "e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493",
            rawSecretHex = "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
        )
    }

    @Test
    fun ecdhP256NistVector() {
        // NIST CAVP ECDH (P-256), one published pair: private d (32B scalar), peer point 0x04‖X‖Y,
        // and the X-coordinate of the shared point as the raw secret.
        // d  (CAVS private key)
        val d = "7d7dc5f71eb29ddaf80d6214632eeae03d9058af1fb6d22ed80badb62bc1a534"
        // peer public X,Y (CAVS public key)
        val x = "700c48f77f56584c5cc632ca65640db91b6bacce3a4df6b42ce7cc838833d287"
        val y = "db71e509e3fd9b060ddb20ba5c51dcc5948d46fbf640dfe0441782cab85fa4ac"
        val shared = "46fc62106420ff012e54a434fbdd2d25ccc5852060561e68040dd7778997bd7b"
        runKat(
            curve = KeyAgreementCurve.P256,
            privScalarHex = d,
            peerPublicHex = "04$x$y",
            rawSecretHex = shared,
        )
    }
}
