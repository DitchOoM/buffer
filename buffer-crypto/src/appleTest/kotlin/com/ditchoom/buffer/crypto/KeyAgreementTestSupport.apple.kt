package com.ditchoom.buffer.crypto

// Apple has synchronous ECDH (Security framework) and X25519 (CryptoKit). The async path delegates
// to sync, so support matches the sync flag.
actual fun asyncAgreementSupported(curve: KeyAgreementCurve): Boolean = supportsSync(curve)

actual val isWebPlatform: Boolean = false

// Apple's *EC* private encoding is the Security external representation (04‖X‖Y‖scalar), not a raw
// scalar, so the raw-scalar KAT/Wycheproof vectors cannot be imported for P-256/384/521. X25519,
// however, goes through CryptoKit's Curve25519.KeyAgreement, which imports the bare 32-byte scalar
// directly — so the RFC 7748 raw-scalar KAT and the X25519 Wycheproof vectors DO apply there.
actual fun supportsRawScalarKat(curve: KeyAgreementCurve): Boolean = curve == KeyAgreementCurve.X25519
