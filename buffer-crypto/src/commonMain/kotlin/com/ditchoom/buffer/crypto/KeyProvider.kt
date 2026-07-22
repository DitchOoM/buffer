package com.ditchoom.buffer.crypto

/**
 * The common supertype for anything that generates keys. A *router* (the resolver added in a later
 * step) also implements this, so [KeyProvider] carries no single custody value — the custody you get
 * can differ per algorithm, which is why it is queried per-algorithm via [custodyFor] rather than
 * exposed as one field here. A single-tier backend is a [ConcreteKeyProvider], which does expose a
 * constant [ConcreteKeyProvider.custody].
 */
interface KeyProvider {
    /** The exact [KeyCustody] a key generated for [alg] will have on this platform (routing introspection). */
    fun custodyFor(alg: ProtectedKeyAlgorithm): KeyCustody

    /** Whether this provider can generate a key for [alg]. */
    fun eligible(alg: ProtectedKeyAlgorithm): Boolean

    /** Generates a fresh signing key for [scheme], per [spec]. */
    suspend fun generateSigning(
        scheme: SignatureScheme,
        spec: ProtectedKeySpec,
    ): SigningKey

    /** Generates a fresh AES-GCM key, per [spec]. */
    suspend fun generateAesGcm(spec: ProtectedKeySpec): AesGcmKey

    /**
     * Mints a key-agreement private key on [curve]; on a non-exportable provider the private scalar
     * never leaves the backend. The result is an ordinary [KeyAgreementKeyPair] whose
     * `deriveSharedSecret` routes through the provider, so it flows through the existing
     * `keyAgreement()` / `hpke()` witnesses unchanged.
     */
    suspend fun generateKeyAgreement(
        curve: KeyAgreementCurve,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair
}

/**
 * A single-tier provider (software / WebCrypto / secure element): every key it mints has the same
 * [custody], so [custodyFor] is that value for any algorithm. A *router* is deliberately **not** a
 * [ConcreteKeyProvider], because its custody varies per algorithm.
 */
interface ConcreteKeyProvider : KeyProvider {
    /** The single custody every key from this provider carries. */
    val custody: KeyCustody

    override fun custodyFor(alg: ProtectedKeyAlgorithm): KeyCustody = custody
}

/**
 * A provider whose keys are non-exportable. The [custody] return type narrows to
 * [KeyCustody.NonExportable], so `exportable == false` is enforced by the *type* — a
 * [ProtectedKeyProvider] whose custody is [KeyCustody.ExportableSoftware] does not compile. This is
 * the broad tier: it covers both a secure element ([HardwareKeyProvider]) and non-exportable software
 * (WebCrypto `extractable:false`, wired in a later step).
 */
interface ProtectedKeyProvider : ConcreteKeyProvider {
    override val custody: KeyCustody.NonExportable
}

/**
 * Whether a non-exportable key provider — secure element **or** non-exportable software (WebCrypto
 * `extractable:false`) — is available on this platform. Strictly broader than [HardwareSupport], which
 * is the secure-element-only refinement: everything [HardwareSupport.Available] is also
 * [ProtectedKeySupport.Available], but not the reverse.
 */
sealed interface ProtectedKeySupport {
    /** No non-exportable key provider on this platform. */
    data object Unavailable : ProtectedKeySupport

    /** A non-exportable key [provider] is available. */
    data class Available(
        val provider: ProtectedKeyProvider,
    ) : ProtectedKeySupport
}

/**
 * The platform's strongest non-exportable key provider, or `null` when none is usable. Derived from
 * the typed [platformProtectedKeyResolution] seam — the `null` here is a *derivation* for the frozen
 * accessors ([CryptoCapabilities.protectedKeys], [CryptoCapabilities.hardware]), not a state of its
 * own: the distinction between "no backend wired" and "wired but refused (and why)" lives in
 * [ProtectedKeyResolution], never overloaded onto this nullable.
 */
internal fun platformProtectedKeyProvider(): ProtectedKeyProvider? =
    (platformProtectedKeyResolution() as? ProtectedKeyResolution.Available)?.provider

/**
 * The non-exportable key capability on this platform: [ProtectedKeySupport.Available] where either a
 * secure element (Android / Apple) or non-exportable software keys are wired,
 * [ProtectedKeySupport.Unavailable] otherwise (JVM, Linux, and web until WebCrypto lands).
 *
 * A plain `val` (not `expect`/`actual`) by design: the *public* signature is frozen, and a backend
 * resolves through the **internal** [platformProtectedKeyProvider] seam, so a later platform can wire
 * a provider without changing this declaration.
 */
val CryptoCapabilities.protectedKeys: ProtectedKeySupport get() =
    platformProtectedKeyProvider()
        ?.let { ProtectedKeySupport.Available(it) }
        ?: ProtectedKeySupport.Unavailable
