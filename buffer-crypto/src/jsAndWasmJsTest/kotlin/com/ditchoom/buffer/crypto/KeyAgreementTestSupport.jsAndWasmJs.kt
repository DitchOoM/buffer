package com.ditchoom.buffer.crypto

// Web has no synchronous KA. The async WebCrypto path supports ECDH P-256/384/521 broadly; X25519
// only on newer engines (feature-detected). The X25519 detection here is best-effort — the tests
// also tolerate an UnsupportedOperationException at the call site so a stale engine never fails CI.
actual fun asyncAgreementSupported(curve: KeyAgreementCurve): Boolean =
    when (curve) {
        KeyAgreementCurve.X25519 -> webCryptoSupportsX25519
        else -> true
    }

actual val isWebPlatform: Boolean = true

// WebCrypto imports PKCS#8 private keys, not raw scalars.
actual fun supportsRawScalarKat(curve: KeyAgreementCurve): Boolean = false
