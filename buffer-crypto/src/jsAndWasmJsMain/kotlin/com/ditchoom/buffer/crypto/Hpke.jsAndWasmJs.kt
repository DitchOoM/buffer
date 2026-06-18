package com.ditchoom.buffer.crypto

/**
 * js/wasmJs HPKE capability flags. AES-GCM has no synchronous path on the web but is available
 * through WebCrypto's async API, which HPKE (suspend throughout) can use — so AES-GCM suites are
 * supported. ChaCha20-Poly1305 is not in WebCrypto, so those suites are gated `false` elsewhere
 * by [supportsChaChaPoly]. The web has async (WebCrypto) key agreement even though no sync path
 * exists, so [isWebPlatformKa] is `true`.
 */
internal actual val supportsAesGcmAnyPath: Boolean = true

internal actual val isWebPlatformKa: Boolean = true
