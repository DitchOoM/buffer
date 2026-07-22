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
 * Proves, for the families a secure element backs (AES-GCM + ECDSA P-256 + ECDH P-256):
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
    private val grant = ProtectedKeySpec()
    private val deny = ProtectedKeySpec(authorization = HardwareAuthorization { false })

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
        assertTrue(provider.eligible(ProtectedKeyAlgorithm.EcdsaP256), "a real provider backs ECDSA P-256")
        val signing = provider.generateSigning(SignatureScheme.EcdsaP256, grant)
        try {
            assertEquals(KeyProvenance.Hardware, signing.provenance)
            // A real hardware key self-describes as non-exportable hardware custody, and its 6.0
            // provenance is exactly the derivation off that single value.
            assertTrue(signing.custody is KeyCustody.NonExportable.Hardware, "real hw signing key is hardware custody")
            assertFalse(signing.custody.exportable, "a hardware key is never exportable")
            assertEquals(signing.custody.provenance, signing.provenance)
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
        if (provider.eligible(ProtectedKeyAlgorithm.AesGcm)) {
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

        // ECDH P-256 key agreement is probed per device (Android API 31+ Keystore) — gate on
        // eligibility. Where it holds, a real element-held key must agree with a software peer:
        // both sides derive the same secret, proving the keystore DH produced standard ECDH.
        if (provider.eligible(ProtectedKeyAlgorithm.EcdhP256)) {
            val pair = provider.generateKeyAgreement(KeyAgreementCurve.P256, grant)
            try {
                assertEquals(KeyProvenance.Hardware, pair.privateKey.provenance)
                assertFailsWith<UnsupportedOperationException>("a hardware agreement key has no exportable scalar") {
                    pair.privateKey.exportEncoded()
                }
                val ops = keyAgreementAsyncOrNull(KeyAgreementCurve.P256)
                if (ops != null) {
                    val peer = ops.generateKeyPair()
                    try {
                        val info = Info.Of(ascii("hw-ka-conformance"))
                        val viaHardware = ops.deriveSharedSecret(pair.privateKey, peer.publicKey, info, length = 32)
                        val viaPeer = ops.deriveSharedSecret(peer.privateKey, pair.publicKey, info, length = 32)
                        assertEquals(viaPeer.toHex(), viaHardware.toHex(), "real hw ECDH must agree with a software peer")
                    } finally {
                        peer.close()
                    }
                }
            } finally {
                pair.close()
            }
        }
    }

    @Test
    fun eligibilityIsARealisticSubset() {
        assertTrue(provider.eligible(ProtectedKeyAlgorithm.AesGcm), "AES-GCM is eligible")
        assertTrue(provider.eligible(ProtectedKeyAlgorithm.EcdsaP256), "ECDSA P-256 is eligible")
        assertTrue(provider.eligible(ProtectedKeyAlgorithm.EcdhP256), "ECDH P-256 is eligible")
        // A secure element backs neither ChaCha20-Poly1305 nor Ed25519 (nor P-384/P-521/X25519 here).
        assertTrue(!provider.eligible(ProtectedKeyAlgorithm.Ed25519), "Ed25519 not eligible")
        assertTrue(!provider.eligible(ProtectedKeyAlgorithm.EcdsaP384), "P-384 not eligible")
        assertTrue(!provider.eligible(ProtectedKeyAlgorithm.EcdsaP521), "P-521 not eligible")
        assertTrue(!provider.eligible(ProtectedKeyAlgorithm.X25519), "X25519 not eligible")
    }

    @Test
    fun generatedKeysReportHardwareProvenance() =
        runTest {
            assertEquals(KeyProvenance.Hardware, provider.generateAesGcm(grant).provenance)
            assertEquals(KeyProvenance.Hardware, provider.generateSigning(SignatureScheme.EcdsaP256, grant).provenance)
        }

    @Test
    fun generatedKeysCarrySelfDescribingHardwareCustody() =
        runTest {
            val aes = provider.generateAesGcm(grant)
            val signing = provider.generateSigning(SignatureScheme.EcdsaP256, grant)
            try {
                for (key in listOf<Any>(aes, signing)) {
                    val custody = if (key is AesGcmKey) key.custody else (key as SigningKey).custody
                    // One canonical value; every other custody fact is derived from it, so they can
                    // never disagree.
                    assertTrue(custody is KeyCustody.NonExportable.Hardware, "hw key custody is NonExportable.Hardware")
                    assertFalse(custody.exportable, "a hardware key is never exportable")
                    assertEquals(KeyProvenance.Hardware, custody.provenance)
                    assertEquals(CustodyTier.Hardware, custody.tier)
                    assertTrue(custody.dedicatedSecureElement, "the fake models a dedicated secure element")
                }
                // Custody is an exhaustive sealed value — this `when` compiles without an `else`.
                val label =
                    when (signing.custody) {
                        KeyCustody.ExportableSoftware -> "exportable-software"
                        KeyCustody.NonExportable.Software -> "non-exportable-software"
                        is KeyCustody.NonExportable.Hardware -> "hardware"
                    }
                assertEquals("hardware", label)
            } finally {
                aes.close()
                signing.close()
            }
        }

    @Test
    fun custodyReflectsDedicatedSecureElementFlag() =
        runTest {
            // A TEE-only provider (no dedicated element) must yield keys whose custody says so — the
            // one field flows all the way into the minted key, unforgeable at the call site.
            val teeOnly = FakeHardware(dedicatedSecureElement = false)
            val key = teeOnly.generateSigning(SignatureScheme.EcdsaP256, grant)
            try {
                val custody = key.custody
                assertTrue(custody is KeyCustody.NonExportable.Hardware, "still hardware custody")
                assertFalse(custody.dedicatedSecureElement, "a TEE-only key must not claim a dedicated element")
                assertEquals(CustodyTier.Hardware, custody.tier)
            } finally {
                key.close()
            }
        }

    @Test
    fun providerAdvertisesItsCustodyPerAlgorithm() {
        // A single-tier provider reports the same custody for every eligible algorithm, and a hardware
        // provider is (by type) also a non-exportable ProtectedKeyProvider.
        assertEquals(KeyCustody.NonExportable.Hardware(dedicatedSecureElement = true), provider.custody)
        assertEquals(provider.custody, provider.custodyFor(ProtectedKeyAlgorithm.EcdsaP256))
        assertEquals(provider.custody, provider.custodyFor(ProtectedKeyAlgorithm.AesGcm))
        // The tier relationship is compile-time: this assignment only type-checks because a
        // HardwareKeyProvider IS a (non-exportable) ProtectedKeyProvider, and its custody narrows to
        // NonExportable — an exportable hardware provider would not compile.
        val protected: ProtectedKeyProvider = provider
        val nonExportable: KeyCustody.NonExportable = protected.custody
        assertEquals(provider.custody, nonExportable)
    }

    @Test
    fun inMemoryKeysReportExportableSoftwareCustody() {
        // The software floor: an in-memory key self-describes as exportable software — the counter-tier
        // that the hardware assertions above are distinguished from.
        AesGcmKey.of(ascii("0123456789abcdef")).use { aes ->
            assertEquals(KeyCustody.ExportableSoftware, aes.custody)
            assertTrue(aes.custody.exportable, "an in-memory key is exportable")
            assertEquals(KeyProvenance.Software, aes.provenance)
            assertEquals(CustodyTier.ExportableSoftware, aes.custody.tier)
            assertFalse(aes.custody.dedicatedSecureElement, "software custody is not a dedicated element")
        }
    }

    // ---- Key agreement (ECDH P-256) ----

    @Test
    fun hardwareKeyAgreementDerivesTheSameSecretAsItsSoftwareTwin() =
        runTest {
            val keys = provider.keyAgreementPair(grant)
            val ops = keyAgreementAsyncOrNull(KeyAgreementCurve.P256) ?: return@runTest
            val peer = ops.generateKeyPair()
            val info = Info.Of(ascii("hw-ka"))
            // The hardware key derives through its gated closure; the software twin (same scalar)
            // and the peer's own derivation must all land on identical key material — the
            // ProtectedKeyAgreementPrivateKey seam wires through to real ECDH + the shared KDF.
            val viaHardware = ops.deriveSharedSecret(keys.hardware.privateKey, peer.publicKey, info, length = 32)
            val viaTwin = ops.deriveSharedSecret(keys.softwareTwin.privateKey, peer.publicKey, info, length = 32)
            val viaPeer = ops.deriveSharedSecret(peer.privateKey, keys.hardware.publicKey, info, length = 32)
            assertEquals(viaTwin.toHex(), viaHardware.toHex(), "hw derive must match its software twin")
            assertEquals(viaPeer.toHex(), viaHardware.toHex(), "hw derive must match the peer's derivation")
        }

    @Test
    fun hardwareKeyAgreementReportsHardwareProvenanceAndNoExport() =
        runTest {
            val pair = provider.generateKeyAgreement(KeyAgreementCurve.P256, grant)
            assertEquals(KeyProvenance.Hardware, pair.privateKey.provenance)
            assertFailsWith<UnsupportedOperationException>("a non-exportable agreement key has no exportable material") {
                pair.privateKey.exportEncoded()
            }
        }

    @Test
    fun hardwareKeyAgreementDeniedAuthorizationFails() =
        runTest {
            val keys = provider.keyAgreementPair(deny)
            val ops = keyAgreementAsyncOrNull(KeyAgreementCurve.P256) ?: return@runTest
            val peer = ops.generateKeyPair()
            assertFailsWith<AuthorizationFailed> {
                ops.deriveSharedSecret(keys.hardware.privateKey, peer.publicKey, Info.Of(ascii("denied")), length = 32)
            }
        }

    @Test
    fun generateKeyAgreementRejectsIneligibleCurve() =
        runTest {
            // The element backs no X25519 agreement key; refused with the same typed error as any
            // ineligible algorithm.
            assertFailsWith<HardwareKeyException.AlgorithmNotEligible> {
                provider.generateKeyAgreement(KeyAgreementCurve.X25519, grant)
            }
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
                provider.generateAesGcm(ProtectedKeySpec(aesKeySizeBits = 192))
            }
        }

    // ---- User-authentication policies (Session / PerUse) ----
    // The observable contract every UserAuthenticatedKeyProvider must honor, driven through the
    // fake's userAuthenticated() (which captures the prompt gate at construction, exactly like the
    // platform extensions capture their authenticator): when the gate fires, how the session
    // window behaves, and how denial surfaces. The OS-binding halves (keystore
    // UserNotAuthenticatedException, Enclave SecAccessControl) are covered by the device-gated
    // Tier-2 tests.

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
            val authed = FakeHardware(timeSource = clock).userAuthenticated(gate)
            val key = authed.generateAesGcm(UserAuthenticationPolicy.Session(5.minutes))
            val ops = aesGcmAsyncOps()

            ops.seal(key, ascii("first"))
            ops.seal(key, ascii("second"))
            assertEquals(1, gate.calls, "ops inside the window must not re-prompt")

            clock += 6.minutes
            ops.seal(key, ascii("third"))
            assertEquals(2, gate.calls, "a stale window must re-prompt exactly once")
        }

    @Test
    fun sessionDenialSurfacesAsAuthorizationFailedAndDoesNotOpenAWindow() =
        runTest {
            val gate = CountingGate(false, true)
            val authed = FakeHardware(timeSource = TestTimeSource()).userAuthenticated(gate)
            val key = authed.generateAesGcm(UserAuthenticationPolicy.Session(5.minutes))
            val ops = aesGcmAsyncOps()

            assertFailsWith<AuthorizationFailed> { ops.seal(key, ascii("denied")) }
            // The denial must not have started a validity window: the next op prompts again.
            ops.seal(key, ascii("granted"))
            assertEquals(2, gate.calls, "a denied prompt must not open the session window")
        }

    @Test
    fun perUseAuthenticatesEveryOperation() =
        runTest {
            val gate = CountingGate(true)
            val key = provider.userAuthenticated(gate).generateAesGcm(UserAuthenticationPolicy.PerUse())
            val ops = aesGcmAsyncOps()

            val sealed = ops.seal(key, ascii("payload"), Aad.Of(ascii("ctx")))
            ops.open(sealed, key, Aad.Of(ascii("ctx")))
            assertEquals(2, gate.calls, "per-use must authenticate each op, including opens")
        }

    @Test
    fun sessionValidityRejectsSubSecondWindows() {
        assertFailsWith<IllegalArgumentException> {
            UserAuthenticationPolicy.Session(500.milliseconds)
        }
    }

    @Test
    fun authenticatedSigningVerifiesAndPromptsPerPolicy() =
        runTest {
            if (!supportsEcdsaSigningFromScalar) return@runTest
            val gate = CountingGate(true)
            val authed = provider.userAuthenticated(gate)
            val key = authed.generateSigning(SignatureScheme.EcdsaP256, UserAuthenticationPolicy.PerUse())
            val ops = signatureAsyncOrNull(SignatureScheme.EcdsaP256) ?: return@runTest
            val message = ascii("authenticated signature")
            val signature = ops.sign(key, message)
            assertTrue(ops.verify(key.verifyKey, message, signature), "bound hw-sign verifies under its public key")
            assertEquals(1, gate.calls, "per-use signing prompts for the op")
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
