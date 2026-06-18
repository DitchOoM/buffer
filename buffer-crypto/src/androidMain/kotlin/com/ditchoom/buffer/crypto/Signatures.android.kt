package com.ditchoom.buffer.crypto

/**
 * Ed25519 is reported **unsupported** on Android — every entry point throws
 * [UnsupportedOperationException] and [supportsSyncEd25519] is `false` — matching the Apple
 * contract. Although Android 14 (API 34+) advertises Ed25519, the platform exposes it **only**
 * through the `AndroidKeyStore` provider for keystore-resident keys: there is no general-purpose
 * `Ed25519` `KeyFactory` that imports a caller-supplied raw 32-byte seed. Verified on an API 36
 * device — the only `Ed25519` KeyFactory is AndroidKeyStore's, which throws "use KeyPairGenerator
 * initialized with KeyGenParameterSpec" on raw import, and Conscrypt (`AndroidOpenSSL`) offers
 * Curve25519 only inside HPKE, not as a standalone Ed25519 Signature/KeyFactory. A buffer-native
 * library that imports raw keys therefore cannot offer Ed25519 on any current Android API level.
 * ECDSA over the NIST P-curves works on every supported API level and is not gated. (Documented
 * deviation from the original SDK_INT >= 34 assumption.)
 */
internal actual val ed25519RuntimeSupported: Boolean
    get() = false
