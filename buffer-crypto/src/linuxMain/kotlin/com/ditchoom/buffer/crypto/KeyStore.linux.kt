package com.ditchoom.buffer.crypto

/**
 * Linux: a durable on-disk software key store ([KeyCustody.ExportableSoftware]). The medium is
 * pluggable via [KeyStoreConfig.storage]; the default is one file per alias under
 * `<HOME>/.buffer-crypto/<name>` (or [KeyStoreConfig.location]), written through POSIX stdio.
 */
internal actual fun platformKeyStore(config: KeyStoreConfig): KeyStore =
    SoftwareKeyStore(config.storage ?: PosixFileKeyStorage(config.name, config.location))
