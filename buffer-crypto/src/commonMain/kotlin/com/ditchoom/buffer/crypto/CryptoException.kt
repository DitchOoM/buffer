package com.ditchoom.buffer.crypto

/**
 * Root of crypto-primitive failures that originate from the platform's native crypto
 * stack. Sealed so callers can handle every cross-platform outcome exhaustively.
 *
 * Each platform throws its own incompatible types for the same failure — a bad AEAD tag
 * is `javax.crypto.AEADBadTagException` on JVM/Android, a thrown `CryptoKitError` on Apple,
 * and a rejected `DOMException` on WebCrypto. The `actual` implementations **normalize**
 * those into this hierarchy so the failure contract is identical on every target.
 *
 * Two deliberately different design rules apply to the two branches below:
 *
 *  - [CryptoMisuseException] (caller/operational error) is **structured and granular** —
 *    it carries typed properties, not just a free-text message — so misuse is easy to
 *    diagnose and assert on deterministically.
 *
 *  - [VerificationFailed] (a cryptographic check did not pass) is **deliberately opaque
 *    and uniform** — it never reveals *why* the check failed. Distinguishable failure
 *    reasons on a verification path are an oracle (the padding-oracle class of attack):
 *    a bad tag, tampered AAD, wrong length, and a malformed signature all surface as the
 *    same exception with no distinguishing data and no [cause].
 *
 * Invariant for every subtype: **no secret material** (keys, plaintext, shared secrets,
 * derived key material) ever appears in any message, property, or cause.
 */
sealed class CryptoException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * A cryptographic verification failed: an AEAD tag did not authenticate, a signature did
 * not verify, or an authenticated decryption otherwise failed.
 *
 * Intentionally carries no reason and no [cause] — two distinct failures are
 * indistinguishable by design, so nothing here can be used as a decryption/verification
 * oracle. The caller already knows which operation it invoked; it does not get to learn
 * *why* that operation rejected the input.
 *
 * Constructed only by the platform `actual` code (hence `internal`); callers match on the
 * type, never instantiate it.
 */
class VerificationFailed internal constructor() : CryptoException("crypto verification failed")

/**
 * A caller/operational error: the inputs or platform state made the operation invalid
 * before (or independent of) any secret-dependent check. Granular and structured by
 * design — these describe public, non-secret conditions and are safe to expose in detail.
 */
sealed class CryptoMisuseException(
    message: String,
) : CryptoException(message)

/**
 * A supplied public key / public point was rejected as invalid for the named [curve]
 * (e.g. an identity / low-order point that would enable a small-subgroup attack, or a
 * point not on the curve).
 *
 * Public keys are not secret, so this is reported explicitly: it indicates a malformed or
 * malicious *peer* input, which the caller generally wants to surface and log.
 */
class InvalidPublicKey internal constructor(
    val curve: String,
) : CryptoMisuseException("invalid public key for $curve")

/**
 * A hardware-backed key operation was not authorized: the secure element / keystore declined to
 * unlock the key for use (e.g. a required user-presence / biometric gate was not satisfied, or the
 * platform's [HardwareKeyProvider] auth callback denied the request).
 *
 * This is a *public, non-secret* operational condition — it tells the caller the op was refused,
 * never *why* the underlying key material is what it is — so it is a [CryptoMisuseException] rather
 * than the opaque [VerificationFailed]. Only the platform/provider code constructs it (hence
 * `internal`); callers match on the type.
 */
class AuthorizationFailed internal constructor() : CryptoMisuseException("hardware key authorization failed")
