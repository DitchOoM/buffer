package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Key generation through the signature witness: a freshly generated key must carry its matching
 * [SigningKey.verifyKey] (Fix 1's invariant — every signing key knows its verifier) and round-trip
 * sign→verify under that public key, on both the async path (every platform) and, where a blocking
 * witness exists, the synchronous path. ECDSA generation reuses the key-agreement generator; Ed25519
 * uses the per-platform primitive — this proves both routes produce usable, self-consistent keys.
 */
class SignatureGenerateTest {
    private val schemes =
        listOf(
            SignatureScheme.Ed25519,
            SignatureScheme.EcdsaP256,
            SignatureScheme.EcdsaP384,
            SignatureScheme.EcdsaP521,
        )

    @Test
    fun generatedKeyRoundTripsThroughAsyncWitness() =
        runTest {
            val message = ascii("generated-key round-trip")
            for (scheme in schemes) {
                val ops = signatureAsyncOrNull(scheme) ?: continue
                // Ed25519 may be reachable (AsyncOnly) yet feature-detected off on the engine.
                if (scheme == SignatureScheme.Ed25519 && !ed25519AsyncAvailable()) continue
                ops.generateSigningKey().use { sk ->
                    assertEquals(scheme, sk.scheme, "generated key scheme")
                    assertEquals(scheme, sk.verifyKey.scheme, "generated verify key scheme")
                    assertEquals(KeyProvenance.Software, sk.provenance, "generated key is software-backed")
                    val sig = ops.sign(sk, message)
                    assertTrue(ops.verify(sk.verifyKey, message, sig), "$scheme generated key verifies its own signature")
                    assertTrue(
                        !ops.verify(sk.verifyKey, ascii("a different message"), sig),
                        "$scheme generated key must not verify a different message",
                    )
                }
            }
        }

    @Test
    fun generatedKeyRoundTripsThroughBlockingWitness() {
        val message = ascii("blocking generate")
        for (scheme in schemes) {
            val w = CryptoCapabilities.signatures(scheme)
            if (w !is SignatureSupport.Blocking) continue
            w.ops.generateSigningKeyBlocking().use { sk ->
                assertEquals(scheme, sk.verifyKey.scheme, "$scheme blocking-generated verify key scheme")
                val sig = w.ops.signBlocking(sk, message)
                assertTrue(
                    w.ops.verifyBlocking(sk.verifyKey, message, sig),
                    "$scheme blocking-generated key verifies its own signature",
                )
            }
        }
    }

    @Test
    fun distinctGenerationsProduceDistinctKeys() =
        runTest {
            // Two generations of the same scheme must not collide (a real CSPRNG-backed keygen).
            for (scheme in schemes) {
                val ops = signatureAsyncOrNull(scheme) ?: continue
                if (scheme == SignatureScheme.Ed25519 && !ed25519AsyncAvailable()) continue
                ops.generateSigningKey().use { a ->
                    ops.generateSigningKey().use { b ->
                        val message = ascii("cross-key check")
                        val sigA = ops.sign(a, message)
                        // a's signature must not verify under b's unrelated public key.
                        assertTrue(
                            !ops.verify(b.verifyKey, message, sigA),
                            "$scheme: a signature must not verify under an independently generated key",
                        )
                    }
                }
            }
        }
}
