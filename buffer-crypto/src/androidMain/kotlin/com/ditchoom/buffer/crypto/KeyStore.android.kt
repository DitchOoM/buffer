package com.ditchoom.buffer.crypto

/**
 * Android: currently a durable on-disk software key store ([KeyCustody.ExportableSoftware]) — one
 * DER file per alias under [KeyStoreConfig.location] (or `<user.home>/.buffer-crypto/<name>`, which
 * on Android resolves inside the app sandbox). A follow-up upgrades this to an AndroidKeyStore-backed
 * store ([KeyCustody.NonExportable.Hardware]); until then a caller that requires hardware custody is
 * correctly refused by [KeyProvider.requireTier].
 */
internal actual fun platformKeyStore(config: KeyStoreConfig): KeyStore =
    SoftwareKeyStore(config.storage ?: FileKeyStorage(config.name, config.location))
