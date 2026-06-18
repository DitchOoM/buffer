package com.ditchoom.buffer.crypto

/**
 * Apple HPKE capability flags. AES-GCM is synchronously available via CommonCrypto, and the
 * platform is not the web. (X25519 suites are still gated out by [supportsSyncX25519] = `false`;
 * the ECDH-curve suites are supported.)
 */
internal actual val supportsAesGcmAnyPath: Boolean = supportsSyncAesGcm

internal actual val isWebPlatformKa: Boolean = false
