package com.ditchoom.buffer.crypto

// JVM has a synchronous native KA; the async path delegates to it, so support matches the sync flag.
actual fun asyncAgreementSupported(curve: KeyAgreementCurve): Boolean = supportsSync(curve)

actual val isWebPlatform: Boolean = false

// JCA imports raw private scalars directly (ECPrivateKeySpec / PKCS#8 wrap for XDH).
actual fun supportsRawScalarKat(curve: KeyAgreementCurve): Boolean = supportsSync(curve)
