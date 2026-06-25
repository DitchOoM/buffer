package com.ditchoom.buffer.crypto

// Linux key agreement is synchronous and native (not the web WebCrypto path).
internal actual val isWebPlatformKa: Boolean = false
