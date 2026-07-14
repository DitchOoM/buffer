package com.ditchoom.buffer.crypto

/**
 * JS / WASM: a durable [WebCryptoKeyStore] when both `crypto.subtle.generateKey` and IndexedDB are
 * present ([KeyCustody.NonExportable.Software] — a non-extractable `CryptoKey` held in IndexedDB). In
 * an engine without IndexedDB (bare Node) or WebCrypto, it falls back to an in-process software store
 * ([KeyCustody.ExportableSoftware]) — honest about the weaker custody, so a `requireTier` caller is
 * refused rather than silently upgraded. A caller may still force a software medium via
 * [KeyStoreConfig.storage].
 */
internal actual fun platformKeyStore(config: KeyStoreConfig): KeyStore =
    when {
        config.storage != null -> SoftwareKeyStore(config.storage)
        subtleGenerateKeyAvailable && webCryptoIndexedDbAvailable -> WebCryptoKeyStore(config.name)
        else -> SoftwareKeyStore(InMemoryKeyStorage())
    }
