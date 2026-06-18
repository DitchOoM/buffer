package com.ditchoom.buffer.crypto

// Apple has synchronous ECDH (Security framework); X25519 is unsupported (no Security key type).
// The async path delegates to sync, so support matches the sync flag.
actual fun asyncAgreementSupported(curve: KeyAgreementCurve): Boolean = supportsSync(curve)

actual val isWebPlatform: Boolean = false

// Apple's private encoding is the Security external representation, not a raw scalar.
actual fun supportsRawScalarKat(curve: KeyAgreementCurve): Boolean = false
