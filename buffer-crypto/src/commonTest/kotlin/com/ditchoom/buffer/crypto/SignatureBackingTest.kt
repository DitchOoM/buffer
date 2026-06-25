package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.pool.BufferPool
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Sign/verify must behave identically regardless of how the message, signature, and key buffers
 * are backed (heap × direct × pooled × slice), since each backing hits a different branch of the
 * platform bridges (managed-array vs native-pointer vs `toByteArray()` copy vs wrapper delegation).
 * A broken fast path in one backing would otherwise slip through a single-backing KAT.
 */
class SignatureBackingTest {
    private val edSeed = "9d61b19deffebc3a6f689b25f8a1ada92a2c4a26e3aa1bd2f60ba844af492ec2"
    private val edPub = "dacdbc0f4e3606de5619c8a565a6864275feddf264b11b130abc1167e4f5d034"
    private val p256Scalar = "2021d664b9d4d3dee182cd2d00fae8814e1b9b5c7250813c1e1bd98c01862b87"
    private val p256Point =
        "040b06d55b786556284360597ea5543929c0fcd1148785094a7cd0d4888f40de8a" +
            "0beacfada1cbe2c031a3d578890ae80b7250144e30067ce85532028c2262bf7f"

    @Test
    fun ed25519AcrossBackings() =
        runTest {
            if (!ed25519AsyncAvailable()) return@runTest
            val pool = BufferPool()
            for (msgK in CryptoBackings.inputs) {
                for (keyK in CryptoBackings.inputs) {
                    for (sigK in CryptoBackings.inputs) {
                        val message = CryptoBackings.place(msgK, ascii("backing-matrix"), pool)
                        val seed = CryptoBackings.place(keyK, hexBuffer(edSeed), pool)
                        val sig = SigningKey.ed25519(seed, VerifyKey.ed25519(hexBuffer(edPub))).use { signAsync(it, message) }
                        val sigPlaced = CryptoBackings.place(sigK, sig, pool)
                        val pub = VerifyKey.ed25519(CryptoBackings.place(keyK, hexBuffer(edPub), pool))
                        assertTrue(
                            verifyAsync(pub, message, sigPlaced),
                            "Ed25519 msg=$msgK key=$keyK sig=$sigK",
                        )
                    }
                }
            }
            pool.clear()
        }

    @Test
    fun ecdsaP256AcrossBackings() =
        runTest {
            if (!supportsEcdsaSigningFromScalar) return@runTest
            val pool = BufferPool()
            for (msgK in CryptoBackings.inputs) {
                for (keyK in CryptoBackings.inputs) {
                    for (sigK in CryptoBackings.inputs) {
                        val message = CryptoBackings.place(msgK, ascii("backing-matrix"), pool)
                        val scalar = CryptoBackings.place(keyK, hexBuffer(p256Scalar), pool)
                        val sig = SigningKey.ecdsaP256(scalar, VerifyKey.ecdsaP256(hexBuffer(p256Point))).use { signAsync(it, message) }
                        val sigPlaced = CryptoBackings.place(sigK, sig, pool)
                        val pub = VerifyKey.ecdsaP256(CryptoBackings.place(keyK, hexBuffer(p256Point), pool))
                        assertTrue(
                            verifyAsync(pub, message, sigPlaced),
                            "ECDSA-P256 msg=$msgK key=$keyK sig=$sigK",
                        )
                    }
                }
            }
            pool.clear()
        }
}
