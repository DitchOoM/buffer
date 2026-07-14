package com.ditchoom.buffer.crypto

/**
 * JVM: a durable on-disk software key store ([KeyCustody.ExportableSoftware]). The medium is
 * pluggable via [KeyStoreConfig.storage]; the default is one DER file per alias under
 * `<user.home>/.buffer-crypto/<name>` (or [KeyStoreConfig.location]).
 */
internal actual fun platformKeyStore(config: KeyStoreConfig): KeyStore =
    SoftwareKeyStore(config.storage ?: FileKeyStorage(config.name, config.location))
