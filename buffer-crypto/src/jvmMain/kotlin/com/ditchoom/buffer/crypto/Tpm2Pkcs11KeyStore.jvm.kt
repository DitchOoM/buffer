package com.ditchoom.buffer.crypto

/**
 * A persistent, alias-addressable [KeyStore] whose keys live inside the TPM-backed PKCS#11 token
 * ([KeyCustody.NonExportable.Hardware]) - the desktop-JVM counterpart of the Android Keystore and
 * Apple Keychain stores. Each store alias maps to the token label `"$name:$alias"`, so independent
 * stores (distinct [KeyStoreConfig.name]) never collide and never see each other's aliases.
 *
 * The ephemeral [KeyProvider] methods delegate to [provider] (session objects, dropped with the
 * process); the alias methods persist - a key from [getOrGenerateSigning] / [loadSigning] survives
 * `close()` and process restart until [delete]d. Entries are cert-wrapped token objects (the
 * SunPKCS11 `KeyStore` packaging requirement; see Tpm2Pkcs11Persistence.jvm.kt), and the wrapper's
 * kind tag is what keeps signing and agreement entries apart: a kind-mismatched load answers `null`
 * and a kind-mismatched get-or-generate raises [KeyStoreException.AliasMismatch], never a mistyped
 * handle.
 *
 * Serves what the token backs non-exportably: ECDSA P-256 signing, and - where the end-to-end probe
 * holds (not tpm2-pkcs11 1.9, which lacks `CKM_ECDH1_DERIVE`) - ECDH P-256 key agreement. A
 * get-or-generate for anything else (other schemes, AES-GCM, X25519) throws
 * [HardwareKeyException.AlgorithmNotEligible] - consult [custodyFor] / [eligible] first, or supply a
 * software [KeyStoreConfig.storage] for a persistent key the token cannot hold.
 *
 * **Custody trust boundary:** identical to the provider's (see HardwareKeys.jvm.kt) - the
 * hardware-custody claim is exactly as trustworthy as the configured module path/PIN.
 */
internal class Tpm2Pkcs11KeyStore(
    private val provider: Tpm2Pkcs11HardwareKeyProvider,
    private val name: String,
) : KeyStore {
    override fun custodyFor(alg: ProtectedKeyAlgorithm): KeyCustody = provider.custody

    override fun eligible(alg: ProtectedKeyAlgorithm): Boolean = provider.eligible(alg)

    // --- KeyProvider (ephemeral) - delegate to the token provider ---------------------------------

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

    @Suppress("ThrowsCount") // not-eligible / alias-mismatch / corrupt are distinct typed outcomes
    override suspend fun getOrGenerateSigning(
        alias: String,
        scheme: SignatureScheme,
        spec: ProtectedKeySpec,
    ): SigningKey {
        requireValidAlias(alias)
        if (!provider.eligible(scheme.toProtectedKeyAlgorithm())) throw HardwareKeyException.AlgorithmNotEligible()
        val label = p11Label(alias)
        provider.storedEntryKind(label)?.let { (kind, tag) ->
            if (kind != KIND_SIGNING || signingScheme(tag) != scheme) {
                throw KeyStoreException
                    .AliasMismatch(alias, storedAlgorithm(kind, tag), scheme.toProtectedKeyAlgorithm())
            }
            // The kind record exists but the key or certificate vanished underneath it: corrupt.
            return provider.signingFromEntry(label, spec) ?: throw KeyStoreException.CorruptEntry()
        }
        return provider.persistSigning(label, spec)
    }

    override suspend fun getOrGenerateAesGcm(
        alias: String,
        spec: ProtectedKeySpec,
    ): AesGcmKey {
        requireValidAlias(alias)
        // The SunPKCS11 + tpm2-pkcs11 combination does not usably back AES-GCM; see the provider.
        throw HardwareKeyException.AlgorithmNotEligible()
    }

    @Suppress("ThrowsCount") // not-eligible / alias-mismatch / corrupt are distinct typed outcomes
    override suspend fun getOrGenerateKeyAgreement(
        alias: String,
        curve: KeyAgreementCurve,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair {
        requireValidAlias(alias)
        // Only ECDH P-256, and only where the probed module support holds (see the provider).
        if (curve != KeyAgreementCurve.P256 || !provider.eligible(ProtectedKeyAlgorithm.EcdhP256)) {
            throw HardwareKeyException.AlgorithmNotEligible()
        }
        val label = p11Label(alias)
        provider.storedEntryKind(label)?.let { (kind, tag) ->
            if (kind != KIND_AGREEMENT || agreementCurve(tag) != curve) {
                throw KeyStoreException
                    .AliasMismatch(alias, storedAlgorithm(kind, tag), curve.toProtectedKeyAlgorithm())
            }
            return provider.agreementFromEntry(label, spec) ?: throw KeyStoreException.CorruptEntry()
        }
        return provider.persistAgreement(label, spec)
    }

    override suspend fun loadSigning(alias: String): SigningKey? {
        requireValidAlias(alias)
        val label = p11Label(alias)
        val stored = provider.storedEntryKind(label)
        return when {
            stored == null -> null
            stored.first != KIND_SIGNING || signingScheme(stored.second) != SignatureScheme.EcdsaP256 -> null
            else -> provider.signingFromEntry(label, ProtectedKeySpec())
        }
    }

    override suspend fun loadAesGcm(alias: String): AesGcmKey? {
        requireValidAlias(alias)
        // No AES key is ever persisted here (see getOrGenerateAesGcm).
        return null
    }

    /**
     * Re-attaches a persisted agreement key, or `null` when the alias is absent, holds a different
     * kind, **or the backend no longer serves agreement** (a store created against a derive-capable
     * module, reopened against one without the mechanism, degrades to `null` rather than a handle
     * whose every operation would fail).
     */
    override suspend fun loadKeyAgreement(alias: String): KeyAgreementKeyPair? {
        requireValidAlias(alias)
        if (!provider.eligible(ProtectedKeyAlgorithm.EcdhP256)) return null
        val label = p11Label(alias)
        val stored = provider.storedEntryKind(label)
        return when {
            stored == null -> null
            stored.first != KIND_AGREEMENT || agreementCurve(stored.second) != KeyAgreementCurve.P256 -> null
            else -> provider.agreementFromEntry(label, ProtectedKeySpec())
        }
    }

    override suspend fun contains(alias: String): Boolean {
        requireValidAlias(alias)
        return provider.containsEntry(p11Label(alias))
    }

    override suspend fun aliases(): Set<String> =
        provider
            .entryAliases()
            .filter { it.startsWith(labelPrefix) }
            .map { it.removePrefix(labelPrefix) }
            .toSet()

    override suspend fun delete(alias: String): Boolean {
        requireValidAlias(alias)
        return provider.deleteEntry(p11Label(alias))
    }

    override fun close() = Unit

    private val labelPrefix = "$name$LABEL_SEPARATOR"

    private fun p11Label(alias: String): String = "$labelPrefix$alias"

    private companion object {
        /** Outside the portable alias/name charset, so namespaced labels can never collide. */
        const val LABEL_SEPARATOR = ':'
    }
}
