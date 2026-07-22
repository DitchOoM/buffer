package com.ditchoom.buffer.crypto

/**
 * Kotlin/Native Linux (BoringSSL-backed) wires no non-exportable key backend in this version, so the
 * resolution is honestly [ProtectedKeyResolution.None] — a fact about this library build, distinct
 * (by type, not by `null`) from a wired-but-refused backend. A TPM 2.0 reached through PKCS#11
 * cinterop is the natural future backend here; the **JVM-on-Linux** target already provides one via
 * `tpm2-pkcs11` (see `HardwareKeys.jvm.kt`).
 */
internal actual fun platformProtectedKeyResolution(): ProtectedKeyResolution = ProtectedKeyResolution.None
