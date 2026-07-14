package com.ditchoom.buffer.crypto

/**
 * JS / WASM: an in-process software key store ([KeyCustody.ExportableSoftware]) by default — not
 * durable across page loads yet. A follow-up wires an IndexedDB store holding a non-extractable
 * WebCrypto `CryptoKey` ([KeyCustody.NonExportable.Software]); until then a caller that requires
 * non-exportable custody is correctly refused by [KeyProvider.requireTier]. A consumer needing
 * durability now can supply its own [KeyStoreConfig.storage].
 */
internal actual fun platformKeyStore(config: KeyStoreConfig): KeyStore = SoftwareKeyStore(config.storage ?: InMemoryKeyStorage())
