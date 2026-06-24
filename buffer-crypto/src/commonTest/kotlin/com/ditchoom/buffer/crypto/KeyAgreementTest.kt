package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Behavioural tests for the key-agreement family: round-trip agreement (both parties derive the
 * same key), the KDF-on-shared-secret / domain-separation contract, capability gating, public-key
 * length validation, and tampered-key divergence. Driven through the buffer API only.
 *
 * Curve availability differs per platform (all curves async-only on web; X25519 unsupported on
 * Apple and Android < 34); the tests skip curves the current target can't run rather than failing.
 */
class KeyAgreementTest {
    private val curves =
        listOf(
            KeyAgreementCurve.X25519,
            KeyAgreementCurve.P256,
            KeyAgreementCurve.P384,
            KeyAgreementCurve.P521,
        )

    private fun info() = CryptoTestVectors.ascii("ditchoom/key-agreement test")

    private fun salt() = CryptoTestVectors.ascii("salt-vector")

    @Test
    fun roundTripDerivesSameKeyAsync() =
        runTest {
            curves.filter { asyncAgreementSupported(it) }.forEach { roundTripAsyncFor(it) }
        }

    private suspend fun roundTripAsyncFor(curve: KeyAgreementCurve) {
        // Engines that advertise WebCrypto but lack a specific algorithm (e.g. older browsers
        // without X25519) throw the capability exception at op time; treat that as a skip —
        // the capability contract itself is asserted in the dedicated capability tests.
        val a =
            try {
                generateKeyPairAsync(curve)
            } catch (_: UnsupportedOperationException) {
                return
            }
        val b = generateKeyPairAsync(curve)
        try {
            val ab = deriveSharedSecretAsync(a.privateKey, b.publicKey, info(), 32, salt())
            val ba = deriveSharedSecretAsync(b.privateKey, a.publicKey, info(), 32, salt())
            assertEquals(ab.toHex(), ba.toHex(), "${curve.curveName} both parties must derive the same key")
            assertEquals(32, ab.remaining(), "derived length")
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun roundTripSyncWhereSupported() {
        for (curve in curves) {
            if (!supportsSync(curve)) continue
            val a = generateKeyPair(curve)
            val b = generateKeyPair(curve)
            try {
                val ab = deriveSharedSecret(a.privateKey, b.publicKey, info(), 48, salt())
                val ba = deriveSharedSecret(b.privateKey, a.publicKey, info(), 48, salt())
                assertEquals(ab.toHex(), ba.toHex(), "${curve.curveName} sync round-trip must agree")
                assertEquals(48, ab.remaining())
            } finally {
                a.close()
                b.close()
            }
        }
    }

    @Test
    fun generateKeyPairHonoursCapabilityFlag() {
        for (curve in curves) {
            CryptoContract.assertCapability(supportsSync(curve)) {
                generateKeyPair(curve).close()
            }
        }
    }

    @Test
    fun syncDeriveThrowsWhenUnsupported() {
        for (curve in curves) {
            if (supportsSync(curve)) continue
            val priv =
                KeyAgreementPrivateKey(
                    curve,
                    BufferFactory.Default.allocate(curve.privateKeyBytes).also { it.resetForRead() },
                )
            val pubBuf = BufferFactory.Default.allocate(curve.publicKeyBytes)
            repeat(curve.publicKeyBytes) { pubBuf.writeByte(0) }
            pubBuf.resetForRead()
            val pub = KeyAgreementPublicKey(curve, pubBuf)
            assertFailsWith<UnsupportedOperationException>(
                "${curve.curveName} sync derive must throw when unsupported",
            ) {
                deriveSharedSecret(priv, pub, info(), 32)
            }
            priv.close()
        }
    }

    @Test
    fun differentInfoYieldsDifferentKeys() =
        runTest {
            val curve = KeyAgreementCurve.P256
            if (!asyncAgreementSupported(curve)) return@runTest
            val a = generateKeyPairAsync(curve)
            val b = generateKeyPairAsync(curve)
            try {
                val k1 = deriveSharedSecretAsync(a.privateKey, b.publicKey, CryptoTestVectors.ascii("ctx-1"), 32)
                val k2 = deriveSharedSecretAsync(a.privateKey, b.publicKey, CryptoTestVectors.ascii("ctx-2"), 32)
                assertTrue(k1.toHex() != k2.toHex(), "different info must yield different keys (KDF domain separation)")
            } finally {
                a.close()
                b.close()
            }
        }

    @Test
    fun tamperedPeerKeyDivergesOrRejects() =
        runTest {
            val curve = KeyAgreementCurve.P256
            if (!asyncAgreementSupported(curve)) return@runTest
            val a = generateKeyPairAsync(curve)
            val b = generateKeyPairAsync(curve)
            try {
                val good = deriveSharedSecretAsync(a.privateKey, b.publicKey, info(), 32).toHex()
                val start = b.publicKey.encoded.position()
                val n = b.publicKey.encoded.remaining()
                val tampered = BufferFactory.Default.allocate(n)
                for (i in 0 until n) {
                    val byte = b.publicKey.encoded.get(start + i)
                    tampered.writeByte(if (i == n - 1) (byte.toInt() xor 0x01).toByte() else byte)
                }
                tampered.resetForRead()
                val tamperedKey = KeyAgreementPublicKey(curve, tampered)
                val diverged =
                    try {
                        deriveSharedSecretAsync(a.privateKey, tamperedKey, info(), 32).toHex() != good
                    } catch (_: InvalidPublicKey) {
                        true
                    }
                assertTrue(diverged, "tampered peer key must diverge or be rejected")
            } finally {
                a.close()
                b.close()
            }
        }

    @Test
    fun offCurveFullLengthPointRejected() {
        // A full-length (so the eager length check passes) but off-curve P-256 point: 0x04 ‖ X=0 ‖ Y=1,
        // which does not satisfy the curve equation. This reaches the provider's point-validation /
        // doPhase path — the case the Wycheproof "04"-stub invalid vectors do NOT exercise — and must
        // be rejected with InvalidPublicKey (or its length-equivalent) rather than producing a key.
        val curve = KeyAgreementCurve.P256
        if (!supportsSync(curve)) return
        val a = generateKeyPair(curve)
        try {
            val coord = curve.privateKeyBytes
            val pt = BufferFactory.Default.allocate(curve.publicKeyBytes)
            pt.writeByte(0x04)
            repeat(coord) { pt.writeByte(0) } // X = 0
            repeat(coord - 1) { pt.writeByte(0) }
            pt.writeByte(1) // Y = 1  → off curve
            pt.resetForRead()
            val offCurve = KeyAgreementPublicKey(curve, pt)
            assertFailsWith<InvalidPublicKey>("full-length off-curve point must be rejected") {
                deriveSharedSecret(a.privateKey, offCurve, info(), 32)
            }
        } finally {
            a.close()
        }
    }

    @Test
    fun wrongLengthPublicKeyRejected() {
        assertFailsWith<IllegalArgumentException> {
            KeyAgreementPublicKey(KeyAgreementCurve.P256, hexBuffer("04aa"))
        }
        assertFailsWith<IllegalArgumentException> {
            KeyAgreementPublicKey(KeyAgreementCurve.X25519, hexBuffer("00"))
        }
    }
}
