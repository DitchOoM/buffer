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
 * to handle: a [HardwareAesGcmKey] is an [AesGcmKey] and a [HardwareSigningKey] is a [SigningKey], so
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
 * exercised by a fake provider in the test suite. Only the two families a secure element actually
 * holds are wired: **AES-GCM** and **signatures**. ChaCha20-Poly1305 is excluded (Enclave/StrongBox
 * do not implement it) and verify keys are public (no secret to protect), so neither gets a hardware
 * variant; their sealed types are nonetheless already shaped to accept one without a break.
 */

/**
 * The algorithms a [HardwareKeyProvider] may be asked to generate keys for. A flat, closed enum (not
 * free-text identifiers) so eligibility checks and dispatch are exhaustive and typo-proof. Only the
 * families a secure element actually backs appear here.
 */
enum class HardwareAlgorithm {
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
}

/** The [HardwareAlgorithm] a signature [scheme] maps to, for eligibility checks. */
internal fun SignatureScheme.toHardwareAlgorithm(): HardwareAlgorithm =
    when (this) {
        SignatureScheme.Ed25519 -> HardwareAlgorithm.Ed25519
        SignatureScheme.EcdsaP256 -> HardwareAlgorithm.EcdsaP256
        SignatureScheme.EcdsaP384 -> HardwareAlgorithm.EcdsaP384
        SignatureScheme.EcdsaP521 -> HardwareAlgorithm.EcdsaP521
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
    ) : UserAuthenticationPolicy {
        init {
            require(validity >= 1.seconds) { "session validity must be at least 1 second, was $validity" }
        }
    }

    /**
     * Every operation requires a fresh user authentication bound to that exact operation (Android:
     * auth-per-use key + `BiometricPrompt.CryptoObject` wrapping the exact `Cipher`/`Signature`;
     * Apple: a fresh, un-evaluated `LAContext` per sign, so the Enclave prompts each time).
     */
    data class PerUse(
        val method: UserAuthenticationMethod = UserAuthenticationMethod.BiometricOnly,
    ) : UserAuthenticationPolicy
}

/**
 * Mints hardware keys that are **bound to user authentication inside the secure element** — an op
 * on an unauthenticated key fails in hardware no matter what the process does, unlike the advisory
 * [HardwareKeySpec.authorization] gate on [HardwareKeyProvider]'s unbound keys.
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
    fun eligible(alg: HardwareAlgorithm): Boolean

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
}

/**
 * Generation parameters for a hardware-backed key. Carried by value (no platform handles) so the
 * shape is identical on every target.
 */
class HardwareKeySpec(
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
 *  1. evaluate [HardwareKeySpec.authorization] (`authorize()`) before each use of the key, and throw
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
interface HardwareKeyProvider {
    /**
     * `true` if keys live in a dedicated secure element (StrongBox / Secure Enclave), `false` for a
     * software-isolated keystore (TEE-only). Informational; does not change the op contract.
     */
    val dedicatedSecureElement: Boolean

    /** Whether this provider can generate a key for [alg] (a secure element backs only a subset). */
    fun eligible(alg: HardwareAlgorithm): Boolean

    /**
     * Generates a fresh AES-GCM key inside the secure element. The returned [AesGcmKey] has
     * [KeyProvenance.Hardware] and no exportable material; it operates only through the async AEAD
     * witness ops, gated by [spec]'s [HardwareKeySpec.authorization].
     */
    suspend fun generateAesGcm(spec: HardwareKeySpec): AesGcmKey

    /**
     * Generates a fresh signing key for [scheme] inside the secure element. The returned [SigningKey]
     * has [KeyProvenance.Hardware] and no exportable material; it signs only through the async
     * signature witness ops, gated by [spec]'s [HardwareKeySpec.authorization].
     */
    suspend fun generateSigning(
        scheme: SignatureScheme,
        spec: HardwareKeySpec,
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
 * resolves through the **internal** [platformHardwareKeyProvider] seam, so a later platform can wire
 * a provider without changing this declaration. The seam is `internal`, so it never enters the
 * public ABI.
 */
val CryptoCapabilities.hardware: HardwareSupport get() =
    platformHardwareKeyProvider()
        ?.let { HardwareSupport.Available(it) }
        ?: HardwareSupport.Unavailable

/**
 * The platform's hardware-backed key provider, or `null` when this platform wires none (or the
 * secure element is present but not actually usable — e.g. an unentitled test binary). `internal`
 * and `expect`/`actual` so it stays out of the public ABI while [CryptoCapabilities.hardware]'s
 * signature remains frozen. Implementations resolve a cheap, cached singleton.
 */
internal expect fun platformHardwareKeyProvider(): HardwareKeyProvider?

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
 * Hardware-backed [AesGcmKey]: an opaque handle with no exportable material. Seal/open route through
 * the provider-supplied gated closures; the blocking witness path is unreachable because
 * [requireInMemoryMaterial] throws for this type.
 */
internal class HardwareAesGcmKey(
    override val sizeBits: Int,
    val gatedSeal: HardwareAesGcmSeal,
    val gatedOpen: HardwareAesGcmOpen,
    private val onClose: () -> Unit = {},
) : AesGcmKey {
    override val provenance: KeyProvenance get() = KeyProvenance.Hardware

    /**
     * Opaque handle; there is no process-memory material to wipe. [onClose] lets a provider release
     * an out-of-process resource (e.g. delete the keystore alias). Defaults to a no-op.
     */
    override fun close() = onClose()
}

/**
 * Hardware-backed [SigningKey]: an opaque handle with no exportable material. Signing routes through
 * the provider-supplied gated closure; the blocking witness path is unreachable because
 * [requireInMemoryMaterial] throws for this type.
 */
internal class HardwareSigningKey(
    override val scheme: SignatureScheme,
    val gatedSign: HardwareSign,
    /** The public key matching this signing key, captured by the provider at generation. */
    override val verifyKey: VerifyKey,
    private val onClose: () -> Unit = {},
) : SigningKey {
    override val provenance: KeyProvenance get() = KeyProvenance.Hardware

    /**
     * Opaque handle; there is no process-memory material to wipe. [onClose] lets a provider release
     * an out-of-process resource (e.g. delete the keystore alias). Defaults to a no-op.
     */
    override fun close() = onClose()
}
