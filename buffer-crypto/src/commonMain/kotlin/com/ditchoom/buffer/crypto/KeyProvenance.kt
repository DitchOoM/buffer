package com.ditchoom.buffer.crypto

/**
 * Where a key's secret material lives.
 *
 *  - [Software] — the material is an in-memory buffer this process can read (and is responsible
 *    for wiping). Every key constructed via a `.of(...)` factory is [Software].
 *  - [Hardware] — the material is held by a secure element / keystore and is **never** exported to
 *    process memory; the key is an opaque handle. Hardware-backed keys are added in a later
 *    non-breaking minor; in this release no platform can construct one (see the hardware witness),
 *    so [Hardware] is reserved API shape today.
 *
 * Carried on every key type ([AesGcmKey.provenance] etc.) so a caller can branch on whether a key
 * is exportable / software-resident without inspecting the (absent) material.
 */
enum class KeyProvenance {
    Software,
    Hardware,
}
