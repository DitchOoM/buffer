package com.ditchoom.buffer.crypto

/**
 * JS / WASM: a durable [WebCryptoKeyStore] when both `crypto.subtle.generateKey` and IndexedDB are
 * present ([KeyCustody.NonExportable.Software] — a non-extractable `CryptoKey` held in IndexedDB). In
 * an engine without IndexedDB (bare Node) or WebCrypto, it falls back to an in-process, **non-durable**
 * software store ([KeyCustody.ExportableSoftware]) — honest about the weaker custody (a `requireTier`
 * caller is refused rather than silently upgraded), but keys there do not survive a process restart. A
 * browser always has IndexedDB and takes the durable path; a caller needing durability under bare Node
 * supplies a durable medium via [KeyStoreConfig.storage].
 */
internal actual fun platformKeyStore(config: KeyStoreConfig): KeyStore =
    when {
        config.storage != null -> SoftwareKeyStore(config.storage)
        subtleGenerateKeyAvailable && webCryptoIndexedDbAvailable -> WebCryptoKeyStore(config.name)
        else -> SoftwareKeyStore(InMemoryKeyStorage())
    }
