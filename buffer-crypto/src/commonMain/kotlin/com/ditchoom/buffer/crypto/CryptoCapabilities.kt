package com.ditchoom.buffer.crypto

/**
 * One discoverable surface for the per-platform crypto capability flags that vary by platform.
 *
 * AEAD capability is **not** here — it is reified as a [capability witness][Aead] reachable via
 * [CryptoCapabilities.aesGcm] / [CryptoCapabilities.chaChaPoly], so an unsupported AEAD path is
 * unrepresentable rather than a boolean a caller might forget to check. The signature /
 * key-agreement / HPKE flags below remain plain booleans pending their own witness reshape.
 *
 * ```kotlin
 * // AEAD: exhaustive when over the witness — the web (AsyncOnly) cannot reach sealBlocking.
 * val sealed = when (val gcm = CryptoCapabilities.aesGcm) {
 *     is Aead.Blocking  -> gcm.ops.sealBlocking(key, plaintext)
 *     is Aead.AsyncOnly -> gcm.ops.seal(key, plaintext)
 * }
 *
 * // HPKE: check the whole suite before use.
 * if (CryptoCapabilities.hpke(suite)) { /* setup sender/receiver */ }
 * ```
 */
object CryptoCapabilities {
    /**
     * Whether Ed25519 sign/verify is available synchronously (JVM 15+, Apple, Android API 34+).
     * `false` on JS/WASM (use the `*Async` sign/verify there).
     */
    val ed25519Sync: Boolean get() = supportsSyncEd25519

    /** Whether ECDSA sign/verify over the NIST P-curves is available synchronously. */
    val ecdsaSync: Boolean get() = supportsSyncEcdsa

    /**
     * Whether ECDSA **signing from a bare private scalar** is supported (`false` on Apple, which
     * needs the full X9.63 private representation). ECDSA *verification* is available regardless.
     */
    val ecdsaSigningFromScalar: Boolean get() = supportsEcdsaSigningFromScalar

    /** Whether key agreement over [curve] (X25519 / P-256 / P-384 / P-521) has a synchronous path here. */
    fun keyAgreementSync(curve: KeyAgreementCurve): Boolean = supportsSync(curve)

    /** Whether the HPKE [suite] is usable on this platform — its KEM curve and AEAD are both available. */
    fun hpke(suite: HpkeSuite): Boolean = hpkeSupported(suite)
}
