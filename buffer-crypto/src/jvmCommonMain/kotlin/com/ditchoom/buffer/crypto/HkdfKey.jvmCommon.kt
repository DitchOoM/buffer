package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer

/**
 * JVM / Android: a software store over [KeyStoreConfig.storage], or the per-user on-disk directory the
 * software [KeyStore] uses. Neither JCA nor the Android Keystore offers a non-exportable HKDF key, so
 * custody is [KeyCustody.ExportableSoftware].
 */
internal actual fun platformHkdfKeyStore(config: KeyStoreConfig): HkdfKeyStore =
    SoftwareHkdfKeyStore(config.storage ?: FileKeyStorage(config.name, config.location))

internal actual suspend fun platformImportHkdfKey(ikm: ReadBuffer): HkdfKey = HkdfKey.of(ikm)
