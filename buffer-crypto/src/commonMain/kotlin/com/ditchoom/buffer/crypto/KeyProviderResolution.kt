package com.ditchoom.buffer.crypto

/**
 * The always-present commonMain fallback: mints ordinary **exportable in-memory** keys by composing
 * the primitives that exist on every platform — the signature witness for signing keys, `cryptoRandom`
 * + [AesGcmKey.of] for AES, and the key-agreement witness for agreement keys. Because it lives in
 * commonMain and leans only on those witnesses, it is the guaranteed floor beneath [keyProvider] on
 * every target; platforms differ only in whether a stronger rung ([platformProtectedKeyProvider]) sits
 * above it.
 */
internal object SoftwareKeyProvider : ConcreteKeyProvider {
    override val custody: KeyCustody get() = KeyCustody.ExportableSoftware

    // The floor serves every algorithm via the platform's own primitives; custodyFor defaults to
    // [custody] (ConcreteKeyProvider), so every eligible alg reports ExportableSoftware.
    override fun eligible(alg: ProtectedKeyAlgorithm): Boolean = true

    override suspend fun generateSigning(
        scheme: SignatureScheme,
        spec: ProtectedKeySpec,
    ): SigningKey {
        val ops =
            when (val support = CryptoCapabilities.signatures(scheme)) {
                is SignatureSupport.Blocking -> support.ops
                is SignatureSupport.AsyncOnly -> support.ops
                SignatureSupport.Unavailable -> throw HardwareKeyException.AlgorithmNotEligible()
            }
        return ops.generateSigningKey()
    }

    override suspend fun generateAesGcm(spec: ProtectedKeySpec): AesGcmKey =
        AesGcmKey.of(cryptoRandom(spec.aesKeySizeBits / Byte.SIZE_BITS))

    override suspend fun generateKeyAgreement(
        curve: KeyAgreementCurve,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair = keyAgreementAsyncOps(curve).generateKeyPair()
}

/** The [ProtectedKeyAlgorithm] a key-agreement [KeyAgreementCurve] maps to, for per-algorithm routing. */
internal fun KeyAgreementCurve.toProtectedKeyAlgorithm(): ProtectedKeyAlgorithm =
    when (this) {
        KeyAgreementCurve.X25519 -> ProtectedKeyAlgorithm.X25519
        KeyAgreementCurve.P256 -> ProtectedKeyAlgorithm.EcdhP256
        KeyAgreementCurve.P384 -> ProtectedKeyAlgorithm.EcdhP384
        KeyAgreementCurve.P521 -> ProtectedKeyAlgorithm.EcdhP521
    }

/**
 * The router handed to consumers by [keyProvider]. Routes each request to the strongest tier that is
 * *eligible for that algorithm*, with [SoftwareKeyProvider] as the floor — so it never hands back a
 * provider that throws for custody reasons at the default, and never routes P-521 to a secure element
 * that only backs P-256. It is a [KeyProvider] but deliberately **not** a [ConcreteKeyProvider],
 * because its custody varies per algorithm.
 */
internal class ResolvingKeyProvider(
    private val strong: ProtectedKeyProvider?,
) : KeyProvider {
    private fun pick(alg: ProtectedKeyAlgorithm): ConcreteKeyProvider = strong?.takeIf { it.eligible(alg) } ?: SoftwareKeyProvider

    override fun custodyFor(alg: ProtectedKeyAlgorithm): KeyCustody = pick(alg).custody

    override fun eligible(alg: ProtectedKeyAlgorithm): Boolean = true

    override suspend fun generateSigning(
        scheme: SignatureScheme,
        spec: ProtectedKeySpec,
    ): SigningKey = pick(scheme.toProtectedKeyAlgorithm()).generateSigning(scheme, spec)

    override suspend fun generateAesGcm(spec: ProtectedKeySpec): AesGcmKey = pick(ProtectedKeyAlgorithm.AesGcm).generateAesGcm(spec)

    override suspend fun generateKeyAgreement(
        curve: KeyAgreementCurve,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair = pick(curve.toProtectedKeyAlgorithm()).generateKeyAgreement(curve, spec)
}

/**
 * The **total**, non-null key-provider entry point: a [KeyProvider] that always works, routing each
 * request per algorithm to the strongest custody the platform offers and falling back to the
 * commonMain [SoftwareKeyProvider] floor. Never returns null and never throws for custody reasons at
 * the default — the only `?:` is internal, against the [platformProtectedKeyProvider] seam.
 *
 * "I require a stronger tier" is expressed as an assertion on the inspectable custody
 * ([KeyProvider.requireTier] / [KeyProvider.custodyFor]), not as a nullable return. The two capability
 * queries ([CryptoCapabilities.protectedKeys] / [CryptoCapabilities.hardware]) remain for callers who
 * want to branch on raw availability instead of routing.
 */
fun CryptoCapabilities.keyProvider(): KeyProvider = ResolvingKeyProvider(strong = platformProtectedKeyProvider())

/**
 * Asserts the platform can serve [alg] at **at least** [tier], and returns the same [KeyProvider] for
 * chaining. Throws [InsufficientKeyCustody] when the strongest eligible custody is weaker — no
 * nullable, no `!!`. Custody tiers are ordered ([CustodyTier]), so "at least" is a simple comparison.
 *
 * ```kotlin
 * val hw = CryptoCapabilities.keyProvider()
 *     .requireTier(ProtectedKeyAlgorithm.EcdsaP256, CustodyTier.Hardware)
 *     .generateSigning(SignatureScheme.EcdsaP256, ProtectedKeySpec())
 * ```
 */
fun KeyProvider.requireTier(
    alg: ProtectedKeyAlgorithm,
    tier: CustodyTier,
): KeyProvider =
    also {
        val got = custodyFor(alg).tier
        if (got < tier) throw InsufficientKeyCustody(alg = alg, required = tier, available = got)
    }
