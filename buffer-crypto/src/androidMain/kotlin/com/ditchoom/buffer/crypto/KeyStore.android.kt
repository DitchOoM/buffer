package com.ditchoom.buffer.crypto

/**
 * Android: a durable [AndroidKeystoreKeyStore] on a device (or emulator) whose keys live in the
 * `AndroidKeyStore` and never leave it ([KeyCustody.NonExportable.Hardware] — StrongBox or TEE, as
 * each key's own custody reports). The keystore is opened on first use; one that fails to load answers
 * that call with a retryable [KeyStoreException.StorageFailure] and is opened again on the next, so a
 * device never falls back to a weaker store. A host-JVM unit-test run has no `AndroidKeyStore`
 * provider at all, so it uses the durable on-disk software store ([KeyCustody.ExportableSoftware]); a
 * caller that requires hardware custody there is correctly refused by [KeyProvider.requireTier]. A
 * consumer may still force a software medium (e.g. an encrypted DB) via [KeyStoreConfig.storage].
 */
internal actual fun platformKeyStore(config: KeyStoreConfig): KeyStore =
    when {
        config.storage != null -> SoftwareKeyStore(config.storage)
        !androidKeystoreProviderRegistered() -> SoftwareKeyStore(FileKeyStorage(config.name, config.location))
        else -> RetryingKeyStore { openAndroidKeystoreStore(config.name) }
    }

/**
 * A persistent, alias-addressable [KeyStore] whose keys are AndroidKeystore entries — the keystore is
 * both custodian and durable store, so there is no exportable blob and [KeyStoreConfig.storage] does
 * not apply ([KeyCustody.NonExportable.Hardware]). Each store alias maps to the keystore entry
 * `"$name:$alias"`, so independent stores (distinct [KeyStoreConfig.name]) never collide and never see
 * the ephemeral provider's random-alias keys. The metadata (kind + public key) is intrinsic to the
 * keystore entry, so there is no sidecar record.
 *
 * The ephemeral [KeyProvider] methods delegate to [provider] (throwaway keys, deleted on close); the
 * alias methods persist — a key from [getOrGenerateSigning] / [loadSigning] survives `close()` and
 * process restart until [delete]d.
 *
 * Serves only the algorithms an AndroidKeystore backs non-exportably: ECDSA P-256 signing, AES-GCM,
 * and — on API 31+ where the keystore-probed `PURPOSE_AGREE_KEY` support holds — ECDH P-256 key
 * agreement. A get-or-generate for anything else (Ed25519 / P-384 / P-521 / X25519) throws
 * [HardwareKeyException.AlgorithmNotEligible] — consult [custodyFor] / [eligible] first, or supply a
 * software [KeyStoreConfig.storage] for a persistent key the element cannot hold.
 */
internal class AndroidKeystoreKeyStore(
    private val provider: AndroidKeystoreHardwareKeyProvider,
    private val name: String,
) : KeyStore {
    /**
     * The TEE floor for an algorithm the keystore holds (a StrongBox key reports more on its own custody); for one it
     * cannot hold, [HardwareKeyException.AlgorithmNotEligible], since no key of it will ever come from this store.
     */
    override fun custodyFor(alg: ProtectedKeyAlgorithm): KeyCustody =
        if (provider.eligible(alg)) provider.custody else throw HardwareKeyException.AlgorithmNotEligible()

    override fun eligible(alg: ProtectedKeyAlgorithm): Boolean = provider.eligible(alg)

    // --- KeyProvider (ephemeral) — delegate to the hardware provider (auto-deleted on close) -------

    override suspend fun generateSigning(
        scheme: SignatureScheme,
        spec: ProtectedKeySpec,
    ): SigningKey = provider.generateSigning(scheme, spec)

    override suspend fun generateAesGcm(spec: ProtectedKeySpec): AesGcmKey = provider.generateAesGcm(spec)

    override suspend fun generateKeyAgreement(
        curve: KeyAgreementCurve,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair = provider.generateKeyAgreement(curve, spec)

    // --- Persistent alias lifecycle ----------------------------------------------------------------

    override suspend fun getOrGenerateSigning(
        alias: String,
        scheme: SignatureScheme,
        spec: ProtectedKeySpec,
    ): SigningKey {
        requireValidAlias(alias)
        if (!provider.eligible(scheme.toProtectedKeyAlgorithm())) throw HardwareKeyException.AlgorithmNotEligible()
        val ns = namespaced(alias)
        return provider
            .getOrGenerateEntry(
                ns,
                ProtectedKeyAlgorithm.EcdsaP256,
                reattach = { provider.reattachSigningEntry(ns, spec) },
                generate = { provider.generatePersistentSigningEntry(ns, spec) },
            ).keyOrMismatch(alias, scheme.toProtectedKeyAlgorithm())
    }

    override suspend fun getOrGenerateAesGcm(
        alias: String,
        spec: ProtectedKeySpec,
    ): AesGcmKey {
        requireValidAlias(alias)
        val ns = namespaced(alias)
        return provider
            .getOrGenerateEntry(
                ns,
                ProtectedKeyAlgorithm.AesGcm,
                reattach = { provider.reattachAesGcmEntry(ns, spec) },
                generate = { provider.generatePersistentAesGcmEntry(ns, spec) },
            ).keyOrMismatch(alias, ProtectedKeyAlgorithm.AesGcm)
    }

    override suspend fun getOrGenerateKeyAgreement(
        alias: String,
        curve: KeyAgreementCurve,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair {
        requireValidAlias(alias)
        // Only ECDH P-256, and only where the keystore-probed PURPOSE_AGREE_KEY support holds
        // (API 31+); X25519 has no AndroidKeystore agreement backing on any API level.
        if (curve != KeyAgreementCurve.P256 || !provider.eligible(ProtectedKeyAlgorithm.EcdhP256)) {
            throw HardwareKeyException.AlgorithmNotEligible()
        }
        val ns = namespaced(alias)
        return provider
            .getOrGenerateEntry(
                ns,
                ProtectedKeyAlgorithm.EcdhP256,
                reattach = { provider.reattachKeyAgreementEntry(ns, spec) },
                generate = { provider.generatePersistentKeyAgreementEntry(ns, spec) },
            ).keyOrMismatch(alias, curve.toProtectedKeyAlgorithm())
    }

    override suspend fun loadSigning(alias: String): SigningKey? {
        requireValidAlias(alias)
        return provider.reattachSigning(namespaced(alias), ProtectedKeySpec())
    }

    override suspend fun loadAesGcm(alias: String): AesGcmKey? {
        requireValidAlias(alias)
        return provider.reattachAesGcm(namespaced(alias), ProtectedKeySpec())
    }

    override suspend fun loadKeyAgreement(alias: String): KeyAgreementKeyPair? {
        requireValidAlias(alias)
        return provider.reattachKeyAgreement(namespaced(alias), ProtectedKeySpec())
    }

    override suspend fun contains(alias: String): Boolean {
        requireValidAlias(alias)
        return provider.containsAlias(namespaced(alias))
    }

    /** See [AndroidKeystoreHardwareKeyProvider.listAliases]: a keystore failing partway can end the listing early. */
    override suspend fun aliases(): Set<String> {
        val prefix = "$name:"
        return provider.listAliases(prefix).mapTo(mutableSetOf()) { it.substring(prefix.length) }
    }

    override suspend fun delete(alias: String): Boolean {
        requireValidAlias(alias)
        return provider.deleteAlias(namespaced(alias))
    }

    override fun close() = Unit

    /** The keystore entry name for a store [alias]: `"$name:$alias"` (neither part contains `:`). */
    private fun namespaced(alias: String): String = "$name:$alias"
}

/**
 * One attempt to open the persistent [AndroidKeystoreKeyStore] named [name]. A keystore that fails to
 * load is a retryable [KeyStoreException.StorageFailure] for this attempt, never a software store.
 */
internal fun openAndroidKeystoreStore(name: String): PlatformStoreOpening =
    when (val resolution = platformProtectedKeyResolution()) {
        is ProtectedKeyResolution.Available ->
            when (val provider = resolution.provider) {
                is AndroidKeystoreHardwareKeyProvider ->
                    PlatformStoreOpening.Opened(AndroidKeystoreKeyStore(provider, name))
                else -> PlatformStoreOpening.Refused(KeyStoreException.StorageFailure(retryable = false))
            }
        is ProtectedKeyResolution.Refused, ProtectedKeyResolution.None ->
            PlatformStoreOpening.Refused(KeyStoreException.StorageFailure(retryable = true))
    }

private fun <K> AliasEntry<K>.keyOrMismatch(
    alias: String,
    requested: ProtectedKeyAlgorithm,
): K =
    when (this) {
        is AliasEntry.Attached -> key
        is AliasEntry.Generated -> key
        is AliasEntry.HoldsOther -> throw KeyStoreException.AliasMismatch(alias, stored, requested)
    }
