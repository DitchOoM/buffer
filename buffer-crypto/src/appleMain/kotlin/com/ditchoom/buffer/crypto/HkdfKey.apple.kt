package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer

/**
 * Apple: a software store over [KeyStoreConfig.storage]. Without one the store is in-process and not
 * durable, matching the [KeyStore] fallback: the Keychain-backed tiers hold Secure Enclave keys, and
 * the Enclave has no HKDF key.
 */
internal actual fun platformHkdfKeyStore(config: KeyStoreConfig): HkdfKeyStore =
    SoftwareHkdfKeyStore(config.storage ?: InMemoryKeyStorage())

internal actual suspend fun platformImportHkdfKey(ikm: ReadBuffer): HkdfKey = HkdfKey.of(ikm)
