package com.ditchoom.buffer.crypto

/**
 * Platform supertype seam for [CryptoException]. On JVM/Android this is a `typealias` to
 * `java.security.GeneralSecurityException` — the natural JCA umbrella — so every crypto failure is
 * **also** the natural platform exception type and existing `catch (GeneralSecurityException)` keeps
 * working; on every other target it is a plain [Exception]. `internal` constructors, so the only way
 * to obtain one is through the sealed [CryptoException] hierarchy.
 */
expect open class NativeCryptoException : Exception {
    internal constructor(message: String?)

    internal constructor(message: String?, cause: Throwable?)
}

/**
 * Root of crypto-primitive failures that originate from the platform's native crypto stack. `sealed`
 * so callers can handle every cross-platform outcome **exhaustively** with a `when`, and — via
 * [NativeCryptoException] — also catchable as the natural platform exception type.
 *
 * Each platform throws its own incompatible types for the same failure — a bad AEAD tag is
 * `javax.crypto.AEADBadTagException` on JVM/Android, a `CryptoKitError` on Apple, a `DOMException` on
 * WebCrypto, an `android.security.KeyStoreException` from the keystore. The `actual` implementations
 * **normalize** those into this hierarchy so the failure contract is identical on every target.
 *
 * Design rules:
 *  - The hierarchy is the contract: callers branch on the **sealed type** (and the few typed,
 *    non-secret fields below), never on the free-text [message]. No subtype exposes a `String`
 *    discriminator — reasons are reified as distinct types ([HardwareKeyException] states) or typed
 *    values ([InvalidPublicKey.curve]).
 *  - [VerificationFailed] is **deliberately opaque** — it carries no reason and no [cause], so a
 *    verification path can never become an oracle.
 *
 * Invariant for every subtype: **no secret material** (keys, plaintext, shared secrets, derived key
 * material) ever appears in any message, property, or cause.
 */
sealed class CryptoException protected constructor(
    message: String,
    cause: Throwable? = null,
) : NativeCryptoException(message, cause)

/**
 * A cryptographic verification failed: an AEAD tag did not authenticate, a signature did not verify,
 * or an authenticated decryption otherwise failed.
 *
 * Intentionally carries no reason and no [cause] — two distinct failures are indistinguishable by
 * design, so nothing here can be used as a decryption/verification oracle. The caller already knows
 * which operation it invoked; it does not get to learn *why* that operation rejected the input.
 */
class VerificationFailed internal constructor() : CryptoException("crypto verification failed")

/**
 * A caller/operational error: the inputs or platform state made the operation invalid before (or
 * independent of) any secret-dependent check. Granular and structured by design — these describe
 * public, non-secret conditions, reified as distinct types so a `when` handles them exhaustively.
 */
sealed class CryptoMisuseException protected constructor(
    message: String,
) : CryptoException(message)

/**
 * A supplied public key / public point was rejected as invalid for [curve] (e.g. an identity /
 * low-order point that would enable a small-subgroup attack, or a point not on the curve). The curve
 * is the typed [KeyAgreementCurve], not a string. Public keys are not secret, so this is reported
 * explicitly: it indicates a malformed or malicious *peer* input the caller generally wants to log.
 */
class InvalidPublicKey internal constructor(
    val curve: KeyAgreementCurve,
) : CryptoMisuseException("invalid public key for ${curve.curveName}")

/**
 * The platform's [HardwareKeyProvider] auth gate ([HardwareAuthorization.authorize]) denied the
 * operation before any crypto ran. Distinct from the keystore-originated
 * [HardwareKeyException.UserAuthenticationRequired]: this is the *SPI gate* refusing, not the secure
 * element reporting a stale authentication.
 */
class AuthorizationFailed internal constructor() : CryptoMisuseException("hardware key authorization denied")

/**
 * A hardware-backed key operation failed in the secure element / keystore itself. `sealed` so every
 * distinct outcome is its own type — callers handle them exhaustively without parsing a message, and
 * each maps to a natural platform exception (e.g. on Android: `StrongBoxUnavailableException`,
 * `UserNotAuthenticatedException`, `KeyPermanentlyInvalidatedException`, a transient
 * `ProviderException` / `KeyStoreException.isTransientFailure()`).
 */
sealed class HardwareKeyException protected constructor(
    message: String,
) : CryptoMisuseException(message) {
    /** The secure element could not satisfy the request (no StrongBox/Enclave for this key). */
    class SecureElementUnavailable internal constructor() : HardwareKeyException("secure element unavailable")

    /**
     * The key requires user authentication that has not happened recently enough — recoverable by
     * authenticating and retrying (the keystore's `UserNotAuthenticatedException`).
     */
    class UserAuthenticationRequired internal constructor() : HardwareKeyException("user authentication required")

    /**
     * The key was *permanently* invalidated (secure lock screen removed/reset, or biometric
     * enrollment changed) — not recoverable; the key must be regenerated. Maps to the keystore's
     * `KeyPermanentlyInvalidatedException`.
     */
    class KeyInvalidated internal constructor() : HardwareKeyException("hardware key permanently invalidated")

    /**
     * A transient secure-hardware failure (busy / rate-limited / temporarily unavailable). [retryable]
     * reflects the platform's retry guidance (e.g. Android's `KeyStoreException.isTransientFailure()`).
     */
    class TransientHardwareFailure internal constructor(
        val retryable: Boolean,
    ) : HardwareKeyException("transient secure-hardware failure")

    /** The secure element does not support the requested algorithm / key size / parameters. */
    class UnsupportedHardwareKey internal constructor() : HardwareKeyException("unsupported hardware key parameters")
}
