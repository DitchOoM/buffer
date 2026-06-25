package com.ditchoom.buffer.crypto

/**
 * Per-platform knowledge the key-agreement tests need but can't read off the public flags:
 * whether the **async** path can run a given curve on the current target. On JVM/Android/Apple the
 * async path delegates to the sync one, so it matches [supportsSync]; on web the sync flags are all
 * `false` but the async WebCrypto path works for ECDH (always) and X25519 (engine-dependent).
 */
expect fun asyncAgreementSupported(curve: KeyAgreementCurve): Boolean

/** Whether the current platform has *no* synchronous key agreement at all (i.e. web). */
expect val isWebPlatform: Boolean

/**
 * Whether [importPrivateKey] accepts a **raw scalar** for [curve] on this platform — required for
 * the RFC 7748 / NIST known-answer vectors, which are specified as raw private scalars. The
 * key-agreement private encoding is the raw big-endian scalar on **every** platform (JVM/Android via
 * JCA, Linux via BoringSSL, Apple via a CryptoKit X9.63-from-scalar reconstruction, web via a
 * scalar→PKCS#8 wrap for WebCrypto), so this is `true` wherever the curve itself is supported.
 */
expect fun supportsRawScalarKat(curve: KeyAgreementCurve): Boolean
