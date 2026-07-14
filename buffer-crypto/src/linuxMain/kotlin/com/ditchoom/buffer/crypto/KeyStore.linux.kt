package com.ditchoom.buffer.crypto

/**
 * Linux: an in-process software key store ([KeyCustody.ExportableSoftware]) by default — not durable
 * across restarts yet. A follow-up wires a POSIX on-disk medium; a consumer needing durability now
 * can supply its own [KeyStoreConfig.storage].
 */
internal actual fun platformKeyStore(config: KeyStoreConfig): KeyStore = SoftwareKeyStore(config.storage ?: InMemoryKeyStorage())
