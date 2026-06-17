package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import com.ditchoom.buffer.pool.BufferPool
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Backing-matrix coverage: a key-agreement op must produce the same derived key regardless of how
 * the peer public key and the derived-key destination factory are backed (heap × direct × pooled ×
 * slice), since each backing exercises a different branch of the platform glue (managed-array vs
 * native-pointer paths, wrapper delegation). Gated on synchronous support so it can run without a
 * coroutine driver; the async path shares the same glue.
 */
class KeyAgreementBackingTests {
    @Test
    fun derivedKeyIsBackingInvariantForPublicKey() {
        val pool = BufferPool()
        for (curve in listOf(KeyAgreementCurve.X25519, KeyAgreementCurve.P256, KeyAgreementCurve.P384, KeyAgreementCurve.P521)) {
            if (!supportsSync(curve)) continue
            val a = generateKeyPair(curve)
            val b = generateKeyPair(curve)
            try {
                val info = CryptoTestVectors.ascii("backing-info")
                val salt = CryptoTestVectors.ascii("backing-salt")
                // Reference using the public key as-generated.
                val reference =
                    deriveSharedSecret(a.privateKey, b.publicKey, info, 32, salt).toHex()

                for (ik in CryptoBackings.inputs) {
                    // Re-back the peer public key in each backing flavour and re-derive.
                    val rebacked = CryptoBackings.place(ik, b.publicKey.encoded, pool)
                    val pub = KeyAgreementPublicKey(curve, rebacked)
                    val out = deriveSharedSecret(a.privateKey, pub, info, 32, salt).toHex()
                    assertEquals(reference, out, "${curve.curveName} public-key backing=$ik")
                }
            } finally {
                a.close()
                b.close()
            }
        }
        pool.clear()
    }
}
