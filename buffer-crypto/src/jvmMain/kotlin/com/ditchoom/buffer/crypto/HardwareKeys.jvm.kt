package com.ditchoom.buffer.crypto

/**
 * The desktop JVM wires no secure-element backend: the host has no Android Keystore and no Secure
 * Enclave reachable from this runtime, so [CryptoCapabilities.hardware] stays
 * [HardwareSupport.Unavailable]. (The Android target supplies its own actual.)
 */
internal actual fun platformProtectedKeyProvider(): ProtectedKeyProvider? = null
