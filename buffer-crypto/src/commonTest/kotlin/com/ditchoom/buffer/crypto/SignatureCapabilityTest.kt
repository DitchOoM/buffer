package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.SignatureTestSupport.signingKey
import com.ditchoom.buffer.crypto.SignatureTestSupport.verifyKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The capability contract, asserted through the witness so the unsupported path is as rigorously
 * tested as the supported one:
 *
 *  - Where the witness is [SignatureSupport.Blocking], the synchronous ops run to completion.
 *  - The synchronous ops are *not members* of [SignatureSupport.AsyncOnly] / [SignatureSupport.Unavailable],
 *    so calling them on the web is unrepresentable rather than a runtime throw.
 *  - Where Ed25519 has no usable path (Android, or a WebCrypto engine without it), the async ops
 *    either don't exist (Unavailable) or throw [UnsupportedOperationException] (AsyncOnly) — never
 *    silently no-op or return a bogus "valid".
 */
class SignatureCapabilityTest {
    private val edSeed = "9d61b19deffebc3a6f689b25f8a1ada92a2c4a26e3aa1bd2f60ba844af492ec2"
    private val edPub = "dacdbc0f4e3606de5619c8a565a6864275feddf264b11b130abc1167e4f5d034"
    private val p256Scalar = "2021d664b9d4d3dee182cd2d00fae8814e1b9b5c7250813c1e1bd98c01862b87"
    private val p256Point =
        "040b06d55b786556284360597ea5543929c0fcd1148785094a7cd0d4888f40de8a" +
            "0beacfada1cbe2c031a3d578890ae80b7250144e30067ce85532028c2262bf7f"

    @Test
    fun ed25519BlockingWitnessRoundTrips() {
        val message = ascii("cap")
        val support = CryptoCapabilities.signatures(SignatureScheme.Ed25519)
        if (support is SignatureSupport.Blocking) {
            signingKey(SignatureScheme.Ed25519, edSeed, edPub).use { sk ->
                val sig = support.ops.signBlocking(sk, message)
                assertTrue(support.ops.verifyBlocking(verifyKey(SignatureScheme.Ed25519, edPub), message, sig))
            }
        }
        // AsyncOnly / Unavailable carry no synchronous ops, so there is nothing to (mis)call.
    }

    @Test
    fun ecdsaBlockingWitnessRoundTrips() {
        val message = ascii("cap")
        val support = CryptoCapabilities.signatures(SignatureScheme.EcdsaP256)
        // Signing-from-scalar is a separate key-construction capability; only drive the sync sign
        // path where both the blocking witness and that capability hold.
        if (support is SignatureSupport.Blocking && supportsEcdsaSigningFromScalar) {
            signingKey(SignatureScheme.EcdsaP256, p256Scalar, p256Point).use { sk ->
                val sig = support.ops.signBlocking(sk, message)
                assertTrue(support.ops.verifyBlocking(verifyKey(SignatureScheme.EcdsaP256, p256Point), message, sig))
            }
        }
    }

    @Test
    fun ecdsaIsNeverUnavailable() {
        // ECDSA verification is available on every platform, so its witness is never Unavailable.
        for (scheme in listOf(SignatureScheme.EcdsaP256, SignatureScheme.EcdsaP384, SignatureScheme.EcdsaP521)) {
            assertTrue(
                CryptoCapabilities.signatures(scheme) != SignatureSupport.Unavailable,
                "${scheme.schemeName} must not be Unavailable",
            )
        }
    }

    @Test
    fun ed25519AsyncMatchesAvailability() =
        runTest {
            val message = ascii("cap-async")
            if (ed25519AsyncAvailable()) {
                signingKey(SignatureScheme.Ed25519, edSeed, edPub).use { sk ->
                    val sig = signAsync(sk, message)
                    assertTrue(verifyAsync(verifyKey(SignatureScheme.Ed25519, edPub), message, sig))
                }
            } else {
                // No usable Ed25519: the scheme is Unavailable (Android) or the engine lacks it (web,
                // AsyncOnly). Where async ops exist, calling them must throw rather than fake success.
                when (val w = CryptoCapabilities.signatures(SignatureScheme.Ed25519)) {
                    SignatureSupport.Unavailable -> Unit
                    is SignatureSupport.AsyncOnly ->
                        assertFailsWith<UnsupportedOperationException> {
                            signingKey(SignatureScheme.Ed25519, edSeed, edPub).use { sk -> w.ops.sign(sk, message) }
                        }
                    is SignatureSupport.Blocking ->
                        assertFailsWith<UnsupportedOperationException> {
                            signingKey(SignatureScheme.Ed25519, edSeed, edPub).use { sk -> w.ops.sign(sk, message) }
                        }
                }
            }
        }
}
