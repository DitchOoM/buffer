package com.ditchoom.buffer.crypto

// Linux has a synchronous native KA (BoringSSL); the async path delegates to it, so support matches
// the sync flag.
actual fun asyncAgreementSupported(curve: KeyAgreementCurve): Boolean = supportsSync(curve)

actual val isWebPlatform: Boolean = false

// BoringSSL imports raw private scalars directly (EC_KEY_set_private_key / X25519 raw scalar).
actual fun supportsRawScalarKat(curve: KeyAgreementCurve): Boolean = supportsSync(curve)
