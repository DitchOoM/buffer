package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.SignatureTestSupport.signingKey
import com.ditchoom.buffer.crypto.SignatureTestSupport.verifyKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Negative / tamper discipline: a valid signature must verify, but flipping a single bit anywhere
 * in the signature, the message, or the public key must make verify **reject** — never silently
 * accept. This exercises *our* verification branch, which a KAT alone never does. We also assert
 * wrong-key rejection and an all-zero (r=0/s=0-shaped) signature rejection.
 */
class SignatureTamperTest {
    // P-256 keypair (scalar, point) + a Ed25519 RFC keypair, so we tamper-test both families.
    private val p256Scalar = "2021d664b9d4d3dee182cd2d00fae8814e1b9b5c7250813c1e1bd98c01862b87"
    private val p256Point =
        "040b06d55b786556284360597ea5543929c0fcd1148785094a7cd0d4888f40de8a" +
            "0beacfada1cbe2c031a3d578890ae80b7250144e30067ce85532028c2262bf7f"

    private val edSeed = "9d61b19deffebc3a6f689b25f8a1ada92a2c4a26e3aa1bd2f60ba844af492ec2"
    private val edPub = "dacdbc0f4e3606de5619c8a565a6864275feddf264b11b130abc1167e4f5d034"

    @Test
    fun ecdsaTamperRejected() =
        runTest {
            if (!supportsEcdsaSigningFromScalar) return@runTest // need to produce a sig to tamper
            val message = ascii("tamper me")
            val pub = verifyKey(SignatureScheme.EcdsaP256, p256Point)
            val sig = signingKey(SignatureScheme.EcdsaP256, p256Scalar).use { signAsync(it, message) }
            assertTrue(verifyAsync(pub, message, sig), "baseline ECDSA sig must verify")

            // Flip every byte of the signature → reject.
            tamperEachByte(sig) { mutant -> verifyAsync(pub, message, mutant) }
            // Flip every byte of the message → reject.
            tamperEachByte(message) { mutant -> verifyAsync(pub, mutant, sig) }
        }

    @Test
    fun ed25519TamperRejected() =
        runTest {
            if (!ed25519AsyncAvailable()) return@runTest
            val message = ascii("tamper me")
            val pub = verifyKey(SignatureScheme.Ed25519, edPub)
            val sig = signingKey(SignatureScheme.Ed25519, edSeed).use { signAsync(it, message) }
            assertTrue(verifyAsync(pub, message, sig), "baseline Ed25519 sig must verify")

            tamperEachByte(sig) { mutant -> verifyAsync(pub, message, mutant) }
            tamperEachByte(message) { mutant -> verifyAsync(pub, mutant, sig) }
            // Flip every byte of the public key → reject.
            tamperEachByte(CryptoTestVectors.hexBuffer(edPub)) { mutantPub ->
                verifyAsync(VerifyKey.ed25519(mutantPub), message, sig)
            }
        }

    @Test
    fun wrongKeyAndZeroSignatureRejected() =
        runTest {
            if (!ed25519AsyncAvailable()) return@runTest
            val message = ascii("hello")
            val sig = signingKey(SignatureScheme.Ed25519, edSeed).use { signAsync(it, message) }

            // A different (RFC 8032 TEST 2) public key must not verify this signature.
            val wrongPub =
                VerifyKey.ed25519(
                    CryptoTestVectors.hexBuffer("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"),
                )
            assertTrue(!verifyAsync(wrongPub, message, sig), "wrong public key must reject")

            // An all-zero 64-byte signature (R=0, S=0) must reject.
            val zeroSig = CryptoTestVectors.repeatedByte(0, 64)
            assertTrue(
                !verifyAsync(VerifyKey.ed25519(CryptoTestVectors.hexBuffer(edPub)), message, zeroSig),
                "all-zero signature must reject",
            )
        }

    private suspend fun tamperEachByte(
        target: ReadBuffer,
        verify: suspend (ReadBuffer) -> Boolean,
    ) {
        val start = target.position()
        val n = target.remaining()
        require(n > 0)
        for (i in 0 until n) {
            val mutant = BufferFactory.Default.allocate(n)
            for (j in 0 until n) {
                val b = target.get(start + j)
                mutant.writeByte(if (j == i) (b.toInt() xor 0x01).toByte() else b)
            }
            mutant.resetForRead()
            assertTrue(!verify(mutant), "byte $i flip must be rejected")
        }
    }
}
