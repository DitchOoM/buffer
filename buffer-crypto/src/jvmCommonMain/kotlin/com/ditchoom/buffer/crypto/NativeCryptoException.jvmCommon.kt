package com.ditchoom.buffer.crypto

import java.security.GeneralSecurityException

/**
 * On JVM/Android, crypto failures ARE `java.security.GeneralSecurityException` — the natural JCA
 * umbrella — so [CryptoException] and all its subtypes are catchable as `GeneralSecurityException`
 * while remaining a sealed, exhaustively-handleable common hierarchy.
 */
actual typealias NativeCryptoException = GeneralSecurityException
