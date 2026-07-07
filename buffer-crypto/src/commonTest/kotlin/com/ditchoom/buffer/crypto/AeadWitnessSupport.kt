package com.ditchoom.buffer.crypto

/*
 * Test-only accessors that resolve the AEAD capability witnesses to ops, so the suites can drive
 * AES-GCM / ChaCha20-Poly1305 uniformly across platforms (Blocking on native, AsyncOnly on web).
 */

/** The async AES-GCM ops (available on every platform — Blocking.ops also satisfies AeadAsyncOps). */
internal fun aesGcmAsyncOps(): AeadAsyncOps<AesGcmKey> =
    when (val w = CryptoCapabilities.aesGcm) {
        is Aead.Blocking -> w.ops
        is Aead.AsyncOnly -> w.ops
    }

/** The async ChaCha20-Poly1305 ops, or `null` where ChaCha is unavailable (web). */
internal fun chaChaPolyAsyncOrNull(): AeadAsyncOps<ChaChaPolyKey>? =
    when (val w = CryptoCapabilities.chaChaPoly) {
        is OptionalAead.Blocking -> w.ops
        is OptionalAead.AsyncOnly -> w.ops
        OptionalAead.Unavailable -> null
    }
