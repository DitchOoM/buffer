package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Known-answer tests for [KeyAgreementAsyncOps.deriveTlsPremasterSecret], the one public API that
 * returns the **raw** (EC)DHE shared secret (the TLS/DTLS premaster / HKDF-Extract IKM). Unlike
 * [KeyAgreementKatTest] — which can only oracle-check the raw DH *indirectly* through the KDF, since
 * [deriveSharedSecret] never returns it — these vectors assert the raw output byte-for-byte:
 *
 *  - RFC 7748 §5.2 X25519: the returned secret equals the vector's 32-byte shared value.
 *  - NIST CAVP ECDH P-256: the returned secret equals the big-endian X-coordinate of the shared point.
 *
 * The vectors are raw private scalars, so the KATs are gated on [supportsRawScalarKat] exactly as
 * [KeyAgreementKatTest] is; the rejection tests run wherever the curve's async path is available.
 */
class TlsPremasterSecretTest {
    /** Reads the premaster to hex and frees the caller-owned wiped [SecureBuffer]. */
    private fun PlatformBuffer.consumeHex(): String =
        try {
            toHex()
        } finally {
            freeNativeMemory()
        }

    private fun runPremasterKat(
        curve: KeyAgreementCurve,
        privScalarHex: String,
        peerPublicHex: String,
        rawSecretHex: String,
    ) = runTest {
        if (!supportsRawScalarKat(curve)) return@runTest
        val priv = importPrivateKey(curve, hexBuffer(privScalarHex))
        try {
            val pub = KeyAgreementPublicKey.of(curve, hexBuffer(peerPublicHex))
            val premaster = deriveTlsPremasterSecret(priv, pub)
            assertEquals(rawSecretHex, premaster.consumeHex(), "${curve.curveName} premaster")
        } finally {
            priv.close()
        }
    }

    @Test
    fun x25519Rfc7748Section5_2() {
        // RFC 7748 §5.2 first test vector — the raw X25519 output IS the returned premaster secret.
        runPremasterKat(
            curve = KeyAgreementCurve.X25519,
            privScalarHex = "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4",
            peerPublicHex = "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c",
            rawSecretHex = "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
        )
    }

    @Test
    fun ecdhP256NistVectorReturnsSharedXCoordinate() {
        // NIST CAVP ECDH (P-256): the TLS 1.2 premaster is exactly the shared point's X-coordinate.
        val d = "7d7dc5f71eb29ddaf80d6214632eeae03d9058af1fb6d22ed80badb62bc1a534"
        val x = "700c48f77f56584c5cc632ca65640db91b6bacce3a4df6b42ce7cc838833d287"
        val y = "db71e509e3fd9b060ddb20ba5c51dcc5948d46fbf640dfe0441782cab85fa4ac"
        val sharedX = "46fc62106420ff012e54a434fbdd2d25ccc5852060561e68040dd7778997bd7b"
        runPremasterKat(
            curve = KeyAgreementCurve.P256,
            privScalarHex = d,
            peerPublicHex = "04$x$y",
            rawSecretHex = sharedX,
        )
    }

    @Test
    fun premasterAndKdfPathAgreeOnTheSameRawSecret() {
        // The raw premaster and the KDF path must be consistent: HKDF over the returned premaster
        // equals what deriveSharedSecret produces for the same keys. Proves the narrow raw path and
        // the default derived path share one DH computation.
        val curve = KeyAgreementCurve.P256
        runTest {
            if (!supportsRawScalarKat(curve) || !supportsSync(curve)) return@runTest
            val d = "7d7dc5f71eb29ddaf80d6214632eeae03d9058af1fb6d22ed80badb62bc1a534"
            val x = "700c48f77f56584c5cc632ca65640db91b6bacce3a4df6b42ce7cc838833d287"
            val y = "db71e509e3fd9b060ddb20ba5c51dcc5948d46fbf640dfe0441782cab85fa4ac"
            val priv = importPrivateKey(curve, hexBuffer(d))
            try {
                val pub = KeyAgreementPublicKey.of(curve, hexBuffer("04$x$y"))
                val info = CryptoTestVectors.ascii("tls13 premaster")
                val premaster = deriveTlsPremasterSecret(priv, pub)
                val fromRaw =
                    Hkdf
                        .derive(
                            salt = Salt.None,
                            ikm = premaster,
                            info = Info.Of(info),
                            length = 32,
                            factory = BufferFactory.Default,
                        ).toHex()
                        .also { premaster.freeNativeMemory() }
                val fromApi = deriveSharedSecret(priv, pub, info, 32).toHex()
                assertEquals(fromApi, fromRaw, "premaster→HKDF must equal deriveSharedSecret")
            } finally {
                priv.close()
            }
        }
    }

    @Test
    fun x25519LowOrderPeerKeyRejected() {
        // An all-zero X25519 peer point is a low-order point: the raw output is all-zero and must be
        // rejected with InvalidPublicKey (RFC 7748 §6.1) — the raw path enforces the same check as
        // the KDF path, so no all-zero secret is ever returned.
        val curve = KeyAgreementCurve.X25519
        runTest {
            if (!asyncAgreementSupported(curve)) return@runTest
            val a = generateKeyPairAsync(curve)
            try {
                val lowOrder = KeyAgreementPublicKey.of(curve, hexBuffer("00".repeat(32)))
                assertFailsWith<InvalidPublicKey>("all-zero X25519 secret must be rejected") {
                    deriveTlsPremasterSecret(a.privateKey, lowOrder)
                }
            } finally {
                a.close()
            }
        }
    }

    @Test
    fun offCurveP256PeerKeyRejected() {
        // A full-length but off-curve P-256 point (0x04 ‖ X=0 ‖ Y=1) reaches provider validation on
        // the raw path and must be rejected uniformly with InvalidPublicKey — no premaster leaks.
        val curve = KeyAgreementCurve.P256
        runTest {
            if (!asyncAgreementSupported(curve)) return@runTest
            val a = generateKeyPairAsync(curve)
            try {
                val coord = curve.privateKeyBytes
                val pt = BufferFactory.Default.allocate(curve.publicKeyBytes)
                pt.writeByte(0x04)
                repeat(coord) { pt.writeByte(0) } // X = 0
                repeat(coord - 1) { pt.writeByte(0) }
                pt.writeByte(1) // Y = 1 → off curve
                pt.resetForRead()
                val offCurve = KeyAgreementPublicKey.of(curve, pt)
                assertFailsWith<InvalidPublicKey>("off-curve P-256 point must be rejected") {
                    deriveTlsPremasterSecret(a.privateKey, offCurve)
                }
            } finally {
                a.close()
            }
        }
    }
}
