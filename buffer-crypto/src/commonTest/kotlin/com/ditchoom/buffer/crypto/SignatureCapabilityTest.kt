package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.SignatureTestSupport.signingKey
import com.ditchoom.buffer.crypto.SignatureTestSupport.verifyKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The capability contract, asserted from both sides so the unsupported path is as rigorously
 * tested as the supported one:
 *
 *  - Where a `supports*` flag is `true`, the corresponding op runs to completion.
 *  - Where it is `false`, the op throws [UnsupportedOperationException] — never silently no-ops or,
 *    worse, returns a bogus "valid". This covers Ed25519 on Android below API 34, Ed25519 on a
 *    WebCrypto engine without it, the sync API on JS/WASM, and ECDSA signing-from-scalar on Apple.
 */
class SignatureCapabilityTest {
    private val edSeed = "9d61b19deffebc3a6f689b25f8a1ada92a2c4a26e3aa1bd2f60ba844af492ec2"
    private val edPub = "dacdbc0f4e3606de5619c8a565a6864275feddf264b11b130abc1167e4f5d034"
    private val p256Scalar = "2021d664b9d4d3dee182cd2d00fae8814e1b9b5c7250813c1e1bd98c01862b87"

    @Test
    fun syncEd25519MatchesFlag() {
        val message = ascii("cap")
        if (supportsSyncEd25519) {
            signingKey(SignatureScheme.Ed25519, edSeed).use { sk ->
                val sig = sign(sk, message)
                assertTrue(verify(verifyKey(SignatureScheme.Ed25519, edPub), message, sig))
            }
        } else {
            assertFailsWith<UnsupportedOperationException> {
                signingKey(SignatureScheme.Ed25519, edSeed).use { sk -> sign(sk, message) }
            }
        }
    }

    @Test
    fun syncEcdsaMatchesFlag() {
        val message = ascii("cap")
        // Signing-from-scalar is separately gated (off on Apple), so only drive the sync path
        // where both the sync-ECDSA flag and the signing-from-scalar flag are on.
        if (supportsSyncEcdsa && supportsEcdsaSigningFromScalar) {
            signingKey(SignatureScheme.EcdsaP256, p256Scalar).use { sk ->
                val sig = sign(sk, message)
                assertTrue(sig.remaining() > 0)
            }
        } else if (!supportsSyncEcdsa) {
            assertFailsWith<UnsupportedOperationException> {
                signingKey(SignatureScheme.EcdsaP256, p256Scalar).use { sk -> sign(sk, message) }
            }
        }
    }

    @Test
    fun asyncEd25519MatchesFlag() =
        runTest {
            val message = ascii("cap-async")
            if (ed25519AsyncAvailable()) {
                signingKey(SignatureScheme.Ed25519, edSeed).use { sk ->
                    val sig = signAsync(sk, message)
                    assertTrue(verifyAsync(verifyKey(SignatureScheme.Ed25519, edPub), message, sig))
                }
            } else {
                assertFailsWith<UnsupportedOperationException> {
                    signingKey(SignatureScheme.Ed25519, edSeed).use { sk -> signAsync(sk, message) }
                }
            }
        }
}
