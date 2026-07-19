package com.ditchoom.buffer.crypto

/*
 * Test-only accessor that resolves the single-block AES (ECB) capability witness to its ops, so the
 * suite can drive it uniformly (Blocking on native, Unavailable on the web → null).
 */

/** The single-block AES ops, or `null` where the primitive is unavailable (web). */
internal fun aesEcbOpsOrNull(): AesEcbOps? =
    when (val w = CryptoCapabilities.aesEcb) {
        is AesEcb.Blocking -> w.ops
        AesEcb.Unavailable -> null
    }
