package com.ditchoom.buffer.crypto

// Linux has a synchronous native AES-GCM (BoringSSL EVP_AEAD), so the HPKE AES-GCM path is available.
internal actual val supportsAesGcmAnyPath: Boolean = supportsSyncAesGcm

// Linux key agreement is synchronous and native (not the web WebCrypto path).
internal actual val isWebPlatformKa: Boolean = false
