package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer

/** Linux: a software store over [KeyStoreConfig.storage], or the on-disk directory the [KeyStore] uses. */
internal actual fun platformHkdfKeyStore(config: KeyStoreConfig): HkdfKeyStore =
    SoftwareHkdfKeyStore(config.storage ?: PosixFileKeyStorage(config.name, config.location))

internal actual suspend fun platformImportHkdfKey(ikm: ReadBuffer): HkdfKey = HkdfKey.of(ikm)
