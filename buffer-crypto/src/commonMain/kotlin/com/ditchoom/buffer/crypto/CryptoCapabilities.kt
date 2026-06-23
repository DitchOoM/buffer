package com.ditchoom.buffer.crypto

/**
 * One discoverable surface for the per-platform crypto capability flags that are otherwise spread
 * across [Aead], [Signatures], [KeyAgreement], and [Hpke]. Every member here is a thin alias over
 * an existing `supports*` declaration — the underlying `expect val`s remain the source of truth;
 * this only gathers them so a caller can branch on availability (and pick the synchronous vs
 * `suspend` path) from a single place.
 *
 * Availability has **two axes**:
 *  - [AeadAvailability.anyPath] / "available at all": the primitive works on this platform via
 *    *some* path — a synchronous call or a `suspend` `*Async` call. WebCrypto primitives on
 *    JS/WASM are async-only, so they are `anyPath = true` but `sync = false`.
 *  - [AeadAvailability.sync] / the `*Sync` booleans: a synchronous (non-`suspend`) entry point
 *    exists. When sync is `false` but the primitive is available, use the `*Async` variant.
 *
 * ```kotlin
 * // AEAD: prefer the sync one-shot, fall back to the async path where only that exists (web).
 * val sealed =
 *     if (CryptoCapabilities.aesGcm.sync) aesGcmSeal(key, nonce, plaintext, dest)
 *     else aesGcmSealAsync(key, nonce, plaintext, dest)
 *
 * // HPKE: check the whole suite before use.
 * if (CryptoCapabilities.hpke(suite)) { /* setup sender/receiver */ }
 * ```
 */
object CryptoCapabilities {
    /**
     * Availability of an AEAD primitive.
     *
     * @property sync a synchronous one-shot entry point exists on this platform
     * @property anyPath the primitive is usable via some path — synchronous or `suspend` async
     */
    data class AeadAvailability internal constructor(
        val sync: Boolean,
        val anyPath: Boolean,
    )

    /**
     * AES-GCM (128/256-bit). Synchronous on JVM/Android/Apple; async-only on JS/WASM (WebCrypto's
     * `SubtleCrypto` is `suspend`) — so [AeadAvailability.anyPath] is `true` everywhere but
     * [AeadAvailability.sync] is `false` on the web.
     */
    val aesGcm: AeadAvailability = AeadAvailability(sync = supportsSyncAesGcm, anyPath = supportsAesGcmAnyPath)

    /**
     * ChaCha20-Poly1305. Not part of WebCrypto and never polyfilled, so it is unavailable on
     * JS/WASM by **either** path ([AeadAvailability.anyPath] is `false` there); elsewhere it is
     * synchronous.
     */
    val chaChaPoly: AeadAvailability = AeadAvailability(sync = supportsSyncChaChaPoly, anyPath = supportsChaChaPoly)

    /** Availability of the AEAD named by an HPKE [aead] id. */
    fun aead(aead: HpkeAead): AeadAvailability =
        when (aead) {
            HpkeAead.Aes128Gcm, HpkeAead.Aes256Gcm -> aesGcm
            HpkeAead.ChaCha20Poly1305 -> chaChaPoly
        }

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
