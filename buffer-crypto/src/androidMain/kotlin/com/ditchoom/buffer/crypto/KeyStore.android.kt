package com.ditchoom.buffer.crypto

/**
 * Android: a durable [AndroidKeystoreKeyStore] on a device (or emulator) whose keys live in the
 * `AndroidKeyStore` and never leave it ([KeyCustody.NonExportable.Hardware] — StrongBox where a
 * dedicated secure element is present, TEE otherwise). A host-JVM unit-test run has no
 * `AndroidKeyStore` provider, so it falls back to the durable on-disk software store
 * ([KeyCustody.ExportableSoftware]); a caller that requires hardware custody there is correctly
 * refused by [KeyProvider.requireTier]. A consumer may still force a software medium (e.g. an
 * encrypted DB) via [KeyStoreConfig.storage].
 */
internal actual fun platformKeyStore(config: KeyStoreConfig): KeyStore =
    when {
        config.storage != null -> SoftwareKeyStore(config.storage)
        else ->
            androidKeystoreProviderOrNull()?.let { AndroidKeystoreKeyStore(it, config.name) }
                ?: SoftwareKeyStore(FileKeyStorage(config.name, config.location))
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
    override fun custodyFor(alg: ProtectedKeyAlgorithm): KeyCustody = provider.custody

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
        return when (val stored = provider.entryAlgorithm(ns)) {
            null -> provider.generatePersistentSigning(ns, spec)
            ProtectedKeyAlgorithm.EcdsaP256 -> requireNotNull(provider.reattachSigning(ns, spec))
            else -> throw KeyStoreException.AliasMismatch(alias, stored, scheme.toProtectedKeyAlgorithm())
        }
    }

    override suspend fun getOrGenerateAesGcm(
        alias: String,
        spec: ProtectedKeySpec,
    ): AesGcmKey {
        requireValidAlias(alias)
        val ns = namespaced(alias)
        return when (val stored = provider.entryAlgorithm(ns)) {
            null -> provider.generatePersistentAesGcm(ns, spec)
            ProtectedKeyAlgorithm.AesGcm -> requireNotNull(provider.reattachAesGcm(ns, spec))
            else -> throw KeyStoreException.AliasMismatch(alias, stored, ProtectedKeyAlgorithm.AesGcm)
        }
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
        return when (val stored = provider.entryAlgorithm(ns)) {
            null -> provider.generatePersistentKeyAgreement(ns, spec)
            ProtectedKeyAlgorithm.EcdhP256 -> requireNotNull(provider.reattachKeyAgreement(ns, spec))
            else -> throw KeyStoreException.AliasMismatch(alias, stored, curve.toProtectedKeyAlgorithm())
        }
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
