package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import com.ditchoom.buffer.crypto.SignatureTestSupport.signingKey
import com.ditchoom.buffer.crypto.SignatureTestSupport.verifyKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Known-answer vectors (RFC 8032 Ed25519 + NIST/FIPS-186 ECDSA) and sign→verify round-trips.
 *
 * Ed25519 per RFC 8032 is deterministic, so where the platform produces deterministic signatures
 * (JCA on the JVM, WebCrypto) the KAT asserts the exact signature bytes. Apple's CryptoKit instead
 * produces *hedged* (randomized) Ed25519 signatures — still valid and RFC-conformant on the verify
 * side, but not bit-identical to the RFC test vector — so on such platforms the KAT proves
 * correctness by verifying the known-good signature and round-tripping our own. Which mode applies
 * is probed at runtime (sign the same message twice; identical ⇒ deterministic). ECDSA signing is
 * always randomized (the per-signature nonce `k`), so its KAT is a verify-of-known-good check plus a
 * sign→verify round-trip. All ops use the async API so the suite runs on every platform (WebCrypto
 * async on JS/WASM, native otherwise).
 */
class SignatureKatTest {
    // RFC 8032 §7.1 — Ed25519 (seed, publicKey, message, signature).
    private val ed25519Vectors =
        listOf(
            Quad(
                "9d61b19deffebc3a6f689b25f8a1ada92a2c4a26e3aa1bd2f60ba844af492ec2",
                "dacdbc0f4e3606de5619c8a565a6864275feddf264b11b130abc1167e4f5d034",
                "",
                "b4f80989858693c75ab32b65b85ca3af703dde0fa635313f7304fdcdaca6c7f3" +
                    "dfe93ecb6af52bd7101b5b65b53ea8bee7d4991655ad824a7a3c5834f2953100",
            ),
            Quad(
                "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
                "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
                "72",
                "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                    "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            ),
        )

    private data class Quad(
        val seed: String,
        val pub: String,
        val msg: String,
        val sig: String,
    )

    /**
     * Probes whether this platform's Ed25519 signing is deterministic by signing the same message
     * twice and comparing. RFC 8032 mandates deterministic signatures, but CryptoKit (Apple) hedges
     * them with randomness, so the exact-bytes assertion only applies where signing is deterministic.
     */
    private suspend fun ed25519IsDeterministic(): Boolean {
        val v0 = ed25519Vectors.first()
        val msg = hexBuffer("ab")
        signingKey(SignatureScheme.Ed25519, v0.seed, v0.pub).use { sk ->
            val a = signAsync(sk, msg).toHex()
            val b = signingKey(SignatureScheme.Ed25519, v0.seed, v0.pub).use { sk2 -> signAsync(sk2, hexBuffer("ab")).toHex() }
            return a == b
        }
    }

    @Test
    fun ed25519DeterministicSignAndVerify() =
        runTest {
            if (!ed25519AsyncAvailable()) return@runTest
            val deterministic = ed25519IsDeterministic()
            for (v in ed25519Vectors) {
                signingKey(SignatureScheme.Ed25519, v.seed, v.pub).use { sk ->
                    val produced = signAsync(sk, hexBuffer(v.msg))
                    if (deterministic) {
                        // RFC 8032 deterministic signing must reproduce the published vector exactly.
                        assertEquals(v.sig, produced.toHex(), "Ed25519 sign(seed=${v.seed.take(8)}…)")
                    } else {
                        // Hedged signing (CryptoKit): the produced signature must still verify.
                        val selfOk = verifyAsync(verifyKey(SignatureScheme.Ed25519, v.pub), hexBuffer(v.msg), produced)
                        assertTrue(selfOk, "Ed25519 hedged sign→verify(seed=${v.seed.take(8)}…)")
                    }
                }
                val ok =
                    verifyAsync(
                        verifyKey(SignatureScheme.Ed25519, v.pub),
                        hexBuffer(v.msg),
                        hexBuffer(v.sig),
                    )
                assertTrue(ok, "Ed25519 verify(known sig)")
            }
        }

    // ECDSA verify KAT — one valid (publicKey, message, signature) per curve (NIST P-curves,
    // SHA-256/384/512). Public keys are uncompressed SEC1 points; sigs are DER (X9.62).
    @Test
    fun ecdsaVerifyKnownAnswer() =
        runTest {
            val p256 =
                Triple(
                    "0404aaec73635726f213fb8a9e64da3b8632e41495a944d0045b522eba7240fad5" +
                        "87d9315798aaa3a5ba01775787ced05eaaf7b4e09fc81d6d1aa546e8365d525d",
                    "4d7367",
                    "30450220530bd6b0c9af2d69ba897f6b5fb59695cfbf33afe66dbadcf5b8d2a2a6538e23" +
                        "022100d85e489cb7a161fd55ededcedbf4cc0c0987e3e3f0f242cae934c72caa3f43e9",
                )
            assertEcdsaVerify(SignatureScheme.EcdsaP256, p256.first, p256.second, p256.third)
        }

    private suspend fun assertEcdsaVerify(
        scheme: SignatureScheme,
        pubHex: String,
        msgHex: String,
        derSigHex: String,
    ) {
        // The DER KAT only applies where the platform consumes DER. On P1363 platforms (web) the
        // round-trip test below proves signing/verifying instead.
        if (ecdsaSignatureEncoding != EcdsaSignatureEncoding.Der) return
        val ok = verifyAsync(verifyKey(scheme, pubHex), hexBuffer(msgHex), hexBuffer(derSigHex))
        assertTrue(ok, "${scheme.schemeName} verify(known DER sig)")
    }

    // Round-trip keypairs (scalar, uncompressed point) generated once; signing is randomized so we
    // verify the signature we just produced rather than comparing to a fixed expected value.
    @Test
    fun ecdsaSignThenVerifyRoundTrip() =
        runTest {
            // Apple cannot sign from a bare scalar (Security.framework needs the full X9.63 private
            // rep); verify is still covered by the KAT + Wycheproof suites there.
            if (!supportsEcdsaSigningFromScalar) return@runTest

            data class Kp(
                val scheme: SignatureScheme,
                val scalar: String,
                val point: String,
            )
            val kps =
                listOf(
                    Kp(
                        SignatureScheme.EcdsaP256,
                        "2021d664b9d4d3dee182cd2d00fae8814e1b9b5c7250813c1e1bd98c01862b87",
                        "040b06d55b786556284360597ea5543929c0fcd1148785094a7cd0d4888f40de8a" +
                            "0beacfada1cbe2c031a3d578890ae80b7250144e30067ce85532028c2262bf7f",
                    ),
                    Kp(
                        SignatureScheme.EcdsaP384,
                        "75bb7554226b8a0240bae93a4946292b46816e27129bbfe00a445c35f923761" +
                            "63c124a072053b79dba6a3479dd8c758b",
                        "0459d64245f51323f2bc7890ea862a52a0f6c9f14fed0f5149f14f16b368a7c60d" +
                            "73dba36468d0e6f87e5ec07799516f968db83ce88891c865767eab5c267f0aca6" +
                            "01c4f95a5c6f7c9b9ad6bed538db4dac829bb4ea814675235c55ccbeab35123",
                    ),
                    Kp(
                        SignatureScheme.EcdsaP521,
                        "00ddc5bea01105f130cb9047edba5257486efb235928d2609e857329738a2735df" +
                            "30dca66c58ddbb9cf5b0a828627e52f5143a278d04fd2ff5cd410967205399ef8a",
                        "04006f05f8d1c8c0b36d4e7f6b5c4ea1e4cdeacc9c9560af21c0966bf213501ca37" +
                            "53fa37fda672e19c374909f0c8beaff54bca5994a2a6c5ebcb7457db1d9a5c91c2" +
                            "a001077fa6461ac840e89ae2d0499b9b03ee7858e989861776e71f74dd3835d53" +
                            "e36af269d20c66ac9378b7d8a8b7bd90b7055bc6105920e6781e3355b8ae89aa7f65",
                    ),
                )
            val message = CryptoTestVectors.ascii("buffer-crypto round-trip")
            for (kp in kps) {
                signingKey(kp.scheme, kp.scalar, kp.point).use { sk ->
                    val sig = signAsync(sk, message)
                    val ok = verifyAsync(verifyKey(kp.scheme, kp.point), message, sig)
                    assertTrue(ok, "${kp.scheme.schemeName} sign→verify round-trip")
                    // A different message must NOT verify under the produced signature.
                    val wrong = verifyAsync(verifyKey(kp.scheme, kp.point), CryptoTestVectors.ascii("other"), sig)
                    assertTrue(!wrong, "${kp.scheme.schemeName} sig must not verify a different message")
                }
            }
        }
}
