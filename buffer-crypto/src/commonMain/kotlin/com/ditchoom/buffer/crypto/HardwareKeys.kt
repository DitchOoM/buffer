package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/*
 * Hardware-backed key support — the *shape* of the API, frozen in 6.0 so a real secure-element /
 * keystore backend can land later as a non-breaking minor.
 *
 * The whole family is built so that "hardware key" is never a special case a consumer must remember
 * to handle: a [ProtectedAesGcmKey] is an [AesGcmKey] and a [ProtectedSigningKey] is a [SigningKey], so
 * they flow through the exact same capability witnesses ([CryptoCapabilities.aesGcm],
 * [CryptoCapabilities.signatures]). What differs is reified in the type system, not in runtime
 * branching the caller writes:
 *
 *  - [KeyProvenance.Hardware] on the key says the material is not exportable.
 *  - A hardware key is **not** a `SyncCapable*` key ([SyncCapableSigningKey] / [SyncCapableAesGcmKey]),
 *    and the *blocking* witness ops only accept the sync-capable type — so routing a hardware key into
 *    a synchronous op is a compile error, not a runtime throw. Hardware keys only operate through the
 *    **async** witness ops, which dispatch to provider-supplied gated closures. The internal
 *    `requireInMemoryMaterial` seam keeps a defensive throw for the now-unreachable hardware branch.
 *  - An op the secure element declines surfaces as [AuthorizationFailed] (a structured, non-secret
 *    [CryptoMisuseException]), never as the opaque [VerificationFailed].
 *
 * In 6.0 no platform constructs a hardware key — [CryptoCapabilities.hardware] is always
 * [HardwareSupport.Unavailable] — but the machinery (SPI, gated keys, witness dispatch) is real and
 * exercised by a fake provider in the test suite. The families a secure element actually holds are
 * wired: **AES-GCM**, **signatures**, and — where the element backs it (Android Keystore
 * `PURPOSE_AGREE_KEY` on API 31+, Apple `SecureEnclave.P256.KeyAgreement`, both probed rather than
 * inferred) — **ECDH key agreement**. ChaCha20-Poly1305 is excluded
 * (Enclave/StrongBox do not implement it) and verify keys are public (no secret to protect), so
 * neither gets a hardware variant; their sealed types are nonetheless already shaped to accept one
 * without a break.
 */

/**
 * The algorithms a [HardwareKeyProvider] may be asked to generate keys for. A flat, closed enum (not
 * free-text identifiers) so eligibility checks and dispatch are exhaustive and typo-proof. Covers the
 * families a non-exportable key backend (secure element or non-exportable software) actually mints.
 */
enum class ProtectedKeyAlgorithm {
    /** AES-256-GCM (symmetric AEAD). */
    AesGcm,

    /** Ed25519 signatures (RFC 8032). */
    Ed25519,

    /** ECDSA over NIST P-256 with SHA-256. */
    EcdsaP256,

    /** ECDSA over NIST P-384 with SHA-384. */
    EcdsaP384,

    /** ECDSA over NIST P-521 with SHA-512. */
    EcdsaP521,

    /** X25519 key agreement (RFC 7748). */
    X25519,

    /** ECDH key agreement over NIST P-256. */
    EcdhP256,

    /** ECDH key agreement over NIST P-384. */
    EcdhP384,

    /** ECDH key agreement over NIST P-521. */
    EcdhP521,
}

/** Renamed to [ProtectedKeyAlgorithm]. */
@Deprecated("Renamed to ProtectedKeyAlgorithm", ReplaceWith("ProtectedKeyAlgorithm"))
typealias HardwareAlgorithm = ProtectedKeyAlgorithm

/** The [ProtectedKeyAlgorithm] a signature [scheme] maps to, for eligibility checks. */
internal fun SignatureScheme.toProtectedKeyAlgorithm(): ProtectedKeyAlgorithm =
    when (this) {
        SignatureScheme.Ed25519 -> ProtectedKeyAlgorithm.Ed25519
        SignatureScheme.EcdsaP256 -> ProtectedKeyAlgorithm.EcdsaP256
        SignatureScheme.EcdsaP384 -> ProtectedKeyAlgorithm.EcdsaP384
        SignatureScheme.EcdsaP521 -> ProtectedKeyAlgorithm.EcdsaP521
    }

/**
 * A presence/biometric/keystore-unlock gate evaluated before each use of a hardware-backed key.
 * Returning `false` (or throwing) denies the operation, which the witness ops surface as
 * [AuthorizationFailed]. `suspend` because a real gate (a biometric prompt, a TEE round-trip) is
 * inherently asynchronous.
 *
 * This gate is **advisory** — library code deciding whether to proceed. Keys the secure element
 * itself refuses without user authentication come from [UserAuthenticatedKeyProvider] instead,
 * whose platform `userAuthenticated(...)` constructor captures a real prompt authenticator
 * (`BiometricPromptAuthenticator` on Android, `LocalAuthAuthenticator` on Apple).
 */
fun interface HardwareAuthorization {
    /** Returns `true` to authorize the pending hardware key operation, `false` to deny it. */
    suspend fun authorize(): Boolean
}

/**
 * Which kinds of user authentication satisfy a [UserAuthenticationPolicy] binding. Fixed at
 * generation and enforced by the OS, not by library code.
 */
enum class UserAuthenticationMethod {
    /**
     * A strong biometric only (fingerprint / face). The OS invalidates the key when biometric
     * enrollment changes (Android `AUTH_BIOMETRIC_STRONG` + invalidate-on-enrollment, Apple
     * `SecAccessControl.biometryCurrentSet`) — surfacing as [HardwareKeyException.KeyInvalidated].
     */
    BiometricOnly,

    /**
     * A biometric or the device credential (PIN / pattern / passcode). Survives biometric
     * re-enrollment (Android `AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL`, Apple
     * `SecAccessControl.userPresence`).
     */
    BiometricOrCredential,
}

/**
 * How an OS-bound key requires user authentication, fixed at generation. Pure data — no platform
 * handles, identical shape on every target. A policy only ever reaches a provider through
 * [UserAuthenticatedKeyProvider], whose platform constructor already captured the prompt
 * authenticator — so "policy without an authenticator" is unrepresentable, not a runtime error.
 */
sealed interface UserAuthenticationPolicy {
    /**
     * The policies an element enforces with a **validity-window / timestamp** check rather than by
     * binding authentication to one exact operation. This is the subset that composes with *every*
     * key kind on every OS-binding platform — including operations no per-operation auth object can
     * wrap. Key agreement is the motivating case: Android's `BiometricPrompt.CryptoObject` has no
     * `KeyAgreement` overload, so a keystore ECDH key can only be gated by the auth window, never
     * per-derive — [UserAuthenticatedKeyProvider.generateKeyAgreement] therefore accepts only a
     * [Windowed] policy, and per-derive agreement is offered separately through
     * [PerUseAgreementCapable] where the element actually binds it (Apple Secure Enclave).
     */
    sealed interface Windowed : UserAuthenticationPolicy

    /**
     * One successful user authentication unlocks the key for [validity]; ops inside the window do
     * not re-prompt. The provider prompts lazily — it attempts the op and only prompts when the
     * OS reports the window stale — so an already-authenticated user is never re-prompted.
     *
     * Window enforcement is by the OS on Android (keystore auth timeout); on Apple the Enclave has
     * no timed window, so the provider enforces [validity] by re-prompting when it expires — the
     * OS still enforces *that a successful authentication happened* via `SecAccessControl`.
     */
    data class Session(
        val validity: Duration,
        val method: UserAuthenticationMethod = UserAuthenticationMethod.BiometricOrCredential,
    ) : Windowed {
        init {
            require(validity >= 1.seconds) { "session validity must be at least 1 second, was $validity" }
        }
    }

    /**
     * Every operation requires a fresh user authentication bound to that exact operation (Android:
     * auth-per-use key + `BiometricPrompt.CryptoObject` wrapping the exact `Cipher`/`Signature`;
     * Apple: a fresh, un-evaluated `LAContext` per sign, so the Enclave prompts each time).
     *
     * Not a [Windowed] policy: per-op binding needs an auth object wrapping the exact operation,
     * which does not exist for JCA `KeyAgreement`. So a `PerUse` **key agreement** key is reachable
     * only through [PerUseAgreementCapable]; `PerUse` signing / AES-GCM stay on the base provider.
     */
    data class PerUse(
        val method: UserAuthenticationMethod = UserAuthenticationMethod.BiometricOnly,
    ) : UserAuthenticationPolicy
}

/**
 * Mints hardware keys that are **bound to user authentication inside the secure element** — an op
 * on an unauthenticated key fails in hardware no matter what the process does, unlike the advisory
 * [ProtectedKeySpec.authorization] gate on [HardwareKeyProvider]'s unbound keys.
 *
 * Obtained from a [HardwareKeyProvider] through a **platform** `userAuthenticated(...)` extension
 * (androidMain takes a `BiometricAuthorization` prompt host, appleMain a `LocalAuthAuthenticator`),
 * which returns `null` where OS user authentication cannot be driven (a non-platform provider, or
 * a platform without the auth framework — e.g. tvOS). Because the prompt authenticator is captured
 * at construction, the policy values stay pure data and a misconfigured request is a compile
 * error, not a [CryptoException].
 *
 * Keys returned here are ordinary [AesGcmKey]/[SigningKey] values with [KeyProvenance.Hardware]:
 * they flow through the same async witness ops as every other hardware key. What changes is only
 * the reachable failure states: a denied/cancelled prompt is [AuthorizationFailed]; a stale
 * session the user must re-authenticate is [HardwareKeyException.UserAuthenticationRequired]; a
 * biometric re-enrollment invalidating a [UserAuthenticationMethod.BiometricOnly] key is
 * [HardwareKeyException.KeyInvalidated].
 */
interface UserAuthenticatedKeyProvider {
    /** Whether the underlying secure element can generate a key for [alg] (same subset as the base provider). */
    fun eligible(alg: ProtectedKeyAlgorithm): Boolean

    /**
     * Generates a fresh AES-GCM key inside the secure element, OS-bound to [policy].
     * [aesKeySizeBits] is 128 or 256; an unsupported size throws
     * [HardwareKeyException.UnsupportedHardwareKey].
     */
    suspend fun generateAesGcm(
        policy: UserAuthenticationPolicy,
        aesKeySizeBits: Int = AES_256_KEY_BYTES * Byte.SIZE_BITS,
    ): AesGcmKey

    /** Generates a fresh signing key for [scheme] inside the secure element, OS-bound to [policy]. */
    suspend fun generateSigning(
        scheme: SignatureScheme,
        policy: UserAuthenticationPolicy,
    ): SigningKey

    /**
     * Generates a fresh key-agreement key pair for [curve] inside the secure element, OS-bound to a
     * [UserAuthenticationPolicy.Windowed] policy (the element gates each derive by its auth window).
     *
     * The parameter is `Windowed`, not the full policy, on purpose: a per-derive-bound agreement key
     * needs an auth object wrapping the exact `KeyAgreement` op, which Android cannot express — so
     * `PerUse` agreement is not offered here at all. Where the element *can* bind per-derive (Apple
     * Secure Enclave), the provider additionally implements [PerUseAgreementCapable]; narrow to it to
     * request [UserAuthenticationPolicy.PerUse]. An ineligible [curve] throws
     * [HardwareKeyException.AlgorithmNotEligible].
     */
    suspend fun generateKeyAgreement(
        curve: KeyAgreementCurve,
        policy: UserAuthenticationPolicy.Windowed,
    ): KeyAgreementKeyPair
}

/**
 * A [UserAuthenticatedKeyProvider] whose secure element binds authentication to the **exact derive
 * operation** for key agreement — so a fresh user authentication is required for every derive, not
 * merely a recent one within a window. Only the Apple Secure Enclave qualifies: it drives a fresh
 * `LAContext` per agreement, whereas Android's keystore has no `CryptoObject` overload for
 * `KeyAgreement` and can gate ECDH by the auth window only.
 *
 * Obtained by narrowing the provider from `userAuthenticated(...)`:
 * ```kotlin
 * val kex = when (val p = provider.userAuthenticated(prompt)) {
 *     is PerUseAgreementCapable -> p.generateKeyAgreement(P256, UserAuthenticationPolicy.PerUse())
 *     else                      -> p.generateKeyAgreement(P256, UserAuthenticationPolicy.Session(5.minutes))
 * }
 * ```
 * A platform that gains per-derive agreement later implements this interface additively — no ABI
 * break, no deprecation. It is deliberately **not** sealed: the test fake implements it too, and the
 * only compile-time guarantee this design needs (no `PerUse` on the base agreement method) already
 * comes from [UserAuthenticationPolicy.Windowed].
 */
interface PerUseAgreementCapable : UserAuthenticatedKeyProvider {
    /**
     * Generates a key-agreement key pair for [curve] whose every derive requires a fresh user
     * authentication bound to that exact operation. An ineligible [curve] throws
     * [HardwareKeyException.AlgorithmNotEligible].
     */
    suspend fun generateKeyAgreement(
        curve: KeyAgreementCurve,
        policy: UserAuthenticationPolicy.PerUse,
    ): KeyAgreementKeyPair
}

/**
 * Generation parameters for a protected (non-exportable) key. Carried by value (no platform handles)
 * so the shape is identical on every target.
 */
class ProtectedKeySpec(
    /**
     * The advisory gate evaluated before each use of the generated key. The default authorizes
     * unconditionally. Library code deciding whether to proceed — *not* an OS binding; for keys
     * the secure element itself refuses without user authentication, use the platform's
     * `userAuthenticated(...)` extension to obtain a [UserAuthenticatedKeyProvider].
     */
    val authorization: HardwareAuthorization = HardwareAuthorization { true },
    /**
     * AES key size in bits for [HardwareKeyProvider.generateAesGcm] (128 or 256). Ignored when
     * generating a signing key (the curve fixes the size).
     */
    val aesKeySizeBits: Int = AES_256_KEY_BYTES * Byte.SIZE_BITS,
)

/** Renamed to [ProtectedKeySpec]. */
@Deprecated("Renamed to ProtectedKeySpec", ReplaceWith("ProtectedKeySpec"))
typealias HardwareKeySpec = ProtectedKeySpec

/**
 * Service-provider interface a platform implements to mint hardware-backed keys from a secure
 * element / keystore. No platform ships one in 6.0; the type is the integration seam a later minor
 * fills, and the test suite exercises it through a fake.
 *
 * A provider only ever returns the standard sealed key types ([AesGcmKey], [SigningKey]) — the
 * concrete hardware impls are module-internal — so a consumer never names a hardware-specific type.
 *
 * **Conformance obligations for an implementer (normative):** the witness ops invoke the gated
 * closures a provider builds into each key, but the *gate itself is the provider's responsibility* —
 * the common code holds no authorization to evaluate. Therefore an implementation **MUST**, for every
 * key it generates:
 *  1. evaluate [ProtectedKeySpec.authorization] (`authorize()`) before each use of the key, and throw
 *     [AuthorizationFailed] when it returns `false` or throws — never proceed with the crypto op;
 *  2. keep secret key material inside the secure element — never expose it through any returned type
 *     (the [KeyProvenance.Hardware] key types carry no exportable material by construction);
 *  3. refuse generation for an [alg] where [eligible] is `false` with
 *     [HardwareKeyException.AlgorithmNotEligible].
 *
 * A [UserAuthenticatedKeyProvider] obtained from this provider additionally **MUST** bind its
 * keys' [UserAuthenticationPolicy] *inside the secure element* (Android
 * `setUserAuthenticationRequired`, Apple `SecAccessControl`), so the binding is not bypassable by
 * code that skips any library gate; it drives its captured platform authenticator per policy —
 * [UserAuthenticationPolicy.Session]: lazily, when the OS reports the window stale, then retry
 * the op exactly once; [UserAuthenticationPolicy.PerUse]: for every op, bound to the exact crypto
 * operation where the platform supports binding.
 */
interface HardwareKeyProvider : ProtectedKeyProvider {
    /**
     * `true` if keys live in a dedicated secure element (StrongBox / Secure Enclave), `false` for a
     * software-isolated keystore (TEE-only). Informational; does not change the op contract.
     */
    val dedicatedSecureElement: Boolean

    /**
     * A hardware provider's keys are always [KeyCustody.NonExportable.Hardware]; the narrowed return
     * type re-states, at the type level, that a hardware key can never be exportable or
     * software-backed. Derived from [dedicatedSecureElement] so the two can never disagree.
     */
    override val custody: KeyCustody.NonExportable.Hardware
        get() = KeyCustody.NonExportable.Hardware(dedicatedSecureElement)

    /** Whether this provider can generate a key for [alg] (a secure element backs only a subset). */
    override fun eligible(alg: ProtectedKeyAlgorithm): Boolean

    /**
     * Generates a fresh AES-GCM key inside the secure element. The returned [AesGcmKey] has
     * [KeyProvenance.Hardware] and no exportable material; it operates only through the async AEAD
     * witness ops, gated by [spec]'s [ProtectedKeySpec.authorization].
     */
    override suspend fun generateAesGcm(spec: ProtectedKeySpec): AesGcmKey

    /**
     * Generates a fresh signing key for [scheme] inside the secure element. The returned [SigningKey]
     * has [KeyProvenance.Hardware] and no exportable material; it signs only through the async
     * signature witness ops, gated by [spec]'s [ProtectedKeySpec.authorization].
     */
    override suspend fun generateSigning(
        scheme: SignatureScheme,
        spec: ProtectedKeySpec,
    ): SigningKey
}

/**
 * Whether hardware-backed key generation is reachable on this platform.
 *
 *  - [Unavailable] — no secure-element backend (every platform in 6.0).
 *  - [Available] — a [HardwareKeyProvider] is installed (reserved for a later minor).
 */
sealed interface HardwareSupport {
    /** No hardware-backed key provider on this platform. */
    data object Unavailable : HardwareSupport

    /** A hardware-backed key [provider] is available. */
    data class Available(
        val provider: HardwareKeyProvider,
    ) : HardwareSupport
}

/**
 * The hardware-backed key capability on this platform: [HardwareSupport.Available] where a secure
 * element / keystore backend is wired and actually usable (Android Keystore, Apple Secure Enclave),
 * [HardwareSupport.Unavailable] elsewhere (JVM, JS/WASM, Linux).
 *
 * A plain `val` (not `expect`/`actual`) by design: the *public* signature is frozen, and a backend
 * resolves through the **internal** [platformProtectedKeyProvider] seam, so a later platform can wire
 * a provider without changing this declaration. The seam is `internal`, so it never enters the
 * public ABI. This is the secure-element-only refinement of [CryptoCapabilities.protectedKeys]: it
 * reports [HardwareSupport.Available] only when the platform's non-exportable provider is a
 * [HardwareKeyProvider], so a non-exportable *software* provider (WebCrypto) is deliberately not
 * surfaced here.
 */
val CryptoCapabilities.hardware: HardwareSupport get() =
    (platformProtectedKeyProvider() as? HardwareKeyProvider)
        ?.let { HardwareSupport.Available(it) }
        ?: HardwareSupport.Unavailable

// =============================================================================
// Internal hardware-backed key impls — gated async closures, never exportable material
// =============================================================================
//
// These are `internal`, so they are absent from the public ABI and downstream cannot name them
// (preserving the sealed-with-internal-impls contract). The crypto behaviour is *not* in this
// module: each key holds suspend closures the provider supplies, so commonMain never references the
// test fake (or any future TEE backend). The closures embed the authorization gate.

/**
 * A gated AES-GCM seal: yields a fully self-framed `nonce ‖ ciphertext ‖ tag` buffer, or throws
 * [AuthorizationFailed]. The *secure element generates the nonce* (an Android Keystore / Enclave key
 * picks its own GCM IV at `init`, and one reused GCM nonce is catastrophic), so — unlike the open
 * seam — no nonce crosses in. The closure is responsible for framing the keystore-chosen nonce ahead
 * of the ciphertext so the standard [splitFramed] open path works unchanged.
 */
internal typealias HardwareAesGcmSeal =
    suspend (aad: ReadBuffer?, plaintext: ReadBuffer, factory: BufferFactory) -> PlatformBuffer

/** A gated AES-GCM open-with-explicit-nonce: yields recovered plaintext, throws on bad tag or denied auth. */
internal typealias HardwareAesGcmOpen =
    suspend (nonce: ReadBuffer, aad: ReadBuffer?, ciphertextAndTag: ReadBuffer, factory: BufferFactory) -> PlatformBuffer

/** A gated sign: yields a signature buffer or throws [AuthorizationFailed]. */
internal typealias HardwareSign =
    suspend (message: ReadBuffer, factory: BufferFactory) -> ReadBuffer

/**
 * A gated raw Diffie–Hellman: computes `DH(sk, peer)` inside the non-exportable backend and yields
 * the **raw** shared secret in a wiped [SecureBuffer] (read-ready, `curve.sharedSecretBytes` bytes),
 * or throws [AuthorizationFailed] / [InvalidPublicKey]. This is exactly the KEM's `DH(sk, enc)`
 * primitive: the caller (the common key-agreement / DHKEM seam) runs the HKDF / all-zero rejection
 * above it, so the whole `keyAgreement()` / `hpke()` machinery composes with a non-exportable
 * private key whose scalar never enters process memory.
 */
internal typealias HardwareDh =
    suspend (peer: KeyAgreementPublicKey) -> PlatformBuffer

/**
 * Non-exportable [AesGcmKey]: an opaque handle with no exportable material. Seal/open route through
 * the provider-supplied gated closures; the blocking witness path is unreachable because
 * [requireInMemoryMaterial] throws for this type. The [custody] param type [KeyCustody.NonExportable]
 * makes an *exportable* protected key unconstructable — it is either non-exportable software
 * (WebCrypto) or hardware. [provenance] is derived from it and can never disagree.
 */
internal class ProtectedAesGcmKey(
    override val sizeBits: Int,
    override val custody: KeyCustody.NonExportable,
    val gatedSeal: HardwareAesGcmSeal,
    val gatedOpen: HardwareAesGcmOpen,
    private val onClose: () -> Unit = {},
) : AesGcmKey {
    /**
     * Opaque handle; there is no process-memory material to wipe. [onClose] lets a provider release
     * an out-of-process resource (e.g. delete the keystore alias). Defaults to a no-op.
     */
    override fun close() = onClose()
}

/**
 * Non-exportable [SigningKey]: an opaque handle with no exportable material. Signing routes through
 * the provider-supplied gated closure; the blocking witness path is unreachable because
 * [requireInMemoryMaterial] throws for this type. The [custody] param type [KeyCustody.NonExportable]
 * makes an *exportable* protected key unconstructable; [provenance] is derived from it.
 */
internal class ProtectedSigningKey(
    override val scheme: SignatureScheme,
    override val custody: KeyCustody.NonExportable,
    val gatedSign: HardwareSign,
    /** The public key matching this signing key, captured by the provider at generation. */
    override val verifyKey: VerifyKey,
    private val onClose: () -> Unit = {},
) : SigningKey {
    /**
     * Opaque handle; there is no process-memory material to wipe. [onClose] lets a provider release
     * an out-of-process resource (e.g. delete the keystore alias). Defaults to a no-op.
     */
    override fun close() = onClose()
}
