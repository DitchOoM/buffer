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
 * the RFC 7748 / NIST known-answer vectors, which are specified as raw private scalars. True on
 * JVM/Android (JCA imports raw scalars directly). On Apple the private encoding is the Security
 * external representation and on web it is PKCS#8, so raw-scalar KAT import is not available there;
 * those platforms still get full round-trip + Wycheproof coverage.
 */
expect fun supportsRawScalarKat(curve: KeyAgreementCurve): Boolean
