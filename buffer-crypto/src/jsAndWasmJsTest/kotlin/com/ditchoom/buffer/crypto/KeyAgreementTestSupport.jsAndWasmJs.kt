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

// The KA private encoding is now the raw big-endian scalar on web too (generation stores the JWK `d`
// scalar; import stores the raw scalar, wrapped to PKCS#8 only transiently for the WebCrypto
// exchange) — so raw-scalar import is byte-portable here. But the KAT/Wycheproof suites drive the
// *synchronous* witness path, which web lacks (WebCrypto is async-only), so they stay skipped here;
// the web raw-scalar import is covered by the async round-trip test instead (see KeyAgreementTest).
actual fun supportsRawScalarKat(curve: KeyAgreementCurve): Boolean = false
