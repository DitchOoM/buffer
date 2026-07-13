package com.ditchoom.buffer.crypto

/**
 * Browser/Node and WASM expose no secure-element key provider (WebCrypto keys are not a hardware
 * secure element under this SPI's non-exportable contract), so [CryptoCapabilities.hardware] stays
 * [HardwareSupport.Unavailable].
 */
internal actual fun platformProtectedKeyProvider(): ProtectedKeyProvider? = null
