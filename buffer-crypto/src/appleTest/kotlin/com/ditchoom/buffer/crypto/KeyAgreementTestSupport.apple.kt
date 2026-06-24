package com.ditchoom.buffer.crypto

// Apple has synchronous ECDH (Security framework) and X25519 (CryptoKit). The async path delegates
// to sync, so support matches the sync flag.
actual fun asyncAgreementSupported(curve: KeyAgreementCurve): Boolean = supportsSync(curve)

actual val isWebPlatform: Boolean = false

// The EC private encoding is now the raw big-endian scalar on Apple too: imports store the scalar
// and the Security-framework exchange reconstructs X9.63 via the CryptoKit shim, while generation
// exports the trailing scalar. X25519 already used CryptoKit's raw 32-byte scalar. So the raw-scalar
// KAT / Wycheproof private-key vectors now apply to every curve.
actual fun supportsRawScalarKat(curve: KeyAgreementCurve): Boolean = supportsSync(curve)
