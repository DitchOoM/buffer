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
 *  - **Capability gating is outside this hierarchy**: "this platform/engine does not provide the
 *    algorithm" throws a plain [UnsupportedOperationException], not a [CryptoException] — it is an
 *    environment/programming condition, not a crypto-operation failure, and the capability
 *    witnesses ([CryptoCapabilities]) exist precisely so callers probe support instead of catching
 *    it. The cost is that it is not part of the exhaustive sealed `when`; branch on the witness,
 *    not the exception.
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
 * The custody on offer for [alg] ([available]) is weaker than the [required] tier: either a
 * [KeyProvider.requireTier] assertion failed, or a key a hardware backend just generated or reattached
 * reported a weaker security level than the backend promises (an OS keystore that silently placed the
 * key in software). In the second case the key has already been deleted, so nothing weaker than
 * [required] is ever handed out. Structured and non-secret — it names only public tiers, never key
 * material. Only ever constructed on the unmet path, so an "available ≥ required" instance is
 * unrepresentable.
 */
class InsufficientKeyCustody internal constructor(
    val alg: ProtectedKeyAlgorithm,
    val required: CustodyTier,
    /** What the platform reported for the key or algorithm; [OfferedCustody.Unreported] when it named no level. */
    val offered: OfferedCustody,
) : CryptoMisuseException("$alg requires custody $required, platform offers only $offered") {
    internal constructor(
        alg: ProtectedKeyAlgorithm,
        required: CustodyTier,
        available: CustodyTier,
    ) : this(alg, required, OfferedCustody.Tier(available))

    /**
     * The strongest tier the platform vouches for. An [OfferedCustody.Unreported] level vouches for none, so it is
     * [CustodyTier.ExportableSoftware]; read [offered] to tell that apart from a reported tier.
     */
    val available: CustodyTier
        get() =
            when (val o = offered) {
                is OfferedCustody.Tier -> o.tier
                OfferedCustody.Unreported -> CustodyTier.ExportableSoftware
            }
}

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

    /**
     * The key's protection class is unavailable while the device is locked (Apple: an Enclave key made under a
     * when-unlocked class, or any Enclave operation on a locked device that the OS refuses). Retry after the unlock;
     * the key itself is unchanged.
     */
    class DeviceLocked internal constructor() : HardwareKeyException("hardware key unavailable until device unlock")

    /**
     * This process may not use the secure element at all: the binary lacks the entitlement it requires. Retrying
     * does not help; the fix is in how the process is signed.
     */
    class AccessDenied internal constructor() : HardwareKeyException("secure element access denied to this process")

    /** The secure element does not support the requested algorithm / key size / parameters. */
    class UnsupportedHardwareKey internal constructor() : HardwareKeyException("unsupported hardware key parameters")

    /**
     * A [ProtectedKeyAlgorithm] where [KeyProvider.eligible] is `false` was requested — either at
     * generation (e.g. AES-GCM on the Apple Secure Enclave) or as a [KeyProvider.requireTier]
     * assertion on a single-backend store that cannot serve it (e.g. Ed25519 on a hardware
     * [KeyStore]). Typed — not an `IllegalArgumentException` with a message — so a caller can branch
     * on it exhaustively; the fix is to consult [KeyProvider.eligible] first.
     */
    class AlgorithmNotEligible internal constructor() : HardwareKeyException("algorithm not hardware-eligible")
}

/**
 * A persistent [KeyStore] operation failed for a public, non-secret reason. `sealed` so callers
 * handle each outcome exhaustively without parsing a message. No field carries key material — an
 * alias is a caller-chosen public name.
 */
sealed class KeyStoreException protected constructor(
    message: String,
) : CryptoMisuseException(message) {
    /**
     * A `getOrGenerate*` call named an [alias] that already holds a key of a different algorithm than
     * [requested] — the store never silently replaces a stored key, so a device-identity key cannot
     * be clobbered by a mistyped call. Regenerate deliberately with [KeyStore.delete] first.
     */
    class AliasMismatch internal constructor(
        val alias: String,
        val stored: ProtectedKeyAlgorithm,
        val requested: ProtectedKeyAlgorithm,
    ) : KeyStoreException("alias '$alias' holds $stored, not the requested $requested")

    /**
     * The backing store could not be reached (disk IO, keystore/Keychain access, a platform keystore
     * that failed to open). [retryable] mirrors the platform's guidance where it provides one; a raw
     * platform IO exception is normalized into this rather than leaking through. A store that could
     * not be opened is opened again on the next call, never replaced by a weaker one.
     */
    class StorageFailure internal constructor(
        val retryable: Boolean,
    ) : KeyStoreException("key store backend unavailable")

    /**
     * The store's entries are unreadable until the device is unlocked — the OS withholds the protected
     * data (Apple `errSecInteractionNotAllowed`). Always worth retrying once the device is unlocked;
     * nothing about the stored keys changed.
     */
    class DeviceLocked internal constructor() : KeyStoreException("key store unavailable until device unlock")

    /**
     * This process may not reach the store at all: the binary lacks the entitlement the backend
     * requires, or the platform refused access to the item. Retrying does not help; the fix is in how
     * the process is signed or configured. A [KeyStoreConfig.storage] medium is the escape hatch for a
     * process that must persist keys without the platform store.
     */
    class AccessDenied internal constructor() : KeyStoreException("key store access denied to this process")

    /**
     * The OS asked the user to allow access to the store and the user dismissed the prompt or failed it (Apple
     * `errSecUserCanceled`, `errSecAuthFailed`). Retry on the user's own action, not in a loop that prompts again.
     */
    class AuthenticationDeclined internal constructor() : KeyStoreException("key store access declined by the user")

    /**
     * The store needs the user to unlock it or approve access, and this process may not show that prompt (macOS
     * `errSecInteractionNotAllowed`: a locked login keychain or an access prompt, reached from a session without UI).
     * Retry from a session that can prompt, or after the keychain is unlocked.
     */
    class InteractionRequired internal constructor() : KeyStoreException("key store needs user interaction")

    /** A stored entry could not be decoded — its bytes are truncated, corrupt, or a newer format. */
    class CorruptEntry internal constructor() : KeyStoreException("stored key entry is corrupt or unreadable")
}
