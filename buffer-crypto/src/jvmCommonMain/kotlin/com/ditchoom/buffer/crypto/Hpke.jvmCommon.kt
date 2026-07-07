package com.ditchoom.buffer.crypto

/**
 * JVM/Android HPKE capability flags. AES-GCM is synchronously available via JCA, and there is no
 * WebCrypto async path here (the platform is not the web).
 */
internal actual val isWebPlatformKa: Boolean = false
