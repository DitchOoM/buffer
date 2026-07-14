package com.ditchoom.buffer.crypto

/**
 * Apple: an in-process software key store ([KeyCustody.ExportableSoftware]) by default — not durable
 * across restarts yet. A follow-up wires a Keychain-backed store
 * ([KeyCustody.NonExportable.Hardware], Secure Enclave where eligible); until then a caller that
 * requires hardware custody is correctly refused by [KeyProvider.requireTier]. A consumer needing
 * durability now can supply its own [KeyStoreConfig.storage].
 */
internal actual fun platformKeyStore(config: KeyStoreConfig): KeyStore = SoftwareKeyStore(config.storage ?: InMemoryKeyStorage())
