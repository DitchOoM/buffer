package com.ditchoom.buffer.crypto

/**
 * One discoverable surface for the per-platform crypto capability flags that vary by platform.
 *
 * AEAD, signature, key-agreement, and HPKE capability are **not** here — each is reified as a
 * capability witness ([Aead] / [OptionalAead] via [CryptoCapabilities.aesGcm] /
 * [CryptoCapabilities.chaChaPoly]; [SignatureSupport] via [CryptoCapabilities.signatures];
 * [KeyAgreementSupport] via [CryptoCapabilities.keyAgreement]; [HpkeSupport] via
 * [CryptoCapabilities.hpke]), so an unsupported op is unrepresentable rather than a boolean a caller
 * might forget to check.
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
 * // HPKE: the suite ops are reachable only through the Supported witness.
 * when (val h = CryptoCapabilities.hpke(suite)) {
 *     is HpkeSupport.Supported   -> h.ops.sealBase(recipientPublicKey, info, plaintext)
 *     is HpkeSupport.Unsupported -> { /* h.missing names the absent primitive */ }
 * }
 * ```
 */
object CryptoCapabilities {
    /**
     * Whether ECDSA **signing from a bare private scalar** is supported (a key-construction
     * capability, not an op variant: ECDSA *verification* is available on every platform regardless).
     */
    val ecdsaSigningFromScalar: Boolean get() = supportsEcdsaSigningFromScalar
}
