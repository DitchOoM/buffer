package com.ditchoom.buffer.crypto

/**
 * JVM: a durable [Tpm2Pkcs11KeyStore] where a TPM-backed PKCS#11 token is configured and usable -
 * each persistent key lives inside the token ([KeyCustody.NonExportable.Hardware]) and survives
 * process restart as a cert-wrapped token object. Elsewhere, the durable on-disk software store
 * ([KeyCustody.ExportableSoftware]): one DER file per alias under `<user.home>/.buffer-crypto/<name>`
 * (or [KeyStoreConfig.location]). Supplying [KeyStoreConfig.storage] always selects the software
 * store over that medium - the escape hatch for persisting what the token cannot hold (AES-GCM,
 * non-P256 schemes) on a TPM-configured host.
 */
internal actual fun platformKeyStore(config: KeyStoreConfig): KeyStore =
    when {
        config.storage != null -> SoftwareKeyStore(config.storage)
        else ->
            tpm2Pkcs11ProviderOrNull()?.let { Tpm2Pkcs11KeyStore(it, config.name) }
                ?: SoftwareKeyStore(FileKeyStorage(config.name, config.location))
    }
