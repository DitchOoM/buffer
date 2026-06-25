package com.ditchoom.buffer.crypto

/**
 * Linux (BoringSSL-backed) wires no secure-element key provider, so [CryptoCapabilities.hardware]
 * stays [HardwareSupport.Unavailable].
 */
internal actual fun platformHardwareKeyProvider(): HardwareKeyProvider? = null
