package com.ditchoom.buffer.crypto

/**
 * One discoverable surface for the per-platform crypto capability flags that vary by platform.
 *
 * AEAD and signature capability are **not** here — each is reified as a capability witness
 * ([Aead] / [OptionalAead] via [CryptoCapabilities.aesGcm] / [CryptoCapabilities.chaChaPoly];
 * [SignatureSupport] via [CryptoCapabilities.signatures]), so an unsupported op is unrepresentable
 * rather than a boolean a caller might forget to check. The key-agreement / HPKE flags below remain
 * plain booleans pending their own witness reshape.
 *
 * ```kotlin
 * // AEAD: exhaustive when over the witness — the web (AsyncOnly) cannot reach sealBlocking.
 * val sealed = when (val gcm = CryptoCapabilities.aesGcm) {
 *     is Aead.Blocking  -> gcm.ops.sealBlocking(key, plaintext)
 *     is Aead.AsyncOnly -> gcm.ops.seal(key, plaintext)
 * }
 *
 * // Signatures: the unsupported op is not a member of the resolved witness.
 * when (val s = CryptoCapabilities.signatures(SignatureScheme.Ed25519)) {
 *     is SignatureSupport.Blocking  -> s.ops.signInto(key, message, dest)
 *     is SignatureSupport.AsyncOnly -> s.ops.sign(key, message)
 *     SignatureSupport.Unavailable  -> { /* not reachable here */ }
 * }
 *
 * // HPKE: check the whole suite before use.
 * if (CryptoCapabilities.hpke(suite)) { /* setup sender/receiver */ }
 * ```
 */
object CryptoCapabilities {
    /**
     * Whether ECDSA **signing from a bare private scalar** is supported (a key-construction
     * capability, not an op variant: ECDSA *verification* is available on every platform regardless).
     */
    val ecdsaSigningFromScalar: Boolean get() = supportsEcdsaSigningFromScalar

    /** Whether key agreement over [curve] (X25519 / P-256 / P-384 / P-521) has a synchronous path here. */
    fun keyAgreementSync(curve: KeyAgreementCurve): Boolean = supportsSync(curve)

    /** Whether the HPKE [suite] is usable on this platform — its KEM curve and AEAD are both available. */
    fun hpke(suite: HpkeSuite): Boolean = hpkeSupported(suite)
}
