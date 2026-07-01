package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/**
 * Conformance contract for the hardware-backed key machinery (the shape frozen in 6.0). No platform
 * ships a real secure element, so the contract is driven through [FakeHardware] — but the dispatch
 * under test (witness → gated closure, blocking-seam safety net, the auth gate) is the production
 * code, not the fake.
 *
 * Proves, for the two families a secure element backs (AES-GCM + ECDSA P-256):
 *  - the public `CryptoCapabilities.hardware` witness is [HardwareSupport.Unavailable] on every
 *    platform in 6.0 (no backend ships yet);
 *  - hardware keys carry [KeyProvenance.Hardware] and a realistic eligibility subset;
 *  - a hardware seal/sign round-trips through real crypto (hw-seal opens with an independent software
 *    key; hw-sign verifies under the matching public key) — the gated closure is wired through;
 *  - tampering is rejected opaquely ([VerificationFailed]) on the hardware open path;
 *  - the **blocking** op path is unreachable for a hardware key (it is not a `SyncCapable*` key, so the
 *    blocking witness ops do not accept it — a compile error, not a runtime throw), leaving the async
 *    witness ops as the only path a hardware key can be driven through;
 *  - a denied authorization gate surfaces as [AuthorizationFailed].
 */
class HardwareKeyConformanceTest {
    private val provider = FakeHardware()
    private val grant = HardwareKeySpec()
    private val deny = HardwareKeySpec(authorization = HardwareAuthorization { false })

    @Test
    fun hardwareCapabilityReflectsThePlatformBackend() =
        runTest {
            // The witness reflects whether *this* platform wires a usable secure element:
            //  - Unavailable on JVM / JS / WASM / Linux, and on Apple/Android targets without a usable
            //    element (the iOS simulator, an unentitled macOS test binary, a host-JVM unit-test run).
            //  - Available where a real backend resolves: the Android Keystore (emulator/device) or the
            //    Apple Secure Enclave (entitled device). There, a provider-minted key must actually work.
            when (val hw = CryptoCapabilities.hardware) {
                is HardwareSupport.Unavailable -> Unit
                is HardwareSupport.Available -> assertRealProviderWorks(hw.provider)
            }
        }

    /** A real (non-fake) provider must round-trip its eligible primitives and expose the verify key. */
    private suspend fun assertRealProviderWorks(provider: HardwareKeyProvider) {
        // Every shipping secure element backs ECDSA P-256; prove a real sign → verify-under-public-key.
        assertTrue(provider.eligible(HardwareAlgorithm.EcdsaP256), "a real provider backs ECDSA P-256")
        val signing = provider.generateSigning(SignatureScheme.EcdsaP256, grant)
        try {
            assertEquals(KeyProvenance.Hardware, signing.provenance)
            val vk = signing.verifyKey
            val ops = signatureAsyncOrNull(SignatureScheme.EcdsaP256)
            if (ops != null) {
                val message = ascii("real hardware-backed signature")
                val signature = ops.sign(signing, message)
                assertTrue(ops.verify(vk, message, signature), "real hw-sign must verify under its public key")
            }
        } finally {
            signing.close()
        }

        // AES-GCM is backed by StrongBox/TEE (Android) but not the Enclave (Apple) — gate on eligibility.
        if (provider.eligible(HardwareAlgorithm.AesGcm)) {
            val aes = provider.generateAesGcm(grant)
            try {
                assertEquals(KeyProvenance.Hardware, aes.provenance)
                val ops = aesGcmAsyncOps()
                val plaintext = ascii("real hardware-backed AES-GCM payload")
                val sealed = ops.seal(aes, plaintext, Aad.Of(ascii("ctx")))
                val opened = ops.open(sealed, aes, Aad.Of(ascii("ctx")))
                assertEquals(plaintext.toHex(), opened.toHex(), "real hw-seal → hw-open must round-trip")
            } finally {
                aes.close()
            }
        }
    }

    @Test
    fun eligibilityIsARealisticSubset() {
        assertTrue(provider.eligible(HardwareAlgorithm.AesGcm), "AES-GCM is eligible")
        assertTrue(provider.eligible(HardwareAlgorithm.EcdsaP256), "ECDSA P-256 is eligible")
        // A secure element backs neither ChaCha20-Poly1305 nor Ed25519 (nor P-384/P-521 here).
        assertTrue(!provider.eligible(HardwareAlgorithm.Ed25519), "Ed25519 not eligible")
        assertTrue(!provider.eligible(HardwareAlgorithm.EcdsaP384), "P-384 not eligible")
        assertTrue(!provider.eligible(HardwareAlgorithm.EcdsaP521), "P-521 not eligible")
    }

    @Test
    fun generatedKeysReportHardwareProvenance() =
        runTest {
            assertEquals(KeyProvenance.Hardware, provider.generateAesGcm(grant).provenance)
            assertEquals(KeyProvenance.Hardware, provider.generateSigning(SignatureScheme.EcdsaP256, grant).provenance)
        }

    @Test
    fun generateSigningRejectsIneligibleScheme() =
        runTest {
            // Typed, not an IllegalArgumentException with a message: callers branch on the sealed
            // state and consult eligible() before generating.
            assertFailsWith<HardwareKeyException.AlgorithmNotEligible> {
                provider.generateSigning(SignatureScheme.Ed25519, grant)
            }
        }

    @Test
    fun generateAesGcmRejectsUnsupportedKeySizeTyped() =
        runTest {
            assertFailsWith<HardwareKeyException.UnsupportedHardwareKey> {
                provider.generateAesGcm(HardwareKeySpec(aesKeySizeBits = 192))
            }
        }

    // ---- User-authentication policies (Session / PerUse / None) ----
    // The observable contract every real provider must honor, driven through the fake: when the
    // gate fires, how the session window behaves, and how denial surfaces. The OS-binding halves
    // (keystore UserNotAuthenticatedException, Enclave SecAccessControl) are covered by the
    // device-gated Tier-2 tests.

    /** A gate that counts invocations and answers from [answers] (last answer repeats). */
    private class CountingGate(
        private vararg val answers: Boolean,
    ) : HardwareAuthorization {
        var calls = 0
            private set

        override suspend fun authorize(): Boolean {
            val answer = answers.getOrElse(calls) { answers.last() }
            calls++
            return answer
        }
    }

    @Test
    fun sessionAuthenticatesOncePerValidityWindow() =
        runTest {
            val clock = TestTimeSource()
            val gate = CountingGate(true)
            val provider = FakeHardware(timeSource = clock)
            val keys =
                provider.aesGcmPair(
                    HardwareKeySpec(
                        authorization = gate,
                        userAuthentication = UserAuthenticationRequirement.Session(5.minutes),
                    ),
                )
            val ops = aesGcmAsyncOps()

            ops.seal(keys.hardware, ascii("first"))
            ops.seal(keys.hardware, ascii("second"))
            assertEquals(1, gate.calls, "ops inside the window must not re-prompt")

            clock += 6.minutes
            ops.seal(keys.hardware, ascii("third"))
            assertEquals(2, gate.calls, "a stale window must re-prompt exactly once")
        }

    @Test
    fun sessionDenialSurfacesAsAuthorizationFailedAndDoesNotOpenAWindow() =
        runTest {
            val clock = TestTimeSource()
            val gate = CountingGate(false, true)
            val provider = FakeHardware(timeSource = clock)
            val keys =
                provider.aesGcmPair(
                    HardwareKeySpec(
                        authorization = gate,
                        userAuthentication = UserAuthenticationRequirement.Session(5.minutes),
                    ),
                )
            val ops = aesGcmAsyncOps()

            assertFailsWith<AuthorizationFailed> { ops.seal(keys.hardware, ascii("denied")) }
            // The denial must not have started a validity window: the next op prompts again.
            ops.seal(keys.hardware, ascii("granted"))
            assertEquals(2, gate.calls, "a denied prompt must not open the session window")
        }

    @Test
    fun perUseAuthenticatesEveryOperation() =
        runTest {
            val gate = CountingGate(true)
            val keys =
                provider.aesGcmPair(
                    HardwareKeySpec(
                        authorization = gate,
                        userAuthentication = UserAuthenticationRequirement.PerUse(),
                    ),
                )
            val ops = aesGcmAsyncOps()

            val sealed = ops.seal(keys.hardware, ascii("payload"), Aad.Of(ascii("ctx")))
            ops.open(sealed, keys.hardware, Aad.Of(ascii("ctx")))
            assertEquals(2, gate.calls, "per-use must authenticate each op, including opens")
        }

    @Test
    fun sessionValidityRejectsSubSecondWindows() {
        assertFailsWith<IllegalArgumentException> {
            UserAuthenticationRequirement.Session(500.milliseconds)
        }
    }

    // ---- AES-GCM ----

    @Test
    fun hardwareSealRoundTrips() =
        runTest {
            val keys = provider.aesGcmPair(grant)
            val ops = aesGcmAsyncOps()
            val plaintext = ascii("hardware-backed AES-GCM payload")
            val expected = ascii("hardware-backed AES-GCM payload").toHex()

            val sealed = ops.seal(keys.hardware, plaintext, Aad.Of(ascii("ctx")))
            // An independent software key with the same material opens it: the hardware seal produced
            // standard AES-GCM (the gated closure wired through to real crypto, not a stub).
            val viaTwin = ops.open(sealed, keys.softwareTwin, Aad.Of(ascii("ctx")))
            assertEquals(expected, viaTwin.toHex(), "hw-seal → sw-open must recover the plaintext")

            // The hardware key's own gated-open path also recovers it.
            val viaHardware = ops.open(sealed, keys.hardware, Aad.Of(ascii("ctx")))
            assertEquals(expected, viaHardware.toHex(), "hw-seal → hw-open must recover the plaintext")
        }

    @Test
    fun hardwareOpenRejectsEveryByteFlip() =
        runTest {
            val keys = provider.aesGcmPair(grant)
            val ops = aesGcmAsyncOps()
            val sealed = ops.seal(keys.hardware, ascii("authenticated"), Aad.Of(ascii("ctx")))
            // The gated open must reject any single-byte corruption opaquely, like the in-memory path.
            CryptoContract.assertEveryByteFlipRejected(sealed) { mutant ->
                ops.open(mutant, keys.hardware, Aad.Of(ascii("ctx")))
            }
        }

    @Test
    fun hardwareAesGcmIsNotSyncCapable() {
        // A hardware AES-GCM key is an AesGcmKey but not a SyncCapableAesGcmKey, so it cannot be passed
        // to the blocking witness ops at all — the impossible state (hardware key → synchronous path)
        // is a compile error, not a runtime throw. Its in-memory software twin IS sync-capable.
        val keys = provider.aesGcmPair(grant)
        assertFalse(keys.hardware is SyncCapableAesGcmKey, "a hardware AES-GCM key must not be sync-capable")
        assertTrue(keys.softwareTwin is SyncCapableAesGcmKey, "an in-memory AES-GCM key is sync-capable")
    }

    @Test
    fun hardwareAesGcmDeniedAuthorizationFails() =
        runTest {
            val keys = provider.aesGcmPair(deny)
            val ops = aesGcmAsyncOps()
            assertFailsWith<AuthorizationFailed> {
                ops.seal(keys.hardware, ascii("secret"))
            }
        }

    // ---- Signatures (ECDSA P-256) ----
    // Gated on supportsEcdsaSigningFromScalar: Apple cannot sign from a bare scalar, so the fake's
    // scalar-built signing key is not constructible there (verify is still covered elsewhere).

    @Test
    fun hardwareSignVerifiesUnderPublicKey() =
        runTest {
            if (!supportsEcdsaSigningFromScalar) return@runTest
            val pair = provider.signingPair(grant)
            val ops = signatureAsyncOrNull(SignatureScheme.EcdsaP256) ?: return@runTest
            val message = ascii("hardware-backed signature")
            val signature = ops.sign(pair.hardware, message)
            assertTrue(ops.verify(pair.verifyKey, message, signature), "hw-sign must verify under the public key")
            // A different message must not verify under that signature.
            assertTrue(
                !ops.verify(pair.verifyKey, ascii("other message"), signature),
                "signature must not verify a different message",
            )
        }

    @Test
    fun hardwareSigningKeyExposesItsVerifyKey() =
        runTest {
            if (!supportsEcdsaSigningFromScalar) return@runTest
            val pair = provider.signingPair(grant)
            val ops = signatureAsyncOrNull(SignatureScheme.EcdsaP256) ?: return@runTest
            // The public SigningKey.verifyKey accessor surfaces the provider-captured public key.
            val vk = pair.hardware.verifyKey
            val message = ascii("verifyKey accessor")
            val signature = ops.sign(pair.hardware, message)
            assertTrue(ops.verify(vk, message, signature), "the accessor's verify key verifies the signature")
        }

    @Test
    fun hardwareSigningIsNotSyncCapable() {
        // A hardware signing key is a SigningKey but not a SyncCapableSigningKey, so signBlocking /
        // signInto (which take the sync-capable type) are statically unreachable for it — a compile
        // error, not a runtime throw. Key construction is platform-independent, so no scalar-signing gate.
        val pair = provider.signingPair(grant)
        assertFalse(pair.hardware is SyncCapableSigningKey, "a hardware signing key must not be sync-capable")
    }

    @Test
    fun hardwareSigningDeniedAuthorizationFails() =
        runTest {
            if (!supportsEcdsaSigningFromScalar) return@runTest
            val pair = provider.signingPair(deny)
            val ops = signatureAsyncOrNull(SignatureScheme.EcdsaP256) ?: return@runTest
            assertFailsWith<AuthorizationFailed> {
                ops.sign(pair.hardware, ascii("message"))
            }
        }
}
